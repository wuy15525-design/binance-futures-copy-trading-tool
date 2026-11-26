package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.Action.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class TerminalTests {
    private static final String MU = "MUUSDT";
    private static final String PORT = LocalData.DEFAULT_PORTFOLIO;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate START = LocalDate.of(2026,11,13), END = LocalDate.of(2026,11,19);
    private static final String PAGE = "11-18, 23:40:31\n平多\n以均价为701.40320USDT 卖出平多MUUSDT永续合约 ，"
            + "成交数量为 532.92MU，总价值为 373,791.79329USDT ，已实现盈亏为-6,316.93560USDT。\n"
            + "11-18, 23:14:58\n开多\n以均价为714.67808USDT 买入开多MUUSDT永续合约 ，"
            + "成交数量为 251.94MU，总价值为 180,055.99439USDT 。\n"
            + "11-18, 23:14:42\n开多\n以均价为711.98211USDT 买入开多MUUSDT永续合约 ，"
            + "成交数量为 280.98MU，总价值为 200,052.73448USDT 。\n"
            + "11-18, 23:00:27\n平空\n以均价为63,732.10USDT 买入平空BTCUSDT永续合约 ，"
            + "成交数量为 28.099BTC，总价值为 1,790,808.16USDT 。\n更多";

    private interface Test { void run() throws Exception; }
    private static int count;
    private static long now = 1_795_016_431_494L;

    public static void main(String[] args) throws Exception {
        test("新建档合并相同开仓，按可见开仓及后续不同平仓保留相同平仓的有效笔数", () -> {
            Rules.Signal open = new Rules.Signal(MU, OPEN_SHORT, now, d("100"), d("10"));
            Rules.Signal fullClose = new Rules.Signal(MU, CLOSE_SHORT, now + 1000, d("95"), d("10"));
            TradeReview.BaselineCounts full = TradeReview.baselineCounts(List.of(open, open.withOccurrence(1),
                    fullClose, fullClose.withOccurrence(1)));
            ok(full.rows.equals(List.of(open, fullClose)));
            ok(full.settled.size() == 2);

            Rules.Signal part = new Rules.Signal(MU, CLOSE_SHORT, now + 1000, d("95"), d("3"));
            Rules.Signal remaining = new Rules.Signal(MU, CLOSE_SHORT, now + 2000, d("94"), d("4"));
            TradeReview.BaselineCounts both = TradeReview.baselineCounts(List.of(open, part, part.withOccurrence(1), remaining));
            ok(both.rows.size() == 4 && both.settled.get(part.time).size() == 2);

            Rules.Signal eight = new Rules.Signal(MU, CLOSE_SHORT, now + 2000, d("94"), d("7"));
            TradeReview.BaselineCounts one = TradeReview.baselineCounts(List.of(open, part, part.withOccurrence(1), eight));
            ok(one.rows.equals(List.of(open, part, eight)));
            ok(one.settled.get(part.time).size() == 1);

            TradeReview.BaselineCounts unknown = TradeReview.baselineCounts(List.of(part, part.withOccurrence(1)));
            ok(unknown.rows.size() == 2 && unknown.settled.isEmpty());

            TradeReview.BaselineCounts upgrade = TradeReview.baselineCounts(List.of(open, open.withOccurrence(1),
                    fullClose, fullClose.withOccurrence(1)), java.util.Set.of(fullClose.time));
            ok(upgrade.rows.size() == 4 && upgrade.settled.get(fullClose.time).size() == 2);
        });
        test("衔接旧档时按已知开空和后续平空数量归并唯一多出的副本", () -> {
            Rules.Signal open = new Rules.Signal(MU, OPEN_SHORT, now, d("100"), d("10.25"));
            Rules.Signal close = new Rules.Signal(MU, CLOSE_SHORT, now + 1000, d("95"), d("3.25"),
                    d("16.25"), d("308.75"), null);
            Rules.Signal next = new Rules.Signal(MU, CLOSE_SHORT, now + 2000, d("94"), d("7"));
            Rules.Signal otherSide = new Rules.Signal(MU, OPEN_LONG, close.time, d("95"), d("2"));
            List<Rules.Signal> raw = List.of(open, close, otherSide, close.withOccurrence(1), next);
            Map<Long, List<Rules.Signal>> verified = TradeReview.archiveCloseCounts(raw, close.time);
            ok(verified.size() == 1 && verified.get(close.time).equals(List.of(close, otherSide)));
            ok(TradeReview.replace(raw, verified, null).equals(List.of(open, close, otherSide, next)));
        });
        test("旧档本地复核保留可行的真实多笔、明细差异和缺少开仓依据的成交", () -> {
            Rules.Signal open = new Rules.Signal(MU, OPEN_SHORT, now, d("100"), d("10"));
            Rules.Signal close = new Rules.Signal(MU, CLOSE_SHORT, now + 1000, d("95"), d("3"), d("15"));
            Rules.Signal repeated = close.withOccurrence(1);
            Rules.Signal later = new Rules.Signal(MU, CLOSE_SHORT, now + 2000, d("94"), d("7"));
            Rules.Signal smaller = new Rules.Signal(MU, CLOSE_SHORT, later.time, d("94"), d("4"));
            Rules.Signal different = new Rules.Signal(MU, CLOSE_SHORT, close.time, d("95"), d("3"), d("16"))
                    .withOccurrence(1);
            Rules.Signal unknown = new Rules.Signal(MU, SELL, now, d("100"), d("10"));
            Rules.Signal openingThree = new Rules.Signal(MU, OPEN_SHORT, now, d("100"), d("3"));
            Rules.Signal reopened = new Rules.Signal(MU, OPEN_SHORT, close.time, d("99"), d("3"));
            for (List<Rules.Signal> uncertain : List.of(List.of(open, close, repeated, smaller),
                    List.of(open, close, different, later), List.of(close, repeated, later),
                    List.of(unknown, close, repeated, later), List.of(open, close, repeated, close.withOccurrence(2), smaller),
                    List.of(openingThree, close, reopened, repeated))) {
                ok(TradeReview.archiveCloseCounts(uncertain, close.time).isEmpty());
            }
        });
        test("更早历史不完整时仍可按已知仓位核对同一轮的多个分页副本", () -> {
            Rules.Signal earlier = new Rules.Signal(MU, CLOSE_SHORT, now - 1000, d("98"), d("2"));
            Rules.Signal open = new Rules.Signal(MU, OPEN_SHORT, now, d("100"), d("10"));
            Rules.Signal first = new Rules.Signal(MU, CLOSE_SHORT, now + 1000, d("97"), d("3"));
            Rules.Signal second = new Rules.Signal(MU, CLOSE_SHORT, now + 2000, d("96"), d("2"));
            Rules.Signal finalClose = new Rules.Signal(MU, CLOSE_SHORT, now + 3000, d("95"), d("5"));
            List<Rules.Signal> raw = List.of(earlier, open, first, first.withOccurrence(1),
                    second, second.withOccurrence(1), finalClose);
            Map<Long, List<Rules.Signal>> verified = TradeReview.archiveCloseCounts(raw, first.time);
            ok(verified.size() == 2);
            ok(TradeReview.replace(raw, verified, null).equals(List.of(earlier, open, first, second, finalClose)));

            Rules.Signal ambiguous = new Rules.Signal(MU, CLOSE_SHORT, finalClose.time, d("95"), d("3"));
            List<Rules.Signal> insufficient = new ArrayList<>(raw);
            insufficient.set(insufficient.size() - 1, ambiguous);
            ok(TradeReview.archiveCloseCounts(insufficient, first.time).isEmpty());
        });
        test("中文合约代码可保存，官网成交及平仓历史保留原单位", () -> {
            String symbol = "币安人生USDT";
            String card = "11-18, 23:14:58\n开多\n以价格为0.1 USDT买入开多币安人生USDT永续合约，成交数量为2 币安人生总价值为0.2 USDT。";
            Rules.Signal trade = webpage(card).get(0);
            ok(symbol.equals(trade.symbol));
            ok(d("2").compareTo(trade.quantity) == 0);
            ok(d("0.2").compareTo(trade.turnover) == 0);
            rejected(() -> webpage(card.replace("2 币安人生", "2 其他币种")));
            rejected(() -> webpage(card.replace("2 币安人生", "2 币安人生猫")));
            Rules.Config config = new Rules.Config(PORT, Rules.symbols(symbol + ",xauusdt"), false, d("20"), 5, d("0.3"));
            ok(LocalData.readConfig(LocalData.configJson(config)).symbols.equals(config.symbols));
            ok(!Rules.isUsdtSymbol("币安人生&XUSDT"));
            String closed = symbol + "\n永续\n做多\n开仓时间\n2026-11-18 23:14:58\n最大持仓量\n2 币安人生"
                    + "\n全部平仓\n2026-11-18 23:15:02\n已平仓量\n2 币安人生";
            List<ClosedTradeReview.Position> positions = ClosedTradeReview.parse(new org.json.JSONObject()
                    .put("zone", ZONE.getId()).put("positions", new org.json.JSONArray().put(closed)));
            ok(positions.size() == 1 && positions.get(0).covers(trade));
        });
        test("只显示买卖方向时保留原记录，跨过零或缺少可验证仓位不会产生委托", () -> {
            String card = "11-18, 23:14:58\n做空\n以价格为700 USDT卖出MUUSDT永续合约，成交数量为2 MU，已实现盈亏为0.00 USDT。";
            Rules.Signal sell = webpage(card).get(0);
            ok(sell.action == SELL && sell.realizedPnl.signum() == 0);
            rejected(() -> webpage(card.replace("2 MU", "2 BTC")));
            List<Rules.Signal> rows = List.of(sell);
            ok(OneWayTrades.resolve(rows, 0, Map.of(), List.of()).get(0).action == SELL);
            ok(OneWayTrades.resolve(rows, 0, Map.of(MU, d("1")), List.of()).get(0).action == SELL);
            Rules.Signal close = OneWayTrades.resolve(rows, 0, Map.of(MU, d("2")), List.of()).get(0);
            ok(close.action == CLOSE_LONG && sell.id.equals(close.id));
            Rules.Signal saved = LocalData.readSignals(LocalData.signalJson(List.of(close))).get(0);
            ok(saved.id.equals(sell.id) && saved.reportedAction == SELL && saved.action == CLOSE_LONG);
            ok(LeaderHistory.merge(List.of(saved), rows, true, false).rows.get(0).action == CLOSE_LONG);
            FollowBook book = new FollowBook(new Rules.Config(PORT, java.util.Set.of(MU), false, d("20"), 5, d("0.3")), "");
            book.seedPositions(Map.of(MU + "|LONG", d("1")));
            book.acceptNew(rows, true);
            ok(book.knownPositions().get(MU + "|LONG").compareTo(d("1")) == 0 && book.countEntries() == 0);
        });
        test("同秒复核可以选择官网默认范围之前的日期", () -> {
            LocalDate displayed = LocalDate.of(2026, 11, 15);
            ok(WebRecords.reviewStart(LocalDate.of(2026, 11, 14), displayed).equals(LocalDate.of(2026, 11, 12)));
            ok(WebRecords.reviewStart(LocalDate.of(2026, 11, 16), displayed).equals(displayed));
            ok(WebRecords.reviewStart(LocalDate.of(2026, 11, 16), null).equals(LocalDate.of(2026, 11, 14)));
        });
        test("日期续读验证交界日并保留真实同秒成交", () -> {
            long midnight = LocalDate.of(2026, 11, 20).atStartOfDay(ZONE).toInstant().toEpochMilli();
            long tomorrow = LocalDate.of(2026, 11, 21).atStartOfDay(ZONE).toInstant().toEpochMilli();
            Rules.Signal old = row(MU, OPEN_LONG, midnight - 1000, "660", "2");
            Rules.Signal boundary = new Rules.Signal(MU, OPEN_LONG, midnight + 1000, d("661"), d("2"),
                    null, d("1322"), null);
            Rules.Signal later = row(MU, CLOSE_LONG, tomorrow, "675", "2");
            List<Rules.Signal> newer = List.of(boundary, later);
            List<Rules.Signal> older = List.of(old, boundary, boundary.withOccurrence(1));
            List<Rules.Signal> joined = LeaderHistory.joinBaseline(newer, older, tomorrow);
            ok(joined.size() == 4 && joined.get(0) == old && joined.get(3) == later);
            ok(!joined.get(1).id.equals(joined.get(2).id));
            rejected(() -> LeaderHistory.joinBaseline(newer, List.of(old), tomorrow));
            rejected(() -> LeaderHistory.joinBaseline(newer, List.of(old, boundary, later), tomorrow));
            var changed = new Rules.Signal(MU, OPEN_LONG, boundary.time, boundary.price, boundary.quantity, null, d("1321"), null);
            rejected(() -> LeaderHistory.joinBaseline(newer, List.of(old, changed), tomorrow));
        });
        test("补页时间交错时按时间归并，同秒的官网顺序不变", () -> {
            String high = "11-18, 23:14:58\n平多\n以均价为714USDT 卖出平多MUUSDT永续合约，成交数量为 1MU。\n";
            String low = "11-18, 23:14:58\n开多\n以均价为712USDT 买入开多MUUSDT永续合约，成交数量为 1MU。\n";
            String older = "11-18, 23:00:00\n开空\n以均价为700USDT 卖出开空MUUSDT永续合约，成交数量为 1MU。\n";
            String middle = "11-18, 23:11:00\n开空\n以均价为700USDT 卖出开空MUUSDT永续合约，成交数量为 1MU。\n";
            List<Rules.Signal> initial = webpage(high + low);
            List<Rules.Signal> expanded = webpage(high + older + middle + low);
            ok(expanded.size() == 4 && expanded.get(2).action == OPEN_LONG && expanded.get(3).action == CLOSE_LONG);
            ok(LeaderHistory.pageGrowth(initial, expanded) == 2);
            rejected(() -> LeaderHistory.pageGrowth(initial, webpage(high + older + middle)));
        });
        test("跨日期重读的重复次数不同，保留候选但不跳过少掉的真实明细", () -> {
            long start = LocalDate.of(2026, 11, 20).atStartOfDay(ZONE).toInstant().toEpochMilli();
            Rules.Signal older = row(MU, OPEN_LONG, start - 1000, "650", "1");
            Rules.Signal seam = row(MU, OPEN_LONG, start + 1000, "660", "2");
            Rules.Signal another = row(MU, OPEN_LONG, seam.time, "661", "2");
            Rules.Signal newest = row(MU, CLOSE_LONG, start + 86400000L, "670", "1");
            List<Rules.Signal> retained = List.of(seam, seam.withOccurrence(1), another, newest);
            List<Rules.Signal> joined = LeaderHistory.joinBaseline(retained, List.of(older, seam, another), newest.time);
            ok(joined.size() == 5 && joined.get(0) == older && joined.get(4) == newest);
            ok(TradeReview.repeatedSeconds(joined).get(seam.time).size() == 3);
            rejected(() -> LeaderHistory.joinBaseline(retained, List.of(older, seam), newest.time));
        });
        test("接受做多做空的内联文案并保留单向仓位的原始买卖方向", () -> {
            String record = "11-18, 23:00:00\n做多\n以均价为660USDT 买入做多MUUSDT永续合约，成交数量为 1MU。";
            ok(webpage(record).get(0).action == BUY);
            ok(webpage(record.replace("做多", "做空").replace("买入", "卖出")).get(0).action == SELL);
            ok(webpage(record.replace("\n做多\n", "\n开多\n")).get(0).action == OPEN_LONG);
            rejected(() -> webpage(record.replace("买入做多", "买入做空")));
        });
        test("基线持续展开到官网末尾，短页及多于四页都可以完成", () -> {
            HistoryPagination paging = new HistoryPagination(true);
            ok(paging.observe(10, 0, true, true, true, false, false, 1000) == HistoryPagination.Step.CLICK);
            paging.clicked(1000);
            ok(paging.observe(10, 0, true, false, false, false, true, 1350) == HistoryPagination.Step.WAIT);
            for (int i = 2; i <= 4; i++) {
                long at = i * 1300L - 700;
                ok(paging.observe(i * 10, 10, true, true, true, false, false, at) == HistoryPagination.Step.WAIT);
                ok(paging.observe(i * 10, 0, true, true, true, false, false, at + 650) == HistoryPagination.Step.CLICK);
                paging.clicked(at + 650);
            }
            ok(paging.observe(47, 7, true, false, false, false, false, 5800) == HistoryPagination.Step.WAIT);
            ok(paging.observe(47, 0, true, false, false, false, false, 7050) == HistoryPagination.Step.END);
            ok(paging.report(7050, 47, true).contains("5 页 · 已读到末尾 · 47 笔 · 4 次点击"));
            HistoryPagination single = new HistoryPagination(true);
            ok(single.observe(3, 0, true, false, false, false, false, 1000) == HistoryPagination.Step.WAIT);
            ok(single.observe(3, 0, true, false, false, false, false, 2300) == HistoryPagination.Step.END);
            ok(single.report(2300, 3, true).contains("1 页 · 已读到末尾 · 3 笔"));
        });
        test("加载失败不能被误认作官网末尾，日常找到档案重合即结束", () -> {
            HistoryPagination paging = new HistoryPagination(true);
            ok(paging.observe(10, 0, true, true, true, false, false, 1000) == HistoryPagination.Step.CLICK);
            paging.clicked(1000);
            long[] busy = {1250, 3300, 6800};
            long[] retry = {3100, 6650, 13150};
            for (int i = 0; i < retry.length; i++) {
                long click = retry[i];
                ok(paging.observe(10, 0, true, true, false, true, false, busy[i]) == HistoryPagination.Step.WAIT);
                ok(paging.observe(10, 0, true, true, true, true, false, busy[i] + 350) == HistoryPagination.Step.WAIT);
                ok(paging.observe(10, 0, true, true, true, false, false, click - 1) == HistoryPagination.Step.WAIT);
                ok(paging.observe(10, 0, true, true, true, false, false, click) == HistoryPagination.Step.CLICK);
                paging.clicked(click);
            }
            ok(paging.observe(10, 0, true, false, false, false, true, 13300) == HistoryPagination.Step.WAIT);
            ok(paging.observe(10, 0, true, false, false, true, false, 13700) == HistoryPagination.Step.WAIT);
            ok(paging.observe(10, 0, true, false, false, true, false, 31300) == HistoryPagination.Step.INCOMPLETE);
            ok(paging.report(31300, 10, false).contains("1 页 · 分页未完成 · 10 笔 · 4 次点击"));
            HistoryPagination lostButton = new HistoryPagination(true);
            lostButton.observe(10, 0, true, true, true, false, false, 1000);
            lostButton.clicked(1000);
            ok(lostButton.observe(10, 0, true, false, false, false, false, 8100) == HistoryPagination.Step.WAIT);
            ok(lostButton.observe(10, 0, true, false, false, false, false, 12000) == HistoryPagination.Step.WAIT);
            HistoryPagination ordinary = new HistoryPagination(false);
            ok(ordinary.observe(10, 0, false, true, true, false, false, 1000) == HistoryPagination.Step.CLICK);
            ordinary.clicked(1000);
            ok(ordinary.observe(20, 10, false, true, true, false, false, 2000) == HistoryPagination.Step.WAIT);
            ok(ordinary.observe(20, 0, false, true, true, false, false, 2650) == HistoryPagination.Step.CLICK);
            ordinary.clicked(2650);
            ok(ordinary.observe(30, 10, true, true, true, false, false, 3000) == HistoryPagination.Step.END);
            ok(new HistoryPagination(false).observe(10, 0, true, true, true, false, false, 1000)
                    == HistoryPagination.Step.END);
        });
        test("首次建档繁忙后延长点击间隔，按钮持续消失后读到末尾", () -> {
            HistoryPagination paging = new HistoryPagination(true, true);
            ok(paging.observe(40, 0, true, true, true, false, false, 1000) == HistoryPagination.Step.CLICK);
            paging.clicked(1000);
            ok(paging.observe(40, 0, true, true, false, true, false, 1700) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 5699) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 5700) == HistoryPagination.Step.CLICK);
            paging.clicked(5700);
            ok(paging.observe(40, 0, true, true, false, true, false, 6400) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 14399) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 14400) == HistoryPagination.Step.CLICK);
            paging.clicked(14400);
            ok(paging.observe(40, 0, true, false, false, false, false, 21399) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, false, false, false, false, 21400) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, false, false, false, false, 22600) == HistoryPagination.Step.END);
        });
        test("正常翻页保持间隔，繁忙消失后再点一次即可续读", () -> {
            HistoryPagination paging = new HistoryPagination(true);
            ok(paging.observe(30, 0, true, true, true, false, false, 1000) == HistoryPagination.Step.CLICK);
            paging.clicked(1000);
            ok(paging.observe(40, 10, true, true, true, false, false, 1400) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 2199) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 2200) == HistoryPagination.Step.CLICK);
            paging.clicked(2200);
            ok(paging.observe(40, 0, true, true, true, true, false, 2550) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, true, false, 2900) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 4399) == HistoryPagination.Step.WAIT);
            ok(paging.observe(40, 0, true, true, true, false, false, 4400) == HistoryPagination.Step.CLICK);
            paging.clicked(4400);
            ok(paging.observe(50, 10, true, false, false, false, false, 4750) == HistoryPagination.Step.WAIT);
            ok(paging.observe(50, 0, true, false, false, false, false, 5950) == HistoryPagination.Step.END);
        });
        test("凭证以随机 IV 加密，解密验证交易环境和完整性", () -> {
            javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance("AES");
            generator.init(256);
            javax.crypto.SecretKey key = generator.generateKey();
            Secrets value = new Secrets("fixtureApiKey", "fixtureApiSecret");
            String a = Secrets.seal(value, FuturesApi.LIVE, key), b = Secrets.seal(value, FuturesApi.LIVE, key);
            ok(!a.equals(b) && !a.contains(value.key) && !a.contains(value.secret));
            Secrets decoded = Secrets.open(a, FuturesApi.LIVE, key);
            ok(decoded.key.equals(value.key) && decoded.secret.equals(value.secret));
            try {
                Secrets.open(a, FuturesApi.DEMO, key);
                throw new AssertionError("密文可以跨交易环境读取");
            } catch (javax.crypto.AEADBadTagException expected) {}
            String[] parts = a.split("\\.");
            byte[] bytes = java.util.Base64.getDecoder().decode(parts[1]);
            bytes[0] ^= 1;
            try {
                Secrets.open(parts[0] + "." + java.util.Base64.getEncoder().encodeToString(bytes), FuturesApi.LIVE, key);
                throw new AssertionError("篡改密文仍能读取");
            } catch (javax.crypto.AEADBadTagException expected) {}
        });
        test("较早页面补全同秒不同方向一笔，已核对重叠区保留", () -> {
            Rules.Signal oldest = row("BTCUSDT", OPEN_LONG, now, "100", "1");
            Rules.Signal sameTime = row("ETHUSDT", OPEN_LONG, now, "10", "1");
            Rules.Signal end = row("BTCUSDT", OPEN_LONG, now + 2000, "102", "1");
            List<Rules.Signal> before = List.of(oldest, end);
            ok(LeaderHistory.newRows(before, List.of(sameTime, oldest, end), true).isEmpty());
            rejected(() -> LeaderHistory.newRows(before, List.of(sameTime, end), true));
            Rules.Signal older = row("BTCUSDT", OPEN_LONG, now - 1000, "99", "1");
            ok(LeaderHistory.newRows(List.of(older, sameTime, oldest, end), before).isEmpty());
            ok(LeaderHistory.merge(before, List.of(older, sameTime, oldest, end), true, false).prepended == 2);
            ok(LeaderHistory.merge(before, List.of(oldest, end,
                    row("BTCUSDT", OPEN_LONG, now + 3000, "103", "1")), true, false).prepended == 0);
        });
        test("官网同一秒的开、加、平按页面次序回放并保留重复成交", () -> {
            String stamp = "11-18, 23:14:58\n";
            String firstOpen = stamp + "开多\n以均价为100USDT 买入开多MUUSDT永续合约，成交数量为 1MU。\n";
            String close = stamp + "平多\n以均价为110USDT 卖出平多MUUSDT永续合约，成交数量为 2MU。\n";
            List<Rules.Signal> rows = webpage(close + firstOpen + firstOpen);
            ok(rows.size() == 3 && rows.get(0).action == OPEN_LONG && rows.get(1).action == OPEN_LONG
                    && rows.get(2).action == CLOSE_LONG);
            ok(rows.get(0).fingerprint.equals(rows.get(1).fingerprint) && !rows.get(0).id.equals(rows.get(1).id));
            Map<String, LeaderHistory.Holding> holdings = new java.util.LinkedHashMap<>();
            for (Rules.Signal row : rows) LeaderHistory.apply(holdings, row);
            ok(!holdings.containsKey(MU + "|LONG"));
            List<Rules.Signal> restored = LocalData.readSignals(LocalData.signalJson(rows));
            ok(restored.get(1).id.equals(rows.get(1).id));
        });
        test("同秒分页截断可衔接，真正缺少中间记录或重排仍会暂停", () -> {
            Rules.Signal prior = row(MU, OPEN_LONG, now - 1000, "95", "1");
            Rules.Signal a = row(MU, OPEN_LONG, now, "100", "1");
            Rules.Signal b = row(MU, CLOSE_LONG, now, "101", "1");
            Rules.Signal c = row(MU, OPEN_LONG, now, "102", "1");
            Rules.Signal next = row(MU, OPEN_LONG, now + 1000, "103", "1");
            List<Rules.Signal> previous = List.of(prior, a, b, c);
            ok(LeaderHistory.newRows(previous, List.of(b, c, next)).get(0).id.equals(next.id));
            rejected(() -> LeaderHistory.newRows(previous, List.of(prior, a, c, next)));
            rejected(() -> LeaderHistory.newRows(previous, List.of(prior, b, a, c, next)));
            Rules.Signal eth = row("ETHUSDT", OPEN_LONG, now, "10", "1");
            LeaderHistory.Merge legacy = LeaderHistory.merge(List.of(prior, eth, a),
                    List.of(prior, a, eth, next), false, true);
            ok(legacy.added.size() == 1 && legacy.added.get(0).id.equals(next.id));
            LeaderHistory.Merge clippedLegacy = LeaderHistory.merge(List.of(prior, eth, a),
                    List.of(eth, next), false, true);
            ok(clippedLegacy.added.size() == 1 && clippedLegacy.added.get(0).id.equals(next.id));
            rejected(() -> LeaderHistory.merge(List.of(prior, eth, a), List.of(eth, b, a, next), false, true));
        });
        test("同秒同价同量跨分页时原成交标识保留，新出现一次只新增一次", () -> {
            Rules.Signal a = row(MU, OPEN_LONG, now, "100", "1");
            Rules.Signal repeat = a.withOccurrence(1);
            LeaderHistory.Merge expanded = LeaderHistory.merge(List.of(a), List.of(a, repeat), true, false);
            ok(expanded.rows.get(1).id.equals(a.id) && expanded.added.isEmpty() && expanded.prepended == 1);
            ok(!expanded.rows.get(0).id.equals(a.id));
            LeaderHistory.Merge later = LeaderHistory.merge(expanded.rows, List.of(a, repeat, a.withOccurrence(2)),
                    false, false);
            ok(later.added.size() == 1 && later.added.get(0).occurrence == 2);
            ok(later.rows.get(0).id.equals(expanded.rows.get(0).id));
            ok(LeaderHistory.newRows(later.rows, List.of(a, repeat, a.withOccurrence(2))).isEmpty());
            Rules.Signal tail = row(MU, CLOSE_LONG, now, "99", "1");
            LeaderHistory.Merge liveExpansion = LeaderHistory.merge(List.of(a), List.of(a, repeat, tail), true, false);
            ok(liveExpansion.prepended == 1 && liveExpansion.added.size() == 1 && liveExpansion.added.get(0).id.equals(tail.id));
        });
        test("官网第三页补进同秒同方向成交后继续核对第四页", () -> {
            HistoryPagination paging = new HistoryPagination(true);
            StringBuilder page = new StringBuilder();
            List<Rules.Signal> previous = List.of();
            for (int index = 39; index >= 0; index--) {
                int second = index >= 18 && index <= 20 ? 18 : index;
                page.append(String.format(java.util.Locale.ROOT, "11-18, 23:14:%02d\n", second));
                if (index == 20) page.append("平多\n以均价为101USDT 卖出平多MUUSDT永续合约，成交数量为 2MU。\n");
                else page.append("开多\n以均价为100USDT 买入开多MUUSDT永续合约，成交数量为 1MU。\n");
                if (index % 10 != 0) continue;
                LeaderHistory.Merge merge = LeaderHistory.merge(previous, webpage(page.toString()), true, false);
                int number = (40 - index) / 10;
                ok(merge.rows.size() == number * 10);
                if (number > 1) ok(merge.added.isEmpty() && merge.prepended == 10);
                long seen = number * 1300L;
                HistoryPagination.Step step = paging.observe(merge.rows.size(), merge.prepended, true,
                        number < 4, true, false, false, seen);
                if (number > 1) {
                    ok(step == HistoryPagination.Step.WAIT);
                    seen += number < 4 ? 650 : 1250;
                    step = paging.observe(merge.rows.size(), 0, true, number < 4, true, false, false, seen);
                }
                ok(step == (number < 4 ? HistoryPagination.Step.CLICK : HistoryPagination.Step.END));
                if (number < 4) paging.clicked(seen);
                previous = merge.rows;
            }
            ok(previous.get(18).fingerprint.equals(previous.get(19).fingerprint));
            ok(!previous.get(18).id.equals(previous.get(19).id) && previous.get(20).action == CLOSE_LONG);
        });
        test("持仓价格和保证金按显示精度舍入，小额保留有效数字", () -> {
            ok(TradeNotice.positionMoney(d("127.882365")).equals("127.88"));
            ok(TradeNotice.positionMoney(d("-0.001255")).equals("-0.0013"));
            ok(TradeNotice.positionPrice(d("2480.125")).equals("2480.13"));
            ok(TradeNotice.positionPrice(d("0.000012626439")).equals("0.00001263"));
            ok(TradeNotice.positionQuantity(d("0.1234567891")).equals("0.12345679"));
        });
        test("钱包余额是定比基数", () -> {
            eq(ratio().rule(MU).orderMargin(d("1000")), d("50"));
            eq(ratio().rule(MU).orderMargin(d("800")), d("40"));
            eq(fixed().rule(MU).orderMargin(d("800")), d("20"));
        });
        test("多空单向价差与价格步长", () -> {
            BigDecimal plus = Rules.entryLimit(OPEN_LONG, d("100.01"), d("0.3"), d("0.01"));
            BigDecimal minus = Rules.entryLimit(OPEN_SHORT, d("100.01"), d("0.3"), d("0.01"));
            eq(plus, d("100.31"));
            eq(minus, d("99.71"));
            ok(Rules.acceptable(OPEN_LONG, plus, quote("100.31")));
            ok(!Rules.acceptable(OPEN_LONG, plus, quote("100.32")));
            ok(!Rules.acceptable(OPEN_SHORT, minus, quote("99.70")));
            ok(Rules.acceptable(OPEN_SHORT, minus, quote("110")));
        });
        test("币安公开网页形态、千分位与年月日", () -> {
            List<Rules.Signal> rows = webpage(PAGE);
            ok(rows.size() == 4);
            eq(rows.get(0).price, d("63732.10"));
            eq(rows.get(1).quantity, d("280.98"));
            eq(rows.get(2).quantity, d("251.94"));
            eq(rows.get(3).quantity, d("532.92"));
            eq(rows.get(2).turnover, d("180055.99439"));
            eq(rows.get(3).turnover, d("373791.79329"));
            ok(rows.get(2).time - rows.get(1).time == 16000);
            ok(rows.get(2).time == Instant.parse("2026-11-18T15:14:58Z").toEpochMilli());
        });
        test("公开成交额保持原精度，复制账户使用 USDT 保证金余额", () -> {
            Rules.Signal first = webpage(PAGE).get(3);
            Rules.Signal enriched = first.withLeaderMargin(d("50000"));
            eq(enriched.exposureRatio(), d("7.4758358658"));
            ok(enriched.id.equals(first.id));
            ok(first.exposureRatio() == null);
            ok(LeaderHistory.newRows(List.of(first), List.of(enriched)).isEmpty());
            Rules.Signal restored = LocalData.readSignals(LocalData.signalJson(List.of(enriched))).get(0);
            eq(restored.leaderMargin, d("50000"));
            eq(restored.turnover, first.turnover);
            JSONObject account = new JSONObject("{\"totalMarginBalance\":\"8888\",\"assets\":["
                    + "{\"asset\":\"USDC\",\"marginBalance\":\"700\",\"availableBalance\":\"555\"},"
                    + "{\"asset\":\"USDT\",\"walletBalance\":\"1000\",\"marginBalance\":\"1240\",\"availableBalance\":\"880\"}]}");
            FuturesApi.Wallet wallet = FuturesApi.readCopyWallet(account);
            eq(wallet.balance, d("1240"));
            eq(wallet.available, d("880"));
            ok(!FuturesApi.permitted(FuturesApi.LIVE_READ, "GET", "/fapi/v3/account", true));
            ok(FuturesApi.permitted(FuturesApi.LIVE, "GET", "/fapi/v3/account", true));
        });
        test("官网平仓盈亏带符号且不改变去重标识", () -> {
            List<Rules.Signal> rows = webpage(PAGE);
            ok(rows.get(0).realizedPnl == null && rows.get(2).realizedPnl == null);
            eq(rows.get(3).realizedPnl, d("-6316.93560"));
            List<Rules.Signal> revised = webpage(PAGE.replace("-6,316.93560", "＋6,316.93560"));
            eq(revised.get(3).realizedPnl, d("6316.93560"));
            ok(LeaderHistory.newRows(rows, revised).isEmpty());
            List<Rules.Signal> restored = LocalData.readSignals(LocalData.signalJson(rows));
            eq(restored.get(3).realizedPnl, d("-6316.93560"));
            ok(restored.get(3).id.equals(rows.get(3).id));
        });
        test("按成本和数量对平的亏损仓位清除成本，后续同品种开仓重新计价", () -> {
            List<Rules.Signal> rows = webpage(PAGE);
            BigDecimal wanted = d("713.2566405654507243113412895");
            ok(LeaderHistory.closeEntry(rows.get(3)).subtract(wanted).abs().compareTo(d("0.0000001")) < 0);
            Rules.Signal shortClose = new Rules.Signal("BTCUSDT", CLOSE_SHORT, now, d("80"), d("2"), d("40"));
            eq(LeaderHistory.closeEntry(shortClose), d("100"));
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            for (Rules.Signal signal : rows) LeaderHistory.apply(positions, signal);
            ok(!positions.containsKey(MU + "|LONG"));
            long next = rows.get(3).time + 1000;
            LeaderHistory.apply(positions, row(MU, OPEN_LONG, next, "660", "2"));
            LeaderHistory.apply(positions, new Rules.Signal(MU, CLOSE_LONG, next + 1000, d("610"), d("1"), d("-55")));
            LeaderHistory.Holding remaining = positions.get(MU + "|LONG");
            eq(remaining.displayQuantity(), d("1"));
            ok(remaining.inferredQuantity == null);
        });
        test("部分减仓盈亏有差异时保留可见均价，同方向归零后清除全部成本", () -> {
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            LeaderHistory.apply(positions, row(MU, OPEN_LONG, now, "100", "3"));
            LeaderHistory.apply(positions, row(MU, OPEN_SHORT, now, "120", "1"));
            LeaderHistory.apply(positions, new Rules.Signal(MU, CLOSE_LONG, now + 1000, d("90"), d("1"), d("-15")));
            LeaderHistory.Holding remaining = positions.get(MU + "|LONG");
            eq(remaining.displayQuantity(), d("2"));
            eq(remaining.entryPrice, d("100"));
            eq(remaining.closeCost, d("105"));
            eq(remaining.unrealized(d("90"), true), d("-20"));
            ok(remaining.uncertainBasis);
            LeaderHistory.apply(positions, new Rules.Signal(MU, CLOSE_LONG, now + 2000, d("90"), d("2"), d("-30")));
            ok(!positions.containsKey(MU + "|LONG"));
            eq(positions.get(MU + "|SHORT").displayQuantity(), d("1"));
        });
        test("BTC 空单两次开仓合计 66.554，官网同量平空后移除持仓", () -> {
            String btc = "BTCUSDT";
            String leg = btc + "|SHORT";
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            LeaderHistory.apply(positions, new Rules.Signal(btc, OPEN_SHORT, now, d("60747.14"),
                    d("33.303"), null, d("2023061.91"), null));
            LeaderHistory.apply(positions, new Rules.Signal(btc, OPEN_SHORT, now + 1000, d("60601.48"),
                    d("33.251"), null, d("2015059.92"), null));
            LeaderHistory.Holding before = positions.get(leg);
            eq(before.displayQuantity(), d("66.554"));
            near(before.entryPrice, "60674.3671304505");
            Rules.Signal close = new Rules.Signal(btc, CLOSE_SHORT, now + 2000, d("59819.26"),
                    d("66.554"), d("36981.39"), d("3981211.21"), null);
            near(LeaderHistory.closeEntry(close), "60374.92261923");
            LeaderHistory.apply(positions, close);
            ok(positions.isEmpty());
            ok(LeaderRisk.estimate(positions, d("10000"), Map.of(), Map.of()).prices.isEmpty());
        });
        test("成交额允许报价舍入，明显冲突时按成交价算持仓成本", () -> {
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            LeaderHistory.apply(positions, new Rules.Signal(MU, OPEN_LONG, now, d("100.01"), d("2"),
                    null, d("200.025"), null));
            eq(positions.get(MU + "|LONG").entryPrice, d("100.0125"));
            LeaderHistory.apply(positions, new Rules.Signal(MU, OPEN_LONG, now + 1000, d("110"), d("2"),
                    null, d("2000"), null));
            eq(positions.get(MU + "|LONG").entryPrice, d("105.00625"));
            Rules.Signal close = new Rules.Signal(MU, CLOSE_LONG, now + 2000, d("110"), d("1"),
                    d("5"), d("1000"), null);
            ok(LeaderHistory.closeEntry(close) == null);
            LeaderHistory.apply(positions, close);
            eq(positions.get(MU + "|LONG").entryPrice, d("105.00625"));
            ok(positions.get(MU + "|LONG").uncertainBasis);
        });
        test("独立复核只归并重复次数，保留同秒的不同成交和确认的相同多笔", () -> {
            Rules.Signal trade = new Rules.Signal(MU, OPEN_LONG, now, d("659.87"), d("169.61"),
                    null, d("111920.5507"), null);
            Rules.Signal distinct = row(MU, OPEN_LONG, now, "661.57", "175.75");
            List<Rules.Signal> raw = List.of(trade, trade.withOccurrence(1), distinct);
            List<Rules.Signal> once = List.of(trade, distinct);
            ok(TradeReview.repeatedSeconds(raw).get(now).size() == 3);
            ok(TradeReview.repeatedSeconds(once).isEmpty());
            ok(TradeReview.compatible(raw, once) && TradeReview.compatible(raw, raw));
            ok(!TradeReview.compatible(raw, List.of(trade)) && !TradeReview.compatible(raw, List.of(distinct, trade)));
            ok(TradeReview.hasAdditionalDetails(List.of(trade, trade.withOccurrence(1)), once));
            ok(!TradeReview.hasAdditionalDetails(raw, List.of(distinct, trade)));
            ok(TradeReview.replace(raw, Map.of(now, once), null).size() == 2);
            ok(TradeReview.replace(raw, Map.of(now, raw), null).size() == 3);
        });
        test("同向两次平仓和中间加仓推断超出可见的余仓，下一笔继续更新", () -> {
            String btc = "BTCUSDT";
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            LeaderHistory.apply(positions, row(btc, OPEN_LONG, now, "95", "0.4"));
            LeaderHistory.apply(positions, new Rules.Signal(btc, CLOSE_LONG, now + 1000, d("105"), d("0.2"), d("1")));
            LeaderHistory.Holding afterFirst = positions.get(btc + "|LONG");
            eq(afterFirst.entryPrice, d("95"));
            eq(afterFirst.quantity, d("0.2"));
            ok(afterFirst.inferredQuantity == null && afterFirst.uncertainBasis);
            LeaderHistory.apply(positions, row(btc, OPEN_LONG, now + 2000, "120", "0.2"));
            LeaderHistory.apply(positions, new Rules.Signal(btc, CLOSE_LONG, now + 3000, d("108"), d("0.1"), d("0.3")));
            LeaderHistory.Holding inferred = positions.get(btc + "|LONG");
            eq(inferred.quantity, d("0.3"));
            eq(inferred.displayQuantity(), d("0.7"));
            eq(inferred.entryPrice, d("105"));
            eq(inferred.visibleEntryPrice, d("107.5"));
            ok(inferred.costFromClose && !inferred.uncertainBasis);
            positions.put(btc + "|LONG", LeaderHistory.Holding.read(inferred.json()));
            LeaderHistory.apply(positions, row(btc, OPEN_LONG, now + 4000, "113", "0.1"));
            eq(positions.get(btc + "|LONG").entryPrice, d("106"));
            eq(positions.get(btc + "|LONG").displayQuantity(), d("0.8"));
            LeaderHistory.apply(positions, new Rules.Signal(btc, CLOSE_LONG, now + 5000, d("110"), d("0.5"), d("2")));
            ok(positions.isEmpty());
            LeaderHistory.apply(positions, row(btc, OPEN_LONG, now + 6000, "114", "0.1"));
            LeaderHistory.Holding reopened = positions.get(btc + "|LONG");
            eq(reopened.displayQuantity(), d("0.1"));
            eq(reopened.entryPrice, d("114"));
            eq(reopened.unrealized(d("110"), true), d("-0.4"));
            eq(reopened.unrealized(d("110"), false), d("0.4"));
            ok(reopened.unrealized(null, true) == null);
        });
        test("成本变化接近显示精度时保留可见数量，不给出不稳定的反推结果", () -> {
            Map<String, LeaderHistory.Holding> positions = new java.util.HashMap<>();
            LeaderHistory.apply(positions, row(MU, OPEN_LONG, now, "100", "1"));
            LeaderHistory.apply(positions, new Rules.Signal(MU, CLOSE_LONG, now + 1000, d("100"), d("0.5"), d("0")));
            LeaderHistory.apply(positions, row(MU, OPEN_LONG, now + 2000, "104", "0.5"));
            LeaderHistory.apply(positions, new Rules.Signal(MU, CLOSE_LONG, now + 3000, d("100.01"), d("0.25"), d("0")));
            LeaderHistory.Holding holding = positions.get(MU + "|LONG");
            eq(holding.displayQuantity(), d("0.75"));
            eq(holding.entryPrice, d("102"));
            ok(holding.inferredQuantity == null && !holding.costFromClose && holding.uncertainBasis);
        });
        test("全仓估算使用其他标的的维持保证金及标记价，双向腿共用触发边界", () -> {
            LeaderRisk.Tier first = new LeaderRisk.Tier(d("5000"), d("0.01"), d("0"));
            LeaderRisk.Tier second = new LeaderRisk.Tier(d("5000"), d("0.02"), d("0"));
            Map<String, LeaderHistory.Holding> positions = Map.of("BTCUSDT|LONG", holding("2", "100"),
                    "ETHUSDT|SHORT", holding("3", "50"));
            Map<String, BigDecimal> marks = Map.of("BTCUSDT", d("100"), "ETHUSDT", d("50"));
            Map<String, List<LeaderRisk.Tier>> tiers = Map.of("BTCUSDT", List.of(first), "ETHUSDT", List.of(second));
            LeaderRisk.Result result = LeaderRisk.estimate(positions, d("40"), marks, tiers);
            ok(result.assumptions.isEmpty());
            near(result.prices.get("BTCUSDT").below, "82.32323232");
            near(result.prices.get("ETHUSDT").above, "61.43790850");
            result = LeaderRisk.estimate(positions, d("40"), Map.of("BTCUSDT", d("100")), Map.of("BTCUSDT", List.of(first)));
            ok(result.assumptions.containsAll(List.of(LeaderRisk.Assumption.MARK, LeaderRisk.Assumption.TIER)));
            near(result.prices.get("BTCUSDT").below, "81.18686869");
            ok(result.prices.get("ETHUSDT").above != null);
            result = LeaderRisk.estimate(Map.of("ETHUSDT|LONG", holding("1", "50"), "ETHUSDT|SHORT", holding("1", "50")),
                    d("5"), marks, tiers);
            ok(result.prices.get("ETHUSDT").below == null);
            near(result.prices.get("ETHUSDT").above, "125");
        });
        test("强平估算使用触发价对应的维持保证金档位，超过上限按末档延伸", () -> {
            List<LeaderRisk.Tier> tiers = List.of(new LeaderRisk.Tier(d("210"), d("0.01"), d("0")),
                    new LeaderRisk.Tier(d("5000"), d("0.02"), d("2.1")));
            Map<String, LeaderHistory.Holding> positions = Map.of("BTCUSDT|SHORT", holding("2", "100"));
            Map<String, BigDecimal> marks = Map.of("BTCUSDT", d("100"));
            LeaderRisk.Result result = LeaderRisk.estimate(positions, d("40"), marks, Map.of("BTCUSDT", tiers));
            near(result.prices.get("BTCUSDT").above, "118.67647059");
            result = LeaderRisk.estimate(positions, d("40"), marks, Map.of("BTCUSDT", List.of(tiers.get(0))));
            near(result.prices.get("BTCUSDT").above, "118.81188119");
            ok(result.assumptions.contains(LeaderRisk.Assumption.EXTENDED_TIER));
        });
        test("强平估算在维持保证金为零的区间内使用实际斜率", () -> {
            LeaderRisk.Tier tier = new LeaderRisk.Tier(d("1000"), d("0.1"), d("50"));
            Map<String, LeaderHistory.Holding> positions = Map.of("BTCUSDT|SHORT", holding("1", "100"));
            LeaderRisk.Result result = LeaderRisk.estimate(positions, d("20"), Map.of("BTCUSDT", d("100")),
                    Map.of("BTCUSDT", List.of(tier)));
            near(result.prices.get("BTCUSDT").above, "120");
            ok(result.prices.get("BTCUSDT").below == null);
        });
        test("缺少余额、标记价和档位时按持仓成本模拟，局部缺价不隐藏其他合约", () -> {
            Map<String, LeaderHistory.Holding> positions = Map.of("BTCUSDT|LONG", holding("2", "100"),
                    "ETHUSDT|LONG", new LeaderHistory.Holding(d("1"), null, 0));
            LeaderRisk.Result result = LeaderRisk.estimate(positions, null, Map.of(), Map.of());
            near(result.prices.get("BTCUSDT").below, "95.47738693");
            ok(result.prices.get("ETHUSDT") == null);
            ok(result.assumptions.containsAll(List.of(LeaderRisk.Assumption.MARGIN, LeaderRisk.Assumption.MARK,
                    LeaderRisk.Assumption.TIER, LeaderRisk.Assumption.MISSING_PRICE)));
            result = LeaderRisk.estimate(positions, null, Map.of("BTCUSDT", d("92")), Map.of());
            ok(result.atMaintenance);
            near(result.prices.get("BTCUSDT").below, "95.47738693");
            result = LeaderRisk.estimate(Map.of("BTCUSDT|SHORT", holding("2", "100")), null, Map.of(), Map.of());
            near(result.prices.get("BTCUSDT").above, "104.47761194");
        });
        test("用最近余额和持仓均价随加仓、减仓和归零更新强平估算", () -> {
            String key = "BTCUSDT|LONG";
            Map<String, LeaderHistory.Holding> positions = new java.util.LinkedHashMap<>();
            long now = END.atStartOfDay(ZONE).toInstant().toEpochMilli() - 5000;
            LeaderHistory.apply(positions, row("BTCUSDT", OPEN_LONG, now, "100", "2"));
            near(LeaderRisk.estimate(positions, d("10"), Map.of(), Map.of()).prices.get("BTCUSDT").below, "95.47738693");
            LeaderHistory.apply(positions, row("BTCUSDT", OPEN_LONG, now + 1000, "120", "1"));
            near(LeaderRisk.estimate(positions, d("10"), Map.of(), Map.of()).prices.get("BTCUSDT").below, "103.85259631");
            LeaderHistory.apply(positions, new Rules.Signal("BTCUSDT", CLOSE_LONG, now + 2000, d("108"), d("1"), d("2")));
            near(positions.get(key).entryPrice, "106.66666667");
            near(LeaderRisk.estimate(positions, d("10"), Map.of(), Map.of()).prices.get("BTCUSDT").below, "102.17755444");
            LeaderHistory.apply(positions, row("BTCUSDT", CLOSE_LONG, now + 3000, "106", "2"));
            ok(LeaderRisk.estimate(positions, d("10"), Map.of(), Map.of()).prices.isEmpty());
        });
        test("没有两个日期输入框时仍能核对可见成交", () -> {
            List<Rules.Signal> rows = WebRecords.parsePage(PAGE, new org.json.JSONArray(), ZONE, END);
            ok(rows.size() == 4);
            ok(rows.get(3).id.equals(webpage(PAGE).get(3).id));
        });
        test("空列表重选日期时保留已有成交锚点", () -> {
            LocalDate last = LocalDate.of(2026, 11, 20);
            long anchor = START.plusDays(1).atTime(10, 0).atZone(ZONE).toInstant().toEpochMilli();
            ok(last.minusDays(2).equals(WebRecords.recoveryStart(START, last, 0, ZONE)));
            ok(START.plusDays(1).equals(WebRecords.recoveryStart(START, last, anchor, ZONE)));
            ok(WebRecords.recoveryStart(last.minusDays(4), last, anchor, ZONE) == null);
        });
        test("不完整、修改和同秒多笔的网页记录", () -> {
            rejected(() -> webpage(PAGE.replace("532.92MU", "532.92BTC")));
            rejected(() -> webpage(PAGE.replace("买入开多", "卖出开多")));
            List<Rules.Signal> tied = webpage(PAGE.replace("23:14:42", "23:14:58"));
            eq(tied.get(1).quantity, d("280.98"));
            eq(tied.get(2).quantity, d("251.94"));
            rejected(() -> webpage(PAGE.replace("均价为701.40320", "未知价格")));
        });
        test("网页快照重叠和缺页提示", () -> {
            List<Rules.Signal> rows = webpage(PAGE);
            ok(CaptureCheck.compare(rows, rows).contains("一致"));
            List<Rules.Signal> later = new ArrayList<>(rows);
            later.add(row(MU, OPEN_LONG, rows.get(3).time + 1000, "705", "1"));
            ok(CaptureCheck.compare(rows, later).contains("1 笔"));
            ok(CaptureCheck.compare(rows, later.subList(4,5)).contains("衔接不上"));
            later.set(3, row(MU, CLOSE_LONG, rows.get(3).time, "701.40", "500"));
            ok(CaptureCheck.compare(rows, later).contains("字段变化"));
        });
        test("重复快照和同分钟两笔开仓分别处理", () -> {
            PaperEngine book = paper();
            List<Rules.Signal> rows = ReplayRun.samples();
            book.apply(rows.subList(0, 2));
            book.apply(rows.subList(0, 2));
            ok(book.pendingCount() == 2);
            ok(check(book,"715").size() == 1);
            ok(book.pendingCount() == 1);
            ok(check(book,"713.50").size() == 1);
            ok(book.pendingCount() == 0);
        });
        test("补读发现已全平，取消这轮待跟", () -> {
            PaperEngine book = paper();
            book.apply(ReplayRun.samples());
            ok(check(book,"701.40").isEmpty());
            ok(book.pendingCount() == 0);
            eq(book.held(MU,true), d("0"));
        });
        test("减仓缩小目标，且平仓不做价差检查", () -> {
            PaperEngine book = paper();
            book.apply(Collections.singletonList(row(MU,OPEN_LONG,10,"100","10")));
            List<PaperEngine.Fill> fills = check(book,"100");
            eq(fills.get(0).quantity,d("1"));
            book.apply(Collections.singletonList(row(MU,CLOSE_LONG,11,"100","3")));
            eq(book.check(Map.of(MU,quote("40")),d("0"),false,now).get(0).quantity,d("0.3"));
            eq(book.held(MU,true),d("0.7"));
        });
        test("部分成交后仍有剩余，并沿用保证金预算", () -> {
            PaperEngine book = paper();
            book.apply(Collections.singletonList(row(MU,OPEN_LONG,10,"100","10")));
            Rules.Quote shallow = new Rules.Quote(d("100"),d("100"),d("0.4"),d("0.4"));
            eq(book.check(Map.of(MU,shallow),d("0"),true,now).get(0).quantity,d("0.4"));
            eq(check(book,"100").get(0).quantity,d("0.6"));
            ok(check(book,"100").isEmpty());
        });
        test("开仓信号不因检测时间过去而失效", () -> {
            PaperEngine book = paper();
            book.apply(Collections.singletonList(row(MU,OPEN_SHORT,10,"100","10")));
            ok(book.check(Map.of(MU,quote("98")),d("0.3"),true,now).isEmpty());
            ok(book.check(Map.of(MU,quote("102")),d("0.3"),true,now+999999999).size() == 1);
        });
        test("合约名单、资金不足和历史缺口", () -> {
            PaperEngine book = new PaperEngine(fixed(),d("10"));
            book.apply(Arrays.asList(row("BTCUSDT",OPEN_LONG,1,"100","1"),row(MU,OPEN_LONG,2,"100","1")));
            ok(book.check(Map.of(MU,quote("100"),"BTCUSDT",quote("100")),d("1"),true,now).isEmpty());
            book.apply(Collections.singletonList(row(MU,CLOSE_LONG,3,"100","2")));
            ok(book.summary().contains("历史缺口"));
        });
        test("全平后同方向新周期先处理旧仓", () -> {
            PaperEngine book = paper();
            book.apply(Collections.singletonList(row(MU,OPEN_LONG,1,"100","4")));
            check(book,"100");
            book.apply(Arrays.asList(row(MU,CLOSE_LONG,2,"100","4"),row(MU,OPEN_LONG,3,"100","5")));
            List<PaperEngine.Fill> output = check(book,"100");
            ok(output.size() == 2);
            ok(output.get(0).action.startsWith("平多"));
            ok(output.get(1).action.startsWith("开多"));
        });
        test("状态保存、恢复及成交提示一致", () -> {
            ReplayRun run = ReplayRun.create(fixed(),ReplayRun.samples(),true);
            for(int i=0;i<3;i++) run.advance(d("0.3"),now+i*3000);
            ReplayRun after = ReplayRun.restore(new JSONObject(run.data.toString()));
            ok(after.step()==3 && run.logs.equals(after.logs));
            eq(run.book.balance(),after.book.balance());
            eq(run.book.held(MU,true),after.book.held(MU,true));
            List<PaperEngine.Fill> fills = after.advance(d("0.3"),now+9000);
            ok(fills.size()==1 && after.finished());
            eq(fills.get(0).position,d("0"));
            ok(fills.get(0).notice().contains(MU) && fills.get(0).notice().contains("持仓 0 MU"));
        });
        test("实盘请求限于签名查询，账户修改隔离到测试网", () -> {
            ok(FuturesApi.permitted(false,"GET","/fapi/v3/balance",true));
            ok(!FuturesApi.permitted(false,"POST","/fapi/v1/order",true));
            ok(!FuturesApi.permitted(false,"POST","/fapi/v1/leverage",true));
            ok(FuturesApi.permitted(true,"POST","/fapi/v1/order",true));
            ok(!FuturesApi.permitted(true,"POST","/sapi/v1/capital/withdraw/apply",true));
        });
        test("HMAC 签名与限流处理", () -> {
            ok("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
                    .equals(FuturesApi.sign("key","The quick brown fox jumps over the lazy dog")));
            ok(FuturesApi.retryMillis("17",429)==17000);
            ok(FuturesApi.retryMillis(null,418)>=60000);
            ok(!new FuturesApi.Failure(503,-1000).definitivelyRejected());
            ok(!new FuturesApi.Failure(400,-2013).definitivelyRejected());
            ok(new FuturesApi.Failure(400,-2019).definitivelyRejected());
        });
        System.out.println(count + " Android logic checks passed");
    }

    private static void test(String label, Test body) throws Exception {
        body.run();
        count++;
        System.out.println("PASS · " + label);
    }
    private static void rejected(Test body) throws Exception {
        try { body.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid input to be rejected");
    }
    private static void ok(boolean passed) { if (!passed) throw new AssertionError("Unexpected state"); }
    private static void eq(BigDecimal got, BigDecimal expected) {
        if (got.compareTo(expected) != 0) throw new AssertionError("Expected " + expected + ", got " + got);
    }
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static LeaderHistory.Holding holding(String quantity, String entry) {
        return new LeaderHistory.Holding(d(quantity), d(entry), 1);
    }
    private static void near(BigDecimal value, String expected) {
        ok(value != null && value.subtract(d(expected)).abs().compareTo(d("0.000001")) < 0);
    }
    private static Rules.Quote quote(String price) { return new Rules.Quote(d(price),d(price),d("1000"),d("1000")); }
    private static Rules.Signal row(String symbol, Rules.Action action, long time, String price, String amount) {
        return new Rules.Signal(symbol,action,time,d(price),d(amount));
    }
    private static Rules.Config fixed() { return new Rules.Config(PORT,Collections.singleton(MU),false,d("20"),5,d("0.3")); }
    private static Rules.Config ratio() { return new Rules.Config(PORT,Collections.singleton(MU),true,d("5"),5,d("0.3")); }
    private static PaperEngine paper() { return new PaperEngine(fixed(),d("1000")); }
    private static List<PaperEngine.Fill> check(PaperEngine book, String price) {
        return book.check(Map.of(MU,quote(price)),d("0.3"),true,now);
    }
    private static List<Rules.Signal> webpage(String text) { return WebRecords.parse(text,START,END,ZONE); }
}
