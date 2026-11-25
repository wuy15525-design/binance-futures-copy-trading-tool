package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.MC;
import static com.localcopy.terminal.Rules.ONE;
import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个账户的一轮跟单账本。每个合约的多空方向分别记录带单仓位、本机纳入跟单的数量、
 * 待执行意向及实际仓位观测。档案水位和待核对委托随账本保存，供中断后接续。
 */
final class FollowBook {
    static final class Entry {
        Rules.Signal signal;
        final boolean existing;
        BigDecimal fraction = ONE;
        BigDecimal remaining, margin, nominalLeft, recoveryAverage;
        BigDecimal rejectedQuantity = ZERO, rejectedNotional = ZERO, reportedMinimum = ZERO;
        boolean cancelled, filled;
        String requestIssue = "";
        int checkFailures;
        long checkFailedAt, checkRetryAt;

        Entry(Rules.Signal signal) { this(signal, false); }

        Entry(Rules.Signal signal, boolean existing) {
            this.signal = signal;
            this.existing = existing;
        }

        String id() { return existing ? "existing|" + signal.id : signal.id; }

        BigDecimal referencePrice() { return recoveryAverage == null ? signal.price : recoveryAverage; }

        boolean waitingForCheckRetry(long now) {
            return checkRetryAt > now && checkFailedAt <= now;
        }

        boolean clearCheckRetry() {
            if (checkFailures == 0 && !requestIssue.endsWith("币安请求超时")) return false;
            checkFailures = 0;
            checkFailedAt = checkRetryAt = 0;
            requestIssue = "";
            return true;
        }

        void shrink(BigDecimal keep) {
            fraction = fraction.multiply(keep, MC);
            if (remaining != null) {
                remaining = remaining.multiply(keep, MC);
                margin = margin.multiply(keep, MC);
                if (nominalLeft != null) nominalLeft = nominalLeft.multiply(keep, MC);
            }
        }

        JSONObject json() throws JSONException {
            JSONObject out = new JSONObject().put("signal",
                    LocalData.signalJson(Collections.singletonList(signal))).put("fraction", Rules.show(fraction));
            if (remaining != null) out.put("remaining", Rules.show(remaining)).put("margin", Rules.show(margin))
                    .put("filled", filled);
            if (nominalLeft != null) out.put("nominalLeft", Rules.show(nominalLeft));
            if (recoveryAverage != null) out.put("recoveryAverage", Rules.show(recoveryAverage));
            if (cancelled) out.put("cancelled", true);
            if (existing) out.put("existing", true);
            if (!requestIssue.isEmpty()) out.put("requestIssue", requestIssue);
            if (checkFailures > 0) out.put("checkFailures", checkFailures).put("checkFailedAt", checkFailedAt)
                    .put("checkRetryAt", checkRetryAt);
            if (rejectedQuantity.signum() > 0) out.put("rejectedQuantity", Rules.show(rejectedQuantity));
            if (rejectedNotional.signum() > 0) out.put("rejectedNotional", Rules.show(rejectedNotional));
            if (reportedMinimum.signum() > 0) out.put("reportedMinimum", Rules.show(reportedMinimum));
            return out;
        }

        static Entry read(JSONObject in) throws JSONException {
            Entry entry = new Entry(LocalData.readSignals(in.getJSONArray("signal")).get(0), in.optBoolean("existing"));
            entry.fraction = FuturesApi.decimal(in, "fraction");
            entry.cancelled = in.optBoolean("cancelled");
            entry.requestIssue = in.optString("requestIssue");
            entry.checkFailures = Math.max(0, in.optInt("checkFailures"));
            entry.checkFailedAt = in.optLong("checkFailedAt");
            entry.checkRetryAt = in.optLong("checkRetryAt");
            entry.rejectedQuantity = new BigDecimal(in.optString("rejectedQuantity", "0"));
            entry.rejectedNotional = new BigDecimal(in.optString("rejectedNotional", "0"));
            entry.reportedMinimum = new BigDecimal(in.optString("reportedMinimum", "0"));
            entry.recoveryAverage = FuturesApi.optionalDecimal(in, "recoveryAverage");
            if (in.has("remaining")) {
                entry.remaining = FuturesApi.decimal(in, "remaining");
                entry.margin = FuturesApi.decimal(in, "margin");
                entry.filled = in.optBoolean("filled", true);
                entry.nominalLeft = FuturesApi.optionalDecimal(in, "nominalLeft");
            }
            return entry;
        }
    }

    static final class Reduction {
        Rules.Signal signal;
        final BigDecimal retention;
        final boolean byPosition;

        Reduction(Rules.Signal signal, BigDecimal retention, boolean byPosition) {
            this.signal = signal;
            this.retention = retention;
            this.byPosition = byPosition;
        }
    }

    static final class CopyAdjustment {
        Rules.Signal signal;
        BigDecimal target;
        final String settingId;
        final BigDecimal leaderEntry;
        BigDecimal margin, reference;
        BigDecimal legacyVisible, legacyRetained = ONE, legacyAdded = ZERO;

        CopyAdjustment(Rules.Signal signal) {
            this.signal = signal;
            settingId = "";
            leaderEntry = null;
        }

        CopyAdjustment(String settingId, BigDecimal leaderEntry, BigDecimal margin) {
            this.settingId = settingId;
            this.leaderEntry = leaderEntry;
            this.margin = margin;
        }

