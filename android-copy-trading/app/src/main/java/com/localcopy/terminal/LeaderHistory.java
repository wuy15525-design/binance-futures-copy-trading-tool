package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 在内存中核对网页窗口的连续性，并按成交顺序重放带单仓位。输入按时间从早到晚排列，
 * 同秒顺序和笔数由官网快照提供，档案重读时尽量沿用已匹配成交的标识。
 */
final class LeaderHistory {
    private static final BigDecimal COST_TOLERANCE = new BigDecimal("0.0005");
    private static final BigDecimal SIZE_TOLERANCE = new BigDecimal("0.0001");
    private static final BigDecimal CLOSE_BASIS_TOLERANCE = new BigDecimal("0.000001");
    static final class Gap extends IllegalArgumentException {
        Gap() { super("官网记录与上次接不上，成交档案存在缺口"); }
    }

    private LeaderHistory() {}

    static void verify(List<Rules.Signal> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("官网记录为空，本轮跳过");
        if (rows.size() > HistoryPagination.MAX_ROWS) throw new IllegalArgumentException("官网分页超过本次核对范围");
        Set<String> events = new HashSet<>();
        long previous = 0;
        for (Rules.Signal row : rows) {
            if (row.time < previous || !events.add(row.id)) {
                throw new IllegalArgumentException("官网记录顺序不明确");
            }
            previous = row.time;
        }
    }

    static List<Rules.Signal> newRows(List<Rules.Signal> previous, List<Rules.Signal> rows) {
        return newRows(previous, rows, false);
    }

    static List<Rules.Signal> newRows(List<Rules.Signal> previous, List<Rules.Signal> rows, boolean expanded) {
        return merge(previous, rows, expanded, false).added;
    }

    static final class Merge {
        final List<Rules.Signal> rows, added;
        final int prepended;

        Merge(List<Rules.Signal> rows, int firstNew, int prepended) {
            this.rows = rows;
            this.added = new ArrayList<>(rows.subList(firstNew, rows.size()));
            this.prepended = prepended;
        }
    }

    // 「更多」后的列表必须按顺序包含先前已读记录。只多出最新成交时，仍然无法证明旧页向前推进。
    static int pageGrowth(List<Rules.Signal> retained, List<Rules.Signal> expanded) {
        int matched = 0;
        long latest = retained.get(retained.size() - 1).time;
        int beforeLatest = 0;
        while (retained.get(beforeLatest).time < latest) beforeLatest++;
        int additionalOlder = -beforeLatest;
        for (Rules.Signal visible : expanded) {
            if (visible.time < latest) additionalOlder++;
            if (matched >= retained.size()) continue;
            Rules.Signal known = retained.get(matched);
            if (known.fingerprint.equals(visible.fingerprint)
                    && compatibleValue(known.turnover, visible.turnover)
                    && compatibleValue(known.realizedPnl, visible.realizedPnl)) matched++;
        }
        if (matched < retained.size()) throw new Gap();
        if (additionalOlder > 0 || retained.get(0).time < latest) return additionalOlder;
        // When all retained cards are from the same second, the older page can fill out that second.
        return Math.max(0, groupEnd(expanded, 0) - retained.size());
    }

    static List<Rules.Signal> joinBaseline(List<Rules.Signal> newer, List<Rules.Signal> older, long boundaryExclusive) {
        int split = atOrAfter(newer, boundaryExclusive);
        if (split == 0 || older.isEmpty() || older.get(older.size() - 1).time >= boundaryExclusive) throw new Gap();
        // The narrower date window includes the entire boundary day. It must contain
        // the accepted overlap before it can supply any older rows.
        older = reconcileBaselineOverlap(newer.subList(0, split), older);
        List<Rules.Signal> joined = new ArrayList<>(older.size() + newer.size() - split);
        joined.addAll(older);
        joined.addAll(newer.subList(split, newer.size()));
        verify(joined);
        return joined;
    }

