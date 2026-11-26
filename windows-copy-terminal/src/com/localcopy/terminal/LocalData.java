package com.localcopy.terminal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LocalData {
    static final String DEFAULT_PORTFOLIO = "5075281354358777856";
    static final BigDecimal DEFAULT_SLIPPAGE = new BigDecimal("0.3");
    private static final Object FILE_LOCK = new Object();
    private static final Object CANCEL_LOCK = new Object();
    final Preferences prefs;
    private final File directory;

    LocalData(DesktopContext context) {
        prefs = context.prefs;
        directory = context.home.toFile();
    }

    Rules.Config config() {
        try {
            return readConfig(new JSONObject(prefs.getString("config", "{}")));
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IllegalStateException("本机设置无法读取，请重新保存", invalid);
        }
    }

    void saveConfig(Rules.Config config) throws JSONException {
        Preferences.Editor update = prefs.edit().putString("config", configJson(config).toString());
        if (!config().portfolio.equals(config.portfolio)) {
            update.putLong("portfolio_selection", portfolioSelection() + 1);
        }
        if (!update.commit()) {
            throw new IllegalStateException("无法保存设置");
        }
    }

    long portfolioSelection() { return prefs.getLong("portfolio_selection", 0); }

    static final class LeaderBalance {
        final BigDecimal value;
        final long at;

        LeaderBalance(BigDecimal value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    void rememberLeaderBalance(String portfolio, BigDecimal value, long at) {
        prefs.edit().putString("risk_balance_" + portfolio, Rules.show(value))
                .putLong("risk_balance_at_" + portfolio, at).apply();
    }

    LeaderBalance leaderBalance(String portfolio) {
        BigDecimal value = WebRecords.positiveAmount(prefs.getString("risk_balance_" + portfolio, ""));
        long at = prefs.getLong("risk_balance_at_" + portfolio, 0);
        if (value != null && at > 0) return new LeaderBalance(value, at);
        if (!portfolio.equals(prefs.getString("auto_portfolio", ""))) return null;
        value = WebRecords.positiveAmount(prefs.getString("auto_leader_margin", ""));
        at = prefs.getLong("auto_leader_margin_at", 0);
        return value != null && at > 0 ? new LeaderBalance(value, at) : null;
    }

    static Rules.Config readConfig(JSONObject json) {
        String portfolio = json.optString("portfolio", DEFAULT_PORTFOLIO);
        JSONArray items = json.optJSONArray("contractRules");
        if (items == null) {
            if (json.has("contractRules")) throw new IllegalArgumentException("合约规则格式无效");
            return new Rules.Config(portfolio, Rules.symbols(json.optString("symbols", "MUUSDT,BTCUSDT")),
                    json.optBoolean("ratio", false), new BigDecimal(json.optString("amount", "20")),
                    json.optInt("leverage", 5), new BigDecimal(json.optString("slippage", "0.3")));
        }
        Map<String, Rules.ContractRule> rules = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("合约规则格式无效");
            String symbol = item.optString("symbol");
            Rules.ContractRule rule = new Rules.ContractRule(item.optBoolean("ratio"),
                    new BigDecimal(item.optString("amount")), item.optInt("leverage"),
                    new BigDecimal(item.optString("slippage")));
            if (rules.put(symbol, rule) != null) throw new IllegalArgumentException("合约 " + symbol + " 重复");
        }
        return new Rules.Config(portfolio, rules);
    }

    static JSONObject configJson(Rules.Config c) throws JSONException {
        JSONArray items = new JSONArray();
        for (Map.Entry<String, Rules.ContractRule> entry : c.rules.entrySet()) {
            Rules.ContractRule rule = entry.getValue();
            items.put(new JSONObject().put("symbol", entry.getKey()).put("ratio", rule.ratio)
                    .put("amount", Rules.show(rule.amount)).put("leverage", rule.leverage)
                    .put("slippage", Rules.show(rule.slippagePercent)));
        }
        return new JSONObject().put("portfolio", c.portfolio).put("contractRules", items);
    }

    JSONObject load(String name) throws IOException, JSONException {
        synchronized (FILE_LOCK) {
            AtomicFile file = new AtomicFile(new File(directory, name + ".json"));
            try {
                return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            } catch (FileNotFoundException missing) {
                return null;
            }
        }
    }

    void save(String name, JSONObject data) throws IOException {
        synchronized (FILE_LOCK) {
            AtomicFile file = new AtomicFile(new File(directory, name + ".json"));
            FileOutputStream stream = null;
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            try {
                stream = file.startWrite();
                stream.write(bytes);
                stream.getFD().sync();
                file.finishWrite(stream);
                stream = null;
                if (!java.util.Arrays.equals(bytes, file.readFully())) {
                    throw new IOException("本机写入核对失败");
                }
            } catch (IOException | RuntimeException failure) {
                if (stream != null) file.failWrite(stream);
                throw failure;
            }
        }
    }

    Set<String> waitCancellations(String mode) {
        synchronized (CANCEL_LOCK) {
            return new HashSet<>(prefs.getStringSet("auto_cancel_" + mode, Collections.emptySet()));
        }
    }

    void cancelWait(String mode, String id) throws IOException {
        synchronized (CANCEL_LOCK) {
            Set<String> requests = waitCancellations(mode);
            if (requests.add(id)) saveCancellations(mode, requests);
        }
    }

    void acknowledgeWaitCancellations(String mode, Set<String> complete) throws IOException {
        synchronized (CANCEL_LOCK) {
            Set<String> remaining = waitCancellations(mode);
            if (remaining.removeAll(complete)) saveCancellations(mode, remaining);
        }
    }

    private void saveCancellations(String mode, Set<String> requests) throws IOException {
        if (!prefs.edit().putStringSet("auto_cancel_" + mode, requests).commit()) {
            throw new IOException("无法保存取消等待的操作");
        }
    }

    static JSONArray signalJson(Collection<Rules.Signal> rows) throws JSONException {
        JSONArray result = new JSONArray();
        for (Rules.Signal row : rows) {
            JSONObject saved = new JSONObject().put("s", row.symbol).put("a", row.action.name())
                    .put("t", row.time).put("p", Rules.show(row.price)).put("q", Rules.show(row.quantity))
                    .put("r", row.realizedPnl == null ? null : Rules.show(row.realizedPnl))
                    .put("turnover", row.turnover == null ? null : Rules.show(row.turnover))
                    .put("leaderMargin", row.leaderMargin == null ? null : Rules.show(row.leaderMargin));
            if (row.occurrence != 0) saved.put("occurrence", row.occurrence);
            if (row.reportedAction != row.action) saved.put("reportedAction", row.reportedAction.name());
            result.put(saved);
        }
        return result;
    }

    static List<Rules.Signal> readSignals(JSONArray rows) throws JSONException {
        if (rows.length() > HistoryPagination.MAX_ROWS) throw new IllegalArgumentException("本次读取的成交过多");
        List<Rules.Signal> output = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Rules.Action action = Rules.Action.valueOf(row.getString("a"));
            output.add(new Rules.Signal(row.getString("s"), action,
                    row.getLong("t"), new BigDecimal(row.getString("p")), new BigDecimal(row.getString("q")),
                    FuturesApi.optionalDecimal(row, "r"), FuturesApi.optionalDecimal(row, "turnover"),
                    FuturesApi.optionalDecimal(row, "leaderMargin"),
                    Rules.Action.valueOf(row.optString("reportedAction", action.name())), row.optInt("occurrence")));
        }
        return output;
    }
}