        boolean fromSetting() { return !settingId.isEmpty(); }
        String id() { return fromSetting() ? settingId : "adjust|" + signal.id; }
        BigDecimal priceReference() { return fromSetting() ? reference : signal.price; }
        BigDecimal leaderMargin() {
            return signal != null && signal.leaderMargin != null ? signal.leaderMargin : margin;
        }

        JSONObject json() throws JSONException {
            JSONObject out = new JSONObject();
            if (fromSetting()) {
                out.put("settingId", settingId);
                putDecimal(out, "leaderEntry", leaderEntry);
                putDecimal(out, "margin", margin);
                putDecimal(out, "reference", reference);
            }
            if (signal != null) out.put("signal", LocalData.signalJson(Collections.singletonList(signal)));
            if (target != null) out.put("target", Rules.show(target));
            if (legacyVisible != null) out.put("legacyVisible", Rules.show(legacyVisible))
                    .put("legacyRetained", Rules.show(legacyRetained)).put("legacyAdded", Rules.show(legacyAdded));
            return out;
        }

        static CopyAdjustment read(JSONObject in) throws JSONException {
            Rules.Signal signal = in.has("signal") ? LocalData.readSignals(in.getJSONArray("signal")).get(0) : null;
            if (signal == null && !in.has("settingId")) throw new JSONException("缺少复制调仓信号");
            CopyAdjustment adjustment = in.has("settingId")
                    ? new CopyAdjustment(in.getString("settingId"), FuturesApi.optionalDecimal(in, "leaderEntry"),
                            FuturesApi.optionalDecimal(in, "margin"))
                    : new CopyAdjustment(signal);
            adjustment.signal = signal;
            adjustment.target = FuturesApi.optionalDecimal(in, "target");
            adjustment.reference = FuturesApi.optionalDecimal(in, "reference");
            adjustment.legacyVisible = FuturesApi.optionalDecimal(in, "legacyVisible");
            if (adjustment.legacyVisible != null) {
                adjustment.legacyRetained = FuturesApi.decimal(in, "legacyRetained");
                adjustment.legacyAdded = FuturesApi.decimal(in, "legacyAdded");
            }
            return adjustment;
        }

        private static void putDecimal(JSONObject out, String name, BigDecimal value) throws JSONException {
            if (value != null) out.put(name, Rules.show(value));
        }
    }

    static final class Leg {
        final String symbol;
        final boolean isLong;
        BigDecimal leader = ZERO, owned = ZERO, target = ZERO;
        BigDecimal pendingRetention = ONE;
        BigDecimal observed;
        long observedAt;
        boolean managed, protectedExisting;
        BigDecimal copied = ZERO;
        boolean adjustOnSignal, legacyCopyScope;
        CopyAdjustment adjustment;
        Rules.Signal reducingSignal;
        private final Deque<Reduction> reductions = new ArrayDeque<>();
        final List<Entry> entries = new ArrayList<>();

        Leg(String symbol, boolean isLong) {
            this.symbol = symbol;
            this.isLong = isLong;
        }

        boolean adjusting() { return adjustOnSignal || adjustment != null; }

        void clearAdjustment() {
            adjustOnSignal = false;
            adjustment = null;
        }

        void resetCopyOrders(String inFlightEntry) {
            entries.removeIf(entry -> !entry.id().equals(inFlightEntry));
            reductions.clear();
            target = owned;
            pendingRetention = ONE;
            reducingSignal = null;
        }

        void stageAdjustment(Rules.Signal signal, BigDecimal before, BigDecimal retained, BigDecimal added) {
            CopyAdjustment previous = adjustment;
            CopyAdjustment next = previous != null && previous.fromSetting() ? previous : new CopyAdjustment(signal);
            if (legacyCopyScope) {
                next.legacyVisible = previous == null || previous.legacyVisible == null ? before : previous.legacyVisible;
                next.legacyRetained = (previous == null ? ONE : previous.legacyRetained).multiply(retained, MC);
                next.legacyAdded = (previous == null ? ZERO : previous.legacyAdded).multiply(retained, MC).add(added);
            }
            next.signal = signal;
            next.target = null;
            next.margin = null;
            resetCopyOrders("");
            adjustOnSignal = false;
            adjustment = next;
        }

        void apply(Rules.Signal signal, boolean follow, Rules.Strategy strategy, boolean fresh) {
            boolean copy = strategy == Rules.Strategy.EXPOSURE;
            if (signal.action.opens) {
                BigDecimal before = leader;
                leader = leader.add(signal.quantity);
                if (follow) {
                    beginManaging();
                    if (copy) copied = copied.add(signal.quantity);
                    if (copy && adjusting()) {
                        if (fresh) stageAdjustment(signal, before, ONE, signal.quantity);
                        else if (adjustment != null) adjustment.target = null;
                    } else entries.add(new Entry(signal));
                }
                return;
            }
            if (leader.signum() == 0) return;
            BigDecimal after = LeaderHistory.after(leader, signal);
            BigDecimal retention = after.divide(leader, MC);
            if (copy) {
                copied = copied.multiply(retention, MC).min(after);
                if (after.signum() == 0) legacyCopyScope = false;
            }
            if (follow) {
                if (copy && adjusting() && after.signum() > 0 && !protectedExisting) {
                    if (fresh) stageAdjustment(signal, leader, retention, ZERO);
                    else if (adjustment != null) adjustment.target = null;
                } else {
                    if (after.signum() == 0) clearAdjustment();
                    reduce(signal, retention, strategy, false);
                }
            }
            leader = after;
        }

