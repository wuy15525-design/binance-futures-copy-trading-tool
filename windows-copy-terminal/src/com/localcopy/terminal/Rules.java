package com.localcopy.terminal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Rules {
    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final BigDecimal ONE = BigDecimal.ONE;
    static final MathContext MC = MathContext.DECIMAL128;
    static final String SYMBOL_CHARACTER = "[A-Z0-9\\p{sc=Han}]";
    static final String SYMBOL_BASE = SYMBOL_CHARACTER + "{1,30}";
    private static final Pattern USDT_SYMBOL = Pattern.compile(SYMBOL_BASE + "USDT");

    enum Action {
        OPEN_LONG("开多", true, true), OPEN_SHORT("开空", false, true),
        CLOSE_LONG("平多", true, false), CLOSE_SHORT("平空", false, false),
        BUY("买入（开平待核对）", true, false), SELL("卖出（开平待核对）", false, false);

        final String label;
        final boolean isLong;
        final boolean opens;

        Action(String label, boolean isLong, boolean opens) {
            this.label = label;
            this.isLong = isLong;
            this.opens = opens;
        }

        boolean sideOnly() { return this == BUY || this == SELL; }

        boolean buys() { return sideOnly() ? this == BUY : opens == isLong; }

        String exchangeSide() { return buys() ? "BUY" : "SELL"; }

        String positionSide() {
            return sideOnly() ? "BOTH" : isLong ? "LONG" : "SHORT";
        }

        static Action fromLabel(String text) {
            for (Action value : values()) if (value.label.equals(text)) return value;
            throw new IllegalArgumentException("无法识别开平方向");
        }
    }

    static final class ContractRule {
        final boolean ratio;
        final BigDecimal amount, slippagePercent;
        final int leverage;

        ContractRule(boolean ratio, BigDecimal amount, int leverage, BigDecimal slippagePercent) {
            if (amount.signum() <= 0 || (ratio && amount.compareTo(new BigDecimal("100")) > 0)) {
                throw new IllegalArgumentException("定额需大于 0，定比范围为 0 至 100%（不含 0）");
            }
            if (leverage < 1 || leverage > 125) throw new IllegalArgumentException("杠杆范围为 1–125 倍");
            if (slippagePercent.signum() < 0 || slippagePercent.compareTo(new BigDecimal("100")) >= 0) {
                throw new IllegalArgumentException("不利价差范围为 0 至小于 100%");
            }
            this.ratio = ratio;
            this.amount = amount;
            this.leverage = leverage;
            this.slippagePercent = slippagePercent;
        }

        BigDecimal orderMargin(BigDecimal walletBalance) {
            return ratio ? walletBalance.max(ZERO).multiply(amount.movePointLeft(2), MC) : amount;
        }

        boolean sameSizing(ContractRule other) {
            return ratio == other.ratio && leverage == other.leverage && amount.compareTo(other.amount) == 0;
        }
    }

    static final class Config {
        final String portfolio;
        final Set<String> symbols;
        final Map<String, ContractRule> rules;

        Config(String portfolio, Set<String> symbols, boolean ratio, BigDecimal amount,
                int leverage, BigDecimal slippagePercent) {
            this(portfolio, uniform(symbols, new ContractRule(ratio, amount, leverage, slippagePercent)));
        }

        Config(String portfolio, Map<String, ContractRule> rules) {
            if (!portfolio.matches("[0-9]{8,25}")) throw new IllegalArgumentException("组合 ID 无效");
            if (rules.isEmpty() || rules.size() > 30) throw new IllegalArgumentException("请添加 1 到 30 个合约");
            for (Map.Entry<String, ContractRule> entry : rules.entrySet()) {
                requireSymbol(entry.getKey());
                if (entry.getValue() == null) throw new IllegalArgumentException("缺少合约规则");
            }
            this.portfolio = portfolio;
            this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
            symbols = this.rules.keySet();
        }

        private static Map<String, ContractRule> uniform(Set<String> symbols, ContractRule common) {
            Map<String, ContractRule> rules = new LinkedHashMap<>();
            for (String symbol : symbols) rules.put(symbol, common);
            return rules;
        }

        ContractRule rule(String symbol) {
            ContractRule rule = rules.get(symbol);
            if (rule == null) throw new IllegalArgumentException(symbol + " 不在合约白名单");
            return rule;
        }

        boolean sameSlippage(Config other) {
            if (!symbols.equals(other.symbols)) return false;
            for (String symbol : symbols) {
                if (rule(symbol).slippagePercent.compareTo(other.rule(symbol).slippagePercent) != 0) return false;
            }
            return true;
        }

        Set<String> changedLeverages(Config other) {
            Set<String> changed = new LinkedHashSet<>();
            for (String symbol : other.symbols) {
                ContractRule before = rules.get(symbol);
                if (before == null || before.leverage != other.rule(symbol).leverage) changed.add(symbol);
            }
            return changed;
        }
    }

    enum Strategy {
        POINTS("按点位跟随"),
        EXPOSURE("复制仓位比例");

        final String label;

        Strategy(String label) { this.label = label; }

        static Strategy read(String value) {
            return value == null || value.isEmpty() ? POINTS : valueOf(value);
        }
    }

    static BigDecimal copyFactor(String value) {
        if (value != null && value.trim().matches("(?:[0-9]{1,12}(?:\\.[0-9]{1,12})?|\\.[0-9]{1,12})")) {
            BigDecimal factor = new BigDecimal(value.trim()).stripTrailingZeros();
            if (factor.signum() > 0) return factor;
        }
        throw new IllegalArgumentException("复制系数须为大于 0 的数，例如 0.5、1 或 2");
    }

    static BigDecimal adjustmentSlippage(String value) {
        if (value != null && value.trim().matches("(?:[0-9]{1,2}(?:\\.[0-9]{1,10})?|\\.[0-9]{1,10})")) {
            return new BigDecimal(value.trim()).stripTrailingZeros();
        }
        throw new IllegalArgumentException("调仓最大不利价差须在 0% 至小于 100% 之间");
    }

    private static BigDecimal adversePriceBoundary(Action action, BigDecimal reference, BigDecimal percent) {
        BigDecimal band = percent.movePointLeft(2);
        return reference.multiply(action.buys() ? ONE.add(band) : ONE.subtract(band));
    }

    static boolean withinAdversePrice(Action action, BigDecimal reference, BigDecimal percent, BigDecimal price) {
        return !exceedsOpeningLimit(action, price, adversePriceBoundary(action, reference, percent));
    }

    static final class Signal {
        final String symbol;
        final Action action, reportedAction;
        final long time;
        final BigDecimal price, quantity, realizedPnl, turnover, leaderMargin;
        final String id, fingerprint;
        final int occurrence;

        Signal(String symbol, Action action, long time, BigDecimal price, BigDecimal quantity) {
            this(symbol, action, time, price, quantity, null);
        }

        Signal(String symbol, Action action, long time, BigDecimal price, BigDecimal quantity, BigDecimal pnl) {
            this(symbol, action, time, price, quantity, pnl, null, null);
        }

        Signal(String symbol, Action action, long time, BigDecimal price, BigDecimal quantity, BigDecimal pnl,
                BigDecimal turnover, BigDecimal leaderMargin) {
            this(symbol, action, time, price, quantity, pnl, turnover, leaderMargin, action, 0);
        }

        Signal(String symbol, Action action, long time, BigDecimal price, BigDecimal quantity, BigDecimal pnl,
                BigDecimal turnover, BigDecimal leaderMargin, Action reportedAction, int occurrence) {
            requireSymbol(symbol);
            if (time <= 0 || price.signum() <= 0 || quantity.signum() <= 0) {
                throw new IllegalArgumentException("成交时间、价格或数量无效");
            }
            if (occurrence < 0) throw new IllegalArgumentException("成交序号无效");
            if (action != reportedAction && (!reportedAction.sideOnly() || action.buys() != reportedAction.buys())) {
                throw new IllegalArgumentException("官网买卖方向与开平仓方向不一致");
            }
            this.symbol = symbol;
            this.action = action;
            this.reportedAction = reportedAction;
            this.time = time;
            this.price = price;
            this.quantity = quantity;
            this.realizedPnl = action.opens && !reportedAction.sideOnly() ? null : pnl;
            this.turnover = positive(turnover);
            this.leaderMargin = positive(leaderMargin);
            this.occurrence = occurrence;
            fingerprint = slot() + "|" + show(price) + "|" + show(quantity);
            id = occurrence == 0 ? fingerprint : fingerprint + "|#" + occurrence;
        }

        Signal withLeaderMargin(BigDecimal balance) {
            if (leaderMargin != null || positive(balance) == null) return this;
            return new Signal(symbol, action, time, price, quantity, realizedPnl, turnover, balance, reportedAction, occurrence);
        }

        Signal enrich(Signal visible) {
            if (!id.equals(visible.id)) return this;
            BigDecimal value = turnover == null ? visible.turnover : turnover;
            BigDecimal balance = leaderMargin == null ? visible.leaderMargin : leaderMargin;
            if (value == turnover && balance == leaderMargin) return this;
            return new Signal(symbol, action, time, price, quantity, realizedPnl, value, balance, reportedAction, occurrence);
        }

        Signal withOccurrence(int index) {
            return index == occurrence ? this : new Signal(symbol, action, time, price, quantity,
                    realizedPnl, turnover, leaderMargin, reportedAction, index);
        }

        Signal withTradeAction(Action resolved) {
            return action == resolved ? this : new Signal(symbol, resolved, time, price, quantity,
                    realizedPnl, turnover, leaderMargin, reportedAction, occurrence);
        }

        Signal withArchivedId(String event) {
            String prefix = fingerprint + "|#";
            return event != null && event.startsWith(prefix)
                    ? withOccurrence(Integer.parseInt(event.substring(prefix.length()))) : this;
        }

        static String fingerprintOf(String event) {
            int occurrenceSuffix = event.lastIndexOf("|#");
            return occurrenceSuffix < 0 ? event : event.substring(0, occurrenceSuffix);
        }

        static Action reportedActionOf(String symbol, Action action, String event) {
            if (event == null) return action;
            if (event.startsWith(symbol + "|BOTH|BUY|")) return Action.BUY;
            if (event.startsWith(symbol + "|BOTH|SELL|")) return Action.SELL;
            return action;
        }

        BigDecimal exposureRatio() {
            return turnover == null || leaderMargin == null ? null : turnover.divide(leaderMargin, MC);
        }

        BigDecimal displayTurnover() { return turnover == null ? price.multiply(quantity) : turnover; }

        private static BigDecimal positive(BigDecimal value) {
            return value != null && value.signum() > 0 ? value : null;
        }

        String leg() {
            return symbol + "|" + action.positionSide();
        }

        String slot() {
            return symbol + "|" + reportedAction.positionSide() + "|" + reportedAction.name() + "|" + time;
        }
    }

    static final class Quote {
        final BigDecimal bid, ask, bidQuantity, askQuantity;

        Quote(BigDecimal bid, BigDecimal ask, BigDecimal bidQuantity, BigDecimal askQuantity) {
            if (bid.signum() <= 0 || ask.compareTo(bid) < 0
                    || bidQuantity.signum() <= 0 || askQuantity.signum() <= 0) {
                throw new IllegalArgumentException("盘口无效");
            }
            this.bid = bid;
            this.ask = ask;
            this.bidQuantity = bidQuantity;
            this.askQuantity = askQuantity;
        }

        BigDecimal execution(Action action) {
            return action.buys() ? ask : bid;
        }

        BigDecimal available(Action action) {
            return action.buys() ? askQuantity : bidQuantity;
        }
    }

    static BigDecimal entryLimit(Action action, BigDecimal leaderPrice, BigDecimal percent,
            BigDecimal tick) {
        if (!action.opens) throw new IllegalArgumentException("平仓无开仓限价");
        return roundStep(adversePriceBoundary(action, leaderPrice, percent), tick,
                action.isLong ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    static boolean exceedsOpeningLimit(Action action, BigDecimal price, BigDecimal limit) {
        return limit.signum() <= 0 || (action.buys() ? price.compareTo(limit) > 0 : price.compareTo(limit) < 0);
    }

    static boolean acceptable(Action action, BigDecimal limit, Quote quote) {
        return !exceedsOpeningLimit(action, quote.execution(action), limit);
    }

    static boolean favorableOpeningPrice(Action action, BigDecimal price, BigDecimal leaderAverage) {
        int comparison = price.compareTo(leaderAverage);
        return action.buys() ? comparison < 0 : comparison > 0;
    }

    static String openingAverageIssue(Action action, FuturesApi.Position position, BigDecimal quantity,
            BigDecimal worstPrice, BigDecimal leaderAverage, BigDecimal slippage) {
        BigDecimal averageLimit = adversePriceBoundary(action, leaderAverage, slippage);
        BigDecimal held = position.size(action);
        if (held.signum() == 0) {
            return exceedsOpeningLimit(action, worstPrice, averageLimit)
                    ? "带单仓位均价的不利价差超限，继续等待" : "";
        }
        BigDecimal entry = position.entryPrice(action);
        if (entry == null || entry.signum() <= 0) return "等待核对本账户开仓均价";
        // Compare costs without rounding the projected average at the price boundary.
        BigDecimal projectedCost = held.multiply(entry).add(quantity.multiply(worstPrice));
        int comparison = projectedCost.compareTo(held.add(quantity).multiply(averageLimit));
        if (action.buys() ? comparison <= 0 : comparison >= 0) return "";
        return "本笔成交后的持仓均价仍超限 · 本账户均价 " + TradeNotice.price(entry)
                + " · 允许均价" + (action.buys() ? "不高于 " : "不低于 ") + TradeNotice.price(averageLimit);
    }

    static boolean openingNeedsFullFill(Action action, FuturesApi.Position position,
            BigDecimal leaderAverage, BigDecimal slippage) {
        // If the existing average is outside the band, a partial fill may still leave it outside.
        return position.size(action).signum() > 0 && exceedsOpeningLimit(action, position.entryPrice(action),
                adversePriceBoundary(action, leaderAverage, slippage));
    }

    static BigDecimal iocPrice(Action action, Quote quote, BigDecimal tick) {
        return roundStep(quote.execution(action), tick,
                action.buys() ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    static BigDecimal quantity(BigDecimal margin, int leverage, BigDecimal price, BigDecimal step) {
        return floor(margin.multiply(BigDecimal.valueOf(leverage), MC).divide(price, MC), step);
    }

    static BigDecimal floor(BigDecimal amount, BigDecimal step) {
        return roundStep(amount, step, RoundingMode.FLOOR);
    }

    private static BigDecimal roundStep(BigDecimal amount, BigDecimal step, RoundingMode rounding) {
        if (step.signum() <= 0) throw new IllegalArgumentException("合约精度无效");
        return amount.divide(step, 0, rounding).multiply(step);
    }

    static Set<String> symbols(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : value.toUpperCase(Locale.ROOT).split("[\\s,，、;；]+")) {
            if (!part.isEmpty()) {
                requireSymbol(part);
                tokens.add(part);
            }
        }
        return tokens;
    }

    static String show(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String base(String symbol) {
        return symbol.substring(0, symbol.length() - 4);
    }

    static void requireSymbol(String value) {
        if (!isUsdtSymbol(value)) {
            throw new IllegalArgumentException("请输入 U 本位 USDT 合约代码");
        }
    }

    static boolean isUsdtSymbol(String value) { return USDT_SYMBOL.matcher(value).matches(); }
}
