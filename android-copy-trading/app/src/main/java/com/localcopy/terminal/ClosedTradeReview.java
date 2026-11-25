package com.localcopy.terminal;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 把官网已平仓记录作为独立证据，用开平时间、最大持仓量和已平仓量核对同一轮成交。
 * 仅在仓位周期和笔数能够确定时为历史档案提供重复纠正结果。
 */
final class ClosedTradeReview {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final Pattern STAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final Pattern AMOUNT = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+(" + Rules.SYMBOL_BASE + ")");
    private static final Pattern OPEN_TIME = Pattern.compile("开仓时间|開倉時間|Open(?:ed)? Time", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSED = Pattern.compile("全部平仓|全部平倉|Fully Closed|Closed", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEAK = Pattern.compile("最大持仓量|最大持倉量|Max\\.? Open Interest|Max\\.? Position Size",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME = Pattern.compile("已平仓量|已平倉量|Closed Vol(?:ume)?\\.?|Closed Quantity",
            Pattern.CASE_INSENSITIVE);
    private static final int SEARCH_LIMIT = 4096;

    private ClosedTradeReview() {}

    static final class Position {
        final String leg;
        final long openedAt, closedAt;
        final BigDecimal peak, volume;

        Position(String leg, long openedAt, long closedAt, BigDecimal peak, BigDecimal volume) {
            this.leg = leg;
            this.openedAt = openedAt;
            this.closedAt = closedAt;
            this.peak = peak;
            this.volume = volume;
        }

        long openedAt() { return openedAt; }
        long closedAt() { return closedAt; }

        boolean covers(Rules.Signal trade) {
            return trade.leg().equals(leg) && trade.time >= openedAt && trade.time <= closedAt;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Position)) return false;
            Position position = (Position) other;
            return leg.equals(position.leg) && openedAt == position.openedAt && closedAt == position.closedAt
                    && peak.equals(position.peak) && volume.equals(position.volume);
        }

        @Override public int hashCode() { return Objects.hash(leg, openedAt, closedAt, peak, volume); }
    }

    static List<Position> parse(JSONObject data) throws JSONException {
        JSONArray rows = data.getJSONArray("positions");
        ZoneId zone = ZoneId.of(data.getString("zone"));
        Set<Position> positions = new LinkedHashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Position position = parse(rows.getString(i), zone);
            if (position != null) positions.add(position);
        }
        return new ArrayList<>(positions);
    }

    private static Position parse(String row, ZoneId zone) {
        List<String> fields = new ArrayList<>();
        for (String line : Normalizer.normalize(row, Normalizer.Form.NFKC).split("\\R")) {
            String field = line.trim();
            if (!field.isEmpty()) fields.add(field);
        }
        if (fields.isEmpty() || !Rules.isUsdtSymbol(fields.get(0))) return null;
        String symbol = fields.get(0);
        String leg = null;
        for (String field : fields) {
            if (field.matches("(?i)(?:(?:全仓|逐仓|全倉|逐倉|Cross|Isolated) )?(?:做多|Long)")) leg = symbol + "|LONG";
            if (field.matches("(?i)(?:(?:全仓|逐仓|全倉|逐倉|Cross|Isolated) )?(?:做空|Short)")) leg = symbol + "|SHORT";
        }
        if (leg == null) return null;
        try {
            long opened = time(valueAfter(fields, OPEN_TIME, STAMP), zone);
            long closed = time(valueAfter(fields, CLOSED, STAMP), zone);
            BigDecimal peak = quantity(valueAfter(fields, PEAK, AMOUNT), Rules.base(symbol));
            BigDecimal volume = quantity(valueAfter(fields, VOLUME, AMOUNT), Rules.base(symbol));
            if (opened > closed || peak.signum() <= 0 || peak.compareTo(volume) > 0) return null;
            return new Position(leg, opened, closed, peak, volume);
        } catch (java.time.DateTimeException | IllegalArgumentException incomplete) {
            return null;
        }
    }

    private static String valueAfter(List<String> fields, Pattern label, Pattern value) {
        for (int i = 1; i < fields.size(); i++) {
            if (label.matcher(fields.get(i - 1)).matches() && value.matcher(fields.get(i)).matches()) return fields.get(i);
        }
        throw new IllegalArgumentException("仓位历史字段不完整");
    }

    private static long time(String value, ZoneId zone) {
        LocalDateTime local = LocalDateTime.parse(value, TIME);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("仓位历史时间有歧义");
        return local.toInstant(offsets.get(0)).toEpochMilli();
    }

    private static BigDecimal quantity(String value, String base) {
        Matcher match = AMOUNT.matcher(value);
        if (!match.matches() || !base.equals(match.group(2))) throw new IllegalArgumentException("仓位历史数量单位不一致");
        return new BigDecimal(match.group(1).replace(",", "")).stripTrailingZeros();
    }

