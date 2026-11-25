package com.localcopy.terminal;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * 把网页可见的成交卡片转成 Signal。每页必须能解析出一致的方向、数量和时间，
 * 字段只渲染了一部分时抛出异常，让 WebView 保留上一份有效列表并等待下一次观测。
 */
final class WebRecords {
    private static final String NUMBER = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?m)^\\h*(?:(\\d{4})-)?(\\d{2})-(\\d{2})\\s*[,，]?\\s*(\\d{2}:\\d{2}:\\d{2})(?!\\d)");
    private static final Pattern ROW = Pattern.compile(
            "(开多|开空|平多|平空|做多|做空)\\s*以(?:均价|价格)为\\s*" + NUMBER
            + "\\s*USDT\\s*(买入|卖出)\\s*(开多|开空|平多|平空|做多|做空)?\\s*(" + Rules.SYMBOL_BASE + ")USDT"
            + "\\s*永续合约\\s*[,，]?\\s*成交数量为\\s*" + NUMBER
            + "\\s*\\5(?=总价值|总成交额|成交总额|成交金额|成交额|已实现|(?!" + Rules.SYMBOL_CHARACTER + "))");
    private static final Pattern PNL = Pattern.compile(
            "已实现(?:盈亏|损益)\\s*(?:为|[:：])?\\s*([+\\-−－＋]?)\\s*" + NUMBER + "\\s*USDT");
    private static final Pattern TURNOVER = Pattern.compile(
            "(?:总价值|总成交额|成交总额|成交金额|成交额)\\s*(?:为|[:：])?\\s*" + NUMBER + "\\s*USDT");

    // 有日期控件时以官网范围推断省略的年份。控件迟到时用有限的近期范围解析，并拒绝有歧义的日期。
    static List<Rules.Signal> parsePage(String text, JSONArray dates, ZoneId zone, LocalDate today)
            throws JSONException {
        if (dates.length() >= 2) {
            return parse(text, LocalDate.parse(dates.getString(0)), LocalDate.parse(dates.getString(1)), zone);
        }
        return parse(text, today.minusDays(32), today.plusDays(1), zone);
    }

    static LocalDate recoveryStart(LocalDate first, LocalDate last, long anchorTime, ZoneId zone) {
        if (last.isBefore(first)) return null;
        LocalDate start = last.minusDays(2);
        if (anchorTime > 0) {
            LocalDate anchor = Instant.ofEpochMilli(anchorTime).atZone(zone).toLocalDate();
            if (anchor.isBefore(first) || anchor.isAfter(last)) return null;
            if (anchor.isBefore(start)) start = anchor;
        }
        return start.isBefore(first) ? first : start;
    }

    static LocalDate reviewStart(LocalDate day, LocalDate displayedStart) {
        LocalDate preferred = day.minusDays(2);
        return displayedStart != null && displayedStart.isAfter(preferred) && displayedStart.isBefore(day)
                ? displayedStart : preferred;
    }

    static List<Rules.Signal> parse(String visibleText, LocalDate firstDate, LocalDate lastDate,
            ZoneId browserZone) {
        if (visibleText.length() > 1_600_000 || lastDate.isBefore(firstDate)
                || lastDate.isAfter(firstDate.plusDays(370))) {
            throw new IllegalArgumentException("页面的记录范围不适合导入");
        }
        String input = visibleText.replace('\u00a0', ' ').replace('\u202f', ' ');
        Matcher times = TIME_PATTERN.matcher(input);
        List<Rules.Signal> result = new ArrayList<>();
        int previousEnd = -1;
        long previousTime = 0;
        while (times.find()) {
            if (previousEnd >= 0) readRow(input, previousEnd, times.start(), previousTime, result);
            int month = Integer.parseInt(times.group(2));
            int day = Integer.parseInt(times.group(3));
            LocalDate date = resolveDate(times.group(1), month, day, firstDate, lastDate);
            LocalTime time = LocalTime.parse(times.group(4));
            ZonedDateTime local = date.atTime(time).atZone(browserZone);
            if (browserZone.getRules().getValidOffsets(date.atTime(time)).size() != 1) {
                throw new IllegalArgumentException("网页时间存在时区歧义");
            }
            previousTime = local.toInstant().toEpochMilli();
            previousEnd = times.end();
        }
        if (previousEnd >= 0) readRow(input, previousEnd, input.length(), previousTime, result);
        if (result.isEmpty()) throw new IllegalArgumentException("尚未读到记录，请打开「最新操作记录」并等页面加载");
        boolean ascending = false, descending = false;
        for (int i = 1; i < result.size(); i++) {
            int direction = Long.compare(result.get(i).time, result.get(i - 1).time);
            ascending |= direction > 0;
            descending |= direction < 0;
        }
        // The public timeline places the newest fill first within a second. A late page may
        // interleave different seconds. Keep the displayed order within each second when sorting.
        if (!ascending || descending) Collections.reverse(result);
        result.sort(Comparator.comparingLong(row -> row.time));
        Map<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < result.size(); i++) {
            Rules.Signal signal = result.get(i);
            int index = occurrences.getOrDefault(signal.fingerprint, 0);
            result.set(i, signal.withOccurrence(index));
            occurrences.put(signal.fingerprint, index + 1);
        }
        return result;
    }

    private static LocalDate resolveDate(String yearText, int month, int day,
            LocalDate first, LocalDate last) {
        LocalDate found = null;
        for (int year = first.getYear(); year <= last.getYear(); year++) {
            if (yearText != null && year != Integer.parseInt(yearText)) continue;
            LocalDate candidate;
            try {
                candidate = LocalDate.of(year, month, day);
            } catch (DateTimeException invalidDate) {
                continue;
            }
            if (candidate.isBefore(first) || candidate.isAfter(last)) continue;
            if (found != null) throw new IllegalArgumentException("同一月日出现在多个年份");
            found = candidate;
        }
        if (found == null) throw new IllegalArgumentException("成交时间与网页选择的日期范围不符");
        return found;
    }

    private static void readRow(String input, int from, int to, long time, List<Rules.Signal> out) {
        String card = input.substring(from, to).trim();
        Matcher row = ROW.matcher(card);
        if (!row.lookingAt()) {
            throw new IllegalArgumentException("官网第 " + (out.size() + 1) + " 笔成交字段不完整（"
                    + TradeNotice.fullTime(time) + "），本页待核对");
        }
        String heading = row.group(1);
        Rules.Action action = "做多".equals(heading) ? Rules.Action.BUY
                : "做空".equals(heading) ? Rules.Action.SELL : Rules.Action.fromLabel(heading);
        String symbol = row.group(5) + "USDT";
        String detail = row.group(4);
        if ("做多".equals(detail) || "做空".equals(detail)) {
            if (action.buys() != "做多".equals(detail)) throw new IllegalArgumentException("官网成交方向不一致");
        } else if (action.sideOnly() && detail != null) {
            Rules.Action explicit = Rules.Action.fromLabel(detail);
            if (explicit.buys() != action.buys()) throw new IllegalArgumentException("官网成交方向不一致");
            action = explicit;
        } else if (detail != null && !heading.equals(detail)) {
            throw new IllegalArgumentException("官网成交开平方向不一致");
        }
        if (action.buys() != row.group(3).equals("买入")) {
            throw new IllegalArgumentException("成交方向不一致");
        }
        BigDecimal pnl = null;
        Matcher value = TURNOVER.matcher(card);
        BigDecimal turnover = value.find() ? positiveAmount(value.group(1)) : null;
        if (!action.opens) {
            Matcher profit = PNL.matcher(card);
            if (profit.find()) {
                String sign = profit.group(1);
                try { pnl = number(profit.group(2)); }
                catch (IllegalArgumentException missing) { pnl = null; }
                if (pnl != null && ("-".equals(sign) || "−".equals(sign) || "－".equals(sign))) pnl = pnl.negate();
            }
        }
        Rules.Signal signal = new Rules.Signal(symbol, action, time, number(row.group(2)), number(row.group(6)),
                pnl, turnover, null);
        out.add(signal);
    }

    static BigDecimal positiveAmount(String text) {
        if (text.length() > 70) return null;
        try {
            BigDecimal result = number(text);
            return result.signum() > 0 ? result : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static BigDecimal number(String text) {
        if (!text.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("网页小数格式无法识别");
        }
        return new BigDecimal(text.replace(",", ""));
    }
}
