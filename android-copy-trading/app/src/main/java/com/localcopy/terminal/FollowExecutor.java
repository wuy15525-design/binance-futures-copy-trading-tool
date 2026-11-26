package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.MC;
import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONObject;

final class FollowExecutor {
    interface Exchange {
        FuturesApi.Wallet wallet() throws Exception;
        FuturesApi.Wallet copyWallet() throws Exception;
        FuturesApi.Contract contract(String symbol) throws Exception;
        Rules.Quote quote(String symbol) throws Exception;
        boolean hedgeMode() throws Exception;
        FuturesApi.Position position(String symbol) throws Exception;
        Map<String, FuturesApi.Position> positions() throws Exception;
        Map<String, Set<String>> openOrderIds() throws Exception;
        int allowedLeverage(String symbol, int requested, BigDecimal exposure, BigDecimal margin,
                BigDecimal remainingNotional) throws Exception;
        default int allowedForNotional(String symbol, int requested, BigDecimal exposure, BigDecimal notional)
                throws Exception {
            // A bracket without room for the entire notional must yield no leverage for this order.
            return allowedLeverage(symbol, requested, exposure, notional, notional);
        }
        int currentLeverage(String symbol) throws Exception;
        int changeLeverage(String symbol, int leverage) throws Exception;
        default void submit(String symbol, Rules.Action action, boolean hedge, BigDecimal quantity,
                BigDecimal limit, String id) throws Exception {
            submit(symbol, action, hedge, quantity, limit, id, false);
        }
        void submit(String symbol, Rules.Action action, boolean hedge, BigDecimal quantity,
                BigDecimal limit, String id, boolean fullFill) throws Exception;
        JSONObject order(String symbol, String id) throws Exception;
        FuturesApi.Fills fills(String symbol, long orderId) throws Exception;
        List<FuturesApi.AccountTrade> tradesSince(String symbol, long since) throws Exception;
    }

    interface Save { void write() throws Exception; }
    interface Notify { void event(String id, String message, boolean sound); }
    interface ArchiveFill { void write(String id, long time, String message) throws Exception; }

    static final class JournalFailure extends Exception {
        JournalFailure(Exception cause) { super("跟单记录无法安全写入，已停止下单。", cause); }
    }

    private static final BigDecimal FEE_BUFFER = new BigDecimal("0.001");
    private static final long CONTRACT_CACHE_MS = 240000;
    private static final long LOST_ORDER_GRACE_MS = 30000;
    private static final long CHECK_RETRY_MS = 3000;
    private static final long MAX_COPY_CHECK_AGE = 10000;
    private static final long IOC_RETRY_MS = 5000;
    private static final String LIMIT_UNFILLED = "上一笔限价委托已确认未成交，稍后按最新盘口重试";
    private static final String FULL_FILL_UNFILLED = "盘口未能整笔成交，等待足量盘口使均价回到范围";
    private final FollowBook book;
    private final Exchange api;
    private final Save save;
    private final Notify notify;
    private final Predicate<String> waitActive;
    private final ArchiveFill archiveFill;
    private final String sourcePriceLabel;
    private final boolean allowManual;
    private final Map<String, FuturesApi.Contract> contracts = new HashMap<>();
    private long catalogAt, walletRevision;
    private final Map<String, CopyRetry> copyRetries = new HashMap<>();
    private final Map<String, CopyLeverageCap> copyLeverageCaps = new HashMap<>();
    private volatile CopyChecks copyChecks;

    FollowExecutor(FollowBook book, Exchange api, Save save, Notify notify) {
        this(book, api, save, notify, id -> true);
    }

    FollowExecutor(FollowBook book, Exchange api, Save save, Notify notify, Predicate<String> waitActive) {
        this(book, api, save, notify, waitActive, (id, time, message) -> {}, "带单员成交均价");
    }

    FollowExecutor(FollowBook book, Exchange api, Save save, Notify notify, Predicate<String> waitActive,
            ArchiveFill archiveFill, String sourcePriceLabel) {
        this(book, api, save, notify, waitActive, archiveFill, sourcePriceLabel, true);
    }

    FollowExecutor(FollowBook book, Exchange api, Save save, Notify notify, Predicate<String> waitActive,
            ArchiveFill archiveFill, String sourcePriceLabel, boolean allowManual) {
        this.book = book;
        this.api = api;
        this.save = save;
        this.notify = notify;
        this.waitActive = waitActive;
        this.archiveFill = archiveFill;
        this.sourcePriceLabel = sourcePriceLabel;
        this.allowManual = allowManual;
    }

    void prepareCopyAdjustmentsWith(java.util.function.Supplier<Exchange> reader, Runnable onReady) {
        copyChecks = new CopyChecks(reader, onReady);
    }

    void closeCopyChecks() {
        CopyChecks checks = copyChecks;
        if (checks != null) checks.close();
    }

    void commit() throws JournalFailure {
        try { save.write(); }
        catch (Exception error) { throw new JournalFailure(error); }
    }

    void flushNotice() throws Exception {
        if (book.notice == null) return;
        String text = book.notice.getString("text");
        boolean legacyFill = text.contains("已成交 · ") || text.contains("部分成交 · ");
        if (book.notice.optBoolean("fill", legacyFill)) notify.event(book.notice.getString("id"), text, true);
        book.notice = null;
        commit();
    }

    FuturesApi.Contract limits(String symbol) throws Exception {
        long now = System.currentTimeMillis();
        if (catalogAt == 0 || now < catalogAt || now - catalogAt >= CONTRACT_CACHE_MS) {
            contracts.clear();
            catalogAt = now;
        }
        FuturesApi.Contract found = contracts.get(symbol);
        if (found == null) {
            found = api.contract(symbol);
            contracts.put(symbol, found);
        }
        return found;
    }

    String tick(Rules.Config current, BooleanSupplier canTrade, BooleanSupplier canOpen) throws Exception {
        flushNotice();
        if (book.pending != null) return reconcile();
        return decide(current, canTrade, canOpen);
    }

    void refreshAccount(long at) throws Exception {
        syncAccount(api.positions(), at);
    }

    boolean syncAccount(Map<String, FuturesApi.Position> positions, long at) throws Exception {
        if (!allowManual || book.pending != null) return false;
        boolean changed = !book.accountSeeded;
        for (String symbol : book.config.symbols) {
            changed |= syncPosition(symbol, positionOrFlat(positions, symbol), at);
        }
        if (changed) {
            book.accountSeeded = true;
            commit();
        }
        return changed;
    }

    private boolean syncPosition(String symbol, FuturesApi.Position actual, long at) throws Exception {
        if (!allowManual) return false;
        FollowBook.Leg longs = book.leg(symbol, true);
        FollowBook.Leg shorts = book.leg(symbol, false);
        boolean first = longs.observed == null || shorts.observed == null;
        longs.seedObservation(actual.longs, at);
        shorts.seedObservation(actual.shorts, at);
        boolean moved = longs.observed.compareTo(actual.longs) != 0 || shorts.observed.compareTo(actual.shorts) != 0;
        if (moved) {
            long since = Math.min(longs.observedAt, shorts.observedAt);
            AccountMovement verified = movement(symbol, since, longs.observed, shorts.observed, actual, -1, ZERO);
            recordManual(longs, actual.longs, verified, at);
            recordManual(shorts, actual.shorts, verified, at);
        }
        long observation = Math.max(at, Math.max(longs.observedAt, shorts.observedAt));
        longs.observedAt = shorts.observedAt = observation;
        return first || moved;
    }