        void reduce(Rules.Signal signal, BigDecimal fraction, Rules.Strategy strategy, boolean byPosition) {
            if (fraction.signum() == 0 && (strategy == Rules.Strategy.EXPOSURE || byPosition)) {
                reductions.clear();
                target = owned;
                pendingRetention = ONE;
                reducingSignal = null;
            }
            if (!protectedExisting) reductions.addLast(new Reduction(signal, fraction, byPosition));
            if (fraction.signum() == 0) entries.clear();
            else for (Entry entry : entries) entry.shrink(fraction);
        }

        boolean needsReduction() {
            return owned.compareTo(target) > 0 || pendingRetention.compareTo(ONE) < 0 || !reductions.isEmpty();
        }

        Reduction nextReduction() {
            return owned.compareTo(target) > 0 || pendingRetention.compareTo(ONE) < 0 ? null : reductions.peekFirst();
        }

        void beginManaging() {
            if (managed) return;
            managed = true;
            protectedExisting = false;
            if (observed != null) owned = target = observed;
        }

        void seedObservation(BigDecimal actual, long at) {
            if (observed != null) return;
            managed |= owned.signum() > 0;
            observed = managed ? owned : actual;
            protectedExisting = !managed && actual.signum() > 0;
            observedAt = at;
        }

        void matchManualChange(BigDecimal actual) {
            if (protectedExisting) {
                if (actual.signum() == 0) protectedExisting = false;
                return;
            }
            if (actual.signum() == 0) {
                matchActualForReduction(ZERO);
            } else {
                beginManaging();
                target = owned.compareTo(target) > 0
                        ? actual.multiply(target.divide(owned, MC), MC) : actual;
                owned = actual;
            }
        }

        void matchActualForReduction(BigDecimal actual) {
            matchActualForReduction(actual, null);
        }

        void matchActualForReduction(BigDecimal actual, BigDecimal reduceQuantity) {
            if (actual.signum() == 0) {
                target = ZERO;
                reducingSignal = null;
                pendingRetention = ONE;
                reductions.clear();
            } else if (owned.compareTo(target) > 0) {
                if (owned.compareTo(actual) != 0) target = actual.multiply(target.divide(owned, MC), MC);
            } else if (pendingRetention.compareTo(ONE) < 0) {
                target = actual.multiply(pendingRetention, MC);
                pendingRetention = ONE;
                reducingSignal = null;
            } else {
                Reduction next = reductions.pollFirst();
                target = next == null ? actual : reduceQuantity == null ? actual.multiply(next.retention, MC)
                        : actual.subtract(reduceQuantity).max(ZERO);
                reducingSignal = next == null ? null : next.signal;
            }
            owned = actual;
        }

        Rules.Action closeAction() {
            return isLong ? Rules.Action.CLOSE_LONG : Rules.Action.CLOSE_SHORT;
        }

        JSONObject json() throws JSONException {
            JSONArray pending = new JSONArray();
            for (Entry entry : entries) pending.put(entry.json());
            JSONArray staged = new JSONArray();
            for (Reduction reduction : reductions) {
                staged.put(new JSONObject().put("signal", LocalData.signalJson(Collections.singletonList(reduction.signal)))
                        .put("retain", Rules.show(reduction.retention)).put("byPosition", reduction.byPosition));
            }
            JSONObject data = new JSONObject().put("symbol", symbol).put("long", isLong)
                    .put("leader", Rules.show(leader)).put("owned", Rules.show(owned))
                    .put("target", Rules.show(target)).put("retain", Rules.show(pendingRetention)).put("entries", pending)
                    .put("reductions", staged).put("managed", managed).put("protectedExisting", protectedExisting);
            if (observed != null) data.put("observed", Rules.show(observed)).put("observedAt", observedAt);
            if (reducingSignal != null) data.put("reducing", LocalData.signalJson(Collections.singletonList(reducingSignal)));
            if (copied.signum() > 0) data.put("copied", Rules.show(copied));
            if (legacyCopyScope) data.put("legacyCopyScope", true);
            if (adjustOnSignal) data.put("adjustOnSignal", true);
            if (adjustment != null) data.put("copyAdjustment", adjustment.json());
            return data;
        }

