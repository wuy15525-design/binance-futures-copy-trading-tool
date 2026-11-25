package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.Action.*;
import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

public final class AutomaticTests {
    private static final String BTC = "BTCUSDT";
    private static final BigDecimal BAND = d("0.3");
    private static final long TIME = 1795050000000L;
    private static int passed;

    public static void main(String[] args) throws Exception {
        test("启动建立基线，历史仓位和重复记录都不会触发开仓", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal old = signal(OPEN_LONG, 1, "2");
            require(s.book.accept(List.of(old)) == 0);
            s.tick();
            require(s.api.submissions == 0);
            Rules.Signal fresh = signal(OPEN_LONG, 2, "10");
            require(s.book.accept(List.of(old, fresh)) == 1);
            s.tick();
            require(s.api.submissions == 1);
            require(s.book.accept(List.of(old, fresh)) == 0);
            s.restart();
            s.tick();
            require(s.api.submissions == 1);
        });
        test("空页、漏页和重叠记录修改均保留现有仓位与水位", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal old = signal(CLOSE_LONG, 1, "2");
            Rules.Signal open = signal(OPEN_LONG, 2, "10");
            s.book.accept(List.of(old));
            s.book.accept(List.of(old, open));
            s.tick();
            String anchor = s.book.anchor();
            rejected(() -> s.book.accept(List.of()));
            rejected(() -> s.book.accept(List.of(signal(CLOSE_LONG, 3, "10"))));
            rejected(() -> s.book.accept(List.of(signal(CLOSE_LONG, 1, "3"), open, signal(CLOSE_LONG, 3, "10"))));
            require(anchor.equals(s.book.anchor()));
            equal(d("1"), s.book.leg(BTC, true).owned);
            s.book.accept(List.of(old, open, signal(CLOSE_LONG, 3, "10")));
            s.tick();
            equal(ZERO, s.book.leg(BTC, true).owned);
        });
        test("空窗内已经开平的信号在恢复后不会开一笔过时仓位", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal old = signal(CLOSE_LONG, 1, "2");
            s.book.accept(List.of(old));
            s.book.accept(List.of(old, signal(OPEN_LONG, 2, "10"), signal(CLOSE_LONG, 3, "10")));
            s.tick();
            require(s.api.submissions == 0 && s.book.countEntries() == 0);
        });
        test("补页完成后净仓位未变，保留实际仓位且跳过缺口内来回开平", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 0, "0.6"), false);
            s.api.position.longs = d("0.2");
            s.book.acceptRecovered(List.of(signal(OPEN_LONG, 1, "1"), signal(CLOSE_LONG, 2, "1")),
                    true, java.util.Map.of(), java.util.Map.of());
            s.tick();
            require(s.api.submissions == 0 && s.book.countEntries() == 0);
            require(s.book.detections.length() == 2);
            equal(d("0.2"), s.api.position.longs);
            equal(d("0.6"), s.book.leg(BTC, true).leader);
        });
        test("缺口内旧持仓完全平掉后再开同等数量，移除旧的等待开仓", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 0, "0.6"), true);
            s.book.acceptRecovered(List.of(signal(CLOSE_LONG, 1, "0.6"), signal(OPEN_LONG, 2, "0.6")),
                    true, java.util.Map.of(), java.util.Map.of());
            s.tick();
            require(s.book.countEntries() == 0 && s.api.submissions == 0);
            equal(d("0.6"), s.book.leg(BTC, true).leader);
            require(s.book.log.getString(0).contains("自动取消开仓等待"));
        });
        test("补页后的净加仓只保留仍在仓位上的份额，点差用当前推算均价", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 0, "0.6"), false);
            Rules.Signal addition = signal(OPEN_LONG, 1, "0.5", "80");
            s.book.acceptRecovered(List.of(addition, signal(CLOSE_LONG, 2, "0.3", "90")),
                    true, java.util.Map.of(), java.util.Map.of(BTC + "|LONG",
                            new LeaderHistory.Holding(d("0.8"), d("100"), TIME)));
            require(s.book.waits().size() == 1 && s.book.waits().get(0).id().equals(addition.id));
            equal(d("0.4"), s.book.waits().get(0).fraction);
            s.execution.commit();
            s.restart();
            equal(d("100"), s.book.waits().get(0).referencePrice());
            s.api.price = d("101");
            s.tick();
            require(s.api.submissions == 0);
            s.api.price = d("99");
            s.tick();
            require(s.api.submissions == 1);
            require(s.events.get(0).contains("补页后推算均价 100 USDT"));
        });
        test("复制模式补页净减仓按当前实际仓位比例执行一次", () -> {
            Rules.Config config = new Scenario(false).book.config;
            Scenario s = new Scenario(config, Rules.Strategy.EXPOSURE);
            s.book.apply(signal(OPEN_LONG, 0, "0.6"), false);
            FollowBook.Leg side = s.book.leg(BTC, true);
            side.owned = side.target = d("0.12");
            s.api.position.longs = d("0.2");
            s.book.acceptRecovered(List.of(signal(OPEN_LONG, 1, "0.4"), signal(CLOSE_LONG, 2, "0.7")),
                    true, java.util.Map.of(), java.util.Map.of());
            s.execution.commit();
            s.restart();
            s.tick();
            require(s.api.submissions == 1 && s.api.copyReads == 0);
            equal(d("0.1"), s.api.position.longs);
            s.tick();
            require(s.api.submissions == 1);
        });
        test("按顺序显示加仓、减仓和平仓并缩减或取消等待", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(OPEN_LONG, 1, "6");
            Rules.Signal add = signal(OPEN_LONG, 2, "4");
            Rules.Signal trim = signal(CLOSE_LONG, 3, "3");
            Rules.Signal close = signal(CLOSE_LONG, 4, "7");
            s.book.accept(List.of(baseline));
            require(s.book.accept(List.of(baseline, add, trim)) == 2);
            require(s.book.detections.getString(0).contains("加多 4"));
            require(s.book.detections.getString(1).contains("减多 3"));
            equal(d("0.7"), s.book.waits().get(0).fraction);
            s.api.price = d("110");
            require(s.tick().contains("价差超限"));
            s.book.accept(List.of(baseline, add, trim, close));
            require(s.book.detections.getString(2).contains("已取消 1 笔等待"));
            s.api.price = d("89");
            s.tick();
            require(s.book.waits().isEmpty() && s.api.submissions == 0);
        });
        test("手动只取消目标信号，重启和重复读取不会重新排队", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(CLOSE_LONG, 1, "1");
            Rules.Signal first = signal(OPEN_LONG, 2, "3");
            Rules.Signal second = signal(OPEN_LONG, 3, "4");
            s.book.accept(List.of(baseline));
            s.book.accept(List.of(baseline, first, second));
            require(s.book.cancelWait(first.id) && !s.book.cancelWait(first.id));
            s.execution.commit();
            s.restart();
            require(s.book.accept(List.of(baseline, first, second)) == 0);
            require(s.book.waits().size() == 1 && s.book.waits().get(0).signal.id.equals(second.id));
            s.tick();
            require(s.api.submissions == 1);
        });
        test("取消发生在委托写盘后仍会阻止发单", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal signal = signal(OPEN_LONG, 1, "10");
            s.book.apply(signal, true);
            s.savedHook = () -> {
                if (s.book.pending != null) s.cancelledIds.add(signal.id);
            };
            s.tick();
            require(s.api.submissions == 0 && s.book.pending == null);
        });
        test("取消时已发部分成交委托，只取消剩余并继续处理平仓", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal open = signal(OPEN_LONG, 1, "10");
            s.book.apply(open, true);
            s.api.partial = d("0.4");
            s.api.loseResponse = true;
            s.tick();
            require(s.book.cancelWait(open.id) && s.book.waits().isEmpty() && s.book.pending != null);
            s.execution.commit();
            s.restart();
            s.tick();
            equal(d("0.4"), s.book.leg(BTC, true).owned);
            require(s.book.waits().isEmpty() && s.api.submissions == 1);
            s.book.apply(signal(CLOSE_LONG, 2, "10"), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
        });
        test("同秒不同方向新增可检测，只监测模式不建立等待", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal first = signal(CLOSE_LONG, 1, "1");
            Rules.Signal shortOpen = signal(OPEN_SHORT, 1, "2");
            s.book.accept(List.of(first), false);
            require(s.book.accept(List.of(first, shortOpen), false) == 1);
            require(s.book.detections.getString(0).contains("开空 2") && s.book.waits().isEmpty());
            require(s.book.accept(List.of(first, shortOpen), false) == 0);
            rejected(() -> s.book.accept(List.of(first, signal(OPEN_SHORT, 1, "3")), false));
        });
        test("已暂停时平仓继续执行，待跟信号跨恢复且只拦不利价差", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.price = d("101");
            require(s.tick().contains("价差超限"));
            require(s.api.submissions == 0);
            s.execution.commit();
            s.restart();
            s.api.price = d("80");
            s.tick();
            equal(d("1.25"), s.api.position.longs);
            s.book.apply(signal(CLOSE_LONG, 2, "10"), true);
            s.api.price = d("30");
            s.execution.tick(s.book.config, () -> true, () -> false);
            equal(ZERO, s.api.position.longs);
            require(s.api.lastLimit == null && !s.api.hedge);
            require(s.events.stream().anyMatch(e -> e.contains("平多已成交") && e.contains("持仓 0 BTC")));
        });
        test("定比读取总钱包余额并在订单前应用统一杠杆", () -> {
            Scenario s = new Scenario(true);
            s.api.available = d("100");
            s.api.leverage = 2;
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            require(s.tick().contains("杠杆已确认") && s.api.submissions == 0);
            s.tick();
            equal(d("2.50"), s.api.position.longs);
            require(s.api.leverage == 5 && s.api.leverageChanges == 1);
        });
        test("预设 50 倍按合约上限 20 倍执行，重启后用实际杠杆补算部分成交的保证金", () -> {
            Scenario s = new Scenario(false, Set.of(BTC), 50);
            s.api.brackets = List.of(new FuturesApi.LeverageBracket(20, d("10000")));
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            require(s.tick().contains("20 倍") && s.api.leverage == 20 && s.api.submissions == 0);
            require(s.events.isEmpty());
            s.api.partial = d("1");
            s.api.loseResponse = true;
            require(s.tick().contains("未确认"));
            equal(d("4"), s.api.lastQuantity);
            require(s.saved.getJSONObject("pending").getInt("leverage") == 20 && s.events.isEmpty());
            s.restart();
            require(s.tick().contains("实际杠杆 20 倍"));
            equal(d("15"), s.book.waits().get(0).margin);
            s.tick();
            equal(d("3"), s.api.lastQuantity);
            equal(d("4"), s.api.position.longs);
            require(s.api.leverageChanges == 1 && s.events.size() == 2);
        });
        test("补已有仓位部分成交后，低于最小数量的尾量结束并且重启后不重发", () -> {
            Scenario s = new Scenario(false, Set.of(BTC), 50);
            s.api.brackets = List.of(new FuturesApi.LeverageBracket(20, d("10000")));
            s.api.contract = new FuturesApi.Contract(d("0.01"), d("0.1"), d("0.2"), d("100"),
                    d("0.1"), d("0.2"), d("100"), d("5"));
            s.book.finishInitialChoice(List.of(signal(OPEN_LONG, 1, "50", "110")));
            require(s.tick().contains("20 倍"));
            s.api.partial = d("3.9");
            require(s.tick().contains("部分成交"));
            equal(d("4"), s.api.lastQuantity);
            equal(d("0.5"), s.book.waits().get(0).margin);
            s.restart();
            require(s.tick().contains("低于最小委托量") && s.book.waits().isEmpty());
            s.restart();
            s.tick();
            equal(d("3.9"), s.api.position.longs);
            require(s.api.submissions == 1 && s.events.size() == 1);
        });
        test("盘口深度暂时不够时保留合法尾量，盘口恢复后按原预算继续", () -> {
            Scenario s = new Scenario(false);
            s.api.contract = new FuturesApi.Contract(d("0.01"), d("0.1"), d("0.1"), d("100"),
                    d("0.1"), d("0.1"), d("100"), d("5"));
            s.book.finishInitialChoice(List.of(signal(OPEN_LONG, 1, "10", "110")));
            s.api.partial = d("0.4");
            s.tick();
            s.api.depth = d("0.02");
            require(s.tick().contains("盘口可成交量暂时") && s.api.submissions == 1 && s.book.waits().size() == 1);
            s.api.depth = d("10");
            s.tick();
            equal(d("0.6"), s.api.lastQuantity);
            equal(d("1"), s.api.position.longs);
        });
        test("币安明确拒绝低于最小数量的尾量时，不重复下相同或更小的单", () -> {
            Scenario s = new Scenario(false);
            s.book.finishInitialChoice(List.of(signal(OPEN_LONG, 1, "10", "110")));
            s.api.partial = d("0.94");
            s.tick();
            s.api.rejection = new FuturesApi.Failure(400, -4004, "Quantity less than min quantity.");
            require(s.tick().contains("-4004") && s.book.pending == null);
            s.restart();
            require(s.tick().contains("交易所已确认该数量低于最小委托量") && s.book.waits().isEmpty());
            require(s.api.submissions == 2 && s.events.size() == 1);
        });
        test("最低名义金额拒单后，相同报价不重发，金额足够时才能继续", () -> {
            Scenario s = new Scenario(false);
            s.book.finishInitialChoice(List.of(signal(OPEN_LONG, 1, "10", "120")));
            s.api.price = d("90");
            s.api.partial = d("0.8");
            s.tick();
            s.api.price = d("80");
            s.api.rejection = new FuturesApi.Failure(400, -4164, "Order's notional must be no smaller than 25.5 (unless you choose reduce only)");
            require(s.tick().contains("-4164"));
            s.restart();
            s.tick();
            require(s.api.submissions == 2 && s.book.waits().size() == 1);
            s.api.price = d("85");
            s.tick();
            require(s.api.submissions == 3 && s.book.waits().isEmpty());
            equal(d("1.111"), s.api.position.longs);
        });
        test("按交易所最小数量和数量步长向下取合法委托量", () -> {
            FuturesApi.Contract contract = new FuturesApi.Contract(d("0.01"), d("0.1"), d("0.15"),
                    d("100"), d("0.01"), d("0.01"), d("100"), d("5"));
            equal(d("0.25"), contract.openingQuantity(d("0.27")));
            equal(d("0.15"), contract.openingQuantity(d("24.9999"), d("100")));
            equal(ZERO, contract.openingQuantity(d("0.1499")));
        });
        test("币安拒绝预设杠杆并明确给出更低上限，自动调整后按新数量开仓", () -> {
            Scenario s = new Scenario(false, Set.of(BTC), 50);
            s.api.rejectedLeverageCap = 15;
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            try {
                s.tick();
                throw new AssertionError("模拟的杠杆限制未发生");
            } catch (FuturesApi.Failure failure) {
                require(failure.code == -4209 && failure.statedLeverageCap() == 15);
            }
            s.restart();
            require(s.book.waits().get(0).requestIssue.contains("-4209") && s.events.isEmpty());
            require(s.tick().contains("15 倍") && s.api.submissions == 0 && s.events.isEmpty());
            require(s.tick().contains("实际杠杆 15 倍"));
            equal(d("3"), s.api.lastQuantity);
            require(s.events.size() == 1);
        });
        test("杠杆档位在现有仓位和剩余委托预算下选择最高可用倍数", () -> {
            JSONObject apiReply = new JSONObject().put("symbol", BTC).put("notionalCoef", 2)
                    .put("brackets", new org.json.JSONArray()
                            .put(new JSONObject().put("initialLeverage", 20).put("notionalCap", 1000))
                            .put(new JSONObject().put("initialLeverage", 10).put("notionalCap", "10000")));
            List<FuturesApi.LeverageBracket> brackets = FuturesApi.readBrackets(new org.json.JSONArray().put(apiReply), BTC);
            require(FuturesApi.highestLeverage(brackets, 50, d("1000"), d("70"), null) == 14);
            require(FuturesApi.highestLeverage(brackets, 50, d("1000"), d("70"), d("100")) == 20);
            require(FuturesApi.highestLeverage(brackets, 7, d("1800"), d("70"), null) == 7);
            require(FuturesApi.highestLeverage(brackets, 50, d("19999"), d("70"), null) == 0);
            require(FuturesApi.readBrackets(apiReply, BTC).size() == 2);
            require(new FuturesApi.Failure(400, -4206, "Users in your location/country can only access a maximum leverage of 9")
                    .statedLeverageCap() == 9);
            require(new FuturesApi.Failure(400, -4028, "Leverage 50 is not valid").statedLeverageCap() == 0);
        });
        test("部分 IOC 成交只补余额，随后按公开平仓数量比例减仓", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.partial = d("0.4");
            s.tick();
            equal(d("0.4"), s.book.leg(BTC, true).owned);
            s.restart();
            s.tick();
            equal(d("0.6"), s.api.lastQuantity);
            equal(d("1.0"), s.book.leg(BTC, true).owned);
            s.book.apply(signal(CLOSE_LONG, 2, "3"), true);
            s.tick();
            equal(d("0.3"), s.api.lastQuantity);
            equal(d("0.7"), s.api.position.longs);
            s.book.apply(signal(CLOSE_LONG, 3, "7"), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
        });
        test("首笔平仓没有可见开仓，跳过且不动账户同向仓位", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal unrelated = new Rules.Signal("ETHUSDT", OPEN_LONG, TIME, d("3000"), d("1"));
            s.book.accept(List.of(unrelated), true);
            s.api.position.longs = d("0.2");
            s.book.accept(List.of(unrelated, signal(CLOSE_LONG, 1, "0.3", "60000")), true);
            require(s.book.detections.getString(0).contains("未见对应开仓，已跳过"));
            s.tick();
            require(s.api.submissions == 0);
            equal(d("0.2"), s.api.position.longs);
            equal(ZERO, s.book.leg(BTC, true).leader);
        });
        test("已有可见开仓但账户为空时，首笔平仓只更新推算", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(OPEN_LONG, 0, "0.6", "30000");
            s.book.accept(List.of(baseline), true);
            s.book.accept(List.of(baseline, signal(CLOSE_LONG, 1, "0.3", "60000")), true);
            require(s.tick().contains("本账户无对应多仓，已跳过平仓"));
            require(s.api.submissions == 0 && !s.book.leg(BTC, true).needsReduction());
            equal(d("0.3"), s.book.leg(BTC, true).leader);
        });
        test("高成交额仍按实际持仓比例减仓，重启后一次性执行", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(OPEN_LONG, 0, "0.4", "30000");
            s.book.accept(List.of(baseline), true);
            s.book.acceptNew(List.of(signal(OPEN_LONG, 1, "0.2", "30000")), true);
            FollowBook.Leg leg = s.book.leg(BTC, true);
            leg.entries.clear();
            leg.owned = leg.target = d("0.12");
            s.api.position.longs = d("0.2");
            s.book.acceptNew(List.of(signal(CLOSE_LONG, 2, "0.3", "60000")), true);
            s.execution.commit();
            s.restart();
            s.tick();
            equal(d("0.10"), s.api.lastQuantity);
            equal(d("0.10"), s.api.position.longs);
            equal(d("0.30"), s.book.leg(BTC, true).leader);
            s.restart();
            s.tick();
            require(s.api.submissions == 1);
        });
        test("平仓量超过推算仓位时全平，双向模式保留反向仓位", () -> {
            Scenario s = new Scenario(false);
            s.api.hedge = true;
            s.execution.syncAccount(java.util.Map.of(), TIME);
            s.api.position.shorts = d("0.2");
            s.api.position.longs = d("0.4");
            s.book.apply(signal(OPEN_SHORT, 0, "0.6", "20000"), false);
            s.book.apply(signal(CLOSE_SHORT, 1, "0.65", "10000"), true);
            s.tick();
            equal(d("0.2"), s.api.lastQuantity);
            equal(ZERO, s.api.position.shorts);
            equal(d("0.4"), s.api.position.longs);
            equal(ZERO, s.book.leg(BTC, false).leader);
        });
        test("同批减仓分开执行，重启后通知仍引用各自的带单员成交价", () -> {
            Scenario s = new Scenario(false);
            s.execution.syncAccount(java.util.Map.of(), TIME);
            s.api.position.longs = d("0.2");
            s.api.price = d("105");
            s.api.realizedPnl = d("-2.25");
            s.book.apply(signal(OPEN_LONG, 0, "0.6", "100"), false);
            s.book.acceptNew(List.of(signal(CLOSE_LONG, 1, "0.3", "100"),
                    signal(CLOSE_LONG, 2, "0.15", "120")), true);
            s.execution.commit();
            s.restart();
            String first = s.tick();
            equal(d("0.1"), s.api.lastQuantity);
            require(first.contains("成交均价 105 USDT") && first.contains("已实现盈亏 -2.25 USDT")
                    && first.contains("带单员成交均价 100 USDT · 点差 -5%（有利）"));
            s.restart();
            String second = s.tick();
            equal(d("0.05"), s.api.lastQuantity);
            equal(d("0.05"), s.api.position.longs);
            require(second.contains("带单员成交均价 120 USDT · 点差 +12.5%（不利）"));
            s.tick();
            require(s.api.submissions == 2);
        });
        test("补仓信号保留推算仓位，重启和再次确认均不重复加入", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(OPEN_LONG, 0, "0.6", "100");
            s.book.accept(List.of(baseline), false);
            require(s.book.waits().isEmpty());
            s.book.finishInitialChoice(List.of(baseline));
            require(s.book.waits().size() == 1 && s.book.waits().get(0).existing);
            require(!s.book.waits().get(0).id().equals(baseline.id));
            equal(d("0.6"), s.book.leg(BTC, true).leader);
            s.execution.commit();
            s.restart();
            s.book.finishInitialChoice(List.of(baseline));
            require(s.book.waits().size() == 1);
            String fill = s.tick();
            require(fill.contains("补多已成交") && fill.contains("带单员推算开仓均价 100 USDT · 点差 0%"));
            equal(d("1"), s.api.position.longs);
            s.restart();
            s.tick();
            require(s.api.submissions == 1);
        });
        test("只跟后续成交时保留已有仓位推算并等待下一笔新开仓", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal baseline = signal(OPEN_LONG, 0, "0.6");
            s.book.accept(List.of(baseline), false);
            s.book.finishInitialChoice(List.of());
            s.execution.commit();
            s.restart();
            require(s.book.initialChoiceDone && s.book.waits().isEmpty());
            equal(d("0.6"), s.book.leg(BTC, true).leader);
            Rules.Signal next = signal(OPEN_LONG, 1, "0.1");
            s.book.accept(List.of(baseline, next), true);
            require(s.book.waits().size() == 1 && s.book.waits().get(0).id().equals(next.id));
        });
        test("账户成交按数量加权，去掉重复成交并累计真实已实现盈亏", () -> {
            JSONObject first = new JSONObject().put("symbol", BTC).put("orderId", 42).put("id", 11)
                    .put("price", "101").put("qty", "0.01").put("realizedPnl", "-1.25").put("time", TIME);
            JSONObject second = new JSONObject().put("symbol", BTC).put("orderId", 42).put("id", 12)
                    .put("price", "105").put("qty", "0.03").put("realizedPnl", "0.8").put("time", TIME + 1000);
            org.json.JSONArray rows = new org.json.JSONArray().put(second).put(first).put(first);
            FuturesApi.Fills fills = FuturesApi.readFills(rows, BTC, 42);
            equal(d("0.04"), fills.quantity);
            equal(d("104"), fills.average());
            equal(d("-0.45"), fills.realizedPnl);
            require(fills.latest == TIME + 1000);
            for (Rules.Action action : Rules.Action.values()) {
                String message = TradeNotice.completed(action.label, BTC, action, fills, ZERO, d("100"), "参考价");
                require(message.contains(action.opens == action.isLong ? "+4%（不利）" : "-4%（有利）"));
                require(message.contains("已实现盈亏 -0.45 USDT") == !action.opens);
            }
            second.remove("realizedPnl");
            require(FuturesApi.readFills(rows, BTC, 42).realizedPnl == null);
        });
        test("发送超时已成交，重启先查同一订单且不重复发单", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.loseResponse = true;
            s.api.hideOrderOnce = true;
            require(s.tick().contains("未确认"));
            String id = s.saved.getJSONObject("pending").getString("id");
            equal(d("1"), s.api.position.longs);
            s.restart();
            require(s.tick().contains("查询未确认"));
            require(s.api.submissions == 1);
            s.tick();
            require(s.book.pending == null && id.equals(s.api.lastId));
            equal(d("1"), s.book.leg(BTC, true).owned);
            s.tick();
            require(s.api.submissions == 1 && s.api.usedIds.equals(List.of(id)));
        });
        test("保存订单标识失败时，绝不发出交易请求", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.failSave = true;
            try { s.tick(); }
            catch (FollowExecutor.JournalFailure expected) {
                require(s.api.submissions == 0);
                return;
            }
            throw new AssertionError("写入故障未传出");
        });
        test("拒单可重算，未发送和交易所未知结果有不同处理", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.reject = true;
            require(s.tick().contains("拒单") && s.book.pending == null);
            s.api.neverSent = true;
            require(s.tick().contains("开仓委托未发送") && s.book.pending == null);
            require(s.tick().contains("待重试"));
            s.book.waits().get(0).checkRetryAt = System.currentTimeMillis() - 1;
            s.tick();
            equal(d("1"), s.api.position.longs);
        });
        test("第二笔开仓检查超时后跨重启退避，带单员平仓逐笔记录取消", () -> {
            Scenario s = new Scenario(false);
            Rules.Signal first = signal(OPEN_LONG, 1, "10"), second = signal(OPEN_LONG, 2, "10");
            Rules.Signal close = signal(CLOSE_LONG, 3, "20");
            s.book.acceptNew(List.of(first), true);
            s.tick();
            require(s.api.submissions == 1);
            s.book.acceptNew(List.of(second), true);
            s.api.walletTimeouts = 2;
            require(s.tick().contains("币安请求超时") && s.book.pending == null);
            int checks = s.api.walletReads;
            s.restart();
            FollowBook.Entry waiting = s.book.waits().get(0);
            require(waiting.id().equals(second.id) && waiting.checkFailures == 1 && waiting.checkRetryAt > waiting.checkFailedAt);
            require(s.tick().contains("待重试") && s.api.walletReads == checks && s.api.submissions == 1);
            waiting.checkRetryAt = System.currentTimeMillis() - 1;
            s.tick();
            require(waiting.checkFailures == 2 && s.api.walletReads == checks + 1 && s.api.submissions == 1);
            s.book.acceptNew(List.of(close), true);
            require(s.book.waits().isEmpty());
            String cancellation = s.book.log.getString(s.book.log.length() - 1);
            require(cancellation.contains("自动取消开仓等待") && cancellation.contains(TradeNotice.fullTime(second.time))
                    && cancellation.contains(TradeNotice.fullTime(close.time)) && cancellation.contains("币安请求超时"));
            s.tick();
            require(s.api.submissions == 2 && s.api.fills.quantity.compareTo(d("1")) == 0);
            equal(ZERO, s.api.position.longs);
            s.restart();
            require(s.book.waits().isEmpty() && s.book.log.toString().contains("自动取消开仓等待"));
        });
        test("开仓检查恢复后按新盘口执行，已提交后超时沿用原委托号核对", () -> {
            Scenario s = new Scenario(false);
            s.book.acceptNew(List.of(signal(OPEN_LONG, 1, "10")), true);
            s.api.walletTimeouts = 1;
            s.tick();
            s.book.waits().get(0).checkRetryAt = System.currentTimeMillis() - 1;
            s.api.loseResponse = true;
            require(s.tick().contains("发送结果未确认"));
            require(s.api.submissions == 1 && s.book.pending != null && s.book.waits().get(0).checkFailures == 0);
            String orderId = s.book.pending.getString("id");
            s.restart();
            require(s.book.pending.getString("id").equals(orderId));
            require(s.tick().contains("已成交") && s.api.submissions == 1);
            require(s.book.log.toString().contains("开仓检查已恢复"));
        });
        test("反复拒单只记一条应用内原因，待核对和未成交均不推送", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            for (int i = 0; i < 3; i++) {
                s.api.reject = true;
                require(s.tick().contains("-2019") && s.events.isEmpty());
            }
            require(s.book.log.length() == 1 && s.book.pending == null);
            s.restart();
            require(s.book.waits().get(0).requestIssue.contains("Margin is insufficient"));
            s.api.partial = ZERO;
            require(s.tick().contains("未成交") && s.events.isEmpty());
            s.book.notice = new JSONObject().put("id", "legacy").put("text", "开多遭拒单 · 旧错误通知");
            s.execution.flushNotice();
            require(s.events.isEmpty() && s.book.notice == null);
            s.tick();
            require(s.events.size() == 1 && s.events.get(0).contains("已成交"));
        });
        test("名单外无委托，开仓前账户已手动加仓时以同步后的仓位继续", () -> {
            Scenario s = new Scenario(false);
            s.execution.syncAccount(java.util.Map.of(), TIME);
            s.book.apply(new Rules.Signal("ETHUSDT", OPEN_LONG, TIME, d("100"), d("10")), true);
            s.tick();
            require(s.api.submissions == 0);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.position.longs = d("0.4");
            require(s.tick().contains("加多已成交") && s.api.submissions == 1);
            equal(d("1.4"), s.book.leg(BTC, true).owned);
            require(s.book.log.toString().contains("手动操作 · BTCUSDT 开多"));
        });
        test("空头只拒绝低卖，双向模式可并存，平空不会开多", () -> {
            Scenario s = new Scenario(false);
            s.api.hedge = true;
            s.book.apply(signal(OPEN_SHORT, 1, "10"), true);
            s.api.price = d("99");
            require(s.tick().contains("价差超限"));
            s.api.price = d("101");
            s.tick();
            require(s.api.position.shorts.signum() > 0);
            s.api.price = d("100");
            s.book.apply(signal(OPEN_LONG, 2, "10"), true);
            s.tick();
            equal(d("1"), s.api.position.longs);
            s.api.price = d("500");
            s.book.apply(signal(CLOSE_SHORT, 3, "10"), true);
            s.tick();
            equal(ZERO, s.api.position.shorts);
            equal(d("1"), s.api.position.longs);
            require(s.api.lastLimit == null && s.api.hedge);
        });
        test("同一单向合约的反向信号等待，暂停或快照失效都阻止开仓", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.execution.tick(s.book.config, () -> false, () -> true);
            require(s.api.submissions == 0);
            s.tick();
            s.book.apply(signal(OPEN_SHORT, 2, "10"), true);
            require(s.tick().contains("反向"));
            require(s.api.submissions == 1);
            s.book.apply(signal(CLOSE_LONG, 3, "10"), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
            s.tick();
            require(s.api.position.shorts.signum() > 0);
        });
        test("一笔补仓的盘口返回 -1121，另一笔可用补仓仍能执行", () -> {
            String missing = "MUUSDT";
            Scenario s = new Scenario(false, Set.of(BTC, missing));
            s.api.failedQuoteSymbol = missing;
            s.book.finishInitialChoice(List.of(new Rules.Signal(missing, OPEN_LONG, TIME, d("100"), d("0.2")),
                    signal(OPEN_LONG, 1, "0.2")));
            require(s.tick().contains(BTC));
            require(s.api.submissions == 1 && BTC.equals(s.api.order.getString("symbol")));
            require(s.book.waits().size() == 1 && missing.equals(s.book.waits().get(0).signal.symbol));
            String reason = s.tick();
            require(reason.contains(missing) && reason.contains("-1121") && s.api.submissions == 1);
        });
        test("按环境报告合约未收录或停牌，一笔坏盘口不影响同批其他盘口", () -> {
            JSONObject valid = new JSONObject().put("quoteAsset", "USDT").put("contractType", "PERPETUAL")
                    .put("status", "TRADING");
            require(FuturesApi.listingIssue(FuturesApi.LIVE, BTC, valid) == null);
            require(FuturesApi.listingIssue(FuturesApi.DEMO, "MUUSDT", null).equals("测试网合约列表未收录 MUUSDT"));
            valid.put("contractType", "TRADIFI_PERPETUAL");
            require(FuturesApi.listingIssue(FuturesApi.LIVE, "XAUUSDT", valid) == null);
            valid.put("status", "SETTLING");
            require(FuturesApi.listingIssue(FuturesApi.DEMO, BTC, valid).contains("SETTLING"));
            valid.put("status", "TRADING").put("quoteAsset", "USDC");
            require(FuturesApi.listingIssue(FuturesApi.LIVE, BTC, valid) != null);
            valid.put("quoteAsset", "USDT").put("contractType", "CURRENT_QUARTER");
            require(FuturesApi.listingIssue(FuturesApi.LIVE, BTC, valid) != null);
            org.json.JSONArray rows = new org.json.JSONArray().put(new JSONObject().put("symbol", "MUUSDT"))
                    .put(new JSONObject().put("symbol", BTC).put("time", TIME).put("bidPrice", "99")
                            .put("askPrice", "101").put("bidQty", "2").put("askQty", "3"));
            java.util.Map<String, Rules.Quote> quotes = FuturesApi.readTickers(rows, Set.of(BTC, "MUUSDT"), TIME);
            require(quotes.size() == 1);
            equal(d("101"), quotes.get(BTC).ask);
        });
        test("实盘、测试网与实盘只读权限分别检查，资金转移路径未开放", () -> {
            for (String realm : List.of(FuturesApi.LIVE, FuturesApi.DEMO)) {
                require(FuturesApi.permitted(realm, "GET", "/fapi/v1/exchangeInfo", false));
                require(!FuturesApi.permitted(realm, "GET", "/fapi/v1/exchangeInfo", true));
                require(FuturesApi.permitted(realm, "POST", "/fapi/v1/order", true));
                require(FuturesApi.permitted(realm, "GET", "/fapi/v3/positionRisk", true));
                require(FuturesApi.permitted(realm, "GET", "/fapi/v1/leverageBracket", true));
                require(!FuturesApi.permitted(realm, "GET", "/fapi/v1/leverageBracket", false));
                require(!FuturesApi.permitted(realm, "POST", "/fapi/v1/order", false));
                require(!FuturesApi.permitted(realm, "POST", "/sapi/v1/asset/transfer", true));
                require(!FuturesApi.permitted(realm, "POST", "/sapi/v1/capital/withdraw/apply", true));
            }
            require(!FuturesApi.permitted(FuturesApi.LIVE_READ, "POST", "/fapi/v1/order", true));
            require(FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v3/positionRisk", true));
            require(FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v1/leverageBracket", true));
            require(FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v1/symbolConfig", true));
            require(!FuturesApi.permitted(FuturesApi.LIVE_READ, "POST", "/fapi/v1/leverage", true));
            require(FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v1/premiumIndex", false));
            require(!FuturesApi.LIVE.equals(FuturesApi.DEMO) && !FuturesApi.READ_BASE.equals(FuturesApi.DEMO_BASE));
        });
        test("读取实际仓位时区分单向和双向仓位，并剔除空仓记录", () -> {
            org.json.JSONArray rows = new org.json.JSONArray("["
                    + "{\"symbol\":\"BTCUSDT\",\"positionSide\":\"LONG\",\"positionAmt\":\"0.2\",\"entryPrice\":\"62000\",\"updateTime\":123},"
                    + "{\"symbol\":\"BTCUSDT\",\"positionSide\":\"SHORT\",\"positionAmt\":\"-0.4\",\"entryPrice\":\"62500\",\"updateTime\":456},"
                    + "{\"symbol\":\"ETHUSDT\",\"positionSide\":\"BOTH\",\"positionAmt\":\"-0.1\"},"
                    + "{\"symbol\":\"SOLUSDT\",\"positionSide\":\"BOTH\",\"positionAmt\":\"0\"}]");
            java.util.Map<String, FuturesApi.Position> positions = FuturesApi.readPositions(rows, null);
            require(positions.size() == 2 && !positions.containsKey("SOLUSDT"));
            equal(d("0.2"), positions.get(BTC).longs);
            equal(d("0.4"), positions.get(BTC).shorts);
            equal(d("0.1"), positions.get("ETHUSDT").shorts);
            equal(d("62000"), positions.get(BTC).longEntry);
            equal(d("62500"), positions.get(BTC).shortEntry);
            require(positions.get(BTC).longUpdated == 123 && positions.get(BTC).shortUpdated == 456);
        });
        test("实际双向仓位分别读取持仓保证金和强平价，杠杆每合约读取", () -> {
            org.json.JSONArray rows = new org.json.JSONArray("["
                    + "{\"symbol\":\"BTCUSDT\",\"positionSide\":\"LONG\",\"positionAmt\":\"0.2\","
                    + "\"positionInitialMargin\":\"620\",\"initialMargin\":\"830\",\"liquidationPrice\":\"53120.525\",\"marginAsset\":\"USDT\"},"
                    + "{\"symbol\":\"BTCUSDT\",\"positionSide\":\"SHORT\",\"positionAmt\":\"-0.4\","
                    + "\"positionInitialMargin\":\"1200\",\"isolatedWallet\":\"1290\",\"liquidationPrice\":\"0\",\"marginAsset\":\"USDT\"}]");
            FuturesApi.Position position = FuturesApi.readPositions(rows, null).get(BTC);
            equal(d("620"), position.longRisk.initialMargin);
            equal(d("53120.525"), position.longRisk.liquidationPrice);
            equal(d("1200"), position.shortRisk.initialMargin);
            equal(d("1290"), position.shortRisk.isolatedWallet);
            equal(ZERO, position.shortRisk.liquidationPrice);
            require("USDT".equals(position.shortRisk.asset));
            java.util.Map<String, Integer> config = FuturesApi.readSymbolLeverages(new org.json.JSONArray(
                    "[{\"symbol\":\"BTCUSDT\",\"leverage\":20},{\"symbol\":\"ETHUSDT\",\"leverage\":0}]"));
            require(config.equals(java.util.Map.of(BTC, 20)));
        });
        test("使用标准维持档位和及时标记价估算带单员风险", () -> {
            org.json.JSONArray tiers = new org.json.JSONArray("[{\"symbol\":\"BTCUSDT\",\"brackets\":["
                    + "{\"notionalFloor\":0,\"notionalCap\":50000,\"maintMarginRatio\":0.004,\"cum\":0},"
                    + "{\"notionalFloor\":50000,\"notionalCap\":250000,\"maintMarginRatio\":0.005,\"cum\":50}]},"
                    + "{\"symbol\":\"ETHUSDT\",\"notionalCoef\":1.5,\"brackets\":[]},"
                    + "{\"symbol\":\"XRPUSDT\",\"brackets\":[{\"notionalFloor\":0,\"notionalCap\":0}]},"
                    + "{\"symbol\":\"SOLUSDT\",\"brackets\":["
                    + "{\"notionalFloor\":0,\"notionalCap\":1000,\"maintMarginRatio\":0.01,\"cum\":0},"
                    + "{\"notionalFloor\":1000,\"notionalCap\":2000,\"maintMarginRatio\":0.02,\"cum\":0}]}]");
            java.util.Map<String, List<LeaderRisk.Tier>> parsed = FuturesApi.readMaintenanceTiers(tiers);
            require(parsed.size() == 1 && parsed.get(BTC).size() == 2);
            equal(d("0.005"), parsed.get(BTC).get(1).rate);
            equal(d("50"), parsed.get(BTC).get(1).deduction);
            org.json.JSONArray marks = new org.json.JSONArray()
                    .put(new JSONObject().put("symbol", BTC).put("markPrice", "63732.10").put("time", TIME))
                    .put(new JSONObject().put("symbol", "ETHUSDT").put("markPrice", "2300").put("time", TIME - 16000));
            java.util.Map<String, BigDecimal> current = FuturesApi.readMarkPrices(marks, Set.of(BTC, "ETHUSDT"), TIME);
            require(current.size() == 1);
            equal(d("63732.10"), current.get(BTC));
        });
        test("带单员浮盈亏走最新成交价接口，只用新鲜的正数报价", () -> {
            org.json.JSONArray ticks = new org.json.JSONArray()
                    .put(new JSONObject().put("symbol", BTC).put("price", "63800.10").put("time", TIME))
                    .put(new JSONObject().put("symbol", "ETHUSDT").put("price", "2300").put("time", TIME - 61000));
            java.util.Map<String, BigDecimal> current = FuturesApi.readLatestPrices(ticks, Set.of(BTC, "ETHUSDT"), TIME);
            require(current.size() == 1);
            equal(d("63800.10"), current.get(BTC));
            require(FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v2/ticker/price", false));
            require(!FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v2/ticker/price", true));
        });
        test("旧版的统一规则迁移到各个合约，单独保存后仍保留合约顺序和数值", () -> {
            String eth = "ETHUSDT";
            Rules.Config legacy = LocalData.readConfig(new JSONObject().put("symbols", BTC + "," + eth)
                    .put("amount", "7.5").put("ratio", true).put("leverage", 12).put("slippage", "0.8"));
            equal(d("75"), legacy.rule(BTC).orderMargin(d("1000")));
            require(legacy.rule(eth).sameSizing(legacy.rule(BTC)));
            java.util.Map<String, Rules.ContractRule> edited = new java.util.LinkedHashMap<>(legacy.rules);
            edited.put(eth, new Rules.ContractRule(false, d("24"), 5, d("0.4")));
            Rules.Config config = LocalData.readConfig(LocalData.configJson(new Rules.Config(legacy.portfolio, edited)));
            require(new ArrayList<>(config.symbols).equals(List.of(BTC, eth)));
            require(!FollowBook.sameRules(legacy, config));
            equal(d("24"), config.rule(eth).orderMargin(d("1000")));
            equal(d("0.4"), config.rule(eth).slippagePercent);
            equal(d("0.8"), config.rule(BTC).slippagePercent);
        });
        test("同一轮不同合约按各自的价差、定额定比和杠杆下单", () -> {
            String eth = "ETHUSDT";
            java.util.Map<String, Rules.ContractRule> config = new java.util.LinkedHashMap<>();
            config.put(BTC, new Rules.ContractRule(false, d("25"), 4, d("0.5")));
            config.put(eth, new Rules.ContractRule(true, d("2"), 10, d("2")));
            Scenario s = new Scenario(new Rules.Config(LocalData.DEFAULT_PORTFOLIO, config));
            s.api.bySymbol.put(eth, new FuturesApi.Position());
            s.api.price = d("101");
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.4"), true);
            s.book.apply(new Rules.Signal(eth, Rules.Action.OPEN_LONG, TIME + 2000, d("100"), d("0.5")), true);
            require(s.tick().contains("ETHUSDT 杠杆已确认 10"));
            require(s.tick().contains(eth) && s.api.submissions == 1);
            equal(d("1.98"), s.api.position(eth).longs);
            require(s.tick().contains("BTCUSDT 不利价差超限"));
            s.api.price = d("100");
            require(s.tick().contains("BTCUSDT 杠杆已确认 4"));
            s.tick();
            equal(d("1"), s.api.position.longs);
            require(s.api.submissions == 2 && s.events.size() == 2);
        });
        test("停机期间手动平仓后，核对空仓清除旧账本拦截且不发送交易", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.6"), true);
            s.tick();
            require(s.book.hasExposure());
            s.api.position.longs = d("0");
            // 非本机的挂单不构成待核对的跟单委托。
            s.api.workingOrderIds = Set.of("manual-limit");
            s.execution.releaseFlatAccount();
            s.restart();
            require(!s.book.hasExposure() && s.book.pnlIncomplete && s.api.submissions == 1);
        });
        test("账户仍有仓位或本机仍有在途委托时保留旧账本", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.6"), true);
            s.tick();
            expectBlocked(s.execution::releaseFlatAccount, "还有未平的账户仓位");
            require(s.book.hasExposure());
            s.api.position.longs = d("0");
            s.api.workingOrderIds = Set.of(s.api.lastId);
            expectBlocked(s.execution::releaseFlatAccount, "还有未结束的跟单委托");
            require(s.book.hasExposure() && s.api.submissions == 1);
        });
        test("停机切换使用账户全量快照，能清理已停售合约上的空仓减仓意向", () -> {
            String delisted = "MUUSDT";
            Scenario s = new Scenario(false, Set.of(BTC, delisted));
            s.book.apply(new Rules.Signal(delisted, Rules.Action.OPEN_LONG, TIME, d("100"), d("1")), false);
            s.book.apply(new Rules.Signal(delisted, Rules.Action.CLOSE_LONG, TIME + 1000, d("110"), d("1")), true);
            require(s.book.hasExposure());
            s.api.invalidPositionSymbol = delisted;
            s.execution.releaseFlatAccount();
            require(!s.book.hasExposure() && s.api.submissions == 0);
        });
        test("发送超时但委托已成交且之后手动平仓，核对后允许切换", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.6"), true);
            s.api.loseResponse = true;
            s.tick();
            require(s.book.pending != null && s.events.isEmpty());
            s.api.position.longs = d("0");
            s.execution.releaseFlatAccount();
            s.restart();
            require(!s.book.hasExposure() && s.book.pnlIncomplete && s.api.submissions == 1 && s.events.isEmpty());
        });
        test("近期查无委托保留核对，旧委托查无且空仓时解除拦截", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.6"), true);
            s.api.loseResponse = true;
            s.tick();
            s.api.position.longs = d("0");
            s.api.hideOrderOnce = true;
            expectBlocked(s.execution::releaseFlatAccount, "-2013");
            require(s.book.pending != null);
            s.book.pending.put("at", System.currentTimeMillis() - 120000L);
            s.api.hideOrderOnce = true;
            s.execution.releaseFlatAccount();
            require(s.book.pending == null && !s.book.hasExposure() && s.api.submissions == 1);
        });
        test("跟单时长和已实现盈亏随账本保存，总盈亏只纳入已核对的跟单仓位", () -> {
            Scenario s = new Scenario(false);
            s.book.trackedMillis = 30000;
            s.book.trackingSince = TIME;
            s.book.apply(signal(Rules.Action.OPEN_LONG, 1, "0.6"), true);
            s.tick();
            s.api.realizedPnl = d("-2.1");
            s.book.apply(signal(Rules.Action.CLOSE_LONG, 2, "0.3"), true);
            s.tick();
            s.restart();
            require(s.book.trackingMillis(TIME + 10000L) == 40000);
            equal(d("-2.1"), s.book.realizedPnl);
            s.api.position.longUnrealized = d("1.3");
            s.api.position.shortUnrealized = d("100");
            equal(d("-0.8"), s.book.totalPnl(java.util.Map.of(BTC, s.api.position)));
            require(s.book.totalPnl(java.util.Map.of()) == null);
        });
        test("启动前多空仓保留至对应方向新增开仓，之后用合并仓位按比例平仓", () -> {
            Scenario s = new Scenario(false);
            s.api.hedge = true;
            s.api.position.longs = d("0.4");
            s.api.position.shorts = d("0.2");
            s.execution.syncAccount(s.api.positions(), TIME);
            s.book.apply(signal(OPEN_LONG, 0, "0.8"), false);
            s.book.apply(signal(OPEN_SHORT, 1, "0.4"), false);
            require(s.book.finishInitialChoice(List.of(signal(OPEN_LONG, 0, "0.8"))) == 0);
            s.book.acceptNew(List.of(signal(CLOSE_LONG, 2, "0.2"), signal(CLOSE_SHORT, 3, "0.2")), true);
            s.tick();
            require(s.api.submissions == 0 && s.book.leg(BTC, true).protectedExisting);
            require(s.book.detections.toString().contains("启动前仓位暂未纳入跟单"));
            s.execution.commit();
            s.restart();
            s.book.acceptNew(List.of(signal(OPEN_LONG, 4, "0.2")), true);
            s.tick();
            equal(d("1.4"), s.api.position.longs);
            require(s.book.log.toString().contains("已有仓位纳入跟单 · BTCUSDT 多"));
            s.book.acceptNew(List.of(signal(CLOSE_LONG, 5, "0.4"), signal(CLOSE_SHORT, 6, "0.2")), true);
            s.tick();
            equal(d("0.7"), s.api.position.longs);
            equal(d("0.2"), s.api.position.shorts);
            require(s.api.submissions == 2 && s.book.leg(BTC, false).protectedExisting);
        });
        test("盘中手动开减仓独立同步，已核对的手动盈亏计入总额且重启不重复", () -> {
            Scenario s = new Scenario(false);
            s.execution.syncAccount(s.api.positions(), TIME);
            s.book.apply(signal(OPEN_LONG, 0, "0.6"), false);
            s.api.position.longs = d("0.4");
            require(s.execution.syncAccount(s.api.positions(), TIME + 1000));
            s.api.position.longs = d("0.3");
            s.api.recent = List.of(new FuturesApi.AccountTrade(9, 50, TIME + 1500, "BOTH", false, d("0.1"), d("-2.5")));
            require(s.execution.syncAccount(s.api.positions(), TIME + 2000));
            require(s.book.log.toString().contains("手动操作 · BTCUSDT 减多")
                    && s.book.log.toString().contains("已实现盈亏 -2.5 USDT"));
            s.restart();
            require(!s.execution.syncAccount(s.api.positions(), TIME + 3000));
            equal(d("-2.5"), s.book.realizedPnl);
            s.book.apply(signal(CLOSE_LONG, 4, "0.3"), true);
            s.tick();
            equal(d("0.15"), s.api.position.longs);
            require(s.api.submissions == 1);
        });
        test("自动开仓回执未知时用成交明细识别同时发生的手动减仓", () -> {
            Scenario s = new Scenario(false);
            s.execution.syncAccount(s.api.positions(), TIME);
            s.book.apply(signal(OPEN_LONG, 1, "10"), true);
            s.api.loseResponse = true;
            s.tick();
            s.api.position.longs = d("0.75");
            require(s.tick().contains("仓位与成交回执还未一致") && s.book.pending != null);
            s.api.recent = List.of(
                    new FuturesApi.AccountTrade(1, 1, TIME, "BOTH", true, d("1"), ZERO),
                    new FuturesApi.AccountTrade(2, 99, TIME + 1, "BOTH", false, d("0.25"), d("-4")));
            s.restart();
            s.tick();
            require(s.book.pending == null && s.events.size() == 1 && s.api.submissions == 1);
            equal(d("0.75"), s.book.leg(BTC, true).owned);
            equal(d("-4"), s.book.realizedPnl);
            require(s.book.log.toString().contains("手动操作 · BTCUSDT 减多"));
        });
        test("实时保存的保证金与点差适用于尚未成交的等待信号", () -> {
            Scenario s = new Scenario(false);
            s.book.apply(signal(OPEN_LONG, 1, "1"), true);
            s.api.price = d("101");
            require(s.tick().contains("价差超限") && s.api.submissions == 0);
            Rules.Config changed = new Rules.Config(LocalData.DEFAULT_PORTFOLIO, Set.of(BTC), false, d("50"), 5, d("2"));
            require(FollowBook.sameUniverse(s.book.config, changed));
            s.api.reject = true;
            require(s.execution.tick(changed, () -> true, () -> true).contains("拒单"));
            Rules.Config latest = new Rules.Config(LocalData.DEFAULT_PORTFOLIO, Set.of(BTC), true, d("3"), 5, d("2"));
            s.execution.tick(latest, () -> true, () -> true);
            equal(d("1.485"), s.api.position.longs);
        });
        test("已有持仓时保留币安实际杠杆，较高的实际倍数不扩大按设置计算的委托", () -> {
            Scenario s = new Scenario(false);
            s.api.leverage = 20;
            s.api.position.longs = d("0.4");
            s.execution.syncAccount(s.api.positions(), TIME);
            s.book.acceptNew(List.of(signal(OPEN_LONG, 1, "1")), true);
            s.tick();
            require(s.api.leverage == 20 && s.api.leverageChanges == 0);
            equal(d("1"), s.api.lastQuantity);
            equal(d("1.4"), s.api.position.longs);
            require(s.events.get(0).contains("实际杠杆 20 倍"));
        });
        test("停止后手动建立的反向仓位重启时保留原跟单和启动前仓位边界", () -> {
            Scenario s = new Scenario(false);
            s.api.hedge = true;
            s.execution.syncAccount(s.api.positions(), TIME);
            s.book.apply(signal(OPEN_LONG, 1, "1"), true);
            s.tick();
            s.restart();
            s.api.position.shorts = d("0.25");
            s.book.reseedIdlePositions();
            s.execution.syncAccount(s.api.positions(), TIME + 3000);
            equal(d("1"), s.book.leg(BTC, true).owned);
            require(s.book.leg(BTC, false).protectedExisting);
            equal(ZERO, s.book.leg(BTC, false).owned);
        });
        test("旧账本与旧配置默认按点位，复制方式和当笔公开余额可跨重启", () -> {
            Scenario s = exposure();
            Rules.Signal lead = exposureSignal(OPEN_LONG, 1, "3", "320", "200");
            s.book.apply(lead, true);
            s.execution.commit();
            JSONObject previous = new JSONObject(s.saved.toString());
            previous.remove("strategy");
            require(FollowBook.restore(previous).strategy == Rules.Strategy.POINTS);
            s.restart();
            require(s.book.strategy == Rules.Strategy.EXPOSURE);
            equal(d("1.6"), s.book.waits().get(0).signal.exposureRatio());
        });
        test("复制开仓和部分减仓按名义金额换算，带单员平完则清空同方向", () -> {
            Scenario s = exposure();
            s.api.copyEquity = d("500");
            s.api.available = d("230");
            s.book.apply(exposureSignal(OPEN_LONG, 1, "20", "2000", "1000"), true);
            s.tick();
            equal(d("10"), s.api.position.longs);
            require(s.api.copyReads == 1 && s.api.walletReads == 0);
            require(s.events.get(0).contains("保证金余额 2 倍"));
            s.api.copyEquity = d("400");
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "4", "400", "1000"), true);
            s.tick();
            equal(d("1.6"), s.api.lastQuantity);
            equal(d("8.4"), s.api.position.longs);
            require(s.api.lastLimit == null);
            s.book.apply(exposureSignal(CLOSE_LONG, 3, "16", null, null), true);
            s.tick();
            equal(d("8.4"), s.api.lastQuantity);
            equal(ZERO, s.api.position.longs);
            require(s.api.copyReads == 2 && s.api.submissions == 3);
        });
        test("没有带单保证金时不发委托，后续同一笔补齐后保留首次有效估算", () -> {
            Scenario s = exposure();
            Rules.Signal lead = exposureSignal(OPEN_LONG, 1, "3", "300", null);
            s.book.apply(lead, true);
            require(s.tick().contains("带单保证金余额") && s.api.submissions == 0 && s.api.copyReads == 0);
            require(!s.book.enrichWaiting(List.of(exposureSignal(OPEN_LONG, 2, "3", "300", "100"))));
            require(s.book.enrichWaiting(List.of(lead.withLeaderMargin(d("400")))));
            require(!s.book.enrichWaiting(List.of(lead.withLeaderMargin(d("200")))));
            s.execution.commit();
            s.restart();
            s.tick();
            equal(d("7.5"), s.api.position.longs);
        });
        test("未取得部分减仓金额时等待，后续同方向清仓可覆盖尚未提交的减仓", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "3", "300", "1000"), true);
            s.tick();
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "1", "100", null), true);
            require(s.tick().contains("等待减仓成交额或带单保证金余额") && s.api.submissions == 1);
            s.book.apply(exposureSignal(CLOSE_LONG, 3, "2", "200", null), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
            require(s.api.submissions == 2);
        });
        test("交易所限制下调实际杠杆时保留名义仓位，保证金不足时等待", () -> {
            Rules.Config config = new Rules.Config(LocalData.DEFAULT_PORTFOLIO, Set.of(BTC), false, d("1"), 50, BAND);
            Scenario s = new Scenario(config, Rules.Strategy.EXPOSURE);
            s.api.observedLeverageCap = 20;
            s.api.copyEquity = d("700");
            s.api.available = d("60");
            s.book.apply(exposureSignal(OPEN_LONG, 1, "20", "2000", "1000"), true);
            require(s.tick().contains("可用保证金不足") && s.api.submissions == 0);
            s.api.available = d("100");
            require(s.tick().contains("杠杆已确认 20"));
            s.tick();
            equal(d("14"), s.api.position.longs);
            require(s.api.leverage == 20 && s.api.submissions == 1);
        });
        test("复制委托部分成交后按剩余名义金额续单，重启和余额变化不放大原委托", () -> {
            Scenario s = exposure();
            s.api.copyEquity = d("300");
            s.api.partial = d("1");
            s.book.apply(exposureSignal(OPEN_LONG, 1, "4", "400", "400"), true);
            s.tick();
            equal(d("200"), s.book.waits().get(0).nominalLeft);
            s.restart();
            s.api.copyEquity = d("1200");
            s.api.price = d("80");
            s.tick();
            equal(d("2.5"), s.api.lastQuantity);
            equal(d("200"), s.api.fills.value);
            equal(d("3.5"), s.api.position.longs);
            require(s.book.waits().isEmpty());
        });
        test("复制系数默认 1，按 0.5 倍开减仓，平仓始终按实际方向归零", () -> {
            rejected(() -> Rules.copyFactor("0"));
            rejected(() -> Rules.copyFactor("-1"));
            rejected(() -> Rules.copyFactor("NaN"));
            Scenario s = exposure();
            equal(d("1"), s.book.copyFactor);
            require(s.book.changeCopySettings(Rules.copyFactor("0.50"), BAND, 0, java.util.Map.of(), null));
            s.api.copyEquity = d("500");
            s.book.apply(exposureSignal(OPEN_LONG, 1, "20", "2000", "1000"), true);
            s.tick();
            equal(d("5"), s.api.position.longs);
            require(s.events.get(0).contains("复制系数 0.5×"));
            s.restart();
            equal(d("0.5"), s.book.copyFactor);
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "4", "400", "1000"), true);
            s.tick();
            equal(d("4"), s.api.position.longs);
            s.book.apply(exposureSignal(CLOSE_LONG, 3, "16", null, null), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
            require(s.api.lastLimit == null);
        });
        test("选择补已有仓位时，测试网复制名义金额也乘以首次设置的系数", () -> {
            Scenario s = exposure();
            s.book.copyFactor = Rules.copyFactor("2");
            s.api.copyEquity = d("500");
            Rules.Signal initial = exposureSignal(OPEN_SHORT, 1, "4", "400", "1000");
            s.book.seedPositions(java.util.Map.of(BTC + "|SHORT", initial.quantity));
            require(s.book.finishInitialChoice(List.of(initial)) == 1);
            s.tick();
            equal(d("4"), s.api.position.shorts);
            equal(d("4"), s.book.leg(BTC, false).copied);
        });
        test("降系数后等待余额时带单员加仓，得到余额后按新目标市价减仓", () -> {
            Scenario s = exposure();
            s.api.hedge = true;
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            long savedAt = 100;
            require(s.book.changeCopySettings(d("0.5"), BAND, savedAt, java.util.Map.of(), null));
            s.execution.commit();
            s.restart();
            require(s.book.leg(BTC,true).adjustment.fromSetting());
            require(s.tick().contains("保证金余额") && s.api.submissions == 1);
            Rules.Signal old = exposureSignal(OPEN_LONG, 2, "0.5", "50", "1000");
            s.book.acceptNew(List.of(old), true, java.util.Map.of(old.id, 99L), 0);
            require(s.book.leg(BTC,true).adjustment.fromSetting());
            Rules.Signal other = exposureSignal(OPEN_SHORT, 3, "1", "100", "1000");
            s.book.acceptNew(List.of(other), true, java.util.Map.of(other.id, 102L), 0);
            s.tick();
            equal(d("0.5"), s.api.position.shorts);
            equal(d("10"), s.api.position.longs);
            Rules.Signal next = exposureSignal(OPEN_LONG, 4, "0.5", "50", "1000");
            s.book.acceptNew(List.of(next), true, java.util.Map.of(next.id, 103L), 0);
            s.api.price = d("99");
            s.api.failedQuoteSymbol = BTC;
            s.execution.commit();
            s.restart();
            require(s.book.leg(BTC, true).adjustment.signal.id.equals(next.id));
            s.tick();
            equal(d("4.5"), s.api.lastQuantity);
            equal(d("5.5"), s.api.position.longs);
            equal(d("0.5"), s.api.position.shorts);
            require(s.api.lastLimit == null);
            require("SELL".equals(s.api.order.getString("side")) && "LONG".equals(s.api.order.getString("positionSide")));
            require(s.book.leg(BTC, true).adjustment == null && s.events.get(2).contains("复制调仓 减多"));
        });
        test("运行中提系数后按目标差额加仓，遵守价差并跨重启核对部分成交", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            s.book.changeCopySettings(d("2"), BAND, 0, java.util.Map.of(), null);
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "1", "100", "1000"), true);
            s.api.price = d("102");
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("18"), s.book.leg(BTC, true).adjustment.target);
            s.api.price = d("99");
            s.api.partial = d("2");
            s.api.loseResponse = true;
            require(s.tick().contains("发送结果未确认") && s.api.submissions == 2);
            equal(d("8"), s.api.lastQuantity);
            String original = s.api.lastId;
            s.restart();
            s.api.copyEquity = d("1400");
            require(s.tick().contains("部分成交") && s.api.lastId.equals(original) && s.api.submissions == 2);
            equal(d("12"), s.api.position.longs);
            s.tick();
            equal(d("6"), s.api.lastQuantity);
            equal(d("18"), s.api.position.longs);
            require(s.api.submissions == 3 && s.book.leg(BTC, true).adjustment == null);
        });
        test("比例太小低于最小数量或金额时不发委托，同方向后续合并到可执行目标", () -> {
            Scenario s = exposure();
            s.book.copyFactor = d("0.1");
            s.api.contract = new FuturesApi.Contract(d("0.01"), d("0.01"), d("0.1"), d("100"),
                    d("0.01"), d("0.1"), d("100"), d("5"));
            s.book.apply(exposureSignal(OPEN_LONG, 1, "0.2", "20", "1000"), true);
            require(s.tick().contains("最小委托金额") && s.api.submissions == 0);
            require(s.book.waits().isEmpty());
            s.restart();
            s.book.apply(exposureSignal(OPEN_LONG, 2, "0.4", "40", "1000"), true);
            require(s.tick().contains("低于合约最小委托量") && s.api.submissions == 0);
            s.book.apply(exposureSignal(OPEN_LONG, 3, "0.6", "60", "1000"), true);
            s.tick();
            equal(d("0.12"), s.api.position.longs);
            require(s.api.submissions == 1);
        });
        test("交易所确认金额过小后不反复试相同委托，带单员清仓仍优先市价平仓", () -> {
            Scenario s = exposure();
            s.book.copyFactor = d("0.1");
            s.api.rejection = new FuturesApi.Failure(400, -4164, "Order's notional must be no smaller than 100.");
            s.book.apply(exposureSignal(OPEN_LONG, 1, "1", "100", "1000"), true);
            require(s.tick().contains("下次同方向带单成交") && s.api.submissions == 1);
            s.tick();
            require(s.api.submissions == 1);
            s.book.apply(exposureSignal(OPEN_LONG, 2, "15", "1500", "1000"), true);
            s.tick();
            equal(d("1.6"), s.api.position.longs);
            s.book.changeCopySettings(d("0.5"), BAND, 0, java.util.Map.of(), null);
            s.book.apply(exposureSignal(CLOSE_LONG, 3, "16", null, null), true);
            s.tick();
            equal(ZERO, s.api.position.longs);
            require(s.api.lastLimit == null && s.api.submissions == 3);
        });
        test("仅跟后续成交时调仓不将首次跳过的历史仓位纳入目标", () -> {
            Scenario s = exposure();
            s.book.seedPositions(java.util.Map.of(BTC + "|LONG", d("10")));
            s.book.finishInitialChoice(List.of());
            s.book.apply(exposureSignal(OPEN_LONG, 1, "2", "200", "1000"), true);
            s.tick();
            equal(d("2"), s.api.position.longs);
            s.book.changeCopySettings(d("0.5"), BAND, 0, java.util.Map.of(), null);
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "6", "600", "1000"), true);
            s.tick();
            equal(d("0.5"), s.api.position.longs);
            require(s.api.submissions == 2);
        });
        test("旧复制账本按实际仓位校准历史范围，并合并该方向新信号", () -> {
            Scenario s = exposure();
            s.book.seedPositions(java.util.Map.of(BTC + "|LONG", d("80")));
            s.book.apply(exposureSignal(OPEN_LONG, 1, "2", "200", "1000"), true);
            s.tick();
            JSONObject legacy = s.book.json();
            legacy.remove("copyFactor");
            for (int i = 0; i < legacy.getJSONArray("legs").length(); i++) {
                legacy.getJSONArray("legs").getJSONObject(i).remove("copied");
            }
            s.saved = legacy;
            s.restart();
            s.api.position.longEntry = d("100");
            equal(d("1"), s.book.copyFactor);
            s.book.changeCopySettings(d("0.5"), BAND, 0, java.util.Map.of(), null);
            s.book.apply(exposureSignal(OPEN_LONG, 2, "2", "200", "1000"), true);
            s.book.apply(exposureSignal(OPEN_LONG, 3, "4", "400", "1000"), true);
            s.execution.commit();
            s.restart();
            s.tick();
            equal(d("8"), s.book.leg(BTC, true).copied);
            equal(d("4"), s.api.position.longs);
            require(s.api.submissions == 2 && !s.book.leg(BTC, true).legacyCopyScope);
        });
        test("同一批中的后续信号可以合并前一笔低于门槛的差额", () -> {
            Scenario s = exposure();
            s.book.copyFactor = d("0.1");
            s.book.acceptNew(List.of(exposureSignal(OPEN_LONG, 1, "0.2", "20", "1000"),
                    exposureSignal(OPEN_LONG, 2, "0.4", "40", "1000")), true);
            require(s.tick().contains("同批后续") && s.api.submissions == 0);
            s.tick();
            equal(d("0.06"), s.api.position.longs);
            require(s.api.submissions == 1);
        });
        test("在普通复制委托待核对时改系数，先记原单实仓，余量留待下一笔带单成交", () -> {
            Scenario s = exposure();
            s.api.partial = d("1");
            s.api.loseResponse = true;
            s.book.apply(exposureSignal(OPEN_SHORT, 1, "4", "400", "1000"), true);
            require(s.tick().contains("发送结果未确认"));
            String original = s.api.lastId;
            s.book.changeCopySettings(d("0.5"), BAND, 0, java.util.Map.of(), null);
            s.execution.commit();
            s.restart();
            require(s.tick().contains("部分成交") && s.api.lastId.equals(original));
            require(s.events.get(0).contains("复制系数 1×"));
            require(s.book.waits().isEmpty() && s.book.leg(BTC, false).adjustment.fromSetting());
            equal(d("1"), s.api.position.shorts);
            s.api.position.shortEntry = d("100");
            s.tick();
            require(s.api.submissions == 1);
            s.book.apply(exposureSignal(OPEN_SHORT, 2, "2", "200", "1000"), true);
            s.tick();
            equal(d("3"), s.api.position.shorts);
            require(s.api.submissions == 2 && s.events.get(1).contains("复制系数 0.5×"));
        });
        test("改系数需要减仓时，盘口不可用及暂停开仓均不影响市价减仓", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.price = d("97");
            s.api.failedQuoteSymbol = BTC;
            s.book.changeCopySettings(d("0.5"), d("0"), 2, java.util.Map.of(), d("1000"));
            s.execution.tick(s.book.config, () -> true, () -> false);
            equal(d("5"), s.api.lastQuantity);
            equal(d("5"), s.api.position.longs);
            require(s.api.lastLimit == null && s.book.leg(BTC, true).adjustment == null);
            require(s.events.get(1).contains("复制调仓 减多"));
        });
        test("无新带单操作时持续检查不利价差，重启和盘口回到阈值后才调仓", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_SHORT, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.shortEntry = d("100");
            s.api.price = d("98");
            s.book.changeCopySettings(d("1.5"), d("1"), 2, java.util.Map.of(), d("1000"));
            require(s.tick().contains("超出调仓不利价差 1%") && s.api.submissions == 1);
            s.restart();
            equal(d("1"), s.book.copySlippage);
            equal(d("100"), s.book.leg(BTC, false).adjustment.reference);
            s.api.price = d("99");
            s.tick();
            equal(d("15"), s.api.position.shorts);
            equal(d("99"), s.api.lastLimit);
            require(s.events.get(1).contains("调仓参考均价"));
            require(s.api.submissions == 2);
        });
        test("价差等待中带单员减仓，将目标更新为新仓位且只发一笔净差额", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            s.api.price = d("105");
            s.book.changeCopySettings(d("1.2"), d("0.5"), 2, java.util.Map.of(), d("1000"));
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("12"), s.book.leg(BTC, true).adjustment.target);
            s.api.depth = d("0.01");
            s.book.apply(exposureSignal(CLOSE_LONG, 2, "6", "600", "1000"), true);
            require(s.book.leg(BTC, true).adjustment.fromSetting());
            s.tick();
            equal(d("4.8"), s.api.position.longs);
            equal(d("5.2"), s.api.lastQuantity);
            require(s.api.submissions == 2 && s.api.lastLimit == null);
        });
        test("恢复旧版等待调仓，沿用本账户参考均价和调仓价差", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            s.api.price = d("101");
            s.book.changeCopySettings(d("0.8"), d("0"), 2, java.util.Map.of(), d("1000"));
            s.book.apply(new Rules.Signal(BTC, OPEN_LONG, TIME + 3000, d("90"), d("5"), null, d("450"), d("1000")), true);
            s.execution.commit();
            s.saved.getJSONArray("legs").getJSONObject(0).getJSONObject("copyAdjustment").put("addReference", "90");
            s.restart();
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("12"), s.book.leg(BTC, true).adjustment.target);
            s.api.price = d("100");
            s.tick();
            equal(d("2"), s.api.lastQuantity);
            equal(d("100"), s.api.lastLimit);
            equal(d("12"), s.api.position.longs);
            require(s.events.get(1).contains("调仓参考均价"));
        });
        test("等待加仓期间带单员加仓，按新数量与余额更新目标，继续守原调仓价差", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            s.api.price = d("105");
            s.book.changeCopySettings(d("2"), d("0.1"), 2, java.util.Map.of(), d("1000"));
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            s.book.apply(new Rules.Signal(BTC, OPEN_LONG, TIME + 3000, d("104"), d("5"), null, d("520"), d("1250")), true);
            s.api.price = d("104.1");
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("24"), s.book.leg(BTC, true).adjustment.target);
            s.api.price = d("100.1");
            s.tick();
            equal(d("14"), s.api.lastQuantity);
            equal(d("100.1"), s.api.lastLimit);
            equal(d("24"), s.api.position.longs);
            require(s.api.submissions == 2);
        });
        test("从待加转为待减，双向模式下只市价减少对应方向到新目标", () -> {
            Scenario s = exposure();
            s.api.hedge = true;
            s.book.apply(exposureSignal(OPEN_SHORT, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.shortEntry = d("100");
            s.api.price = d("95");
            s.book.changeCopySettings(d("2"), d("0"), 2, java.util.Map.of(), d("1000"));
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("20"), s.book.leg(BTC, false).adjustment.target);
            s.api.failedQuoteSymbol = BTC;
            Rules.Signal close = exposureSignal(CLOSE_SHORT, 3, "6", "600", "1000");
            s.book.acceptRecovered(List.of(close), true, java.util.Map.of(close.id, 3L), java.util.Map.of());
            s.tick();
            equal(d("8"), s.api.position.shorts);
            equal(d("2"), s.api.lastQuantity);
            require(s.api.submissions == 2 && s.api.lastLimit == null);
            require("BUY".equals(s.api.order.getString("side")) && "SHORT".equals(s.api.order.getString("positionSide")));
        });
        test("重连后以相同官网窗口恢复已保存的复制系数，只调仓一次", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            equal(d("10"), s.api.position.longs);
            require(s.book.queueCopySettings(d("0.5"), d("0.18"), TIME + 2000));
            s.execution.commit();
            s.restart();
            require(s.book.copyReplanPending && s.book.leg(BTC, true).adjustment == null);
            require(s.tick().contains("等待官网与账户核对") && s.api.submissions == 1);
            s.execution.refreshAccount(TIME + 3000);
            require(s.book.prepareRequestedCopy(java.util.Map.of(), d("1000")));
            s.execution.commit();
            s.restart();
            require(!s.book.copyReplanPending && s.book.leg(BTC, true).adjustment.fromSetting());
            s.api.failedQuoteSymbol = BTC;
            s.tick();
            equal(d("5"), s.api.position.longs);
            require(s.api.lastLimit == null && s.api.submissions == 2);
            require(!s.book.prepareRequestedCopy(java.util.Map.of(), d("1000")));
            s.tick();
            require(s.api.submissions == 2);
        });
        test("待加仓期间补回先于改系数检测到的减仓，按新目标市价减仓", () -> {
            Scenario s = exposure();
            s.book.apply(exposureSignal(OPEN_LONG, 1, "10", "1000", "1000"), true);
            s.tick();
            s.api.position.longEntry = d("100");
            s.api.price = d("105");
            s.book.changeCopySettings(d("2"), d("0.1"), TIME + 3000, java.util.Map.of(), d("1000"));
            require(s.tick().contains("超出调仓不利价差") && s.api.submissions == 1);
            equal(d("20"), s.book.leg(BTC, true).adjustment.target);
            Rules.Signal close = exposureSignal(CLOSE_LONG, 2, "6", "600", "1000");
            s.book.acceptRecovered(List.of(close), true, java.util.Map.of(close.id, TIME + 2500), java.util.Map.of());
            s.api.failedQuoteSymbol = BTC;
            s.tick();
            equal(d("8"), s.api.position.longs);
            require(s.api.lastLimit == null && s.api.submissions == 2);
        });
        test("带单成交触发的调仓使用当前复制点差，普通开仓仍用合约点差", () -> {
            Scenario s = exposure();
            s.book.copyFactor = d("0.1");
            s.book.copySlippage = d("0.1");
            s.book.acceptNew(List.of(exposureSignal(OPEN_LONG, 1, "0.2", "20", "1000"),
                    exposureSignal(OPEN_LONG, 2, "0.4", "40", "1000")), true);
            s.api.price = d("100.2");
            require(s.tick().contains("同批后续") && s.api.submissions == 0);
            require(s.tick().contains("超出调仓不利价差 0.1%") && s.api.submissions == 0);
            s.book.changeCopySettings(d("0.1"), d("0.6"), TIME + 3000, java.util.Map.of(), d("1000"));
            require(!s.book.copyReplanPending);
            s.api.price = d("100.4");
            s.tick();
            equal(d("0.06"), s.api.position.longs);
            equal(d("100.6"), s.api.lastLimit);
            require(s.api.submissions == 1);
        });
        test("慢合约仓位核对期间，另外两个合约可先计算并调仓", () -> {
            String slow = BTC;
            String first = "ETHUSDT";
            String second = "SOLUSDT";
            var config = new Rules.Config(LocalData.DEFAULT_PORTFOLIO, Set.of(slow, first, second), false, d("20"), 5, BAND);
            Scenario s = new Scenario(config, Rules.Strategy.EXPOSURE);
            for (String symbol : List.of(slow, first, second)) {
                FuturesApi.Position base = new FuturesApi.Position();
                base.longs = d("8");
                base.longEntry = d("100");
                s.api.bySymbol.put(symbol, base);
                FollowBook.Leg leg = s.book.leg(symbol, true);
                leg.owned = leg.target = leg.observed = d("8");
                leg.managed = true;
                leg.leader = leg.copied = d("8");
            }
            s.book.changeCopySettings(d("0.5"), BAND, TIME, java.util.Map.of(), d("1000"));

            var blocked = new java.util.concurrent.CountDownLatch(1);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var quick = new java.util.concurrent.CountDownLatch(4);
            var prepared = new java.util.concurrent.CountDownLatch(2);
            s.execution.prepareCopyAdjustmentsWith(() -> new Venue() {
                @Override public FuturesApi.Position position(String symbol) throws FuturesApi.Failure {
                    if (symbol.equals(slow)) {
                        entered.countDown();
                        try { blocked.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        throw new FuturesApi.Failure(504, -1007);
                    }
                    FuturesApi.Position base = new FuturesApi.Position();
                    base.longs = d("8");
                    base.longEntry = d("100");
                    quick.countDown();
                    return base;
                }
                @Override public void submit(String symbol, Rules.Action action, boolean hedge, BigDecimal qty,
                        BigDecimal limit, String id) { throw new AssertionError("调仓检查线程发送了委托"); }
            }, prepared::countDown);
            try {
                s.tick();
                require(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                require(quick.await(2, java.util.concurrent.TimeUnit.SECONDS));
                require(prepared.await(2, java.util.concurrent.TimeUnit.SECONDS));
                s.execution.finishReadyCopyAdjustments(s.book.config, () -> true, () -> true);
                long expires = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                require(s.api.submissions == 2 && blocked.getCount() == 1);
                require(s.book.pending == null);
                require(!s.api.usedIds.get(0).equals(s.api.usedIds.get(1)));
                equal(d("8"), s.api.bySymbol.get(slow).longs);
                equal(d("4"), s.api.bySymbol.get(first).longs);
                equal(d("4"), s.api.bySymbol.get(second).longs);
                blocked.countDown();
                while (System.nanoTime() < expires) {
                    if (s.tick().contains("检查待重试")) return;
                    Thread.sleep(10);
                }
                throw new AssertionError("慢合约失败后未显示独立重试");
            } finally {
                blocked.countDown();
                s.execution.closeCopyChecks();
            }
        });
        System.out.println(passed + " automatic follow checks passed");
    }

    private static final class Scenario {
        final List<String> events = new ArrayList<>();
        final Venue api = new Venue();
        FollowBook book;
        FollowExecutor execution;
        JSONObject saved;
        Runnable savedHook = () -> {};
        final java.util.Set<String> cancelledIds = new java.util.HashSet<>();
        boolean failSave;

        Scenario(boolean ratio) { this(ratio, Set.of(BTC)); }

        Scenario(boolean ratio, Set<String> symbols) { this(ratio, symbols, 5); }

        Scenario(boolean ratio, Set<String> symbols, int leverage) {
            this(new Rules.Config(LocalData.DEFAULT_PORTFOLIO, symbols, ratio, d(ratio ? "5" : "20"), leverage, BAND));
        }

        Scenario(Rules.Config config) {
            this(config, Rules.Strategy.POINTS);
        }

        Scenario(Rules.Config config, Rules.Strategy strategy) {
            book = new FollowBook(config, "test", strategy);
            wire();
            api.beforePost = () -> require(saved != null && saved.optJSONObject("pending") != null);
        }

        void wire() {
            execution = new FollowExecutor(book, api, () -> {
                if (failSave) throw new java.io.IOException("disk");
                saved = new JSONObject(book.json().toString());
                savedHook.run();
            }, (id, message, sound) -> events.add(message), id -> !cancelledIds.contains(id));
        }

        void restart() throws Exception { book = FollowBook.restore(saved); wire(); }
        String tick() throws Exception { return execution.tick(book.config, () -> true, () -> true); }
    }

    private static class Venue implements FollowExecutor.Exchange {
        final FuturesApi.Position position = new FuturesApi.Position();
        final java.util.Map<String, FuturesApi.Position> bySymbol = new java.util.HashMap<>();
        Set<String> workingOrderIds = Set.of();
        final List<String> usedIds = new ArrayList<>();
        List<FuturesApi.AccountTrade> recent = List.of();
        BigDecimal price = d("100"), depth = d("100"), available = d("1000"), partial, lastQuantity, lastLimit, realizedPnl;
        BigDecimal copyEquity = d("1000");
        int walletReads, copyReads, walletTimeouts;
        boolean hedge, loseResponse, hideOrderOnce, reject, neverSent;
        int submissions, leverage = 5, leverageChanges;
        int rejectedLeverageCap, observedLeverageCap = 125;
        List<FuturesApi.LeverageBracket> brackets = List.of(new FuturesApi.LeverageBracket(125, d("100000")));
        String lastId, failedQuoteSymbol, invalidPositionSymbol;
        FuturesApi.Failure rejection;
        FuturesApi.Contract contract = new FuturesApi.Contract(d("0.01"), d("0.001"), d("0.001"), d("100"),
                d("0.001"), d("0.001"), d("100"), d("5"));
        JSONObject order;
        FuturesApi.Fills fills;
        Runnable beforePost;

        @Override public FuturesApi.Wallet wallet() throws java.net.SocketTimeoutException {
            walletReads++;
            if (walletTimeouts > 0) {
                walletTimeouts--;
                throw new java.net.SocketTimeoutException();
            }
            return new FuturesApi.Wallet(d("1000"), available);
        }
        @Override public FuturesApi.Wallet copyWallet() {
            copyReads++;
            return new FuturesApi.Wallet(copyEquity, available);
        }
        @Override public FuturesApi.Contract contract(String symbol) { return contract; }
        @Override public Rules.Quote quote(String symbol) throws Exception {
            if (symbol.equals(failedQuoteSymbol)) throw new FuturesApi.Failure(400, -1121);
            return new Rules.Quote(price, price, depth, depth);
        }
        @Override public boolean hedgeMode() { return hedge; }
        @Override public FuturesApi.Position position(String symbol) throws FuturesApi.Failure {
            if (symbol.equals(invalidPositionSymbol)) throw new FuturesApi.Failure(400, -1121);
            return bySymbol.getOrDefault(symbol, position);
        }
        @Override public java.util.Map<String, FuturesApi.Position> positions() {
            java.util.Map<String, FuturesApi.Position> positions = new java.util.HashMap<>(bySymbol);
            positions.putIfAbsent(BTC, position);
            return positions;
        }
        @Override public java.util.Map<String, Set<String>> openOrderIds() { return java.util.Map.of(BTC, workingOrderIds); }
        @Override public int allowedLeverage(String symbol, int requested, BigDecimal exposure, BigDecimal margin,
                BigDecimal remainingNotional) {
            return FuturesApi.highestLeverage(brackets, Math.min(requested, observedLeverageCap), exposure, margin, remainingNotional);
        }
        @Override public int currentLeverage(String symbol) { return leverage; }
        @Override public int changeLeverage(String symbol, int value) throws Exception {
            if (rejectedLeverageCap > 0 && value > rejectedLeverageCap) {
                observedLeverageCap = rejectedLeverageCap;
                throw new FuturesApi.Failure(400, -4209, "Current symbol max leverage limit is " + rejectedLeverageCap + "x");
            }
            leverageChanges++;
            leverage = value;
            return leverage;
        }
        @Override public void submit(String symbol, Rules.Action action, boolean dual, BigDecimal amount,
                BigDecimal limit, String id) throws Exception {
            beforePost.run();
            if (neverSent) { neverSent = false; throw new FuturesApi.NotSent("signed preflight", null); }
            submissions++;
            lastId = id;
            lastQuantity = amount;
            lastLimit = limit;
            usedIds.add(id);
            if (rejection != null) {
                FuturesApi.Failure failure = rejection;
                rejection = null;
                throw failure;
            }
            if (reject) { reject = false; throw new FuturesApi.Failure(400, -2019, "Margin is insufficient."); }
            BigDecimal filled = partial == null ? amount : partial;
            partial = null;
            require(amount.signum() > 0 && amount.compareTo(filled) >= 0 && dual == hedge);
            BigDecimal signed = action.opens ? filled : filled.negate();
            FuturesApi.Position position = position(symbol);
            if (action.isLong) position.longs = position.longs.add(signed);
            else position.shorts = position.shorts.add(signed);
            require(position.longs.signum() >= 0 && position.shorts.signum() >= 0);
            order = new JSONObject().put("symbol", symbol).put("side", action.exchangeSide()).put("clientOrderId", id)
                    .put("positionSide", dual ? action.positionSide() : "BOTH").put("origQty", Rules.show(amount))
                    .put("executedQty", Rules.show(filled)).put("orderId", submissions)
                    .put("status", filled.compareTo(amount) == 0 ? "FILLED" : "EXPIRED").put("updateTime", TIME);
            fills = new FuturesApi.Fills();
            fills.quantity = filled;
            fills.value = filled.multiply(price);
            fills.realizedPnl = realizedPnl;
            fills.minPrice = fills.maxPrice = price;
            fills.latest = TIME;
            if (loseResponse) { loseResponse = false; throw new java.net.SocketTimeoutException(); }
        }
        @Override public JSONObject order(String symbol, String id) throws Exception {
            if (hideOrderOnce) { hideOrderOnce = false; throw new FuturesApi.Failure(400, -2013); }
            require(id.equals(lastId));
            return order;
        }
        @Override public FuturesApi.Fills fills(String symbol, long id) { return fills; }
        @Override public List<FuturesApi.AccountTrade> tradesSince(String symbol, long since) { return recent; }
    }

    private interface Check { void run() throws Exception; }
    private static Scenario exposure() {
        return new Scenario(new Rules.Config(LocalData.DEFAULT_PORTFOLIO, Set.of(BTC), false, d("20"), 5, BAND),
                Rules.Strategy.EXPOSURE);
    }

    private static Rules.Signal exposureSignal(Rules.Action action, int n, String quantity, String turnover, String balance) {
        return new Rules.Signal(BTC, action, TIME + n * 1000, d("100"), d(quantity), null,
                turnover == null ? null : d(turnover), balance == null ? null : d(balance));
    }
    private static void expectBlocked(Check check, String reason) throws Exception {
        try { check.run(); }
        catch (IllegalStateException expected) {
            require(expected.getMessage().contains(reason));
            return;
        }
        throw new AssertionError("该账户尚不能切换");
    }
    private static void test(String name, Check check) throws Exception {
        check.run();
        passed++;
        System.out.println("PASS · " + name);
    }
    private static void rejected(Check check) throws Exception {
        try { check.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("应拒绝异常快照");
    }
    private static Rules.Signal signal(Rules.Action action, int n, String quantity) {
        return signal(action, n, quantity, "100");
    }
    private static Rules.Signal signal(Rules.Action action, int n, String quantity, String price) {
        return new Rules.Signal(BTC, action, TIME + n * 1000, d(price), d(quantity));
    }
    private static BigDecimal d(String text) { return new BigDecimal(text); }
    private static void require(boolean ok) { if (!ok) throw new AssertionError("Unexpected state"); }
    private static void equal(BigDecimal expected, BigDecimal got) {
        if (expected.compareTo(got) != 0) throw new AssertionError("Expected " + expected + " got " + got);
    }
}
