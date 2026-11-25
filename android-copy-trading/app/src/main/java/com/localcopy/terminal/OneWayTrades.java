package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用已平仓历史或已核对仓位，把官网只写「做多／做空」的记录推断为开仓或平仓。
 * 证据不足或单笔跨过零仓位时，保留原始买卖方向供后续核对。
 */
final class OneWayTrades {
    private OneWayTrades() {}

    static boolean hasSideReports(List<Rules.Signal> rows) {
        return rows.stream().anyMatch(row -> row.reportedAction.sideOnly());
    }

    static List<Rules.Signal> resolve(List<Rules.Signal> rows, int firstNew, Map<String, BigDecimal> known,
            List<ClosedTradeReview.Position> positions) {
        Map<String, VerifiedFill> proven = verifiedFills(rows, positions);
        Map<String, BigDecimal> balances = new HashMap<>(known);
        Set<String> confirmedFlat = new HashSet<>();
        List<Rules.Signal> result = new ArrayList<>(rows);
        for (int i = 0; i < rows.size(); i++) {
            Rules.Signal row = rows.get(i);
            VerifiedFill proof = proven.get(row.id);
            if (i < firstNew) {
                if (proof != null) result.set(i, row.withTradeAction(proof.action));
                if (proof != null && proof.after.signum() == 0) confirmedFlat.add(row.symbol);
                else confirmedFlat.remove(row.symbol);
                continue;
            }
            // A history response may arrive after a read-only baseline was saved.
            // A fully verified close at the untouched tail establishes a fresh zero.
            if (i == firstNew) for (String symbol : confirmedFlat) balances.putIfAbsent(symbol, Rules.ZERO);
            if (!row.reportedAction.sideOnly()) {
                balances.remove(row.symbol);
                continue;
            }
            BigDecimal before = balances.get(row.symbol);
            boolean buys = row.reportedAction.buys();
            BigDecimal after = before == null ? null : before.add(buys ? row.quantity : row.quantity.negate());
            Rules.Action action = null;
            if (proof != null && (before == null || proof.before.compareTo(before) == 0)) {
                action = proof.action;
                after = proof.after;
            } else if (before != null && proof == null) {
                boolean opening = before.signum() == 0 || (before.signum() > 0) == buys;
                if (opening && (row.realizedPnl == null || row.realizedPnl.signum() == 0)) action = open(buys);
                if (!opening && row.quantity.compareTo(before.abs()) <= 0) action = close(!buys);
            }
            if (action == null) {
                // An order crossing zero cannot be represented as a single opening or closing fill.
                result.set(i, row.withTradeAction(row.reportedAction));
                balances.remove(row.symbol);
            } else {
                result.set(i, row.withTradeAction(action));
                balances.put(row.symbol, after);
            }
        }
        return result;
    }

    private static Map<String, VerifiedFill> verifiedFills(List<Rules.Signal> rows,
            List<ClosedTradeReview.Position> positions) {
        Map<String, List<Rules.Signal>> bySymbol = new HashMap<>();
        for (Rules.Signal row : rows) bySymbol.computeIfAbsent(row.symbol, key -> new ArrayList<>()).add(row);
        Map<String, VerifiedFill> proven = new HashMap<>();
        for (ClosedTradeReview.Position position : positions) {
            String symbol = position.leg.substring(0, position.leg.indexOf('|'));
            List<Rules.Signal> trades = bySymbol.get(symbol);
            if (trades == null || trades.get(0).time > position.closedAt) continue;
            if (positions.stream().anyMatch(other -> !position.equals(other) && other.leg.startsWith(symbol + "|")
                    && other.openedAt <= position.closedAt && other.closedAt >= position.openedAt)) continue;
            proven.putAll(verifyCycle(trades, position));
        }
        return proven;
    }

    private static Map<String, VerifiedFill> verifyCycle(List<Rules.Signal> rows, ClosedTradeReview.Position position) {
        boolean isLong = position.leg.endsWith("|LONG");
        BigDecimal size = Rules.ZERO, peak = Rules.ZERO, opened = Rules.ZERO, closed = Rules.ZERO;
        Map<String, VerifiedFill> fills = new HashMap<>();
        boolean ended = false;
        for (Rules.Signal row : rows) {
            if (row.time < position.openedAt || row.time > position.closedAt) continue;
            if (ended || !row.reportedAction.sideOnly()) return Collections.emptyMap();
            boolean adds = row.reportedAction.buys() == isLong;
            BigDecimal before = size;
            if (adds) {
                if (row.realizedPnl != null && row.realizedPnl.signum() != 0) return Collections.emptyMap();
                opened = opened.add(row.quantity);
                size = size.add(row.quantity);
                peak = peak.max(size);
            } else {
                closed = closed.add(row.quantity);
                size = size.subtract(row.quantity);
                if (size.signum() < 0 || (size.signum() == 0 && row.time != position.closedAt)) {
                    return Collections.emptyMap();
                }
                ended = size.signum() == 0;
            }
            fills.put(row.id, new VerifiedFill(adds ? open(isLong) : close(isLong),
                    isLong ? before : before.negate(), isLong ? size : size.negate()));
        }
        return ended && peak.compareTo(position.peak) == 0 && opened.compareTo(position.volume) == 0
                && closed.compareTo(position.volume) == 0 ? fills : Collections.emptyMap();
    }

    private static Rules.Action open(boolean isLong) {
        return isLong ? Rules.Action.OPEN_LONG : Rules.Action.OPEN_SHORT;
    }

    private static Rules.Action close(boolean isLong) {
        return isLong ? Rules.Action.CLOSE_LONG : Rules.Action.CLOSE_SHORT;
    }

    private static final class VerifiedFill {
        final Rules.Action action;
        final BigDecimal before, after;

        VerifiedFill(Rules.Action action, BigDecimal before, BigDecimal after) {
            this.action = action;
            this.before = before;
            this.after = after;
        }
    }
}