        static Leg read(JSONObject in, boolean legacyCopy) throws JSONException {
            Leg leg = new Leg(in.getString("symbol"), in.getBoolean("long"));
            leg.leader = FuturesApi.decimal(in, "leader");
            leg.owned = FuturesApi.decimal(in, "owned");
            leg.target = FuturesApi.decimal(in, "target");
            leg.pendingRetention = new BigDecimal(in.optString("retain", "1"));
            JSONArray active = in.optJSONArray("reducing");
            if (active != null) leg.reducingSignal = LocalData.readSignals(active).get(0);
            JSONArray reductions = in.optJSONArray("reductions");
            if (reductions != null) {
                for (int i = 0; i < reductions.length(); i++) {
                    JSONObject row = reductions.getJSONObject(i);
                    leg.reductions.addLast(new Reduction(LocalData.readSignals(row.getJSONArray("signal")).get(0),
                            FuturesApi.decimal(row, "retain"), row.optBoolean("byPosition")));
                }
            }
            JSONArray rows = in.getJSONArray("entries");
            for (int i = 0; i < rows.length(); i++) leg.entries.add(Entry.read(rows.getJSONObject(i)));
            boolean legacyManaged = leg.owned.signum() > 0 || leg.entries.stream().anyMatch(entry -> !entry.existing);
            leg.managed = in.optBoolean("managed", legacyManaged);
            leg.protectedExisting = in.optBoolean("protectedExisting");
            leg.observed = FuturesApi.optionalDecimal(in, "observed");
            leg.observedAt = in.optLong("observedAt");
            BigDecimal copied = FuturesApi.optionalDecimal(in, "copied");
            boolean oldScope = legacyCopy && leg.managed && !leg.protectedExisting && leg.leader.signum() > 0;
            leg.legacyCopyScope = in.optBoolean("legacyCopyScope", oldScope);
            leg.copied = copied == null ? (oldScope ? leg.leader : ZERO) : copied;
            leg.adjustOnSignal = in.optBoolean("adjustOnSignal");
            JSONObject adjustment = in.optJSONObject("copyAdjustment");
            if (adjustment != null) leg.adjustment = CopyAdjustment.read(adjustment);
            return leg;
        }
    }

    final Rules.Config config;
    final String account;
    final Rules.Strategy strategy;
    BigDecimal copyFactor = ONE;
    BigDecimal copySlippage = LocalData.DEFAULT_SLIPPAGE;
    long copyChangedAt;
    boolean copyReplanPending;
    final Map<String, Leg> legs = new LinkedHashMap<>();
    List<Rules.Signal> window = Collections.emptyList();
    JSONObject pending;
    JSONArray log = new JSONArray();
    JSONArray detections = new JSONArray();
    JSONObject notice;
    int drillStage;
    long archiveCursor, baselineBackfilled, archiveRevision, portfolioSelection;
    boolean initialChoiceDone, accountSeeded, baselineReady;
    long trackingSince, trackedMillis;
    BigDecimal realizedPnl = ZERO;
    boolean pnlIncomplete;

    FollowBook(Rules.Config config, String account) {
        this(config, account, Rules.Strategy.POINTS);
    }

    FollowBook(Rules.Config config, String account, Rules.Strategy strategy) {
        this.config = config;
        this.account = account;
        this.strategy = strategy;
    }

    // 旧账本缺少策略或复制系数时沿用对应默认值。旧复制范围由后续调仓结合实际仓位补齐。
    static FollowBook restore(JSONObject saved) throws JSONException {
        FollowBook book = new FollowBook(LocalData.readConfig(saved.getJSONObject("config")), saved.getString("account"),
                Rules.Strategy.read(saved.optString("strategy", "")));
        boolean legacyCopy = book.strategy == Rules.Strategy.EXPOSURE && !saved.has("copyFactor");
        book.copyFactor = Rules.copyFactor(saved.optString("copyFactor", "1"));
        book.copySlippage = Rules.adjustmentSlippage(saved.optString("copySlippage", Rules.show(LocalData.DEFAULT_SLIPPAGE)));
        book.copyChangedAt = saved.optLong("copyChangedAt");
        book.copyReplanPending = saved.optBoolean("copyReplanPending");
        book.window = LocalData.readSignals(saved.getJSONArray("window"));
        book.baselineReady = saved.optBoolean("baselineReady", !book.window.isEmpty());
        book.initialChoiceDone = saved.optBoolean("initialChoiceDone", !book.window.isEmpty());
        book.accountSeeded = saved.optBoolean("accountSeeded");
        book.pending = saved.optJSONObject("pending");
        book.notice = saved.optJSONObject("notice");
        book.log = saved.getJSONArray("log");
        JSONArray detected = saved.optJSONArray("detections");
        if (detected != null) book.detections = detected;
        book.drillStage = saved.optInt("drillStage");
        book.archiveCursor = saved.optLong("archiveCursor");
        book.baselineBackfilled = saved.optLong("baselineBackfilled");
        book.archiveRevision = saved.optLong("archiveRevision");
        book.portfolioSelection = saved.optLong("portfolioSelection");
        book.trackingSince = saved.optLong("trackingSince");
        book.trackedMillis = saved.optLong("trackedMillis");
        book.realizedPnl = new BigDecimal(saved.optString("realizedPnl", "0"));
        book.pnlIncomplete = saved.optBoolean("pnlIncomplete");
        JSONArray legs = saved.getJSONArray("legs");
        for (int i = 0; i < legs.length(); i++) {
            Leg leg = Leg.read(legs.getJSONObject(i), legacyCopy);
            book.legs.put(key(leg.symbol, leg.isLong), leg);
        }
        return book;
    }

    JSONObject json() throws JSONException {
        JSONArray items = new JSONArray();
        for (Leg leg : legs.values()) items.put(leg.json());
        return new JSONObject().put("config", LocalData.configJson(config)).put("account", account)
                .put("strategy", strategy.name()).put("copyFactor", Rules.show(copyFactor)).put("copyChangedAt", copyChangedAt)
                .put("copySlippage", Rules.show(copySlippage)).put("copyReplanPending", copyReplanPending)
                .put("window", LocalData.signalJson(window)).put("pending", pending)
                .put("notice", notice).put("log", log).put("detections", detections)
                .put("drillStage", drillStage).put("archiveCursor", archiveCursor).put("legs", items)
                .put("baselineBackfilled", baselineBackfilled).put("archiveRevision", archiveRevision)
                .put("portfolioSelection", portfolioSelection)
                .put("initialChoiceDone", initialChoiceDone).put("accountSeeded", accountSeeded).put("baselineReady", baselineReady)
                .put("trackingSince", trackingSince)
                .put("trackedMillis", trackedMillis).put("realizedPnl", Rules.show(realizedPnl))
                .put("pnlIncomplete", pnlIncomplete);
    }

