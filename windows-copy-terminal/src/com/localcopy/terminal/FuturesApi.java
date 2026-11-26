package com.localcopy.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

final class FuturesApi implements FollowExecutor.Exchange {
    static final String DEMO = "demo";
    static final String LIVE_READ = "readonly";
    static final String LIVE = "live";
    static final String DEMO_BASE = "https://demo-fapi.binance.com";
    static final String READ_BASE = "https://fapi.binance.com";
    private static final long SYMBOL_CACHE_MS = 60000;
    private static final Pattern MAX_LEVERAGE = Pattern.compile(
            "\\bmax(?:imum)?\\s+leverage\\b[^0-9]{0,70}([0-9]{1,3})(?![0-9])|最大杠杆[^0-9]{0,30}([0-9]{1,3})(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MINIMUM_NOTIONAL = Pattern.compile(
            "notional must be no smaller than\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final java.util.concurrent.atomic.AtomicLong DEMO_BACKOFF = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LIVE_BACKOFF = new java.util.concurrent.atomic.AtomicLong();
    private final boolean demo;
    private final String realm;
    private final Secrets credentials;
    private long timeOffset;
    private long measuredAt;
    private long listedAt;
    private boolean singleTickersOnly;
    private Map<String, JSONObject> listings = Collections.emptyMap();
    private final Map<String, String> rejectedTickers = new LinkedHashMap<>();
    private final Map<String, LeverageTable> leverageTables = new LinkedHashMap<>();

    static final class Failure extends IOException {
        final int status, code;
        final String reason;
        Failure(int status, int code) {
            this(status, code, "");
        }
        Failure(int status, int code, String message) {
            super("币安 API HTTP " + status + (code == 0 ? "" : " / " + code) + describe(message));
            this.status = status;
            this.code = code;
            this.reason = cleanMessage(message);
        }

        private static String describe(String message) {
            String safe = cleanMessage(message);
            return safe.isEmpty() ? "" : " · " + safe;
        }

        private static String cleanMessage(String message) {
            if (message == null) return "";
            String safe = message.replaceAll("[\\p{Cntrl}]", " ").trim();
            if (safe.length() > 240 || safe.matches("(?is).*(?:[0-9a-f]{32,}|https?://|signature=|api[_-]?key=).*")) return "";
            return safe;
        }

        boolean definitivelyRejected() {
            if (status == 401) return true;
            if (status != 400) return false;
            switch (code) {
                case -1100: case -1102: case -1111: case -1121: case -1122: case -1021:
                case -1022: case -2015: case -2019: case -2022: case -1013:
                case -2027: case -2028: case -4004: case -4028: case -4161: case -4164: case -5021: case -5022:
                case -4202: case -4203: case -4205: case -4206: case -4208: case -4209:
                    return true;
                default:
                    return false;
            }
        }

        boolean unavailableSymbol() { return code == -1121 || code == -1122; }

        boolean belowOrderMinimum() { return definitivelyRejected() && (code == -4004 || code == -4164); }

        BigDecimal reportedMinimumNotional() {
            Matcher amount = MINIMUM_NOTIONAL.matcher(reason);
            return code == -4164 && amount.find() ? new BigDecimal(amount.group(1)) : Rules.ZERO;
        }

        int statedLeverageCap() {
            if (!definitivelyRejected()) return 0;
            if (code == -4202 || code == -4203 || code == -4205 || code == -4208) return 20;
            if (code != -4206 && code != -4209) return 0;
            Matcher explicit = MAX_LEVERAGE.matcher(reason);
            if (!explicit.find()) return 0;
            int limit = Integer.parseInt(explicit.group(1) == null ? explicit.group(2) : explicit.group(1));
            return limit >= 1 && limit <= 125 ? limit : 0;
        }
    }

    static final class UnavailableSymbol extends IllegalArgumentException {
        UnavailableSymbol(String message) { super(message); }
    }

    static final class BookTickers {
        final Map<String, Rules.Quote> quotes;
        final Map<String, String> issues;

        BookTickers(Map<String, Rules.Quote> quotes, Map<String, String> issues) {
            this.quotes = Collections.unmodifiableMap(quotes);
            this.issues = Collections.unmodifiableMap(issues);
        }
    }

    static final class NotSent extends IOException {
        NotSent(String message, Throwable cause) { super(message, cause); }
    }

    static final class Wallet {
        final BigDecimal balance, available;
        Wallet(BigDecimal balance, BigDecimal available) {
            this.balance = balance;
            this.available = available;
        }
    }

    static final class Contract {
        final BigDecimal tick, lotStep, lotMin, lotMax, marketStep, marketMin, marketMax, minValue;
        Contract(BigDecimal tick, BigDecimal lotStep, BigDecimal lotMin, BigDecimal lotMax,
                BigDecimal marketStep, BigDecimal marketMin, BigDecimal marketMax, BigDecimal minValue) {
            if (tick.signum() <= 0 || lotStep.signum() <= 0 || marketStep.signum() <= 0
                    || lotMin.signum() <= 0 || marketMin.signum() <= 0
                    || lotMax.compareTo(lotMin) < 0 || marketMax.compareTo(marketMin) < 0
                    || minValue.signum() < 0) {
                throw new IllegalArgumentException("交易所返回的合约限制无效");
            }
            this.tick = tick;
            this.lotStep = lotStep;
            this.lotMin = lotMin;
            this.lotMax = lotMax;
            this.marketStep = marketStep;
            this.marketMin = marketMin;
            this.marketMax = marketMax;
            this.minValue = minValue;
        }

        BigDecimal openingQuantity(BigDecimal ceiling) {
            if (ceiling.compareTo(lotMin) < 0) return Rules.ZERO;
            return lotMin.add(Rules.floor(ceiling.subtract(lotMin), lotStep));
        }

        BigDecimal openingQuantity(BigDecimal notional, BigDecimal price) {
            BigDecimal available = notional.subtract(lotMin.multiply(price));
            if (available.signum() < 0) return Rules.ZERO;
            return lotMin.add(available.divide(lotStep.multiply(price), 0, RoundingMode.FLOOR).multiply(lotStep));
        }

        BigDecimal marketClosingQuantity(BigDecimal ceiling) {
            BigDecimal available = ceiling.min(marketMax).subtract(marketMin);
            return available.signum() < 0 ? Rules.ZERO : marketMin.add(Rules.floor(available, marketStep));
        }
    }

    static final class Position {
        BigDecimal longs = Rules.ZERO, shorts = Rules.ZERO;
        BigDecimal reportedNotional = Rules.ZERO;
        BigDecimal longEntry, shortEntry, longUnrealized, shortUnrealized;
        PositionRisk longRisk, shortRisk;
        long longUpdated, shortUpdated;
        BigDecimal size(Rules.Action action) { return action.isLong ? longs : shorts; }
        BigDecimal entryPrice(Rules.Action action) { return action.isLong ? longEntry : shortEntry; }
        boolean sameOpeningBasis(Position other, Rules.Action action) {
            if (longs.compareTo(other.longs) != 0 || shorts.compareTo(other.shorts) != 0) return false;
            if (!action.opens || size(action).signum() == 0) return true;
            BigDecimal a = entryPrice(action);
            BigDecimal b = other.entryPrice(action);
            return a == null ? b == null : b != null && a.compareTo(b) == 0;
        }
        boolean flat() { return longs.signum() == 0 && shorts.signum() == 0; }
        BigDecimal exposure(BigDecimal price) { return longs.add(shorts).multiply(price).max(reportedNotional); }
    }

    static final class PositionRisk {
        final BigDecimal initialMargin, liquidationPrice, isolatedWallet;
        final String asset;

        PositionRisk(BigDecimal initialMargin, BigDecimal liquidationPrice, BigDecimal isolatedWallet, String asset) {
            this.initialMargin = initialMargin;
            this.liquidationPrice = liquidationPrice;
            this.isolatedWallet = isolatedWallet;
            this.asset = asset;
        }
    }

    static final class LeverageBracket {
        final int maximum;
        final BigDecimal cap;

        LeverageBracket(int maximum, BigDecimal cap) {
            if (maximum < 1 || maximum > 125 || cap.signum() <= 0) {
                throw new IllegalArgumentException("币安返回的杠杆档位无效");
            }
            this.maximum = maximum;
            this.cap = cap;
        }
    }

    private static final class LeverageTable {
        final long checkedAt = System.currentTimeMillis();
        final List<LeverageBracket> brackets;
        int observedMax = 125;

        LeverageTable(List<LeverageBracket> brackets) { this.brackets = brackets; }
    }

    static final class Fills {
        BigDecimal quantity = Rules.ZERO, value = Rules.ZERO;
        BigDecimal minPrice, maxPrice, realizedPnl;
        long latest;

        BigDecimal average() { return value.divide(quantity, Rules.MC); }
    }

    static final class AccountTrade {
        final long id, orderId, time;
        final String positionSide;
        final boolean buy;
        final BigDecimal quantity, realizedPnl;

        AccountTrade(long id, long orderId, long time, String positionSide, boolean buy,
                BigDecimal quantity, BigDecimal realizedPnl) {
            this.id = id;
            this.orderId = orderId;
            this.time = time;
            this.positionSide = positionSide;
            this.buy = buy;
            this.quantity = quantity;
            this.realizedPnl = realizedPnl;
        }
    }

    FuturesApi(String realm, Secrets credentials) {
        if (!DEMO.equals(realm) && !LIVE_READ.equals(realm) && !LIVE.equals(realm)) {
            throw new IllegalArgumentException("交易环境无效");
        }
        demo = DEMO.equals(realm);
        this.realm = realm;
        this.credentials = credentials;
    }

    FuturesApi independentReader() {
        return new FuturesApi(realm, credentials);
    }

    @Override public Wallet wallet() throws Exception {
        JSONArray balances = (JSONArray) signed("GET", "/fapi/v3/balance", "");
        for (int i = 0; i < balances.length(); i++) {
            JSONObject row = balances.getJSONObject(i);
            if ("USDT".equals(row.getString("asset"))) {
                return new Wallet(decimal(row, "balance"), decimal(row, "availableBalance"));
            }
        }
        throw new IllegalStateException("账户未返回 USDT 钱包余额");
    }

    @Override public Wallet copyWallet() throws Exception {
        return readCopyWallet((JSONObject) signed("GET", "/fapi/v3/account", ""));
    }

    static Wallet readCopyWallet(JSONObject account) throws JSONException {
        JSONArray assets = account.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject row = assets.getJSONObject(i);
            if ("USDT".equals(row.getString("asset"))) {
                return new Wallet(decimal(row, "marginBalance"), decimal(row, "availableBalance"));
            }
        }
        throw new IllegalStateException("账户未返回 USDT 保证金余额");
    }
    @Override public Contract contract(String symbol) throws Exception {
        JSONObject row = tradableListing(symbol);
        JSONObject price = null, lot = null, market = null;
        BigDecimal notional = Rules.ZERO;
        JSONArray filters = row.getJSONArray("filters");
        for (int j = 0; j < filters.length(); j++) {
            JSONObject filter = filters.getJSONObject(j);
            switch (filter.getString("filterType")) {
                case "PRICE_FILTER": price = filter; break;
                case "LOT_SIZE": lot = filter; break;
                case "MARKET_LOT_SIZE": market = filter; break;
                case "MIN_NOTIONAL": notional = decimal(filter, "notional"); break;
                default: break;
            }
        }
        if (price == null || lot == null || market == null) throw new UnavailableSymbol(symbol + " 未返回交易精度");
        return new Contract(decimal(price, "tickSize"), decimal(lot, "stepSize"),
                decimal(lot, "minQty"), decimal(lot, "maxQty"), decimal(market, "stepSize"),
                decimal(market, "minQty"), decimal(market, "maxQty"), notional);
    }

