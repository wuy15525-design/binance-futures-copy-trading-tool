package com.localcopy.terminal;
import java.math.*;
import java.util.*;
import org.json.*;
final class Display {
    static String waitDescription(FollowBook.Entry entry, String mode,
            AutoFollowService.WaitingPrices prices, BigDecimal slippage, BigDecimal leaderAverage) {
        Rules.Signal signal = entry.signal;
        BigDecimal reference = entry.referencePrice(leaderAverage);
        String title = signal.symbol + " " + signal.action.label + (entry.existing ? " · 补已有仓位" : "")
                + " · " + TradeNotice.time(signal.time)
                + (entry.existing && leaderAverage != null ? "\n带单仓位均价 "
                        : entry.recoveryAverage == null ? "\n参考价 " : "\n补页后推算均价 ")
                + TradeNotice.price(reference) + " USDT";
        if (!entry.existing && leaderAverage != null) {
            title += " · 带单仓位均价 " + TradeNotice.price(leaderAverage) + " USDT";
        }
        if (entry.fraction.compareTo(Rules.ONE) < 0) {
            title += " · 剩余跟单意向 " + Rules.show(entry.fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP)) + "%";
        }
        if (!entry.requestIssue.isEmpty()) {
            title += "\n上次请求 · " + entry.requestIssue;
            if (entry.checkFailedAt > 0) title += " · " + TradeNotice.time(entry.checkFailedAt);
            if (entry.checkFailures > 0) title += " · 连续 " + entry.checkFailures + " 次";
        }
        long age = System.currentTimeMillis() - prices.time;
        boolean matchingMode = mode.equals(prices.mode);
        Rules.Quote quote = matchingMode && age >= 0 && age <= 10000 ? prices.quotes.get(signal.symbol) : null;
        if (quote == null) {
            String issue = matchingMode ? prices.issues.get(signal.symbol) : null;
            if (issue != null) return title + "\n" + issue;
            return title + "\n等待可用盘口" + (matchingMode && !prices.error.isEmpty() ? " · " + prices.error : "");
        }
        BigDecimal current = quote.execution(signal.action);
        String market = title + "\n" + (signal.action.isLong ? "卖一 " : "买一 ") + Rules.show(current);
        if (leaderAverage != null) {
            return market + (Rules.favorableOpeningPrice(signal.action, current, leaderAverage)
                    ? " · 报价优于带单均价，仍需核对成交后均价" : " · 下单前核对成交后均价");
        }
        BigDecimal delta = signal.action.isLong ? current.subtract(reference) : reference.subtract(current);
        BigDecimal adverse = delta.max(Rules.ZERO).divide(reference, Rules.MC).movePointRight(2);
        return market + " · 不利价差 " + Rules.show(adverse.setScale(3, RoundingMode.HALF_UP)) + "% / "
                + Rules.show(slippage) + "% · " + (adverse.compareTo(slippage) > 0 ? "超限等待" : "盘口价差达标");
    }

    static String copyWaitDescription(FollowBook.Leg leg, BigDecimal slippage, String mode,
            AutoFollowService.WaitingPrices prices) {
        FollowBook.CopyAdjustment adjustment = leg.adjustment;
        String title = (leg.isLong ? "多" : "空") + " · " + adjustment.label() + " · 当前 " + Rules.show(leg.owned)
                + " · 目标 " + (adjustment.target == null ? "待核算" : Rules.show(adjustment.target));
        if (leg.adjustOnSignal) return title + "\n差额低于下单要求 · 等待下一笔带单成交";
        if (adjustment.target == null) return title;
        if (adjustment.target.compareTo(leg.owned) < 0) return title + "\n按新目标市价减仓";
        if (adjustment.target.compareTo(leg.owned) == 0) return title;
        title += "\n加仓最大不利价差 " + Rules.show(slippage) + "%";
        boolean setting = adjustment.fromSetting();
        BigDecimal reference = leg.leaderAverage == null ? adjustment.priceReference() : leg.leaderAverage;
        if (reference == null) return title + " · 等待参考价";
        String label = leg.leaderAverage != null ? "带单仓位均价 " : setting ? "参考均价 " : "带单成交价 ";
        title += " · " + label + TradeNotice.price(reference);
        long age = System.currentTimeMillis() - prices.time;
        Rules.Quote quote = mode.equals(prices.mode) && age >= 0 && age <= 10000 ? prices.quotes.get(leg.symbol) : null;
        if (quote == null) return title + " · 等待可用盘口";
        Rules.Action direction = leg.isLong ? Rules.Action.OPEN_LONG : Rules.Action.OPEN_SHORT;
        BigDecimal price = quote.execution(direction);
        String market = title + "\n" + (direction.buys() ? "卖一 " : "买一 ") + TradeNotice.price(price);
        if (leg.leaderAverage != null) {
            return market + (Rules.favorableOpeningPrice(direction, price, leg.leaderAverage)
                    ? " · 报价优于带单均价，仍需核对成交后均价" : " · 下单前核对成交后均价");
        }
        return market + (Rules.withinAdversePrice(direction, reference, slippage, price)
                ? " · 盘口价差达标" : " · 等待合适价格");
    }

    static String waitRequestStatus(FollowBook.Entry entry, JSONObject pending, boolean running, boolean opens,
            boolean websiteReady, long now) {
        if (pending != null && entry.id().equals(pending.optString("entry"))) {
            return "状态 · 原委托号待核对" + (running ? "" : " · 跟单已停止");
        }
        if (!running) return "状态 · 跟单未运行";
        if (!opens) return "状态 · 开仓已暂停";
        if (pending != null) return "状态 · 等待在途委托核对完成";
        if (!websiteReady) return "状态 · 等待官网成交核对完成";
        if (entry.waitingForCheckRetry(now)) {
            return "状态 · 自动重试排队 · 最早 " + TradeNotice.time(entry.checkRetryAt) + " 后检查";
        }
        if (entry.checkFailures > 0 || entry.requestIssue.endsWith("币安请求超时")) {
            return "状态 · 等待下一轮有效信号，自动重试开仓检查";
        }
        return "状态 · " + (entry.waitReason.isEmpty() ? "等待开仓条件满足" : entry.waitReason);
    }

    static String forecastText(LeaderRisk.Boundary value) {
        if (value == null) return "缺可用价格";
        if (value.below == null && value.above == null) return "无正值触发价";
        String down = value.below == null ? "" : "≈ " + TradeNotice.positionPrice(value.below) + " USDT";
        String up = value.above == null ? "" : "≈ " + TradeNotice.positionPrice(value.above) + " USDT";
        if (down.isEmpty() || up.isEmpty()) return down + up;
        return "下行 " + down + "\n上行 " + up;
    }

    static String leaderRiskDescription(LeaderArchive.State state, LeaderArchive.Projection projection,
            LocalData.LeaderBalance balance, AutoFollowService.LeaderMarket market, LeaderRisk.Result risk, long now) {
        List<String> lines = new ArrayList<>();
        if (projection.lastTrade > 0) lines.add("截至 " + TradeNotice.fullTime(projection.lastTrade));
        if (state.gap) lines.add("成交有缺口 · 按已见记录估算");
        if (risk.prices.isEmpty()) return String.join("\n", lines);
        lines.add("全仓估算 · 浮盈亏按最新成交价");
        if (balance != null) lines.add(balance.at > 0 ? "余额快照 " + TradeNotice.fullTime(balance.at) : "使用历史成交附带余额");
        else if (risk.assumptions.contains(LeaderRisk.Assumption.MARGIN)) {
            lines.add("余额未知 · 按 " + Rules.show(LeaderRisk.REFERENCE_LEVERAGE) + " 倍杠杆假设");
        }
        List<String> assumptions = new ArrayList<>();
        if (risk.assumptions.contains(LeaderRisk.Assumption.MARK)) assumptions.add("缺标记价用持仓均价");
        if (risk.assumptions.contains(LeaderRisk.Assumption.TIER)) {
            assumptions.add("缺档位按 " + Rules.show(LeaderRisk.REFERENCE_RATE.movePointRight(2)) + "% 维持率");
        }
        if (risk.assumptions.contains(LeaderRisk.Assumption.EXTENDED_TIER)) assumptions.add("超档位按末档估算");
        if (risk.assumptions.contains(LeaderRisk.Assumption.MISSING_PRICE)) assumptions.add("缺价仓位暂未计入强平估算");
        if (!market.tiers.isEmpty() && now - market.tiersAt > AutoFollowService.LEADER_TIER_FRESH_MS) {
            assumptions.add("沿用档位快照");
        }
        if (!assumptions.isEmpty()) lines.add(String.join(" · ", assumptions));
        if (risk.atMaintenance) lines.add("参考价已到估算维持线");
        return String.join("\n", lines);
    }

}