    Leg leg(String symbol, boolean isLong) {
        return legs.computeIfAbsent(key(symbol, isLong), id -> new Leg(symbol, isLong));
    }

    private static String key(String symbol, boolean isLong) {
        return symbol + (isLong ? "|LONG" : "|SHORT");
    }

    boolean changeCopySettings(BigDecimal factor, BigDecimal slippage, long at,
            Map<String, LeaderHistory.Holding> holdings, BigDecimal leaderMargin) throws JSONException {
        boolean saved = queueCopySettings(factor, slippage, at);
        return prepareRequestedCopy(holdings, leaderMargin) || saved;
    }

    boolean queueCopySettings(BigDecimal factor, BigDecimal slippage, long at) throws JSONException {
        if (strategy != Rules.Strategy.EXPOSURE) return false;
        boolean factorChanged = copyFactor.compareTo(factor) != 0;
        if (!factorChanged && copySlippage.compareTo(slippage) == 0) return false;
        copySlippage = slippage;
        if (!factorChanged) {
            log("调仓最大不利价差已改为 " + Rules.show(copySlippage) + "%");
            return true;
        }
        BigDecimal before = copyFactor;
        copyFactor = factor;
        copyChangedAt = at;
        copyReplanPending = true;
        log("复制系数 " + Rules.show(before) + "× → " + Rules.show(copyFactor)
                + "× · 调仓最大不利价差 " + Rules.show(copySlippage) + "%");
        return true;
    }

    boolean prepareRequestedCopy(Map<String, LeaderHistory.Holding> holdings, BigDecimal leaderMargin)
            throws JSONException {
        if (!copyReplanPending || strategy != Rules.Strategy.EXPOSURE) return false;
        String inFlightEntry = pending == null ? "" : pending.optString("entry");
        int immediate = 0;
        for (Leg leg : legs.values()) {
            if (leg.leader.signum() <= 0 || leg.protectedExisting || !leg.managed
                    || (leg.copied.signum() == 0 && leg.entries.isEmpty())) continue;
            String side = key(leg.symbol, leg.isLong);
            boolean inFlight = pending != null && leg.symbol.equals(pending.optString("symbol"))
                    && Rules.Action.valueOf(pending.getString("action")).isLong == leg.isLong;
            if (leg.owned.signum() == 0 && !inFlight) {
                leg.adjustment = null;
                for (Entry entry : leg.entries) {
                    entry.remaining = entry.margin = entry.nominalLeft = null;
                    entry.rejectedNotional = entry.rejectedQuantity = entry.reportedMinimum = ZERO;
                }
                continue;
            }
            leg.resetCopyOrders(inFlightEntry);
            LeaderHistory.Holding source = holdings.get(side);
            leg.adjustment = new CopyAdjustment("adjust-setting|" + side + "|" + copyChangedAt,
                    source == null ? null : source.entryPrice, leaderMargin);
            leg.adjustOnSignal = false;
            if (leg.legacyCopyScope) leg.adjustment.legacyVisible = leg.leader;
            immediate++;
        }
        copyReplanPending = false;
        log(immediate == 0 ? "复制系数已应用 · 当前无需要检查的跟单仓位"
                : "复制系数已应用 · 检查 " + immediate + " 个已有仓位");
        return true;
    }

    boolean supplyAdjustmentMargin(BigDecimal margin) {
        if (margin == null) return false;
        boolean changed = false;
        for (Leg leg : legs.values()) {
            CopyAdjustment adjustment = leg.adjustment;
            if (adjustment != null && adjustment.fromSetting() && adjustment.margin == null) {
                adjustment.margin = margin;
                changed = true;
            }
        }
        return changed;
    }

    private boolean canApplyAdjustment(long detectedAt) { return detectedAt >= copyChangedAt; }

    boolean hasExposure() {
        if (pending != null) return true;
        for (Leg leg : legs.values()) if (leg.owned.signum() > 0 || leg.needsReduction()) return true;
        return false;
    }

    boolean hasWork() {
        return hasExposure() || countEntries() > 0 || legs.values().stream().anyMatch(Leg::adjusting);
    }

    void reseedIdlePositions() {
        if (pending != null) return;
        for (Leg leg : legs.values()) {
            if (leg.owned.signum() > 0 || leg.needsReduction() || !leg.entries.isEmpty()) continue;
            leg.observed = null;
            leg.observedAt = 0;
            leg.protectedExisting = false;
            leg.managed = false;
        }
        accountSeeded = false;
    }

    long trackingMillis(long now) {
        return trackedMillis + (trackingSince > 0 ? Math.max(0L, now - trackingSince) : 0L);
    }

    BigDecimal totalPnl(Map<String, FuturesApi.Position> positions) {
        if (pending != null) return null;
        BigDecimal total = realizedPnl;
        for (Leg leg : legs.values()) {
            if (leg.owned.signum() == 0) continue;
            FuturesApi.Position position = positions.get(leg.symbol);
            if (position == null || position.size(leg.closeAction()).compareTo(leg.owned) != 0) return null;
            BigDecimal unrealized = leg.isLong ? position.longUnrealized : position.shortUnrealized;
            if (unrealized == null) return null;
            total = total.add(unrealized);
        }
        return total;
    }