    // 重读可能只显示部分同秒成交。对齐顺序后，先保留笔数有争议的原样本，等独立复核确定实际笔数。
    static List<Rules.Signal> reconcileBaselineOverlap(List<Rules.Signal> known, List<Rules.Signal> reread) {
        if (known.isEmpty() || reread.isEmpty()) throw new Gap();
        Map<Long, List<Rules.Signal>> uncertain = new HashMap<>();
        int current = 0;
        for (int previous = 0; previous < known.size(); previous = groupEnd(known, previous)) {
            long stamp = known.get(previous).time;
            while (current < reread.size() && reread.get(current).time < stamp) current = groupEnd(reread, current);
            if (current == reread.size() || reread.get(current).time != stamp) throw new Gap();
            int oldEnd = groupEnd(known, previous), newEnd = groupEnd(reread, current);
            if (oldEnd - previous > newEnd - current) {
                List<Rules.Signal> old = known.subList(previous, oldEnd), fresh = reread.subList(current, newEnd);
                // Hold both observations until the overlap's multiplicity can be reviewed separately.
                if (!TradeReview.compatible(old, fresh)) throw new Gap();
                uncertain.put(stamp, old);
            }
            current = newEnd;
        }
        List<Rules.Signal> merged = TradeReview.replace(reread, uncertain, null);
        pageGrowth(known, merged);
        verify(merged);
        return merged;
    }

    private static boolean compatibleValue(BigDecimal recorded, BigDecimal visible) {
        return recorded == null || visible == null || recorded.compareTo(visible) == 0;
    }

    /**
     * 用重合记录接上档案末尾。只在窗口边缘允许同秒记录因分页截断，窗口内部逐秒核对。
     * 对齐后为新增记录分配出现序号，并把前补历史与水位之后的新增成交分别交给存储层。
     */
    static Merge merge(List<Rules.Signal> previous, List<Rules.Signal> rows, boolean expanded, boolean legacyOrder) {
        verify(rows);
        List<Rules.Signal> aligned = new ArrayList<>(rows);
        if (previous.isEmpty()) return new Merge(aligned, 0, 0);
        long end = previous.get(previous.size() - 1).time;
        long start = Math.max(previous.get(0).time, rows.get(0).time);
        if (start > end) throw new Gap();
        boolean[] matched = new boolean[rows.size()];
        int old = atOrAfter(previous, start), visible = atOrAfter(rows, start);
        while (old < previous.size() && previous.get(old).time < end) {
            if (visible >= rows.size() || rows.get(visible).time != previous.get(old).time) throw new Gap();
            int oldEnd = groupEnd(previous, old), visibleEnd = groupEnd(rows, visible);
            int oldLength = oldEnd - old, visibleLength = visibleEnd - visible;
            if (oldLength != visibleLength) {
                boolean clipped = visibleLength < oldLength ? visible == 0 : old == 0 && (expanded || visible != 0);
                if (!clipped) throw new Gap();
            }
            int shared = Math.min(oldLength, visibleLength);
            if (!matches(previous, oldEnd - shared, rows, visibleEnd - shared, shared, legacyOrder)) throw new Gap();
            align(previous, oldEnd - shared, aligned, visibleEnd - shared, shared, legacyOrder, matched);
            old = oldEnd;
            visible = visibleEnd;
        }
        if (visible >= rows.size() || rows.get(visible).time != end) throw new Gap();
        int latestEnd = groupEnd(rows, visible);
        int known = previous.size() - old;
        if (expanded && old == 0 && latestEnd - visible >= known) {
            // Expanding the oldest page can reveal an earlier prefix in this same second.
            int candidate = latestEnd - known;
            while (candidate >= visible && !matches(previous, old, rows, candidate, known, legacyOrder)) candidate--;
            if (candidate < visible) throw new Gap();
            visible = candidate;
        } else if (visible == 0) {
            // A rolling first page can retain only the newest suffix of the archived second.
            known = Math.min(known, latestEnd);
            while (known > 0 && !matches(previous, previous.size() - known, rows, visible, known, legacyOrder)) known--;
            if (known == 0) throw new Gap();
            old = previous.size() - known;
            if (legacyOrder) {
                Map<String, Integer> omitted = legacyOccurrences(previous, old);
                for (int i = 0; i < known; i++) omitted.remove(rows.get(i).fingerprint);
                for (int i = known; i < latestEnd; i++) if (omitted.containsKey(rows.get(i).fingerprint)) throw new Gap();
            }
        } else if (visible + known > latestEnd || !matches(previous, old, rows, visible, known, legacyOrder)) {
            throw new Gap();
        }
        align(previous, old, aligned, visible, known, legacyOrder, matched);
        Map<String, Integer> nextOccurrence = new HashMap<>();
        for (Rules.Signal row : previous) nextOccurrence.merge(row.fingerprint, row.occurrence + 1, Math::max);
        for (int i = 0; i < aligned.size(); i++) {
            if (matched[i]) continue;
            Rules.Signal row = aligned.get(i);
            int occurrence = nextOccurrence.getOrDefault(row.fingerprint, 0);
            nextOccurrence.put(row.fingerprint, occurrence + 1);
            aligned.set(i, row.withOccurrence(occurrence));
        }
        int prepended = 0;
        while (!matched[prepended]) prepended++;
        return new Merge(aligned, visible + known, prepended);
    }