    static Map<Long, List<Rules.Signal>> corrections(List<Rules.Signal> rows, List<Position> positions) {
        Set<String> excluded = new LinkedHashSet<>();
        for (Position position : positions) {
            boolean overlaps = positions.stream().anyMatch(other -> !position.equals(other) && position.leg.equals(other.leg)
                    && other.openedAt <= position.closedAt && other.closedAt >= position.openedAt);
            if (overlaps) continue;
            List<Rules.Signal> cycle = new ArrayList<>();
            for (Rules.Signal row : rows) if (position.covers(row)) cycle.add(row);
            excluded.addAll(excess(cycle, position));
        }
        Map<Long, List<Rules.Signal>> corrected = new LinkedHashMap<>();
        for (Rules.Signal row : rows) if (excluded.contains(row.id)) corrected.put(row.time, new ArrayList<>());
        for (Rules.Signal row : rows) {
            List<Rules.Signal> kept = corrected.get(row.time);
            if (kept != null && !excluded.contains(row.id)) kept.add(row);
        }
        return corrected;
    }

    private static final class Run {
        final Rules.Signal trade;
        final int start, size;

        Run(Rules.Signal trade, int start, int size) {
            this.trade = trade;
            this.start = start;
            this.size = size;
        }
    }

    private static Set<String> excess(List<Rules.Signal> cycle, Position position) {
        if (cycle.size() < 3) return Collections.emptySet();
        Rules.Signal first = cycle.get(0);
        Rules.Signal last = cycle.get(cycle.size() - 1);
        if (!first.action.opens || first.time != position.openedAt || last.action.opens || last.time != position.closedAt) {
            return Collections.emptySet();
        }
        BigDecimal open = Rules.ZERO, close = Rules.ZERO;
        List<Run> repeats = new ArrayList<>();
        for (int i = 0; i < cycle.size();) {
            Rules.Signal trade = cycle.get(i);
            String key = TradeReview.key(trade);
            int end = i + 1;
            while (end < cycle.size() && key.equals(TradeReview.key(cycle.get(end)))) end++;
            int size = end - i;
            if (size > 1) repeats.add(new Run(trade, i, size));
            BigDecimal amount = trade.quantity.multiply(BigDecimal.valueOf(size));
            if (trade.action.opens) open = open.add(amount);
            else close = close.add(amount);
            i = end;
        }
        BigDecimal excessOpen = open.subtract(position.volume);
        BigDecimal excessClose = close.subtract(position.volume);
        if (repeats.isEmpty() || repeats.size() > 20 || excessOpen.signum() < 0 || excessClose.signum() < 0
                || excessOpen.signum() + excessClose.signum() == 0) return Collections.emptySet();
        Search search = new Search(cycle, position.peak, repeats);
        search.find(0, excessOpen, excessClose);
        if (search.attempts > SEARCH_LIMIT || search.solutions != 1) return Collections.emptySet();
        return search.excluded;
    }

    private static final class Search {
        final List<Rules.Signal> cycle;
        final BigDecimal expectedPeak;
        final List<Run> repeats;
        final boolean[] removed;
        Set<String> excluded;
        int attempts, solutions;

        Search(List<Rules.Signal> cycle, BigDecimal expectedPeak, List<Run> repeats) {
            this.cycle = cycle;
            this.expectedPeak = expectedPeak;
            this.repeats = repeats;
            removed = new boolean[cycle.size()];
        }

        void find(int index, BigDecimal excessOpen, BigDecimal excessClose) {
            if (solutions > 1 || ++attempts > SEARCH_LIMIT) return;
            if (index == repeats.size()) {
                if (excessOpen.signum() == 0 && excessClose.signum() == 0) verify();
                return;
            }
            Run run = repeats.get(index);
            boolean opens = run.trade.action.opens;
            BigDecimal available = opens ? excessOpen : excessClose;
            for (int count = 0; count < run.size; count++) {
                BigDecimal left = available.subtract(run.trade.quantity.multiply(BigDecimal.valueOf(count)));
                if (left.signum() < 0 || solutions > 1 || attempts > SEARCH_LIMIT) break;
                // Interchangeable cards share one key. Keep the last occurrence to retain the trailing anchor.
                for (int i = 0; i < run.size; i++) removed[run.start + i] = i < count;
                find(index + 1, opens ? left : excessOpen, opens ? excessClose : left);
            }
        }

        private void verify() {
            BigDecimal size = Rules.ZERO, peak = Rules.ZERO;
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < cycle.size(); i++) {
                Rules.Signal trade = cycle.get(i);
                if (removed[i]) {
                    ids.add(trade.id);
                    continue;
                }
                size = trade.action.opens ? size.add(trade.quantity) : size.subtract(trade.quantity);
                if (i < cycle.size() - 1 && size.signum() <= 0) return;
                peak = peak.max(size);
            }
            if (size.signum() == 0 && peak.compareTo(expectedPeak) == 0) {
                solutions++;
                excluded = ids;
            }
        }
    }
}