    int countEntries() {
        int count = 0;
        for (Leg leg : legs.values()) for (Entry entry : leg.entries) if (!entry.cancelled) count++;
        return count;
    }

    Rules.Signal latest() { return window.isEmpty() ? null : window.get(window.size() - 1); }

    String anchor() { return latest() == null ? "" : latest().id; }

    int accept(List<Rules.Signal> rows) throws JSONException { return accept(rows, true); }

    int accept(List<Rules.Signal> rows, boolean enqueue) throws JSONException {
        LeaderHistory.Merge merged = LeaderHistory.merge(window, rows, false, false);
        if (window.isEmpty()) {
            for (Rules.Signal row : merged.rows) apply(row, false);
            window = merged.rows;
            return 0;
        }
        acceptNew(merged.added, enqueue);
        window = merged.rows;
        return merged.added.size();
    }

    void acceptNew(List<Rules.Signal> rows, boolean enqueue) throws JSONException {
        acceptNew(rows, enqueue, Collections.emptyMap(), System.currentTimeMillis());
    }

    void acceptNew(List<Rules.Signal> rows, boolean enqueue, Map<String, Long> detectedAt, long defaultTime)
            throws JSONException {
        int opens = 0;
        for (Rules.Signal row : rows) {
            if (row.action.opens && config.symbols.contains(row.symbol)) opens++;
        }
        if (enqueue && countEntries() + opens > 1000) throw new IllegalArgumentException("待跟信号过多，需核对跟单记录");
        for (Rules.Signal row : rows) applyDetected(row, enqueue, detectedAt.getOrDefault(row.id, defaultTime));
    }

    private static final class RecoveredSide {
        final BigDecimal before, copiedBefore;
        final boolean legacyScope;
        final List<Rules.Signal> opens = new ArrayList<>();
        Rules.Signal lastClose, latest;
        BigDecimal visible, retained = ONE, added = ZERO;
        Rules.Signal closedCycle;

        RecoveredSide(Leg leg) {
            before = visible = leg.leader;
            copiedBefore = leg.copied;
            legacyScope = leg.legacyCopyScope;
        }
    }

    // 缺口内可能已经完成一轮开平。按合约和方向累计留存比例与净变化，再用当前带单均价处理剩余增仓。
    void acceptRecovered(List<Rules.Signal> rows, boolean enqueue, Map<String, Long> detectedAt,
            Map<String, LeaderHistory.Holding> holdings) throws JSONException {
        if (!enqueue) {
            acceptNew(rows, false, detectedAt, 0);
            return;
        }
        Map<String, RecoveredSide> sides = new LinkedHashMap<>();
        for (Rules.Signal row : rows) {
            if (row.action.sideOnly() || !config.symbols.contains(row.symbol)) continue;
            Leg leg = leg(row.symbol, row.action.isLong);
            RecoveredSide side = sides.computeIfAbsent(row.leg(), key -> new RecoveredSide(leg));
            BigDecimal after = LeaderHistory.after(side.visible, row);
            side.latest = row;
            if (row.action.opens) {
                side.opens.add(row);
                side.added = side.added.add(row.quantity);
            }
            else {
                side.lastClose = row;
                if (side.visible.signum() > 0) {
                    BigDecimal retention = after.divide(side.visible, MC);
                    side.retained = side.retained.multiply(retention, MC);
                    side.added = side.added.multiply(retention, MC);
                }
                if (side.visible.signum() > 0 && after.signum() == 0) {
                    if (side.closedCycle == null) side.closedCycle = row;
                    side.opens.clear();
                }
            }
            side.visible = after;
        }
        int openSignals = sides.values().stream().mapToInt(side -> side.opens.size()).sum();
        if (countEntries() + openSignals > 1000) throw new IllegalArgumentException("待跟信号过多，需核对跟单记录");
        acceptNew(rows, false, detectedAt, 0);
        for (Map.Entry<String, RecoveredSide> item : sides.entrySet()) {
            String key = item.getKey();
            Leg leg = legs.get(key);
            RecoveredSide side = item.getValue();
            if (side.closedCycle != null) {
                logCloseCancellations(leg.entries, side.closedCycle, detectedAt.getOrDefault(side.closedCycle.id, 0L));
                leg.entries.clear();
            }
            if (strategy == Rules.Strategy.EXPOSURE) {
                leg.copied = side.copiedBefore.multiply(side.retained, MC).add(side.added).min(leg.leader);
                if (leg.leader.signum() == 0) leg.clearAdjustment();
                else if (leg.adjusting() && !leg.protectedExisting) {
                    boolean activeSetting = leg.adjustment != null && leg.adjustment.fromSetting() && !leg.adjustOnSignal;
                    if (activeSetting || canApplyAdjustment(detectedAt.getOrDefault(side.latest.id, 0L))) {
                        leg.legacyCopyScope = side.legacyScope;
                        leg.stageAdjustment(side.latest, side.before, side.retained, side.added);
                    }
                    continue;
                }
            }
            BigDecimal increase = leg.leader.subtract(side.before);
            if (increase.signum() < 0) {
                leg.reduce(side.lastClose, leg.leader.divide(side.before, MC), strategy, true);
                continue;
            }
            if (increase.signum() == 0) continue;
            LeaderHistory.Holding current = holdings.get(key);
            if (current == null || current.entryPrice == null || current.entryPrice.signum() <= 0) {
                log("补页核对 · " + leg.symbol + " 缺少带单员当前均价，略过缺口内开仓信号");
                continue;
            }
            List<Entry> surviving = new ArrayList<>();
            for (int i = side.opens.size() - 1; i >= 0 && increase.signum() > 0; i--) {
                Rules.Signal row = side.opens.get(i);
                BigDecimal part = increase.min(row.quantity);
                Entry entry = new Entry(row);
                entry.recoveryAverage = current.entryPrice;
                entry.fraction = part.divide(row.quantity, MC);
                surviving.add(entry);
                increase = increase.subtract(part);
            }
            Collections.reverse(surviving);
            leg.beginManaging();
            leg.entries.addAll(surviving);
        }
    }