    private static int atOrAfter(List<Rules.Signal> rows, long time) {
        int index = 0;
        while (index < rows.size() && rows.get(index).time < time) index++;
        return index;
    }

    private static int groupEnd(List<Rules.Signal> rows, int start) {
        long time = rows.get(start).time;
        int end = start + 1;
        while (end < rows.size() && rows.get(end).time == time) end++;
        return end;
    }

    private static boolean matches(List<Rules.Signal> previous, int old, List<Rules.Signal> rows,
            int visible, int length, boolean legacyOrder) {
        if (legacyOrder) {
            Map<String, Integer> occurrences = legacyOccurrences(previous, old);
            for (int i = 0; i < length; i++) if (occurrences.remove(rows.get(visible + i).fingerprint) == null) return false;
            return true;
        }
        for (int i = 0; i < length; i++) {
            if (!previous.get(old + i).fingerprint.equals(rows.get(visible + i).fingerprint)) return false;
        }
        return true;
    }

    private static void align(List<Rules.Signal> previous, int old, List<Rules.Signal> aligned,
            int visible, int length, boolean legacyOrder, boolean[] matched) {
        Map<String, Integer> legacyIds = legacyOrder ? legacyOccurrences(previous, old) : null;
        for (int i = 0; i < length; i++) {
            int index = visible + i;
            Rules.Signal row = aligned.get(index);
            int occurrence = legacyOrder ? legacyIds.get(row.fingerprint) : previous.get(old + i).occurrence;
            if (!legacyOrder && row.reportedAction.sideOnly()) row = row.withTradeAction(previous.get(old + i).action);
            aligned.set(index, row.withOccurrence(occurrence));
            matched[index] = true;
        }
    }

    private static Map<String, Integer> legacyOccurrences(List<Rules.Signal> rows, int index) {
        long time = rows.get(index).time;
        while (index > 0 && rows.get(index - 1).time == time) index--;
        Map<String, Integer> occurrences = new HashMap<>();
        while (index < rows.size() && rows.get(index).time == time) {
            Rules.Signal row = rows.get(index++);
            occurrences.put(row.fingerprint, row.occurrence);
        }
        return occurrences;
    }

    static BigDecimal after(BigDecimal before, Rules.Signal signal) {
        return signal.action.opens ? before.add(signal.quantity) : before.subtract(signal.quantity).max(Rules.ZERO);
    }

    static final class Holding {
        final BigDecimal quantity, entryPrice, visibleEntryPrice, inferredQuantity;
        final BigDecimal closeCost, addedQuantity, addedValue;
        final long updatedAt;
        final boolean costFromClose, uncertainBasis;

        Holding(BigDecimal quantity, BigDecimal entryPrice, long updatedAt) {
            this(quantity, entryPrice, entryPrice, updatedAt, null, false, null, Rules.ZERO, Rules.ZERO, false);
        }

