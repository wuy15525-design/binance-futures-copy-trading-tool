package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核对同秒成交样本。官网卡片中可能有同价、同量的真实多笔成交，分页拼接也可能重复同一笔。
 * 重复指纹用于标记整秒待核对。复核时结合成交额、盈亏与相对顺序，保留官网确认的笔数。
 */
final class TradeReview {
    private TradeReview() {}

    static Map<Long, List<Rules.Signal>> repeatedSeconds(List<Rules.Signal> rows) {
        Map<Long, List<Rules.Signal>> seconds = new LinkedHashMap<>();
        Set<String> identities = new HashSet<>();
        Set<Long> repeated = new HashSet<>();
        for (Rules.Signal row : rows) {
            seconds.computeIfAbsent(row.time, ignored -> new ArrayList<>()).add(row);
            if (!identities.add(row.fingerprint)) repeated.add(row.time);
        }
        seconds.keySet().retainAll(repeated);
        return seconds;
    }

    static String key(Rules.Signal row) {
        return row.fingerprint + "|" + amount(row.turnover) + "|" + amount(row.realizedPnl);
    }

    private static String amount(BigDecimal amount) { return amount == null ? "" : Rules.show(amount); }

    private static List<String> keys(List<Rules.Signal> rows) {
        List<String> result = new ArrayList<>(rows.size());
        for (Rules.Signal row : rows) result.add(key(row));
        return result;
    }

    static boolean same(List<Rules.Signal> a, List<Rules.Signal> b) { return keys(a).equals(keys(b)); }

    static boolean addsIdenticalTrade(List<Rules.Signal> before, List<Rules.Signal> after, long stamp) {
        Map<String, Integer> known = new LinkedHashMap<>();
        for (Rules.Signal row : before) if (row.time == stamp) known.merge(key(row), 1, Integer::sum);
        if (known.isEmpty()) return false;
        for (Rules.Signal row : after) {
            if (row.time != stamp) continue;
            Integer left = known.computeIfPresent(key(row), (ignored, n) -> n - 1);
            if (left != null && left < 0) return true;
        }
        return false;
    }

    static boolean compatible(List<Rules.Signal> raw, List<Rules.Signal> checked) {
        List<String> first = keys(raw), second = keys(checked);
        if (first.isEmpty() || !new HashSet<>(first).equals(new HashSet<>(second))) return false;
        if (first.size() > second.size()) return subsequence(second, first);
        return subsequence(first, second);
    }

    static boolean hasAdditionalDetails(List<Rules.Signal> earlier, List<Rules.Signal> later) {
        List<String> first = new ArrayList<>(new LinkedHashSet<>(keys(earlier)));
        List<String> second = new ArrayList<>(new LinkedHashSet<>(keys(later)));
        return !first.isEmpty() && second.size() > first.size() && subsequence(first, second);
    }

    private static boolean subsequence(List<String> shorter, List<String> longer) {
        int index = 0;
        for (String key : longer) if (index < shorter.size() && key.equals(shorter.get(index))) index++;
        return index == shorter.size();
    }

    static List<Rules.Signal> at(List<Rules.Signal> rows, long stamp) {
        List<Rules.Signal> group = new ArrayList<>();
        for (Rules.Signal row : rows) if (row.time == stamp) group.add(row);
        return group;
    }

    static List<Rules.Signal> replace(List<Rules.Signal> rows, Map<Long, List<Rules.Signal>> reviews,
            BigDecimal margin) {
        if (reviews.isEmpty()) return rows;
        List<Rules.Signal> verified = new ArrayList<>();
        long last = 0;
        for (Rules.Signal row : rows) {
            List<Rules.Signal> checked = reviews.get(row.time);
            if (checked == null) verified.add(row);
            else if (last != row.time) {
                for (Rules.Signal result : checked) verified.add(result.withLeaderMargin(margin));
            }
            last = row.time;
        }
        return Collections.unmodifiableList(verified);
    }
}