    boolean enrichWaiting(List<Rules.Signal> rows) {
        if (strategy != Rules.Strategy.EXPOSURE) return false;
        Map<String, Rules.Signal> visible = new java.util.HashMap<>();
        for (Rules.Signal row : rows) visible.put(row.id, row);
        boolean changed = false;
        for (Leg leg : legs.values()) {
            if (leg.adjustment != null && leg.adjustment.signal != null) {
                Rules.Signal previous = leg.adjustment.signal;
                Rules.Signal latest = visible.get(previous.id);
                if (latest != null) {
                    leg.adjustment.signal = previous.enrich(latest);
                    changed |= leg.adjustment.signal != previous;
                }
            }
            for (Entry entry : leg.entries) {
                Rules.Signal latest = visible.get(entry.signal.id);
                if (latest == null) continue;
                Rules.Signal enriched = entry.signal.enrich(latest);
                changed |= enriched != entry.signal;
                entry.signal = enriched;
            }
            for (Reduction reduction : leg.reductions) {
                Rules.Signal latest = visible.get(reduction.signal.id);
                if (latest == null) continue;
                Rules.Signal enriched = reduction.signal.enrich(latest);
                changed |= enriched != reduction.signal;
                reduction.signal = enriched;
            }
        }
        return changed;
    }

