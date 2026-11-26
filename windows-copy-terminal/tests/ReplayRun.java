package com.localcopy.terminal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ReplayRun {
    static final BigDecimal INITIAL_WALLET = new BigDecimal("1000");
    private static final BigDecimal DEPTH = new BigDecimal("100000");
    final JSONObject data;
    final Rules.Config config;
    final PaperEngine book;
    final boolean sample;
    final List<Rules.Signal> source;
    final List<String> logs = new ArrayList<>();
    private final Map<String, Rules.Quote> quotes = new HashMap<>();
    private final JSONArray history;

    private ReplayRun(JSONObject data, Rules.Config config, List<Rules.Signal> source) throws JSONException {
        this.data = data;
        this.config = config;
        this.source = source;
        sample = data.getBoolean("sample");
        history = data.getJSONArray("history");
        book = new PaperEngine(config, INITIAL_WALLET);
        if (history.length() > maxSteps()) throw new IllegalArgumentException("回放游标无效");
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.getJSONObject(i);
            play(i, new BigDecimal(entry.getString("slippage")), entry.getBoolean("opens"), entry.getLong("at"));
        }
    }

    static ReplayRun create(Rules.Config config, List<Rules.Signal> input, boolean sample)
            throws JSONException {
        List<Rules.Signal> selected = new ArrayList<>();
        for (Rules.Signal row : input) if (config.symbols.contains(row.symbol)) selected.add(row);
        selected.sort(Comparator.comparingLong(row -> row.time));
        if (selected.isEmpty() || (sample && selected.size() != 3)) {
            throw new IllegalArgumentException(sample ? "请在名单中保留 MUUSDT 再运行演示" : "当前名单没有可回放的记录");
        }
        JSONObject data = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("sample", sample).put("config", LocalData.configJson(config))
                .put("source", LocalData.signalJson(selected)).put("history", new JSONArray())
                .put("active", true).put("opens", true).put("startedAt", System.currentTimeMillis());
        return new ReplayRun(data, config, selected);
    }

    static ReplayRun restore(JSONObject data) throws JSONException {
        return new ReplayRun(data, LocalData.readConfig(data.getJSONObject("config")),
                LocalData.readSignals(data.getJSONArray("source")));
    }

    private int maxSteps() { return sample ? 4 : source.size(); }

    boolean finished() { return history.length() >= maxSteps(); }

    int step() { return history.length(); }

    int total() { return maxSteps(); }

    List<PaperEngine.Fill> advance(BigDecimal slippage, long time) throws JSONException {
        if (finished()) return Collections.emptyList();
        boolean opens = data.getBoolean("opens");
        int current = history.length();
        List<PaperEngine.Fill> output = play(current, slippage, opens, time);
        history.put(new JSONObject().put("slippage", Rules.show(slippage)).put("at", time).put("opens", opens));
        if (finished()) data.put("active", false);
        return output;
    }

    private List<PaperEngine.Fill> play(int step, BigDecimal slippage, boolean opens, long time) {
        if (sample) {
            if (step <= 1) {
                update(source.get(step), "715");
            } else if (step == 2) {
                setPrice("MUUSDT", new BigDecimal("713.50"));
            } else {
                update(source.get(2), "701.40320");
            }
        } else {
            Rules.Signal signal = source.get(step);
            update(signal, Rules.show(signal.price));
        }
        List<PaperEngine.Fill> fills = book.check(quotes, slippage, opens, time);
        for (PaperEngine.Fill fill : fills) logs.add(fill.notice());
        return fills;
    }

    private void update(Rules.Signal signal, String mid) {
        book.apply(Collections.singletonList(signal));
        setPrice(signal.symbol, new BigDecimal(mid));
    }

    private void setPrice(String symbol, BigDecimal price) {
        quotes.put(symbol, new Rules.Quote(price, price, DEPTH, DEPTH));
    }

    static List<Rules.Signal> samples() {
        List<Rules.Signal> result = new ArrayList<>();
        result.add(sample("2026-11-18T15:14:42.800Z", "711.9821143", "280.98", Rules.Action.OPEN_LONG));
        result.add(sample("2026-11-18T15:14:58.571Z", "714.6780757", "251.94", Rules.Action.OPEN_LONG));
        result.add(sample("2026-11-18T15:40:31.494Z", "701.4031999", "532.92", Rules.Action.CLOSE_LONG));
        return result;
    }

    private static Rules.Signal sample(String time, String price, String quantity, Rules.Action action) {
        return new Rules.Signal("MUUSDT", action, Instant.parse(time).toEpochMilli(),
                new BigDecimal(price), new BigDecimal(quantity));
    }
}
