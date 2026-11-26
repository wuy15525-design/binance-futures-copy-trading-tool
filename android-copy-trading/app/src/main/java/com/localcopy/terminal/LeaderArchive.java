package com.localcopy.terminal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LeaderArchive extends SQLiteOpenHelper {
    private static final String DB_NAME = "leader-history.db";
    static final int DB_VERSION = 15;
    private static final String DETECTED_FILTER = " AND kind IN(0,-2) AND excluded_revision=0";
    private static final String TRADE_COLUMNS = "ordinal,stamp,symbol,action,price,quantity,kind,pnl,detected_at,turnover,lead_margin,event,excluded_revision";
    private static final int SUPPLEMENT = 3;
    private static final int RECONNECTED_BASELINE = 4;
    private static LeaderArchive instance;

    static synchronized LeaderArchive open(Context context) {
        if (instance == null) instance = new LeaderArchive(context.getApplicationContext());
        return instance;
    }

    private LeaderArchive(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE leader (portfolio TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', "
                + "started INTEGER NOT NULL DEFAULT 0, captured INTEGER NOT NULL DEFAULT 0, "
                + "head INTEGER NOT NULL DEFAULT 0, gap INTEGER NOT NULL DEFAULT 0, "
                + "snapshot TEXT NOT NULL DEFAULT '[]', estimate TEXT NOT NULL DEFAULT '{}', "
                + "backfilled INTEGER NOT NULL DEFAULT 0, history_complete INTEGER NOT NULL DEFAULT 0, "
                + "history_detail TEXT NOT NULL DEFAULT '', recovery_head INTEGER NOT NULL DEFAULT 0, "
                + "ordered_snapshot INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE trades (portfolio TEXT NOT NULL, event TEXT NOT NULL, stamp INTEGER NOT NULL, "
                + "symbol TEXT NOT NULL, action TEXT NOT NULL, price TEXT NOT NULL, quantity TEXT NOT NULL, "
                + "ordinal INTEGER, kind INTEGER NOT NULL, pnl TEXT, detected_at INTEGER NOT NULL DEFAULT 0, "
                + "turnover TEXT, lead_margin TEXT, excluded_revision INTEGER NOT NULL DEFAULT 0, "
                + "included_revision INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(portfolio,event))");
        db.execSQL("CREATE INDEX trades_time ON trades(portfolio,stamp DESC,event DESC)");
        db.execSQL("CREATE UNIQUE INDEX trades_ordinal ON trades(portfolio,ordinal)");
        createAccountFills(db);
        createTradeReviews(db);
        createSessions(db);
        createDetectionIndex(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion >= DB_VERSION || newVersion != DB_VERSION) {
            throw new IllegalStateException("此版本不支持修改该成交档案格式");
        }
        db.execSQL("ALTER TABLE trades ADD COLUMN included_revision INTEGER NOT NULL DEFAULT 0");
        if (oldVersion >= 9) {
            db.execSQL("ALTER TABLE trade_reviews ADD COLUMN provisional INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion == 14) return;
        if (oldVersion >= 9) {
            db.execSQL("ALTER TABLE trade_reviews ADD COLUMN baseline_checked INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion == 13) return;
        if (oldVersion == 12) {
            createDetectionIndex(db);
            return;
        }
        if (oldVersion == 11) {
            createDetectionIndex(db);
            db.execSQL("ALTER TABLE leader_sessions ADD COLUMN trusted_empty INTEGER NOT NULL DEFAULT 0");
            return;
        }
        createSessions(db);
        if (oldVersion == 10) {
            createDetectionIndex(db);
            return;
        }
        if (oldVersion < 8) db.execSQL("ALTER TABLE leader ADD COLUMN ordered_snapshot INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE leader ADD COLUMN backfilled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE leader ADD COLUMN history_complete INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE leader ADD COLUMN history_detail TEXT NOT NULL DEFAULT ''");
        } else if (oldVersion == 6) {
            db.execSQL("UPDATE leader SET history_complete=0, history_detail='升级后待核对官网基线'");
        }
        if (oldVersion < 7) db.execSQL("ALTER TABLE leader ADD COLUMN recovery_head INTEGER NOT NULL DEFAULT 0");
        if (oldVersion <= 3) {
            db.execSQL("ALTER TABLE trades ADD COLUMN turnover TEXT");
            db.execSQL("ALTER TABLE trades ADD COLUMN lead_margin TEXT");
        }
        if (oldVersion <= 2) db.execSQL("ALTER TABLE trades ADD COLUMN detected_at INTEGER NOT NULL DEFAULT 0");
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE trades ADD COLUMN pnl TEXT");
            createAccountFills(db);
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE trades ADD COLUMN excluded_revision INTEGER NOT NULL DEFAULT 0");
            createTradeReviews(db);
        }
        createDetectionIndex(db);
        if (oldVersion == 8) {
            db.execSQL("INSERT OR IGNORE INTO trade_reviews(portfolio,stamp,sample,legacy) "
                    + "SELECT portfolio,stamp,'[]',1 FROM trades WHERE ordinal IS NOT NULL GROUP BY portfolio,stamp,"
                    + "CASE WHEN instr(event,'|#')>0 THEN substr(event,1,instr(event,'|#')-1) ELSE event END,pnl,turnover "
                    + "HAVING COUNT(*)>1 AND SUM(CASE WHEN kind=" + SUPPLEMENT + " THEN 1 ELSE 0 END)>0");
        }
        try (Cursor rows = db.rawQuery("SELECT portfolio,estimate FROM leader", null)) {
            while (rows.moveToNext()) {
                String portfolio = rows.getString(0);
                Map<String, LeaderHistory.Holding> old = readHoldings(new JSONObject(rows.getString(1)), oldVersion == 1);
                Map<String, LeaderHistory.Holding> rebuilt = replayHoldings(db, portfolio, old);
                ContentValues update = new ContentValues();
                update.put("estimate", holdingsJson(rebuilt));
                db.update("leader", update, "portfolio=?", new String[]{portfolio});
            }
        } catch (JSONException failure) { throw new IllegalStateException("无法迁移已有成交档案", failure); }
    }

    private static void createDetectionIndex(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS trades_detected ON trades(portfolio,detected_at DESC,stamp DESC)"
                + " WHERE kind IN(0,-2) AND excluded_revision=0");
    }

    private static void createAccountFills(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE account_fills (portfolio TEXT NOT NULL, mode TEXT NOT NULL, "
                + "event TEXT PRIMARY KEY, stamp INTEGER NOT NULL, summary TEXT NOT NULL)");
        db.execSQL("CREATE INDEX account_fills_time ON account_fills(portfolio,stamp DESC,event DESC)");
    }

    private static void createTradeReviews(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trade_reviews (portfolio TEXT NOT NULL, stamp INTEGER NOT NULL, "
                + "sample TEXT NOT NULL, checked TEXT, legacy INTEGER NOT NULL DEFAULT 0, "
                + "baseline_checked INTEGER NOT NULL DEFAULT 0, provisional INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(portfolio,stamp))");
    }

    private static void createSessions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS leader_sessions (portfolio TEXT PRIMARY KEY,"
                + " verified_selection INTEGER NOT NULL, segment_start INTEGER NOT NULL,"
                + " trusted_empty INTEGER NOT NULL DEFAULT 0)");
    }

    static final class State {
        final String name;
        final long started, captured, head;
        final long backfilled, recoveryHead;
        final boolean gap;
        final boolean historyComplete, orderedSnapshot;
        final String historyDetail;
        final List<Rules.Signal> window;
        final Map<String, BigDecimal> estimate;
        final Map<String, BigDecimal> projectedSizes;
        final Map<String, LeaderHistory.Holding> holdings;

        State(String name, long started, long captured, long head, boolean gap,
                List<Rules.Signal> window, Map<String, LeaderHistory.Holding> holdings) {
            this(name, started, captured, head, gap, window, holdings, 0, false, "", 0, false);
        }

        State(String name, long started, long captured, long head, boolean gap,
                List<Rules.Signal> window, Map<String, LeaderHistory.Holding> holdings,
                long backfilled, boolean historyComplete, String historyDetail, long recoveryHead, boolean orderedSnapshot) {
            this.name = name;
            this.started = started;
            this.captured = captured;
            this.head = head;
            this.gap = gap;
            this.backfilled = backfilled;
            this.historyComplete = historyComplete;
            this.historyDetail = historyDetail;
            this.recoveryHead = recoveryHead;
            this.orderedSnapshot = orderedSnapshot;
            this.window = Collections.unmodifiableList(window);
            this.holdings = Collections.unmodifiableMap(holdings);
            Map<String, BigDecimal> sizes = new LinkedHashMap<>();
            Map<String, BigDecimal> projected = new LinkedHashMap<>();
            for (Map.Entry<String, LeaderHistory.Holding> entry : holdings.entrySet()) {
                LeaderHistory.Holding holding = entry.getValue();
                if (holding.quantity.signum() > 0) sizes.put(entry.getKey(), holding.quantity);
                BigDecimal amount = holding.displayQuantity();
                if (amount.signum() > 0) projected.put(entry.getKey(), amount.stripTrailingZeros());
            }
            this.estimate = Collections.unmodifiableMap(sizes);
            this.projectedSizes = Collections.unmodifiableMap(projected);
        }

        Rules.Signal latest() { return window.isEmpty() ? null : window.get(window.size() - 1); }
    }

    static final class Update {
        final State state;
        final int added;
        final boolean baseline, continuous;
        Update(State state, int added, boolean baseline, boolean continuous) {
            this.state = state;
            this.added = added;
            this.baseline = baseline;
            this.continuous = continuous;
        }
    }

    static final class Projection {
        final Map<String, LeaderHistory.Holding> holdings;
        final long lastTrade;

        Projection(Map<String, LeaderHistory.Holding> holdings, long lastTrade) {
            this.holdings = holdings;
            this.lastTrade = lastTrade;
        }

        Set<String> openSymbols() {
            Set<String> symbols = new HashSet<>();
            for (Map.Entry<String, LeaderHistory.Holding> entry : holdings.entrySet()) {
                if (entry.getValue().displayQuantity().signum() <= 0) continue;
                String leg = entry.getKey();
                symbols.add(leg.substring(0, leg.indexOf('|')));
            }
            return symbols;
        }
    }

    static final class Record {
        final long ordinal;
        final Rules.Signal signal;
        final int kind;
        final long detectedAt;
        final boolean excluded;
        Record(long ordinal, Rules.Signal signal, int kind, long detectedAt, boolean excluded) {
            this.ordinal = ordinal;
            this.signal = signal;
            this.kind = kind;
            this.detectedAt = detectedAt;
            this.excluded = excluded;
        }

        String detectionText(boolean tracked) {
            String detail = kind == -2 ? " · 缺口后观察 · 待核对" : "";
            if (!tracked) detail += " · 白名单外";
            if (!signal.action.opens) detail += " · " + TradeNotice.pnl(signal.realizedPnl);
            return signal.symbol + " " + signal.action.label + " " + Rules.show(signal.quantity)
                    + " @ " + Rules.show(signal.price) + detail + "\n成交 " + TradeNotice.fullTime(signal.time)
                    + " · 检测 " + (detectedAt == 0 ? "未记录" : TradeNotice.fullTime(detectedAt));
        }

        String source() {
            if (kind < 0) return "缺口后观察";
            if (kind == RECONNECTED_BASELINE) return "新跟单基线";
            if (kind == SUPPLEMENT) return "历史基线补录";
            if (kind == 2) return "旧版基线";
            if (kind == 1) return "初始基线";
            return "跟踪新增";
        }
    }

    State state(String portfolio) throws JSONException { return read(getReadableDatabase(), portfolio); }

    static final class Portfolio {
        final String id, name;
        Portfolio(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return (name.isEmpty() ? "币安组合" : name) + "  ·  " + id; }
    }

    List<Portfolio> followedPortfolios() {
        List<Portfolio> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT portfolio,title FROM leader WHERE started>0 OR history_complete=1 ORDER BY captured DESC,portfolio", null)) {
            while (rows.moveToNext()) result.add(new Portfolio(rows.getString(0), rows.getString(1)));
        }
        return result;
    }

    long verifiedSelection(String portfolio) {
        return selectionColumn(getReadableDatabase(), portfolio, "verified_selection");
    }

    boolean needsEmptyRecheck(String portfolio, State state) {
        return state.head > 0 && state.window.isEmpty()
                && selectionColumn(getReadableDatabase(), portfolio, "trusted_empty") == 0;
    }

    private static long selectionColumn(SQLiteDatabase db, String portfolio, String column) {
        try (Cursor row = db.rawQuery("SELECT " + column + " FROM leader_sessions WHERE portfolio=?", new String[]{portfolio})) {
            return row.moveToFirst() ? row.getLong(0) : 0;
        }
    }

    private static long firstCurrentOrdinal(SQLiteDatabase db, String portfolio) {
        long start = selectionColumn(db, portfolio, "segment_start");
        return start == 0 ? Long.MIN_VALUE : start;
    }

    long currentSegmentTime(String portfolio) {
        SQLiteDatabase db = getReadableDatabase();
        long first = firstCurrentOrdinal(db, portfolio);
        if (first == Long.MIN_VALUE) return Long.MIN_VALUE;
        try (Cursor row = db.rawQuery("SELECT stamp FROM trades WHERE portfolio=? AND ordinal>=? "
                + "AND excluded_revision=0 ORDER BY ordinal LIMIT 1", new String[]{portfolio, Long.toString(first)})) {
            return row.moveToFirst() ? row.getLong(0) : Long.MAX_VALUE;
        }
    }

    Projection projection(String portfolio, State confirmed) {
        long last = confirmed.latest() == null ? 0 : confirmed.latest().time;
        if (!confirmed.gap) return new Projection(confirmed.holdings, last);
        List<Record> observations = query("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? "
                + "AND kind=-2 AND excluded_revision=0 AND stamp>? ORDER BY stamp DESC,rowid DESC LIMIT 250",
                new String[]{portfolio, Long.toString(last)});
        Map<String, Long> batches = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Record record : observations) {
            Rules.Signal row = record.signal;
            String legAtTime = row.leg() + "|" + row.time;
            Long earlierBatch = batches.putIfAbsent(legAtTime, record.detectedAt);
            if (earlierBatch != null && earlierBatch != record.detectedAt) ambiguous.add(legAtTime);
        }
        // Observations across a gap only contribute to the displayed scenario.
        Map<String, LeaderHistory.Holding> visible = new LinkedHashMap<>(confirmed.holdings);
        for (int i = observations.size() - 1; i >= 0; i--) {
            Rules.Signal row = observations.get(i).signal;
            if (ambiguous.contains(row.leg() + "|" + row.time)) continue;
            LeaderHistory.apply(visible, row);
            last = row.time;
        }
        return new Projection(visible, last);
    }

    private State read(SQLiteDatabase db, String portfolio) throws JSONException {
        try (Cursor row = db.rawQuery("SELECT title,started,captured,head,gap,snapshot,estimate,"
                + "backfilled,history_complete,history_detail,recovery_head,ordered_snapshot FROM leader "
                + "WHERE portfolio=?", new String[]{portfolio})) {
            if (!row.moveToFirst()) return new State("", 0, 0, 0, false, new ArrayList<>(), new LinkedHashMap<>());
            Map<String, LeaderHistory.Holding> holdings = readHoldings(new JSONObject(row.getString(6)), false);
            return new State(row.getString(0), row.getLong(1), row.getLong(2), row.getLong(3), row.getInt(4) != 0,
                    LocalData.readSignals(new JSONArray(row.getString(5))), holdings, row.getLong(7),
                    row.getInt(8) != 0, row.getString(9), row.getLong(10), row.getInt(11) != 0);
        }
    }

    private static Map<String, LeaderHistory.Holding> readHoldings(JSONObject json, boolean legacy) throws JSONException {
        Map<String, LeaderHistory.Holding> holdings = new LinkedHashMap<>();
        for (Iterator<String> it = json.keys(); it.hasNext();) {
            String key = it.next();
            LeaderHistory.Holding holding = legacy
                    ? new LeaderHistory.Holding(new BigDecimal(json.getString(key)), null, 0)
                    : LeaderHistory.Holding.read(json.getJSONObject(key));
            if (holding.quantity.signum() > 0) holdings.put(key, holding);
        }
        return holdings;
    }

    private static Map<String, LeaderHistory.Holding> replayHoldings(SQLiteDatabase db, String portfolio,
            Map<String, LeaderHistory.Holding> prior) {
        Map<String, LeaderHistory.Holding> replayed = replay(db, portfolio);
        // Imported checkpoints can contain a known quantity from before the retained archive.
        replayed.entrySet().removeIf(row -> !prior.containsKey(row.getKey()) && row.getValue().quantity.signum() > 0);
        for (Map.Entry<String, LeaderHistory.Holding> row : prior.entrySet()) {
            LeaderHistory.Holding old = row.getValue();
            LeaderHistory.Holding rebuilt = replayed.get(row.getKey());
            if (rebuilt == null ? old.quantity.signum() > 0 : rebuilt.quantity.compareTo(old.quantity) != 0) {
                replayed.put(row.getKey(), old);
            }
        }
        return replayed;
    }

    private static Map<String, LeaderHistory.Holding> replay(SQLiteDatabase db, String portfolio) {
        return replay(db, portfolio, Long.MAX_VALUE);
    }

    Map<String, LeaderHistory.Holding> holdingsAt(String portfolio, long ordinal) {
        return replay(getReadableDatabase(), portfolio, ordinal);
    }

    private static Map<String, LeaderHistory.Holding> replay(SQLiteDatabase db, String portfolio, long ordinal) {
        Map<String, LeaderHistory.Holding> holdings = new LinkedHashMap<>();
        try (Cursor trades = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? "
                + "AND ordinal BETWEEN ? AND ? AND excluded_revision=0 ORDER BY ordinal",
                new String[]{portfolio, Long.toString(firstCurrentOrdinal(db, portfolio)), Long.toString(ordinal)})) {
            while (trades.moveToNext()) LeaderHistory.apply(holdings, record(trades).signal);
        }
        return holdings;
    }

    boolean hasConfirmedOverlap(String portfolio, List<Rules.Signal> rows) {
        SQLiteDatabase db = getReadableDatabase();
        return hasConfirmedOverlap(db, portfolio, rows, firstCurrentOrdinal(db, portfolio));
    }

    boolean connectsToTail(String portfolio, State state, List<Rules.Signal> rows) {
        try {
            List<Rules.Signal> reference = captureReference(getReadableDatabase(), portfolio, state);
            if (reference.isEmpty() || rows.isEmpty()) return false;
            LeaderHistory.merge(reference, normalizeArchiveOverlap(portfolio, state, rows, null),
                    true, !state.orderedSnapshot);
            return true;
        } catch (LeaderHistory.Gap | JSONException missing) {
            return false;
        }
    }

    static final class TradeSides {
        final List<Rules.Signal> rows;
        final Set<String> pending;

        TradeSides(List<Rules.Signal> rows, Set<String> pending) {
            this.rows = rows;
            this.pending = pending;
        }
    }

    TradeSides resolveTradeSides(String portfolio, State before, List<Rules.Signal> rows,
            List<ClosedTradeReview.Position> positions) {
        if (!OneWayTrades.hasSideReports(rows)) return new TradeSides(rows, Collections.emptySet());
        SQLiteDatabase db = getReadableDatabase();
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        int firstNew;
        try {
            LeaderHistory.Merge merge = LeaderHistory.merge(captureReference(db, portfolio, before), rows,
                    true, !before.orderedSnapshot);
            rows = merge.rows;
            firstNew = rows.size() - merge.added.size();
        } catch (LeaderHistory.Gap missing) {
            return new TradeSides(OneWayTrades.resolve(rows, 0, balances, positions), Collections.emptySet());
        }
        if (!before.window.isEmpty() && firstNew < rows.size()) {
            Set<String> checked = new HashSet<>();
            long start = firstCurrentOrdinal(db, portfolio);
            for (Rules.Signal row : rows.subList(firstNew, rows.size())) {
                if (!row.reportedAction.sideOnly() || !checked.add(row.symbol)) continue;
                try (Cursor last = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? "
                        + "AND symbol=? AND ordinal BETWEEN ? AND ? AND excluded_revision=0 ORDER BY ordinal DESC LIMIT 1",
                        new String[]{portfolio, row.symbol, Long.toString(start), Long.toString(before.head)})) {
                    if (!last.moveToFirst()) continue;
                    Rules.Signal anchor = record(last).signal;
                    if (!anchor.reportedAction.sideOnly() || anchor.action.sideOnly()) continue;
                    BigDecimal longs = before.estimate.getOrDefault(row.symbol + "|LONG", Rules.ZERO);
                    BigDecimal shorts = before.estimate.getOrDefault(row.symbol + "|SHORT", Rules.ZERO);
                    if (longs.signum() == 0 || shorts.signum() == 0) balances.put(row.symbol, longs.subtract(shorts));
                }
            }
        }
        List<Rules.Signal> resolved = OneWayTrades.resolve(rows, firstNew, balances, positions);
        Set<String> pending = new LinkedHashSet<>();
        for (Rules.Signal row : resolved.subList(firstNew, resolved.size())) {
            if (row.action.sideOnly()) pending.add(row.symbol);
        }
        return new TradeSides(resolved, pending);
    }

    void observeGap(String portfolio, String name, List<Rules.Signal> rows, long now) throws JSONException {
        if (rows.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            State state = read(db, portfolio);
            long cutoff = state.latest() == null ? Long.MAX_VALUE : state.latest().time;
            for (Rules.Signal row : rows) putIfNew(db, portfolio, row, row.time >= cutoff ? -2 : -1, now);
            State gap = new State(name.isEmpty() ? state.name : name, state.started, state.captured, state.head, true,
                    state.window, state.holdings, state.backfilled, state.historyComplete,
                    state.historyDetail, state.recoveryHead, state.orderedSnapshot);
            write(db, portfolio, gap);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static boolean hasConfirmedOverlap(SQLiteDatabase db, String portfolio, List<Rules.Signal> rows, long start) {
        if (rows.isEmpty()) return false;
        Set<String> events = new HashSet<>();
        for (Rules.Signal row : rows) events.add(row.fingerprint);
        try (Cursor records = db.rawQuery("SELECT event FROM trades WHERE portfolio=? "
                + "AND stamp>=? AND stamp<=? AND ordinal>=? AND excluded_revision=0",
                new String[]{portfolio, Long.toString(rows.get(0).time),
                        Long.toString(rows.get(rows.size() - 1).time), Long.toString(start)})) {
            while (records.moveToNext()) if (events.contains(Rules.Signal.fingerprintOf(records.getString(0)))) return true;
        }
        return false;
    }

    static final class Review {
        final long stamp;
        final List<Rules.Signal> sample;
        final boolean legacy;

        Review(long stamp, List<Rules.Signal> sample, boolean legacy) {
            this.stamp = stamp;
            this.sample = sample;
            this.legacy = legacy;
        }
    }

    void queueReviews(String portfolio, Map<Long, List<Rules.Signal>> candidates) throws JSONException {
        if (candidates.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<Long, List<Rules.Signal>> entry : candidates.entrySet()) {
                long stamp = entry.getKey();
                List<Rules.Signal> sample = entry.getValue();
                boolean legacy = false;
                try (Cursor row = db.rawQuery("SELECT sample,checked,legacy,provisional FROM trade_reviews WHERE portfolio=? AND stamp=?",
                        new String[]{portfolio, Long.toString(stamp)})) {
                    if (row.moveToFirst()) {
                        if (row.getInt(3) != 0) continue;
                        legacy = row.getInt(2) == 1;
                        List<Rules.Signal> earlier = readReviewRows(row.getString(0));
                        if (row.isNull(1)) {
                            if (legacy || !TradeReview.hasAdditionalDetails(earlier, sample)) continue;
                        } else if (TradeReview.same(sample, earlier) || TradeReview.same(sample, readReviewRows(row.getString(1)))) {
                            continue;
                        }
                    }
                }
                ContentValues values = new ContentValues();
                values.put("portfolio", portfolio);
                values.put("stamp", stamp);
                values.put("sample", LocalData.signalJson(sample).toString());
                values.put("legacy", legacy ? 1 : 0);
                db.insertWithOnConflict("trade_reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    Review nextReview(String portfolio) throws JSONException {
        return nextReview(portfolio, Long.MIN_VALUE);
    }

    Review nextReview(String portfolio, long since) throws JSONException {
        return nextReview(portfolio, since, Long.MAX_VALUE, null);
    }

    Review nextReview(String portfolio, long since, long through, Set<Long> visible) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor row = db.rawQuery("SELECT stamp,sample,legacy FROM trade_reviews "
                + "WHERE portfolio=? AND checked IS NULL AND provisional=0 AND stamp BETWEEN ? AND ? ORDER BY legacy,stamp DESC",
                new String[]{portfolio, Long.toString(since), Long.toString(through)})) {
            while (row.moveToNext()) {
                long stamp = row.getLong(0);
                if (visible != null && !visible.contains(stamp)) continue;
                List<Rules.Signal> sample = readReviewRows(row.getString(1));
                return new Review(stamp, sample.isEmpty() ? confirmedAt(db, portfolio, stamp) : sample, row.getInt(2) == 1);
            }
            return null;
        }
    }

    private static List<Rules.Signal> readReviewRows(String value) throws JSONException {
        return LocalData.readSignals(new JSONArray(value));
    }

    Review nextBaselineReview(String portfolio, long beforeStamp) {
        SQLiteDatabase db = getReadableDatabase();
        long first = firstCurrentOrdinal(db, portfolio);
        try (Cursor row = db.rawQuery("SELECT t.stamp FROM trades t LEFT JOIN trade_reviews r "
                + "ON r.portfolio=t.portfolio AND r.stamp=t.stamp WHERE t.portfolio=? AND t.ordinal>=? "
                + "AND t.stamp<? AND t.excluded_revision=0 AND t.kind IN (1,3,4) "
                + "AND COALESCE(r.baseline_checked,0)=0 AND COALESCE(r.provisional,0)=0 "
                + "GROUP BY t.stamp,t.symbol,t.action,t.price,t.quantity,t.turnover,t.pnl "
                + "HAVING COUNT(*)>1 ORDER BY t.stamp DESC LIMIT 1",
                new String[]{portfolio, Long.toString(first), Long.toString(beforeStamp)})) {
            if (!row.moveToFirst()) return null;
            long stamp = row.getLong(0);
            return new Review(stamp, confirmedAt(db, portfolio, stamp, first), false);
        }
    }

    boolean resolveBaselineReview(String portfolio, Review review, List<Rules.Signal> checked) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long first = firstCurrentOrdinal(db, portfolio);
            if (!TradeReview.same(confirmedAt(db, portfolio, review.stamp, first), review.sample)) {
                throw WebsiteCapture.reviewFailure("本轮核对期间历史档案已变化");
            }
            int removed = correctHistoricalTrades(db, portfolio, review.stamp, checked, true, first);
            if (removed < 0) throw WebsiteCapture.reviewFailure("历史档案与官网复核未对齐，保留已有成交进度");
            saveCheckedReview(db, portfolio, review.stamp, review.sample, checked);
            db.setTransactionSuccessful();
            return removed > 0;
        } finally {
            db.endTransaction();
        }
    }

    List<Review> provisionalReviews(String portfolio) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        List<Review> reviews = new ArrayList<>();
        try (Cursor row = db.rawQuery("SELECT r.stamp,r.sample FROM trade_reviews r WHERE r.portfolio=? AND r.provisional=1"
                + " AND EXISTS (SELECT 1 FROM trades t WHERE t.portfolio=r.portfolio AND t.stamp=r.stamp AND t.ordinal>=?)"
                + " ORDER BY r.stamp DESC", new String[]{portfolio, Long.toString(firstCurrentOrdinal(db, portfolio))})) {
            while (row.moveToNext()) reviews.add(new Review(row.getLong(0), readReviewRows(row.getString(1)), false));
        }
        return reviews;
    }

    List<Rules.Signal> normalizeProvisional(String portfolio, List<Rules.Signal> rows, BigDecimal margin)
            throws JSONException {
        if (rows.isEmpty()) return rows;
        SQLiteDatabase db = getReadableDatabase();
        Map<Long, List<Rules.Signal>> replacements = new LinkedHashMap<>();
        try (Cursor proof = db.rawQuery("SELECT stamp,sample,checked FROM trade_reviews WHERE portfolio=?"
                + " AND provisional<>0 AND stamp BETWEEN ? AND ?",
                new String[]{portfolio, Long.toString(rows.get(0).time), Long.toString(rows.get(rows.size() - 1).time)})) {
            while (proof.moveToNext()) {
                long stamp = proof.getLong(0);
                List<Rules.Signal> visible = TradeReview.at(rows, stamp);
                if (visible.isEmpty()) continue;
                List<Rules.Signal> sample = readReviewRows(proof.getString(1));
                if (!proof.isNull(2)) {
                    List<Rules.Signal> checked = readReviewRows(proof.getString(2));
                    if (TradeReview.compatible(sample, visible) && !TradeReview.same(checked, visible)) {
                        replacements.put(stamp, checked);
                    }
                    continue;
                }
                Map<String, Integer> caps = TradeReview.counts(confirmedAt(db, portfolio, stamp,
                        firstCurrentOrdinal(db, portfolio)));
                Map<String, Integer> sampled = TradeReview.counts(sample);
                caps.keySet().removeIf(key -> sampled.getOrDefault(key, 0) < 2);
                replacements.put(stamp, TradeReview.limitCopies(visible, caps));
            }
        }
        return TradeReview.replace(rows, replacements, margin);
    }

    void rememberBaselineCounts(String portfolio, List<Rules.Signal> raw, Map<Long, List<Rules.Signal>> settled)
            throws JSONException {
        if (settled.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<Long, List<Rules.Signal>> group : settled.entrySet()) {
                saveBaselineCount(db, portfolio, group.getKey(), TradeReview.at(raw, group.getKey()), group.getValue());
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    boolean settlePendingBaselineCounts(String portfolio) throws JSONException {
        List<Review> pending = provisionalReviews(portfolio);
        if (pending.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<Rules.Signal> rows = new ArrayList<>();
            long first = firstCurrentOrdinal(db, portfolio);
            try (Cursor found = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND ordinal>=? AND kind IN (1,3,4)"
                    + " AND (excluded_revision=0 OR (excluded_revision=-1 AND stamp IN "
                    + "(SELECT stamp FROM trade_reviews WHERE portfolio=? AND provisional=1))) ORDER BY stamp,ordinal",
                    new String[]{portfolio, Long.toString(first), portfolio})) {
                while (found.moveToNext()) rows.add(record(found).signal);
            }
            Set<Long> stamps = new HashSet<>();
            for (Review review : pending) stamps.add(review.stamp);
            Map<Long, List<Rules.Signal>> settled = TradeReview.baselineCounts(rows, stamps).settled;
            boolean changed = false;
            for (Review review : pending) {
                List<Rules.Signal> counted = settled.get(review.stamp);
                if (counted != null) changed |= resolveProvisionalReview(db, portfolio, review, counted, true);
            }
            db.setTransactionSuccessful();
            return changed;
        } finally {
            db.endTransaction();
        }
    }

    private static void saveBaselineCount(SQLiteDatabase db, String portfolio, long stamp, List<Rules.Signal> sample,
            List<Rules.Signal> counted) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("portfolio", portfolio);
        values.put("stamp", stamp);
        values.put("sample", LocalData.signalJson(sample).toString());
        values.put("checked", LocalData.signalJson(counted).toString());
        values.put("provisional", 2);
        values.put("baseline_checked", 1);
        db.insertWithOnConflict("trade_reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    boolean hasCheckedReview(String portfolio, long stamp, List<Rules.Signal> rows) throws JSONException {
        try (Cursor proof = getReadableDatabase().rawQuery("SELECT sample,checked FROM trade_reviews "
                + "WHERE portfolio=? AND stamp=? AND checked IS NOT NULL",
                new String[]{portfolio, Long.toString(stamp)})) {
            return proof.moveToFirst() && (TradeReview.same(rows, readReviewRows(proof.getString(0)))
                    || TradeReview.same(rows, readReviewRows(proof.getString(1))));
        }
    }

    boolean resolveProvisionalReview(String portfolio, Review review, List<Rules.Signal> checked, boolean closedProof)
            throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!resolveProvisionalReview(db, portfolio, review, checked, closedProof)) return false;
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private boolean resolveProvisionalReview(SQLiteDatabase db, String portfolio, Review review,
            List<Rules.Signal> checked, boolean closedProof) throws JSONException {
        // Changing the date filter can return the same duplicate cards. An unchanged count
        // needs the independent reconciliation of the entire closed position.
        if (!TradeReview.compatible(review.sample, checked)
                || (!closedProof && checked.size() >= review.sample.size())) return false;
        try (Cursor pending = db.rawQuery("SELECT 1 FROM trade_reviews WHERE portfolio=? AND stamp=? AND provisional=1",
                new String[]{portfolio, Long.toString(review.stamp)})) {
            if (!pending.moveToFirst()) return false;
        }
        long first = firstCurrentOrdinal(db, portfolio);
        List<Record> observed = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND stamp=?"
                + " AND ordinal>=? AND excluded_revision IN(0,-1) ORDER BY ordinal",
                new String[]{portfolio, Long.toString(review.stamp), Long.toString(first)})) {
            while (rows.moveToNext()) observed.add(record(rows));
        }
        if (!TradeReview.same(review.sample, observed.stream().map(r -> r.signal).collect(Collectors.toList()))) return false;
        List<Record> kept = new ArrayList<>();
        int next = 0;
        for (Record row : observed) {
            if (next < checked.size() && TradeReview.key(row.signal).equals(TradeReview.key(checked.get(next)))) {
                if (row.excluded && row.signal.action.sideOnly()) return false;
                kept.add(row);
                next++;
            }
        }
        if (next < checked.size()) return false;
        List<Rules.Signal> accepted = kept.stream().map(row -> row.signal).collect(Collectors.toList());
        State before = read(db, portfolio);
        Map<String, LeaderHistory.Holding> prior = replay(db, portfolio);
        long revision = revision(db, portfolio) + 1;
        Set<String> legs = new LinkedHashSet<>();
        for (Record row : observed) {
            boolean include = kept.contains(row);
            if (include != row.excluded) continue;
            ContentValues change = new ContentValues();
            change.put("excluded_revision", include ? 0 : revision);
            if (include) change.put("included_revision", revision);
            db.update("trades", change, "portfolio=? AND event=?", new String[]{portfolio, row.signal.id});
            legs.add(row.signal.leg());
        }
        if (!legs.isEmpty()) {
            Map<String, LeaderHistory.Holding> latest = new LinkedHashMap<>(before.holdings);
            Map<String, LeaderHistory.Holding> corrected = replay(db, portfolio);
            for (String leg : legs) {
                if (visibleSize(prior, leg).compareTo(visibleSize(latest, leg)) != 0) continue;
                if (corrected.containsKey(leg)) latest.put(leg, corrected.get(leg));
                else latest.remove(leg);
            }
            List<Rules.Signal> window = TradeReview.replace(before.window,
                    Collections.singletonMap(review.stamp, accepted), null);
            ContentValues values = new ContentValues();
            values.put("snapshot", LocalData.signalJson(window).toString());
            values.put("estimate", holdingsJson(latest));
            db.update("leader", values, "portfolio=?", new String[]{portfolio});
        }
        saveBaselineCount(db, portfolio, review.stamp, review.sample, accepted);
        return true;
    }

    boolean hasArchivedRepeats(String portfolio) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT 1 FROM trades WHERE portfolio=? AND ordinal>=? "
                + "AND excluded_revision=0 AND NOT EXISTS (SELECT 1 FROM trade_reviews r "
                + "WHERE r.portfolio=trades.portfolio AND r.stamp=trades.stamp AND r.provisional=2) "
                + "GROUP BY stamp,symbol,action,price,quantity,turnover,pnl "
                + "HAVING COUNT(*)>1 AND SUM(kind IN (1,3,4))>0 LIMIT 1",
                new String[]{portfolio, Long.toString(firstCurrentOrdinal(getReadableDatabase(), portfolio))})) {
            return row.moveToFirst();
        }
    }

    List<Rules.Signal> normalizeReviewedDuplicates(String portfolio, List<Rules.Signal> rows, BigDecimal margin)
            throws JSONException {
        Map<Long, List<Rules.Signal>> verified = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Rules.Signal>> entry : TradeReview.repeatedSeconds(rows).entrySet()) {
            try (Cursor row = getReadableDatabase().rawQuery("SELECT sample,checked FROM trade_reviews "
                    + "WHERE portfolio=? AND stamp=? AND checked IS NOT NULL",
                    new String[]{portfolio, Long.toString(entry.getKey())})) {
                if (!row.moveToFirst()) continue;
                List<Rules.Signal> checked = readReviewRows(row.getString(1));
                if (checked.size() < entry.getValue().size() && TradeReview.same(entry.getValue(), readReviewRows(row.getString(0)))) {
                    verified.put(entry.getKey(), checked);
                }
            }
        }
        return TradeReview.replace(rows, verified, margin);
    }

    List<Rules.Signal> normalizeArchiveOverlap(String portfolio, State state, List<Rules.Signal> rows, BigDecimal margin)
            throws JSONException {
        rows = normalizeProvisional(portfolio, normalizeReviewedDuplicates(portfolio, rows, margin), margin);
        if (!state.historyComplete || state.latest() == null) return rows;
        long tail = state.latest().time;
        SQLiteDatabase db = getReadableDatabase();
        long first = firstCurrentOrdinal(db, portfolio);
        Map<Long, List<Rules.Signal>> archived = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Rules.Signal>> group : TradeReview.repeatedSeconds(rows).entrySet()) {
            if (group.getKey() >= tail) continue;
            List<Rules.Signal> known = confirmedAt(db, portfolio, group.getKey(), first);
            if (known.size() < group.getValue().size() && TradeReview.compatible(known, group.getValue())) {
                // An overlapping second older than the tail cannot add another copy to this session.
                archived.put(group.getKey(), known);
            }
        }
        return TradeReview.replace(rows, archived, margin);
    }

    long reviewFromArchiveTail(String portfolio, State state, List<Rules.Signal> rows) {
        long tail = state.latest().time;
        SQLiteDatabase db = getReadableDatabase();
        List<Rules.Signal> known = confirmedAt(db, portfolio, tail, firstCurrentOrdinal(db, portfolio));
        if (!TradeReview.same(known, TradeReview.at(rows, tail))) return tail;
        try (Cursor found = db.rawQuery("SELECT 1 FROM trades WHERE portfolio=? AND stamp=? "
                + "AND kind=-2 AND excluded_revision=0 LIMIT 1", new String[]{portfolio, Long.toString(tail)})) {
            return found.moveToFirst() ? tail : tail + 1;
        }
    }

    List<Rules.Signal> reconcileArchiveCloses(String portfolio, State state, List<Rules.Signal> rows,
            long since, BigDecimal margin, boolean completeSwitch) throws JSONException {
        if (state.latest() == null || rows.isEmpty()) return rows;
        long tail = state.latest().time;
        boolean disjoint = completeSwitch && rows.get(0).time > tail;
        if (!disjoint && (!state.historyComplete || TradeReview.at(rows, tail).isEmpty())) return rows;
        long reviewStart = Math.max(since, tail);
        Map<Long, List<Rules.Signal>> samples = TradeReview.repeatedSeconds(rows);
        samples.keySet().removeIf(stamp -> stamp < reviewStart);

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<Long, List<Rules.Signal>> pendingSamples = pendingArchiveSamples(db, portfolio, rows, reviewStart);
            samples.putAll(pendingSamples);
            if (samples.isEmpty()) return rows;
            long first = firstCurrentOrdinal(db, portfolio);
            List<Rules.Signal> evidence = new ArrayList<>();
            if (!disjoint) {
                // The opening may predate the visible page. Use only the confirmed journal of this session.
                try (Cursor trades = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? "
                        + "AND ordinal BETWEEN ? AND ? AND stamp<? AND excluded_revision=0 ORDER BY stamp,ordinal",
                        new String[]{portfolio, Long.toString(first), Long.toString(state.head), Long.toString(tail)})) {
                    while (trades.moveToNext()) evidence.add(record(trades).signal);
                }
            }
            for (Rules.Signal row : TradeReview.replace(rows, pendingSamples, margin)) {
                if (row.time >= tail) evidence.add(row);
            }
            Map<Long, List<Rules.Signal>> settled = TradeReview.archiveCloseCounts(evidence, reviewStart);
            if (settled.isEmpty()) return rows;

            Set<String> unverifiedLegs = new HashSet<>();
            if (!disjoint) {
                for (Review review : provisionalReviews(portfolio)) {
                    unverifiedLegs.addAll(TradeReview.repeatedLegs(review.sample));
                }
                Map<String, LeaderHistory.Holding> replayed = replay(db, portfolio, state.head);
                state.holdings.forEach((leg, holding) -> {
                    if (holding.inferredQuantity != null
                            || visibleSize(replayed, leg).compareTo(holding.quantity) != 0) unverifiedLegs.add(leg);
                });
                replayed.keySet().stream().filter(leg -> !state.holdings.containsKey(leg)).forEach(unverifiedLegs::add);
            }
            for (Map.Entry<Long, List<Rules.Signal>> group : settled.entrySet()) {
                long stamp = group.getKey();
                List<Rules.Signal> sample = samples.get(stamp);
                List<Rules.Signal> kept = TradeReview.limitCopies(TradeReview.at(rows, stamp),
                        TradeReview.counts(group.getValue()));
                if (!canConfirmArchiveClose(db, portfolio, stamp, sample, kept)) {
                    unverifiedLegs.addAll(TradeReview.repeatedLegs(sample));
                }
                // Keep the current page's records and metadata when the extra copies came from a saved review.
                group.setValue(kept);
            }
            settled.keySet().removeIf(stamp -> !Collections.disjoint(unverifiedLegs,
                    TradeReview.repeatedLegs(samples.get(stamp))));
            if (settled.isEmpty()) return rows;
            List<Rules.Signal> verified = TradeReview.replace(rows, settled, margin);
            if (!disjoint) {
                List<Rules.Signal> known = confirmedAt(db, portfolio, tail, first);
                if (known.isEmpty()) return rows;
                LeaderHistory.Merge merge = LeaderHistory.merge(captureReference(db, portfolio, state), verified,
                        true, !state.orderedSnapshot);
                List<Rules.Signal> overlap = merge.rows.subList(0, merge.rows.size() - merge.added.size());
                // Quantity proof cannot stand in for an archived trade or bridge a missing page.
                LeaderHistory.pageGrowth(known, TradeReview.at(overlap, tail));
            }
            for (Map.Entry<Long, List<Rules.Signal>> group : settled.entrySet()) {
                long stamp = group.getKey();
                excludeUnconfirmedRepeats(db, portfolio, stamp, group.getValue());
                saveCheckedReview(db, portfolio, stamp, samples.get(stamp), group.getValue());
            }
            db.setTransactionSuccessful();
            return verified;
        } catch (LeaderHistory.Gap missing) {
            return rows;
        } finally {
            db.endTransaction();
        }
    }

    private static boolean canConfirmArchiveClose(SQLiteDatabase db, String portfolio, long stamp,
            List<Rules.Signal> sample, List<Rules.Signal> kept) throws JSONException {
        try (Cursor saved = db.rawQuery("SELECT sample,checked,legacy,provisional FROM trade_reviews "
                + "WHERE portfolio=? AND stamp=?", new String[]{portfolio, Long.toString(stamp)})) {
            if (!saved.moveToFirst()) return true;
            if (saved.getInt(2) != 0 || saved.getInt(3) != 0) return false;
            List<Rules.Signal> previous = readReviewRows(saved.getString(0));
            return saved.isNull(1)
                    ? TradeReview.compatible(previous, sample) || TradeReview.hasAdditionalDetails(previous, sample)
                    : TradeReview.compatible(previous, sample) && TradeReview.same(readReviewRows(saved.getString(1)), kept);
        }
    }

    List<Rules.Signal> retainPendingArchiveSamples(String portfolio, State state, List<Rules.Signal> rows,
            BigDecimal margin) throws JSONException {
        Map<Long, List<Rules.Signal>> samples = pendingArchiveSamples(getReadableDatabase(), portfolio, rows,
                state.latest().time + 1);
        samples.values().removeIf(sample -> !TradeReview.exactCopies(sample));
        return TradeReview.replace(rows, samples, margin);
    }

    private static Map<Long, List<Rules.Signal>> pendingArchiveSamples(SQLiteDatabase db, String portfolio,
            List<Rules.Signal> rows, long since) throws JSONException {
        Map<Long, List<Rules.Signal>> samples = new LinkedHashMap<>();
        try (Cursor pending = db.rawQuery("SELECT stamp,sample FROM trade_reviews WHERE portfolio=? "
                + "AND checked IS NULL AND provisional=0 AND legacy=0 AND stamp BETWEEN ? AND ?",
                new String[]{portfolio, Long.toString(since), Long.toString(rows.get(rows.size() - 1).time)})) {
            while (pending.moveToNext()) {
                long stamp = pending.getLong(0);
                List<Rules.Signal> sample = readReviewRows(pending.getString(1));
                List<Rules.Signal> visible = TradeReview.at(rows, stamp);
                if (sample.size() > visible.size() && TradeReview.compatible(sample, visible)) samples.put(stamp, sample);
            }
        }
        return samples;
    }

    Map<Long, List<Rules.Signal>> stageableArchiveReviews(String portfolio, State state, List<Rules.Signal> rows,
            boolean completeSwitch) throws JSONException {
        Map<Long, List<Rules.Signal>> pending = new LinkedHashMap<>();
        long tail = state.latest().time;
        boolean disjoint = completeSwitch && rows.get(0).time > tail;
        if (!disjoint && !connectsToTail(portfolio, state, rows)) return pending;
        SQLiteDatabase db = getReadableDatabase();
        for (Map.Entry<Long, List<Rules.Signal>> group : TradeReview.repeatedSeconds(rows).entrySet()) {
            long stamp = group.getKey();
            List<Rules.Signal> sample = group.getValue();
            if (stamp <= tail || !TradeReview.exactCopies(sample)) continue;
            // Defer only untouched seconds. Confirmed trades and existing independent proofs keep their identities.
            List<Rules.Signal> kept = TradeReview.distinctExactCopies(sample);
            if (confirmedAt(db, portfolio, stamp).isEmpty() && !hasCheckedReview(portfolio, stamp, sample)
                    && canConfirmArchiveClose(db, portfolio, stamp, sample, kept)) pending.put(stamp, kept);
        }
        return pending;
    }

    int confirmClosedPositionReviews(String portfolio, List<Rules.Signal> rows,
            List<ClosedTradeReview.Position> positions) throws JSONException {
        if (positions.isEmpty()) return 0;
        long first = positions.stream().mapToLong(ClosedTradeReview.Position::openedAt).min().orElse(Long.MAX_VALUE);
        long last = positions.stream().mapToLong(ClosedTradeReview.Position::closedAt).max().orElse(Long.MIN_VALUE);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<Review> provisional = provisionalReviews(portfolio);
            List<Rules.Signal> archived = new ArrayList<>();
            try (Cursor found = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? "
                    + "AND ordinal>=? AND stamp BETWEEN ? AND ? AND (excluded_revision=0 OR "
                    + "(excluded_revision=-1 AND stamp IN (SELECT stamp FROM trade_reviews WHERE portfolio=? AND provisional=1))) "
                    + "ORDER BY stamp,ordinal",
                    new String[]{portfolio, Long.toString(firstCurrentOrdinal(db, portfolio)),
                            Long.toString(first), Long.toString(last), portfolio})) {
                while (found.moveToNext()) archived.add(record(found).signal);
            }
            Map<Long, List<Rules.Signal>> proof = ClosedTradeReview.confirmedCounts(archived, positions);
            int repaired = 0;
            Set<Long> held = new HashSet<>();
            for (Review review : provisional) {
                held.add(review.stamp);
                List<Rules.Signal> checked = proof.get(review.stamp);
                if (checked != null && resolveProvisionalReview(db, portfolio, review, checked, true)) repaired++;
            }
            repaired += saveClosedPositionReviews(db, portfolio, archived, positions, held);
            repaired += saveClosedPositionReviews(db, portfolio, rows, positions, held);
            db.setTransactionSuccessful();
            return repaired;
        } finally {
            db.endTransaction();
        }
    }

    private int saveClosedPositionReviews(SQLiteDatabase db, String portfolio, List<Rules.Signal> source,
            List<ClosedTradeReview.Position> positions, Set<Long> held) throws JSONException {
        int repaired = 0;
        for (Map.Entry<Long, List<Rules.Signal>> entry : ClosedTradeReview.confirmedCounts(source, positions).entrySet()) {
            long stamp = entry.getKey();
            if (held.contains(stamp)) continue;
            try (Cursor proof = db.rawQuery("SELECT 1 FROM trade_reviews WHERE portfolio=? AND stamp=? AND provisional=2",
                    new String[]{portfolio, Long.toString(stamp)})) {
                if (proof.moveToFirst()) continue;
            }
            List<Rules.Signal> checked = entry.getValue();
            List<Rules.Signal> prior = confirmedAt(db, portfolio, stamp);
            if (!prior.isEmpty() && !TradeReview.compatible(prior, checked)) continue;
            if (prior.size() > checked.size()) {
                int removed = correctHistoricalTrades(db, portfolio, stamp, checked, true, Long.MIN_VALUE);
                if (removed < 0) continue;
                repaired += removed;
            }
            List<Rules.Signal> sample = TradeReview.at(source, stamp);
            excludeUnconfirmedRepeats(db, portfolio, stamp, checked);
            saveCheckedReview(db, portfolio, stamp, sample, checked);
        }
        return repaired;
    }

    List<Rules.Signal> verifiedRows(String portfolio, List<Rules.Signal> raw, BigDecimal margin,
            Map<Long, List<Rules.Signal>> justChecked) throws Exception {
        return verifiedRows(portfolio, raw, margin, justChecked, Long.MIN_VALUE);
    }

    List<Rules.Signal> verifiedRows(String portfolio, List<Rules.Signal> raw, BigDecimal margin,
            Map<Long, List<Rules.Signal>> justChecked, long reviewFrom) throws Exception {
        return verifiedRows(portfolio, raw, margin, justChecked, reviewFrom, Collections.emptySet());
    }

    List<Rules.Signal> verifiedRows(String portfolio, List<Rules.Signal> raw, BigDecimal margin,
            Map<Long, List<Rules.Signal>> justChecked, long reviewFrom, Set<Long> provisional) throws Exception {
        Map<Long, List<Rules.Signal>> verified = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Rules.Signal>> entry : justChecked.entrySet()) {
            long stamp = entry.getKey();
            if (stamp < raw.get(0).time || stamp > raw.get(raw.size() - 1).time) continue;
            List<Rules.Signal> visible = TradeReview.at(raw, stamp), checked = entry.getValue();
            if (!TradeReview.compatible(visible, checked) && !TradeReview.hasAdditionalDetails(visible, checked)) {
                throw WebsiteCapture.reviewFailure("两次读取的成交明细无法对齐，等待重新读取");
            }
            verified.put(stamp, checked);
        }
        for (Map.Entry<Long, List<Rules.Signal>> entry : TradeReview.repeatedSeconds(raw).entrySet()) {
            if (entry.getKey() < reviewFrom || verified.containsKey(entry.getKey()) || provisional.contains(entry.getKey())) continue;
            try (Cursor row = getReadableDatabase().rawQuery("SELECT sample,checked,provisional FROM trade_reviews WHERE portfolio=? AND stamp=?",
                    new String[]{portfolio, Long.toString(entry.getKey())})) {
                if (!row.moveToFirst()) throw WebsiteCapture.reviewFailure("仍需独立核对这组成交");
                if (row.getInt(2) != 0) continue;
                if (row.isNull(1)) throw WebsiteCapture.reviewFailure("仍需独立核对这组成交");
                List<Rules.Signal> checked = readReviewRows(row.getString(1));
                if (!TradeReview.same(entry.getValue(), readReviewRows(row.getString(0)))
                        && !TradeReview.same(entry.getValue(), checked)) {
                    throw WebsiteCapture.reviewFailure("同秒列表发生变化，等待新一轮读取");
                }
                verified.put(entry.getKey(), checked);
            }
        }
        return TradeReview.replace(raw, verified, margin);
    }

    boolean resolveReview(String portfolio, Review review, List<Rules.Signal> checked) throws Exception {
        if (!TradeReview.compatible(review.sample, checked)
                && (review.legacy || !TradeReview.hasAdditionalDetails(review.sample, checked))) {
            throw WebsiteCapture.reviewFailure("两次官网读取的成交明细或顺序不同");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean corrected = review.legacy && correctSupplement(db, portfolio, review.stamp, checked);
            long first = firstCurrentOrdinal(db, portfolio);
            List<Rules.Signal> prior = confirmedAt(db, portfolio, review.stamp, first);
            boolean baselineChecked = prior.isEmpty() || TradeReview.same(prior, checked);
            if (!review.legacy && prior.size() > checked.size()) {
                int removed = correctHistoricalTrades(db, portfolio, review.stamp, checked, true, first);
                corrected |= removed > 0;
                baselineChecked = removed >= 0;
            }
            if (review.sample.size() > checked.size()) {
                corrected |= excludeUnconfirmedRepeats(db, portfolio, review.stamp, checked);
            }
            ContentValues proof = checkedReviewValues(review.sample, checked);
            proof.put("baseline_checked", baselineChecked ? 1 : 0);
            db.update("trade_reviews", proof, "portfolio=? AND stamp=? AND checked IS NULL",
                    new String[]{portfolio, Long.toString(review.stamp)});
            db.setTransactionSuccessful();
            return corrected;
        } finally { db.endTransaction(); }
    }

    private static void saveCheckedReview(SQLiteDatabase db, String portfolio, long stamp,
            List<Rules.Signal> sample, List<Rules.Signal> checked) throws JSONException {
        ContentValues proof = checkedReviewValues(sample, checked);
        proof.put("portfolio", portfolio);
        proof.put("stamp", stamp);
        db.insertWithOnConflict("trade_reviews", null, proof, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static ContentValues checkedReviewValues(List<Rules.Signal> sample, List<Rules.Signal> checked)
            throws JSONException {
        ContentValues proof = new ContentValues();
        proof.put("sample", LocalData.signalJson(sample).toString());
        proof.put("checked", LocalData.signalJson(checked).toString());
        proof.put("legacy", 0);
        proof.put("baseline_checked", 1);
        return proof;
    }

    private boolean excludeUnconfirmedRepeats(SQLiteDatabase db, String portfolio, long stamp, List<Rules.Signal> checked) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (Rules.Signal row : checked) remaining.merge(TradeReview.key(row), 1, Integer::sum);
        for (Rules.Signal row : confirmedAt(db, portfolio, stamp)) {
            remaining.computeIfPresent(TradeReview.key(row), (key, count) -> count - 1);
        }
        List<String> repeats = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND stamp=? "
                + "AND kind=-2 AND ordinal IS NULL AND excluded_revision=0 ORDER BY rowid",
                new String[]{portfolio, Long.toString(stamp)})) {
            while (rows.moveToNext()) {
                Rules.Signal row = record(rows).signal;
                Integer left = remaining.computeIfPresent(TradeReview.key(row), (key, count) -> count - 1);
                if (left != null && left < 0) repeats.add(row.id);
            }
        }
        if (repeats.isEmpty()) return false;
        ContentValues update = new ContentValues();
        update.put("excluded_revision", revision(db, portfolio) + 1);
        for (String id : repeats) db.update("trades", update, "portfolio=? AND event=?", new String[]{portfolio, id});
        return true;
    }

    private boolean correctSupplement(SQLiteDatabase db, String portfolio, long stamp, List<Rules.Signal> checked) throws Exception {
        int changed = correctHistoricalTrades(db, portfolio, stamp, checked, false, Long.MIN_VALUE);
        if (changed < 0) throw WebsiteCapture.reviewFailure("历史档案与官网复核未对齐，保留已有成交进度");
        return changed > 0;
    }

    private int correctHistoricalTrades(SQLiteDatabase db, String portfolio, long stamp, List<Rules.Signal> checked,
            boolean allowBaseline, long first) throws JSONException {
        List<Record> records = confirmedRecords(db, portfolio, stamp, first);
        List<Rules.Signal> prior = records.stream().map(row -> row.signal).collect(Collectors.toList());
        if (!TradeReview.compatible(prior, checked) || prior.size() < checked.size()) return -1;
        if (TradeReview.same(prior, checked)) return 0;
        // Keep the newest matching subsequence so an existing non-supplement anchor keeps its original ID.
        Set<String> excluded = new HashSet<>();
        Set<String> changed = new HashSet<>();
        int expected = checked.size() - 1;
        for (int i = records.size() - 1; i >= 0; i--) {
            Record row = records.get(i);
            if (expected >= 0 && TradeReview.key(row.signal).equals(TradeReview.key(checked.get(expected)))) expected--;
            else {
                if (row.kind != SUPPLEMENT && (!allowBaseline || (row.kind != 1 && row.kind != RECONNECTED_BASELINE))) return -1;
                excluded.add(row.signal.id);
                changed.add(row.signal.leg());
            }
        }
        State before = read(db, portfolio);
        Map<String, LeaderHistory.Holding> original = replay(db, portfolio);
        ContentValues revision = new ContentValues();
        revision.put("excluded_revision", revision(db, portfolio) + 1);
        for (String id : excluded) {
            db.update("trades", revision, "portfolio=? AND event=? AND excluded_revision=0",
                    new String[]{portfolio, id});
        }
        Map<String, LeaderHistory.Holding> corrected = replay(db, portfolio);
        Map<String, LeaderHistory.Holding> latest = new LinkedHashMap<>(before.holdings);
        for (String leg : changed) {
            if (visibleSize(original, leg).compareTo(visibleSize(latest, leg)) != 0) continue;
            if (corrected.containsKey(leg)) latest.put(leg, corrected.get(leg));
            else latest.remove(leg);
        }
        List<Rules.Signal> window = new ArrayList<>(before.window);
        window.removeIf(row -> excluded.contains(row.id));
        ContentValues updates = new ContentValues();
        updates.put("snapshot", LocalData.signalJson(window).toString());
        updates.put("estimate", holdingsJson(latest));
        updates.put("history_detail", before.historyDetail + " · 历史重复已归并 " + excluded.size() + " 笔");
        db.update("leader", updates, "portfolio=?", new String[]{portfolio});
        return excluded.size();
    }

    long revision(String portfolio) { return revision(getReadableDatabase(), portfolio); }

    private static long revision(SQLiteDatabase db, String portfolio) {
        try (Cursor row = db.rawQuery("SELECT COALESCE(MAX(MAX(excluded_revision,included_revision)),0) FROM trades WHERE portfolio=?",
                new String[]{portfolio})) {
            return row.moveToFirst() ? row.getLong(0) : 0;
        }
    }

    void noteBaselineProgress(String portfolio, String name, String detail) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureLeader(db, portfolio);
            ContentValues update = new ContentValues();
            if (!name.isEmpty()) update.put("title", name);
            update.put("history_detail", detail);
            db.update("leader", update, "portfolio=? AND history_complete=0", new String[]{portfolio});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    Map<String, BigDecimal> visibleAt(String portfolio, long cursor, long backfilled) {
        return visibleAt(portfolio, cursor, backfilled, Long.MAX_VALUE);
    }

    Map<String, BigDecimal> visibleAt(String portfolio, long cursor, long backfilled, long revision) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        long first = Math.max(-backfilled, firstCurrentOrdinal(db, portfolio));
        try (Cursor trades = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades "
                + "WHERE portfolio=? AND ordinal>=? AND ordinal<=? AND (excluded_revision=0 OR excluded_revision>?) "
                + "AND included_revision<=? ORDER BY ordinal",
                new String[]{portfolio, Long.toString(first), Long.toString(cursor), Long.toString(revision), Long.toString(revision)})) {
            while (trades.moveToNext()) {
                Rules.Signal signal = record(trades).signal;
                if (signal.action.sideOnly()) {
                    result.remove(signal.symbol + "|LONG");
                    result.remove(signal.symbol + "|SHORT");
                    continue;
                }
                String leg = signal.leg();
                BigDecimal remaining = LeaderHistory.after(result.getOrDefault(leg, Rules.ZERO), signal);
                if (remaining.signum() == 0) result.remove(leg);
                else result.put(leg, remaining);
            }
        }
        return result;
    }

    void saveName(String portfolio, String name) throws JSONException {
        if (name.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureLeader(db, portfolio);
            ContentValues data = new ContentValues();
            data.put("title", name);
            db.update("leader", data, "portfolio=?", new String[]{portfolio});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    State migrate(String portfolio, List<Rules.Signal> rows, Map<String, BigDecimal> known, long now)
            throws JSONException {
        if (rows.isEmpty()) return state(portfolio);
        LeaderHistory.verify(rows);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            State before = read(db, portfolio);
            if (before.head != 0) return before;
            Map<String, LeaderHistory.Holding> holdings = new LinkedHashMap<>();
            long head = 0;
            for (Rules.Signal row : rows) {
                putTrade(db, portfolio, row, ++head, 2, 0);
                LeaderHistory.apply(holdings, row);
            }
            for (Map.Entry<String, BigDecimal> leg : known.entrySet()) {
                if (leg.getValue().signum() > 0) holdings.put(leg.getKey(), keepSize(holdings.get(leg.getKey()), leg.getValue()));
                else holdings.remove(leg.getKey());
            }
            State current = new State(before.name, now, now, head, false, new ArrayList<>(rows), holdings);
            write(db, portfolio, current);
            db.setTransactionSuccessful();
            return current;
        } finally { db.endTransaction(); }
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now) throws JSONException {
        return capture(portfolio, name, rows, now, false, "");
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now,
            boolean historyEnded, String pagingDetail) throws JSONException {
        return capture(portfolio, name, rows, now, historyEnded, pagingDetail, false);
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now,
            boolean historyEnded, String pagingDetail, boolean recoveryRead) throws JSONException {
        return capture(portfolio, name, rows, now, historyEnded, pagingDetail, recoveryRead, 0);
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now,
            boolean historyEnded, String pagingDetail, boolean recoveryRead, long selection) throws JSONException {
        return capture(portfolio, name, rows, now, historyEnded, pagingDetail, recoveryRead, selection, false);
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now,
            boolean historyEnded, String pagingDetail, boolean recoveryRead, long selection, boolean joinedArchive)
            throws JSONException {
        return capture(portfolio, name, rows, now, historyEnded, pagingDetail, recoveryRead, selection,
                joinedArchive, Collections.emptyMap());
    }

    Update capture(String portfolio, String name, List<Rules.Signal> rows, long now, boolean historyEnded,
            String pagingDetail, boolean recoveryRead, long selection, boolean joinedArchive,
            Map<Long, List<Rules.Signal>> provisional) throws JSONException {
        if (!rows.isEmpty()) LeaderHistory.verify(rows);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            State before = read(db, portfolio);
            String title = name.isEmpty() ? before.name : name;
            boolean baseline = before.head == 0 && !before.historyComplete;
            boolean switched = selection > 0 && selection != selectionColumn(db, portfolio, "verified_selection");
            boolean returningFromEmpty = before.head > 0 && before.window.isEmpty();
            boolean trustedEmpty = returningFromEmpty && selectionColumn(db, portfolio, "trusted_empty") != 0;
            if (joinedArchive && (!before.historyComplete || before.window.isEmpty() || rows.isEmpty())) {
                throw new LeaderHistory.Gap();
            }
            if ((switched || (returningFromEmpty && !trustedEmpty)) && !historyEnded && !joinedArchive) {
                throw new IllegalArgumentException("本次跟单需要完整展开官网成交记录");
            }
            if (rows.isEmpty()) {
                if (switched || (returningFromEmpty && !trustedEmpty)) {
                    Update update = startSegment(db, portfolio, title, rows, now, before, pagingDetail,
                            selection, true, Collections.emptyMap());
                    db.setTransactionSuccessful();
                    return update;
                }
                if (!before.window.isEmpty() || (!historyEnded && !before.historyComplete)) throw new LeaderHistory.Gap();
                State empty = new State(title, before.started == 0 ? now : before.started, now, before.head, false,
                        Collections.emptyList(), Collections.emptyMap(), before.backfilled, true,
                        pagingDetail.isEmpty() ? before.historyDetail : pagingDetail, before.recoveryHead, true);
                write(db, portfolio, empty);
                db.setTransactionSuccessful();
                return new Update(empty, 0, baseline, true);
            }
            boolean expanding = !before.historyComplete && !pagingDetail.isEmpty();
            long backfilled = before.backfilled;
            boolean historyComplete = before.historyComplete;
            String historyDetail = expanding ? pagingDetail : before.historyDetail;
            List<Rules.Signal> reference = captureReference(db, portfolio, before);
            LeaderHistory.Merge merge;
            try { merge = LeaderHistory.merge(reference, rows,
                    expanding || switched || recoveryRead || returningFromEmpty || joinedArchive, !before.orderedSnapshot); }
            catch (LeaderHistory.Gap gap) {
                if (joinedArchive) throw gap;
                long cutoff = reference.get(reference.size() - 1).time;
                if (switched || returningFromEmpty) {
                    List<Rules.Signal> newSegment = new ArrayList<>();
                    for (Rules.Signal row : rows) if (row.time > cutoff) newSegment.add(row);
                    if (!newSegment.isEmpty()) {
                        boolean asBaseline = switched || !trustedEmpty || newSegment.size() != rows.size();
                        if (asBaseline && !historyEnded) throw gap;
                        Update update = startSegment(db, portfolio, title, newSegment, now, before, pagingDetail,
                                selection, asBaseline, provisional);
                        db.setTransactionSuccessful();
                        return update;
                    }
                }
                for (Rules.Signal row : rows) putIfNew(db, portfolio, row, row.time >= cutoff ? -2 : -1, now);
                State current = new State(title, before.started, before.captured, before.head, true,
                        before.window, before.holdings, backfilled, historyComplete,
                        (pagingDetail.isEmpty() ? historyDetail : pagingDetail) + " · 等待衔接旧档案",
                        before.recoveryHead, before.orderedSnapshot);
                write(db, portfolio, current);
                db.setTransactionSuccessful();
                return new Update(current, 0, false, false);
            }
            rows = merge.rows;
            List<Rules.Signal> added = merge.added;
            List<Rules.Signal> older = expanding && !baseline
                    ? olderThanBaseline(db, portfolio, rows, rows.size() - added.size(), !before.orderedSnapshot)
                    : Collections.emptyList();
            boolean rebuild = false;
            for (Rules.Signal row : rows) {
                rebuild |= fillMissing(db, portfolio, row.id, "pnl", row.realizedPnl);
                rebuild |= fillMissing(db, portfolio, row.id, "turnover", row.turnover);
                fillMissing(db, portfolio, row.id, "lead_margin", row.leaderMargin);
            }
            Map<String, LeaderHistory.Holding> holdings = new LinkedHashMap<>(before.holdings);
            if (expanding) {
                if (older == null) historyDetail += " · 最早成交待核对";
                else {
                    historyComplete = historyEnded;
                    if (!older.isEmpty()) {
                        Map<String, LeaderHistory.Holding> previousReplay = replay(db, portfolio);
                        long first = -backfilled - older.size();
                        Set<String> imported = new HashSet<>();
                        for (Rules.Signal row : older) {
                            Rules.Signal archived = putTrade(db, portfolio, row, first++, SUPPLEMENT, now);
                            if (archived != row) Collections.replaceAll(rows, row, archived);
                            imported.add(row.id);
                        }
                        added.removeIf(row -> imported.contains(row.id));
                        Map<String, LeaderHistory.Holding> mergedReplay = replay(db, portfolio);
                        Set<String> sides = new HashSet<>(holdings.keySet());
                        sides.addAll(mergedReplay.keySet());
                        // Retain legacy checkpoints whose sizes cannot be reconstructed from their saved rows.
                        for (String side : sides) {
                            if (visibleSize(holdings, side).compareTo(visibleSize(previousReplay, side)) != 0) continue;
                            LeaderHistory.Holding merged = mergedReplay.get(side);
                            if (merged == null) holdings.remove(side);
                            else holdings.put(side, merged);
                        }
                        backfilled += older.size();
                        historyDetail += " · 补录 " + older.size() + " 笔";
                    }
                }
            }
            long head = before.head;
            Set<String> deferred = (baseline && historyEnded || switched && (historyEnded || joinedArchive))
                    ? saveProvisionalReviews(db, portfolio, baseline ? rows : added, provisional) : Collections.emptySet();
            for (Rules.Signal row : added) {
                boolean hidden = deferred.contains(row.id);
                int kind = baseline ? 1 : hidden ? RECONNECTED_BASELINE : 0;
                Rules.Signal archived = putTrade(db, portfolio, row, ++head, kind, now);
                if (archived != row) Collections.replaceAll(rows, row, archived);
                if (hidden) {
                    hideProvisionalCopy(db, portfolio, archived.id);
                    rows.remove(archived);
                } else LeaderHistory.apply(holdings, archived);
            }
            if (switched || returningFromEmpty) {
                long first = returningFromEmpty ? firstRetainedOrdinal(db, portfolio, rows, before.head) : 0;
                finalizeSelection(db, portfolio, selection, first, false);
                if (returningFromEmpty) holdings = replay(db, portfolio);
                historyComplete = true;
                historyDetail = pagingDetail + (before.head > 0 ? " · 已衔接旧档案" : " · 已建立本次跟单基线");
            }
            if (rebuild) holdings = replayHoldings(db, portfolio, holdings);
            boolean unresolved = unresolved(db, portfolio);
            if (expanding && unresolved) {
                historyComplete = false;
                historyDetail += " · 成交缺口待衔接";
            }
            long recoveryHead = !baseline && (recoveryRead || before.gap) ? head : before.recoveryHead;
            State current = new State(title, baseline ? now : before.started, now, head, unresolved,
                    rows, holdings, backfilled, historyComplete, historyDetail, recoveryHead, true);
            write(db, portfolio, current);
            db.setTransactionSuccessful();
            return new Update(current, baseline ? 0 : added.size() - deferred.size(),
                    baseline || switched || returningFromEmpty, !unresolved);
        } finally { db.endTransaction(); }
    }

    private static long firstRetainedOrdinal(SQLiteDatabase db, String portfolio, List<Rules.Signal> rows, long fallback) {
        Set<String> visible = new HashSet<>();
        for (Rules.Signal row : rows) visible.add(row.id);
        try (Cursor saved = db.rawQuery("SELECT event,ordinal FROM trades WHERE portfolio=? AND stamp>=? "
                + "AND ordinal<=? AND excluded_revision=0 ORDER BY ordinal",
                new String[]{portfolio, Long.toString(rows.get(0).time), Long.toString(fallback)})) {
            while (saved.moveToNext()) if (visible.contains(saved.getString(0))) return saved.getLong(1);
        }
        return fallback;
    }

    private static Update startSegment(SQLiteDatabase db, String portfolio, String title, List<Rules.Signal> rows,
            long now, State before, String detail, long selection, boolean baseline,
            Map<Long, List<Rules.Signal>> provisional) throws JSONException {
        long head = before.head;
        Map<String, LeaderHistory.Holding> holdings = new LinkedHashMap<>();
        List<Rules.Signal> archived = new ArrayList<>();
        Set<String> deferred = baseline ? saveProvisionalReviews(db, portfolio, rows, provisional) : Collections.emptySet();
        for (Rules.Signal row : rows) {
            Rules.Signal saved = putTrade(db, portfolio, row, ++head, baseline ? RECONNECTED_BASELINE : 0, now);
            if (deferred.contains(row.id)) hideProvisionalCopy(db, portfolio, saved.id);
            else {
                archived.add(saved);
                LeaderHistory.apply(holdings, saved);
            }
        }
        State current = new State(title, before.started > 0 ? before.started : now, now, head, false,
                archived, holdings, before.backfilled, true,
                !baseline ? before.historyDetail : detail + (before.head > 0 ? " · 本次基线已接在旧档案后" : " · 已建立本次跟单基线"),
                baseline ? head : before.recoveryHead, true);
        write(db, portfolio, current);
        finalizeSelection(db, portfolio, selection, before.head + 1, rows.isEmpty());
        return new Update(current, archived.size(), baseline, true);
    }

    private static Set<String> saveProvisionalReviews(SQLiteDatabase db, String portfolio, List<Rules.Signal> added,
            Map<Long, List<Rules.Signal>> provisional) throws JSONException {
        Set<String> deferred = new HashSet<>();
        for (Map.Entry<Long, List<Rules.Signal>> group : provisional.entrySet()) {
            List<Rules.Signal> raw = TradeReview.at(added, group.getKey());
            if (raw.isEmpty()) continue;
            Set<String> kept = TradeReview.limitCopies(raw, TradeReview.counts(group.getValue())).stream()
                    .map(row -> row.id).collect(Collectors.toSet());
            for (Rules.Signal row : raw) if (!kept.contains(row.id)) deferred.add(row.id);
            ContentValues values = new ContentValues();
            values.put("portfolio", portfolio);
            values.put("stamp", group.getKey());
            values.put("sample", LocalData.signalJson(raw).toString());
            values.put("provisional", 1);
            db.insertWithOnConflict("trade_reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
        return deferred;
    }

    private static void hideProvisionalCopy(SQLiteDatabase db, String portfolio, String event) {
        ContentValues values = new ContentValues();
        values.put("excluded_revision", -1);
        db.update("trades", values, "portfolio=? AND event=?", new String[]{portfolio, event});
    }

    private static void finalizeSelection(SQLiteDatabase db, String portfolio, long selection, long segmentStart, boolean trustedEmpty) {
        ContentValues updates = new ContentValues();
        updates.put("portfolio", portfolio);
        updates.put("verified_selection", selection);
        updates.put("segment_start", segmentStart != 0 ? segmentStart : selectionColumn(db, portfolio, "segment_start"));
        updates.put("trusted_empty", trustedEmpty ? 1 : 0);
        db.insertWithOnConflict("leader_sessions", null, updates, SQLiteDatabase.CONFLICT_REPLACE);
        updates.clear();
        updates.put("kind", -1);
        db.update("trades", updates, "portfolio=? AND kind=-2", new String[]{portfolio});
    }

    private static List<Rules.Signal> captureReference(SQLiteDatabase db, String portfolio, State before) {
        if (before.head == 0) return before.window;
        if (before.window.isEmpty()) {
            // A 4.2.2 empty switch replaced the window but left the confirmed journal intact.
            try (Cursor last = db.rawQuery("SELECT stamp FROM trades WHERE portfolio=? AND ordinal<=? "
                    + "AND excluded_revision=0 ORDER BY ordinal DESC LIMIT 1",
                    new String[]{portfolio, Long.toString(before.head)})) {
                return last.moveToFirst() ? confirmedAt(db, portfolio, last.getLong(0)) : before.window;
            }
        }
        long time = before.latest().time;
        int first = before.window.size() - 1;
        while (first > 0 && before.window.get(first - 1).time == time) first--;
        List<Rules.Signal> known = confirmedAt(db, portfolio, time, firstCurrentOrdinal(db, portfolio));
        if (known.size() <= before.window.size() - first) return before.window;
        List<Rules.Signal> window = new ArrayList<>(before.window.subList(0, first));
        window.addAll(known);
        return window;
    }

    private static List<Rules.Signal> confirmedAt(SQLiteDatabase db, String portfolio, long time) {
        return confirmedAt(db, portfolio, time, Long.MIN_VALUE);
    }

    private static List<Rules.Signal> confirmedAt(SQLiteDatabase db, String portfolio, long time, long first) {
        return confirmedRecords(db, portfolio, time, first).stream().map(row -> row.signal).collect(Collectors.toList());
    }

    private static List<Record> confirmedRecords(SQLiteDatabase db, String portfolio, long time, long first) {
        List<Record> known = new ArrayList<>();
        try (Cursor trades = db.rawQuery("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND stamp=? "
                + "AND ordinal>=? AND excluded_revision=0 ORDER BY ordinal",
                new String[]{portfolio, Long.toString(time), Long.toString(first)})) {
            while (trades.moveToNext()) known.add(record(trades));
        }
        return known;
    }

    private static List<Rules.Signal> olderThanBaseline(SQLiteDatabase db, String portfolio, List<Rules.Signal> rows,
            int firstNew, boolean legacyOrder) {
        long boundary;
        try (Cursor earliest = db.rawQuery("SELECT stamp FROM trades WHERE portfolio=? "
                + "AND ordinal IS NOT NULL AND excluded_revision=0 ORDER BY ordinal LIMIT 1", new String[]{portfolio})) {
            if (!earliest.moveToFirst()) return null;
            boundary = earliest.getLong(0);
        }
        if (rows.get(0).time > boundary) return Collections.emptyList();
        int throughBoundary = 0;
        while (throughBoundary < firstNew && rows.get(throughBoundary).time <= boundary) throughBoundary++;
        if (throughBoundary == 0 || rows.get(throughBoundary - 1).time != boundary) return null;
        List<Rules.Signal> anchors = confirmedAt(db, portfolio, boundary);
        try {
            LeaderHistory.Merge historical = LeaderHistory.merge(anchors, rows.subList(0, throughBoundary), true, legacyOrder);
            if (!historical.added.isEmpty()) return null;
            for (int i = 0; i < throughBoundary; i++) rows.set(i, historical.rows.get(i));
            return new ArrayList<>(rows.subList(0, historical.prepended));
        } catch (LeaderHistory.Gap gap) {
            return null;
        }
    }

    private static BigDecimal visibleSize(Map<String, LeaderHistory.Holding> holdings, String side) {
        LeaderHistory.Holding holding = holdings.get(side);
        return holding == null ? Rules.ZERO : holding.quantity;
    }

    private static ContentValues trade(String portfolio, Rules.Signal row, Long ordinal, int kind) {
        ContentValues data = new ContentValues();
        data.put("portfolio", portfolio);
        data.put("event", row.id);
        data.put("stamp", row.time);
        data.put("symbol", row.symbol);
        data.put("action", row.action.name());
        data.put("price", Rules.show(row.price));
        data.put("quantity", Rules.show(row.quantity));
        data.put("ordinal", ordinal);
        data.put("kind", kind);
        return data;
    }

    private static boolean fillMissing(SQLiteDatabase db, String portfolio, String id, String field, BigDecimal value) {
        if (value == null) return false;
        ContentValues data = new ContentValues();
        data.put(field, Rules.show(value));
        return db.update("trades", data, "portfolio=? AND event=? AND " + field + " IS NULL", new String[]{portfolio, id}) > 0;
    }

    private static void putTradeDetails(ContentValues data, Rules.Signal row) {
        if (row.realizedPnl != null) data.put("pnl", Rules.show(row.realizedPnl));
        if (row.turnover != null) data.put("turnover", Rules.show(row.turnover));
        if (row.leaderMargin != null) data.put("lead_margin", Rules.show(row.leaderMargin));
    }

    private static Rules.Signal putTrade(SQLiteDatabase db, String portfolio, Rules.Signal row, long ordinal, int kind, long now) {
        try (Cursor existing = db.rawQuery("SELECT ordinal,excluded_revision FROM trades WHERE portfolio=? AND event=?",
                new String[]{portfolio, row.id})) {
            if (existing.moveToFirst()) {
                if (existing.getLong(1) != 0) row = nextOccurrence(db, portfolio, row);
                else if (!existing.isNull(0)) throw new LeaderHistory.Gap();
            }
        }
        ContentValues data = trade(portfolio, row, ordinal, kind);
        putTradeDetails(data, row);
        int updated = db.update("trades", data, "portfolio=? AND event=? AND ordinal IS NULL AND excluded_revision=0",
                new String[]{portfolio, row.id});
        if (updated == 0) {
            data.put("detected_at", now);
            db.insertOrThrow("trades", null, data);
        }
        return row;
    }

    private static Rules.Signal nextOccurrence(SQLiteDatabase db, String portfolio, Rules.Signal row) {
        int next = row.occurrence + 1;
        try (Cursor sameSecond = db.rawQuery("SELECT event FROM trades WHERE portfolio=? AND stamp=?",
                new String[]{portfolio, Long.toString(row.time)})) {
            while (sameSecond.moveToNext()) {
                String event = sameSecond.getString(0);
                if (row.fingerprint.equals(Rules.Signal.fingerprintOf(event))) {
                    next = Math.max(next, row.withArchivedId(event).occurrence + 1);
                }
            }
        }
        return row.withOccurrence(next);
    }

    private static void putIfNew(SQLiteDatabase db, String portfolio, Rules.Signal row, int kind, long now) {
        ContentValues data = trade(portfolio, row, null, kind);
        data.put("detected_at", now);
        putTradeDetails(data, row);
        db.insertWithOnConflict("trades", null, data, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static boolean unresolved(SQLiteDatabase db, String portfolio) {
        try (Cursor rows = db.rawQuery("SELECT 1 FROM trades WHERE portfolio=? AND kind=-2 AND excluded_revision=0 LIMIT 1",
                new String[]{portfolio})) {
            return rows.moveToFirst();
        }
    }

    private static void ensureLeader(SQLiteDatabase db, String portfolio) {
        ContentValues key = new ContentValues();
        key.put("portfolio", portfolio);
        db.insertWithOnConflict("leader", null, key, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void write(SQLiteDatabase db, String portfolio, State state) throws JSONException {
        ensureLeader(db, portfolio);
        ContentValues data = new ContentValues();
        data.put("title", state.name);
        data.put("started", state.started);
        data.put("captured", state.captured);
        data.put("head", state.head);
        data.put("gap", state.gap ? 1 : 0);
        data.put("backfilled", state.backfilled);
        data.put("history_complete", state.historyComplete ? 1 : 0);
        data.put("history_detail", state.historyDetail);
        data.put("recovery_head", state.recoveryHead);
        data.put("ordered_snapshot", state.orderedSnapshot ? 1 : 0);
        data.put("snapshot", LocalData.signalJson(state.window).toString());
        data.put("estimate", holdingsJson(state.holdings));
        int updated = db.update("leader", data, "portfolio=?", new String[]{portfolio});
        if (updated != 1) throw new IllegalStateException("无法保存带单员成交档案");
    }

    private static LeaderHistory.Holding keepSize(LeaderHistory.Holding replayed, BigDecimal recorded) {
        if (replayed != null && replayed.quantity.compareTo(recorded) == 0) return replayed;
        return new LeaderHistory.Holding(recorded, null, replayed == null ? 0 : replayed.updatedAt);
    }

    private static String holdingsJson(Map<String, LeaderHistory.Holding> holdings) throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, LeaderHistory.Holding> entry : holdings.entrySet()) {
            result.put(entry.getKey(), entry.getValue().json());
        }
        return result.toString();
    }

    long sequenceOf(String portfolio, String event) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT ordinal FROM trades WHERE portfolio=? AND event=?",
                new String[]{portfolio, event})) {
            return row.moveToFirst() && !row.isNull(0) ? row.getLong(0) : 0;
        }
    }

    List<Record> after(String portfolio, long ordinal, long head) {
        return query("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND ordinal>? "
                + "AND ordinal<=? ORDER BY ordinal ASC LIMIT 200",
                new String[]{portfolio, Long.toString(ordinal), Long.toString(head)});
    }

    long count(String portfolio) {
        return count("trades", portfolio, " AND excluded_revision=0");
    }

    // Read the observation archive independently from account reconciliation and the order journal.
    long detectionCount(String portfolio) {
        return count("trades", portfolio, DETECTED_FILTER);
    }

    List<Record> detections(String portfolio, int limit, long offset) {
        return query("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=?" + DETECTED_FILTER
                + " ORDER BY detected_at DESC,stamp DESC,COALESCE(ordinal,rowid) DESC LIMIT ? OFFSET ?",
                new String[]{portfolio, Integer.toString(limit), Long.toString(offset)});
    }

    long accountFillCount(String portfolio) {
        return count("account_fills", portfolio, "");
    }

    private long count(String table, String portfolio, String filter) {
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table + " WHERE portfolio=?" + filter,
                new String[]{portfolio})) {
            return rows.moveToFirst() ? rows.getLong(0) : 0;
        }
    }

    static final class AccountFill {
        final String mode, summary;
        final long time;

        AccountFill(String mode, long time, String summary) {
            this.mode = mode;
            this.time = time;
            this.summary = summary;
        }
    }

    void saveAccountFill(String portfolio, String mode, String id, long time, String summary) {
        ContentValues data = new ContentValues();
        data.put("portfolio", portfolio);
        data.put("mode", mode);
        data.put("event", id);
        data.put("stamp", time);
        data.put("summary", summary);
        getWritableDatabase().insertWithOnConflict("account_fills", null, data, SQLiteDatabase.CONFLICT_IGNORE);
    }

    List<AccountFill> accountFills(String portfolio, int limit, long offset) {
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT mode,stamp,summary FROM account_fills WHERE portfolio=? "
                + "ORDER BY stamp DESC,event DESC LIMIT ? OFFSET ?",
                new String[]{portfolio, Integer.toString(limit), Long.toString(offset)})) {
            List<AccountFill> result = new ArrayList<>();
            while (rows.moveToNext()) result.add(new AccountFill(rows.getString(0), rows.getLong(1), rows.getString(2)));
            return result;
        }
    }

    List<Record> page(String portfolio, int limit, long offset) {
        return query("SELECT " + TRADE_COLUMNS + " FROM trades WHERE portfolio=? AND excluded_revision=0 "
                + "ORDER BY stamp DESC,(ordinal IS NULL) DESC,COALESCE(ordinal,rowid) DESC LIMIT ? OFFSET ?",
                new String[]{portfolio, Integer.toString(limit), Long.toString(offset)});
    }

    private List<Record> query(String sql, String[] values) {
        List<Record> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery(sql, values)) {
            while (rows.moveToNext()) result.add(record(rows));
        }
        return result;
    }

    private static Record record(Cursor row) {
        String symbol = row.getString(2), event = row.getString(11);
        Rules.Action action = Rules.Action.valueOf(row.getString(3));
        return new Record(row.isNull(0) ? 0 : row.getLong(0), new Rules.Signal(symbol,
                action, row.getLong(1), new BigDecimal(row.getString(4)),
                new BigDecimal(row.getString(5)), row.isNull(7) ? null : new BigDecimal(row.getString(7)),
                row.isNull(9) ? null : new BigDecimal(row.getString(9)),
                row.isNull(10) ? null : new BigDecimal(row.getString(10)),
                Rules.Signal.reportedActionOf(symbol, action, event), 0).withArchivedId(event),
                row.getInt(6), row.getLong(8), row.getLong(12) != 0);
    }

    void export(String portfolio, OutputStream raw) throws Exception {
        String name = state(portfolio).name;
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8));
                Cursor rows = getReadableDatabase().rawQuery("SELECT " + TRADE_COLUMNS
                        + " FROM trades WHERE portfolio=? AND excluded_revision=0 ORDER BY stamp ASC,(ordinal IS NULL) ASC,"
                        + "COALESCE(ordinal,rowid) ASC", new String[]{portfolio})) {
            out.write('\uFEFF');
            out.write("组合ID,带单员,成交时间,时间戳毫秒,标的,方向,数量,成交均价USDT,成交额USDT,已实现盈亏USDT,来源\r\n");
            while (rows.moveToNext()) {
                Record row = record(rows);
                Rules.Signal signal = row.signal;
                out.write(TradeCsv.line(portfolio, name, TradeNotice.fullTime(signal.time), Long.toString(signal.time),
                        signal.symbol, signal.action.label, Rules.show(signal.quantity), Rules.show(signal.price),
                        Rules.show(signal.displayTurnover()),
                        signal.realizedPnl == null ? "" : Rules.show(signal.realizedPnl), row.source()));
            }
        }
    }
}