    Map<String, BigDecimal> knownPositions() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Leg leg : legs.values()) result.put(key(leg.symbol, leg.isLong), leg.leader);
        return result;
    }

    void seedPositions(Map<String, BigDecimal> known) {
        for (String symbol : config.symbols) {
            leg(symbol, true).leader = known.getOrDefault(key(symbol, true), ZERO);
            leg(symbol, false).leader = known.getOrDefault(key(symbol, false), ZERO);
        }
    }

    void rebaseLeaderPositions(Map<String, BigDecimal> oldModel, Map<String, BigDecimal> mergedModel) {
        for (String symbol : config.symbols) {
            for (boolean isLong : new boolean[]{true, false}) {
                String side = key(symbol, isLong);
                Leg leg = leg(symbol, isLong);
                if (leg.leader.compareTo(oldModel.getOrDefault(side, ZERO)) == 0) {
                    leg.leader = mergedModel.getOrDefault(side, ZERO);
                    if (leg.copied.compareTo(leg.leader) > 0) {
                        leg.copied = leg.leader;
                        if (leg.adjustment != null) leg.adjustment.target = null;
                        if (leg.leader.signum() == 0) {
                            leg.clearAdjustment();
                            leg.legacyCopyScope = false;
                        }
                    }
                }
            }
        }
    }

    void removeStaleInitialEntries(Map<String, BigDecimal> oldModel, Map<String, BigDecimal> newModel) throws JSONException {
        for (Map.Entry<String, Leg> side : legs.entrySet()) {
            String id = side.getKey();
            Leg leg = side.getValue();
            if (oldModel.getOrDefault(id, ZERO).compareTo(newModel.getOrDefault(id, ZERO)) == 0) continue;
            BigDecimal removed = ZERO;
            for (Entry entry : leg.entries) if (entry.existing) {
                removed = removed.add(entry.signal.quantity.multiply(entry.fraction, MC));
            }
            if (leg.entries.removeIf(entry -> entry.existing)) {
                if (strategy == Rules.Strategy.EXPOSURE) leg.copied = leg.copied.subtract(removed).max(ZERO);
                log("历史成交复核 · 已撤下旧的已有仓位补跟等待 · " + leg.symbol);
            }
        }
    }

    int finishInitialChoice(List<Rules.Signal> existing) {
        if (initialChoiceDone) return 0;
        int added = 0;
        for (Rules.Signal signal : existing) {
            Leg side = leg(signal.symbol, signal.action.isLong);
            if (side.protectedExisting) continue;
            side.beginManaging();
            side.entries.add(new Entry(signal, true));
            if (strategy == Rules.Strategy.EXPOSURE) side.copied = side.copied.add(signal.quantity).min(side.leader);
            added++;
        }
        initialChoiceDone = true;
        return added;
    }

    private void applyDetected(Rules.Signal signal, boolean enqueue, long detectedAt) throws JSONException {
        String direction = signal.action.isLong ? "多" : "空";
        boolean tracked = config.symbols.contains(signal.symbol);
        Leg side = tracked && !signal.action.sideOnly() ? leg(signal.symbol, signal.action.isLong) : null;
        String action = signal.action.label;
        String detail = "";
        if (side != null) {
            List<Entry> cancelled = Collections.emptyList();
            if (signal.action.opens && side.leader.signum() > 0) action = "加" + direction;
            if (!signal.action.opens) {
                if (side.leader.signum() == 0) detail = " · 未见对应开仓，已跳过";
                else if (side.leader.compareTo(signal.quantity) > 0) action = "减" + direction;
                else if (enqueue && !side.entries.isEmpty()) cancelled = new ArrayList<>(side.entries);
                if (side.protectedExisting && enqueue) detail += " · 启动前仓位暂未纳入跟单";
            }
            if (signal.action.opens && enqueue && side.protectedExisting) {
                log("已有仓位纳入跟单 · " + signal.symbol + " " + direction + " · "
                        + Rules.show(side.observed) + " " + Rules.base(signal.symbol));
            }
            boolean waitForRatio = side.adjustment != null;
            side.apply(signal, enqueue, strategy, canApplyAdjustment(detectedAt));
            if (waitForRatio && side.leader.signum() == 0 && enqueue) {
                log("复制调仓取消 · " + signal.symbol + " " + direction + " · 带单员同方向已平仓");
            }
            int removed = logCloseCancellations(cancelled, signal, detectedAt);
            if (removed > 0) detail += " · 已取消 " + removed + " 笔等待";
        } else if (!tracked) detail = " · 白名单外";
        if (!signal.action.opens) detail = " · " + TradeNotice.pnl(signal.realizedPnl) + detail;
        detections = append(detections, signal.symbol + " " + action + " " + Rules.show(signal.quantity)
                + " @ " + Rules.show(signal.price) + detail + "\n成交 " + TradeNotice.fullTime(signal.time)
                + " · 检测 " + (detectedAt > 0 ? TradeNotice.fullTime(detectedAt) : "未记录"));
    }

    private int logCloseCancellations(List<Entry> entries, Rules.Signal close, long detectedAt) throws JSONException {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.cancelled) continue;
            count++;
            Rules.Signal signal = entry.signal;
            String original = entry.existing ? "补已有仓位" : signal.action.label;
            log("自动取消开仓等待 · " + signal.symbol + " " + original
                    + " · 原信号 " + TradeNotice.fullTime(signal.time)
                    + " · 参考价 " + TradeNotice.price(entry.referencePrice()) + " USDT"
                    + "\n原因 带单员同方向已平仓 · 平仓 " + TradeNotice.fullTime(close.time)
                    + (detectedAt > 0 ? " · 检测 " + TradeNotice.fullTime(detectedAt) : "")
                    + (entry.filled ? "\n已成交部分按平仓流程处理" : "")
                    + (entry.requestIssue.isEmpty() ? "" : "\n上次失败 " + entry.requestIssue));
        }
        return count;
    }

    void apply(Rules.Signal signal, boolean follow) {
        if (!signal.action.sideOnly() && config.symbols.contains(signal.symbol)) {
            leg(signal.symbol, signal.action.isLong).apply(signal, follow, strategy,
                    canApplyAdjustment(System.currentTimeMillis()));
        }
    }

    void log(String text) throws JSONException {
        log = append(log, text);
    }

    private static JSONArray append(JSONArray previous, String text) throws JSONException {
        JSONArray next = new JSONArray();
        for (int i = Math.max(0, previous.length() - 59); i < previous.length(); i++) next.put(previous.getString(i));
        return next.put(text);
    }

    List<Entry> waits() {
        List<Entry> list = new ArrayList<>();
        for (Leg leg : legs.values()) for (Entry entry : leg.entries) if (!entry.cancelled) list.add(entry);
        list.sort(java.util.Comparator.comparingLong(entry -> entry.signal.time));
        return list;
    }

    boolean cancelWait(String signalId) throws JSONException {
        for (Leg leg : legs.values()) {
            if (leg.adjustment != null && leg.adjustment.id().equals(signalId)) {
                leg.clearAdjustment();
                log("手动取消复制调仓 · " + leg.symbol + (leg.isLong ? " 多" : " 空"));
                return true;
            }
            java.util.Iterator<Entry> entries = leg.entries.iterator();
            while (entries.hasNext()) {
                Entry entry = entries.next();
                if (!entry.id().equals(signalId) || entry.cancelled) continue;
                boolean submitted = pending != null && signalId.equals(pending.optString("entry"));
                if (submitted) entry.cancelled = true;
                else {
                    entries.remove();
                    if (strategy == Rules.Strategy.EXPOSURE && !entry.filled) {
                        leg.copied = leg.copied.subtract(entry.signal.quantity.multiply(entry.fraction, MC)).max(ZERO);
                    }
                }
                log("手动取消等待 · " + entry.signal.symbol + " " + entry.signal.action.label + " · "
                        + TradeNotice.time(entry.signal.time) + (submitted ? " · 已发委托继续核对" : ""));
                return true;
            }
        }
        return false;
    }

    static boolean sameRules(Rules.Config a, Rules.Config b) {
        if (!sameUniverse(a, b)) return false;
        for (String symbol : a.symbols) if (!a.rule(symbol).sameSizing(b.rule(symbol))) return false;
        return true;
    }

    static boolean sameUniverse(Rules.Config a, Rules.Config b) {
        return a.portfolio.equals(b.portfolio) && a.symbols.equals(b.symbols);
    }
}
