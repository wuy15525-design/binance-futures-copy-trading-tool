package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ClosedPositionReviewTest {
    private static final long OPEN = 1795269872000L;
    private static final long CLOSE = 1795274345000L;
    private static final String SHORT = "BTCUSDT|SHORT";
    private static final String NEW = "426111111";
    private static final String UPGRADE = "426111112";
    private static final String LIVE = "426111113";
    private static final String HISTORY = """
            BTCUSDT
            永续
            10x
            全仓
            做空
            全部平仓
            开仓时间
            2026-11-21 22:04:32
            开仓价格
            60,674.37 USDT
            最大持仓量
            66.554 BTC
            平仓盈亏
            +54,905.78 USDT
            全部平仓
            2026-11-21 23:19:05
            平仓均价
            59,819.26 USDT
            已平仓量
            66.554 BTC
            """;

    public static void main(String[] args) throws Exception {
        DesktopApp app = new DesktopApp();
        LeaderArchive archive = app.archive;
        if (args.length != 0) {
            check(archive.count(UPGRADE) == 3 && archive.revision(UPGRADE) > 0, "重启保留已修复的档案");
            check(!archive.state(UPGRADE).holdings.containsKey(SHORT), "重启后 BTC 空仓维持归零");
            check(archive.accountFillCount(UPGRADE) == 1, "重启保留账户成交");
            check(archive.connectsToTail(UPGRADE, archive.state(UPGRADE), original()), "重启保留官网重复的核对结果");
            check(archive.count("8175417") == 3 && archive.provisionalReviews("8175417").size() == 1,
                    "重启后保留同秒待核对的原始样本和暂定笔数");
            System.out.println("PASS · restart preserves the corrected archive, canonical overlap and original account fill");
            return;
        }

        var positions = ClosedTradeReview.parse(new JSONObject().put("zone", "Asia/Shanghai")
                .put("positions", new JSONArray().put(HISTORY).put(HISTORY)
                        .put(HISTORY.replace("66.554 BTC", "66.554 USDT"))));
        check(positions.size() == 1, "同仓位卡片去重，拒绝错误单位");
        var position = positions.get(0);
        check(position.openedAt() == OPEN && position.closedAt() == CLOSE, "按官网时区解读秒级开平仓时间");
        var raw = original();
        var corrected = ClosedTradeReview.corrections(raw, positions);
        check(corrected.get(OPEN).size() == 1, "BTC 33.303 + 33.251 恰好平空 66.554");
        check(ClosedTradeReview.corrections(raw, List.of()).isEmpty(), "缺少仓位历史不能推定网页重复");
        check(ClosedTradeReview.corrections(raw.subList(0, 3), positions).isEmpty(), "缺失平仓成交不归并");

        var genuine = List.of(raw.get(0), raw.get(1), raw.get(2),
                signal(Rules.Action.CLOSE_SHORT, CLOSE, "59819.26", "99.857", null, null));
        var genuinePosition = new ClosedTradeReview.Position(SHORT, OPEN, CLOSE, d("99.857"), d("99.857"));
        check(ClosedTradeReview.corrections(genuine, List.of(genuinePosition)).isEmpty(), "两笔完全相同的真实成交保留");
        var second = signal(Rules.Action.OPEN_SHORT, OPEN + 1000, "60742", "33.303", null, null);
        var guessed = List.of(raw.get(0), raw.get(1), second, second.withOccurrence(1),
                signal(Rules.Action.CLOSE_SHORT, CLOSE, "59819.26", "99.909", null, null));
        var ambiguous = new ClosedTradeReview.Position(SHORT, OPEN, CLOSE, d("99.909"), d("99.909"));
        check(ClosedTradeReview.corrections(guessed, List.of(ambiguous)).isEmpty(), "两种归并都对得上时保留记录");

        var closeTwice = List.of(raw.get(0), raw.get(2), raw.get(3), raw.get(3).withOccurrence(1));
        check(ClosedTradeReview.corrections(closeTwice, positions).get(CLOSE).size() == 1, "同样支持重复平仓卡片");
        archive.confirmClosedPositionReviews(NEW, raw, positions);
        var confirmed = archive.normalizeReviewedDuplicates(NEW, raw, null);
        archive.capture(NEW, "建档", confirmed, CLOSE + 3000, true, "完整基线");
        check(archive.count(NEW) == 3 && !archive.state(NEW).holdings.containsKey(SHORT), "新建档只确认一笔重复开空");
        check(archive.detectionCount(NEW) == 0, "修正的基线不产生新委托信号");

        archive.capture(UPGRADE, "旧建档", raw, CLOSE + 3000, true, "完整基线");
        archive.queueReviews(UPGRADE, TradeReview.repeatedSeconds(raw));
        archive.resolveReview(UPGRADE, archive.nextReview(UPGRADE), TradeReview.at(raw, OPEN));
        archive.saveAccountFill(UPGRADE, "testnet-web", "remaining-fill", CLOSE, "原账户成交");
        var previous = archive.state(UPGRADE);
        check(previous.holdings.get(SHORT).quantity.compareTo(d("33.303")) == 0, "旧算法留下虚增空仓");
        check(archive.hasArchivedRepeats(UPGRADE), "即使历史重复不在当前网页窗口仍可安排核对");
        check(archive.confirmClosedPositionReviews(UPGRADE, List.of(raw.get(3)), positions) == 1, "只用旧档也能修复");
        check(archive.state(UPGRADE).head == previous.head, "修正保持原档案游标");
        check(archive.after(UPGRADE, 0, previous.head).stream().filter(row -> row.excluded).count() == 1, "账本水位保留被排除序号");
        check(archive.visibleAt(UPGRADE, previous.head, 0, 0).get(SHORT).compareTo(d("33.303")) == 0, "保留旧投影用于账本迁移");
        check(!archive.visibleAt(UPGRADE, previous.head, 0).containsKey(SHORT), "已确认仓位按新投影归零");
        check(archive.connectsToTail(UPGRADE, archive.state(UPGRADE), raw), "官网仍显示重复也能衔接");
        check(archive.capture(UPGRADE, "", archive.normalizeReviewedDuplicates(UPGRADE, raw, null),
                CLOSE + 4000).added == 0, "刷新不会重新加入该笔");
        check(archive.count(UPGRADE) == 3 && archive.accountFillCount(UPGRADE) == 1, "历史修复不改原账户成交");

        Rules.Signal anchor = signal(Rules.Action.OPEN_LONG, OPEN - 1000, "60000", "1", null, null);
        archive.capture(LIVE, "", List.of(anchor), OPEN, true, "完整基线");
        archive.capture(LIVE, "", List.of(anchor, raw.get(0), raw.get(1), raw.get(2), raw.get(3)), CLOSE + 3000);
        check(archive.detectionCount(LIVE) == 4, "活动跟单里的历史信号测试");
        check(archive.confirmClosedPositionReviews(LIVE, raw, positions) == 0 && archive.revision(LIVE) == 0,
                "不改写进入过跟单流程的增量信号");
        provisionalCopies(archive);
        settlePreviousBaselinePolicy(archive);
        System.out.println("PASS · official 66.554 BTC proof, new and old baseline, stable cursor, no order replay, genuine and ambiguous trades");
    }

    private static void settlePreviousBaselinePolicy(LeaderArchive archive) throws Exception {
        String portfolio = "8175551";
        var raw = original();
        archive.capture(portfolio, "", raw, CLOSE + 1000, true, "基线", false, 0, false,
                Map.of(OPEN, TradeReview.at(raw, OPEN)));
        check(archive.count(portfolio) == 4 && archive.provisionalReviews(portfolio).size() == 1,
                "旧版把同秒开仓标成待核对");
        check(archive.settlePendingBaselineCounts(portfolio), "升级后本地合并相同开仓");
        check(archive.count(portfolio) == 3 && archive.provisionalReviews(portfolio).isEmpty(),
                "去重后档案继续可用");
        check(archive.detectionCount(portfolio) == 0, "旧成交合并不产生新信号");
    }

    private static void provisionalCopies(LeaderArchive archive) throws Exception {
        String portfolio = "8175416";
        long time = 1795269872000L;
        Rules.Signal first = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_SHORT, time,
                new BigDecimal("60000"), new BigDecimal("1"));
        Rules.Signal older = new Rules.Signal("ETHUSDT", Rules.Action.OPEN_LONG, time - 1000,
                new BigDecimal("3200"), new BigDecimal("2"));
        Rules.Signal newer = new Rules.Signal("ETHUSDT", Rules.Action.OPEN_LONG, time + 1000,
                new BigDecimal("3300"), new BigDecimal("1"));
        Rules.Signal close = new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_SHORT, time + 2000,
                new BigDecimal("58000"), new BigDecimal("2"));
        List<Rules.Signal> both = List.of(first, first.withOccurrence(1));
        List<Rules.Signal> raw = List.of(older, first, first.withOccurrence(1), newer);
        Map<String, Integer> limits = new java.util.LinkedHashMap<>();
        TradeReview.recordPageRepeats(List.of(first, newer), raw, limits);
        List<Rules.Signal> counted = TradeReview.limitCopies(both, limits);
        assertEquals(1, counted.size());
        assertEquals(2, TradeReview.limitCopies(both, Map.of()).size());

        archive.capture(portfolio, "测试", raw, time + 3000, true, "基线", false, 0, false, Map.of(time, counted));
        LeaderArchive.State initial = archive.state(portfolio);
        assertTrue(initial.historyComplete);
        assertEquals(3L, archive.count(portfolio));
        assertEquals(4L, initial.head);
        assertNull(archive.nextReview(portfolio));
        assertEquals(Set.of("BTCUSDT|SHORT"), TradeReview.repeatedLegs(archive.provisionalReviews(portfolio).get(0).sample));
        assertEquals(3, archive.normalizeProvisional(portfolio, raw, null).size());
        assertFalse(archive.resolveProvisionalReview(portfolio, archive.provisionalReviews(portfolio).get(0), both, false));
        assertEquals(new BigDecimal("1"), archive.state(portfolio).holdings.get("BTCUSDT|SHORT").quantity);
        archive.capture(portfolio, "", List.of(older, first, newer, close), time + 4000);
        assertEquals(1L, archive.detectionCount(portfolio));

        ClosedTradeReview.Position position = new ClosedTradeReview.Position("BTCUSDT|SHORT", time,
                time + 2000, new BigDecimal("2"), new BigDecimal("2"));
        assertEquals(1, archive.confirmClosedPositionReviews(portfolio, List.of(), List.of(position)));
        assertTrue(archive.provisionalReviews(portfolio).isEmpty());
        assertEquals(5L, archive.count(portfolio));
        assertEquals(5L, archive.state(portfolio).head);
        assertEquals(1L, archive.detectionCount(portfolio));
        assertTrue(archive.revision(portfolio) > 0);
        assertEquals(new BigDecimal("1"), archive.visibleAt(portfolio, 4, 0, 0).get("BTCUSDT|SHORT"));
        assertEquals(new BigDecimal("2"), archive.visibleAt(portfolio, 4, 0).get("BTCUSDT|SHORT"));
        assertEquals(4, archive.normalizeProvisional(portfolio, List.of(older, first, newer), null).size());
        assertEquals(0, archive.capture(portfolio, "", archive.normalizeProvisional(portfolio,
                List.of(older, first, newer, close), null), time + 5000).added);

        String unconfirmed = "8175417";
        archive.capture(unconfirmed, "测试", raw, time + 3000, true, "基线", false, 0, false, Map.of(time, counted));
    }

    private static void assertEquals(Object expected, Object actual) {
        check(Objects.equals(expected, actual), "预期 " + expected + "，实际 " + actual);
    }

    private static void assertTrue(boolean value) { check(value, "预期为真"); }
    private static void assertFalse(boolean value) { check(!value, "预期为假"); }
    private static void assertNull(Object value) { check(value == null, "预期没有待处理的普通复核"); }

    private static List<Rules.Signal> original() {
        Rules.Signal twin = signal(Rules.Action.OPEN_SHORT, OPEN, "60747.14", "33.303", "2023061.91", null);
        return List.of(twin, twin.withOccurrence(1),
                signal(Rules.Action.OPEN_SHORT, OPEN + 24000, "60601.48", "33.251", "2015059.92", null),
                signal(Rules.Action.CLOSE_SHORT, CLOSE, "59819.26", "66.554", "3981211.21", "36981.39"));
    }

    private static Rules.Signal signal(Rules.Action action, long stamp, String price, String size, String turnover, String pnl) {
        return new Rules.Signal("BTCUSDT", action, stamp, d(price), d(size), pnl == null ? null : d(pnl),
                turnover == null ? null : d(turnover), null);
    }

    private static BigDecimal d(String value) { return new BigDecimal(value); }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