        private Holding(BigDecimal quantity, BigDecimal entryPrice, BigDecimal visibleEntryPrice, long updatedAt,
                BigDecimal inferredQuantity, boolean costFromClose, BigDecimal closeCost,
                BigDecimal addedQuantity, BigDecimal addedValue, boolean uncertainBasis) {
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.visibleEntryPrice = visibleEntryPrice;
            this.updatedAt = updatedAt;
            this.inferredQuantity = inferredQuantity;
            this.costFromClose = costFromClose;
            this.closeCost = closeCost;
            this.addedQuantity = addedQuantity;
            this.addedValue = addedValue;
            this.uncertainBasis = uncertainBasis;
        }

        BigDecimal displayQuantity() {
            if (quantity.signum() <= 0) return Rules.ZERO;
            return inferredQuantity == null ? quantity : inferredQuantity;
        }

        BigDecimal unrealized(BigDecimal lastPrice, boolean longSide) {
            BigDecimal size = displayQuantity();
            if (size.signum() <= 0 || entryPrice == null || entryPrice.signum() <= 0
                    || lastPrice == null || lastPrice.signum() <= 0) return null;
            BigDecimal difference = longSide ? lastPrice.subtract(entryPrice) : entryPrice.subtract(lastPrice);
            return difference.multiply(size, Rules.MC);
        }

        JSONObject json() throws JSONException {
            JSONObject saved = new JSONObject().put("q", Rules.show(quantity)).put("t", updatedAt)
                    .put("p", entryPrice == null ? null : Rules.show(entryPrice))
                    .put("visible", visibleEntryPrice == null ? JSONObject.NULL : Rules.show(visibleEntryPrice));
            if (inferredQuantity != null) saved.put("inferred", Rules.show(inferredQuantity));
            if (costFromClose) saved.put("fromClose", true);
            if (uncertainBasis) saved.put("uncertainBasis", true);
            if (closeCost != null) {
                saved.put("closeCost", Rules.show(closeCost)).put("added", Rules.show(addedQuantity))
                        .put("addedValue", Rules.show(addedValue));
            }
            return saved;
        }

        static Holding read(JSONObject saved) throws JSONException {
            BigDecimal cost = FuturesApi.optionalDecimal(saved, "closeCost");
            BigDecimal price = FuturesApi.optionalDecimal(saved, "p");
            boolean derived = saved.optBoolean("fromClose");
            BigDecimal visiblePrice = saved.has("visible") ? FuturesApi.optionalDecimal(saved, "visible")
                    : derived ? null : price;
            return new Holding(FuturesApi.decimal(saved, "q"), price, visiblePrice, saved.optLong("t"),
                    FuturesApi.optionalDecimal(saved, "inferred"), derived, cost,
                    cost == null ? Rules.ZERO : FuturesApi.decimal(saved, "added"),
                    cost == null ? Rules.ZERO : FuturesApi.decimal(saved, "addedValue"),
                    saved.optBoolean("uncertainBasis"));
        }
    }

    static void apply(Map<String, Holding> positions, Rules.Signal signal) {
        if (signal.action.sideOnly()) {
            positions.remove(signal.symbol + "|LONG");
            positions.remove(signal.symbol + "|SHORT");
            return;
        }
        String leg = signal.leg();
        Holding previous = positions.get(leg);
        if (signal.action.opens) {
            positions.put(leg, opened(previous, signal));
            return;
        }

        // Public realized PnL cannot keep a position alive once the visible quantity is exhausted.
        if (previous == null || signal.quantity.compareTo(previous.quantity) >= 0) {
            positions.remove(leg);
            return;
        }
        BigDecimal remaining = previous.quantity.subtract(signal.quantity);
        BigDecimal cost = closeEntry(signal);
        BigDecimal known = previous.inferredQuantity;
        if (cost != null) {
            BigDecimal inferred = inferBeforeClose(previous, cost, previous.quantity);
            if (inferred != null) known = inferred;
            else if (known != null && !similarCost(cost, previous.entryPrice)) known = null;
        }
        BigDecimal inferredRemaining = known == null ? null : known.subtract(signal.quantity);
        if (inferredRemaining != null && inferredRemaining.compareTo(remaining) <= 0) inferredRemaining = null;

        BigDecimal price = previous.visibleEntryPrice;
        boolean derived = false;
        if (inferredRemaining != null || price == null) {
            price = cost == null ? previous.entryPrice : cost;
            derived = price != null && (cost != null || previous.costFromClose);
        }
        boolean uncertainBasis = signal.realizedPnl == null ? previous.uncertainBasis
                : cost == null || !similarCost(cost, price, CLOSE_BASIS_TOLERANCE);
        positions.put(leg, new Holding(remaining, price, previous.visibleEntryPrice, signal.time, inferredRemaining,
                derived, cost, Rules.ZERO, Rules.ZERO, uncertainBasis));
    }

