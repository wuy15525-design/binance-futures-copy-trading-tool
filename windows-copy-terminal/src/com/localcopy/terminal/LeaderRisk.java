package com.localcopy.terminal;

import static com.localcopy.terminal.Rules.MC;
import static com.localcopy.terminal.Rules.ZERO;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class LeaderRisk {
    static final BigDecimal REFERENCE_RATE = new BigDecimal("0.005");
    static final BigDecimal REFERENCE_LEVERAGE = BigDecimal.valueOf(20);

    enum Assumption { MARGIN, MARK, TIER, EXTENDED_TIER, MISSING_PRICE }

    static final class Tier {
        final BigDecimal cap, rate, deduction;

        Tier(BigDecimal cap, BigDecimal rate, BigDecimal deduction) {
            if (cap.signum() <= 0 || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0 || deduction.signum() < 0) {
                throw new IllegalArgumentException("维持保证金档位无效");
            }
            this.cap = cap;
            this.rate = rate;
            this.deduction = deduction;
        }
    }

    static final class Boundary {
        final BigDecimal below, above;

        Boundary(BigDecimal below, BigDecimal above) {
            this.below = below;
            this.above = above;
        }
    }

    static final class Result {
        final Map<String, Boundary> prices;
        final Set<Assumption> assumptions;
        final boolean atMaintenance;

        private Result(Map<String, Boundary> prices, Set<Assumption> assumptions, boolean atMaintenance) {
            this.prices = Collections.unmodifiableMap(prices);
            this.assumptions = Collections.unmodifiableSet(assumptions);
            this.atMaintenance = atMaintenance;
        }
    }

    private static final class Contract {
        BigDecimal longs = ZERO, shorts = ZERO;
        BigDecimal longEntry, shortEntry, mark;
        List<Tier> tiers;

        BigDecimal entryReference() {
            BigDecimal knownLongs = longEntry == null ? ZERO : longs;
            BigDecimal knownShorts = shortEntry == null ? ZERO : shorts;
            BigDecimal quantity = knownLongs.add(knownShorts);
            if (quantity.signum() == 0) return null;
            BigDecimal cost = amount(knownLongs, longEntry).add(amount(knownShorts, shortEntry));
            return cost.divide(quantity, MC);
        }

        BigDecimal simulatedEquity() {
            BigDecimal longCost = amount(longs, longEntry == null ? mark : longEntry);
            BigDecimal shortCost = amount(shorts, shortEntry == null ? mark : shortEntry);
            BigDecimal collateral = longCost.add(shortCost).divide(REFERENCE_LEVERAGE, MC);
            return collateral.add(mark.multiply(longs.subtract(shorts))).subtract(longCost).add(shortCost);
        }

        BigDecimal maintenance(BigDecimal price) {
            return LeaderRisk.maintenance(longs.multiply(price), tiers)
                    .add(LeaderRisk.maintenance(shorts.multiply(price), tiers));
        }

        private static BigDecimal amount(BigDecimal size, BigDecimal price) {
            return price == null ? ZERO : size.multiply(price);
        }
    }

    static Result estimate(Map<String, LeaderHistory.Holding> holdings, BigDecimal balance,
            Map<String, BigDecimal> marks, Map<String, List<Tier>> brackets) {
        Map<String, Contract> contracts = new TreeMap<>();
        for (Map.Entry<String, LeaderHistory.Holding> entry : holdings.entrySet()) {
            LeaderHistory.Holding holding = entry.getValue();
            BigDecimal size = holding.displayQuantity();
            if (size.signum() <= 0) continue;
            String leg = entry.getKey();
            String symbol = leg.substring(0, leg.indexOf('|'));
            Contract contract = contracts.computeIfAbsent(symbol, key -> new Contract());
            BigDecimal entryPrice = positive(holding.entryPrice);
            if (leg.endsWith("|LONG")) {
                contract.longs = size;
                contract.longEntry = entryPrice;
            } else {
                contract.shorts = size;
                contract.shortEntry = entryPrice;
            }
        }

        Set<Assumption> assumptions = EnumSet.noneOf(Assumption.class);
        BigDecimal totalMaintenance = ZERO;
        BigDecimal equity = balance == null ? ZERO : balance;
        for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
            String symbol = entry.getKey();
            Contract contract = entry.getValue();
            contract.mark = positive(marks.get(symbol));
            if (contract.mark == null) {
                contract.mark = contract.entryReference();
                assumptions.add(contract.mark == null ? Assumption.MISSING_PRICE : Assumption.MARK);
            }
            if (contract.mark == null) continue;
            contract.tiers = brackets.getOrDefault(symbol, Collections.emptyList());
            if (contract.tiers.isEmpty()) assumptions.add(Assumption.TIER);
            totalMaintenance = totalMaintenance.add(contract.maintenance(contract.mark));
            if (balance == null) equity = equity.add(contract.simulatedEquity());
        }

        Map<String, Boundary> prices = new LinkedHashMap<>();
        for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
            Contract contract = entry.getValue();
            if (contract.mark == null) continue;
            BigDecimal otherMaintenance = totalMaintenance.subtract(contract.maintenance(contract.mark));
            // Margin balance is equity at the reference prices. Keep the other contracts fixed.
            BigDecimal net = contract.longs.subtract(contract.shorts);
            BigDecimal intercept = equity.subtract(otherMaintenance).subtract(contract.mark.multiply(net));
            Boundary boundary = boundaries(contract, intercept, net);
            prices.put(entry.getKey(), boundary);
            if (extendsTier(contract, boundary)) assumptions.add(Assumption.EXTENDED_TIER);
        }
        if (balance == null && !prices.isEmpty()) assumptions.add(Assumption.MARGIN);
        return new Result(prices, assumptions, !prices.isEmpty() && equity.compareTo(totalMaintenance) <= 0);
    }

    private static Boundary boundaries(Contract contract, BigDecimal intercept, BigDecimal net) {
        TreeSet<BigDecimal> breaks = new TreeSet<>();
        addBreaks(breaks, contract.tiers, contract.longs);
        addBreaks(breaks, contract.tiers, contract.shorts);

        BigDecimal below = null, above = null;
        BigDecimal left = ZERO, surplus = intercept;
        // Between the bracket edges and the zero floors of maintenance, the surplus is linear.
        for (BigDecimal right : breaks) {
            BigDecimal next = intercept.add(right.multiply(net)).subtract(contract.maintenance(right));
            BigDecimal change = next.subtract(surplus);
            if (crosses(surplus, next, change)) {
                BigDecimal distance = right.subtract(left).multiply(surplus).divide(change, MC);
                BigDecimal price = positive(left.subtract(distance));
                if (change.signum() > 0) below = price;
                else above = price;
            }
            left = right;
            surplus = next;
        }

        BigDecimal lastRate = contract.tiers.isEmpty() ? REFERENCE_RATE
                : contract.tiers.get(contract.tiers.size() - 1).rate;
        BigDecimal slope = net.subtract(contract.longs.add(contract.shorts).multiply(lastRate));
        if (slope.signum() != 0 && slope.signum() * surplus.signum() <= 0) {
            BigDecimal price = positive(left.subtract(surplus.divide(slope, MC)));
            if (slope.signum() > 0) below = price;
            else above = price;
        }
        return new Boundary(below, above);
    }

    private static void addBreaks(Set<BigDecimal> breaks, List<Tier> tiers, BigDecimal size) {
        if (size.signum() == 0) return;
        BigDecimal floor = ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            boolean last = i + 1 == tiers.size();
            if (!last) breaks.add(tier.cap.divide(size, MC));
            if (tier.rate.signum() > 0 && tier.deduction.signum() > 0) {
                BigDecimal zeroNotional = tier.deduction.divide(tier.rate, MC);
                if (zeroNotional.compareTo(floor) > 0 && (last || zeroNotional.compareTo(tier.cap) < 0)) {
                    breaks.add(zeroNotional.divide(size, MC));
                }
            }
            floor = tier.cap;
        }
    }

    private static boolean crosses(BigDecimal left, BigDecimal right, BigDecimal change) {
        int direction = change.signum();
        return direction != 0 && left.signum() * direction <= 0 && right.signum() * direction >= 0;
    }

    private static boolean extendsTier(Contract contract, Boundary boundary) {
        if (contract.tiers.isEmpty()) return false;
        BigDecimal maxPrice = contract.mark;
        if (boundary.below != null) maxPrice = maxPrice.max(boundary.below);
        if (boundary.above != null) maxPrice = maxPrice.max(boundary.above);
        BigDecimal cap = contract.tiers.get(contract.tiers.size() - 1).cap;
        return maxPrice.multiply(contract.longs.max(contract.shorts)).compareTo(cap) > 0;
    }

    private static BigDecimal maintenance(BigDecimal value, List<Tier> tiers) {
        if (tiers.isEmpty()) return value.multiply(REFERENCE_RATE);
        for (Tier tier : tiers) {
            if (value.compareTo(tier.cap) <= 0) return value.multiply(tier.rate).subtract(tier.deduction).max(ZERO);
        }
        Tier last = tiers.get(tiers.size() - 1);
        return value.multiply(last.rate).subtract(last.deduction).max(ZERO);
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }
}
