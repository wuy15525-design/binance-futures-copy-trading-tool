package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.MC;
import static com.localcopy.terminal.Rules.ONE;
import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PaperEngine {
    static final BigDecimal STEP = new BigDecimal("0.00001");
    private static final BigDecimal FEE = new BigDecimal("0.0005");
    private static final BigDecimal FEE_BUFFER = new BigDecimal("0.001");

    static final class Fill {
        final String action, symbol;
        final long time;
        final BigDecimal quantity, position;
        Fill(String action, String symbol, long time, BigDecimal quantity, BigDecimal position) {
            this.action = action;
            this.symbol = symbol;
            this.time = time;
            this.quantity = quantity;
            this.position = position;
        }

        String notice() {
            return TradeNotice.fill(action, symbol, time, quantity, position, Rules.base(symbol));
        }
    }

    private static final class Pending {
        final Rules.Signal signal;
        BigDecimal remainingFraction = ONE;
        BigDecimal target;
        BigDecimal unspent;

        Pending(Rules.Signal signal) { this.signal = signal; }

        void shrink(BigDecimal fraction) {
            remainingFraction = remainingFraction.multiply(fraction, MC);
            if (target != null) {
                target = target.multiply(fraction, MC);
                unspent = unspent.multiply(fraction, MC);
            }
        }
    }

    private static final class Position {
        final String symbol;
        final boolean longSide;
        BigDecimal leader = ZERO, held = ZERO, retained = ZERO, basis = ZERO, margin = ZERO;
        boolean missingHistory;
        final List<Pending> pending = new ArrayList<>();

        Position(String symbol, boolean longSide) {
            this.symbol = symbol;
            this.longSide = longSide;
        }

        void apply(Rules.Signal signal) {
            if (missingHistory) return;
            if (signal.action.opens) {
                leader = leader.add(signal.quantity);
                pending.add(new Pending(signal));
                return;
            }
            if (leader.compareTo(signal.quantity) < 0) {
                missingHistory = true;
                pending.clear();
                return;
            }
            BigDecimal after = leader.subtract(signal.quantity);
            if (after.signum() == 0) {
                pending.clear();
                retained = ZERO;
            } else {
                BigDecimal fraction = after.divide(leader, MC);
                retained = retained.multiply(fraction, MC);
                for (Pending item : pending) item.shrink(fraction);
            }
            leader = after;
        }
    }

    private final Rules.Config config;
    private final Map<String, Position> sides = new LinkedHashMap<>();
    private final Set<String> processed = new LinkedHashSet<>();
    private final Map<String, String> slots = new LinkedHashMap<>();
    private long lastSourceTime;
    private BigDecimal wallet;

    PaperEngine(Rules.Config config, BigDecimal initialWallet) {
        if (initialWallet.signum() <= 0) throw new IllegalArgumentException("模拟余额需大于 0");
        this.config = config;
        wallet = initialWallet;
    }

    void apply(List<Rules.Signal> signals) {
        Set<String> batchIds = new LinkedHashSet<>();
        Map<String, String> batchSlots = new LinkedHashMap<>();
        long watermark = lastSourceTime;
        for (Rules.Signal signal : signals) {
            if (processed.contains(signal.id) || !batchIds.add(signal.id)) continue;
            if (signal.time < watermark) throw new IllegalArgumentException("成交乱序，回放暂停");
            String key = signal.leg() + "|" + signal.time;
            if (slots.containsKey(key) || batchSlots.put(key, signal.id) != null) {
                throw new IllegalArgumentException("同一方向有无法排序的成交");
            }
            watermark = signal.time;
        }
        for (Rules.Signal signal : signals) {
            if (!processed.add(signal.id)) continue;
            slots.put(signal.leg() + "|" + signal.time, signal.id);
            if (config.symbols.contains(signal.symbol)) {
                sides.computeIfAbsent(signal.leg(), key -> new Position(signal.symbol, signal.action.isLong))
                        .apply(signal);
            }
        }
        lastSourceTime = watermark;
    }

    List<Fill> check(Map<String, Rules.Quote> quotes, BigDecimal slippage, boolean allowOpens, long now) {
        List<Fill> fills = new ArrayList<>();
        for (Position leg : sides.values()) {
            Rules.Quote quote = quotes.get(leg.symbol);
            if (quote != null && !leg.missingHistory) reduce(leg, quote, fills, now);
        }
        if (!allowOpens) return fills;
        for (Position leg : sides.values()) {
            Rules.Quote quote = quotes.get(leg.symbol);
            if (quote != null && !leg.missingHistory && closeDue(leg).signum() == 0) {
                enter(leg, quote, slippage, fills, now);
            }
        }
        return fills;
    }

    private void reduce(Position leg, Rules.Quote quote, List<Fill> fills, long now) {
        BigDecimal quantity = closeDue(leg);
        if (quantity.signum() == 0) return;
        Rules.Action action = leg.longSide ? Rules.Action.CLOSE_LONG : Rules.Action.CLOSE_SHORT;
        BigDecimal price = quote.execution(action);
        BigDecimal realised = price.subtract(leg.basis).multiply(quantity, MC);
        if (!leg.longSide) realised = realised.negate();
        wallet = wallet.add(realised).subtract(price.multiply(quantity, MC).multiply(FEE, MC));
        BigDecimal remaining = leg.held.subtract(quantity);
        leg.margin = leg.margin.multiply(remaining.divide(leg.held, MC), MC);
        leg.held = remaining;
        if (remaining.signum() == 0) leg.basis = ZERO;
        String label = remaining.signum() == 0 ? action.label : (leg.longSide ? "减多" : "减空");
        fills.add(new Fill(label + "已成交", leg.symbol, now, quantity, leg.held));
    }

    private void enter(Position leg, Rules.Quote quote, BigDecimal slippage, List<Fill> fills, long now) {
        BigDecimal remainingDepth = leg.longSide ? quote.askQuantity : quote.bidQuantity;
        Rules.ContractRule rule = config.rule(leg.symbol);
        for (Pending item : leg.pending) {
            Rules.Action action = item.signal.action;
            BigDecimal limit = Rules.entryLimit(action, item.signal.price, slippage, STEP);
            if (!Rules.acceptable(action, limit, quote)) continue;
            BigDecimal price = quote.execution(action);
            if (item.target == null && !allocate(item, price, rule)) continue;
            BigDecimal affordable = Rules.quantity(item.unspent, rule.leverage, price, STEP);
            BigDecimal amount = Rules.floor(item.target.min(remainingDepth).min(affordable), STEP);
            if (amount.signum() <= 0) continue;
            BigDecimal value = amount.multiply(price, MC);
            BigDecimal used = value.divide(BigDecimal.valueOf(rule.leverage), MC);
            if (available().compareTo(used.add(value.multiply(FEE_BUFFER, MC))) < 0) continue;
            BigDecimal old = leg.held;
            leg.held = old.add(amount);
            leg.retained = leg.retained.add(amount);
            leg.basis = leg.basis.multiply(old, MC).add(value).divide(leg.held, MC);
            leg.margin = leg.margin.add(used);
            wallet = wallet.subtract(value.multiply(FEE, MC));
            item.target = item.target.subtract(amount);
            item.unspent = item.unspent.subtract(used).max(ZERO);
            remainingDepth = remainingDepth.subtract(amount);
            String label = old.signum() == 0 ? action.label : (leg.longSide ? "加多" : "加空");
            fills.add(new Fill(label + "已成交", leg.symbol, now, amount, leg.held));
        }
        leg.pending.removeIf(item -> item.target != null && (item.target.signum() == 0
                || item.unspent.signum() == 0));
    }

    private boolean allocate(Pending item, BigDecimal price, Rules.ContractRule rule) {
        BigDecimal budget = rule.orderMargin(wallet).multiply(item.remainingFraction, MC);
        BigDecimal fees = budget.multiply(BigDecimal.valueOf(rule.leverage)).multiply(FEE_BUFFER, MC);
        if (budget.signum() <= 0 || available().compareTo(budget.add(fees)) < 0) return false;
        BigDecimal quantity = Rules.quantity(budget, rule.leverage, price, STEP);
        if (quantity.signum() == 0) return false;
        item.target = quantity;
        item.unspent = budget;
        return true;
    }

    private static BigDecimal closeDue(Position leg) {
        if (leg.retained.signum() == 0) return leg.held;
        return Rules.floor(leg.held.subtract(leg.retained).max(ZERO), STEP);
    }

    BigDecimal balance() { return wallet; }

    BigDecimal available() {
        BigDecimal sum = ZERO;
        for (Position leg : sides.values()) sum = sum.add(leg.margin);
        return wallet.subtract(sum).max(ZERO);
    }

    int pendingCount() {
        int count = 0;
        for (Position leg : sides.values()) count += leg.pending.size();
        return count;
    }

    BigDecimal held(String symbol, boolean longSide) {
        Position position = sides.get(symbol + (longSide ? "|LONG" : "|SHORT"));
        return position == null ? ZERO : position.held;
    }

    String summary() {
        StringBuilder lines = new StringBuilder();
        for (Position leg : sides.values()) {
            if (leg.missingHistory) {
                lines.append(leg.symbol).append(leg.longSide ? " 多" : " 空").append("　历史缺口，暂停该方向\n");
            } else if (leg.held.signum() > 0 || !leg.pending.isEmpty()) {
                lines.append(leg.symbol).append(leg.longSide ? " 多　" : " 空　")
                        .append(Rules.show(leg.held)).append(" ").append(Rules.base(leg.symbol));
                if (!leg.pending.isEmpty()) lines.append("　待跟 ").append(leg.pending.size()).append(" 笔");
                lines.append("\n");
            }
        }
        return lines.length() == 0 ? "暂无模拟仓位" : lines.toString().trim();
    }
}
