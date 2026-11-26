package com.localcopy.terminal;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class V3ArchiveTest {
    private static final String ID = LocalData.DEFAULT_PORTFOLIO;
    private static final String OTHER = "77777777";
    private static final long START = 1795050000000L;

    @Before public void dismissCurrentVersionIntroForUnrelatedTests() {
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive.open(app).close();
        LeaderBalances.open(app).close();
        app.deleteDatabase("leader-history.db");
        app.deleteDatabase("leader-balances.db");
        new LocalData(app).prefs.edit().putString("last_intro_version", BuildConfig.VERSION_NAME)
                .putBoolean("usage_notice_seen", true).commit();
    }

    @Test public void changingAwayAndBackRequiresANewFollowChoiceAndKeepsThePortfolioHistory() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive archive = LeaderArchive.open(app);
        Rules.Config original = new Rules.Config("78100801", data.config().rules);
        Rules.Config alternate = new Rules.Config("78100802", original.rules);
        data.saveConfig(original);
        long priorSelection = data.portfolioSelection();
        archive.capture(original.portfolio, "旧带单员", opens(0, 1), START, true, "完成");
        archive.capture(alternate.portfolio, "另一位带单员", opens(1, 2), START, true, "完成");
        archive.saveName("78100803", "仅验证过的组合");
        FollowBook completed = new FollowBook(original, "test");
        completed.portfolioSelection = priorSelection;
        completed.initialChoiceDone = true;
        data.save(AutoFollowService.file(AutoFollowService.DEMO_WEB), completed.json());
        assertTrue(AutoFollowService.savedFollowBook(data, AutoFollowService.DEMO_WEB, original).initialChoiceDone);
        AutoFollowService.saveRules(data, archive, alternate);
        AutoFollowService.saveRules(data, archive, original);
        assertEquals(priorSelection + 2, data.portfolioSelection());
        assertNull(AutoFollowService.savedFollowBook(data, AutoFollowService.DEMO_WEB, original));
        assertEquals(1, archive.count(original.portfolio));
        assertTrue(archive.followedPortfolios().stream().anyMatch(item -> item.id.equals(alternate.portfolio)));
        assertFalse(archive.followedPortfolios().stream().anyMatch(item -> item.id.equals("78100803")));
        assertTrue(archive.followedPortfolios().stream().anyMatch(item -> item.toString().contains("旧带单员")
                && item.id.equals(original.portfolio)));
    }

    @Test public void appendACompletedDisjointSwitchBaselineWithoutUsingStalePositions() throws Exception {
        String portfolio = "23324455";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal oldLong = signal(0, Rules.Action.OPEN_LONG, "2");
        Rules.Signal newShort = signal(90, Rules.Action.OPEN_SHORT, "0.3");
        db.capture(portfolio, "测试带单员", List.of(oldLong), START, true, "原基线");
        try {
            db.capture(portfolio, "", List.of(newShort), START + 1000, false, "补页中", true, 1);
            fail("未确认完整分页");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("完整展开"));
        }
        LeaderArchive.Update disjoint = db.capture(portfolio, "", List.of(newShort), START + 2000, true, "完整", true, 1);
        assertTrue(disjoint.baseline);
        assertTrue(disjoint.continuous);
        assertEquals(1, disjoint.added);
        assertEquals(2, db.count(portfolio));
        assertEquals(1, db.verifiedSelection(portfolio));
        assertFalse(disjoint.state.estimate.containsKey("BTCUSDT|LONG"));
        assertEquals(new BigDecimal("0.3"), db.holdingsAt(portfolio, disjoint.state.head).get("BTCUSDT|SHORT").quantity);
        assertEquals(oldLong.id, db.after(portfolio, 0, 2).get(0).signal.id);
        assertEquals("新跟单基线", db.after(portfolio, 1, 2).get(0).source());
        assertFalse(db.hasConfirmedOverlap(portfolio, List.of(oldLong)));

        Rules.Signal next = signal(91, Rules.Action.OPEN_SHORT, "0.2");
        LeaderArchive.Update connected = db.capture(portfolio, "", List.of(newShort, next), START + 3000,
                false, "", false, 1);
        assertEquals(1, connected.added);
        assertEquals(new BigDecimal("0.5"), connected.state.estimate.get("BTCUSDT|SHORT"));
        assertFalse(db.capture(portfolio, "", opens(95, 96), START + 5000, true, "完整", true, 1).continuous);
    }

    @Test public void switchedEmptyBaselinePreservesPreviousOrdinalsAndCanStartRecordingAgain() throws Exception {
        String portfolio = "23324456";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        db.capture(portfolio, "测试带单员", opens(0, 1), START, true, "原基线");
        LeaderArchive.Update next = db.capture(portfolio, "", List.of(), START + 1000, true, "无成交", true, 2);
        assertTrue(next.continuous);
        assertTrue(next.state.estimate.isEmpty());
        assertEquals(1, next.state.head);
        assertEquals(1, db.count(portfolio));
        assertEquals(0, db.capture(portfolio, "", List.of(), START + 2000, false, "", false, 2).added);
        assertEquals(1, db.capture(portfolio, "", opens(10, 11), START + 3000, false, "", false, 2).added);
        assertEquals(2, db.count(portfolio));
    }

    @Test public void anOlderOverlapCanBeKeptWhenTheLastArchivedTradeHasRolledOut() throws Exception {
        String portfolio = "23324458";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> before = opens(0, 2);
        db.capture(portfolio, "测试带单员", before, START, true, "原基线");
        Rules.Signal newest = signal(4, Rules.Action.OPEN_SHORT, "0.3");
        LeaderArchive.Update switched = db.capture(portfolio, "", List.of(before.get(0), newest),
                START + 9000, true, "完整", true, 1);
        assertTrue(switched.continuous);
        assertEquals(1, switched.added);
        assertEquals(3, db.count(portfolio));
        assertEquals(List.of(newest.id), switched.state.window.stream().map(row -> row.id).toList());
    }

    @Test public void repairsTheV11EmptyWindowAgainstExistingTradeKeysOnUpgrade() throws Exception {
        String portfolio = "5075281354358777857";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> prior = opens(0, 4);
        archive.capture(portfolio, "熬鹰资本", prior, START, true, "早期基线");
        archive.saveAccountFill(portfolio, AutoFollowService.DEMO_WEB, "v11-original-fill", START + 1, "已确认历史");
        archive.capture(portfolio, "", List.of(), START + 1000, true, "官网暂无成交", true, 2);
        SQLiteDatabase old = archive.getWritableDatabase();
        old.execSQL("ALTER TABLE leader_sessions RENAME TO previous_sessions");
        old.execSQL("CREATE TABLE leader_sessions (portfolio TEXT PRIMARY KEY, verified_selection INTEGER NOT NULL, segment_start INTEGER NOT NULL)");
        old.execSQL("INSERT INTO leader_sessions SELECT portfolio,verified_selection,segment_start FROM previous_sessions");
        old.execSQL("DROP TABLE previous_sessions");
        restoreV13ReviewSchema(old);
        old.setVersion(11);
        archive.close();
        assertTrue(archive.needsEmptyRecheck(portfolio, archive.state(portfolio)));
        LeaderArchive.Update repaired = archive.capture(portfolio, "", prior, START + 2000, true, "已读到末尾", true, 2);
        assertTrue(repaired.state.historyDetail, repaired.baseline);
        assertTrue(repaired.continuous);
        assertEquals(0, repaired.added);
        assertEquals(4, archive.count(portfolio));
        assertEquals(1, archive.accountFillCount(portfolio));
        assertEquals(new BigDecimal("0.4"), repaired.state.estimate.get("BTCUSDT|LONG"));
        assertFalse(archive.connectsToTail(portfolio, repaired.state, List.of(prior.get(0))));
        archive.close();
        LeaderArchive.Update actualNew = archive.capture(portfolio, "", opens(0, 5), START + 3000, false, "", false, 2);
        assertFalse(actualNew.baseline);
        assertEquals(1, actualNew.added);
        assertEquals(5, archive.count(portfolio));
    }

    @Test public void partiallyReturnedPagesDoNotMoveTheConfirmedWatermark() throws Exception {
        String portfolio = "4532994172262753536";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> prior = opens(0, 2);
        archive.capture(portfolio, "历史组合", prior, START, true, "早期基线");
        archive.observeGap(portfolio, "", opens(2, 4), START + 800);
        LeaderArchive.State saved = archive.state(portfolio);
        assertEquals(2, saved.head);
        assertEquals(START, saved.captured);
        assertTrue(saved.gap);
        LeaderArchive.Update recovered = archive.capture(portfolio, "", opens(0, 4), START + 1000, true, "", true);
        assertEquals(2, recovered.added);
        assertTrue(recovered.continuous);
        assertEquals(4, archive.count(portfolio));
    }

    @Test public void upgradesVersionTenInPlaceWithoutLosingTheExistingTradeAndAccountFill() throws Exception {
        String portfolio = "23324457";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        db.capture(portfolio, "老版本组合", opens(0, 2), START, true, "原基线");
        db.saveAccountFill(portfolio, AutoFollowService.DEMO_WEB, "existing-v10-fill", START + 1, "原成交");
        SQLiteDatabase original = db.getWritableDatabase();
        original.execSQL("DROP TABLE leader_sessions");
        restoreV13ReviewSchema(original);
        original.setVersion(10);
        db.close();
        assertEquals(0, db.verifiedSelection(portfolio));
        assertEquals(2, db.count(portfolio));
        assertEquals(1, db.accountFillCount(portfolio));
        assertEquals("老版本组合", db.followedPortfolios().stream().filter(item -> item.id.equals(portfolio))
                .findFirst().orElseThrow().name);
    }

    @Test public void showsObservationsWithoutAnAccountAndRecoversAllDetectionPagesOnUpgrade() throws Exception {
        String portfolio = "41231231";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> rows = opens(0, 74);
        archive.capture(portfolio, "测试", rows.subList(0, 2), START, true, "基线");
        assertEquals(0, archive.detectionCount(portfolio));
        long detected = START + 200000;
        archive.observeGap(portfolio, "", rows.subList(70, 74), detected);
        assertEquals(4, archive.detectionCount(portfolio));
        assertEquals(2, archive.state(portfolio).head);
        assertTrue(archive.detections(portfolio, 8, 0).get(0).detectionText(true).contains("待核对"));
        archive.capture(portfolio, "", rows, detected + 1000, false, "", true);
        SQLiteDatabase old = archive.getWritableDatabase();
        old.execSQL("DROP INDEX trades_detected");
        restoreV13ReviewSchema(old);
        old.setVersion(12);
        archive.close();
        assertEquals(72, archive.detectionCount(portfolio));
        assertEquals(8, archive.detections(portfolio, 8, 64).size());
        LeaderArchive.Record last = archive.detections(portfolio, 80, 0).stream()
                .filter(row -> row.signal.time == rows.get(73).time).findFirst().orElseThrow();
        assertEquals(detected, last.detectedAt);
        assertEquals(0, last.kind);
    }

    private static void restoreTradeSchemaWithoutInclusion(SQLiteDatabase db) {
        boolean indexed;
        try (Cursor row = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='trades_detected'", null)) {
            indexed = row.moveToFirst();
        }
        String columns = "portfolio,event,stamp,symbol,action,price,quantity,ordinal,kind,pnl,"
                + "detected_at,turnover,lead_margin,excluded_revision";
        db.execSQL("CREATE TABLE old_trades (portfolio TEXT NOT NULL,event TEXT NOT NULL,stamp INTEGER NOT NULL,"
                + "symbol TEXT NOT NULL,action TEXT NOT NULL,price TEXT NOT NULL,quantity TEXT NOT NULL,"
                + "ordinal INTEGER,kind INTEGER NOT NULL,pnl TEXT,detected_at INTEGER NOT NULL DEFAULT 0,"
                + "turnover TEXT,lead_margin TEXT,excluded_revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(portfolio,event))");
        db.execSQL("INSERT INTO old_trades SELECT " + columns + " FROM trades");
        db.execSQL("DROP TABLE trades");
        db.execSQL("ALTER TABLE old_trades RENAME TO trades");
        db.execSQL("CREATE INDEX trades_time ON trades(portfolio,stamp DESC,event DESC)");
        db.execSQL("CREATE UNIQUE INDEX trades_ordinal ON trades(portfolio,ordinal)");
        if (indexed) db.execSQL("CREATE INDEX trades_detected ON trades(portfolio,detected_at DESC,stamp DESC)"
                + " WHERE kind IN(0,-2) AND excluded_revision=0");
    }

    private static void restoreV13ReviewSchema(SQLiteDatabase db) {
        restoreTradeSchemaWithoutInclusion(db);
        db.execSQL("ALTER TABLE trade_reviews RENAME TO newer_trade_reviews");
        db.execSQL("CREATE TABLE trade_reviews (portfolio TEXT NOT NULL, stamp INTEGER NOT NULL, "
                + "sample TEXT NOT NULL, checked TEXT, legacy INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(portfolio,stamp))");
        db.execSQL("INSERT INTO trade_reviews SELECT portfolio,stamp,sample,checked,legacy FROM newer_trade_reviews");
        db.execSQL("DROP TABLE newer_trade_reviews");
    }

    @Test public void storesAllExpandedInitialRowsAsBaselineAndKeepsTheResultAcrossRestart() throws Exception {
        String portfolio = "45434343";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        LeaderArchive.Update first = db.capture(portfolio, "熬鹰资本", opens(0, 42), START + 50000,
                true, "官网基线已展开 · 42 笔 · 4 次点击 · 2.9 秒");
        assertTrue(first.baseline);
        assertTrue(first.state.historyComplete);
        assertEquals(0, first.added);
        assertEquals(42, first.state.head);
        assertEquals(42, db.count(portfolio));
        assertEquals("初始基线", db.page(portfolio, 1, 41).get(0).source());
        db.close();
        LeaderArchive.Update another = db.capture(portfolio, "", opens(33, 43), START + 60000);
        assertTrue(another.state.historyComplete);
        assertTrue(another.state.historyDetail.contains("2.9 秒"));
        assertEquals(1, another.added);
        assertEquals(43, db.count(portfolio));
        assertTrue(db.hasConfirmedOverlap(portfolio, List.of(signal(4, Rules.Action.OPEN_LONG, "0.1"))));
        assertFalse(db.hasConfirmedOverlap(portfolio, List.of(signal(4, Rules.Action.OPEN_LONG, "0.2"))));
    }

    @Test public void serviceKeepsTheInitialWatermarkEmptyUntilFourPagesAreVerified() throws Exception {
        String portfolio = "49434434";
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive db = LeaderArchive.open(app);
        LocalData data = new LocalData(app);
        Rules.Config config = new Rules.Config(portfolio, data.config().rules);
        FollowBook book = new FollowBook(config, "");
        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "archive", db);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "mode", AutoFollowService.WATCH);
            ReflectionHelpers.setField(service, "file", "auto-344-baseline-test");
            String detail = "官网基线 2/4 页 · 分页未完成";
            WebsiteCapture.Read partial = new WebsiteCapture.Read("", opens(20, 40), null, null, false, detail, false, "", 10);
            try {
                passCapture(service, db.state(portfolio), partial);
                fail("expected four-page baseline gate");
            } catch (RuntimeException blocked) {
                assertTrue(blocked.getCause() instanceof WebsiteCapture.Failure);
            }
            assertEquals(0, db.state(portfolio).head);
            assertEquals(0, db.count(portfolio));
            assertEquals(detail + " · 无进展 0/3 轮 · 8 秒后续读", db.state(portfolio).historyDetail);
            assertTrue(book.window.isEmpty());
            data.prefs.edit().putString("auto_web_access", WebsiteCapture.Access.REGION.name()).apply();
            List<Rules.Signal> timeline = opens(0, 40);
            Rules.Signal crossingPage = timeline.get(18);
            timeline.set(19, crossingPage.withOccurrence(1));
            timeline.set(20, signal(18, Rules.Action.CLOSE_LONG, "0.2"));
            WebsiteCapture.Read full = new WebsiteCapture.Read("", timeline, null, null,
                    true, "官网基线 4/4 页 · 已核对", false, "", 10);
            passCapture(service, db.state(portfolio), full);
            assertTrue(db.state(portfolio).historyComplete);
            assertEquals(40, db.state(portfolio).head);
            assertEquals(40, book.archiveCursor);
            assertEquals(0, book.countEntries());
            List<LeaderArchive.Record> ordered = db.page(portfolio, 3, 19);
            assertEquals(timeline.get(20).id, ordered.get(0).signal.id);
            assertEquals(timeline.get(19).id, ordered.get(1).signal.id);
            assertEquals(timeline.get(18).id, ordered.get(2).signal.id);
            assertFalse(data.prefs.contains("auto_web_access"));
        } finally { controller.destroy(); }
    }

    @Test public void storesSameSecondOrdersInWebsiteOrderAndReusesTheirIdsAfterOpeningTheArchive() throws Exception {
        String portfolio = "53434534";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal start = signal(0, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal open = signal(1, Rules.Action.OPEN_LONG, "0.2");
        Rules.Signal repeat = open.withOccurrence(1);
        Rules.Signal close = signal(1, Rules.Action.CLOSE_LONG, "0.3");
        List<Rules.Signal> timeline = List.of(start, open, repeat, close);
        db.capture(portfolio, "", timeline, START + 5000, true, "官网基线 4/4 页");
        db.close();
        assertTrue(db.state(portfolio).orderedSnapshot);
        assertEquals(repeat.id, db.state(portfolio).window.get(2).id);
        assertEquals(repeat.id, db.after(portfolio, 2, 4).get(0).signal.id);
        assertEquals(close.id, db.page(portfolio, 3, 0).get(0).signal.id);
        assertEquals(repeat.id, db.page(portfolio, 3, 0).get(1).signal.id);
        assertEquals(0, db.capture(portfolio, "", List.of(repeat, close), START + 6000).added);
        Rules.Signal next = signal(1, Rules.Action.OPEN_LONG, "0.4");
        LeaderArchive.Update update = db.capture(portfolio, "", List.of(open, close, next), START + 7000);
        assertTrue(update.continuous);
        assertEquals(1, update.added);
        assertEquals(next.id, db.page(portfolio, 1, 0).get(0).signal.id);
        assertEquals(5, db.count(portfolio));
    }

    @Test public void importsObservedBuySellCardsAndKeepsTheirIdentityAcrossRestarts() throws Exception {
        String portfolio = "4553378712623087105";
        String[][] cards = {
                {"11-21, 22:59:01", "ETH", "买入", "1,689.44", "882.156", "8,155.96"},
                {"11-21, 23:01:51", "ETH", "买入", "1,688.91", "882.156", "8,623.50"},
                {"11-21, 21:41:30", "ETH", "卖出", "1,698.69", "1,764.312", "0.00"},
                {"11-21, 21:32:22", "ETH", "买入", "1,683.49", "1,793.431", "-21,997.90"},
                {"11-21, 20:36:47", "ETH", "卖出", "1,671.22", "1,793.431", "0.00"},
                {"11-21, 19:33:55", "ETH", "卖出", "1,665.78", "870.361", "23,045.19"},
                {"11-21, 19:20:38", "ETH", "卖出", "1,640.20", "870.360", "774.14"},
                {"11-21, 15:10:17", "ETH", "卖出", "1,645.00", "382.109", "2,175.71"},
                {"11-21, 14:57:22", "ETH", "买入", "1,639.31", "2,122.830", "0.00"},
                {"11-18, 17:35:43", "XAU", "买入", "3,786.58", "0.001", "0.03"},
                {"11-18, 17:35:38", "XAU", "买入", "3,787.40", "522.499", "16,741.89"},
                {"11-18, 17:35:38", "XAU", "买入", "3,787.39", "522.499", "16,746.08"},
                {"11-18, 15:30:02", "XAU", "卖出", "3,778.57", "319.816", "0.00"},
                {"11-18, 15:29:52", "XAU", "卖出", "3,778.99", "165.374", "0.00"},
                {"11-17, 21:36:24", "XAU", "卖出", "3,854.74", "559.809", "0.00"},
                {"11-17, 02:35:07", "TQQQ", "买入", "53.04876", "13,907.07", "56,749.96432"}
        };
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 23);
        JSONArray dates = new JSONArray(List.of("2026-11-17", today.toString()));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; i++) text.append(buySellCard(cards[i]));
        List<Rules.Signal> firstPage = WebRecords.parsePage(text.toString(), dates, zone, today);
        for (int i = 10; i < cards.length; i++) text.append(buySellCard(cards[i]));
        List<Rules.Signal> visible = WebRecords.parsePage(text.toString(), dates, zone, today);
        assertEquals(10, firstPage.size());
        assertEquals(16, visible.size());
        assertEquals(6, LeaderHistory.pageGrowth(firstPage, visible));

        JSONArray history = new JSONArray();
        history.put(closedCard("ETH", "做空", "11-21 21:41:30", "11-21 23:01:51", "1,764.312"));
        history.put(closedCard("ETH", "做空", "11-21 20:36:47", "11-21 21:32:22", "1,793.431"));
        history.put(closedCard("ETH", "做多", "11-21 14:57:20", "11-21 19:33:55", "2,122.830"));
        history.put(closedCard("XAU", "做空", "11-17 21:36:23", "11-18 17:35:43", "1,044.999"));
        history.put(closedCard("TQQQ", "做空", "11-10 03:45:07", "11-17 02:35:07", "30,721.95"));
        List<ClosedTradeReview.Position> proofs = ClosedTradeReview.parse(
                new JSONObject().put("zone", zone.getId()).put("positions", history));
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        LeaderArchive.TradeSides directions = db.resolveTradeSides(portfolio, db.state(portfolio), visible, proofs);
        assertEquals(Set.of("TQQQUSDT"), directions.pending);
        assertEquals(15L, directions.rows.stream().filter(row -> !row.action.sideOnly()).count());
        assertEquals(Rules.Action.CLOSE_SHORT, directions.rows.get(15).action);

        long at = visible.get(15).time;
        db.capture(portfolio, "公开成交回归", directions.rows, at + 1000, true, "官网基线 2 页");
        db.close();
        LeaderArchive.State baseline = db.state(portfolio);
        assertTrue(baseline.historyComplete);
        assertTrue(baseline.holdings.isEmpty());
        assertEquals(16L, db.count(portfolio));
        assertEquals(0L, db.detectionCount(portfolio));
        assertEquals(visible.get(15).id, db.after(portfolio, 15, 16).get(0).signal.id);
        assertTrue(db.connectsToTail(portfolio, baseline, visible));

        List<Rules.Signal> updated = new ArrayList<>(visible);
        Rules.Signal newBuy = new Rules.Signal("ETHUSDT", Rules.Action.BUY, at + 2000,
                new BigDecimal("1700"), new BigDecimal("2"), BigDecimal.ZERO);
        Rules.Signal zeroPnlSell = new Rules.Signal("ETHUSDT", Rules.Action.SELL, at + 3000,
                new BigDecimal("1700"), BigDecimal.ONE, BigDecimal.ZERO);
        updated.add(newBuy);
        updated.add(zeroPnlSell);
        LeaderArchive.TradeSides next = db.resolveTradeSides(portfolio, baseline, updated, List.of());
        assertTrue(next.pending.isEmpty());
        assertEquals(Rules.Action.OPEN_LONG, next.rows.get(16).action);
        assertEquals(Rules.Action.CLOSE_LONG, next.rows.get(17).action);
        assertEquals(2, db.capture(portfolio, "", next.rows, at + 4000).added);
        assertEquals(BigDecimal.ONE, db.state(portfolio).estimate.get("ETHUSDT|LONG"));

        String beforeProof = "429111108";
        db.capture(beforeProof, "", visible, at + 1000, true, "已读官网，方向待核对");
        LeaderArchive.TradeSides retried = db.resolveTradeSides(beforeProof, db.state(beforeProof), updated, proofs);
        assertTrue(retried.pending.isEmpty());
        assertEquals(Rules.Action.OPEN_LONG, retried.rows.get(16).action);
        assertEquals(2, db.capture(beforeProof, "", retried.rows, at + 4000).added);
        assertEquals(18L, db.count(beforeProof));
        assertEquals(BigDecimal.ONE, db.state(beforeProof).estimate.get("ETHUSDT|LONG"));
    }

    private static String buySellCard(String[] trade) {
        boolean buys = trade[2].equals("买入");
        return trade[0] + (buys ? "\n做多\n以均价为" : "\n做空\n以价格为") + trade[3] + " USDT "
                + trade[2] + trade[1] + "USDT永续合约，成交数量为 " + trade[4] + " " + trade[1]
                + "，已实现盈亏为" + trade[5] + " USDT。\n";
    }

    private static String closedCard(String base, String side, String start, String end, String quantity) {
        return base + "USDT\n永续\n全仓\n" + side + "\n开仓时间\n2026-" + start + "\n最大持仓量\n"
                + quantity + " " + base + "\n全部平仓\n2026-" + end + "\n已平仓量\n" + quantity + " " + base;
    }

    @Test public void newUnresolvedBuySellFillStaysObservedAndPausesOrders() throws Exception {
        String portfolio = "429111107";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal raw = new Rules.Signal("BTCUSDT", Rules.Action.BUY, START, new BigDecimal("60000"),
                BigDecimal.ONE, BigDecimal.ZERO);
        db.capture(portfolio, "", List.of(raw.withTradeAction(Rules.Action.OPEN_LONG)), START + 1000, true, "已核对");
        Rules.Signal reversal = new Rules.Signal("BTCUSDT", Rules.Action.SELL, START + 2000, new BigDecimal("60100"),
                new BigDecimal("2"), new BigDecimal("100"));
        Rules.Config config = new Rules.Config(portfolio, Set.of("BTCUSDT"), false, new BigDecimal("20"), 5,
                new BigDecimal("0.3"));
        FollowBook book = new FollowBook(config, "");
        var controller = Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "archive", db);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "mode", AutoFollowService.DEMO_WEB);
            ReflectionHelpers.setField(service, "file", "single-side-unknown-test");
            ReflectionHelpers.setField(service, "execution", new FollowExecutor(book, null, () -> {},
                    (id, message, sound) -> {}));
            WebsiteCapture.Read read = new WebsiteCapture.Read("", List.of(raw, reversal), null, null,
                    false, "", false, "", 10);
            try {
                passCapture(service, db.state(portfolio), read);
                fail("an unresolved new fill must keep automatic orders on hold");
            } catch (RuntimeException blocked) {
                assertTrue(blocked.getCause() instanceof WebsiteCapture.Failure);
                assertTrue(blocked.getCause().getMessage().contains("开平仓待核对"));
            }
            assertEquals(1L, db.state(portfolio).head);
            assertEquals(1L, db.detectionCount(portfolio));
            assertEquals(Rules.Action.SELL, db.detections(portfolio, 1, 0).get(0).signal.action);
            assertTrue(db.state(portfolio).gap);
            assertEquals(0, book.countEntries());
        } finally { controller.destroy(); }
    }

    @Test public void backfillsAnIdenticalOlderTradeAfterTheOriginalAnchorHasLeftTheRollingPage() throws Exception {
        String portfolio = "54345435";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal anchor = signal(1, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal second = signal(2, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal third = signal(3, Rules.Action.OPEN_LONG, "0.1");
        db.capture(portfolio, "", List.of(anchor, second), START + 5000);
        assertTrue(db.capture(portfolio, "", List.of(second, third), START + 6000).continuous);
        LeaderArchive.Update filled = db.capture(portfolio, "", List.of(signal(0, Rules.Action.OPEN_LONG, "0.1"),
                anchor, anchor.withOccurrence(1), second, third), START + 7000, true, "官网基线 4/4 页");
        assertTrue(filled.state.historyComplete);
        assertEquals(2, filled.state.backfilled);
        assertEquals(0, filled.added);
        assertEquals(5, db.count(portfolio));
        assertEquals(anchor.id, db.page(portfolio, 1, 2).get(0).signal.id);
        assertEquals(anchor.withOccurrence(1).id, db.page(portfolio, 1, 3).get(0).signal.id);
    }

    @Test public void doesNotReuseTheIdOfAnOlderSameSecondTradeWhenItDropsOutOfView() throws Exception {
        String portfolio = "54454535";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal anchor = signal(1, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal middle = signal(1, Rules.Action.OPEN_LONG, "0.2");
        db.capture(portfolio, "", List.of(anchor), START + 5000);
        assertTrue(db.capture(portfolio, "", List.of(anchor, middle, anchor.withOccurrence(1)), START + 6000,
                true, "官网基线 4/4 页").continuous);
        assertEquals(0, db.capture(portfolio, "", List.of(anchor), START + 7000).added);
        LeaderArchive.Update next = db.capture(portfolio, "", List.of(anchor, anchor.withOccurrence(1)), START + 8000);
        assertTrue(next.continuous);
        assertEquals(1, next.added);
        assertEquals(anchor.withOccurrence(2).id, db.page(portfolio, 1, 0).get(0).signal.id);
    }

    @Test public void distinguishesRegionRestrictionFromPermissionDenialAndNetworkFailures() {
        assertEquals(WebsiteCapture.Access.REGION, WebsiteCapture.httpAccess(451));
        assertEquals(WebsiteCapture.Access.SIGN_IN, WebsiteCapture.httpAccess(401));
        assertEquals(WebsiteCapture.Access.DENIED, WebsiteCapture.httpAccess(403));
        assertEquals(WebsiteCapture.Access.NONE, WebsiteCapture.httpAccess(429));
        assertEquals(WebsiteCapture.Access.NONE, WebsiteCapture.httpAccess(502));
        assertEquals(WebsiteCapture.Access.REGION, WebsiteCapture.redirectAccess(
                android.net.Uri.parse("https://www.binance.com/zh-CN/restricted-region")));
        assertEquals(WebsiteCapture.Access.SIGN_IN, WebsiteCapture.redirectAccess(
                android.net.Uri.parse("https://accounts.binance.com/zh-CN/login")));
        assertEquals(WebsiteCapture.Access.REDIRECT, WebsiteCapture.redirectAccess(
                android.net.Uri.parse("https://www.binance.com.invalid/restricted-region")));
        assertFalse(WebsiteCapture.accessFailure(WebsiteCapture.Access.REGION).stop);
        assertTrue(WebsiteCapture.accessFailure(WebsiteCapture.Access.REGION).backoffMs >= 120000);
        assertTrue(WebsiteCapture.Access.VERIFY.waitsForRetry());
    }

    @Test public void requiresCorroborationBeforeWritingExactCopiesAndPreservesConfirmedMultiples() throws Exception {
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal first = signal(1, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal independent = signal(1, Rules.Action.OPEN_LONG, "0.2");
        Rules.Signal repeat = first.withOccurrence(1);
        List<Rules.Signal> raw = List.of(first, repeat, independent);
        for (int count : new int[]{1, 2}) {
            String portfolio = count == 1 ? "55134531" : "55134532";
            db.queueReviews(portfolio, TradeReview.repeatedSeconds(raw));
            db.close();
            LeaderArchive.Review pending = db.nextReview(portfolio);
            assertNotNull(pending);
            assertEquals(first.time, pending.stamp);
            try {
                db.verifiedRows(portfolio, raw, null, Map.of());
                fail("unverified exact copies must not reach the archive");
            } catch (WebsiteCapture.Failure expected) { assertTrue(expected.getMessage().contains("同秒成交待核对")); }
            try {
                db.resolveReview(portfolio, pending, List.of(first));
                fail("a distinct fill was missing from the second reading");
            } catch (WebsiteCapture.Failure expected) { assertNotNull(db.nextReview(portfolio)); }
            List<Rules.Signal> reference = count == 1 ? List.of(first, independent) : raw;
            assertFalse(db.resolveReview(portfolio, pending, reference));
            assertNull(db.nextReview(portfolio));
            var checked = db.verifiedRows(portfolio, raw, null, Map.of());
            assertEquals(count + 1, checked.size());
            db.capture(portfolio, "", checked, START + 10000, true, "官网基线 4/4 页");
            db.close();
            db.queueReviews(portfolio, TradeReview.repeatedSeconds(raw));
            assertNull(db.nextReview(portfolio));
            assertEquals(0, db.capture(portfolio, "", db.verifiedRows(portfolio, raw, null, Map.of()), START + 11000).added);
            assertEquals(count + 1, db.count(portfolio));
        }
    }

    @Test public void positionHistoryRepairsHistoricalBtcCopiesWithoutReplayingOrders() throws Exception {
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        String newPortfolio = "428111111";
        String oldPortfolio = "428111112";
        long openedAt = 1795269872000L;
        long closedAt = 1795274345000L;
        String leg = "BTCUSDT|SHORT";
        Rules.Signal first = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_SHORT, openedAt,
                new BigDecimal("60747.14"), new BigDecimal("33.303"));
        Rules.Signal added = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_SHORT, openedAt + 24000,
                new BigDecimal("60601.48"), new BigDecimal("33.251"));
        Rules.Signal closed = new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_SHORT, closedAt,
                new BigDecimal("59819.26"), new BigDecimal("66.554"));
        List<Rules.Signal> visible = List.of(first, first.withOccurrence(1), added, closed);
        String card = "BTCUSDT\n永续\n全仓\n做空\n全部平仓\n开仓时间\n2026-11-21 22:04:32\n"
                + "最大持仓量\n66.554 BTC\n全部平仓\n2026-11-21 23:19:05\n已平仓量\n66.554 BTC";
        List<ClosedTradeReview.Position> positions = ClosedTradeReview.parse(new JSONObject()
                .put("zone", "Asia/Shanghai").put("positions", new JSONArray().put(card).put(card)
                        .put(card.replace("66.554 BTC", "66.554 USDT"))));
        assertEquals(1, positions.size());
        assertEquals(openedAt, positions.get(0).openedAt());
        assertEquals(closedAt, positions.get(0).closedAt());
        assertTrue(ClosedTradeReview.corrections(visible.subList(0, 3), positions).isEmpty());

        db.confirmClosedPositionReviews(newPortfolio, visible, positions);
        db.capture(newPortfolio, "", db.normalizeReviewedDuplicates(newPortfolio, visible, null),
                closedAt + 1000, true, "完整基线");
        assertEquals(3L, db.count(newPortfolio));
        assertFalse(db.state(newPortfolio).holdings.containsKey(leg));
        assertEquals(0L, db.detectionCount(newPortfolio));

        db.capture(oldPortfolio, "", visible, closedAt + 1000, true, "旧基线");
        db.saveAccountFill(oldPortfolio, AutoFollowService.DEMO_WEB, "existing-account-fill", closedAt, "原账户成交");
        LeaderArchive.State previous = db.state(oldPortfolio);
        assertEquals(new BigDecimal("33.303"), previous.estimate.get(leg));
        assertTrue(db.hasArchivedRepeats(oldPortfolio));
        assertEquals(1, db.confirmClosedPositionReviews(oldPortfolio, List.of(closed), positions));
        db.close();

        LeaderArchive.State repaired = db.state(oldPortfolio);
        assertEquals(previous.head, repaired.head);
        assertEquals(1L, db.after(oldPortfolio, 0, repaired.head).stream().filter(row -> row.excluded).count());
        assertEquals(new BigDecimal("33.303"), db.visibleAt(oldPortfolio, repaired.head, 0, 0).get(leg));
        assertFalse(db.visibleAt(oldPortfolio, repaired.head, 0).containsKey(leg));
        assertTrue(db.connectsToTail(oldPortfolio, repaired, visible));
        assertEquals(0, db.capture(oldPortfolio, "", db.normalizeReviewedDuplicates(oldPortfolio, visible, null),
                closedAt + 2000).added);
        assertEquals(3L, db.count(oldPortfolio));
        assertEquals(1L, db.accountFillCount(oldPortfolio));
        assertEquals(0L, db.detectionCount(oldPortfolio));
    }

    @Test public void upgradesAndChecksOlderRepeatedBaselineTradesWithoutMovingTheLatestTrade() throws Exception {
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        long oldest = java.time.LocalDate.of(2026, 11, 21).atTime(14, 42, 25).atZone(zone).toInstant().toEpochMilli();
        long latest = java.time.LocalDate.of(2026, 11, 24).atTime(10, 6, 4).atZone(zone).toInstant().toEpochMilli();
        Rules.Signal open = new Rules.Signal("ZECUSDT", Rules.Action.OPEN_SHORT, oldest,
                new BigDecimal("200"), new BigDecimal("2"));
        Rules.Signal other = new Rules.Signal("ZECUSDT", Rules.Action.OPEN_SHORT, oldest,
                new BigDecimal("201"), new BigDecimal("3"));
        Rules.Signal anchor = new Rules.Signal("SKHYNIXUSDT", Rules.Action.OPEN_SHORT, latest,
                new BigDecimal("400"), new BigDecimal("1"));
        List<Rules.Signal> repeated = List.of(open, open.withOccurrence(1), other);
        List<Rules.Signal> raw = List.of(open, open.withOccurrence(1), other, anchor);
        for (String portfolio : List.of("73500001", "73500002")) {
            db.queueReviews(portfolio, TradeReview.repeatedSeconds(raw));
            db.resolveReview(portfolio, db.nextReview(portfolio), repeated);
            db.capture(portfolio, "", raw, latest + 1000, true, "已完成旧版建档");
            db.saveAccountFill(portfolio, AutoFollowService.DEMO_WEB, "saved-" + portfolio, latest, "原账户成交");
        }
        SQLiteDatabase old = db.getWritableDatabase();
        restoreV13ReviewSchema(old);
        old.setVersion(13);
        db.close();

        LeaderArchive.Review repair = db.nextBaselineReview("73500001", Long.MAX_VALUE);
        assertEquals(oldest, repair.stamp);
        try {
            db.resolveBaselineReview("73500001", repair, List.of(open));
            fail("the isolated reading must account for the distinct fill in the same second");
        } catch (WebsiteCapture.Failure expected) {
            assertEquals(4, db.count("73500001"));
        }
        assertTrue(db.resolveBaselineReview("73500001", repair, List.of(open, other)));
        LeaderArchive.Review genuine = db.nextBaselineReview("73500002", Long.MAX_VALUE);
        assertFalse(db.resolveBaselineReview("73500002", genuine, repeated));
        db.close();

        assertEquals(3, db.count("73500001"));
        assertEquals(4, db.count("73500002"));
        assertEquals(1, db.accountFillCount("73500001"));
        LeaderArchive.State state = db.state("73500001");
        assertEquals(4, state.head);
        assertEquals(anchor.id, state.latest().id);
        assertEquals(new BigDecimal("5"), state.estimate.get(open.leg()));
        assertEquals(new BigDecimal("7"), db.state("73500002").estimate.get(open.leg()));
        assertNull(db.nextBaselineReview("73500001", Long.MAX_VALUE));
        assertNull(db.nextBaselineReview("73500002", Long.MAX_VALUE));
        assertEquals(0, db.capture("73500001", "", db.normalizeReviewedDuplicates("73500001", raw, null), latest + 2000).added);
        assertEquals(0, db.detectionCount("73500001"));
    }

    @Test public void positionHistoryKeepsGenuineAndAlreadyDispatchedFills() throws Exception {
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> base = opens(0, 1);
        Rules.Signal first = signal(1, Rules.Action.OPEN_LONG, "2");
        Rules.Signal repeat = first.withOccurrence(1);
        Rules.Signal actualClose = signal(2, Rules.Action.CLOSE_LONG, "4");
        List<Rules.Signal> genuine = List.of(first, repeat, actualClose);
        ClosedTradeReview.Position actual = new ClosedTradeReview.Position(first.leg(), first.time,
                actualClose.time, new BigDecimal("4"), new BigDecimal("4"));
        assertTrue(ClosedTradeReview.corrections(genuine, List.of(actual)).isEmpty());

        Rules.Signal visibleClose = signal(2, Rules.Action.CLOSE_LONG, "2");
        List<Rules.Signal> feed = List.of(base.get(0), first, repeat, visibleClose);
        ClosedTradeReview.Position proof = new ClosedTradeReview.Position(first.leg(), first.time,
                visibleClose.time, new BigDecimal("2"), new BigDecimal("2"));
        String portfolio = "428111113";
        db.capture(portfolio, "", base, START, true, "完整基线");
        db.capture(portfolio, "", feed, START + 3000);
        assertEquals(3L, db.detectionCount(portfolio));
        assertEquals(0, db.confirmClosedPositionReviews(portfolio, feed, List.of(proof)));
        assertEquals(0L, db.revision(portfolio));
        assertEquals(4L, db.count(portfolio));
    }

    @Test public void repairsTheMuSupplementOnUpgradeWithoutReplayingOrDeletingArchivedOrders() throws Exception {
        String portfolio = "55345631";
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive db = LeaderArchive.open(app);
        Rules.Signal repeated = mu(15, Rules.Action.OPEN_LONG, "659.87", "169.61", "111920.5507", null);
        List<Rules.Signal> published = List.of(
                mu(0, Rules.Action.OPEN_LONG, "711.98211", "280.98", "200052.73448", null),
                mu(1, Rules.Action.OPEN_LONG, "714.67808", "251.94", "180055.99439", null),
                mu(2, Rules.Action.CLOSE_LONG, "701.4032", "532.92", "373791.79329", "-6316.93560"),
                mu(9, Rules.Action.OPEN_LONG, "659.27396", "303.03", "199779.78919", null),
                mu(10, Rules.Action.OPEN_LONG, "659.29878", "75.75", "49941.8827", null),
                mu(11, Rules.Action.OPEN_LONG, "662.31402", "375.93", "248983.70897", null),
                mu(12, Rules.Action.OPEN_LONG, "663.25951", "375.71", "249193.22889", null),
                mu(13, Rules.Action.OPEN_LONG, "663.3", "28.54", "18930.582", null),
                mu(14, Rules.Action.OPEN_LONG, "661.57", "175.75", "116270.9275", null),
                repeated, repeated.withOccurrence(1),
                mu(16, Rules.Action.CLOSE_LONG, "610.45181", "737.11", "449970.13345", "-37585.49699"),
                mu(17, Rules.Action.CLOSE_LONG, "607.09602", "767.21", "465770.14126", "-41694.89821"));
        List<Rules.Signal> recent = opens(20, 22);
        db.capture(portfolio, "", recent, START + 24000);
        List<Rules.Signal> raw = new ArrayList<>(published);
        raw.addAll(recent);
        var baseline = db.capture(portfolio, "", raw, START + 25000, true, "官网基线 4/4 页").state;
        assertEquals(2L, baseline.head);
        assertEquals(new BigDecimal("169.61"), baseline.estimate.get("MUUSDT|LONG"));
        var previous = baseline.holdings.get("MUUSDT|LONG").json().put("inferred", "174.756321414557756");
        var savedEstimate = new JSONObject().put("MUUSDT|LONG", previous)
                .put("BTCUSDT|LONG", baseline.holdings.get("BTCUSDT|LONG").json());
        SQLiteDatabase old = db.getWritableDatabase();
        old.execSQL("UPDATE leader SET estimate=? WHERE portfolio=?", new Object[]{savedEstimate.toString(), portfolio});
        old.execSQL("DROP TABLE trade_reviews");
        old.execSQL("CREATE TABLE previous_trades (portfolio TEXT NOT NULL, event TEXT NOT NULL, stamp INTEGER NOT NULL, "
                + "symbol TEXT NOT NULL, action TEXT NOT NULL, price TEXT NOT NULL, quantity TEXT NOT NULL, ordinal INTEGER, "
                + "kind INTEGER NOT NULL, pnl TEXT, detected_at INTEGER NOT NULL DEFAULT 0, turnover TEXT, lead_margin TEXT, "
                + "PRIMARY KEY(portfolio,event))");
        old.execSQL("INSERT INTO previous_trades SELECT portfolio,event,stamp,symbol,action,price,quantity,ordinal,kind,pnl,"
                + "detected_at,turnover,lead_margin FROM trades");
        old.execSQL("DROP TABLE trades");
        old.execSQL("ALTER TABLE previous_trades RENAME TO trades");
        old.execSQL("CREATE INDEX trades_time ON trades(portfolio,stamp DESC,event DESC)");
        old.execSQL("CREATE UNIQUE INDEX trades_ordinal ON trades(portfolio,ordinal)");
        old.setVersion(8);
        db.close();

        var upgraded = db.state(portfolio);
        assertEquals(2L, upgraded.head);
        assertEquals(13L, upgraded.backfilled);
        assertNull(upgraded.holdings.get("MUUSDT|LONG").inferredQuantity);
        assertEquals(new BigDecimal("169.61"), upgraded.projectedSizes.get("MUUSDT|LONG"));
        LeaderArchive.Review request = db.nextReview(portfolio);
        assertNotNull(request);
        assertTrue(request.legacy);
        assertTrue(db.resolveReview(portfolio, request, List.of(repeated)));
        assertEquals(14L, db.count(portfolio));
        assertEquals(1L, db.revision(portfolio));
        assertEquals(2L, db.state(portfolio).head);
        assertFalse(db.state(portfolio).holdings.containsKey("MUUSDT|LONG"));
        assertEquals(new BigDecimal("169.61"), db.visibleAt(portfolio, 2, 13, 0).get("MUUSDT|LONG"));
        assertFalse(db.visibleAt(portfolio, 2, 13, 1).containsKey("MUUSDT|LONG"));
        try (Cursor copies = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM trades WHERE portfolio=? AND stamp=?",
                new String[]{portfolio, Long.toString(repeated.time)})) {
            assertTrue(copies.moveToFirst());
            assertEquals(2, copies.getInt(0));
        }
        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        db.export(portfolio, csv);
        assertEquals(1L, csv.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains("\"169.61\"")).count());

        var controller = Robolectric.buildService(AutoFollowService.class).create();
        try {
            Rules.Config config = new Rules.Config(portfolio, Set.of("MUUSDT"), false, new BigDecimal("20"), 5, new BigDecimal("0.3"));
            FollowBook book = new FollowBook(config, "");
            book.seedPositions(upgraded.estimate);
            book.archiveCursor = upgraded.head;
            book.baselineBackfilled = upgraded.backfilled;
            book.window = upgraded.window;
            book.leg("MUUSDT", true).entries.add(new FollowBook.Entry(repeated, true));
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "archive", db);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "file", "test-346-corrected-history");
            ReflectionHelpers.callInstanceMethod(service, "syncBook", ClassParameter.from(LeaderArchive.State.class, db.state(portfolio)));
            assertEquals(2L, book.archiveCursor);
            assertEquals(1L, book.archiveRevision);
            assertEquals(BigDecimal.ZERO, book.leg("MUUSDT", true).leader);
            assertEquals(0, book.countEntries());
            assertEquals(0, book.detections.length());
        } finally { controller.destroy(); }
    }

    @Test public void persistsRecoveryWatermarkWithoutAcceptingUnconfirmedObservationsAsOverlap() throws Exception {
        String portfolio = "50434434";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        db.capture(portfolio, "", opens(0, 10), START, true, "官网基线 4/4 页");
        assertFalse(db.capture(portfolio, "", opens(12, 22), START + 22000).continuous);
        assertFalse(db.hasConfirmedOverlap(portfolio, opens(12, 22)));
        LeaderArchive.Update recovered = db.capture(portfolio, "", opens(6, 22), START + 23000, false, "", true);
        assertTrue(recovered.continuous);
        assertEquals(22, recovered.state.recoveryHead);
        db.close();
        assertEquals(22, db.state(portfolio).recoveryHead);
        LeaderArchive.Update ordinary = db.capture(portfolio, "", opens(13, 23), START + 24000);
        assertEquals(22, ordinary.state.recoveryHead);
        assertEquals(23, ordinary.state.head);
    }

    private static void passCapture(AutoFollowService service, LeaderArchive.State before, WebsiteCapture.Read read) {
        ReflectionHelpers.callInstanceMethod(service, "acceptCapture", ClassParameter.from(LeaderArchive.State.class, before),
                ClassParameter.from(WebsiteCapture.Read.class, read), ClassParameter.from(long.class, 10L),
                ClassParameter.from(long.class, START + 45000));
    }

    @Test public void prependsHistoricalRowsWithoutReplayingOrdersOrRenumberingTheCursor() throws Exception {
        String portfolio = "46434343";
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive db = LeaderArchive.open(app);
        LocalData data = new LocalData(app);
        Rules.Config config = new Rules.Config(portfolio, data.config().rules);
        LeaderArchive.Update first = db.capture(portfolio, "", opens(6, 10), START + 10000);
        long oldHead = first.state.head;
        String previousEnd = first.state.latest().id;
        FollowBook book = new FollowBook(config, "demo");
        book.accept(first.state.window);
        book.archiveCursor = oldHead;
        FollowBook.Leg longSide = book.leg("BTCUSDT", true);
        longSide.owned = new BigDecimal("0.2");
        longSide.target = new BigDecimal("0.15");
        longSide.entries.add(new FollowBook.Entry(signal(9, Rules.Action.OPEN_LONG, "0.1")));

        LeaderArchive.Update merged = db.capture(portfolio, "", opens(0, 11), START + 11000,
                false, "官网基线分页未完成");
        assertTrue(merged.continuous);
        assertFalse(merged.state.historyComplete);
        assertEquals(6, merged.state.backfilled);
        assertEquals(oldHead + 1, merged.state.head);
        assertEquals(oldHead, db.sequenceOf(portfolio, previousEnd));
        assertEquals("历史基线补录", db.page(portfolio, 1, 10).get(0).source());
        assertEquals(1, db.after(portfolio, oldHead, merged.state.head).size());
        assertEquals(new BigDecimal("1.1"), merged.state.estimate.get("BTCUSDT|LONG"));
        assertEquals(11, db.count(portfolio));

        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "archive", db);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "file", "auto-history-merge-test");
            ReflectionHelpers.callInstanceMethod(service, "syncBook",
                    ClassParameter.from(LeaderArchive.State.class, merged.state));
            assertEquals(merged.state.head, book.archiveCursor);
            assertEquals(6, book.baselineBackfilled);
            assertEquals(new BigDecimal("1.1"), longSide.leader);
            assertEquals(new BigDecimal("0.2"), longSide.owned);
            assertEquals(new BigDecimal("0.15"), longSide.target);
            assertEquals(1, longSide.entries.size());
            assertEquals(1, book.detections.length());
            assertEquals(6, FollowBook.restore(data.load("auto-history-merge-test")).baselineBackfilled);
        } finally { controller.destroy(); }
        db.close();
        LeaderArchive.Update ended = db.capture(portfolio, "", opens(0, 11), START + 12000,
                true, "官网基线已展开");
        assertTrue(ended.state.historyComplete);
        assertEquals(6, ended.state.backfilled);
        assertEquals(0, ended.added);
        assertEquals(11, db.count(portfolio));
    }

    @Test public void leavesHistoryPendingWhenOlderRowsCannotBeJoinedToTheOriginalStart() throws Exception {
        String portfolio = "47434343";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        db.capture(portfolio, "", opens(5, 9), START + 9000);
        LeaderArchive.Update continuous = db.capture(portfolio, "", opens(7, 11), START + 11000);
        assertTrue(continuous.continuous);
        List<Rules.Signal> mismatch = opens(0, 5);
        mismatch.addAll(opens(6, 12));
        LeaderArchive.Update next = db.capture(portfolio, "", mismatch, START + 12000,
                true, "官网基线已展开");
        assertTrue(next.continuous);
        assertEquals(1, next.added);
        assertFalse(next.state.historyComplete);
        assertEquals(0, next.state.backfilled);
        assertEquals(7, db.count(portfolio));
    }

    @Test public void archivesRepeatedRollingWindowsAcrossRestartAndExportsEveryTrade() throws Exception {
        String id = "66666666";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        LeaderArchive.Update baseline = db.capture(id, "熬鹰资本", opens(0, 10), START);
        assertTrue(baseline.baseline);
        assertEquals(0, baseline.added);
        assertEquals(10, db.count(id));
        assertEquals(START, baseline.state.started);
        assertEquals(1, baseline.state.estimate.get("BTCUSDT|LONG").compareTo(BigDecimal.ZERO));
        for (int next = 10; next < 75; next++) {
            List<Rules.Signal> window = opens(next - 9, next + 1);
            assertEquals(1, db.capture(id, "", window, START + next).added);
            assertEquals(0, db.capture(id, "", window, START + next).added);
        }
        db.close();

        LeaderArchive reopened = LeaderArchive.open(RuntimeEnvironment.getApplication());
        assertEquals("熬鹰资本", reopened.state(id).name);
        assertEquals(START, reopened.state(id).started);
        assertEquals(75, reopened.state(id).head);
        assertEquals(0, new BigDecimal("7.5").compareTo(reopened.state(id).estimate.get("BTCUSDT|LONG")));
        List<Rules.Signal> update = opens(66, 75);
        update.add(signal(75, Rules.Action.CLOSE_LONG, "0.3"));
        assertEquals(1, reopened.capture(id, "", update, START + 75).added);
        assertEquals(76, reopened.count(id));
        assertEquals(0, new BigDecimal("7.2").compareTo(reopened.state(id).estimate.get("BTCUSDT|LONG")));
        assertEquals("初始基线", reopened.page(id, 40, 40).get(35).source());
        assertEquals("跟踪新增", reopened.page(id, 40, 0).get(0).source());
        assertEquals(6, reopened.after(id, 70, 76).size());
        reopened.capture(OTHER, "另一位", opens(0, 10), START);
        assertEquals(10, reopened.count(OTHER));
        assertEquals(76, reopened.count(id));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        reopened.export(id, bytes);
        String csv = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF组合ID,带单员"));
        assertEquals(77, csv.split("\r\n").length);
        assertTrue(csv.contains("\"熬鹰资本\""));
        assertTrue(csv.contains("\"平多\",\"0.3\""));
        assertFalse(csv.contains("另一位"));
    }

    @Test public void keepsNewVisibleTradesDuringGapAndPromotesThemAfterOverlapReturns() throws Exception {
        String id = "88888888";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        db.capture(id, "熬鹰资本", opens(0, 10), START);
        LeaderArchive.Update gap = db.capture(id, "", opens(12, 16), START + 1);
        assertFalse(gap.continuous);
        assertTrue(gap.state.gap);
        assertEquals(14, db.count(id));
        assertEquals(10, gap.state.head);
        assertEquals(0, db.sequenceOf(id, signal(15, Rules.Action.OPEN_LONG, "0.1").id));
        assertEquals("缺口后观察", db.page(id, 1, 0).get(0).source());
        assertEquals(0, new BigDecimal("1").compareTo(gap.state.estimate.get("BTCUSDT|LONG")));
        LeaderArchive.Projection observation = db.projection(id, gap.state);
        assertEquals(START + 15000, observation.lastTrade);
        assertEquals(0, new BigDecimal("1.4").compareTo(observation.holdings.get("BTCUSDT|LONG").displayQuantity()));
        assertEquals(Set.of("BTCUSDT"), observation.openSymbols());
        db.close();
        assertEquals(0, new BigDecimal("1.4").compareTo(db.projection(id, db.state(id))
                .holdings.get("BTCUSDT|LONG").displayQuantity()));
        LeaderArchive.Update recovered = db.capture(id, "", opens(6, 18), START + 2);
        assertTrue(recovered.continuous);
        assertFalse(recovered.state.gap);
        assertEquals(18, db.count(id));
        assertEquals(8, recovered.added);
        assertEquals(16, db.sequenceOf(id, signal(15, Rules.Action.OPEN_LONG, "0.1").id));
        assertEquals(8, db.after(id, 10, 18).size());
        assertEquals(START + 1, db.after(id, 15, 16).get(0).detectedAt);
        assertEquals(0, new BigDecimal("1.8").compareTo(db.projection(id, recovered.state)
                .holdings.get("BTCUSDT|LONG").displayQuantity()));
    }

    @Test public void showsNamePositionsAndArchiveAndResumesInterruptedMode() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive db = LeaderArchive.open(app);
        db.capture(ID, "熬鹰资本", opens(0, 10), START);
        db.capture(ID, "", opens(2, 12), START + 1);
        FuturesApi.Position actual = new FuturesApi.Position();
        actual.shorts = new BigDecimal("0.07");
        actual.shortEntry = new BigDecimal("2100.75");
        actual.shortUpdated = START + 4;
        actual.shortRisk = new FuturesApi.PositionRisk(new BigDecimal("127.882365"), new BigDecimal("2480.125"),
                new BigDecimal("19.5238"), "USDT");
        AutoFollowService.publishPositions(FuturesApi.DEMO, Map.of("ETHUSDT", actual));
        AutoFollowService.publishLeverages(FuturesApi.DEMO, Map.of("ETHUSDT", 10));
        db.saveAccountFill(ID, AutoFollowService.DEMO_WEB, "activity-fill", START,
                "减空已成交 · BTCUSDT\n成交均价 30001 USDT · 已实现盈亏 -1 USDT\n带单员成交均价 30000 USDT · 点差 +0.0033%");
        LocalData data = new LocalData(app);
        data.rememberLeaderBalance(ID, new BigDecimal("20000"), System.currentTimeMillis());
        data.prefs.edit().putString("auto_active", AutoFollowService.WATCH).commit();

        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertNotNull(text(root, "熬鹰资本"));
            assertNotNull(text(root, "带单员推算持仓"));
            assertNotNull(text(root, "BTCUSDT 多 · 1.2 BTC"));
            assertNotNull(text(root, "推算开仓均价 30000 USDT"));
            assertNotNull(text(root, "估算强平价"));
            assertNotNull(text(root, "≈ 13400.34 USDT"));
            assertNotNull(text(root, "缺标记价用持仓均价"));
            assertNotNull(text(root, "我的当前持仓"));
            assertNotNull(text(root, "ETHUSDT 空 · 0.07 ETH"));
            assertNotNull(text(root, "开仓均价 2100.75 USDT"));
            assertNotNull(text(root, "持仓保证金"));
            assertNotNull(text(root, "127.88 USDT"));
            assertNotNull(text(root, "2480.13 USDT"));
            assertNotNull(text(root, "10 倍"));
            assertNotNull(text(root, "逐仓钱包 19.52 USDT"));
            assertNull(text(root, "手动导入和回放"));
            assertNull(text(root, "本地回放"));
            text(root, "成交记录").performClick();
            assertNotNull(text(root, "共 12 笔"));
            assertNotNull(text(root, "跟踪新增"));
            assertNotNull(text(root, "初始基线"));
            assertNotNull(text(root, "导出全部 CSV"));
            text(root, "我的成交").performClick();
            assertNotNull(text(root, "成交均价 30001 USDT · 已实现盈亏 -1 USDT"));
            assertNotNull(text(root, "点差 +0.0033%"));
            Intent resumed = shadowOf(app).getNextStartedService();
            assertNotNull(resumed);
            assertEquals(AutoFollowService.RESTORE, resumed.getAction());
        } finally {
            AutoFollowService.reserveStart(false);
        }
    }

    @Test public void showsAndUpdatesEstimateFromVisibleTradesWithNoMarketMetadata() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String portfolio = "72727271";
        LocalData data = new LocalData(app);
        data.saveConfig(new Rules.Config(portfolio, data.config().rules));
        LeaderArchive db = LeaderArchive.open(app);
        assertTrue(db.capture(portfolio, "熬鹰资本", opens(0, 10), START).baseline);
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertNotNull(text(root, "余额未知 · 按 20 倍杠杆假设"));
            assertNotNull(text(root, "缺档位按 0.5% 维持率"));
            assertNotNull(text(root, "≈ 28643.22 USDT"));
            Rules.Signal add = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_LONG, START + 12000,
                    new BigDecimal("40000"), BigDecimal.ONE);
            LeaderArchive.Update gap = db.capture(portfolio, "", List.of(add), START + 14000);
            assertFalse(gap.continuous);
            assertEquals(0, new BigDecimal("2").compareTo(db.projection(portfolio, gap.state)
                    .holdings.get("BTCUSDT|LONG").displayQuantity()));
            ReflectionHelpers.callInstanceMethod(screen.get(), "refreshFollow");
            assertNotNull(text(root, "成交有缺口 · 按已见记录估算"));
            TextView holding = text(root, "BTCUSDT 多");
            assertNotNull(holding);
            assertTrue(holding.getText().toString(), holding.getText().toString().contains("BTCUSDT 多 · 2 BTC"));
            assertNotNull(text(root, "≈ 33417.09 USDT"));
            assertNull(text(root, "资料待核对"));
        }
    }

    @Test public void persistsLeaderBalanceOnlyForTheSamePortfolio() {
        Application app = RuntimeEnvironment.getApplication();
        LocalData first = new LocalData(app);
        first.prefs.edit().putString("auto_portfolio", ID).putString("auto_leader_margin", "100")
                .putLong("auto_leader_margin_at", START).apply();
        assertEquals(new BigDecimal("100"), first.leaderBalance(ID).value);
        assertNull(first.leaderBalance(OTHER));
        first.rememberLeaderBalance(ID, new BigDecimal("120"), START + 20);
        first.prefs.edit().putString("auto_portfolio", OTHER).remove("auto_leader_margin")
                .remove("auto_leader_margin_at").apply();
        LocalData reopened = new LocalData(app);
        assertEquals(new BigDecimal("120"), reopened.leaderBalance(ID).value);
        assertEquals(START + 20, reopened.leaderBalance(ID).at);
        assertNull(reopened.leaderBalance(OTHER));
    }

    @Test public void showsLatestPricePnlAndHidesTheDirectionThatClosedMoreThanItsVisibleSize() throws Exception {
        String portfolio = "51434434";
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        data.saveConfig(new Rules.Config(portfolio, data.config().rules));
        LeaderArchive db = LeaderArchive.open(app);
        List<Rules.Signal> rows = List.of(signal(0, Rules.Action.OPEN_LONG, "0.2"),
                new Rules.Signal("ETHUSDT", Rules.Action.OPEN_SHORT, START + 1000,
                        new BigDecimal("2000"), new BigDecimal("0.5")),
                new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_LONG, START + 2000,
                        new BigDecimal("31000"), new BigDecimal("0.4"), new BigDecimal("400")));
        db.capture(portfolio, "", rows, START + 5000);
        AutoFollowService.LeaderMarket original = AutoFollowService.leaderMarket();
        ReflectionHelpers.setStaticField(AutoFollowService.class, "leaderMarket", new AutoFollowService.LeaderMarket(
                Map.of(), 0, Map.of("ETHUSDT", new BigDecimal("1950")), System.currentTimeMillis(), Map.of(), 0));
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertNotNull(text(root, "ETHUSDT 空 · 0.5 ETH"));
            assertNotNull(text(root, "估算浮盈亏"));
            assertNotNull(text(root, "+25 USDT"));
            assertNull(text(root, "BTCUSDT 多 · 余量待确认"));
        } finally { ReflectionHelpers.setStaticField(AutoFollowService.class, "leaderMarket", original); }
    }

    @Test public void preservesWeightedEntryPriceAndBackfillsLeaderPnlWithoutDuplicates() throws Exception {
        String portfolio = "55555555";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal first = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_LONG, START, new BigDecimal("100"), new BigDecimal("0.2"));
        Rules.Signal second = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_LONG, START + 1000, new BigDecimal("160"), new BigDecimal("0.4"));
        Rules.Signal close = new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_LONG, START + 2000, new BigDecimal("130"), new BigDecimal("0.15"));
        db.capture(portfolio, "测试组合", List.of(first, second, close), START);
        LeaderHistory.Holding holding = db.state(portfolio).holdings.get("BTCUSDT|LONG");
        assertEquals(0, new BigDecimal("140").compareTo(holding.entryPrice));
        assertEquals(0, new BigDecimal("0.45").compareTo(holding.quantity));
        assertEquals(close.time, holding.updatedAt);
        Rules.Signal enriched = new Rules.Signal(close.symbol, close.action, close.time, close.price, close.quantity,
                new BigDecimal("-1.35"));
        assertEquals(0, db.capture(portfolio, "", List.of(first, second, enriched), START + 1000).added);
        db.close();
        assertEquals(3, db.count(portfolio));
        assertEquals(new BigDecimal("-1.35"), db.page(portfolio, 10, 0).get(0).signal.realizedPnl);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        db.export(portfolio, bytes);
        String csv = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("已实现盈亏USDT"));
        assertTrue(csv.contains("\"-1.35\""));
        db.saveAccountFill(portfolio, AutoFollowService.REAL, "same-fill", START, "已确认成交");
        db.saveAccountFill(portfolio, AutoFollowService.REAL, "same-fill", START, "已确认成交");
        assertEquals(1, db.accountFillCount(portfolio));
    }

    @Test public void upgradesVersionOneWithoutInventingCostForOlderIncompletePositions() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, estimate TEXT)");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, "
                    + "action TEXT, price TEXT, quantity TEXT, kind INTEGER)");
            db.execSQL("INSERT INTO leader VALUES (?,?)", new Object[]{"82828282",
                    "{\"BTCUSDT|LONG\":\"0.2\",\"ETHUSDT|SHORT\":\"0.5\"}"});
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?)", new Object[]{"82828282", 1, START,
                    "BTCUSDT", "OPEN_LONG", "30000", "0.2", 1});
            db.execSQL("ALTER TABLE trades ADD COLUMN event TEXT");
            archive.onUpgrade(db, 1, LeaderArchive.DB_VERSION);
            try (Cursor cursor = db.rawQuery("SELECT estimate FROM leader WHERE portfolio='82828282'", null)) {
                assertTrue(cursor.moveToFirst());
                JSONObject estimates = new JSONObject(cursor.getString(0));
                assertEquals("30000", estimates.getJSONObject("BTCUSDT|LONG").getString("p"));
                JSONObject incomplete = estimates.getJSONObject("ETHUSDT|SHORT");
                assertEquals("0.5", incomplete.getString("q"));
                assertTrue(incomplete.isNull("p"));
            }
        }
    }

    @Test public void upgradesVersionTwoKeepingOriginalTradesAndOnlyTimestampsNewDetections() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, estimate TEXT)");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, action TEXT, "
                    + "price TEXT, quantity TEXT, kind INTEGER, pnl TEXT)");
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?)", new Object[]{ID, 1, START,
                    "BTCUSDT", "CLOSE_LONG", "30000", "0.2", 0, "-2"});
            db.execSQL("ALTER TABLE trades ADD COLUMN event TEXT");
            archive.onUpgrade(db, 2, LeaderArchive.DB_VERSION);
            try (Cursor row = db.rawQuery("SELECT stamp,pnl,detected_at FROM trades", null)) {
                assertTrue(row.moveToFirst());
                assertEquals(START, row.getLong(0));
                assertEquals("-2", row.getString(1));
                assertEquals(0, row.getLong(2));
            }
        }
    }

    @Test public void upgradingVersionFourPreservesVisibleBasisWhenClosePnlDisagrees() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, estimate TEXT)");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, action TEXT,"
                    + " price TEXT, quantity TEXT, kind INTEGER, pnl TEXT, detected_at INTEGER, turnover TEXT, lead_margin TEXT)");
            db.execSQL("INSERT INTO leader VALUES (?,?)", new Object[]{"85858585",
                    "{\"BTCUSDT|LONG\":{\"q\":\"0.2\",\"p\":\"95\",\"t\":" + (START + 1000) + "}}"});
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"85858585", 1, START, "BTCUSDT", "OPEN_LONG", "95", "0.4", 1, null, START, "38", null});
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"85858585", 2, START + 1000, "BTCUSDT", "CLOSE_LONG", "105", "0.2", 0, "1", START + 1000, "21", null});
            db.execSQL("ALTER TABLE trades ADD COLUMN event TEXT");
            archive.onUpgrade(db, 4, LeaderArchive.DB_VERSION);
            try (Cursor rows = db.rawQuery("SELECT estimate FROM leader", null)) {
                assertTrue(rows.moveToFirst());
                LeaderHistory.Holding holding = LeaderHistory.Holding.read(new JSONObject(rows.getString(0)).getJSONObject("BTCUSDT|LONG"));
                assertEquals(new BigDecimal("0.2"), holding.quantity);
                assertEquals(new BigDecimal("95"), holding.entryPrice);
                assertEquals(new BigDecimal("100"), holding.closeCost);
                assertTrue(holding.uncertainBasis);
            }
        }
    }

    @Test public void upgradesVersionFiveInPlaceAndSchedulesOnlyTheMissingBaselineExpansion() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, estimate TEXT, head INTEGER, gap INTEGER)");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, action TEXT,"
                    + " price TEXT, quantity TEXT, kind INTEGER, pnl TEXT, detected_at INTEGER, turnover TEXT, lead_margin TEXT)");
            String estimate = new JSONObject().put("BTCUSDT|LONG", new JSONObject().put("q", "0.2")).toString();
            db.execSQL("INSERT INTO leader VALUES (?,?,?,?)", new Object[]{"48434343", estimate, 21, 1});
            db.execSQL("ALTER TABLE trades ADD COLUMN event TEXT");
            archive.onUpgrade(db, 5, LeaderArchive.DB_VERSION);
            try (Cursor row = db.rawQuery("SELECT estimate,head,gap,backfilled,history_complete,history_detail FROM leader", null)) {
                assertTrue(row.moveToFirst());
                assertEquals("0.2", new JSONObject(row.getString(0)).getJSONObject("BTCUSDT|LONG").getString("q"));
                assertEquals(21, row.getLong(1));
                assertEquals(1, row.getInt(2));
                assertEquals(0, row.getLong(3));
                assertEquals(0, row.getInt(4));
                assertEquals("", row.getString(5));
            }
        }
    }

    @Test public void upgradesVersionSixAndRebuildsOverClosedDirectionsBeforeTheNewBaselineCheck() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, estimate TEXT, history_complete INTEGER,"
                    + " history_detail TEXT)");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, action TEXT,"
                    + " price TEXT, quantity TEXT, kind INTEGER, pnl TEXT, detected_at INTEGER, turnover TEXT, lead_margin TEXT)");
            String portfolio = "52434434";
            JSONObject old = new JSONObject().put("BTCUSDT|LONG",
                    new JSONObject().put("q", "0").put("p", "100").put("t", START + 1000));
            db.execSQL("INSERT INTO leader VALUES (?,?,1,'旧基线')", new Object[]{portfolio, old.toString()});
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{portfolio, 1, START, "BTCUSDT", "OPEN_LONG", "100", "0.2", 1, null, START, "20", null});
            db.execSQL("INSERT INTO trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{portfolio, 2, START + 1000, "BTCUSDT", "CLOSE_LONG", "105", "0.4", 0, "2", START, "42", null});
            db.execSQL("ALTER TABLE trades ADD COLUMN event TEXT");
            archive.onUpgrade(db, 6, LeaderArchive.DB_VERSION);
            try (Cursor row = db.rawQuery("SELECT estimate,history_complete,history_detail,recovery_head FROM leader", null)) {
                assertTrue(row.moveToFirst());
                assertFalse(new JSONObject(row.getString(0)).has("BTCUSDT|LONG"));
                assertEquals(0, row.getInt(1));
                assertTrue(row.getString(2).contains("官网基线"));
                assertEquals(0, row.getLong(3));
            }
        }
    }

    @Test public void versionSevenKeepsCompletedBaselineDuringOrderedWindowUpgrade() throws Exception {
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, head INTEGER, history_complete INTEGER, "
                    + "recovery_head INTEGER, estimate TEXT NOT NULL DEFAULT '{}')");
            db.execSQL("CREATE TABLE trades (portfolio TEXT, event TEXT, ordinal INTEGER, stamp INTEGER, symbol TEXT, "
                    + "action TEXT, price TEXT, quantity TEXT, kind INTEGER, pnl TEXT, detected_at INTEGER, turnover TEXT, lead_margin TEXT)");
            db.execSQL("INSERT INTO leader(portfolio,head,history_complete,recovery_head) VALUES ('54434534', 40, 1, 39)");
            archive.onUpgrade(db, 7, LeaderArchive.DB_VERSION);
            try (Cursor row = db.rawQuery("SELECT head,history_complete,recovery_head,ordered_snapshot FROM leader", null)) {
                assertTrue(row.moveToFirst());
                assertEquals(40, row.getLong(0));
                assertEquals(1, row.getInt(1));
                assertEquals(39, row.getLong(2));
                assertEquals(0, row.getInt(3));
            }
        }
    }

    @Test public void upgradingVersionNineReplaysTheClosedBtcLegWithoutDiscardingArchiveState() throws Exception {
        String portfolio = "52244192";
        String leg = "BTCUSDT|SHORT";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal first = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_SHORT, START,
                new BigDecimal("60747.14"), new BigDecimal("33.303"), null, new BigDecimal("2023061.91"), null);
        Rules.Signal second = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_SHORT, START + 1000,
                new BigDecimal("60601.48"), new BigDecimal("33.251"), null, new BigDecimal("2015059.92"), null);
        Rules.Signal other = new Rules.Signal("ETHUSDT", Rules.Action.OPEN_LONG, START + 2000,
                new BigDecimal("2500"), BigDecimal.ONE);
        Rules.Signal closed = new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_SHORT, START + 3000,
                new BigDecimal("59819.26"), new BigDecimal("66.554"), new BigDecimal("36981.39"),
                new BigDecimal("3981211.21"), null);
        LeaderArchive.State before = db.capture(portfolio, "测试组合", List.of(first, second, other, closed),
                START + 4000, true, "官网基线 4/4 页").state;
        db.saveAccountFill(portfolio, AutoFollowService.DEMO_WEB, "existing-fill", START + 5000, "已有成交");
        db.queueReviews(portfolio, Map.of(closed.time, List.of(closed, closed.withOccurrence(1))));
        JSONObject saved = new JSONObject().put(leg, new JSONObject().put("q", "0").put("t", closed.time)
                .put("p", "60374.922619226").put("closeCost", "60374.922619226").put("fromClose", true)
                .put("uncertainBasis", true).put("added", "0").put("addedValue", "0"))
                .put("ETHUSDT|LONG", before.holdings.get("ETHUSDT|LONG").json());
        SQLiteDatabase old = db.getWritableDatabase();
        old.execSQL("UPDATE leader SET estimate=? WHERE portfolio=?", new Object[]{saved.toString(), portfolio});
        restoreV13ReviewSchema(old);
        old.setVersion(9);
        db.close();

        LeaderArchive.State reopened = db.state(portfolio);
        assertEquals(before.head, reopened.head);
        assertTrue(reopened.historyComplete);
        assertEquals(4, db.count(portfolio));
        assertEquals(1, db.accountFillCount(portfolio));
        assertEquals(closed.time, db.nextReview(portfolio).stamp);
        assertFalse(reopened.holdings.containsKey(leg));
        assertEquals(Set.of("ETHUSDT"), db.projection(portfolio, reopened).openSymbols());
        assertEquals(0, closed.realizedPnl.compareTo(db.page(portfolio, 1, 0).get(0).signal.realizedPnl));
        try (Cursor row = db.getReadableDatabase().rawQuery("SELECT estimate FROM leader WHERE portfolio=?",
                new String[]{portfolio})) {
            assertTrue(row.moveToFirst());
            assertFalse(new JSONObject(row.getString(0)).has(leg));
        }
    }

    @Test public void recalculatesTheSameHoldingWhenLaterSnapshotsCompleteTheTradePnl() throws Exception {
        String portfolio = "32323232";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal opened = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_LONG, START,
                new BigDecimal("95"), new BigDecimal("0.4"));
        Rules.Signal close = new Rules.Signal("BTCUSDT", Rules.Action.CLOSE_LONG, START + 1000,
                new BigDecimal("105"), new BigDecimal("0.2"));
        archive.capture(portfolio, "", List.of(opened, close), START + 2000);
        Rules.Signal enriched = new Rules.Signal(close.symbol, close.action, close.time, close.price, close.quantity,
                new BigDecimal("1"), new BigDecimal("21"), null);
        LeaderArchive.Update change = archive.capture(portfolio, "", List.of(opened, enriched), START + 3000);
        assertEquals(0, change.added);
        assertEquals(0, new BigDecimal("95").compareTo(change.state.holdings.get("BTCUSDT|LONG").entryPrice));
        archive.close();
        LeaderHistory.Holding holding = LeaderArchive.open(RuntimeEnvironment.getApplication()).state(portfolio)
                .holdings.get("BTCUSDT|LONG");
        assertTrue(holding.uncertainBasis);
        assertEquals(0, new BigDecimal("100").compareTo(holding.closeCost));
        assertEquals(new BigDecimal("0.2"), holding.displayQuantity());
    }
    @Test public void preservesFirstPublishedSizingInputsAndDetectionTimeAcrossRefreshes() throws Exception {
        String portfolio = "67676767";
        LeaderArchive db = LeaderArchive.open(RuntimeEnvironment.getApplication());
        Rules.Signal first = signal(0, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal next = new Rules.Signal("BTCUSDT", Rules.Action.OPEN_LONG, START + 1000,
                new BigDecimal("100"), BigDecimal.ONE, null, new BigDecimal("99.994"), null);
        db.capture(portfolio, "测试组合", List.of(first), START);
        db.capture(portfolio, "", List.of(first, next), START + 2000);
        Rules.Signal withBalance = next.withLeaderMargin(new BigDecimal("70"));
        assertEquals(0, db.capture(portfolio, "", List.of(first, withBalance), START + 3000).added);
        assertEquals(0, db.capture(portfolio, "", List.of(first, next.withLeaderMargin(new BigDecimal("90"))), START + 4000).added);
        db.close();
        LeaderArchive.Record record = LeaderArchive.open(RuntimeEnvironment.getApplication()).after(portfolio, 1, 2).get(0);
        assertEquals(START + 2000, record.detectedAt);
        assertEquals(new BigDecimal("70"), record.signal.leaderMargin);
        assertEquals(new BigDecimal("99.994"), record.signal.turnover);
    }

    @Test public void requiresFreshConsentBeforeQueuingTheFirstExistingPosition() throws Exception {
        String portfolio = "90909090";
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive archive = LeaderArchive.open(app);
        Rules.Config config = new Rules.Config(portfolio, Set.of("BTCUSDT", "ETHUSDT"), false,
                BigDecimal.TEN, 10, BigDecimal.ONE);
        FollowBook book = new FollowBook(config, "fixture");
        String key = "test-initial-choice";
        Rules.Signal eth = new Rules.Signal("ETHUSDT", Rules.Action.OPEN_SHORT, START, BigDecimal.valueOf(2800), BigDecimal.ONE);
        List<Rules.Signal> opening = List.of(eth, signal(0, Rules.Action.OPEN_LONG, "0.1"));
        LeaderArchive.State before = archive.capture(portfolio, "测试组合", opening, START).state;
        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "mode", AutoFollowService.DEMO_WEB);
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "file", key);
            ReflectionHelpers.setField(service, "initialChoiceKey", key);
            assertFalse(applyChoice(service, before));
            assertTrue(book.waits().isEmpty());
            String beforeChoice = AutoFollowService.initialPositions().candidates.stream()
                    .filter(row -> row.symbol.equals("BTCUSDT")).findFirst().get().id;
            service.onStartCommand(choice(portfolio, before.head, beforeChoice), 0, 1);
            LeaderArchive.State newer = archive.capture(portfolio, "", List.of(eth, opening.get(1),
                    signal(1, Rules.Action.OPEN_LONG, "0.1")), START + 1000).state;
            assertFalse(applyChoice(service, newer));
            assertTrue(book.waits().isEmpty());
            String selected = AutoFollowService.initialPositions().candidates.stream()
                    .filter(row -> row.symbol.equals("BTCUSDT")).findFirst().get().id;
            service.onStartCommand(choice(portfolio, newer.head, selected), 0, 2);
            assertTrue(applyChoice(service, newer));
            FollowBook saved = FollowBook.restore(new LocalData(app).load(key));
            assertTrue(saved.initialChoiceDone);
            assertEquals(1, saved.waits().size());
            assertTrue(saved.waits().get(0).existing);
            assertEquals("BTCUSDT", saved.waits().get(0).signal.symbol);
            assertEquals(new BigDecimal("0.2"), saved.waits().get(0).signal.quantity);
        } finally { controller.destroy(); }
    }

    @Test public void followsTheFirstNewTradeAfterAnExplicitlyEmptyBaselineAndRestart() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive archive = LeaderArchive.open(app);
        Rules.Config config = new Rules.Config("83838383", Set.of("BTCUSDT"), false,
                BigDecimal.TEN, 5, BigDecimal.ONE);
        String file = "empty-baseline-fixture";
        FollowBook book = new FollowBook(config, "fixture");
        book.initialChoiceDone = true;
        LeaderArchive.Update initial = archive.capture(config.portfolio, "新组合", List.of(), START,
                true, "官网基线 0 页 · 已读到末尾");
        assertTrue(initial.baseline);
        assertTrue(initial.state.historyComplete);
        assertEquals(0L, initial.state.head);
        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "mode", AutoFollowService.DEMO_WEB);
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "book", book);
            ReflectionHelpers.setField(service, "file", file);
            syncBook(service, initial.state);
            LocalData data = new LocalData(app);
            FollowBook restored = FollowBook.restore(data.load(file));
            assertTrue(restored.baselineReady);
            assertEquals(0, restored.archiveCursor);
            ReflectionHelpers.setField(service, "book", restored);
            FollowExecutor execution = new FollowExecutor(restored, null,
                    () -> data.save(file, restored.json()), (id, message, sound) -> {});
            ReflectionHelpers.setField(service, "execution", execution);
            LeaderArchive.Update next = archive.capture(config.portfolio, "", opens(0, 1), START + 1000);
            assertEquals(1, next.added);
            syncBook(service, next.state);
            assertEquals(1, restored.waits().size());
            assertFalse(restored.waits().get(0).existing);
        } finally { controller.destroy(); }
    }

    @Test public void showsUnlistedContractWithoutHidingAnotherWaitingPrice() {
        long now = System.currentTimeMillis();
        AutoFollowService.WaitingPrices prices = new AutoFollowService.WaitingPrices(AutoFollowService.DEMO_WEB,
                Map.of("BTCUSDT", new Rules.Quote(new BigDecimal("30000"), new BigDecimal("30001"), BigDecimal.ONE, BigDecimal.ONE)),
                Map.of("MUUSDT", "测试网合约列表未收录 MUUSDT"), now, "");
        FollowBook.Entry unavailable = new FollowBook.Entry(new Rules.Signal("MUUSDT", Rules.Action.OPEN_LONG,
                now, new BigDecimal("100"), BigDecimal.ONE), true);
        FollowBook.Entry available = new FollowBook.Entry(signal(0, Rules.Action.OPEN_LONG, "0.1"), true);
        available.requestIssue = "调整杠杆请求失败 · 币安 API HTTP 400 / -4209";
        assertTrue(waitDescription(unavailable, prices).contains("测试网合约列表未收录 MUUSDT"));
        assertTrue(waitDescription(available, prices).contains("卖一 30001"));
        assertTrue(waitDescription(available, prices).contains(available.requestIssue));
    }

    @Test public void waitingStatusDistinguishesCheckRetryPauseWebsiteAndTheOriginalOrder() throws Exception {
        FollowBook.Entry entry = new FollowBook.Entry(signal(0, Rules.Action.OPEN_LONG, "0.1"));
        entry.checkFailures = 2;
        entry.checkFailedAt = START;
        entry.checkRetryAt = START + 6000;
        entry.requestIssue = "开仓检查（保证金余额）请求失败 · 币安请求超时";
        long now = START + 1000;
        assertTrue(MainActivity.waitRequestStatus(entry, null, true, true, true, now).contains("自动重试排队"));
        assertTrue(MainActivity.waitRequestStatus(entry, null, true, true, true, START + 6001).contains("下一轮"));
        assertTrue(MainActivity.waitRequestStatus(entry, null, false, true, true, now).contains("未运行"));
        assertTrue(MainActivity.waitRequestStatus(entry, null, true, false, true, now).contains("暂停"));
        assertTrue(MainActivity.waitRequestStatus(entry, null, true, true, false, now).contains("官网成交核对"));
        assertTrue(MainActivity.waitRequestStatus(entry, new JSONObject().put("entry", entry.id()), true, true, true, now)
                .contains("原委托号"));
    }

    @Test public void reviewCanUseTheSameTradingDayWhenTheEarlierStartIsDisabled() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 14);
        long stamp = day.atTime(22, 30).atZone(zone).toInstant().toEpochMilli();
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> false, false, false, stamp);
        observe(capture, poll, new JSONObject().put("phase", "list").put("datePicker", true)
                .put("dates", new org.json.JSONArray(List.of("2026-11-15", "2026-11-21"))).put("zone", zone.getId()));
        assertEquals(day.minusDays(2), poll.rangeStart);
        assertEquals(day, poll.rangeEnd);
        observe(capture, poll, new JSONObject().put("phase", "calendar").put("opened", true));
        assertEquals("START", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
        observe(capture, poll, new JSONObject().put("phase", "calendar").put("blocked", true));
        assertEquals(day, poll.rangeStart);
        assertEquals("START", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
        assertFalse(poll.unfilteredReview());
    }

    @Test public void reviewingAnOlderDayKeepsTheBeginningOfTheDisplayedRange() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 21);
        long stamp = day.atTime(14, 42, 25).atZone(zone).toInstant().toEpochMilli();
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> false, false, false, stamp);
        observe(capture, poll, new JSONObject().put("phase", "list").put("datePicker", true)
                .put("dates", new JSONArray(List.of("2026-11-18", "2026-11-24"))).put("zone", zone.getId()));
        assertEquals(day.minusDays(3), poll.rangeStart);
        assertEquals(day, poll.rangeEnd);
        assertEquals("END", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
    }

    @Test public void baselineContinuationRetriesAnUnappliedEndDateAndWaitsForRefreshedRows() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 20);
        java.time.LocalDate start = day.minusDays(3);
        JSONArray originalDates = new JSONArray(List.of(start.toString(), day.plusDays(3).toString()));
        JSONArray narrowedDates = new JSONArray(List.of(start.toString(), day.toString()));
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        poll.continuationFrom = day.atTime(12, 8).atZone(zone).toInstant().toEpochMilli();
        poll.baselineZone = zone;
        poll.baselineCutoff = start.atStartOfDay(zone).toInstant().toEpochMilli();
        ReflectionHelpers.setField(capture, "current", poll);
        poll.priorRangeText = reviewLine(day.plusDays(1), "22:00:00");
        JSONObject snapshot = new JSONObject().put("phase", "rows").put("datePicker", false)
                .put("zone", zone.getId()).put("dates", new JSONArray()).put("text", poll.priorRangeText);
        JSONObject selected = new JSONObject().put("phase", "calendar").put("clicked", true);

        observe(capture, poll, snapshot);
        assertNull(poll.requestedRange);
        assertTrue(poll.lastRows.isEmpty());
        assertFalse(poll.result.isDone());
        ReflectionHelpers.setField(poll, "requestedRange", ReflectionHelpers.callStaticMethod(
                WebsiteCapture.class, "baselineRange", ClassParameter.from(WebsiteCapture.Poll.class, poll)));
        observe(capture, poll, snapshot.put("dates", originalDates).put("datePicker", true));
        assertEquals("END", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
        observe(capture, poll, selected);
        observe(capture, poll, snapshot.put("dates", narrowedDates));
        assertTrue(poll.lastRows.isEmpty());
        poll.calendarSince = android.os.SystemClock.elapsedRealtime() - 2300;
        observe(capture, poll, snapshot.put("dates", originalDates));
        assertEquals(1, poll.rangeRetries);
        assertEquals("END", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
        observe(capture, poll, selected);

        observe(capture, poll, snapshot.put("dates", narrowedDates).put("text", reviewLine(day, "22:00:00")));
        assertEquals(1, poll.lastRows.size());
        assertFalse(poll.result.isDone());
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, snapshot);
        shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        observe(capture, poll, snapshot);
        assertTrue(poll.result.isDone());
        assertTrue(poll.result.join().historyComplete);
    }

    @Test public void switchingToAnArchivedLeaderStopsAtTheFirstPageThatConnectsToItsTail() throws Exception {
        String portfolio = "23324460";
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), portfolio, "");
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.now(zone).minusDays(2);
        String first = reviewLine(day, "12:01:00");
        String middle = reviewLine(day, "12:02:00");
        String tail = reviewLine(day, "12:03:00");
        List<Rules.Signal> prior = WebRecords.parse(tail + middle + first, day, day, zone);
        archive.capture(portfolio, "测试组合", prior, START, true, "原基线");
        LeaderArchive.State saved = archive.state(portfolio);
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> archive.connectsToTail(portfolio, saved, rows), false, true);
        poll.tryArchiveJoin = true;
        ReflectionHelpers.setField(capture, "current", poll);

        String latest = reviewLine(day, "12:05:00") + reviewLine(day, "12:04:00");
        JSONObject data = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true).put("text", latest);
        observe(capture, poll, data);
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, data);
        assertFalse(poll.result.isDone());

        poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        observe(capture, poll, data.put("text", latest + tail + middle).put("loading", true));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, data);
        assertFalse(poll.result.isDone());
        observe(capture, poll, data.put("loading", false).put("moreReady", true));
        WebsiteCapture.Read result = poll.result.getNow(null);
        assertNotNull(poll.parseIssue, result);
        assertTrue(result.joinedArchive);
        assertTrue(result.historyComplete);
        assertTrue(result.historyDetail.contains("2 页 · 已核对重合"));
        assertFalse(poll.triedRange);
        assertEquals(4, result.rows.size());

        LeaderArchive.Update joined = archive.capture(portfolio, "", result.rows, START + 3000,
                false, result.historyDetail, true, 1, true);
        assertEquals(2, joined.added);
        assertEquals(5, archive.count(portfolio));
        assertEquals(new BigDecimal("5"), joined.state.estimate.get("MUUSDT|LONG"));
        assertEquals(1, archive.verifiedSelection(portfolio));
    }

    @Test public void returnsFromArchivedOverlapWithoutReviewingAnOldPageEchoOrInvisiblePendingRows() throws Exception {
        String portfolio = "23324461";
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive archive = LeaderArchive.open(app);
        List<Rules.Signal> known = opens(0, 4);
        LeaderArchive.State saved = archive.capture(portfolio, "", known, START, true, "旧档案").state;
        Rules.Signal older = known.get(1), tail = known.get(3), pending = opens(5, 6).get(0);
        archive.queueReviews(portfolio, Map.of(older.time, List.of(older, older.withOccurrence(1)),
                pending.time, List.of(pending, pending.withOccurrence(1))));
        List<Rules.Signal> homepage = List.of(older, older.withOccurrence(1), known.get(2), tail);
        assertTrue(archive.connectsToTail(portfolio, saved, homepage));

        var controller = Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            Rules.Config config = new Rules.Config(portfolio, new LocalData(app).config().rules);
            ReflectionHelpers.setField(service, "initial", config);
            WebsiteCapture.Read read = new WebsiteCapture.Read("", homepage, null, null, false, "", false, "", START);
            WebsiteCapture.Read result = ReflectionHelpers.callInstanceMethod(service, "verifyWebsiteRows",
                    ClassParameter.from(LeaderArchive.State.class, saved),
                    ClassParameter.from(WebsiteCapture.Read.class, read), ClassParameter.from(boolean.class, true));
            assertEquals(known.subList(1, 4).stream().map(row -> row.id).toList(),
                    result.rows.stream().map(row -> row.id).toList());
            LeaderArchive.Update joined = archive.capture(portfolio, "", result.rows, START + 5000,
                    false, "首页接续", true, 1, true);
            assertEquals(0, joined.added);
            assertEquals(known.size(), archive.count(portfolio));
            assertNotNull(archive.nextReview(portfolio));

            long since = archive.reviewFromArchiveTail(portfolio, joined.state,
                    List.of(tail, tail.withOccurrence(1)));
            assertEquals(tail.time, since);
            archive.queueReviews(portfolio, Map.of(tail.time, List.of(tail, tail.withOccurrence(1))));
            assertEquals(tail.time, archive.nextReview(portfolio, since, tail.time, Set.of(tail.time)).stamp);
        } finally { controller.destroy(); }
    }

    @Test public void resetsCachedInitialChoiceForANewTradingSessionButKeepsItForAnInterruptedSession() throws Exception {
        String portfolio = "23324462";
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive archive = LeaderArchive.open(app);
        Rules.Config config = new Rules.Config(portfolio, Set.of("BTCUSDT"), false, BigDecimal.TEN, 5, BigDecimal.ONE);
        LeaderArchive.State rejoined = archive.capture(portfolio, "", opens(0, 2), START, true, "已衔接旧档案").state;
        FollowBook book = new FollowBook(config, "fixture");
        String key = "initial_choice_" + AutoFollowService.DEMO_WEB + "_" + portfolio + "_fixture_selection_2";
        data.prefs.edit().putBoolean(key, true).commit();
        var controller = Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "mode", AutoFollowService.DEMO_WEB);
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "book", book);
            restoreInitialChoice(service, false);
            assertFalse(book.initialChoiceDone);
            assertFalse(data.prefs.contains(key));
            assertFalse(applyChoice(service, rejoined));
            assertEquals(1, AutoFollowService.initialPositions().candidates.size());
            assertTrue(book.waits().isEmpty());
            book.initialChoiceDone = true;
            restoreInitialChoice(service, true);
            assertTrue(book.initialChoiceDone);
        } finally { controller.destroy(); }
    }

    private static void restoreInitialChoice(AutoFollowService service, boolean resume) {
        ReflectionHelpers.callInstanceMethod(service, "restoreInitialChoice",
                ClassParameter.from(String.class, "fixture"), ClassParameter.from(long.class, 2L),
                ClassParameter.from(boolean.class, resume));
    }

    @Test public void earlyArchiveJoinMustStillMatchTheConfirmedTailAtCommit() throws Exception {
        String portfolio = "23324459";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> prior = opens(0, 3);
        archive.capture(portfolio, "", prior, START, true, "已建档", false, 1);
        List<Rules.Signal> missingTail = List.of(prior.get(0), opens(4, 5).get(0));
        LeaderArchive.State saved = archive.state(portfolio);
        assertTrue(archive.hasConfirmedOverlap(portfolio, missingTail));
        assertFalse(archive.connectsToTail(portfolio, saved, missingTail));
        try {
            archive.capture(portfolio, "", missingTail, START + 1000, false, "衔接旧档案", true, 2, true);
            fail("不可跳过已确认的最后一笔");
        } catch (LeaderHistory.Gap expected) {
            assertEquals(1, archive.verifiedSelection(portfolio));
            assertEquals(saved.head, archive.state(portfolio).head);
            assertEquals(3, archive.count(portfolio));
        }
    }

    @Test public void sevenDayBaselineKeepsEveryBoundaryFillAndStopsBeforeTheRestOfHistory() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate newest = java.time.LocalDate.now(zone).minusDays(2);
        java.time.LocalDate boundary = newest.minusDays(7);
        String latest = reviewLine(newest, "12:08:00");
        String seam = reviewLine(boundary, "12:08:00");
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true).put("text", latest + seam);
        observe(capture, poll, view);
        shadowOf(Looper.getMainLooper()).idleFor(1400, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        assertFalse(poll.result.isDone());
        observe(capture, poll, view.put("text", latest + seam + seam + reviewLine(boundary, "12:07:59")));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        WebsiteCapture.Read result = poll.result.join();
        assertTrue(result.historyComplete);
        assertTrue(result.historyDetail.contains("7 天建档窗口"));
        assertEquals(3, result.rows.size());
        assertEquals(poll.baselineCutoff, result.rows.get(0).time);
        assertEquals(result.rows.get(0).fingerprint, result.rows.get(1).fingerprint);
        assertNotEquals(result.rows.get(0).id, result.rows.get(1).id);
    }

    @Test public void aNewTradeDuringPaginationDoesNotShiftTheFrozenBaseline() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.now(zone).minusDays(2);
        String newest = reviewLine(day, "12:08:00");
        String boundary = reviewLine(day.minusDays(7), "12:08:00");
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true).put("text", newest + boundary);
        observe(capture, poll, view);
        long pinned = poll.baselineNewest;
        observe(capture, poll, view.put("text", reviewLine(day, "12:30:00") + newest + boundary
                + reviewLine(day.minusDays(7), "12:07:59")));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        WebsiteCapture.Read result = poll.result.join();
        assertTrue(result.historyComplete);
        assertEquals(2, result.rows.size());
        assertEquals(pinned, result.rows.get(1).time);
        assertEquals(poll.baselineCutoff, result.rows.get(0).time);
        ReflectionHelpers.setField(capture, "pendingBaseline", result);
        assertSame(result, capture.fetch(null, rows -> true, true, true));
        capture.baselineAccepted();
        assertNull(ReflectionHelpers.getField(capture, "pendingBaseline"));
    }

    @Test public void reopensMissingDateControlsAndContinuesSeventyRetainedTradesAcrossTheDateSeam() throws Exception {
        String portfolio = "97003521";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), portfolio, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate latest = java.time.LocalDate.now(zone);
        java.time.LocalDate seam = latest.minusDays(3);
        java.time.LocalDate cutoff = latest.minusDays(7);
        StringBuilder retainedText = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            retainedText.append(reviewLine(i == 69 ? seam : latest.minusDays(i / 35),
                    String.format(java.util.Locale.ROOT, "12:%02d:00", 59 - i % 35)));
        }
        List<Rules.Signal> retained = WebRecords.parse(retainedText.toString(), cutoff, latest, zone);
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        poll.continuationFrom = retained.get(0).time;
        poll.baselineCutoff = retained.get(69).time - TimeUnit.DAYS.toMillis(7);
        poll.baselineZone = zone;
        ReflectionHelpers.setField(poll, "requestedRange", ReflectionHelpers.callStaticMethod(
                WebsiteCapture.class, "baselineRange", ClassParameter.from(WebsiteCapture.Poll.class, poll)));
        ReflectionHelpers.setField(capture, "current", poll);
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("text", retainedText.toString());
        poll.controlsSince = android.os.SystemClock.elapsedRealtime() - 8100;
        String stalledDocument = poll.document;
        observe(capture, poll, view);
        assertTrue(poll.reopenedDatePage);
        assertNotEquals(stalledDocument, poll.document);
        assertFalse(poll.result.isDone());
        assertTrue(poll.lastRows.isEmpty());

        observe(capture, poll, view.put("datePicker", true)
                .put("dates", new JSONArray(List.of(latest.minusDays(6).toString(), latest.toString()))));
        assertEquals("END", String.valueOf((Enum<?>) ReflectionHelpers.getField(poll, "selectingDate")));
        JSONObject selected = new JSONObject().put("phase", "calendar").put("clicked", true);
        observe(capture, poll, selected);
        observe(capture, poll, selected);
        view.put("dates", new JSONArray(List.of(cutoff.toString(), seam.toString())))
                .put("text", reviewLine(seam, "12:25:00") + reviewLine(seam.minusDays(1), "12:00:00"));
        observe(capture, poll, view.put("dateApplyPending", true));
        assertTrue(poll.lastRows.isEmpty());
        observe(capture, poll, new JSONObject().put("phase", "calendar-apply"));
        observe(capture, poll, view.put("dateApplyPending", false));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        WebsiteCapture.Read older = poll.result.join();
        assertTrue(older.historyComplete);

        long boundary = seam.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Rules.Signal> merged = LeaderHistory.joinBaseline(retained, older.rows, boundary);
        assertEquals(71, merged.size());
        assertEquals(retained.get(69).id, merged.get(70).id);
        archive.capture(portfolio, "", merged, System.currentTimeMillis(), true, "日期续读");
        assertEquals(71L, archive.count(portfolio));
        assertEquals(0L, archive.detectionCount(portfolio));
    }

    @Test public void recoveringAnOldArchiveCanRecheckTheDateAfterATransientEmptyPage() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate latest = java.time.LocalDate.now(zone);
        java.time.LocalDate start = latest.minusDays(7);
        String text = reviewLine(latest, "12:08:00");
        JSONArray dates = new JSONArray(List.of(start.toString(), latest.toString()));
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("datePicker", true).put("dates", dates).put("more", false).put("text", text);
        Rules.Signal anchor = WebRecords.parse(text, start, latest, zone).get(0);
        WebsiteCapture.Poll first = new WebsiteCapture.Poll(anchor, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", first);
        observe(capture, first, view);
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, first, view);
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, first, view);
        assertFalse(first.result.join().historyComplete);

        WebsiteCapture.Poll retry = new WebsiteCapture.Poll(null, rows -> true, false, true);
        retry.baselineZone = zone;
        retry.baselineCutoff = first.baselineCutoff;
        retry.continuationFrom = first.lastRows.get(0).time;
        retry.priorRangeText = text;
        ReflectionHelpers.setField(retry, "requestedRange", ReflectionHelpers.callStaticMethod(
                WebsiteCapture.class, "baselineRange", ClassParameter.from(WebsiteCapture.Poll.class, retry)));
        ReflectionHelpers.setField(capture, "current", retry);
        observe(capture, retry, view);
        assertEquals(start.plusDays(1), retry.rangeStart);
        JSONObject clicked = new JSONObject().put("phase", "calendar").put("clicked", true);
        observe(capture, retry, clicked);
        observe(capture, retry, clicked);
        JSONArray interim = new JSONArray(List.of(start.plusDays(1).toString(), latest.toString()));
        observe(capture, retry, view.put("dates", interim));
        assertEquals(start, retry.rangeStart);
        observe(capture, retry, clicked);
        observe(capture, retry, clicked);

        observe(capture, retry, view.put("dates", dates).put("phase", "empty"));
        shadowOf(Looper.getMainLooper()).idleFor(5100, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        assertFalse(retry.result.isDone());
        assertTrue(retry.rereadEmpty);
        assertTrue(retry.lastRows.isEmpty());
        observe(capture, retry, clicked);
        observe(capture, retry, view.put("phase", "rows"));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        assertTrue(retry.result.join().historyComplete);
        assertEquals(1, retry.result.join().rows.size());
    }

    @Test public void preservesFourConfirmedPagesAndMergesDateContinuationBeforeArchiving() throws Exception {
        String portfolio = "5305355976370339073";
        Application app = RuntimeEnvironment.getApplication();
        WebsiteCapture capture = new WebsiteCapture(app, portfolio, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        java.time.LocalDate seam = today.minusDays(3);
        java.time.LocalDate first = today.minusDays(6);
        StringBuilder visible = new StringBuilder();
        JSONObject snapshot = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true);
        for (int i = 0; i < 40; i++) {
            java.time.LocalDate day = i < 29 ? today.minusDays(i / 10) : seam;
            visible.append(reviewLine(day, java.time.LocalTime.of(23, 59 - i).toString() + ":00"));
            if ((i + 1) % 10 == 0) {
                if (i >= 10) poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
                observe(capture, poll, snapshot.put("text", visible.toString()));
            }
        }
        assertEquals(4, poll.pagination.pages());
        assertEquals(40, poll.lastRows.size());
        assertNull(poll.displayedRange);
        // Rendering the date controls does not change the transaction text.
        snapshot.put("dates", new JSONArray(List.of(first.toString(), today.toString()))).put("datePicker", true);
        observe(capture, poll, snapshot);
        assertNotNull(poll.displayedRange);
        for (int i = 0; i < 4; i++) {
            poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
            shadowOf(Looper.getMainLooper()).idleFor(7100, TimeUnit.MILLISECONDS);
            observe(capture, poll, snapshot);
        }
        assertFalse(poll.restartedPagination);
        assertTrue(poll.result.isDone());
        WebsiteCapture.Read retained = poll.result.join();
        assertFalse(retained.historyComplete);
        assertEquals(40, retained.rows.size());
        assertTrue(retained.historyDetail.contains("4 页 · 分页未完成 · 40 笔 · 7 次点击"));

        StringBuilder older = new StringBuilder(visible.substring(visible.indexOf(seam.toString())));
        for (int i = 0; i < 29; i++) older.append(reviewLine(seam.minusDays(1 + i / 10),
                java.time.LocalTime.of(21, 59 - i).toString() + ":00"));
        List<Rules.Signal> earlier = WebRecords.parse(older.toString(), first, seam, zone);
        long boundary = seam.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Rules.Signal> joined = LeaderHistory.joinBaseline(retained.rows, earlier, boundary);
        assertEquals(69, joined.size());
        assertEquals(retained.rows.get(39).id, joined.get(68).id);
        assertThrows(LeaderHistory.Gap.class, () -> LeaderHistory.joinBaseline(retained.rows,
                earlier.subList(0, earlier.size() - 1), boundary));
        LeaderArchive archive = LeaderArchive.open(app);
        assertEquals(0L, archive.count(portfolio));
        archive.capture(portfolio, "", joined, System.currentTimeMillis(), true, "日期续读已核对");
        assertTrue(archive.state(portfolio).historyComplete);
        assertEquals(69L, archive.count(portfolio));
        assertEquals(0L, archive.detectionCount(portfolio));
    }

    @Test public void checksAStalledFourthPagesBoundaryDayBeforeAddingSevenEarlierTrades() throws Exception {
        String portfolio = "5075281354358777856";
        Application app = RuntimeEnvironment.getApplication();
        WebsiteCapture capture = new WebsiteCapture(app, portfolio, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate latest = java.time.LocalDate.now(zone).minusDays(1);
        java.time.LocalDate seam = latest.minusDays(4);
        java.time.LocalDate first = latest.minusDays(7);
        WebsiteCapture.Poll initial = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", initial);
        JSONObject snapshot = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray(List.of(first.toString(), latest.toString())))
                .put("datePicker", true).put("more", true);
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            visible.append(reviewLine(i < 30 ? latest : seam, java.time.LocalTime.of(12, 59 - i) + ":00"));
            if ((i + 1) % 10 == 0) {
                if (i >= 10) initial.pagination.clicked(android.os.SystemClock.elapsedRealtime());
                observe(capture, initial, snapshot.put("text", visible.toString()));
            }
        }
        assertEquals(4, initial.pagination.pages());
        ReflectionHelpers.callInstanceMethod(capture, "timeout", ClassParameter.from(WebsiteCapture.Poll.class, initial));
        WebsiteCapture.Read retained = initial.result.join();
        assertFalse(retained.historyComplete);
        assertEquals(40, retained.rows.size());
        WebsiteCapture.BaselineProgress progress = new WebsiteCapture.BaselineProgress(initial, retained);

        String seamRows = visible.substring(visible.indexOf(seam.toString())) + reviewLine(seam, "08:00:00");
        WebsiteCapture.Poll boundary = observeArchivePartition(capture, initial, seam, seam, seamRows);
        assertTrue(progress.accept(boundary, boundary.result.join()));
        progress.verifiedBoundary = seam;
        assertEquals(41, progress.rows.size());
        assertThrows(LeaderHistory.Gap.class, () -> progress.acceptEarlier(boundary, boundary.result.join()));
        WebsiteCapture.Poll empty = observeArchivePartition(capture, initial, first, seam.minusDays(1), "");
        assertFalse(progress.acceptEarlier(empty, empty.result.join()));
        assertEquals(41, progress.rows.size());

        StringBuilder earlierRows = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            earlierRows.append(reviewLine(seam.minusDays(1), java.time.LocalTime.of(20, 30 - i) + ":00"));
        }
        earlierRows.append(reviewLine(first, "12:59:00")).append(reviewLine(first, "12:58:59"));
        WebsiteCapture.Poll earlier = observeArchivePartition(capture, initial, first, seam.minusDays(1),
                earlierRows.toString());
        assertTrue(earlier.cutoffReached);
        assertTrue(progress.acceptEarlier(earlier, earlier.result.join()));
        assertEquals(initial.baselineCutoff, progress.rows.get(0).time);
        assertEquals(initial.baselineNewest, progress.rows.get(46).time);
        assertEquals(47L, progress.rows.stream().map(row -> row.id).distinct().count());
        assertEquals(retained.rows.get(39).id, progress.rows.get(46).id);
        WebsiteCapture.Read complete = ReflectionHelpers.callInstanceMethod(capture, "finishBaseline",
                ClassParameter.from(WebsiteCapture.BaselineProgress.class, progress),
                ClassParameter.from(WebsiteCapture.Poll.class, initial));
        assertTrue(complete.historyDetail, complete.historyComplete);
        LeaderArchive archive = LeaderArchive.open(app);
        String freshArchive = "97180251";
        assertEquals(0L, archive.count(freshArchive));
        LeaderArchive.Update archived = archive.capture(freshArchive, "", complete.rows, System.currentTimeMillis(),
                complete.historyComplete, complete.historyDetail, false, 1);
        assertTrue(archived.state.historyDetail, archived.state.historyComplete);
        assertTrue(archive.state(freshArchive).historyComplete);
        assertEquals(47L, archive.count(freshArchive));
        assertEquals(0L, archive.detectionCount(freshArchive));
    }

    @Test public void baselineWebViewScriptWatchdogRetainsRowsAndRejectsALateResponse() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        WebsiteCapture capture = new WebsiteCapture(app, ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.now(zone);
        JSONObject rows = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true).put("text", reviewLine(day, "12:00:00"));
        observe(capture, poll, rows);
        java.util.concurrent.atomic.AtomicReference<android.webkit.ValueCallback<String>> response =
                new java.util.concurrent.atomic.AtomicReference<>();
        android.webkit.WebView stalled = new android.webkit.WebView(app) {
            @Override public void evaluateJavascript(String script, android.webkit.ValueCallback<String> callback) {
                assertTrue(response.compareAndSet(null, callback));
            }
        };
        ReflectionHelpers.setField(capture, "page", stalled);
        shadowOf(Looper.getMainLooper()).idleFor(8500, TimeUnit.MILLISECONDS);
        WebsiteCapture.Read partial = poll.result.join();
        assertFalse(partial.historyComplete);
        assertTrue(partial.historyDetail.contains("网页脚本未返回"));
        assertEquals(1, partial.rows.size());
        assertNull(ReflectionHelpers.getField(capture, "page"));
        response.get().onReceiveValue(JSONObject.quote(rows.put("text", "").toString()));
        assertSame(partial, poll.result.join());
    }

    private static WebsiteCapture.Poll observeArchivePartition(WebsiteCapture capture, WebsiteCapture.Poll initial,
            java.time.LocalDate start, java.time.LocalDate end, String text) throws Exception {
        WebsiteCapture.Poll partition = new WebsiteCapture.Poll(null, rows -> true, false, true);
        Class<?> dateRange = Class.forName(WebsiteCapture.class.getName() + "$DateRange");
        var constructor = dateRange.getDeclaredConstructor(java.time.LocalDate.class, java.time.LocalDate.class,
                java.time.ZoneId.class);
        constructor.setAccessible(true);
        ReflectionHelpers.setField(partition, "requestedRange", constructor.newInstance(start, end, initial.baselineZone));
        partition.continuationFrom = initial.lastRows.get(0).time;
        partition.baselineZone = initial.baselineZone;
        partition.baselineNewest = initial.baselineNewest;
        partition.baselineCutoff = initial.baselineCutoff;
        partition.triedRange = partition.rangeConfirmed = true;
        partition.rangeStart = start;
        partition.rangeEnd = end;
        ReflectionHelpers.setField(capture, "current", partition);
        partition.allowEmptyPartition = text.isEmpty();
        JSONObject snapshot = new JSONObject().put("phase", text.isEmpty() ? "empty" : "rows")
                .put("zone", partition.baselineZone.getId())
                .put("dates", new JSONArray(List.of(start.toString(), end.toString())))
                .put("more", false).put("text", text);
        observe(capture, partition, snapshot);
        shadowOf(Looper.getMainLooper()).idleFor(text.isEmpty() ? 5100 : 900, TimeUnit.MILLISECONDS);
        observe(capture, partition, snapshot);
        if (!partition.result.isDone()) {
            shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
            observe(capture, partition, snapshot);
        }
        assertTrue(partition.result.isDone());
        assertTrue(partition.result.join().historyComplete);
        return partition;
    }

    @Test public void upgradesVersionFourteenWithoutLosingConfirmedTradesOrReviewProof() throws Exception {
        String portfolio = "8175418";
        LeaderArchive archive = LeaderArchive.open(RuntimeEnvironment.getApplication());
        List<Rules.Signal> trades = opens(0, 3);
        archive.capture(portfolio, "", trades, START, true, "原基线");
        archive.saveAccountFill(portfolio, AutoFollowService.DEMO_WEB, "kept-v14", START + 1, "原成交");
        SQLiteDatabase old = archive.getWritableDatabase();
        restoreTradeSchemaWithoutInclusion(old);
        old.execSQL("ALTER TABLE trade_reviews RENAME TO newer_trade_reviews");
        old.execSQL("CREATE TABLE trade_reviews (portfolio TEXT NOT NULL,stamp INTEGER NOT NULL,sample TEXT NOT NULL,"
                + "checked TEXT,legacy INTEGER NOT NULL DEFAULT 0,baseline_checked INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY(portfolio,stamp))");
        old.execSQL("INSERT INTO trade_reviews SELECT portfolio,stamp,sample,checked,legacy,baseline_checked FROM newer_trade_reviews");
        old.execSQL("DROP TABLE newer_trade_reviews");
        old.setVersion(14);
        archive.close();
        assertEquals(3L, archive.count(portfolio));
        assertEquals(1L, archive.accountFillCount(portfolio));
        assertTrue(archive.provisionalReviews(portfolio).isEmpty());
        assertEquals(LeaderArchive.DB_VERSION, archive.getReadableDatabase().getVersion());
    }

    @Test public void provisionalPageCopySurvivesRestartAndCanBeRestoredWithoutNewSignals() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive archive = LeaderArchive.open(app);
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
        archive.close();
        archive = LeaderArchive.open(app);
        assertEquals(3L, archive.count(unconfirmed));
        assertEquals(1, archive.provisionalReviews(unconfirmed).size());
        assertTrue(archive.resolveProvisionalReview(unconfirmed, archive.provisionalReviews(unconfirmed).get(0), counted, false));
        assertEquals(3L, archive.count(unconfirmed));
        assertTrue(archive.provisionalReviews(unconfirmed).isEmpty());
    }

    @Test public void missingDateControlsCanConfirmAnOlderSecondButCannotProveAnOverlappingCopy() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.now(zone).minusDays(1);
        long stamp = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli();
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> false, false, false, stamp);
        ReflectionHelpers.setField(capture, "current", poll);
        String page = reviewLine(day, "10:04:00");
        JSONObject data = new JSONObject().put("phase", "rows").put("dates", new org.json.JSONArray())
                .put("zone", zone.getId()).put("more", true).put("text", page);
        poll.controlsSince = android.os.SystemClock.elapsedRealtime() - 3500;
        observe(capture, poll, data);
        assertTrue(poll.unfilteredReview());
        assertFalse(poll.result.isDone());
        observe(capture, poll, data);
        assertEquals(1, poll.pagination.pages());
        poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        page += reviewLine(day, "10:03:00");
        observe(capture, poll, data.put("text", page));
        assertEquals(2, poll.pagination.pages());
        poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        page += reviewLine(day, "10:00:00") + reviewLine(day, "09:59:00");
        observe(capture, poll, data.put("text", page));
        assertFalse(poll.result.isDone());
        poll.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1000;
        observe(capture, poll, data);
        assertEquals(1, TradeReview.at(poll.result.join().rows, stamp).size());

        WebsiteCapture.Poll split = new WebsiteCapture.Poll(null, rows -> false, false, false, stamp);
        ReflectionHelpers.setField(capture, "current", split);
        split.reviewFallback = "fixture";
        String splitPage = reviewLine(day, "10:04:00") + reviewLine(day, "10:00:00");
        observe(capture, split, data.put("more", true).put("text", splitPage));
        split.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        split.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1000;
        observe(capture, split, data.put("more", false));
        assertFalse(split.result.isDone());
        observe(capture, split, data.put("text", splitPage + reviewLine(day, "10:00:00") + reviewLine(day, "09:59:00")));
        split.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1000;
        observe(capture, split, data);
        assertTrue(split.result.isCompletedExceptionally());

        WebsiteCapture.Poll isolated = new WebsiteCapture.Poll(null, rows -> false, false, false, stamp);
        ReflectionHelpers.setField(capture, "current", isolated);
        isolated.reviewFallback = "fixture";
        observe(capture, isolated, data.put("text", splitPage + reviewLine(day, "10:00:00") + reviewLine(day, "09:59:00")));
        isolated.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1000;
        observe(capture, isolated, data);
        assertEquals(2, TradeReview.at(isolated.result.join().rows, stamp).size());
    }

    @Test public void stalledBaselineContinuesTheSameDocumentWhenTheNextPageArrivesWithoutDateControls() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll original = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", original);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate day = java.time.LocalDate.now(zone);
        StringBuilder text = new StringBuilder();
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("dates", new JSONArray()).put("more", true);
        for (int i = 0; i < 40; i++) {
            text.append(reviewLine(day, String.format(java.util.Locale.ROOT, "12:%02d:00", 59 - i)));
            if ((i + 1) % 10 == 0) {
                if (i >= 10) original.pagination.clicked(android.os.SystemClock.elapsedRealtime());
                observe(capture, original, view.put("text", text.toString()));
            }
        }
        assertEquals(4, original.pagination.pages());
        original.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        ReflectionHelpers.callInstanceMethod(capture, "timeout", ClassParameter.from(WebsiteCapture.Poll.class, original));
        assertFalse(original.result.join().historyComplete);
        assertEquals(40, original.result.join().rows.size());

        WebsiteCapture.Poll retry = original.resumeBaseline();
        assertEquals(original.document, retry.resumeDocument);
        assertEquals(4, retry.pagination.pages());
        ReflectionHelpers.setField(capture, "current", retry);
        observe(capture, retry, view.put("more", false));
        shadowOf(Looper.getMainLooper()).idleFor(2500, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        assertFalse(retry.result.isDone());
        assertEquals(40, retry.lastRows.size());

        for (int i = 0; i < 10; i++) {
            text.append(reviewLine(day, String.format(java.util.Locale.ROOT, "12:%02d:00", 19 - i)));
        }
        observe(capture, retry, view.put("text", text.toString()));
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, retry, view);
        assertTrue(retry.result.join().historyComplete);
        assertFalse(retry.triedRange);
        assertEquals(50, retry.result.join().rows.size());
        assertEquals(5, retry.pagination.pages());
        assertEquals(original.snapshotAt, retry.result.join().snapshotAt);
    }

    @Test public void initialArchiveUsesFortyVisibleTradesWhenTheFifthPageButtonStaysGone() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate newest = java.time.LocalDate.now(zone);
        StringBuilder text = new StringBuilder();
        JSONObject view = new JSONObject().put("phase", "rows").put("zone", zone.getId())
                .put("datePicker", true).put("dates", new JSONArray(List.of(newest.minusDays(3).toString(), newest.toString())))
                .put("more", true);
        for (int i = 0; i < 40; i++) {
            text.append(reviewLine(newest.minusDays(i / 10), java.time.LocalTime.of(12, 59 - i) + ":00"));
            if ((i + 1) % 10 == 0) {
                if (i >= 10) poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
                observe(capture, poll, view.put("text", text.toString()));
            }
        }
        poll.pagination.clicked(android.os.SystemClock.elapsedRealtime());
        observe(capture, poll, view.put("more", false).put("loading", true));
        shadowOf(Looper.getMainLooper()).idleFor(2200, TimeUnit.MILLISECONDS);
        observe(capture, poll, view.put("phase", "empty").put("loading", false));
        assertFalse(poll.result.isDone());
        assertEquals(40, poll.lastRows.size());
        shadowOf(Looper.getMainLooper()).idleFor(5000, TimeUnit.MILLISECONDS);
        observe(capture, poll, view.put("phase", "rows"));
        assertFalse(poll.result.isDone());
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, poll, view);
        WebsiteCapture.Read complete = poll.result.join();
        assertTrue(complete.historyComplete);
        assertFalse(poll.triedRange);
        assertEquals(4, poll.pagination.pages());
        assertEquals(40, complete.rows.size());
        assertEquals(40L, complete.rows.stream().map(row -> row.id).distinct().count());
    }

    @Test public void initialArchiveRereadsAnEmptyPageBeforeRecordingItsResult() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, false, true);
        ReflectionHelpers.setField(capture, "current", poll);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        JSONObject snapshot = new JSONObject().put("phase", "empty").put("name", "测试组合")
                .put("zone", zone.getId()).put("dates", new JSONArray());
        String firstDocument = poll.document;
        observe(capture, poll, snapshot);
        shadowOf(Looper.getMainLooper()).idleFor(3800, TimeUnit.MILLISECONDS);
        observe(capture, poll, snapshot);
        assertTrue(poll.rereadEmpty);
        assertNotEquals(firstDocument, poll.document);
        assertFalse(poll.result.isDone());

        snapshot.put("phase", "rows").put("text", reviewLine(java.time.LocalDate.now(zone), "12:00:00"));
        observe(capture, poll, snapshot);
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
        observe(capture, poll, snapshot);
        shadowOf(Looper.getMainLooper()).idleFor(1300, TimeUnit.MILLISECONDS);
        observe(capture, poll, snapshot);
        assertTrue(poll.result.join().historyComplete);
        assertEquals(1, poll.result.join().rows.size());
    }

    @Test public void repeatedBaselineStallsOfferManualRetryWithoutArchivingPartialRows() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive db = LeaderArchive.open(app);
        String portfolio = "5305355976370339073";
        Rules.Config config = new Rules.Config(portfolio, data.config().rules);
        var controller = Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "store", data);
            ReflectionHelpers.setField(service, "initial", config);
            ReflectionHelpers.setField(service, "archive", db);
            ReflectionHelpers.setField(service, "book", new FollowBook(config, ""));
            data.prefs.edit().putString("auto_web_access", WebsiteCapture.Access.REGION.name()).apply();
            for (int round = 0; round < 4; round++) {
                WebsiteCapture.Read partial = new WebsiteCapture.Read("测试组合", opens(0, 40 + round),
                        null, null, false, "第 5 页未返回更早记录", false, "", 10);
                assertThrows(RuntimeException.class, () -> passCapture(service, db.state(portfolio), partial));
                assertEquals(round == 3, data.prefs.getBoolean("auto_baseline_waiting", false));
            }
            assertEquals(0L, db.count(portfolio));
            assertFalse(db.state(portfolio).historyComplete);
            assertFalse(data.prefs.contains("auto_web_access"));
            assertTrue(db.state(portfolio).historyDetail.contains("重试官网读取"));
        } finally {
            controller.destroy();
        }
    }

    @Test public void opensTheBinancePortfolioInsideTheAppEvenWithoutAMountedReader() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(WebsiteCapture.class, "mounted", null);
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            android.widget.Button button = ReflectionHelpers.getField(activity, "openWebsite");
            assertTrue(button.isEnabled());
            button.performClick();
            AlertDialog dialog = ReflectionHelpers.getField(activity, "websitePrompt");
            assertTrue(dialog.isShowing());
            android.webkit.WebView preview = websiteView(dialog.getWindow().getDecorView());
            assertNotNull(preview);
            assertEquals(WebsiteCapture.portfolioUrl(new LocalData(app).config().portfolio), preview.getUrl());
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(WebsiteCapture.previewAddress(android.net.Uri.parse("https://www.binance.com/zh-CN")));
            assertFalse(WebsiteCapture.previewAddress(android.net.Uri.parse("intent://example.com")));
            dialog.dismiss();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(preview.getParent());
        }
    }

    private static android.webkit.WebView websiteView(View root) {
        if (root instanceof android.webkit.WebView) return (android.webkit.WebView) root;
        if (root instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) root;
            for (int i = 0; i < children.getChildCount(); i++) {
                android.webkit.WebView found = websiteView(children.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void manualBaselinePromptMovesTheSameWebViewAndOnlyForItsPortfolio() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        WebsiteCapture capture = new WebsiteCapture(app, ID, "");
        android.webkit.WebView view = new android.webkit.WebView(app);
        android.widget.FrameLayout normal = new android.widget.FrameLayout(app);
        android.widget.FrameLayout prompt = new android.widget.FrameLayout(app);
        String url = WebsiteCapture.portfolioUrl(ID);
        view.loadUrl(url);
        ReflectionHelpers.setField(capture, "page", view);
        ReflectionHelpers.setField(capture, "document", "baseline-document");
        ReflectionHelpers.setStaticField(WebsiteCapture.class, "mounted", capture);
        try {
            WebsiteCapture.attachViewport(normal);
            assertFalse(WebsiteCapture.attachBaselinePrompt(OTHER, prompt));
            assertSame(view, normal.getChildAt(0));
            assertTrue(WebsiteCapture.attachBaselinePrompt(ID, prompt));
            assertSame(view, prompt.getChildAt(0));
            assertEquals(url, view.getUrl());
            assertEquals("baseline-document", ReflectionHelpers.getField(capture, "manualBaselineDocument"));
            WebsiteCapture.detachRegionPrompt(prompt);
            assertSame(view, normal.getChildAt(0));
            assertEquals(url, view.getUrl());
        } finally {
            WebsiteCapture.detachViewport(normal);
            capture.close();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void savingProfileNeverParsesTransientTradeRows() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> true, true, false);
        ReflectionHelpers.setField(capture, "current", poll);
        observe(capture, poll, new JSONObject().put("phase", "rows").put("text", "页面正在变化"));
        assertFalse(poll.result.isDone());
        assertTrue(poll.parseIssue.isEmpty());
        observe(capture, poll, new JSONObject().put("phase", "profile").put("name", "新组合"));
        assertEquals("新组合", poll.result.join().name);
    }

    private static void observe(WebsiteCapture capture, WebsiteCapture.Poll poll, JSONObject data) {
        ReflectionHelpers.callInstanceMethod(capture, "observe", ClassParameter.from(WebsiteCapture.Poll.class, poll),
                ClassParameter.from(JSONObject.class, data));
    }

    private static String reviewLine(java.time.LocalDate date, String time) {
        return date + " " + time + "\n开多\n以均价为100USDT 买入开多MUUSDT永续合约，成交数量为 1MU。\n";
    }

    @Test public void displaysRunStatsAndPagesLongRunAndArchiveLists() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive db = LeaderArchive.open(app);
        db.capture(ID, "测试组合", opens(0, 10), START);
        db.capture(ID, "", opens(5, 25), START + 1);
        for (int i = 0; i < 13; i++) db.saveAccountFill(ID, AutoFollowService.REAL, "fill-" + i, START + i, "成交 " + i);
        FollowBook book = new FollowBook(data.config(), "test");
        book.trackedMillis = 754000;
        book.realizedPnl = new BigDecimal("3");
        book.leg("BTCUSDT", true).owned = new BigDecimal("0.2");
        for (int i = 0; i < 15; i++) {
            book.log(String.format(java.util.Locale.ROOT, "条目 %02d\n成交数量与价格\n订单核对信息", i));
        }
        data.save(AutoFollowService.file(AutoFollowService.REAL), book.json());
        data.prefs.edit().putString("auto_last_mode", AutoFollowService.REAL).commit();
        FuturesApi.Position position = new FuturesApi.Position();
        position.longs = new BigDecimal("0.2");
        position.longUnrealized = new BigDecimal("1.5");
        AutoFollowService.publishPositions(FuturesApi.LIVE, Map.of("BTCUSDT", position));
        AutoFollowService.publishWallet(FuturesApi.LIVE, new FuturesApi.Wallet(new BigDecimal("987.65"), new BigDecimal("765")));

        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            assertNotNull(text(root, "00:12:34"));
            text(root, "显示金额").performClick();
            TextView totalLabel = text(root, "当前总盈亏");
            TextView totalValue = text(root, "4.5 USDT");
            assertNotNull(totalValue);
            assertTrue(totalValue.getTextSize() > totalLabel.getTextSize());
            assertNotEquals(totalValue.getCurrentTextColor(), totalLabel.getCurrentTextColor());
            assertNotNull(text(root, "987.65"));
            assertNotNull(text(root, "实盘跟单 · 按点位跟随"));
            FuturesApi.Position refreshed = new FuturesApi.Position();
            refreshed.longs = position.longs;
            refreshed.longUnrealized = new BigDecimal("2");
            AutoFollowService.publishPositions(FuturesApi.LIVE, Map.of("BTCUSDT", refreshed));
            AutoFollowService.publishWallet(FuturesApi.LIVE, new FuturesApi.Wallet(new BigDecimal("990"), new BigDecimal("765")));
            app.sendBroadcast(new Intent(AutoFollowService.ACCOUNT_CHANGED).setPackage(app.getPackageName()));
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(text(root, "5 USDT"));
            assertNotNull(text(root, "浮盈亏 2 USDT"));
            assertNotNull(text(root, "990"));
            View logs = (View) text(root, "自动运行记录").getParent();
            TextView row = text(logs, "条目 14");
            assertNotNull(row);
            Ui.LimitedScroll scroll = (Ui.LimitedScroll) row.getParent().getParent();
            scroll.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(app, 300), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue(scroll.isVerticalScrollBarEnabled());
            assertTrue(scroll.getMeasuredHeight() <= Ui.dp(app, 240));
            assertTrue(scroll.getChildAt(0).getMeasuredHeight() > scroll.getMeasuredHeight());
            assertNotNull(text(logs, "第 1 / 3 页"));
            assertFalse(text(logs, "上一页").isEnabled());
            text(logs, "下一页").performClick();
            assertNotNull(text(logs, "第 2 / 3 页"));
            assertNotNull(text(logs, "条目 08"));
            assertNull(text(logs, "条目 14"));
            text(logs, "上一页").performClick();
            assertNotNull(text(logs, "条目 14"));

            text(root, "成交记录").performClick();
            assertNotNull(text(root, "第 1 / 3 页"));
            text(root, "下一页").performClick();
            assertNotNull(text(root, "显示 13–24"));
            text(root, "下一页").performClick();
            assertNotNull(text(root, "第 3 / 3 页"));
            assertFalse(text(root, "下一页").isEnabled());
            text(root, "我的成交").performClick();
            assertNotNull(text(root, "第 1 / 2 页"));
            text(root, "下一页").performClick();
            assertNotNull(text(root, "第 2 / 2 页"));
            assertNotNull(text(root, "成交 0"));
        }
    }

    @Test public void showsOriginalDetectionTimeAndUpdatesClockWithoutWebsiteEvents() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        String portfolio = "91919191";
        data.saveConfig(new Rules.Config(portfolio, data.config().rules));
        Rules.Signal base = signal(0, Rules.Action.OPEN_LONG, "0.1");
        Rules.Signal addition = signal(1, Rules.Action.OPEN_LONG, "0.1");
        LeaderArchive archive = LeaderArchive.open(app);
        archive.capture(portfolio, "测试组合", List.of(base), START);
        long detectedAt = START + 7500;
        archive.capture(portfolio, "", List.of(base, addition), detectedAt);
        LeaderArchive.Record record = archive.after(portfolio, 1, 2).get(0);
        assertEquals(detectedAt, record.detectedAt);
        assertEquals(addition.time, record.signal.time);
        android.os.SystemClock.setCurrentTimeMillis(START + 20000);
        FollowBook book = new FollowBook(data.config(), "test");
        book.portfolioSelection = data.portfolioSelection();
        book.trackingSince = System.currentTimeMillis() - 3000;
        book.apply(base, false);
        book.acceptNew(List.of(record.signal), false, Map.of(record.signal.id, record.detectedAt), 0);
        String tradeAndDetection = "成交 " + TradeNotice.fullTime(addition.time)
                + " · 检测 " + TradeNotice.fullTime(detectedAt);
        assertTrue(book.detections.getString(0), book.detections.getString(0).contains(tradeAndDetection));
        data.save(AutoFollowService.file(AutoFollowService.WATCH), book.json());
        data.prefs.edit().remove("auto_active").putString("auto_portfolio", portfolio)
                .putString("auto_last_mode", AutoFollowService.WATCH).commit();
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            View detectionCard = (View) text(root, "检测到的新增操作").getParent();
            TextView description = text(detectionCard, "BTCUSDT");
            assertNotNull(description);
            assertTrue(description.getText().toString(), description.getText().toString().contains(tradeAndDetection));
            Ui.Overview overview = ReflectionHelpers.getField(screen.get(), "runStats");
            TextView stats = ReflectionHelpers.getField(overview, "time");
            String before = stats.getText().toString();
            FollowBook running = ReflectionHelpers.getField(screen.get(), "displayedBook");
            running.trackedMillis += 2000;
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
            assertNotEquals(before, stats.getText().toString());
        }
        assertEquals(600, AutoFollowService.nextAccountDelay(400));
        assertEquals(800, AutoFollowService.nextAccountDelay(1200));
    }

    @Test public void newWebFollowAsksForStrategyAndSendsSelectedMode() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        String portfolio = "73737373";
        data.saveConfig(new Rules.Config(portfolio, data.config().rules));
        data.prefs.edit().remove("auto_active").putString("credentials_" + FuturesApi.DEMO, "fixture").commit();
        LeaderArchive.open(app).saveName(portfolio, "测试组合");
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            text(root, "用官网成交跟单到测试网").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View prompt = dialog.getWindow().getDecorView();
            RadioButton points = (RadioButton) text(prompt, "按点位跟随");
            RadioButton copy = (RadioButton) text(prompt, "复制仓位比例");
            assertTrue(points.isChecked());
            copy.performClick();
            assertTrue(copy.isChecked());
            assertFalse(points.isChecked());
            EditText factor = input(prompt, "复制系数");
            assertNotNull(factor);
            assertTrue(factor.isShown());
            assertEquals("1", factor.getText().toString());
            factor.setText("0");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(factor.getError());
            factor.setText("0.5");
            EditText limit = input(prompt, "调仓最大不利价差（%）");
            assertNotNull(limit);
            limit.setText("0.75");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent started = shadowOf(app).getNextStartedService();
            assertNotNull(started);
            assertEquals(AutoFollowService.DEMO_WEB, started.getAction());
            assertEquals(Rules.Strategy.EXPOSURE.name(), started.getStringExtra(AutoFollowService.EXTRA_STRATEGY));
            assertEquals("0.5", started.getStringExtra(AutoFollowService.EXTRA_COPY_FACTOR));
            assertEquals("0.75", started.getStringExtra(AutoFollowService.EXTRA_COPY_SLIPPAGE));
        } finally {
            AutoFollowService.reserveStart(false);
        }
    }

    @Test public void editsTheActiveCopyFactorAndQueuesRepricingUntilThereIsAFreshWebsiteSnapshot() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        String mode = AutoFollowService.DEMO_WEB;
        String file = AutoFollowService.file(mode);
        Rules.Config config = new Rules.Config("42500001", data.config().rules);
        data.saveConfig(config);
        long selection = data.portfolioSelection();
        LeaderArchive archive = LeaderArchive.open(app);
        Rules.Signal opening = signal(0, Rules.Action.OPEN_LONG, "1");
        LeaderArchive.State baseline = archive.capture(config.portfolio, "测试组合",
                List.of(opening), START, true, "完成", false, selection).state;
        assertEquals(1, baseline.head);
        FollowBook book = new FollowBook(config, "fixture", Rules.Strategy.EXPOSURE);
        book.apply(opening, true);
        book.baselineReady = true;
        book.archiveCursor = baseline.head;
        book.portfolioSelection = selection;
        book.leg("BTCUSDT", true).owned = book.leg("BTCUSDT", true).target = BigDecimal.ONE;
        book.leg("BTCUSDT", true).entries.clear();
        data.save(file, book.json());
        data.prefs.edit().putString("auto_active", mode).putString("auto_last_mode", mode)
                .putString("auto_portfolio", config.portfolio).putLong("auto_portfolio_selection", selection).commit();
        ReflectionHelpers.setStaticField(AutoFollowService.class, "runningMode", mode);
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            TextView edit = text(root, "调整复制系数");
            assertTrue(edit.isShown());
            edit.performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            EditText factor = input(dialog.getWindow().getDecorView(), "复制系数");
            factor.setText("2");
            input(dialog.getWindow().getDecorView(), "调仓最大不利价差（%）").setText("0.8");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Intent requested = shadowOf(app).getNextStartedService();
            assertEquals(AutoFollowService.COPY_FACTOR, requested.getAction());
            assertEquals(config.portfolio, requested.getStringExtra("portfolio"));
            org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                    Robolectric.buildService(AutoFollowService.class).create();
            try {
                AutoFollowService service = controller.get();
                ReflectionHelpers.setField(service, "mode", mode);
                ReflectionHelpers.setField(service, "book", book);
                ReflectionHelpers.setField(service, "file", file);
                service.onStartCommand(requested, 0, 1);
                ExecutorService worker = ReflectionHelpers.getField(service, "accountJournal");
                worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
                FollowBook saved = FollowBook.restore(data.load(file));
                assertEquals(new BigDecimal("2"), saved.copyFactor);
                assertEquals(new BigDecimal("0.8"), saved.copySlippage);
                assertTrue(saved.copyReplanPending);
                assertNull(saved.leg("BTCUSDT", true).adjustment);
                assertNull(saved.pending);
                assertTrue(saved.waits().isEmpty());

                FuturesApi.Position actual = new FuturesApi.Position();
                actual.longs = BigDecimal.ONE;
                var accountReads = new java.util.concurrent.atomic.AtomicInteger();
                FollowExecutor.Exchange exchange = (FollowExecutor.Exchange) java.lang.reflect.Proxy.newProxyInstance(
                        FollowExecutor.Exchange.class.getClassLoader(), new Class<?>[]{FollowExecutor.Exchange.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("positions")) {
                                accountReads.incrementAndGet();
                                return Map.of("BTCUSDT", actual);
                            }
                            throw new AssertionError(method.getName());
                        });
                ReflectionHelpers.setField(service, "book", saved);
                ReflectionHelpers.setField(service, "initial", config);
                ReflectionHelpers.setField(service, "connectivity", null);
                ReflectionHelpers.setField(service, "execution", new FollowExecutor(saved, exchange,
                        () -> data.save(file, saved.json()), (id, text, sound) -> {}));
                WebsiteCapture.Read unchanged = new WebsiteCapture.Read("测试组合", List.of(opening),
                        null, null, true, "完成", false, "", 10);
                ReflectionHelpers.callInstanceMethod(service, "acceptCapture",
                        ClassParameter.from(LeaderArchive.State.class, baseline),
                        ClassParameter.from(WebsiteCapture.Read.class, unchanged),
                        ClassParameter.from(long.class, android.os.SystemClock.elapsedRealtime()),
                        ClassParameter.from(long.class, START + 5000L));

                FollowBook recovered = FollowBook.restore(data.load(file));
                assertEquals(1, accountReads.get());
                assertEquals(baseline.head, recovered.archiveCursor);
                assertFalse(recovered.copyReplanPending);
                assertTrue(recovered.leg("BTCUSDT", true).adjustment.fromSetting());
                assertNull(recovered.pending);
            } finally { controller.destroy(); }
        } finally {
            ReflectionHelpers.setStaticField(AutoFollowService.class, "runningMode", null);
            data.prefs.edit().remove("auto_active").commit();
        }
    }

    @Test public void bottomNavigationShowsSettingsAndTheOverviewMasksBothValuesByDefault() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive.open(app).saveName(data.config().portfolio, "测试组合");
        AutoFollowService.publishWallet(FuturesApi.LIVE, new FuturesApi.Wallet(new BigDecimal("123.45"), BigDecimal.TEN));
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            Ui.Overview overview = ReflectionHelpers.getField(screen.get(), "runStats");
            TextView pnl = ReflectionHelpers.getField(overview, "pnl"), wallet = ReflectionHelpers.getField(overview, "wallet");
            assertEquals("****", pnl.getText().toString());
            assertEquals("****", wallet.getText().toString());
            text(root, "显示金额").performClick();
            assertEquals("123.45", wallet.getText().toString());
            text(root, "隐藏金额").performClick();
            assertEquals("****", wallet.getText().toString());
            android.widget.Button nav = navigation(root, "设置");
            assertNotNull(nav.getCompoundDrawables()[1]);
            nav.performClick();
            assertNotNull(text(root, "跟单规则"));
            assertNotNull(text(root, "币安 U 本位合约账户"));
            assertNull(text(root, "测试网委托演练"));
            assertNotNull(text(root, "在系统应用信息中允许自启动"));
            navigation(root, "成交记录").performClick();
            assertNotNull(text(root, "带单保证金余额历史"));
            text(root, "我的成交").performClick();
            assertNull(text(root, "带单保证金余额历史"));
        }
    }

    @Test @Config(sdk = {34, 35, 36, 37}, qualifiers = "w320dp-h640dp")
    public void donationCopiesEachValueAndSystemInsetsKeepBothNavigationAreasClear() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LeaderArchive.open(app).saveName(new LocalData(app).config().portfolio, "测试组合");
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            View surface = ReflectionHelpers.getField(activity, "surface");
            surface.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 24, 0, 30))
                    .setInsets(android.view.WindowInsets.Type.displayCutout(), android.graphics.Insets.of(7, 41, 0, 0))
                    .build());
            assertEquals(7, surface.getPaddingLeft());
            assertEquals(41, surface.getPaddingTop());
            assertEquals(30, surface.getPaddingBottom());

            View root = activity.getWindow().getDecorView();
            assertTrue(text(root, "捐赠").performClick());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View popup = dialog.getWindow().getDecorView();
            assertNotNull(text(popup, "币安转账"));
            assertNotNull(text(popup, "BNB Chain · USDT"));
            assertNotNull(text(popup, "如果这款工具有帮到你，欢迎捐赠，多少都不嫌弃，你的支持是我更新的动力。"));
            android.content.ClipboardManager clipboard = activity.getSystemService(android.content.ClipboardManager.class);
            assertTrue(text(popup, "复制 UID").performClick());
            assertEquals("1218112316", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertTrue(text(popup, "复制地址").performClick());
            assertEquals("0x46CE8C06265b4d7667AA2cb693B71193f9F0C6ae",
                    clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            dialog.dismiss();
            navigation(root, "设置").performClick();
            assertNull(text(root, "捐赠"));
            assertNotNull(text(root, "打开通知设置"));
            assertNotNull(text(root, "检查电池优化"));
        }
    }

    @Test @Config(sdk = {33, 37}, qualifiers = "w320dp-h640dp")
    public void introCoversTheAppForThreeSecondsAndThenOffersTheDisclaimerOnce() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData local = new LocalData(app);
        local.prefs.edit().remove("last_intro_version").remove("usage_notice_seen").commit();
        LeaderArchive.open(app).saveName(local.config().portfolio, "测试带单员");
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            View root = screen.get().getWindow().getDecorView();
            View content = ReflectionHelpers.getField(activity, "mainContent");
            View intro = ReflectionHelpers.getField(activity, "introCover");
            android.widget.FrameLayout viewport = ReflectionHelpers.getField(activity, "browserViewport");
            android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) intro.getBackground();
            for (int color : background.getColors()) assertEquals(255, android.graphics.Color.alpha(color));
            assertEquals(255, viewport.getForeground().getAlpha());
            assertEquals(View.INVISIBLE, content.getVisibility());
            assertNotNull(text(root, "场外跟单，无需求人。"));
            long until = ReflectionHelpers.getField(activity, "introEndsAt");
            long remaining = until - android.os.SystemClock.uptimeMillis();
            assertTrue(remaining > 2600 && remaining <= 3000);
            shadowOf(Looper.getMainLooper()).idleFor(remaining - 1, TimeUnit.MILLISECONDS);
            assertNotNull(text(root, "场外跟单，无需求人。"));
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
            assertEquals(View.VISIBLE, content.getVisibility());
            assertEquals(255, viewport.getForeground().getAlpha());
            assertNull(text(root, "场外跟单，无需求人。"));
            AlertDialog warning = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(warning.isShowing());
            assertNotNull(text(warning.getWindow().getDecorView(), "请勿用于商业以及违法用途。"));
            assertEquals("我知道了", warning.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            warning.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent info = shadowOf(activity).getNextStartedActivity();
            assertEquals(InfoActivity.class.getName(), info.getComponent().getClassName());
            assertTrue(local.prefs.getBoolean("usage_notice_seen", false));
            assertEquals(BuildConfig.VERSION_NAME, local.prefs.getString("last_intro_version", ""));
            try (org.robolectric.android.controller.ActivityController<InfoActivity> page =
                    Robolectric.buildActivity(InfoActivity.class, info).setup()) {
                View legal = page.get().getWindow().getDecorView();
                assertNotNull(text(legal, "地区限制与使用资格"));
                assertNotNull(text(legal, "交易风险与损失承担"));
                assertNotNull(text(legal, "带单员授权与第三方权利"));
            }
        }
        local.prefs.edit().putString("last_intro_version", "4.2.1").commit();
        try (org.robolectric.android.controller.ActivityController<MainActivity> updated =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = updated.get().getWindow().getDecorView();
            assertNotNull(text(root, "场外跟单，无需求人。"));
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
            assertNull(text(root, "场外跟单，无需求人。"));
            AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(previous == null || !previous.isShowing());
        }
    }

    @Test @Config(sdk = {34, 37})
    public void narrowTallAndWideWindowsKeepTheSettingsAndHistoryDropdownUsable() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive.open(app).capture(data.config().portfolio, "历史带单员", opens(0, 1), START, true, "完成");
        String[] displays = {"w320dp-h570dp", "w360dp-h800dp", "w800dp-h360dp", "w900dp-h1100dp"};
        int[] widths = {320, 360, 800, 900}, heights = {570, 800, 360, 1100};
        for (int i = 0; i < displays.length; i++) {
            RuntimeEnvironment.setQualifiers(displays[i]);
            try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                    Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = screen.get();
                View root = activity.getWindow().getDecorView();
                navigation(root, "设置").performClick();
                View surface = ReflectionHelpers.getField(activity, "surface");
                int width = Ui.dp(activity, widths[i]), height = Ui.dp(activity, heights[i]);
                surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                surface.layout(0, 0, width, height);
                View body = ReflectionHelpers.getField(activity, "page");
                assertTrue(body.getMeasuredWidth() <= Ui.dp(activity, 660));
                assertTrue(body.getMeasuredWidth() >= Ui.dp(activity, 260));
                if (widths[i] > 660) assertTrue(body.getLeft() > 0);
                android.widget.AutoCompleteTextView portfolio = (android.widget.AutoCompleteTextView)
                        input(body, "币安组合 ID 或组合链接");
                assertEquals("历史带单员  ·  " + data.config().portfolio, portfolio.getAdapter().getItem(0).toString());
                assertNotNull(text(body, "历史带单员 ▾"));
                text(body, "作者信息").performClick();
                Intent info = shadowOf(activity).getNextStartedActivity();
                try (org.robolectric.android.controller.ActivityController<InfoActivity> author =
                        Robolectric.buildActivity(InfoActivity.class, info).setup()) {
                    View details = author.get().getWindow().getDecorView();
                    assertNotNull(text(details, "@wuyanzuWa"));
                    assertNotNull(text(details, "wuy15525@gmail.com"));
                }
            }
        }
    }

    @Test @Config(sdk = {33, 34, 35, 36, 37})
    public void foregroundTypeMatchesTheSdkAndLegacySilentChannelsUseSystemAudio() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        android.content.pm.ServiceInfo service = app.getPackageManager().getServiceInfo(
                new android.content.ComponentName(app, AutoFollowService.class), 0);
        int sdk = android.os.Build.VERSION.SDK_INT;
        int type = AutoFollowService.foregroundType(sdk);
        assertEquals(sdk < 34 ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, type);
        assertEquals(type, service.getForegroundServiceType() & type);
        assertFalse(service.exported);
        assertEquals(37, service.applicationInfo.targetSdkVersion);
        android.app.NotificationManager manager = app.getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel legacy = new android.app.NotificationChannel("copy_trades", "交易事件",
                android.app.NotificationManager.IMPORTANCE_DEFAULT);
        legacy.setSound(null, null);
        manager.createNotificationChannel(legacy);
        android.app.NotificationChannel blocked = new android.app.NotificationChannel("leader_balance_transfers",
                "带单员余额划转", android.app.NotificationManager.IMPORTANCE_NONE);
        blocked.setSound(null, null);
        manager.createNotificationChannel(blocked);
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Notices.channels(app);
        Notices.trade(app, "api-test", "交易测试", "BTCUSDT", true);
        android.app.NotificationChannel migrated = manager.getNotificationChannel("copy_trades_alerts");
        assertEquals(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, migrated.getSound());
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION, migrated.getAudioAttributes().getUsage());
        assertNull(manager.getNotificationChannel("leader_balance_transfers_alerts"));
        assertEquals("copy_trades_alerts", manager.getActiveNotifications()[0].getNotification().getChannelId());
        assertTrue(app.getResources().openRawResource(R.raw.order_alert).read() >= 0);
        Notices.testTrade(app, "第一次提示音");
        Notices.testTrade(app, "第二次提示音");
        android.app.Notification test = java.util.Arrays.stream(manager.getActiveNotifications())
                .filter(row -> "notice-test".equals(row.getTag())).findFirst().orElseThrow().getNotification();
        assertEquals(0, test.flags & android.app.Notification.FLAG_ONLY_ALERT_ONCE);
    }

    @Test public void finalLiveDialogRequiresAnotherExplicitTapBeforeStartingTheService() {
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = screen.get();
            ReflectionHelpers.callInstanceMethod(activity, "chooseStrategy", ClassParameter.from(String.class, AutoFollowService.REAL));
            AlertDialog strategy = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("下一步", strategy.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            strategy.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            AlertDialog real = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(strategy, real);
            assertNotNull(text(real.getWindow().getDecorView(), "当前为实盘跟单"));
            assertNull(shadowOf(activity).getNextStartedService());
            real.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertNull(shadowOf(activity).getNextStartedService());
        }
    }

    @Test @Config(sdk = {33, 37})
    public void audibleNotificationUsesOnePlaybackPathAndRespectsRingerMode() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        android.app.NotificationManager manager = app.getSystemService(android.app.NotificationManager.class);
        android.media.AudioManager audio = app.getSystemService(android.media.AudioManager.class);
        shadowOf(manager).setNotificationPolicyAccessGranted(true);
        manager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
        audio.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 4, 0);
        Notices.testTrade(app, "用应用内声音");
        android.app.Notification direct = manager.getActiveNotifications()[0].getNotification();
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, direct.getGroupAlertBehavior());
        assertTrue(Notices.soundStatus(app).contains("应用内提示音"));
        audio.setRingerMode(android.media.AudioManager.RINGER_MODE_VIBRATE);
        Notices.testTrade(app, "按系统静音");
        android.app.Notification system = manager.getActiveNotifications()[0].getNotification();
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, system.getGroupAlertBehavior());
        assertTrue(Notices.soundStatus(app).contains("静音或振动"));
    }

    @Test public void publishedSignerIsCheckedAgainstTheActualCertificateDigest() throws Exception {
        android.content.pm.Signature signature = new android.content.pm.Signature(new byte[]{1, 2, 3});
        String fingerprint = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
        assertTrue(ReleaseIntegrity.matches(new android.content.pm.Signature[]{signature}, fingerprint));
        assertFalse(ReleaseIntegrity.matches(new android.content.pm.Signature[]{signature}, "0".repeat(64)));
        assertFalse(ReleaseIntegrity.matches(new android.content.pm.Signature[]{signature, signature}, fingerprint));
    }

    @Test @Config(sdk = {34, 35, 36, 37})
    public void startingWatchPromotesTheServiceBeforeWaitingForAnOfflineNetwork() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
        shadowOf(network).setActiveNetworkInfo(null);
        LocalData data = new LocalData(app);
        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            Intent start = new Intent(app, AutoFollowService.class).setAction(AutoFollowService.WATCH);
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(start, 0, 1));
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.getForegroundServiceType());
            assertEquals(Notices.AUTO_ID, shadowOf(service).getLastForegroundNotificationId());
            ExecutorService worker = ReflectionHelpers.getField(service, "worker");
            worker.submit(() -> {}).get(8, TimeUnit.SECONDS);
            assertTrue(data.prefs.getString("auto_status", "").contains("手机网络暂不可用"));
            assertEquals(AutoFollowService.WATCH, data.prefs.getString("auto_active", ""));
        } finally {
            controller.destroy();
            data.prefs.edit().remove("auto_active").commit();
        }
    }

    @Test public void backgroundGuideRecognizesTheFivePhoneBrands() {
        assertTrue(MainActivity.backgroundGuide("Xiaomi", "POCO").contains("无限制"));
        assertTrue(MainActivity.backgroundGuide("HONOR", "HONOR").contains("允许后台活动"));
        assertTrue(MainActivity.backgroundGuide("vivo", "iQOO").contains("允许后台耗电"));
        assertTrue(MainActivity.backgroundGuide("OPPO", "OPPO").contains("允许后台活动"));
        assertTrue(MainActivity.backgroundGuide("samsung", "Samsung").contains("从不自动休眠"));
    }

    @Test public void changingMarginDoesNotPreventConfirmingStableTransferRows() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> false, false, false);
        poll.balances = new java.util.concurrent.CompletableFuture<>();
        ReflectionHelpers.setField(capture, "current", poll);
        JSONObject data = new JSONObject().put("phase", "balances").put("page", 1).put("zone", "UTC")
                .put("leaderMargin", "500.29").put("transfers", new org.json.JSONArray(
                        "[[\"2026-11-21 00:38:32\",\"USDT\",\"80,000.00000000\",\"现货账户\",\"带单交易\"]]"));
        observe(capture, poll, data);
        assertFalse(poll.balances.isDone());
        poll.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1001;
        observe(capture, poll, data.put("leaderMargin", "510.29"));
        LeaderBalances.Read read = poll.balances.join();
        assertTrue(read.complete);
        assertEquals("510.29", Rules.show(read.margin));
        assertEquals(1, read.transfers.size());
        assertTrue(read.transfers.get(0).text().contains("转入 80000 USDT"));
    }

    @Test public void newPageNumberCannotConfirmOldTransferRowsAndPartialReadIsPreserved() throws Exception {
        WebsiteCapture capture = new WebsiteCapture(RuntimeEnvironment.getApplication(), ID, "");
        WebsiteCapture.Poll poll = new WebsiteCapture.Poll(null, rows -> false, false, false);
        poll.balances = new java.util.concurrent.CompletableFuture<>();
        poll.balancePage = 2;
        poll.savedBalancePage = 1;
        poll.balanceZone = "UTC";
        poll.balanceRows = new org.json.JSONArray(
                "[[\"2026-11-21 00:38:32\",\"USDT\",\"80,000.00000000\",\"现货账户\",\"带单交易\"]]");
        poll.previousBalancePage = poll.balanceRows.toString();
        ReflectionHelpers.setField(capture, "current", poll);
        JSONObject data = new JSONObject().put("phase", "balances").put("page", 2).put("zone", "UTC")
                .put("leaderMargin", "510.29").put("transfers", poll.balanceRows);
        observe(capture, poll, data);
        poll.textChangedAt = android.os.SystemClock.elapsedRealtime() - 1500;
        observe(capture, poll, data);
        assertFalse(poll.balances.isDone());
        assertEquals(1, poll.balanceRows.length());
        ReflectionHelpers.callInstanceMethod(capture, "timeout", ClassParameter.from(WebsiteCapture.Poll.class, poll));
        LeaderBalances.Read read = poll.balances.join();
        assertFalse(read.complete);
        assertTrue(read.issue.contains("第 2 页"));
        assertEquals(1, read.transfers.size());
        assertEquals("510.29", Rules.show(read.margin));
    }

    @Test public void recordsOnlyOfficialTransfersAndHidesOldMarginSnapshots() throws Exception {
        String portfolio = "53828582";
        Application app = RuntimeEnvironment.getApplication();
        LeaderBalances balances = LeaderBalances.open(app);
        balances.getWritableDatabase().execSQL("INSERT INTO changes(portfolio,event,stamp,asset,amount,origin,destination,value) "
                + "VALUES (?, 'balance|old', 1, 'USDT', '0', '', '', '520')", new Object[]{portfolio});
        long at = java.time.Instant.parse("2026-11-21T00:38:32Z").toEpochMilli();
        JSONObject website = new JSONObject("{\"zone\":\"UTC\",\"leaderMargin\":\"500.29\",\"transfers\":["
                + "[\"2026-11-21 00:38:32\",\"USDT\",\"80,000.00000000\",\"现货账户\",\"带单交易\"],"
                + "[\"2026-11-19 00:12:06\",\"USDT\",\"374,433.00000000\",\"带单交易\",\"现货账户\"]]}");
        LeaderBalances.Read read = LeaderBalances.parse(website);
        balances.record(portfolio, read, at + 500, at - 1000);
        assertEquals(2, balances.count(portfolio));
        List<LeaderBalances.Event> alert = balances.pending(portfolio);
        assertEquals(1, alert.size());
        assertTrue(alert.get(0).text().contains("转入 80000 USDT"));
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Notices.balance(app, alert.get(0).id, "测试组合", alert.get(0).text());
        android.app.NotificationManager notifications = app.getSystemService(android.app.NotificationManager.class);
        assertEquals("leader_balance_transfers_alerts", notifications.getActiveNotifications()[0].getNotification().getChannelId());
        balances.notified(portfolio, alert.get(0).id);
        balances.close();
        LeaderBalances.Read change = LeaderBalances.parse(website.put("leaderMargin", "510.29"));
        balances.record(portfolio, change, at + 60000, at - 1000);
        assertEquals(2, balances.count(portfolio));
        assertEquals(2, balances.page(portfolio, 10, 0).size());
        assertTrue(balances.page(portfolio, 2, 0).get(0).text().contains("转入 80000 USDT"));
        assertTrue(balances.pending(portfolio).isEmpty());
    }

    @Test public void savesLiveSizingButOnlyAllowsLeverageChangesForEmptyContracts() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        LeaderArchive db = LeaderArchive.open(app);
        Rules.Config before = data.config();
        Map<String, Rules.ContractRule> rules = new java.util.LinkedHashMap<>(before.rules);
        String btc = "BTCUSDT";
        Rules.ContractRule existing = before.rule(btc);
        rules.put(btc, new Rules.ContractRule(true, new BigDecimal("4"), existing.leverage, new BigDecimal("0.6")));
        Rules.Config change = new Rules.Config(before.portfolio, rules);
        AutoFollowService.reserveStart(true);
        try {
            AutoFollowService.saveRules(data, db, change);
            assertEquals(new BigDecimal("4"), data.config().rule(btc).amount);
            assertEquals(new BigDecimal("0.6"), data.config().rule(btc).slippagePercent);
            try {
                AutoFollowService.saveRules(data, db, new Rules.Config(OTHER, rules));
                fail("应先停止再更换组合");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("请先停止"));
            }
        } finally { AutoFollowService.reserveStart(false); }
        FuturesApi.Position held = new FuturesApi.Position();
        held.shorts = BigDecimal.ONE;
        AutoFollowService.requireFlatLeverage(Set.of(btc), Map.of("ETHUSDT", held), "实盘");
        try {
            AutoFollowService.requireFlatLeverage(Set.of(btc), Map.of(btc, held), "实盘");
            fail("对应方向有仓时应拦截杠杆修改");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("BTCUSDT 当前有仓位"));
        }
    }

    @Test public void editingOneWhitelistContractPersistsOnlyItsSettings() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        LocalData data = new LocalData(app);
        assertTrue(data.prefs.getAll().keySet().stream().noneMatch(key -> key.startsWith("credentials_")));
        LeaderArchive.open(app).saveName(ID, "测试组合");
        try (org.robolectric.android.controller.ActivityController<MainActivity> screen =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            navigation(root, "设置").performClick();
            text(root, "添加合约").performClick();
            AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
            View selection = picker.getWindow().getDecorView();
            ListView choices = firstView(selection, ListView.class);
            EditText search = input(selection, "搜索合约名称或代码");
            search.setText("ETH");
            assertEquals("ETHUSDT", choices.getItemAtPosition(0));
            chooseFirst(choices);
            search.setText("SOL");
            assertEquals("SOLUSDT", choices.getItemAtPosition(0));
            chooseFirst(choices);
            search.setText("币安人生USDT");
            assertTrue(choices.getItemAtPosition(0).toString().startsWith("币安人生USDT"));
            chooseFirst(choices);
            assertNotNull(text(selection, "已选 3 个"));
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(text(root, "ETHUSDT"));
            assertNotNull(text(root, "SOLUSDT"));
            View btc = (View) text(root, "BTCUSDT").getParent();
            text(btc, "设置").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View settings = dialog.getWindow().getDecorView();
            text(settings, "定比 · 按当前 USDT 钱包余额").performClick();
            input(settings, "每笔保证金比例（%）").setText("2.5");
            input(settings, "预设杠杆倍数").setText("9");
            input(settings, "最大不利价差（%）").setText("0.45");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNotNull(text(root, "2.5% 钱包余额 · 9 倍"));
            text(root, "保存规则").performClick();
            ExecutorService worker = ReflectionHelpers.getStaticField(MainActivity.class, "ACCOUNT");
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(data.config().symbols.contains("币安人生USDT"));
            assertEquals(9, data.config().rule("BTCUSDT").leverage);
            assertEquals(new BigDecimal("0.45"), data.config().rule("BTCUSDT").slippagePercent);
            assertTrue(data.config().rule("BTCUSDT").ratio);
            assertEquals(5, data.config().rule("MUUSDT").leverage);
            assertFalse(data.config().rule("MUUSDT").ratio);
            assertEquals(5, data.config().rule("ETHUSDT").leverage);
            assertEquals(new BigDecimal("20"), data.config().rule("SOLUSDT").amount);
        }
    }

    @Test public void writesOrderRequestErrorsToRunStatusWithoutPostingSystemNotifications() {
        Application app = RuntimeEnvironment.getApplication();
        org.robolectric.android.controller.ServiceController<AutoFollowService> controller =
                Robolectric.buildService(AutoFollowService.class).create();
        try {
            AutoFollowService service = controller.get();
            ReflectionHelpers.setField(service, "mode", AutoFollowService.DEMO_WEB);
            String failure = "BTCUSDT 调整杠杆失败 · 币安 API HTTP 400 / -4209";
            ReflectionHelpers.callInstanceMethod(service, "status", ClassParameter.from(String.class, failure));
            assertTrue(new LocalData(app).prefs.getString("auto_status", "").contains(failure));
            android.app.NotificationManager manager = app.getSystemService(android.app.NotificationManager.class);
            assertEquals(0, manager.getActiveNotifications().length);
        } finally { controller.destroy(); }
    }

    private static String waitDescription(FollowBook.Entry entry, AutoFollowService.WaitingPrices prices) {
        return ReflectionHelpers.callStaticMethod(MainActivity.class, "waitDescription",
                ClassParameter.from(FollowBook.Entry.class, entry),
                ClassParameter.from(String.class, AutoFollowService.DEMO_WEB),
                ClassParameter.from(AutoFollowService.WaitingPrices.class, prices),
                ClassParameter.from(BigDecimal.class, BigDecimal.ONE), ClassParameter.from(BigDecimal.class, null));
    }

    private static boolean applyChoice(AutoFollowService service, LeaderArchive.State state) {
        return ReflectionHelpers.callInstanceMethod(service, "applyInitialChoice",
                ClassParameter.from(LeaderArchive.State.class, state), ClassParameter.from(BigDecimal.class, null));
    }

    private static Intent choice(String portfolio, long head, String id) {
        return new Intent(AutoFollowService.INITIAL_CHOICE).putExtra("portfolio", portfolio)
                .putExtra("head", head).putExtra("positions", new String[]{id});
    }

    private static void syncBook(AutoFollowService service, LeaderArchive.State state) {
        ReflectionHelpers.callInstanceMethod(service, "syncBook", ClassParameter.from(LeaderArchive.State.class, state));
    }

    private static android.widget.Button navigation(View root, String label) {
        if (root instanceof android.widget.Button) {
            android.widget.Button button = (android.widget.Button) root;
            if (label.contentEquals(button.getText()) && button.getCompoundDrawables()[1] != null) return button;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = navigation(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Rules.Signal> opens(int from, int until) {
        List<Rules.Signal> rows = new ArrayList<>();
        for (int i = from; i < until; i++) rows.add(signal(i, Rules.Action.OPEN_LONG, "0.1"));
        return rows;
    }

    private static Rules.Signal signal(int index, Rules.Action action, String quantity) {
        return new Rules.Signal("BTCUSDT", action, START + index * 1000L,
                new BigDecimal("30000"), new BigDecimal(quantity));
    }

    private static Rules.Signal mu(int index, Rules.Action action, String price, String quantity, String value, String pnl) {
        return new Rules.Signal("MUUSDT", action, START + index * 1000L, new BigDecimal(price), new BigDecimal(quantity),
                pnl == null ? null : new BigDecimal(pnl), new BigDecimal(value), null);
    }

    private static TextView text(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(label)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText input(View view, String hint) {
        if (view instanceof EditText && hint.contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = input(group.getChildAt(i), hint);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T firstView(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = firstView(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void chooseFirst(ListView list) {
        list.performItemClick(list.getAdapter().getView(0, null, list), 0, list.getItemIdAtPosition(0));
    }
}
