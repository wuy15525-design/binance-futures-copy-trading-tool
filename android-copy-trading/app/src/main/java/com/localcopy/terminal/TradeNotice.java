package com.localcopy.terminal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 统一成交提醒、仓位数值和盈亏的展示格式。显示舍入与委托数量计算分开。 */
final class TradeNotice {
    private static final MathContext PRICE_DISPLAY = new MathContext(14, RoundingMode.HALF_UP);
    private static final MathContext SMALL_POSITION_PRICE = new MathContext(4, RoundingMode.HALF_UP);
    private static final MathContext SMALL_POSITION_AMOUNT = new MathContext(2, RoundingMode.HALF_UP);
    private static final MathContext POSITION_QUANTITY = new MathContext(8, RoundingMode.HALF_UP);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter FULL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);

    static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String fullTime(long millis) {
        return FULL_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String fill(String action, String symbol, long when, BigDecimal amount,
            BigDecimal position, String unit) {
        return fillText(action, symbol, time(when), amount, position, unit);
    }

    private static String fillText(String action, String symbol, String when, BigDecimal amount,
            BigDecimal position, String unit) {
        return action + " · " + symbol + " · " + when + " · 成交 " + Rules.show(amount) + " "
                + unit + (position == null ? " · 仓位待核对" : " · 持仓 " + Rules.show(position) + " " + unit);
    }

    static String completed(String label, String symbol, Rules.Action action, FuturesApi.Fills fills,
            BigDecimal position, BigDecimal reference, String referenceLabel) {
        BigDecimal average = fills.average();
        String message = fillText(label, symbol, fullTime(fills.latest), fills.quantity, position, Rules.base(symbol))
                + "\n成交均价 " + price(average) + " USDT";
        if (!action.opens) message += " · " + pnl(fills.realizedPnl);
        if (reference == null) return message;
        BigDecimal difference = action.opens == action.isLong ? average.subtract(reference) : reference.subtract(average);
        BigDecimal percent = difference.divide(reference, Rules.MC).movePointRight(2).setScale(4, RoundingMode.HALF_UP);
        String direction = percent.signum() > 0 ? "（不利）" : percent.signum() < 0 ? "（有利）" : "";
        return message + "\n" + referenceLabel + " " + price(reference) + " USDT · 点差 "
                + signed(percent) + "%" + direction;
    }

    static String price(BigDecimal value) { return Rules.show(value.round(PRICE_DISPLAY)); }

    static String positionPrice(BigDecimal value) {
        return Rules.show(value.abs().compareTo(BigDecimal.ONE) < 0
                ? value.round(SMALL_POSITION_PRICE) : value.setScale(2, RoundingMode.HALF_UP));
    }

    static String positionMoney(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return Rules.show(rounded.signum() == 0 ? value.round(SMALL_POSITION_AMOUNT) : rounded);
    }

    static String positionQuantity(BigDecimal value) { return Rules.show(value.round(POSITION_QUANTITY)); }

    static String exposureMultiple(BigDecimal value) {
        BigDecimal displayed = value.setScale(4, RoundingMode.HALF_UP);
        return value.signum() > 0 && displayed.signum() == 0 ? "<0.0001" : Rules.show(displayed);
    }

    static String pnl(BigDecimal value) {
        return "已实现盈亏 " + (value == null ? "未提供" : signed(value) + " USDT");
    }

    private static String signed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + Rules.show(value);
    }

    static String submitted(String action, String symbol, long at, BigDecimal amount, String unit) {
        return action + " · " + symbol + " · " + time(at) + " · 请求 "
                + Rules.show(amount) + " " + unit + " · 确认中";
    }
}