    private static Holding opened(Holding previous, Rules.Signal signal) {
        BigDecimal fillValue = checkedTurnover(signal);
        if (fillValue == null) fillValue = signal.price.multiply(signal.quantity);
        if (previous == null || previous.quantity.signum() <= 0) {
            return new Holding(signal.quantity, fillValue.divide(signal.quantity, Rules.MC), signal.time);
        }
        BigDecimal quantity = previous.quantity.add(signal.quantity);
        BigDecimal known = previous.inferredQuantity == null ? null : previous.inferredQuantity.add(signal.quantity);
        BigDecimal visiblePrice = averageAfterOpen(previous.visibleEntryPrice, previous.quantity, fillValue, signal.quantity);
        BigDecimal price = known == null && visiblePrice != null ? visiblePrice
                : averageAfterOpen(previous.entryPrice, previous.displayQuantity(), fillValue, signal.quantity);
        return new Holding(quantity, price, visiblePrice, signal.time, known, previous.costFromClose, previous.closeCost,
                previous.closeCost == null ? Rules.ZERO : previous.addedQuantity.add(signal.quantity),
                previous.closeCost == null ? Rules.ZERO : previous.addedValue.add(fillValue), previous.uncertainBasis);
    }

    private static BigDecimal averageAfterOpen(BigDecimal price, BigDecimal size, BigDecimal value, BigDecimal added) {
        return price == null ? null : price.multiply(size).add(value).divide(size.add(added), Rules.MC);
    }

    private static BigDecimal checkedTurnover(Rules.Signal signal) {
        BigDecimal product = signal.price.multiply(signal.quantity);
        if (signal.turnover == null) return product;
        BigDecimal rounding = signal.price.ulp().multiply(signal.quantity).add(signal.turnover.ulp());
        BigDecimal difference = signal.turnover.subtract(product).abs();
        return difference.multiply(BigDecimal.valueOf(2)).compareTo(rounding) <= 0 ? signal.turnover : null;
    }

    static BigDecimal closeEntry(Rules.Signal signal) {
        if (signal.action.opens || signal.realizedPnl == null) return null;
        BigDecimal turnover = checkedTurnover(signal);
        if (turnover == null) return null;
        BigDecimal value = signal.action.isLong ? turnover.subtract(signal.realizedPnl) : turnover.add(signal.realizedPnl);
        return value.signum() > 0 ? value.divide(signal.quantity, Rules.MC) : null;
    }

    private static BigDecimal inferBeforeClose(Holding previous, BigDecimal cost, BigDecimal floor) {
        if (previous.closeCost == null || previous.addedQuantity.signum() <= 0 || similarCost(cost, previous.closeCost)) {
            return null;
        }
        BigDecimal priorSize = previous.addedValue.subtract(cost.multiply(previous.addedQuantity))
                .divide(cost.subtract(previous.closeCost), Rules.MC);
        // A near-zero solution cannot distinguish a fresh position from a rounded average.
        if (priorSize.compareTo(previous.addedQuantity.multiply(SIZE_TOLERANCE)) <= 0) return null;
        return consistentSize(priorSize.add(previous.addedQuantity), floor);
    }

    private static boolean similarCost(BigDecimal a, BigDecimal b) {
        return similarCost(a, b, COST_TOLERANCE);
    }

    private static boolean similarCost(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return b != null && a.subtract(b).abs().compareTo(a.max(b).multiply(tolerance)) <= 0;
    }

    private static BigDecimal consistentSize(BigDecimal size, BigDecimal floor) {
        if (size.compareTo(floor) >= 0) return size;
        return floor.subtract(size).compareTo(floor.multiply(SIZE_TOLERANCE)) <= 0 ? floor : null;
    }
}