    private AccountMovement movement(String symbol, long since, BigDecimal longs, BigDecimal shorts,
            FuturesApi.Position actual, long ownOrder, BigDecimal ownQuantity) {
        if (since <= 0) return null;
        try {
            return AccountMovement.verify(api.tradesSince(symbol, since), longs, shorts, actual, ownOrder, ownQuantity);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private void recordManual(FollowBook.Leg leg, BigDecimal actual, AccountMovement verified, long at)
            throws org.json.JSONException {
        BigDecimal previous = leg.observed;
        BigDecimal delta = actual.subtract(previous);
        boolean closed = verified == null ? delta.signum() < 0 : verified.closed(leg.isLong);
        BigDecimal pnl = verified != null && closed ? verified.pnl(leg.isLong) : null;
        boolean managed = !leg.protectedExisting && (leg.managed || actual.signum() > 0);
        if (closed && managed) {
            if (pnl == null) book.pnlIncomplete = true;
            else book.realizedPnl = book.realizedPnl.add(pnl);
        }
        if (delta.signum() != 0) {
            String side = leg.isLong ? "多" : "空";
            String action = delta.signum() > 0 ? (previous.signum() == 0 ? "开" : "加")
                    : (actual.signum() == 0 ? "平" : "减");
            String unit = Rules.base(leg.symbol);
            book.log("手动操作 · " + leg.symbol + " " + action + side + " · 变动 " + Rules.show(delta.abs())
                    + " " + unit + " · 持仓 " + Rules.show(actual) + " " + unit
                    + "\n检测 " + TradeNotice.fullTime(at)
                    + (closed ? " · " + TradeNotice.pnl(pnl) : "") + (!managed ? " · 启动前仓位暂未纳入跟单" : ""));
            leg.matchManualChange(actual);
        }
        leg.observed = actual;
        leg.observedAt = at;
    }

    private Set<String> reviewingLegs = Collections.emptySet();

    void holdTradeReviews(Set<String> legs) { reviewingLegs = legs; }

    private boolean reviewing(FollowBook.Leg leg) {
        return reviewingLegs.contains(leg.symbol + "|" + (leg.isLong ? "LONG" : "SHORT"));
    }

    String decide(Rules.Config current, BooleanSupplier canTrade, BooleanSupplier canOpen) throws Exception {
        if (book.pending != null) return "等待核对原委托号";
        boolean copy = book.strategy == Rules.Strategy.EXPOSURE;
        BooleanSupplier permitOpen = () -> canTrade.getAsBoolean() && canOpen.getAsBoolean();
        String wait = "等待名单内的新成交";
        List<FollowBook.Leg> legs = new ArrayList<>(book.legs.values());
        for (FollowBook.Leg leg : legs) {
            if (!leg.needsReduction() || reviewing(leg)) continue;
            if (!canTrade.getAsBoolean()) return "等待有效官网快照，未发平仓委托";
            try {
                long checkedAt = System.currentTimeMillis();
                FuturesApi.Position actual = api.position(leg.symbol);
                if (syncPosition(leg.symbol, actual, checkedAt)) commit();
                if (leg.protectedExisting) {
                    leg.matchActualForReduction(ZERO);
                    wait = leg.symbol + " 启动前仓位暂未纳入跟单，已跳过平仓";
                    book.log(wait);
                    commit();
                    continue;
                }
                Rules.Action close = leg.closeAction();
                BigDecimal available = actual.size(close);
                FollowBook.Reduction next = leg.nextReduction();
                BigDecimal copyReduction = null;
                if (copy && next != null && !next.byPosition && next.retention.signum() > 0 && available.signum() > 0) {
                    BigDecimal ratio = next.signal.exposureRatio();
                    if (ratio == null) {
                        wait = leg.symbol + " 等待减仓成交额或带单保证金余额";
                        continue;
                    }
                    BigDecimal balance = api.copyWallet().balance;
                    if (balance.signum() <= 0) {
                        wait = leg.symbol + " 本账户保证金余额不足以换算减仓，等待核对";
                        continue;
                    }
                    copyReduction = balance.multiply(ratio, MC).multiply(book.copyFactor, MC)
                            .divide(api.quote(leg.symbol).execution(close), MC);
                }
                leg.matchActualForReduction(available, copyReduction);
                if (available.signum() == 0) {
                    wait = leg.symbol + " 本账户无对应" + (leg.isLong ? "多" : "空") + "仓，已跳过平仓";
                    book.log(wait);
                    commit();
                    continue;
                }
                commit();
                BigDecimal due = available.subtract(leg.target).max(ZERO);
                if (due.signum() <= 0) continue;
                FuturesApi.Contract contract = limits(leg.symbol);
                BigDecimal quantity = Rules.floor(due.min(contract.marketMax), contract.marketStep);
                if (quantity.compareTo(contract.marketMin) < 0) {
                    wait = leg.symbol + " 待减仓量低于市价单最小数量";
                    if (leg.target.signum() > 0) {
                        leg.target = available;
                        leg.reducingSignal = null;
                        if (copy) wait = deferCopy(leg, wait);
                        else {
                            book.log(wait);
                            commit();
                        }
                    }
                    continue;
                }
                boolean hedge = api.hedgeMode();
                if (!canTrade.getAsBoolean()) return "本轮停止前未发送平仓委托";
                BigDecimal otherSide = leg.isLong ? actual.shorts : actual.longs;
                return send(leg, close, hedge, quantity, null, null, otherSide, 0, checkedAt, canTrade, false, false);
            } catch (FuturesApi.UnavailableSymbol unavailable) {
                wait = unavailable.getMessage();
            } catch (FuturesApi.Failure failure) {
                if (!failure.unavailableSymbol()) throw failure;
                wait = leg.symbol + " 被币安拒绝（" + failure.code + "）";
            }
        }
        if (book.copyReplanPending) return "等待官网与账户核对后应用复制系数";
        AdjustmentStep adjusting = decideCopyAdjustments(current, canTrade, permitOpen);
        if (adjusting.acted) return adjusting.message;
        if (!adjusting.message.isEmpty()) wait = adjusting.message;
        if (!permitOpen.getAsBoolean()) return "暂停开仓，继续核对平仓信号";
        for (FollowBook.Leg leg : legs) {
            if (leg.entries.isEmpty() || reviewing(leg)) continue;
            if (leg.needsReduction() || leg.adjusting()) {
                String reason = leg.needsReduction() ? "等待同方向减仓核对" : "等待同方向复制目标核对";
                for (FollowBook.Entry entry : leg.entries) {
                    if (!entry.cancelled) recordOpenWait(entry, reason);
                }
                continue;
            }
            if (!permitOpen.getAsBoolean()) return "新开仓会等下一次有效快照";
            for (Iterator<FollowBook.Entry> entries = leg.entries.iterator(); entries.hasNext();) {
                FollowBook.Entry entry = entries.next();
                if (entry.cancelled || !waitActive.test(entry.id())) continue;
                long now = System.currentTimeMillis();
                if (recentlyUnfilled(entry.lastUnfilled, now)) {
                    wait = leg.symbol + " " + entry.waitReason;
                    continue;
                }
                if (entry.waitingForCheckRetry(now)) {
                    wait = entry.signal.symbol + " 开仓检查待重试 · 最早 " + TradeNotice.time(entry.checkRetryAt);
                    continue;
                }
                BooleanSupplier canSend = () -> permitOpen.getAsBoolean() && waitActive.test(entry.id());
                String stage = "开仓检查";
                try {
                    String symbol = leg.symbol;
                    Rules.Action action = entry.signal.action;
                    Rules.ContractRule rule = current.rule(symbol);
                    BigDecimal multiple = copy ? entry.signal.exposureRatio() : null;
                    if (copy && multiple == null && entry.nominalLeft == null) {
                        wait = symbol + " 等待开仓成交额或带单保证金余额";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    if (entry.existing && leg.protectedExisting) {
                        entries.remove();
                        wait = symbol + " 跳过启动前已持有的同方向仓位";
                        book.log(wait);
                        commit();
                        continue;
                    }
                    FuturesApi.Contract contract;
                    stage = "开仓检查（合约规格）";
                    try { contract = limits(symbol); }
                    catch (IllegalArgumentException unsupported) {
                        wait = unsupported instanceof FuturesApi.UnavailableSymbol ? unsupported.getMessage()
                                : symbol + " 此环境无可交易的该合约";
                        recordOpenWait(entry, wait);
                        break;
                    }
                    String endReason = unfillableRemainder(entry, contract, rule.leverage);
                    if (endReason != null) {
                        wait = leg.symbol + " " + (entry.existing ? "补仓" : "跟单开仓") + "结束 · " + endReason;
                        if (copy) {
                            wait = deferOpeningCopy(leg, entry, wait);
                            break;
                        }
                        entries.remove();
                        book.log(wait);
                        commit();
                        continue;
                    }
                    if (book.leaderAveragesVerified && leg.leaderAverage == null) {
                        wait = symbol + " 等待核对带单仓位均价";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    BigDecimal leaderAverage = leg.leaderAverage;
                    BigDecimal slippage = entry.usesAdjustmentSlippage() ? book.copySlippage : rule.slippagePercent;
                    BigDecimal boundary = leaderAverage == null
                            ? Rules.entryLimit(action, entry.referencePrice(), slippage, contract.tick) : null;
                    stage = "开仓检查（盘口）";
                    Rules.Quote quote = api.quote(symbol);
                    if (boundary != null && !Rules.acceptable(action, boundary, quote)) {
                        wait = symbol + " 不利价差超限，继续等待";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    stage = "开仓检查（持仓模式）";
                    boolean hedge = api.hedgeMode();
                    long checkedAt = System.currentTimeMillis();
                    stage = "开仓检查（账户持仓）";
                    FuturesApi.Position venue = api.position(symbol);
                    if (allowManual) {
                        if (syncPosition(symbol, venue, checkedAt)) commit();
                    } else verifyPosition(symbol, venue);
                    if (!hedge && (leg.isLong ? venue.shorts : venue.longs).signum() > 0) {
                        wait = symbol + " 单向持仓模式下有反向仓位，等待平仓";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    stage = "开仓检查（保证金余额）";
                    FuturesApi.Wallet wallet = copy ? api.copyWallet() : api.wallet();
                    stage = "开仓检查（复核盘口）";
                    quote = api.quote(symbol);
                    BigDecimal nominal = entry.nominalLeft;
                    BigDecimal budget = entry.margin;
                    if (copy && nominal == null) {
                        nominal = wallet.balance.max(ZERO).multiply(multiple, MC)
                                .multiply(book.copyFactor, MC).multiply(entry.fraction, MC);
                    }
                    if (!copy && budget == null) budget = rule.orderMargin(wallet.balance).multiply(entry.fraction, MC);
                    if ((copy ? nominal : budget).signum() <= 0) {
                        wait = symbol + " 本账户保证金余额不足，继续等待";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    if (copy && nominal.compareTo(contract.minValue.max(entry.reportedMinimum)) < 0) {
                        wait = deferOpeningCopy(leg, entry, symbol + " 复制金额低于最小委托金额");
                        break;
                    }
                    stage = "查询杠杆档位";
                    BigDecimal reference = quote.ask.max(quote.bid);
                    int heldLeverage = venue.flat() ? 0 : api.currentLeverage(symbol);
                    if (!venue.flat() && heldLeverage < 1) {
                        wait = symbol + " 当前持仓杠杆待核对";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    int sizing = venue.flat() ? rule.leverage : Math.min(rule.leverage, heldLeverage);
                    int requested = venue.flat() ? sizing : heldLeverage;
                    BigDecimal exposure = venue.exposure(reference);
                    int leverage;
                    if (copy) {
                        leverage = api.allowedForNotional(symbol, requested, exposure, nominal);
                    } else {
                        BigDecimal bracketMargin = venue.flat() ? budget : budget.multiply(BigDecimal.valueOf(sizing))
                                .divide(BigDecimal.valueOf(heldLeverage), MC);
                        leverage = api.allowedLeverage(symbol, requested, exposure, bracketMargin,
                                entry.remaining == null ? null : entry.remaining.multiply(reference));
                    }
                    if (leverage < Math.max(1, heldLeverage)) {
                        wait = symbol + " 持仓及本单金额超过杠杆档位的名义价值上限";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    if (copy) budget = nominal.divide(BigDecimal.valueOf(leverage), MC);
                    Offer offer = offer(entry, quote, wallet, contract, boundary, budget,
                            copy || venue.flat() ? leverage : sizing, leverage, nominal);
                    if (offer.quantity.signum() == 0) {
                        wait = symbol + " " + offer.wait;
                        if (copy && offer.tooSmall) {
                            wait = deferOpeningCopy(leg, entry, wait);
                            break;
                        }
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    boolean fullFill = false;
                    if (leaderAverage != null) {
                        String issue = Rules.openingAverageIssue(action, venue, offer.quantity, offer.price,
                                leaderAverage, slippage);
                        if (!issue.isEmpty()) {
                            wait = symbol + " " + issue;
                            recordOpenWait(entry, wait);
                            continue;
                        }
                        fullFill = Rules.openingNeedsFullFill(action, venue, leaderAverage, slippage);
                    }
                    if (!canSend.getAsBoolean()) return "本轮未发开仓委托";
                    stage = "查询当前杠杆";
                    if (venue.flat() && api.currentLeverage(symbol) != leverage) {
                        long recheckedAt = System.currentTimeMillis();
                        FuturesApi.Position rechecked = api.position(symbol);
                        if (!rechecked.flat()) {
                            if (syncPosition(symbol, rechecked, recheckedAt)) commit();
                            wait = symbol + " 已有持仓，下一轮沿用实际杠杆";
                            recordOpenWait(entry, wait);
                            return wait;
                        }
                        if (!canSend.getAsBoolean()) return "本轮未调整杠杆";
                        stage = "调整杠杆";
                        invalidateCopyChecks(symbol);
                        int confirmed = api.changeLeverage(symbol, leverage);
                        String message = symbol + " 杠杆已确认 " + confirmed + " 倍"
                                + (confirmed < rule.leverage ? "（预设 " + rule.leverage + " 倍）" : "");
                        entry.clearCheckRetry();
                        entry.requestIssue = "";
                        entry.waitReason = "杠杆已确认，下轮检查最新盘口";
                        book.log(message);
                        commit();
                        return message + "，下轮检查最新盘口";
                    }
                    if (leaderAverage != null && !venue.sameOpeningBasis(api.position(symbol), action)) {
                        wait = symbol + " 本账户仓位或均价已变化，下轮重算";
                        recordOpenWait(entry, wait);
                        continue;
                    }
                    if (entry.remaining == null || copy) {
                        entry.margin = offer.budget;
                        entry.nominalLeft = nominal;
                        entry.remaining = offer.target;
                    }
                    if (entry.clearCheckRetry()) book.log("开仓检查已恢复 · " + symbol + " " + action.label
                            + " · 原信号 " + TradeNotice.fullTime(entry.signal.time));
                    stage = "开仓";
                    BigDecimal otherSide = leg.isLong ? venue.shorts : venue.longs;
                    return send(leg, action, hedge, offer.quantity, offer.price, entry, otherSide,
                            leverage, checkedAt, canSend, false, fullFill);
                } catch (FuturesApi.UnavailableSymbol unavailable) {
                    wait = unavailable.getMessage();
                    recordOpenWait(entry, wait);
                    break;
                } catch (FuturesApi.Failure failure) {
                    if (book.pending == null && retryableCheck(failure)) {
                        wait = retryCheck(entry, stage + "请求失败 · " + failureText(failure));
                        continue;
                    }
                    recordIssue(entry, stage + "请求失败 · " + failure.getMessage());
                    if (!failure.unavailableSymbol()) throw failure;
                    wait = leg.symbol + " 被币安拒绝（" + failure.code + "）";
                    break;
                } catch (java.io.IOException | org.json.JSONException | IllegalArgumentException | IllegalStateException failure) {
                    if (book.pending == null && retryableCheck(failure)) {
                        String label = "开仓".equals(stage) ? "开仓委托未发送" : stage + "请求失败";
                        wait = retryCheck(entry, label + " · " + failureText(failure));
                        continue;
                    }
                    if (book.pending == null) recordIssue(entry, stage + "请求失败 · " + failureText(failure));
                    throw failure;
                }
            }
        }
        if ("等待名单内的新成交".equals(wait) && legs.stream().anyMatch(leg -> leg.adjustOnSignal)) {
            return "复制目标等待对应方向下一笔带单成交";
        }
        return wait;
    }

    static final class AdjustmentStep {
        final String message;
        final boolean acted;

        AdjustmentStep(String message) { this(message, false); }
        AdjustmentStep(String message, boolean acted) { this.message = message; this.acted = acted; }
    }

    AdjustmentStep decideCopyAdjustments(Rules.Config current, BooleanSupplier canTrade, BooleanSupplier canOpen)
            throws Exception {
        return decideCopyAdjustments(current, canTrade, canOpen, true);
    }

    AdjustmentStep finishReadyCopyAdjustments(Rules.Config current, BooleanSupplier canTrade, BooleanSupplier canOpen)
            throws Exception {
        return decideCopyAdjustments(current, canTrade, canOpen, false);
    }

    private AdjustmentStep decideCopyAdjustments(Rules.Config current, BooleanSupplier canTrade,
            BooleanSupplier canOpen, boolean scheduleChecks) throws Exception {
        if (book.pending != null) return new AdjustmentStep("等待核对原委托号");
        if (book.copyReplanPending) return new AdjustmentStep("等待官网与账户核对后应用复制系数");
        if (!scheduleChecks && copyChecks == null) return new AdjustmentStep("");
        List<CopyPlan.Request> requests = new ArrayList<>();
        Map<String, String> revisions = new HashMap<>();
        List<String> messages = new ArrayList<>();
        Set<String> attempted = new LinkedHashSet<>();
        for (FollowBook.Leg leg : book.legs.values()) {
            if (leg.adjustment == null || leg.adjustOnSignal || leg.needsReduction() || reviewing(leg)) continue;
            if (recentlyUnfilled(leg.adjustment.lastUnfilled, System.currentTimeMillis())) {
                messages.add(leg.symbol + " " + leg.adjustment.waitReason);
                continue;
            }
            CopyPlan.Request request = new CopyPlan.Request(book, leg, current.rule(leg.symbol),
                    walletRevision, confirmedCopyLeverage(leg.symbol));
            if (!waitActive.test(leg.adjustment.id())) {
                messages.add(request.label + "等待取消确认");
                continue;
            }
            if (leg.adjustment.leaderMargin() == null) {
                String reason = request.label + "等待带单保证金余额";
                messages.add(reason);
                recordCopyWait(leg, reason);
                continue;
            }
            if (!canTrade.getAsBoolean()) return new AdjustmentStep("等待有效官网快照，暂缓复制委托");
            revisions.put(request.key, request.revision);
            requests.add(request);
        }
        if (copyChecks != null) copyChecks.discardExcept(revisions);
        copyRetries.keySet().retainAll(revisions.keySet());
        boolean acted = false;
        for (CopyPlan.Request request : requests) {
            CopyRetry retry = copyRetries.get(request.key);
            if (retry != null && !retry.revision.equals(request.revision)) {
                copyRetries.remove(request.key);
                retry = null;
            }
            if (retry != null && System.currentTimeMillis() < retry.until) {
                attempted.add(request.key);
                messages.add(retry.message);
                continue;
            }
            FollowBook.Leg leg = book.leg(request.symbol, request.isLong);
            try {
                CopyPlan plan = copyChecks == null ? CopyPlan.prepare(request, api, canOpen.getAsBoolean(), FEE_BUFFER)
                        : copyChecks.take(request);
                if (plan == null) continue;
                attempted.add(request.key);
                AdjustmentStep step = applyCopyPlan(leg, plan, canTrade, canOpen);
                copyRetries.remove(request.key);
                if (step.acted) messages.add(0, step.message);
                else {
                    messages.add(step.message);
                    recordCopyWait(leg, step.message);
                }
                acted |= step.acted;
                if (book.pending != null) break;
            } catch (JournalFailure fatal) {
                throw fatal;
            } catch (Exception problem) {
                if (book.pending != null || invalidCredentials(problem)
                        || problem instanceof javax.net.ssl.SSLException || problem instanceof InterruptedException) throw problem;
                attempted.add(request.key);
                String issue = copyCheckFailed(request, retry, problem);
                messages.add(issue);
                recordCopyWait(leg, issue);
            }
        }
        if (scheduleChecks && copyChecks != null && book.pending == null && canTrade.getAsBoolean()) {
            copyChecks.schedule(requests, attempted, canOpen.getAsBoolean(), FEE_BUFFER);
        }
        if (scheduleChecks && messages.isEmpty() && !requests.isEmpty()) messages.add("正在按合约检查 " + requests.size() + " 项复制委托");
        int shown = Math.min(3, messages.size());
        String summary = String.join("\n", messages.subList(0, shown));
        if (shown < messages.size()) summary += "\n另外 " + (messages.size() - shown) + " 项复制委托";
        return new AdjustmentStep(summary, acted);
    }

    private AdjustmentStep applyCopyPlan(FollowBook.Leg leg, CopyPlan plan, BooleanSupplier canTrade,
            BooleanSupplier canOpen) throws Exception {
        CopyPlan.Request request = plan.request;
        FollowBook.CopyAdjustment adjustment = leg.adjustment;
        if (adjustment == null || !waitActive.test(adjustment.id()) || !canTrade.getAsBoolean()) {
            return new AdjustmentStep(request.label + "等待新一轮核对");
        }
        if (plan.position != null) {
            long age = System.currentTimeMillis() - plan.checkedAt;
            FollowBook.Leg otherLeg = book.leg(leg.symbol, !leg.isLong);
            if (copyChecks != null && (age < 0 || age > MAX_COPY_CHECK_AGE
                    || positionChangedSince(leg, plan.position, plan.checkedAt)
                    || positionChangedSince(otherLeg, plan.position, plan.checkedAt))) {
                return new AdjustmentStep(request.label + "仓位快照已更新，重新检查");
            }
            if (syncPosition(leg.symbol, plan.position, plan.checkedAt)) commit();
            if (leg.protectedExisting && plan.result != CopyPlan.Result.FINISH) {
                leg.clearAdjustment();
                String detail = request.label + "启动前仓位暂未纳入跟单，已结束本次委托";
                book.log(detail);
                commit();
                return new AdjustmentStep(detail);
            }
        }
        boolean updated = false;
        if (plan.target != null && adjustment.target == null) {
            adjustment.target = plan.target;
            if (plan.migratedCopied != null) {
                leg.copied = plan.migratedCopied;
                leg.legacyCopyScope = false;
                adjustment.legacyVisible = null;
            }
            updated = true;
        }
        if (plan.reference != null && adjustment.reference == null) {
            adjustment.reference = plan.reference;
            updated = true;
        }
        if (updated) commit();
        if (plan.result == CopyPlan.Result.FINISH) {
            leg.clearAdjustment();
            book.log(plan.message);
            commit();
        } else if (plan.result == CopyPlan.Result.DEFER) {
            return new AdjustmentStep(deferCopy(leg, plan.message));
        } else if (plan.result == CopyPlan.Result.SEND || plan.result == CopyPlan.Result.SET_LEVERAGE) {
            BooleanSupplier permitted = () -> canTrade.getAsBoolean() && waitActive.test(adjustment.id())
                    && (!plan.action.opens || canOpen.getAsBoolean());
            if (!permitted.getAsBoolean()) return new AdjustmentStep(request.label + "委托已暂停");
            if (plan.action.opens && request.walletRevision != walletRevision
                    && api.copyWallet().available.compareTo(plan.required) < 0) {
                return new AdjustmentStep(request.label + "可用保证金已变化，等待重算");
            }
            if (!permitted.getAsBoolean()) return new AdjustmentStep(request.label + "委托已暂停");
            if (plan.result == CopyPlan.Result.SET_LEVERAGE) {
                invalidateCopyChecks(leg.symbol);
                int confirmed;
                try {
                    confirmed = api.changeLeverage(leg.symbol, plan.leverage);
                } catch (FuturesApi.Failure rejected) {
                    int cap = rejected.statedLeverageCap();
                    if (cap <= 0 || cap >= plan.leverage) throw rejected;
                    rememberCopyLeverage(leg.symbol, cap);
                    String detail = request.label + "币安限制为 " + cap + " 倍，按实际杠杆重算";
                    adjustment.waitReason = detail;
                    book.log(detail);
                    commit();
                    return new AdjustmentStep(detail, true);
                }
                if (confirmed < plan.leverage) rememberCopyLeverage(leg.symbol, confirmed);
                String detail = request.label + "杠杆已确认 " + confirmed + " 倍";
                adjustment.waitReason = detail;
                book.log(detail);
                commit();
                return new AdjustmentStep(detail, true);
            }
            if (plan.action.opens && request.leaderAverage != null
                    && !plan.position.sameOpeningBasis(api.position(leg.symbol), plan.action)) {
                return new AdjustmentStep(request.label + "本账户仓位或均价已变化，下轮重算");
            }
            BigDecimal other = leg.isLong ? plan.position.shorts : plan.position.longs;
            return new AdjustmentStep(send(leg, plan.action, plan.hedge, plan.quantity, plan.price,
                    null, other, plan.leverage, plan.checkedAt, permitted, true, plan.fullFill), true);
        }
        return new AdjustmentStep(plan.message);
    }

    private static boolean recentlyUnfilled(long last, long now) {
        return last > 0 && now >= last && now - last < IOC_RETRY_MS;
    }

    private static final class CopyLeverageCap {
        final int maximum;
        final long checkedAt = System.currentTimeMillis();

        CopyLeverageCap(int maximum) { this.maximum = maximum; }
    }

    private int confirmedCopyLeverage(String symbol) {
        CopyLeverageCap cap = copyLeverageCaps.get(symbol);
        long age = cap == null ? -1 : System.currentTimeMillis() - cap.checkedAt;
        return age >= 0 && age < CONTRACT_CACHE_MS ? cap.maximum : Integer.MAX_VALUE;
    }

    private void rememberCopyLeverage(String symbol, int cap) {
        copyLeverageCaps.put(symbol, new CopyLeverageCap(Math.min(cap, confirmedCopyLeverage(symbol))));
    }

    private void recordCopyWait(FollowBook.Leg leg, String reason) throws JournalFailure {
        if (leg.adjustment != null && !reason.equals(leg.adjustment.waitReason)) {
            leg.adjustment.waitReason = reason;
            commit();
        }
    }

    private static boolean positionChangedSince(FollowBook.Leg leg, FuturesApi.Position position, long at) {
        return leg.observedAt > at && leg.observed != null && leg.observed.compareTo(position.size(leg.closeAction())) != 0;
    }

    private static final class CopyRetry {
        final String revision, message;
        final long until;
        final int attempts;

        CopyRetry(String revision, String message, long until, int attempts) {
            this.revision = revision;
            this.message = message;
            this.until = until;
            this.attempts = attempts;
        }
    }

    private String copyCheckFailed(CopyPlan.Request request, CopyRetry before, Exception error) throws Exception {
        int attempts = before == null ? 1 : Math.min(4, before.attempts + 1);
        String issue = request.label + "检查待重试 · " + failureText(error);
        if (before == null || !issue.equals(before.message)) {
            book.log(issue);
            commit();
        }
        copyRetries.put(request.key, new CopyRetry(request.revision, issue,
                System.currentTimeMillis() + (CHECK_RETRY_MS << (attempts - 1)), attempts));
        return issue;
    }

    static boolean invalidCredentials(Exception problem) {
        if (!(problem instanceof FuturesApi.Failure)) return false;
        FuturesApi.Failure failure = (FuturesApi.Failure) problem;
        return failure.status == 401 || failure.code == -2015 || failure.code == -1022;
    }

    private void invalidateCopyChecks(String symbol) {
        if (copyChecks != null) copyChecks.invalidate(symbol);
    }

    private String deferCopy(FollowBook.Leg leg, String reason) throws Exception {
        leg.resetCopyOrders("");
        leg.adjustOnSignal = leg.leader.signum() > 0 && leg.copied.signum() > 0;
        if (!leg.adjustOnSignal) leg.clearAdjustment();
        else if (leg.adjustment == null || !leg.adjustment.fromSetting()) leg.adjustment = null;
        String message = reason + (leg.adjustOnSignal ? " · 下次同方向带单成交重新核算" : "");
        book.log(message);
        commit();
        return message;
    }

    private String deferOpeningCopy(FollowBook.Leg leg, FollowBook.Entry skipped, String reason) throws Exception {
        for (FollowBook.Entry entry : leg.entries) {
            if (entry.usesAdjustmentSlippage() && !entry.cancelled && waitActive.test(entry.id())) {
                leg.deferredAdjustmentSlippage = true;
                break;
            }
        }
        if (!leg.legacyCopyScope) {
            for (int i = leg.entries.size() - 1, first = leg.entries.indexOf(skipped); i > first; i--) {
                FollowBook.Entry later = leg.entries.get(i);
                if (later.existing || later.cancelled || !waitActive.test(later.id())) continue;
                leg.stageAdjustment(later.signal, ZERO, Rules.ONE, ZERO);
                String message = reason + " · 按同批后续成交重新核算";
                book.log(message);
                commit();
                return message;
            }
        }
        return deferCopy(leg, reason);
    }

    private static String unfillableRemainder(FollowBook.Entry entry, FuturesApi.Contract contract, int preset) {
        if (entry.remaining == null) return null;
        boolean copy = entry.nominalLeft != null;
        String remaining = "剩余 " + Rules.show(entry.remaining) + " " + Rules.base(entry.signal.symbol);
        if (!copy) {
            BigDecimal quantity = contract.openingQuantity(entry.remaining.min(contract.lotMax));
            if (quantity.signum() == 0) {
                return remaining + "，低于最小委托量 " + Rules.show(contract.lotMin) + " " + Rules.base(entry.signal.symbol);
            }
            if (quantity.compareTo(entry.rejectedQuantity) <= 0) {
                return remaining + "，交易所已确认该数量低于最小委托量（-4004）";
            }
        }
        BigDecimal maximumValue = copy ? entry.nominalLeft : entry.margin.multiply(BigDecimal.valueOf(preset));
        BigDecimal minimum = contract.minValue.max(entry.reportedMinimum);
        if (maximumValue.compareTo(minimum) < 0 || maximumValue.compareTo(entry.rejectedNotional) <= 0) {
            return copy ? "剩余名义金额 " + Rules.show(maximumValue) + " USDT，低于委托限制"
                    : remaining + "，剩余保证金在预设杠杆内无法达到最小委托金额";
        }
        return null;
    }

    static final class Offer {
        final BigDecimal quantity, price, target, budget;
        final String wait;
        final boolean tooSmall;
        Offer(String wait) { this(ZERO, ZERO, ZERO, ZERO, wait); }
        Offer(String wait, boolean tooSmall) { this(ZERO, ZERO, ZERO, ZERO, wait, tooSmall); }
        Offer(BigDecimal quantity, BigDecimal price, BigDecimal target, BigDecimal budget, String wait) {
            this(quantity, price, target, budget, wait, false);
        }
        Offer(BigDecimal quantity, BigDecimal price, BigDecimal target, BigDecimal budget, String wait, boolean tooSmall) {
            this.quantity = quantity;
            this.price = price;
            this.target = target;
            this.budget = budget;
            this.wait = wait;
            this.tooSmall = tooSmall;
        }
    }

    static Offer offer(FollowBook.Entry entry, Rules.Quote quote, FuturesApi.Wallet wallet,
            FuturesApi.Contract contract, BigDecimal boundary, BigDecimal budget, int leverage) {
        return offer(entry, quote, wallet, contract, boundary, budget, leverage, leverage, null);
    }

    private static Offer offer(FollowBook.Entry entry, Rules.Quote quote, FuturesApi.Wallet wallet,
            FuturesApi.Contract contract, BigDecimal boundary, BigDecimal budget, int leverage,
            int actualLeverage, BigDecimal nominal) {
        Rules.Action action = entry.signal.action;
        BigDecimal price = Rules.iocPrice(action, quote, contract.tick);
        if (price.signum() <= 0 || (boundary != null
                && (!Rules.acceptable(action, boundary, quote) || Rules.exceedsOpeningLimit(action, price, boundary)))) {
            return new Offer("不利价差超限，继续等待");
        }
        BigDecimal available = wallet.available.max(ZERO);
        BigDecimal leverageValue = BigDecimal.valueOf(leverage);
        BigDecimal notionalBudget = nominal == null ? budget.multiply(leverageValue) : nominal;
        BigDecimal amount = contract.openingQuantity(notionalBudget, price);
        BigDecimal target = nominal != null || entry.remaining == null ? amount : entry.remaining;
        BigDecimal ceiling = target.min(amount).min(contract.lotMax);
        BigDecimal quantity = contract.openingQuantity(ceiling.min(quote.available(action)));
        BigDecimal value = quantity.multiply(price);
        BigDecimal minimum = contract.minValue.max(entry.reportedMinimum);
        if (quantity.signum() == 0 || value.compareTo(minimum) < 0) {
            BigDecimal withoutDepthLimit = contract.openingQuantity(ceiling);
            if (withoutDepthLimit.signum() > 0 && withoutDepthLimit.multiply(price).compareTo(minimum) >= 0) {
                return new Offer("盘口可成交量暂时低于最小委托量或金额，继续等待");
            }
            BigDecimal minimumLot = contract.lotMin.add(minimum.subtract(contract.lotMin.multiply(price)).max(ZERO)
                    .divide(contract.lotStep.multiply(price), 0, RoundingMode.CEILING).multiply(contract.lotStep));
            BigDecimal margin = minimumLot.multiply(price).divide(leverageValue, 2, RoundingMode.CEILING);
            return new Offer("委托量低于合约限制，当前价格下最小保证金约 " + Rules.show(margin) + " USDT", true);
        }
        if (quantity.compareTo(entry.rejectedQuantity) <= 0 || value.compareTo(entry.rejectedNotional) <= 0) {
            return new Offer("当前可委托量或金额未超过交易所已拒绝的下限，继续等待", true);
        }
        // Only the slice sent in this IOC needs available margin now.
        BigDecimal usesAndFees = value.divide(BigDecimal.valueOf(actualLeverage), MC)
                .add(value.multiply(FEE_BUFFER, MC));
        if (budget.signum() <= 0 || available.compareTo(usesAndFees) < 0) {
            return new Offer("可用保证金不足，继续等待");
        }
        return new Offer(quantity, price, target, budget, "");
    }

    private String send(FollowBook.Leg leg, Rules.Action action, boolean hedge, BigDecimal amount,
            BigDecimal limit, FollowBook.Entry entry, BigDecimal otherSide, int leverage, long checkedAt,
            BooleanSupplier canSend, boolean adjustment, boolean fullFill) throws Exception {
        if (!book.config.symbols.contains(leg.symbol) || book.pending != null) {
            throw new IllegalStateException("跟单状态或合约名单发生变化");
        }
        if (!canSend.getAsBoolean()) return "本轮未发委托";
        String id = newId();
        book.pending = new JSONObject().put("id", id).put("symbol", leg.symbol).put("action", action.name())
                .put("qty", Rules.show(amount)).put("hedge", hedge).put("at", System.currentTimeMillis())
                .put("checkedAt", checkedAt);
        if (limit != null) book.pending.put("limit", Rules.show(limit));
        if (fullFill) book.pending.put("fullFill", true);
        if (entry != null) {
            entry.waitReason = "";
            entry.lastUnfilled = 0;
            book.pending.put("entry", entry.id()).put("leverage", leverage);
        }
        if (adjustment) {
            leg.adjustment.waitReason = "";
            leg.adjustment.lastUnfilled = 0;
            book.pending.put("copyAdjustment", leg.adjustment.id()).put("leverage", leverage)
                    .put("copyTarget", Rules.show(leg.adjustment.target));
        }
        if (book.strategy == Rules.Strategy.EXPOSURE) {
            boolean rebalance = adjustment ? leg.adjustment.fromSetting() : entry != null && entry.existing;
            book.pending.put("copyFactor", Rules.show(book.copyFactor)).put("copyRebalance", rebalance);
        }
        Rules.Signal source = adjustment ? leg.adjustment.signal : entry == null ? leg.reducingSignal : entry.signal;
        BigDecimal reference = null;
        String label = referenceLabel(entry);
        if (adjustment && action.opens && leg.leaderAverage != null) {
            reference = leg.leaderAverage;
            label = "带单仓位均价";
        } else if (adjustment && leg.adjustment.fromSetting() && action.opens) {
            reference = leg.adjustment.reference;
            label = "调仓参考均价";
        } else if (source != null) {
            reference = entry != null ? entry.referencePrice(leg.leaderAverage) : source.price;
            if (action.opens && leg.leaderAverage != null && entry != null && entry.existing) {
                label = "带单仓位均价";
            }
        }
        if (action.opens && leg.leaderAverage != null) book.pending.put("leaderAverage", Rules.show(leg.leaderAverage));
        if (reference != null) book.pending.put("reference", Rules.show(reference)).put("referenceLabel", label);
        if (source != null) {
            BigDecimal ratio = source.exposureRatio();
            if (book.strategy == Rules.Strategy.EXPOSURE && ratio != null) {
                book.pending.put("exposureRatio", Rules.show(ratio));
            }
        }
        if (otherSide != null) book.pending.put("otherBefore", Rules.show(otherSide));
        commit();
        if (!canSend.getAsBoolean()) {
            book.pending = null;
            resetUnfilled(entry);
            commit();
            return "本轮未发委托";
        }
        try {
            invalidateCopyChecks(leg.symbol);
            walletRevision++;
            api.submit(leg.symbol, action, hedge, amount, limit, id, fullFill);
        } catch (FuturesApi.NotSent notSent) {
            book.pending = null;
            resetUnfilled(entry);
            if (entry == null) {
                String issue = (action.opens ? "开仓" : "平仓") + "请求未发送 · " + failureText(notSent);
                if (adjustment) leg.adjustment.waitReason = issue;
                book.log(leg.symbol + " " + issue);
            }
            commit();
            throw notSent;
        } catch (FuturesApi.Failure failure) {
            if (!failure.definitivelyRejected()) return uncertain("发送结果未确认 · " + failureText(failure));
            book.pending = null;
            if (fullFill && failure.code == -5021) {
                resetUnfilled(entry);
                recordUnfilled(entry, adjustment ? leg.adjustment : null, true, System.currentTimeMillis());
                commit();
                return leg.symbol + " " + FULL_FILL_UNFILLED;
            }
            String detail = (action.opens ? "开仓" : "平仓") + "请求失败 · " + failure.getMessage();
            boolean copyRemainder = book.strategy == Rules.Strategy.EXPOSURE && (entry != null || leg.target.signum() > 0);
            if ((adjustment || copyRemainder) && failure.belowOrderMinimum()) {
                contracts.remove(leg.symbol);
                return entry == null ? deferCopy(leg, leg.symbol + " " + detail)
                        : deferOpeningCopy(leg, entry, leg.symbol + " " + detail);
            }
            if (entry == null) {
                if (adjustment) leg.adjustment.waitReason = "币安拒单 · " + failure.getMessage();
                book.log(leg.symbol + " " + detail);
            } else {
                resetUnfilled(entry);
                entry.waitReason = "币安拒单 · " + failure.getMessage();
                if (failure.belowOrderMinimum()) {
                    contracts.remove(leg.symbol);
                    if (failure.code == -4004) entry.rejectedQuantity = entry.rejectedQuantity.max(amount);
                    else {
                        entry.rejectedNotional = entry.rejectedNotional.max(amount.multiply(limit));
                        entry.reportedMinimum = entry.reportedMinimum.max(failure.reportedMinimumNotional());
                    }
                }
                noteIssue(entry, detail);
            }
            commit();
            if (failure.status == 401 || failure.code == -2015 || failure.code == -1022 || failure.unavailableSymbol()) {
                throw failure;
            }
            return "币安拒单 · " + failure.getMessage();
        } catch (Exception unknown) {
            return uncertain("发送结果未确认 · " + failureText(unknown));
        }
        return reconcile();
    }

    private static void resetUnfilled(FollowBook.Entry entry) {
        if (entry != null && !entry.filled) entry.remaining = entry.margin = entry.nominalLeft = null;
    }

    void releaseFlatAccount() throws Exception {
        Set<String> symbols = new LinkedHashSet<>();
        for (FollowBook.Leg leg : book.legs.values()) {
            if (leg.owned.signum() > 0 || leg.needsReduction()) symbols.add(leg.symbol);
        }
        JSONObject pending = book.pending;
        if (pending != null) symbols.add(pending.getString("symbol"));
        Map<String, Set<String>> openOrders = api.openOrderIds();
        Map<String, FuturesApi.Position> actual = api.positions();
        for (String symbol : symbols) {
            for (String id : openOrders.getOrDefault(symbol, Collections.emptySet())) {
                if (id.matches("CF[0-9a-f]{32}") || (pending != null && id.equals(pending.getString("id")))) {
                    throw new IllegalStateException(symbol + " 还有未结束的跟单委托");
                }
            }
            if (!positionOrFlat(actual, symbol).flat()) throw new IllegalStateException(symbol + " 还有未平的账户仓位");
        }
        if (pending != null) {
            String result = reconcile(true);
            if (book.pending != null) throw new IllegalStateException(result);
        }
        for (FollowBook.Leg leg : book.legs.values()) {
            if (leg.owned.signum() > 0) book.pnlIncomplete = true;
            leg.matchActualForReduction(ZERO);
            if (leg.managed) leg.observed = ZERO;
        }
        book.log("已向币安核对账户平仓，旧跟单仓位已结清");
        commit();
    }

    private static FuturesApi.Position positionOrFlat(Map<String, FuturesApi.Position> actual, String symbol) {
        return actual.getOrDefault(symbol, new FuturesApi.Position());
    }

    String reconcile() throws Exception { return reconcile(false); }

    private String reconcile(boolean stoppedFlat) throws Exception {
        if (book.pending == null) return "当前没有待核对委托";
        JSONObject pending = book.pending;
        String id = pending.getString("id");
        String symbol = pending.getString("symbol");
        Rules.Action action = Rules.Action.valueOf(pending.getString("action"));
        FollowBook.Leg leg = book.leg(symbol, action.isLong);
        boolean adjusting = pending.has("copyAdjustment");
        BigDecimal requested = FuturesApi.decimal(pending, "qty");
        JSONObject order;
        try { order = api.order(symbol, id); }
        catch (FuturesApi.Failure failure) {
            long at = pending.getLong("at");
            if (stoppedFlat && failure.status == 400 && failure.code == -2013 && at > 0
                    && System.currentTimeMillis() - at >= LOST_ORDER_GRACE_MS && positionOrFlat(api.positions(), symbol).flat()) {
                book.pending = null;
                book.pnlIncomplete = true;
                String message = symbol + " 历史委托查无记录，已确认无持仓和在途跟单委托";
                book.log(message);
                commit();
                return message;
            }
            return uncertain("查询未确认 · " + failureText(failure));
        }
        catch (Exception failure) { return uncertain("查询未确认 · " + failureText(failure)); }
        String side = pending.getBoolean("hedge") ? action.positionSide() : "BOTH";
        if (!symbol.equals(order.optString("symbol")) || !id.equals(order.optString("clientOrderId"))
                || !action.exchangeSide().equals(order.optString("side"))
                || !side.equals(order.optString("positionSide"))
                || requested.compareTo(FuturesApi.decimal(order, "origQty")) != 0) {
            return uncertain("回执字段与本机委托不一致");
        }
        BigDecimal executed = FuturesApi.decimal(order, "executedQty");
        if (!terminal(order.getString("status")) || executed.signum() < 0 || executed.compareTo(requested) > 0) {
            return uncertain("等待订单终态");
        }
        BigDecimal expected = action.opens ? leg.owned.add(executed) : leg.owned.subtract(executed);
        if (!stoppedFlat && expected.signum() < 0) return uncertain("成交量超出本机跟单仓位");
        long checkedAt = System.currentTimeMillis();
        FuturesApi.Position position;
        try { position = stoppedFlat ? positionOrFlat(api.positions(), symbol) : api.position(symbol); }
        catch (Exception failure) { return uncertain("等待仓位核对 · " + failureText(failure)); }
        BigDecimal other = position.size(leg.isLong ? Rules.Action.CLOSE_SHORT : Rules.Action.CLOSE_LONG);
        BigDecimal expectedOther = pending.has("otherBefore")
                ? FuturesApi.decimal(pending, "otherBefore") : owned(symbol, !leg.isLong);
        if (stoppedFlat && !position.flat()) {
            return uncertain("账户仓位与成交回执还未一致");
        }
        AccountMovement external = null;
        if (!stoppedFlat && (position.size(action).compareTo(expected) != 0 || other.compareTo(expectedOther) != 0)) {
            if (allowManual) {
                BigDecimal beforeLong = leg.isLong ? leg.owned : expectedOther;
                BigDecimal beforeShort = leg.isLong ? expectedOther : leg.owned;
                external = movement(symbol, pending.optLong("checkedAt", pending.getLong("at")), beforeLong, beforeShort,
                        position, order.getLong("orderId"), executed);
            }
            if (external == null) return uncertain("账户仓位与成交回执还未一致");
        }
        FuturesApi.Fills fills = null;
        if (executed.signum() > 0) {
            try { fills = api.fills(symbol, order.getLong("orderId")); }
            catch (Exception failure) {
                if (!stoppedFlat) return uncertain("等待成交明细 · " + failureText(failure));
            }
            if (fills != null && (fills.quantity.compareTo(executed) != 0 || fills.latest <= 0 || fills.value.signum() <= 0)) {
                fills = null;
            }
            if (fills == null && !stoppedFlat) return uncertain("成交明细尚不完整");
        }
        FollowBook.Entry entry = null;
        if (action.opens && !adjusting && (!stoppedFlat || fills != null)) {
            String signalId = pending.getString("entry");
            entry = leg.entries.stream().filter(e -> e.id().equals(signalId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("本机开仓信号缺失"));
        }
        if (fills != null && pending.has("limit")) {
            if (entry != null) entry.requestIssue = "";
            BigDecimal limit = FuturesApi.decimal(pending, "limit");
            if (fills.minPrice == null || fills.maxPrice == null
                    || (action.buys() ? fills.maxPrice.compareTo(limit) > 0 : fills.minPrice.compareTo(limit) < 0)) {
                if (!stoppedFlat) return uncertain("成交价格与提交的限价不一致");
                fills = null;
            }
        }
        boolean increase = action.opens && leg.owned.signum() > 0;
        String sideLabel = action.label;
        if (entry != null && entry.existing) sideLabel = leg.isLong ? "补多" : "补空";
        if (fills != null && increase) sideLabel = leg.isLong ? "加多" : "加空";
        if (fills != null && !action.opens && expected.signum() > 0) sideLabel = leg.isLong ? "减多" : "减空";
        boolean rebalance = pending.optBoolean("copyRebalance",
                pending.optString("copyAdjustment").startsWith("adjust-setting|")
                        || pending.has("copyFactor") && pending.optString("entry").startsWith("existing|"));
        if (rebalance) {
            if (entry != null && entry.existing) sideLabel = leg.isLong ? "补多" : "补空";
            sideLabel = "复制调仓 " + sideLabel;
        }
        String label = sideLabel + (executed.signum() == 0 ? "未成交" : executed.compareTo(requested) < 0
                ? "部分成交" : "已成交");
        int leverage = pending.optInt("leverage", book.config.rule(symbol).leverage);
        BigDecimal remaining = stoppedFlat ? ZERO : position.size(action);
        String message;
        if (fills == null) {
            message = TradeNotice.fill(label, symbol, order.optLong("updateTime", pending.getLong("at")),
                    executed, remaining, Rules.base(symbol));
            if (executed.signum() > 0) message += " · 成交明细未取得";
        } else {
            BigDecimal reference = FuturesApi.optionalDecimal(pending, "reference");
            if (reference == null && entry != null) reference = entry.referencePrice();
            message = TradeNotice.completed(label, symbol, action, fills, remaining, reference,
                    pending.optString("referenceLabel", referenceLabel(entry)));
            if (action.opens) message += "\n实际杠杆 " + leverage + " 倍";
            BigDecimal leaderAverage = FuturesApi.optionalDecimal(pending, "leaderAverage");
            BigDecimal accountAverage = position.entryPrice(action);
            if (leaderAverage != null && accountAverage != null && accountAverage.signum() > 0) {
                message += "\n成交后持仓均价 " + TradeNotice.price(accountAverage)
                        + " USDT · 带单仓位均价 " + TradeNotice.price(leaderAverage) + " USDT";
            }
            BigDecimal multiple = FuturesApi.optionalDecimal(pending, "exposureRatio");
            if (multiple != null) message += "\n带单成交额 / 保证金余额 " + TradeNotice.exposureMultiple(multiple) + " 倍";
            BigDecimal factor = FuturesApi.optionalDecimal(pending, "copyFactor");
            if (factor != null) message += "\n复制系数 " + Rules.show(factor) + "×";
            if (adjusting) message += " · 目标 " + pending.getString("copyTarget") + " " + Rules.base(symbol);
            try { archiveFill.write(id, fills.latest, message); }
            catch (Exception failure) { throw new JournalFailure(failure); }
        }
        if (!action.opens && executed.signum() > 0) {
            if (fills == null || fills.realizedPnl == null) book.pnlIncomplete = true;
            else book.realizedPnl = book.realizedPnl.add(fills.realizedPnl);
        }
        if (stoppedFlat && expected.signum() != 0) book.pnlIncomplete = true;
        if (adjusting) {
            leg.target = expected.max(ZERO);
            if (leg.adjustment != null && leg.adjustment.id().equals(pending.getString("copyAdjustment"))
                    && remaining.compareTo(FuturesApi.decimal(pending, "copyTarget")) == 0) {
                leg.clearAdjustment();
            }
        } else if (entry != null && fills != null) {
            entry.filled = true;
            leg.target = leg.target.add(executed);
            entry.remaining = entry.remaining.subtract(executed).max(ZERO);
            entry.margin = entry.margin.subtract(fills.value.divide(BigDecimal.valueOf(leverage), MC)).max(ZERO);
            if (entry.nominalLeft != null) entry.nominalLeft = entry.nominalLeft.subtract(fills.value).max(ZERO);
        } else if (!action.opens && expected.signum() == 0) {
            leg.target = ZERO;
        }
        if (entry != null) {
            if (!entry.cancelled && !waitActive.test(entry.id())) book.cancelWait(entry.id());
            boolean budgetUsed = entry.nominalLeft == null ? entry.margin != null && entry.margin.signum() == 0
                    : entry.nominalLeft.signum() == 0;
            boolean quantityUsed = entry.nominalLeft == null && entry.remaining != null && entry.remaining.signum() == 0;
            if (entry.cancelled || budgetUsed || quantityUsed || leg.adjusting()) {
                leg.entries.remove(entry);
            } else if (fills == null) resetUnfilled(entry);
        }
        if (stoppedFlat) leg.matchActualForReduction(ZERO);
        else {
            leg.owned = expected;
            if (allowManual) {
                FollowBook.Leg otherLeg = book.leg(symbol, !leg.isLong);
                leg.observed = expected;
                leg.observedAt = checkedAt;
                otherLeg.seedObservation(expectedOther, checkedAt);
                otherLeg.observed = expectedOther;
                otherLeg.observedAt = checkedAt;
                if (external != null) {
                    recordManual(leg, position.size(action), external, checkedAt);
                    recordManual(otherLeg, other, external, checkedAt);
                }
            }
        }
        if (!stoppedFlat && action.opens && pending.has("limit") && executed.signum() == 0) {
            FollowBook.CopyAdjustment matching = leg.adjustment != null
                    && leg.adjustment.id().equals(pending.optString("copyAdjustment")) ? leg.adjustment : null;
            recordUnfilled(entry, matching, pending.optBoolean("fullFill"), checkedAt);
        }
        book.pending = null;
        book.log(message);
        if (!stoppedFlat && fills != null) book.notice = new JSONObject().put("id", id).put("text", message).put("fill", true);
        commit();
        if (!stoppedFlat) flushNotice();
        return message;
    }

    private static void recordUnfilled(FollowBook.Entry entry, FollowBook.CopyAdjustment adjustment,
            boolean fullFill, long checkedAt) {
        String reason = fullFill ? FULL_FILL_UNFILLED : LIMIT_UNFILLED;
        if (entry != null) {
            entry.lastUnfilled = checkedAt;
            entry.waitReason = reason;
        } else if (adjustment != null) {
            adjustment.lastUnfilled = checkedAt;
            adjustment.waitReason = reason;
        }
    }

    private String referenceLabel(FollowBook.Entry entry) {
        if (entry != null && entry.recoveryAverage != null) return "补页后推算均价";
        return entry != null && entry.existing ? "带单员推算开仓均价" : sourcePriceLabel;
    }

    private String uncertain(String reason) throws Exception {
        JSONObject pending = book.pending;
        String symbol = pending.getString("symbol");
        String message = TradeNotice.submitted(Rules.Action.valueOf(pending.getString("action")).label,
                symbol, pending.getLong("at"), FuturesApi.decimal(pending, "qty"), Rules.base(symbol))
                + " · " + reason;
        if (!reason.equals(pending.optString("issue"))) {
            pending.put("issue", reason);
            book.log(message);
            commit();
        }
        return message;
    }

    private boolean noteIssue(FollowBook.Entry entry, String issue) throws org.json.JSONException {
        if (issue.equals(entry.requestIssue)) return false;
        entry.requestIssue = issue;
        Rules.Signal signal = entry.signal;
        book.log(signal.symbol + " " + signal.action.label + " · " + TradeNotice.time(signal.time) + " · " + issue);
        return true;
    }

    private void recordIssue(FollowBook.Entry entry, String issue) throws Exception {
        boolean cleared = entry.clearCheckRetry();
        if (noteIssue(entry, issue) || cleared) commit();
    }

    private void recordOpenWait(FollowBook.Entry entry, String reason) throws JournalFailure {
        boolean cleared = entry.clearCheckRetry();
        if (!reason.equals(entry.waitReason) || cleared) {
            entry.waitReason = reason;
            commit();
        }
    }

    private static boolean retryableCheck(Exception failure) {
        if (failure instanceof FuturesApi.Failure) {
            FuturesApi.Failure api = (FuturesApi.Failure) failure;
            return api.status == 408 || api.status == 418 || api.status == 429 || api.status >= 500
                    || api.code == -1001 || api.code == -1003 || api.code == -1006 || api.code == -1007
                    || api.code == -1008 || api.code == -1021;
        }
        return failure instanceof java.io.IOException && !(failure instanceof javax.net.ssl.SSLException);
    }

    private String retryCheck(FollowBook.Entry entry, String issue) throws Exception {
        if (entry.checkFailures < Integer.MAX_VALUE) entry.checkFailures++;
        entry.checkFailedAt = System.currentTimeMillis();
        entry.checkRetryAt = entry.checkFailedAt + (CHECK_RETRY_MS << Math.min(3, entry.checkFailures - 1));
        noteIssue(entry, issue);
        commit();
        return entry.signal.symbol + " 开仓检查待重试 · " + issue + " · 最早 " + TradeNotice.time(entry.checkRetryAt);
    }

    static String failureText(Exception failure) {
        if (failure instanceof java.net.SocketTimeoutException) return "币安请求超时";
        if (failure instanceof java.net.UnknownHostException) return "无法解析币安域名";
        if (failure instanceof javax.net.ssl.SSLException) return "币安连接的 TLS 校验失败";
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "请求暂未完成" : message;
    }

    void verifyPosition(String symbol, FuturesApi.Position actual) {
        if (actual.longs.compareTo(owned(symbol, true)) != 0 || actual.shorts.compareTo(owned(symbol, false)) != 0) {
            throw new IllegalStateException(symbol + " 账户仓位与本机记录不同，已停止此轮委托");
        }
    }

    private BigDecimal owned(String symbol, boolean isLong) {
        FollowBook.Leg side = book.legs.get(symbol + (isLong ? "|LONG" : "|SHORT"));
        return side == null ? ZERO : side.owned;
    }

    private static String newId() { return "CF" + UUID.randomUUID().toString().replace("-", ""); }

    static boolean terminal(String status) {
        return "FILLED".equals(status) || "CANCELED".equals(status) || "EXPIRED".equals(status)
                || "EXPIRED_IN_MATCH".equals(status) || "REJECTED".equals(status);
    }
}
