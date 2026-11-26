package com.localcopy.terminal;

import java.math.BigDecimal;
import org.json.JSONException;

import static com.localcopy.terminal.Rules.MC;
import static com.localcopy.terminal.Rules.ZERO;

/** A read-only snapshot and the result of preparing one copy adjustment. */
final class CopyPlan {
    enum Result { WAIT, FINISH, DEFER, SET_LEVERAGE, SEND }

    static final class Request {
        final String key, revision, symbol, label;
        final boolean isLong, protectedExisting, leaderAveragesVerified, usesAdjustmentSlippage;
        final BigDecimal leader, copied, factor, slippage, leaderAverage;
        final FollowBook.CopyAdjustment adjustment;
        final Rules.ContractRule rule;
        final long walletRevision;
        final int leverageCap;

        Request(FollowBook book, FollowBook.Leg leg, Rules.ContractRule rule, long walletRevision,
                int leverageCap) throws JSONException {
            symbol = leg.symbol;
            isLong = leg.isLong;
            key = symbol + (isLong ? "|LONG" : "|SHORT");
            label = symbol + (isLong ? " 多 " : " 空 ") + leg.adjustment.label() + " · ";
            protectedExisting = leg.protectedExisting;
            leaderAveragesVerified = book.leaderAveragesVerified;
            leaderAverage = leg.leaderAverage;
            usesAdjustmentSlippage = leg.adjustmentUsesCopySlippage();
            leader = leg.leader;
            copied = leg.copied;
            factor = book.copyFactor;
            slippage = book.copySlippage;
            this.rule = rule;
            this.walletRevision = walletRevision;
            this.leverageCap = leverageCap;
            org.json.JSONObject serialized = leg.adjustment.json();
            serialized.remove("waitReason");
            serialized.remove("lastUnfilled");
            adjustment = FollowBook.CopyAdjustment.read(serialized);
            revision = adjustment.id() + "|" + serialized + "|" + leader + "|" + copied + "|" + protectedExisting
                    + "|" + factor + "|" + slippage + "|" + rule.leverage + "|" + rule.slippagePercent
                    + "|" + leverageCap + "|" + leaderAveragesVerified + "|" + leaderAverage
                    + "|" + usesAdjustmentSlippage;
        }
    }

    final Request request;
    Result result = Result.WAIT;
    FuturesApi.Position position;
    long checkedAt;
    BigDecimal target, migratedCopied, reference;
    BigDecimal quantity, price, required;
    Rules.Action action;
    String message;
    boolean hedge, fullFill;
    int leverage;

    private CopyPlan(Request request) {
        this.request = request;
    }

    private CopyPlan finish(Result result, String detail) {
        this.result = result;
        message = request.label + detail;
        return this;
    }

    private CopyPlan waitFor(String detail) { return finish(Result.WAIT, detail); }

