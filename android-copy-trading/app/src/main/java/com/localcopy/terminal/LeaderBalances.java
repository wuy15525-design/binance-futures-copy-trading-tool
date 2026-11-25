package com.localcopy.terminal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** 独立保存官网划转记录和读取进度。事件标识用于合并重复翻页，通知状态随事件保存。 */
final class LeaderBalances extends SQLiteOpenHelper {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static LeaderBalances instance;

    static synchronized LeaderBalances open(Context context) {
        if (instance == null) instance = new LeaderBalances(context.getApplicationContext());
        return instance;
    }

    private LeaderBalances(Context context) {
        super(context, "leader-balances.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples (portfolio TEXT PRIMARY KEY, value TEXT, checked INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE changes (portfolio TEXT NOT NULL, event TEXT NOT NULL, stamp INTEGER NOT NULL,"
                + " asset TEXT NOT NULL, amount TEXT NOT NULL, origin TEXT NOT NULL, destination TEXT NOT NULL,"
                + " value TEXT, notify INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(portfolio,event))");
        db.execSQL("CREATE INDEX changes_time ON changes(portfolio,stamp DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("不支持的余额历史档案版本");
    }

    static final class Read {
        final BigDecimal margin;
        final List<Event> transfers;
        final boolean complete;
        final String issue;

        Read(BigDecimal margin, List<Event> transfers) {
            this(margin, transfers, true);
        }

        Read(BigDecimal margin, List<Event> transfers, boolean complete) {
            this(margin, transfers, complete, "");
        }

        Read(BigDecimal margin, List<Event> transfers, boolean complete, String issue) {
            this.margin = margin;
            this.transfers = transfers;
            this.complete = complete;
            this.issue = issue;
        }
    }

    static final class Event {
        final String id, asset, from, to;
        final long at;
        final BigDecimal amount, balance;

        Event(String id, long at, String asset, BigDecimal amount, String from, String to, BigDecimal balance) {
            this.id = id;
            this.at = at;
            this.asset = asset;
            this.amount = amount;
            this.from = from;
            this.to = to;
            this.balance = balance;
        }

        String text() {
            String time = TradeNotice.fullTime(at);
            if (balance != null) {
                String delta = amount.signum() == 0 ? "" : " · " + (amount.signum() > 0 ? "+" : "") + Rules.show(amount) + " USDT";
                return "保证金余额 " + Rules.show(balance) + " USDT" + delta + "\n采样 " + time;
            }
            String direction = leadAccount(from) ? "转出" : leadAccount(to) ? "转入" : "划转";
            return direction + " " + Rules.show(amount) + " " + asset + " · " + from + " → " + to + "\n" + time;
        }

        private static boolean leadAccount(String label) {
            return label.toLowerCase(Locale.ROOT).replace(" ", "")
                    .matches(".*(?:带单|帶單|leadtrad|copytrad|copyportfolio).*");
        }
    }

    static Read parse(JSONObject data) throws Exception {
        ZoneId zone = ZoneId.of(data.getString("zone"));
        BigDecimal margin = parseMargin(data);
        JSONArray rows = data.getJSONArray("transfers");
        if (rows.length() > 1000) throw new IllegalArgumentException("币安余额历史条数异常");
        List<Event> entries = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            String time = clean(row.getString(0));
            String asset = clean(row.getString(1)).toUpperCase(Locale.ROOT);
            BigDecimal value = amount(row.getString(2));
            String from = clean(row.getString(3)), to = clean(row.getString(4));
            if (!asset.matches("[A-Z0-9]{1,24}") || value == null || value.signum() <= 0 || from.equals(to)) {
                throw new IllegalArgumentException("币安余额划转字段暂未完整");
            }
            long at = LocalDateTime.parse(time, DATE).atZone(zone).toInstant().toEpochMilli();
            String base = at + "|" + asset + "|" + Rules.show(value) + "|" + from + "|" + to;
            String id = base + "|" + seen.merge(base, 1, Integer::sum);
            entries.add(new Event(id, at, asset, value, from, to, null));
        }
        return new Read(margin, entries);
    }

    static BigDecimal parseMargin(JSONObject data) { return amount(data.optString("leaderMargin")); }

    private static String clean(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 70 || normalized.matches(".*[|\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("币安余额历史字段无效");
        }
        return normalized;
    }

    private static BigDecimal amount(String value) {
        String number = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).trim();
        return number.matches("(?:[0-9]{1,30}|[0-9]{1,3}(?:,[0-9]{3}){1,9})(?:\\.[0-9]{1,18})?")
                ? new BigDecimal(number.replace(",", "")) : null;
    }

    // 分页中断时仍保存已读划转。只有本轮读完才推进上次成功核对时间，避免下一轮跳过未读记录。
    void record(String portfolio, Read read, long now, long followingSince) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Event transfer : read.transfers) {
                insert(db, portfolio, transfer, transfer.at >= followingSince && transfer.at <= now + 120000);
            }
            if (read.complete) {
                ContentValues sample = new ContentValues();
                sample.put("portfolio", portfolio);
                sample.put("checked", now);
                db.insertWithOnConflict("samples", null, sample, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void insert(SQLiteDatabase db, String portfolio, Event event, boolean notify) {
        ContentValues row = new ContentValues();
        row.put("portfolio", portfolio);
        row.put("event", event.id);
        row.put("stamp", event.at);
        row.put("asset", event.asset);
        row.put("amount", Rules.show(event.amount));
        row.put("origin", event.from);
        row.put("destination", event.to);
        if (event.balance != null) row.put("value", Rules.show(event.balance));
        row.put("notify", notify ? 1 : 0);
        db.insertWithOnConflict("changes", null, row, SQLiteDatabase.CONFLICT_IGNORE);
    }

    long count(String portfolio) { return number("SELECT COUNT(*) FROM changes WHERE portfolio=? AND value IS NULL", portfolio); }
    long checked(String portfolio) { return number("SELECT checked FROM samples WHERE portfolio=?", portfolio); }

    private long number(String sql, String portfolio) {
        try (Cursor row = getReadableDatabase().rawQuery(sql, new String[]{portfolio})) {
            return row.moveToFirst() ? row.getLong(0) : 0;
        }
    }

    List<Event> page(String portfolio, int limit, long offset) {
        return query("portfolio=? AND value IS NULL ORDER BY stamp DESC,rowid DESC LIMIT ? OFFSET ?",
                new String[]{portfolio, Integer.toString(limit), Long.toString(offset)});
    }

    List<Event> pending(String portfolio) {
        return query("portfolio=? AND value IS NULL AND notify=1 ORDER BY stamp,rowid", new String[]{portfolio});
    }

    void notified(String portfolio, String id) {
        ContentValues row = new ContentValues();
        row.put("notify", 0);
        getWritableDatabase().update("changes", row, "portfolio=? AND event=?", new String[]{portfolio, id});
    }

    private List<Event> query(String clause, String[] args) {
        List<Event> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT event,stamp,asset,amount,origin,destination,value FROM changes WHERE " + clause, args)) {
            while (row.moveToNext()) result.add(new Event(row.getString(0), row.getLong(1), row.getString(2),
                    new BigDecimal(row.getString(3)), row.getString(4), row.getString(5),
                    row.isNull(6) ? null : new BigDecimal(row.getString(6))));
        }
        return result;
    }
}
