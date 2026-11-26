package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class DemoOrders {
    private static final String FILE = "demo-orders";
    private static final BigDecimal FEE_BUFFER = new BigDecimal("0.001");
    private final DesktopContext context;
    private final LocalData store;
    private final FuturesApi api;
    private final JSONObject journal;
    private final JSONObject positions;

    DemoOrders(DesktopContext context) throws Exception {
        this.context = context.getApplicationContext();
        store = new LocalData(context);
        Secrets secrets = Secrets.load(store, FuturesApi.DEMO);
        api = new FuturesApi(FuturesApi.DEMO, secrets);
        String fingerprint = FuturesApi.accountFingerprint(secrets);
        JSONObject old = store.load(FILE);
        if (old != null && !fingerprint.equals(old.getString("account")) && hasWork(old)) {
            throw new IllegalStateException("请用原测试网账户核对订单和仓位");
        }
        journal = old != null && fingerprint.equals(old.getString("account")) ? old
                : new JSONObject().put("account", fingerprint).put("positions", new JSONObject())
                    .put("messages", new JSONArray());
        positions = journal.getJSONObject("positions");
    }

    static boolean hasWork(JSONObject journal) {
        if (journal == null) return false;
        JSONObject positions = journal.optJSONObject("positions");
        return journal.optJSONObject("pending") != null || (positions != null && positions.length() > 0);
    }

    static boolean hasWork(LocalData store) throws Exception { return hasWork(store.load(FILE)); }

    static void releaseFlatJournal(LocalData store, FollowExecutor.Exchange api, String account) throws Exception {
        JSONObject journal = store.load(FILE);
        if (!hasWork(journal)) return;
        if (!account.equals(journal.getString("account"))) {
            throw new IllegalStateException("请用原测试网账户核对旧演练订单和仓位");
        }
        JSONObject pending = journal.optJSONObject("pending");
        java.util.Set<String> tracked = new java.util.LinkedHashSet<>();
        java.util.Iterator<String> symbols = journal.getJSONObject("positions").keys();
        while (symbols.hasNext()) tracked.add(symbols.next());
        if (pending != null) tracked.add(pending.getString("symbol"));
        java.util.Map<String, FuturesApi.Position> positions = api.positions();
        java.util.Map<String, java.util.Set<String>> orders = api.openOrderIds();
        String id = pending == null ? "" : pending.getString("id");
        for (String symbol : tracked) {
            FuturesApi.Position position = positions.get(symbol);
            if (position != null && !position.flat()) {
                throw new IllegalStateException(symbol + " 旧演练仍有仓位，请在币安测试网核对");
            }
            if (orders.getOrDefault(symbol, java.util.Collections.emptySet()).stream()
                    .anyMatch(order -> order.equals(id) || order.matches("CT[0-9a-f]{32}"))) {
                throw new IllegalStateException(symbol + " 旧演练仍有未结委托，请在币安测试网核对");
            }
        }
        if (pending != null) {
            String symbol = pending.getString("symbol");
            try {
                JSONObject order = api.order(symbol, id);
                if (!symbol.equals(order.optString("symbol")) || !id.equals(order.optString("clientOrderId"))
                        || !FollowExecutor.terminal(order.optString("status"))) {
                    throw new IllegalStateException(symbol + " 旧演练委托仍待核对");
                }
            } catch (FuturesApi.Failure failure) {
                if (failure.code != -2013 || System.currentTimeMillis() - pending.getLong("at") < 30000) throw failure;
            }
        }
        journal.remove("pending");
        journal.put("positions", new JSONObject());
        store.save(FILE, journal);
    }

    static void checkWhitelist(LocalData store, java.util.Set<String> symbols) throws Exception {
        JSONObject journal = store.load(FILE);
        if (journal == null) return;
        JSONObject pending = journal.optJSONObject("pending");
        if (pending != null && !symbols.contains(pending.getString("symbol"))) {
            throw new IllegalArgumentException("请先核对该测试网合约的订单再移除");
        }
        java.util.Iterator<String> managed = journal.getJSONObject("positions").keys();
        while (managed.hasNext()) {
            if (!symbols.contains(managed.next())) throw new IllegalArgumentException("请平掉测试网仓位后移除合约");
        }
    }

    static String messages(LocalData store) throws Exception {
        JSONObject data = store.load(FILE);
        if (data == null) return "";
        JSONArray array = data.getJSONArray("messages");
        StringBuilder text = new StringBuilder();
        for (int i = array.length() - 1; i >= 0; i--) {
            if (text.length() > 0) text.append("\n\n");
            text.append(array.getString(i));
        }
        JSONObject pending = data.optJSONObject("pending");
        if (pending != null) {
            String header = "有一笔测试网订单待核对 · " + pending.getString("symbol");
            if (!pending.optString("issue").isEmpty()) header += "\n" + pending.getString("issue");
            return text.length() == 0 ? header : header + "\n\n" + text;
        }
        return text.toString();
    }

    String open(Rules.Config config, String symbol, Rules.Action action, BigDecimal reference)
            throws Exception {
        if (!action.opens) throw new IllegalArgumentException("请先选择开仓方向");
        canOperate(config, symbol);
        Rules.ContractRule rule = config.rule(symbol);
        if (!api.position(symbol).flat() || positions.has(symbol)) {
            throw new IllegalStateException("该测试网合约有仓位，请先核对或平仓");
        }
        FuturesApi.Contract contract = api.contract(symbol);
        FuturesApi.Wallet wallet = api.wallet();
        BigDecimal margin = rule.orderMargin(wallet.balance);
        if (margin.signum() <= 0) throw new IllegalStateException("按钱包余额计算的保证金为 0");
        boolean hedge = api.hedgeMode();
        Rules.Quote quote = api.quote(symbol);
        BigDecimal anchor = reference == null ? quote.execution(action) : reference;
        BigDecimal limit = Rules.entryLimit(action, anchor, rule.slippagePercent, contract.tick);
        if (!Rules.acceptable(action, limit, quote)) {
            return "不利价差超限，未发送测试网委托";
        }
        int leverage;
        try { leverage = applyLeverage(symbol, rule.leverage, margin); }
        catch (Exception failure) {
            return finishMessage(symbol + " " + action.label + " · 杠杆请求失败 · " + FollowExecutor.failureText(failure));
        }
        if (!api.position(symbol).flat()) throw new IllegalStateException("账户已有仓位，本次开仓取消");
        wallet = api.wallet();
        margin = rule.orderMargin(wallet.balance);
        if (margin.signum() <= 0) throw new IllegalStateException("按最新钱包余额计算的保证金为 0");
        int permitted = api.allowedLeverage(symbol, leverage, Rules.ZERO, margin, null);
        if (permitted < leverage) {
            try { leverage = applyLeverage(symbol, leverage, margin); }
            catch (Exception failure) {
                return finishMessage(symbol + " " + action.label + " · 杠杆请求失败 · " + FollowExecutor.failureText(failure));
            }
        }
        quote = api.quote(symbol);
        if (!Rules.acceptable(action, limit, quote)) return "最新盘口价差超限，未发送测试网开仓委托";
        BigDecimal basis = action.isLong ? limit : quote.execution(action);
        BigDecimal target = Rules.quantity(margin, leverage, basis, contract.lotStep);
        BigDecimal quantity = Rules.floor(target.min(quote.available(action)).min(contract.lotMax), contract.lotStep);
        BigDecimal estimated = quantity.multiply(basis);
        if (quantity.compareTo(contract.lotMin) < 0 || estimated.compareTo(contract.minValue) < 0) {
            throw new IllegalStateException("测试网订单低于合约最小数量或名义金额");
        }
        if (wallet.available.compareTo(margin.add(estimated.multiply(FEE_BUFFER))) < 0) {
            throw new IllegalStateException("测试网可用余额不足");
        }
        return send(config.portfolio, symbol, action, hedge, Rules.ZERO, quantity, limit, reference, leverage);
    }

    private int applyLeverage(String symbol, int requested, BigDecimal margin) throws Exception {
        int supported = api.allowedLeverage(symbol, requested, Rules.ZERO, margin, null);
        if (supported < 1) throw new IllegalStateException("本单金额超过杠杆档位的名义价值上限");
        int current = api.currentLeverage(symbol);
        if (current == supported) return current;
        int actual;
        try { actual = api.changeLeverage(symbol, supported); }
        catch (FuturesApi.Failure rejection) {
            int limit = rejection.statedLeverageCap();
            if (limit < 1 || limit >= supported) throw rejection;
            int lower = api.allowedLeverage(symbol, limit, Rules.ZERO, margin, null);
            if (lower < 1) throw rejection;
            actual = current == lower ? lower : api.changeLeverage(symbol, lower);
        }
        finishMessage(symbol + " 杠杆已确认 " + actual + " 倍");
        return actual;
    }

    String close(Rules.Config config, String symbol) throws Exception {
        canOperate(config, symbol);
        JSONObject managed = positions.optJSONObject(symbol);
        if (managed == null) return "没有由本机测试委托建立且已核对的仓位";
        Rules.Action action = Rules.Action.valueOf(managed.getString("close"));
        BigDecimal expected = new BigDecimal(managed.getString("qty"));
        FuturesApi.Contract contract = api.contract(symbol);
        boolean hedge = api.hedgeMode();
        BigDecimal position = api.position(symbol).size(action);
        if (position.signum() == 0) {
            positions.remove(symbol);
            store.save(FILE, journal);
            return "测试网该方向已空仓，已更新本机记录";
        }
        if (position.compareTo(expected) != 0) throw new IllegalStateException("测试网仓位与本机记录不一致");
        BigDecimal quantity = Rules.floor(position.min(contract.marketMax), contract.marketStep);
        if (quantity.compareTo(contract.marketMin) < 0) {
            throw new IllegalStateException("测试网剩余仓位低于市价单数量限制");
        }
        return send(config.portfolio, symbol, action, hedge, position, quantity, null, null, 0);
    }

    private void canOperate(Rules.Config config, String symbol) throws Exception {
        if (AutoFollowService.usesDemo() || AutoFollowService.hasExposure(store, AutoFollowService.DRILL)
                || AutoFollowService.hasExposure(store, AutoFollowService.DEMO_WEB)) {
            throw new IllegalStateException("测试网自动跟单还有仓位或待确认委托，请先处理");
        }
        if (!config.symbols.contains(symbol)) throw new IllegalArgumentException("合约不在跟踪名单");
        if (journal.has("pending")) throw new IllegalStateException("上一笔测试网订单待核对");
    }

    private String send(String portfolio, String symbol, Rules.Action action, boolean hedge, BigDecimal before,
            BigDecimal quantity, BigDecimal limit, BigDecimal reference, int leverage) throws Exception {
        String id = id();
        long at = System.currentTimeMillis();
        JSONObject pending = new JSONObject().put("id", id).put("symbol", symbol)
                .put("action", action.name()).put("qty", Rules.show(quantity))
                .put("before", Rules.show(before)).put("at", at).put("portfolio", portfolio)
                .put("reference", reference == null ? null : Rules.show(reference));
        if (action.opens) pending.put("leverage", leverage);
        journal.put("pending", pending);
        store.save(FILE, journal);
        String submitted = TradeNotice.submitted(action.label, symbol, at, quantity, Rules.base(symbol));
        try {
            api.submit(symbol, action, hedge, quantity, limit, id);
        } catch (FuturesApi.NotSent failure) {
            journal.remove("pending");
            return finishMessage(submitted.replace("确认中", "请求未发送 · " + failure.getMessage()));
        } catch (FuturesApi.Failure failure) {
            if (failure.definitivelyRejected()) {
                journal.remove("pending");
                return finishMessage(submitted.replace("确认中", "请求失败 · " + failure.getMessage()));
            }
            return pendingMessage(pending, "发送结果待核对 · " + failure.getMessage());
        } catch (Exception uncertain) {
            return pendingMessage(pending, "发送结果待核对 · " + FollowExecutor.failureText(uncertain));
        }
        return reconcile();
    }

    String reconcile() throws Exception {
        JSONObject pending = journal.optJSONObject("pending");
        if (pending == null) return "当前没有待核对的测试网订单";
        String id = pending.getString("id");
        String symbol = pending.getString("symbol");
        Rules.Action action = Rules.Action.valueOf(pending.getString("action"));
        BigDecimal requested = FuturesApi.decimal(pending, "qty");
        BigDecimal before = FuturesApi.decimal(pending, "before");
        try {
            JSONObject order = api.order(symbol, id);
            if (!symbol.equals(order.getString("symbol")) || !id.equals(order.getString("clientOrderId"))
                    || !action.exchangeSide().equals(order.getString("side"))
                    || requested.compareTo(FuturesApi.decimal(order, "origQty")) != 0) {
                return pendingMessage(pending, "订单回执与本机委托不一致");
            }
            String positionSide = order.getString("positionSide");
            if (!"BOTH".equals(positionSide) && !action.positionSide().equals(positionSide)) {
                return pendingMessage(pending, "持仓方向与本机委托不一致");
            }
            String status = order.getString("status");
            BigDecimal executed = FuturesApi.decimal(order, "executedQty");
            if (executed.signum() < 0 || executed.compareTo(requested) > 0) {
                return pendingMessage(pending, "成交数量超出本机委托范围");
            }
            BigDecimal reference = FuturesApi.optionalDecimal(pending, "reference");
            if ("NEW".equals(status) || "PARTIALLY_FILLED".equals(status)) {
                String progress = TradeNotice.submitted(action.label, symbol, pending.getLong("at"),
                        requested, Rules.base(symbol)).replace("确认中", "已受理");
                if (executed.signum() > 0) {
                    FuturesApi.Fills partial = api.fills(symbol, order.getLong("orderId"));
                    progress = validFills(partial, executed) ? TradeNotice.completed(action.label + "部分成交，待确认其余数量",
                            symbol, action, partial, null, reference, "手填参考价") : progress + " · 成交明细待核对";
                }
                return progress;
            }
            if (!terminal(status)) return pendingMessage(pending, "等待订单终态");
            BigDecimal expected = action.opens ? before.add(executed) : before.subtract(executed);
            if (expected.signum() < 0) return pendingMessage(pending, "成交数量超出本机管理仓位");
            FuturesApi.Fills fills = executed.signum() > 0 ? api.fills(symbol, order.getLong("orderId")) : null;
            if (fills != null && !validFills(fills, executed)) {
                return pendingMessage(pending, "成交明细仍在核对");
            }
            BigDecimal seen = api.position(symbol).size(action);
            if (seen.compareTo(expected) != 0) return pendingMessage(pending, "账户仓位仍在核对");
            String summary;
            if (fills == null) {
                summary = TradeNotice.submitted(action.label, symbol, pending.getLong("at"),
                        requested, Rules.base(symbol)).replace("确认中", "已结束，未成交");
            } else {
                String label = executed.compareTo(requested) < 0 || (!action.opens && seen.signum() > 0)
                        ? action.label + "部分成交" : action.label + "已成交";
                summary = TradeNotice.completed(label, symbol, action, fills, seen, reference, "手填参考价");
                int leverage = pending.optInt("leverage");
                if (action.opens && leverage > 0) summary += "\n实际杠杆 " + leverage + " 倍";
                String portfolio = pending.optString("portfolio", store.config().portfolio);
                LeaderArchive.open(context).saveAccountFill(portfolio, AutoFollowService.MANUAL, id, fills.latest, summary);
                if (seen.signum() == 0) positions.remove(symbol);
                else positions.put(symbol, new JSONObject().put("close",
                        (action.isLong ? Rules.Action.CLOSE_LONG : Rules.Action.CLOSE_SHORT).name())
                        .put("qty", Rules.show(seen)));
            }
            journal.remove("pending");
            finishMessage(summary);
            if (fills != null) Notices.trade(context, id, "币安测试网", summary, true);
            return summary;
        } catch (Exception uncertain) {
            if (!journal.has("pending")) throw uncertain;
            return pendingMessage(pending, "核对请求失败 · " + FollowExecutor.failureText(uncertain));
        }
    }

    private static boolean validFills(FuturesApi.Fills fills, BigDecimal executed) {
        return fills.quantity.compareTo(executed) == 0 && fills.latest > 0 && fills.value.signum() > 0;
    }

    private static boolean terminal(String status) {
        return "FILLED".equals(status) || "CANCELED".equals(status) || "EXPIRED".equals(status)
                || "EXPIRED_IN_MATCH".equals(status) || "REJECTED".equals(status);
    }

    private String pendingMessage(JSONObject pending, String reason) throws Exception {
        String symbol = pending.getString("symbol");
        String summary = TradeNotice.submitted(Rules.Action.valueOf(pending.getString("action")).label,
                symbol, pending.getLong("at"), FuturesApi.decimal(pending, "qty"), Rules.base(symbol))
                .replace("确认中", reason);
        if (!reason.equals(pending.optString("issue"))) {
            pending.put("issue", reason);
            store.save(FILE, journal);
        }
        return summary;
    }

    private String finishMessage(String message) throws Exception {
        JSONArray old = journal.getJSONArray("messages");
        JSONArray next = new JSONArray();
        for (int i = Math.max(0, old.length() - 79); i < old.length(); i++) next.put(old.getString(i));
        next.put(message);
        journal.put("messages", next);
        store.save(FILE, journal);
        return message;
    }

    private static String id() { return "CT" + UUID.randomUUID().toString().replace("-", ""); }
}