    static CopyPlan prepare(Request in, FollowExecutor.Exchange reader, boolean allowIncrease, BigDecimal feeBuffer)
            throws Exception {
        CopyPlan plan = new CopyPlan(in);
        FollowBook.CopyAdjustment adjustment = in.adjustment;
        BigDecimal leaderMargin = adjustment.leaderMargin();
        if (leaderMargin == null || leaderMargin.signum() <= 0) return plan.waitFor("等待带单保证金余额");
        Rules.Action close = in.isLong ? Rules.Action.CLOSE_LONG : Rules.Action.CLOSE_SHORT;
        plan.checkedAt = System.currentTimeMillis();
        plan.position = reader.position(in.symbol);
        BigDecimal actual = plan.position.size(close);
        boolean leaderAdded = adjustment.signal != null && adjustment.signal.action.opens;
        if (in.protectedExisting || (adjustment.fromSetting() && actual.signum() == 0 && !leaderAdded)) {
            return plan.finish(Result.FINISH, "账户无纳入跟单的对应仓位，已结束本次委托");
        }

        FuturesApi.Wallet wallet = null;
        plan.target = adjustment.target;
        if (plan.target == null) {
            wallet = reader.copyWallet();
            if (wallet.balance.signum() <= 0) return plan.waitFor("等待本账户保证金余额");
            if (adjustment.legacyVisible != null) {
                BigDecimal before = actual.multiply(leaderMargin, MC)
                        .divide(wallet.balance, MC).min(adjustment.legacyVisible);
                plan.migratedCopied = before.multiply(adjustment.legacyRetained, MC)
                        .add(adjustment.legacyAdded).min(in.leader);
            }
            BigDecimal copied = plan.migratedCopied == null ? in.copied : plan.migratedCopied;
            plan.target = copied.multiply(in.factor, MC).multiply(wallet.balance, MC).divide(leaderMargin, MC);
        }
        BigDecimal delta = plan.target.subtract(actual);
        if (delta.signum() == 0) {
            return plan.finish(Result.FINISH, "已达到 " + Rules.show(plan.target) + " " + Rules.base(in.symbol));
        }

        boolean increase = delta.signum() > 0;
        if (increase && !allowIncrease) return plan.waitFor("等待允许加仓");
        if (increase && in.leaderAveragesVerified && in.leaderAverage == null) {
            return plan.waitFor("等待核对带单仓位均价");
        }
        plan.hedge = reader.hedgeMode();
        BigDecimal other = in.isLong ? plan.position.shorts : plan.position.longs;
        if (increase && !plan.hedge && other.signum() > 0) return plan.waitFor("等待反向仓位平仓");
        plan.action = increase ? (in.isLong ? Rules.Action.OPEN_LONG : Rules.Action.OPEN_SHORT) : close;
        FuturesApi.Contract contract = reader.contract(in.symbol);
        if (!increase) {
            plan.quantity = contract.marketClosingQuantity(delta.abs());
            if (plan.quantity.signum() == 0) {
                return plan.finish(Result.DEFER, "目标差额低于市价单最小委托量");
            }
            return plan.verify(reader, Result.SEND);
        }

        Rules.Quote quote = reader.quote(in.symbol);
        BigDecimal atBook = Rules.iocPrice(plan.action, quote, contract.tick);
        if (atBook.signum() <= 0) return plan.waitFor("等待有效盘口");
        plan.price = atBook;
        if (in.leaderAverage == null) {
            BigDecimal reference = adjustment.priceReference();
            if (adjustment.fromSetting() && (reference == null || reference.signum() <= 0)) {
                BigDecimal entry = plan.position.entryPrice(plan.action);
                reference = entry != null && entry.signum() > 0 ? entry : adjustment.leaderEntry;
                plan.reference = reference;
            }
            if (reference == null || reference.signum() <= 0) return plan.waitFor("等待可核对的开仓均价");
            if (!Rules.withinAdversePrice(plan.action, reference, in.slippage, atBook)) {
                String priceRule = adjustment.fromSetting() ? "调仓不利价差" : "跟单不利价差";
                return plan.waitFor("超出" + priceRule + " " + Rules.show(in.slippage) + "%，继续等待");
            }
            if (!adjustment.fromSetting()) {
                plan.price = Rules.entryLimit(plan.action, reference, in.slippage, contract.tick);
            }
        }
        plan.quantity = contract.openingQuantity(delta.min(contract.lotMax));
        if (plan.quantity.signum() == 0 || plan.quantity.multiply(plan.price).compareTo(contract.minValue) < 0) {
            return plan.finish(Result.DEFER, "目标差额低于合约最小委托量或金额");
        }
        if (adjustment.fromSetting() || in.leaderAverage != null) {
            plan.quantity = contract.openingQuantity(plan.quantity.min(quote.available(plan.action)));
            if (plan.quantity.signum() == 0 || plan.quantity.multiply(plan.price).compareTo(contract.minValue) < 0) {
                return plan.waitFor("盘口可成交量低于下单要求，继续等待");
            }
        }
        if (in.leaderAverage != null) {
            BigDecimal tolerance = in.usesAdjustmentSlippage ? in.slippage : in.slippage.min(in.rule.slippagePercent);
            String issue = Rules.openingAverageIssue(plan.action, plan.position, plan.quantity, plan.price,
                    in.leaderAverage, tolerance);
            if (!issue.isEmpty()) return plan.waitFor(issue);
            plan.fullFill = Rules.openingNeedsFullFill(plan.action, plan.position, in.leaderAverage, tolerance);
        }
        if (wallet == null) wallet = reader.copyWallet();
        int configured = reader.currentLeverage(in.symbol);
        int held = plan.position.flat() ? 0 : configured;
        if (!plan.position.flat() && held < 1) return plan.waitFor("当前杠杆待核对");
        BigDecimal value = plan.quantity.multiply(plan.price);
        plan.leverage = reader.allowedForNotional(in.symbol, held == 0 ? Math.min(in.rule.leverage, in.leverageCap) : held,
                plan.position.exposure(quote.ask), value);
        if (plan.leverage < Math.max(1, held)) return plan.waitFor("超过当前杠杆档位的名义金额上限");
        plan.required = value.divide(BigDecimal.valueOf(plan.leverage), MC).add(value.multiply(feeBuffer, MC));
        if (wallet.available.compareTo(plan.required) < 0) return plan.waitFor("可用保证金不足");
        return plan.verify(reader, plan.position.flat() && configured != plan.leverage ? Result.SET_LEVERAGE : Result.SEND);
    }

    private CopyPlan verify(FollowExecutor.Exchange reader, Result ready) throws Exception {
        checkedAt = System.currentTimeMillis();
        FuturesApi.Position latest = reader.position(request.symbol);
        if (!position.sameOpeningBasis(latest, action)) {
            position = latest;
            // The previous quantity and target inputs do not describe this account snapshot.
            target = migratedCopied = reference = null;
            return waitFor("账户仓位已更新，下轮重算差额");
        }
        return finish(ready, "委托准备就绪");
    }
}