    private Map<String, JSONObject> listings() throws Exception {
        long now = System.currentTimeMillis();
        if (listedAt == 0 || now < listedAt || now - listedAt >= SYMBOL_CACHE_MS) {
            JSONArray rows = ((JSONObject) request("GET", "/fapi/v1/exchangeInfo", "", false)).getJSONArray("symbols");
            if (rows.length() == 0) throw new IOException("币安未返回合约列表");
            Map<String, JSONObject> refreshed = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                refreshed.put(row.getString("symbol"), row);
            }
            listings = refreshed;
            listedAt = System.currentTimeMillis();
            rejectedTickers.clear();
            singleTickersOnly = false;
        }
        return listings;
    }

    private JSONObject tradableListing(String symbol) throws Exception {
        Rules.requireSymbol(symbol);
        JSONObject row = listings().get(symbol);
        String issue = listingIssue(realm, symbol, row);
        if (issue != null) throw new UnavailableSymbol(issue);
        return row;
    }

    List<String> tradableSymbols() throws Exception {
        List<String> symbols = new ArrayList<>();
        for (Map.Entry<String, JSONObject> listing : listings().entrySet()) {
            String symbol = listing.getKey();
            if (Rules.isUsdtSymbol(symbol) && listingIssue(realm, symbol, listing.getValue()) == null) {
                symbols.add(symbol);
            }
        }
        Collections.sort(symbols);
        return symbols;
    }

    static String listingIssue(String realm, String symbol, JSONObject row) {
        String environment = DEMO.equals(realm) ? "测试网" : "实盘";
        if (row == null) return environment + "合约列表未收录 " + symbol;
        String type = row.optString("contractType");
        if (!"USDT".equals(row.optString("quoteAsset"))
                || !(type.startsWith("PERPETUAL") || "TRADIFI_PERPETUAL".equals(type))) {
            return environment + " " + symbol + " 不是可跟的 USDT 永续合约";
        }
        String status = row.optString("status", "未知");
        return "TRADING".equals(status) ? null : environment + " " + symbol + " 暂不可交易（" + status + "）";
    }

    @Override public Rules.Quote quote(String symbol) throws Exception {
        tradableListing(symbol);
        return fetchQuote(symbol);
    }

    private Rules.Quote fetchQuote(String symbol) throws Exception {
        String rejected = rejectedTickers.get(symbol);
        if (rejected != null) throw new UnavailableSymbol(rejected);
        JSONObject q;
        try { q = (JSONObject) request("GET", "/fapi/v1/ticker/bookTicker", symbolParam(symbol), false); }
        catch (Failure failure) {
            if (failure.code == -1112) throw new UnavailableSymbol(symbol + " 暂无买卖盘（-1112）");
            if (!failure.unavailableSymbol()) throw failure;
            String issue = (demo ? "测试网" : "实盘") + "拒绝 " + symbol + " 的盘口查询（" + failure.code + "）";
            rejectedTickers.put(symbol, issue);
            throw new UnavailableSymbol(issue);
        }
        long now = currentServerTime();
        try {
            if (!symbol.equals(q.getString("symbol"))) throw new IllegalStateException("盘口合约不匹配");
            return readQuote(q, now);
        } catch (IllegalArgumentException | IllegalStateException | JSONException invalid) {
            throw new UnavailableSymbol(noBook(symbol));
        }
    }

    BookTickers quotes(Set<String> symbols) throws Exception {
        Map<String, Rules.Quote> result = new LinkedHashMap<>();
        Map<String, String> issues = new LinkedHashMap<>();
        if (symbols.isEmpty()) return new BookTickers(result, issues);
        Map<String, JSONObject> supported = listings();
        Set<String> eligible = new LinkedHashSet<>();
        for (String symbol : symbols) {
            String issue = listingIssue(realm, symbol, supported.get(symbol));
            if (issue == null) issue = rejectedTickers.get(symbol);
            if (issue == null) eligible.add(symbol);
            else issues.put(symbol, issue);
        }
        if (eligible.size() > 1 && !singleTickersOnly) {
            try {
                JSONArray response = (JSONArray) request("GET", "/fapi/v1/ticker/bookTicker", "", false);
                result.putAll(readTickers(response, eligible, currentServerTime()));
                for (String symbol : eligible) {
                    if (!result.containsKey(symbol)) issues.put(symbol, noBook(symbol));
                }
                return new BookTickers(result, issues);
            } catch (Failure failure) {
                if (!failure.unavailableSymbol()) throw failure;
                singleTickersOnly = true;
            }
        }
        for (String symbol : eligible) {
            try { result.put(symbol, fetchQuote(symbol)); }
            catch (UnavailableSymbol issue) { issues.put(symbol, issue.getMessage()); }
        }
        return new BookTickers(result, issues);
    }

    private static String noBook(String symbol) { return symbol + " 暂无有效买一／卖一"; }

    static Map<String, Rules.Quote> readTickers(JSONArray response, Set<String> symbols, long serverTime) {
        Map<String, Rules.Quote> result = new LinkedHashMap<>();
        for (int i = 0; i < response.length(); i++) {
            JSONObject row = response.optJSONObject(i);
            if (row == null) continue;
            String symbol = row.optString("symbol");
            if (symbols.contains(symbol)) {
                try { result.put(symbol, readQuote(row, serverTime)); }
                catch (IllegalArgumentException | IllegalStateException | JSONException stale) {}
            }
        }
        return result;
    }

    private static Rules.Quote readQuote(JSONObject q, long serverTime) throws JSONException {
        long age = serverTime - q.getLong("time");
        if (age > 10000 || age < -3000) throw new IllegalStateException("币安盘口时间超出可用范围");
        return new Rules.Quote(decimal(q, "bidPrice"), decimal(q, "askPrice"),
                decimal(q, "bidQty"), decimal(q, "askQty"));
    }

    @Override public boolean hedgeMode() throws Exception {
        return ((JSONObject) signed("GET", "/fapi/v1/positionSide/dual", "")).getBoolean("dualSidePosition");
    }

    @Override public Position position(String symbol) throws Exception {
        JSONArray list = (JSONArray) signed("GET", "/fapi/v3/positionRisk", symbolParam(symbol));
        return readPositions(list, symbol).getOrDefault(symbol, new Position());
    }

    @Override public Map<String, Position> positions() throws Exception {
        return java.util.Collections.unmodifiableMap(
                readPositions((JSONArray) signed("GET", "/fapi/v3/positionRisk", ""), null));
    }

    static java.util.Map<String, Position> readPositions(JSONArray list, String requested) throws JSONException {
        java.util.Map<String, Position> all = new java.util.TreeMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.getJSONObject(i);
            String symbol = row.getString("symbol");
            if (requested != null && !requested.equals(symbol)) continue;
            String side = row.getString("positionSide");
            BigDecimal amount = decimal(row, "positionAmt");
            if (!"LONG".equals(side) && !"SHORT".equals(side) && !"BOTH".equals(side)) {
                throw new IllegalStateException("无法识别账户持仓模式");
            }
            if (amount.signum() == 0) continue;
            Position result = all.computeIfAbsent(symbol, key -> new Position());
            BigDecimal notional = optionalDecimal(row, "notional");
            if (notional != null) result.reportedNotional = result.reportedNotional.add(notional.abs());
            BigDecimal entry = optionalDecimal(row, "entryPrice");
            BigDecimal unrealized = optionalDecimal(row, "unRealizedProfit");
            if (entry != null && entry.signum() <= 0) entry = null;
            long updated = row.optLong("updateTime");
            String asset = row.optString("marginAsset");
            if (!asset.matches("[A-Z0-9]{1,20}")) asset = "";
            PositionRisk risk = new PositionRisk(displayAmount(row, "positionInitialMargin"),
                    displayAmount(row, "liquidationPrice"), displayAmount(row, "isolatedWallet"), asset);
            if ("LONG".equals(side) || ("BOTH".equals(side) && amount.signum() > 0)) {
                result.longs = amount.abs();
                result.longEntry = entry;
                result.longUnrealized = unrealized;
                result.longRisk = risk;
                result.longUpdated = updated;
            } else {
                result.shorts = amount.abs();
                result.shortEntry = entry;
                result.shortUnrealized = unrealized;
                result.shortRisk = risk;
                result.shortUpdated = updated;
            }
        }
        return all;
    }

    private static BigDecimal displayAmount(JSONObject row, String field) {
        try {
            BigDecimal value = optionalDecimal(row, field);
            return value != null && value.signum() >= 0 ? value : null;
        } catch (JSONException | NumberFormatException invalid) {
            return null;
        }
    }

    Map<String, Integer> symbolLeverages() throws Exception {
        return readSymbolLeverages((JSONArray) signed("GET", "/fapi/v1/symbolConfig", ""));
    }

    static Map<String, Integer> readSymbolLeverages(JSONArray rows) throws JSONException {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            int leverage = row.optInt("leverage");
            if (leverage > 0 && leverage <= 125) result.put(row.getString("symbol"), leverage);
        }
        return Collections.unmodifiableMap(result);
    }

    Map<String, BigDecimal> markPrices(Set<String> symbols) throws Exception {
        if (symbols.isEmpty()) return Collections.emptyMap();
        String query = symbols.size() == 1 ? symbolParam(symbols.iterator().next()) : "";
        long now = currentServerTime();
        Object response = request("GET", "/fapi/v1/premiumIndex", query, false);
        return readMarkPrices(response, symbols, now);
    }

    static Map<String, BigDecimal> readMarkPrices(Object response, Set<String> symbols, long now) throws JSONException {
        return readPrices(response, symbols, now, "markPrice", 15000);
    }

    Map<String, BigDecimal> latestPrices(Set<String> symbols) throws Exception {
        if (symbols.isEmpty()) return Collections.emptyMap();
        String query = symbols.size() == 1 ? symbolParam(symbols.iterator().next()) : "";
        long now = currentServerTime();
        return readLatestPrices(request("GET", "/fapi/v2/ticker/price", query, false), symbols, now);
    }

    static Map<String, BigDecimal> readLatestPrices(Object response, Set<String> symbols, long now) throws JSONException {
        return readPrices(response, symbols, now, "price", 60000);
    }

    private static Map<String, BigDecimal> readPrices(Object response, Set<String> symbols, long now,
            String field, long maxAge) throws JSONException {
        JSONArray rows = response instanceof JSONArray ? (JSONArray) response : new JSONArray().put(response);
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String symbol = row.getString("symbol");
            if (!symbols.contains(symbol)) continue;
            long age = now - row.getLong("time");
            BigDecimal price = decimal(row, field);
            if (price.signum() > 0 && age >= -10000 && age <= maxAge) prices.put(symbol, price);
        }
        return prices;
    }

    Map<String, List<LeaderRisk.Tier>> maintenanceTiers() throws Exception {
        return readMaintenanceTiers((JSONArray) signed("GET", "/fapi/v1/leverageBracket", ""));
    }

    static Map<String, List<LeaderRisk.Tier>> readMaintenanceTiers(JSONArray entries) {
        Map<String, List<LeaderRisk.Tier>> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String symbol = entry.optString("symbol");
            if (!Rules.isUsdtSymbol(symbol)) continue;
            try {
                BigDecimal factor = optionalDecimal(entry, "notionalCoef");
                // Personal adjustments are not transferable to a different account.
                if (factor != null && factor.compareTo(BigDecimal.ONE) != 0) continue;
                List<LeaderRisk.Tier> tiers = new ArrayList<>();
                JSONArray rows = entry.getJSONArray("brackets");
                BigDecimal previousCap = Rules.ZERO, previousRate = Rules.ZERO, previousDeduction = Rules.ZERO;
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject row = rows.getJSONObject(j);
                    LeaderRisk.Tier tier = new LeaderRisk.Tier(decimal(row, "notionalCap"),
                            decimal(row, "maintMarginRatio"), decimal(row, "cum"));
                    BigDecimal before = previousCap.multiply(previousRate).subtract(previousDeduction).max(Rules.ZERO);
                    BigDecimal after = previousCap.multiply(tier.rate).subtract(tier.deduction).max(Rules.ZERO);
                    if (decimal(row, "notionalFloor").compareTo(previousCap) != 0
                            || tier.cap.compareTo(previousCap) <= 0 || tier.rate.compareTo(previousRate) < 0
                            || before.subtract(after).abs().compareTo(new BigDecimal("0.01")) > 0) {
                        tiers.clear();
                        break;
                    }
                    tiers.add(tier);
                    previousCap = tier.cap;
                    previousRate = tier.rate;
                    previousDeduction = tier.deduction;
                }
                if (!tiers.isEmpty()) result.put(symbol, Collections.unmodifiableList(tiers));
            } catch (JSONException | IllegalArgumentException invalid) {
                // A malformed contract falls back to the reference rate independently.
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override public int currentLeverage(String symbol) throws Exception {
        JSONArray list = (JSONArray) signed("GET", "/fapi/v1/symbolConfig", symbolParam(symbol));
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.getJSONObject(i);
            if (symbol.equals(row.getString("symbol"))) return row.getInt("leverage");
        }
        return 0;
    }

    @Override public int allowedLeverage(String symbol, int requested, BigDecimal exposure, BigDecimal margin,
            BigDecimal remainingNotional) throws Exception {
        LeverageTable table = leverageTables.get(symbol);
        long now = System.currentTimeMillis();
        if (table == null || now < table.checkedAt || now - table.checkedAt >= SYMBOL_CACHE_MS) {
            Object response = signed("GET", "/fapi/v1/leverageBracket", symbolParam(symbol));
            table = new LeverageTable(readBrackets(response, symbol));
            leverageTables.put(symbol, table);
        }
        return highestLeverage(table.brackets, Math.min(requested, table.observedMax), exposure, margin, remainingNotional);
    }

    static List<LeverageBracket> readBrackets(Object response, String symbol) throws JSONException, IOException {
        JSONArray entries = response instanceof JSONArray ? (JSONArray) response : new JSONArray().put(response);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !symbol.equals(entry.optString("symbol"))) continue;
            BigDecimal factor = optionalDecimal(entry, "notionalCoef");
            if (factor == null) factor = BigDecimal.ONE;
            if (factor.signum() <= 0) throw new IOException(symbol + " 币安返回无效的杠杆档位系数");
            JSONArray brackets = entry.getJSONArray("brackets");
            List<LeverageBracket> result = new ArrayList<>();
            for (int j = 0; j < brackets.length(); j++) {
                JSONObject bracket = brackets.getJSONObject(j);
                result.add(new LeverageBracket(bracket.getInt("initialLeverage"), decimal(bracket, "notionalCap").multiply(factor)));
            }
            if (!result.isEmpty()) return result;
        }
        throw new IOException(symbol + " 币安未返回杠杆档位");
    }

    static int highestLeverage(List<LeverageBracket> brackets, int requested, BigDecimal exposure, BigDecimal margin,
            BigDecimal remainingNotional) {
        if (margin.signum() <= 0) return 0;
        int highest = 0;
        for (LeverageBracket bracket : brackets) {
            BigDecimal room = bracket.cap.subtract(exposure);
            if (room.signum() <= 0) continue;
            int cap = Math.min(requested, bracket.maximum);
            if (remainingNotional == null || remainingNotional.compareTo(room) > 0) {
                cap = room.divide(margin, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(cap)).intValueExact();
            }
            highest = Math.max(highest, cap);
        }
        return highest;
    }

    @Override public int changeLeverage(String symbol, int leverage) throws Exception {
        JSONObject result;
        try { result = (JSONObject) signed("POST", "/fapi/v1/leverage", symbolParam(symbol) + "&leverage=" + leverage); }
        catch (Failure failure) {
            int cap = failure.statedLeverageCap();
            if (cap > 0 && cap < leverage) rememberLeverageCap(symbol, cap);
            else if (failure.code == -4028 || failure.code == -2027) leverageTables.remove(symbol);
            throw failure;
        }
        int actual = result.getInt("leverage");
        if (!symbol.equals(result.getString("symbol")) || actual < 1 || actual > leverage) {
            throw new IllegalStateException("币安未确认所设杠杆");
        }
        if (actual < leverage) rememberLeverageCap(symbol, actual);
        return actual;
    }

    private void rememberLeverageCap(String symbol, int cap) {
        LeverageTable table = leverageTables.get(symbol);
        if (table != null) table.observedMax = Math.min(table.observedMax, cap);
    }

    @Override public void submit(String symbol, Rules.Action action, boolean hedge, BigDecimal quantity,
            BigDecimal limit, String id, boolean fullFill) throws Exception {
        String params = symbolParam(symbol) + "&side=" + action.exchangeSide()
                + "&type=" + (limit == null ? "MARKET" : "LIMIT")
                + "&quantity=" + Rules.show(quantity) + "&newClientOrderId=" + enc(id)
                + "&newOrderRespType=ACK";
        if (hedge) params += "&positionSide=" + action.positionSide();
        else if (!action.opens) params += "&reduceOnly=true";
        if (limit != null) params += "&timeInForce=" + (fullFill ? "FOK" : "IOC") + "&price=" + Rules.show(limit);
        try { signed("POST", "/fapi/v1/order", params); }
        catch (Failure failure) {
            if (action.opens && (failure.code == -2027 || failure.code == -4028)) leverageTables.remove(symbol);
            if (action.opens && failure.belowOrderMinimum()) listedAt = 0;
            throw failure;
        }
    }

    @Override public JSONObject order(String symbol, String id) throws Exception {
        return (JSONObject) signed("GET", "/fapi/v1/order", symbolParam(symbol)
                + "&origClientOrderId=" + enc(id));
    }

    @Override public Map<String, Set<String>> openOrderIds() throws Exception {
        JSONArray rows = (JSONArray) signed("GET", "/fapi/v1/openOrders", "");
        Map<String, Set<String>> ids = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ids.computeIfAbsent(row.getString("symbol"), symbol -> new LinkedHashSet<>())
                    .add(row.getString("clientOrderId"));
        }
        return ids;
    }

    @Override public Fills fills(String symbol, long orderId) throws Exception {
        JSONArray rows = (JSONArray) signed("GET", "/fapi/v1/userTrades",
                symbolParam(symbol) + "&orderId=" + orderId + "&limit=1000");
        return readFills(rows, symbol, orderId);
    }

    @Override public List<AccountTrade> tradesSince(String symbol, long since) throws Exception {
        long end = currentServerTime();
        long start = since + timeOffset;
        if (since <= 0 || start > end || end - start >= 604800000L) return Collections.emptyList();
        JSONArray rows = (JSONArray) signed("GET", "/fapi/v1/userTrades", symbolParam(symbol)
                + "&startTime=" + start + "&endTime=" + end + "&limit=1000");
        if (rows.length() >= 1000) return Collections.emptyList();
        Map<Long, AccountTrade> trades = new java.util.TreeMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long time = row.getLong("time");
            if (!symbol.equals(row.getString("symbol")) || time < start || time > end) continue;
            String side = row.getString("side");
            BigDecimal quantity = decimal(row, "qty");
            if ((!side.equals("BUY") && !side.equals("SELL")) || quantity.signum() <= 0) {
                throw new IOException("币安账户成交明细无效");
            }
            long id = row.getLong("id");
            trades.putIfAbsent(id, new AccountTrade(id, row.getLong("orderId"), time,
                    row.getString("positionSide"), side.equals("BUY"), quantity, optionalDecimal(row, "realizedPnl")));
        }
        return new ArrayList<>(trades.values());
    }

    static Fills readFills(JSONArray rows, String symbol, long orderId) throws JSONException, IOException {
        Fills result = new Fills();
        BigDecimal pnl = Rules.ZERO;
        boolean completePnl = true;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!symbol.equals(row.getString("symbol")) || row.getLong("orderId") != orderId
                    || !seen.add(row.getLong("id"))) continue;
            BigDecimal price = decimal(row, "price"), quantity = decimal(row, "qty");
            if (price.signum() <= 0 || quantity.signum() <= 0) throw new IOException("币安成交明细无效");
            result.quantity = result.quantity.add(quantity);
            result.value = result.value.add(price.multiply(quantity));
            BigDecimal realized = optionalDecimal(row, "realizedPnl");
            if (realized == null) completePnl = false;
            else pnl = pnl.add(realized);
            result.minPrice = result.minPrice == null ? price : result.minPrice.min(price);
            result.maxPrice = result.maxPrice == null ? price : result.maxPrice.max(price);
            result.latest = Math.max(result.latest, row.getLong("time"));
        }
        if (completePnl && result.quantity.signum() > 0) result.realizedPnl = pnl;
        return result;
    }

    long currentServerTime() throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (measuredAt == 0 || now - measuredAt > 60000) {
            long before = System.currentTimeMillis();
            long server = ((JSONObject) request("GET", "/fapi/v1/time", "", false)).getLong("serverTime");
            long after = System.currentTimeMillis();
            timeOffset = server - (before + (after - before) / 2);
            measuredAt = SystemClock.elapsedRealtime();
        }
        return System.currentTimeMillis() + timeOffset;
    }

    private Object signed(String method, String path, String query) throws Exception {
        String signed;
        try {
            String params = (query.isEmpty() ? "" : query + "&")
                    + "recvWindow=5000&timestamp=" + currentServerTime();
            signed = params + "&signature=" + sign(credentials.secret, params);
        } catch (Exception beforeSend) {
            if ("POST".equals(method)) throw new NotSent("币安请求在发送前未能完成签名或校时", beforeSend);
            throw beforeSend;
        }
        return request(method, path, signed, true);
    }

    private Object request(String method, String path, String params, boolean authenticated) throws Exception {
        if (!permitted(realm, method, path, authenticated)) {
            throw new IllegalStateException("当前版本不发送该类型的账户操作");
        }
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicLong throttle = demo ? DEMO_BACKOFF : LIVE_BACKOFF;
        long blocked = throttle.get();
        if (blocked > now) throw new NotSent("币安限流，" + ((blocked - now + 999) / 1000) + " 秒后可重试", null);
        boolean post = "POST".equals(method);
        String base = demo ? DEMO_BASE : READ_BASE;
        URL url = new URL(base + path + (!post && !params.isEmpty() ? "?" + params : ""));
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (authenticated) connection.setRequestProperty("X-MBX-APIKEY", credentials.key);
            if (post) {
                byte[] body = params.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            if (status == 429 || status == 418) {
                long next = System.currentTimeMillis() + retryMillis(connection.getHeaderField("Retry-After"), status);
                throttle.accumulateAndGet(next, Math::max);
            }
            if (status < 200 || status >= 300) {
                JSONObject error;
                try {
                    error = new JSONObject(read(connection.getErrorStream()));
                } catch (JSONException | IOException ignored) {
                    throw new Failure(status, 0);
                }
                throw new Failure(status, error.optInt("code", 0), error.optString("msg", ""));
            }
            Object result = new JSONTokener(read(connection.getInputStream())).nextValue();
            if (result instanceof JSONObject && ((JSONObject) result).optInt("code", 0) < 0) {
                JSONObject error = (JSONObject) result;
                throw new Failure(status, error.optInt("code", 0), error.optString("msg", ""));
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    static boolean permitted(boolean demo, String verb, String path, boolean authenticated) {
        return permitted(demo ? DEMO : LIVE_READ, verb, path, authenticated);
    }

    static boolean permitted(String realm, String verb, String path, boolean authenticated) {
        boolean trading = DEMO.equals(realm) || LIVE.equals(realm);
        if (!trading && !LIVE_READ.equals(realm)) return false;
        if ("GET".equals(verb)) {
            if ("/fapi/v1/time".equals(path)) return !authenticated;
            if ("/fapi/v1/premiumIndex".equals(path) || "/fapi/v2/ticker/price".equals(path)) return !authenticated;
            if ("/fapi/v3/balance".equals(path) || "/fapi/v3/positionRisk".equals(path)
                    || "/fapi/v1/symbolConfig".equals(path) || "/fapi/v1/leverageBracket".equals(path)) return authenticated;
            if (!trading) return false;
            if ("/fapi/v1/exchangeInfo".equals(path) || "/fapi/v1/ticker/bookTicker".equals(path)) {
                return !authenticated;
            }
            return authenticated && ("/fapi/v1/positionSide/dual".equals(path) || "/fapi/v1/order".equals(path)
                    || "/fapi/v1/openOrders".equals(path) || "/fapi/v1/userTrades".equals(path)
                    || "/fapi/v3/account".equals(path));
        }
        return trading && authenticated && "POST".equals(verb)
                && ("/fapi/v1/order".equals(path) || "/fapi/v1/leverage".equals(path));
    }

    static long retryMillis(String value, int status) {
        if (value != null) {
            try {
                return Math.max(1000L, Math.min(86400L, Long.parseLong(value)) * 1000L);
            } catch (NumberFormatException notSeconds) {
                try {
                    return Math.max(1000L, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - System.currentTimeMillis());
                } catch (RuntimeException invalidDate) {}
            }
        }
        return status == 418 ? 300000L : 60000L;
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = in.read(buffer)) != -1) {
                if (out.size() + size > 6_000_000) throw new IOException("币安响应超出大小限制");
                out.write(buffer, 0, size);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String symbolParam(String symbol) {
        Rules.requireSymbol(symbol);
        return "symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
    }

    static String accountFingerprint(Secrets secrets) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(secrets.key.getBytes(StandardCharsets.UTF_8)));
    }

    static String sign(String secret, String params) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(params.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) out.append(String.format(Locale.ROOT, "%02x", item & 255));
        return out.toString();
    }

    private static String enc(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }

    static BigDecimal decimal(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Number) && !(value instanceof String)) throw new JSONException("币安返回的数字字段无效：" + key);
        return new BigDecimal(value.toString());
    }

    static BigDecimal optionalDecimal(JSONObject object, String key) throws JSONException {
        return object.isNull(key) ? null : decimal(object, key);
    }
}
