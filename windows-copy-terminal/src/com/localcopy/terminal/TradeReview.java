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

    static void recordPageRepeats(List<Rules.Signal> before, List<Rules.Signal> after, Map<String, Integer> limits) {
        Map<String, Integer> known = counts(before);
        for (Map.Entry<String, Integer> entry : counts(after).entrySet()) {
            Integer count = known.get(entry.getKey());
            if (count != null && entry.getValue() > count) limits.putIfAbsent(entry.getKey(), count);
        }
    }

    static Map<String, Integer> counts(List<Rules.Signal> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Rules.Signal row : rows) counts.merge(key(row), 1, Integer::sum);
        return counts;
    }

    static List<Rules.Signal> limitCopies(List<Rules.Signal> rows, Map<String, Integer> limits) {
        List<Rules.Signal> kept = new ArrayList<>();
        Map<String, Integer> remaining = new LinkedHashMap<>(limits);
        for (Rules.Signal row : rows) {
            Integer left = remaining.computeIfPresent(key(row), (ignored, n) -> n - 1);
            if (left == null || left >= 0) kept.add(row);
        }
        return kept;
    }

    static Set<String> repeatedLegs(List<Rules.Signal> rows) {
        Set<String> legs = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (Rules.Signal row : rows) {
            if (seen.add(row.fingerprint)) continue;
            if (row.reportedAction.sideOnly()) {
                legs.add(row.symbol + "|LONG");
                legs.add(row.symbol + "|SHORT");
            } else legs.add(row.leg());
        }
        return legs;
    }

    static final class BaselineCounts {
        final List<Rules.Signal> rows;
        final Map<Long, List<Rules.Signal>> settled;

        BaselineCounts(List<Rules.Signal> rows, Map<Long, List<Rules.Signal>> settled) {
            this.rows = rows;
            this.settled = settled;
        }
    }

    static BaselineCounts baselineCounts(List<Rules.Signal> raw) {
        return baselineCounts(raw, null);
    }

    static BaselineCounts baselineCounts(List<Rules.Signal> raw, Set<Long> pending) {
        Map<Long, List<Rules.Signal>> repeated = repeatedSeconds(raw);
        if (pending != null) repeated.keySet().retainAll(pending);
        if (repeated.isEmpty()) return new BaselineCounts(raw, Collections.emptyMap());
        Set<String> openings = new HashSet<>();
        List<Rules.Signal> rows = new ArrayList<>();
        for (Rules.Signal row : raw) {
            if (!repeated.containsKey(row.time) || !knownAction(row) || !row.action.opens
                    || openings.add(key(row))) rows.add(row);
        }
        Map<String, Integer> copies = counts(rows);
        Map<String, BigDecimal> minimum = new LinkedHashMap<>();
        Map<String, Integer> firstClose = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Rules.Signal row = rows.get(i);
            if (knownAction(row) && !row.action.opens) firstClose.putIfAbsent(key(row), i);
        }
        BigDecimal[] requiredAfter = new BigDecimal[rows.size()];
        // Reserve one of each distinct later close up to the next opening on this side.
        for (int i = rows.size() - 1; i >= 0; i--) {
            Rules.Signal row = rows.get(i);
            if (!knownAction(row)) {
                minimum.remove(row.symbol + "|LONG");
                minimum.remove(row.symbol + "|SHORT");
            } else if (row.action.opens) {
                minimum.remove(row.leg());
            } else {
                String leg = row.leg();
                BigDecimal reserved = minimum.getOrDefault(leg, Rules.ZERO);
                requiredAfter[i] = reserved;
                if (!repeated.containsKey(row.time) || firstClose.get(key(row)) == i) {
                    minimum.put(leg, reserved.add(row.quantity));
                }
            }
        }
        Map<String, BigDecimal> available = new LinkedHashMap<>();
        Set<String> unpaired = new HashSet<>();
        Set<Long> unresolved = new HashSet<>();
        List<Rules.Signal> kept = new ArrayList<>(rows.size());
        Map<Long, List<Rules.Signal>> settled = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Rules.Signal row = rows.get(i);
            String leg = row.leg();
            if (!knownAction(row)) {
                unpaired.add(row.symbol + "|LONG");
                unpaired.add(row.symbol + "|SHORT");
                unresolved.add(row.time);
            } else if (row.action.opens) {
                available.merge(leg, row.quantity, BigDecimal::add);
            } else {
                String identity = key(row);
                BigDecimal before = available.getOrDefault(leg, Rules.ZERO);
                boolean first = firstClose.get(identity) == i;
                boolean sameClose = repeated.containsKey(row.time) && copies.get(identity) > 1;
                BigDecimal needed = row.quantity.add(requiredAfter[i]);
                if (sameClose && !first && !unpaired.contains(leg) && before.compareTo(needed) < 0) continue;
                if (before.compareTo(row.quantity) < 0 || (sameClose && first && before.compareTo(needed) < 0)) {
                    unpaired.add(leg);
                }
                if (sameClose && unpaired.contains(leg)) unresolved.add(row.time);
                available.put(leg, before.subtract(row.quantity).max(Rules.ZERO));
            }
            kept.add(row);
            if (repeated.containsKey(row.time)) settled.computeIfAbsent(row.time, ignored -> new ArrayList<>()).add(row);
        }
        settled.entrySet().removeIf(entry -> unresolved.contains(entry.getKey())
                || hasDifferentDetails(repeated.get(entry.getKey())));
        return new BaselineCounts(kept, settled);
    }

    static Map<Long, List<Rules.Signal>> archiveCloseCounts(List<Rules.Signal> rows, long since) {
        return closeCounts(rows, since, false, Long.MIN_VALUE);
    }

    static boolean canReviewFromList(List<Rules.Signal> sample) {
        if (!exactCopies(sample)) return false;
        Set<String> seen = new HashSet<>();
        return sample.stream().allMatch(row -> seen.add(key(row)) || knownAction(row));
    }

    static List<Rules.Signal> checkOpeningCopies(List<Rules.Signal> sample) {
        if (!canReviewFromList(sample)) return Collections.emptyList();
        List<Rules.Signal> kept = distinctOpeningCopies(sample);
        return anyDuplicateKey(kept) ? Collections.emptyList() : kept;
    }

    static List<Rules.Signal> checkCloseCopies(List<Rules.Signal> rows, long stamp,
            List<Rules.Signal> sample, boolean ended) {
        // The list must have advanced past the entire second before it can supply evidence.
        if (rows.isEmpty() || rows.get(0).time >= stamp) return Collections.emptyList();
        List<Rules.Signal> visible = at(rows, stamp);
        if (!compatible(sample, visible)) return Collections.emptyList();
        if (sample.size() > visible.size()) rows = replace(rows, Collections.singletonMap(stamp, sample), null);
        long oldest = ended ? Long.MIN_VALUE : rows.get(0).time;
        return closeCounts(distinctOpeningCopies(rows), Long.MIN_VALUE, true, oldest)
                .getOrDefault(stamp, Collections.emptyList());
    }

    private static List<Rules.Signal> distinctOpeningCopies(List<Rules.Signal> rows) {
        Set<String> seen = new HashSet<>();
        List<Rules.Signal> kept = new ArrayList<>(rows.size());
        for (Rules.Signal row : rows) {
            if (!knownAction(row) || !row.action.opens || seen.add(key(row))) kept.add(row);
        }
        return kept;
    }

    private static Map<Long, List<Rules.Signal>> closeCounts(List<Rules.Signal> rows, long since,
            boolean fromList, long oldestOpenMayBeClipped) {
        Map<Long, List<Rules.Signal>> seconds = repeatedSeconds(rows);
        seconds.keySet().removeIf(stamp -> stamp < since);
        Set<String> uncertain = new HashSet<>();
        Map<String, Integer> candidates = new LinkedHashMap<>();
        Map<String, List<Rules.Signal>> histories = new LinkedHashMap<>();
        for (List<Rules.Signal> second : seconds.values()) {
            Set<String> fingerprints = new HashSet<>(), repeats = new HashSet<>();
            for (Rules.Signal row : second) if (!fingerprints.add(row.fingerprint)) repeats.add(row.fingerprint);
            uncertain.addAll(repeats);
            String leg = null;
            boolean eligible = !hasDifferentDetails(second);
            for (Rules.Signal row : second) {
                if (!repeats.contains(row.fingerprint)) continue;
                if (!knownAction(row) || row.action.opens || (leg != null && !leg.equals(row.leg()))) eligible = false;
                leg = row.leg();
            }
            if (eligible && leg != null) {
                for (Rules.Signal row : second) {
                    if (repeats.contains(row.fingerprint)) candidates.merge(row.fingerprint, 1, Integer::sum);
                }
                histories.computeIfAbsent(leg, ignored -> new ArrayList<>());
            }
        }
        for (Rules.Signal row : rows) {
            if (knownAction(row)) {
                List<Rules.Signal> history = histories.get(row.leg());
                if (history != null) history.add(row);
            } else {
                for (String side : new String[]{"|LONG", "|SHORT"}) {
                    List<Rules.Signal> history = histories.get(row.symbol + side);
                    if (history != null) history.add(row);
                }
            }
        }
        Map<Long, List<Rules.Signal>> settled = new LinkedHashMap<>();
        for (List<Rules.Signal> history : histories.values()) {
            BigDecimal size = Rules.ZERO;
            for (int i = 0; i < history.size();) {
                Rules.Signal first = history.get(i);
                if (!knownAction(first)) break;
                if (first.action.opens) {
                    if (uncertain.contains(first.fingerprint) || first.time == oldestOpenMayBeClipped) break;
                    size = size.add(first.quantity);
                    i++;
                    continue;
                }
                BigDecimal volume = Rules.ZERO;
                BigDecimal smallestCopy = null;
                Map<String, Integer> counted = new LinkedHashMap<>();
                Set<Long> verifiedSeconds = new LinkedHashSet<>();
                boolean unresolved = false;
                for (; i < history.size(); i++) {
                    Rules.Signal close = history.get(i);
                    if (!knownAction(close) || close.action.opens) break;
                    if (uncertain.contains(close.fingerprint)) {
                        if (!candidates.containsKey(close.fingerprint)) unresolved = true;
                        if (counted.merge(close.fingerprint, 1, Integer::sum) > 1) {
                            if (smallestCopy == null || close.quantity.compareTo(smallestCopy) < 0) smallestCopy = close.quantity;
                            continue;
                        }
                        verifiedSeconds.add(close.time);
                    }
                    volume = volume.add(close.quantity);
                }
                if (unresolved) break;
                if (!verifiedSeconds.isEmpty()) {
                    // Reserve one of every distinct close in this run. Every extra copy must exceed the remainder.
                    BigDecimal remainder = size.subtract(volume);
                    if (remainder.signum() < 0 || !candidates.entrySet().containsAll(counted.entrySet())) break;
                    if (remainder.signum() > 0 && (!fromList || smallestCopy == null
                            || remainder.compareTo(smallestCopy) >= 0)) break;
                    boolean wholeSeconds = verifiedSeconds.stream().allMatch(stamp -> seconds.get(stamp).stream()
                            .allMatch(row -> !uncertain.contains(row.fingerprint) || counted.containsKey(row.fingerprint)));
                    if (!wholeSeconds) break;
                    for (long stamp : verifiedSeconds) settled.put(stamp, distinctExactCopies(seconds.get(stamp)));
                }
                size = size.subtract(volume);
                if (size.signum() < 0) {
                    if (history.get(i - 1).time >= since) break;
                    // An older confirmed close may predate the retained opening. Replay subsequent cycles as the archive does.
                    size = Rules.ZERO;
                }
            }
        }
        return settled;
    }

    static List<Rules.Signal> distinctExactCopies(List<Rules.Signal> rows) {
        Set<String> seen = new HashSet<>();
        List<Rules.Signal> kept = new ArrayList<>(rows.size());
        for (Rules.Signal row : rows) if (seen.add(key(row))) kept.add(row);
        return kept;
    }

    static boolean exactCopies(List<Rules.Signal> second) {
        return anyDuplicateKey(second) && !hasDifferentDetails(second);
    }

    private static boolean anyDuplicateKey(List<Rules.Signal> rows) {
        Set<String> seen = new HashSet<>();
        for (Rules.Signal row : rows) if (!seen.add(key(row))) return true;
        return false;
    }

    private static boolean hasDifferentDetails(List<Rules.Signal> rows) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Rules.Signal row : rows) {
            String key = key(row), previous = keys.putIfAbsent(row.fingerprint, key);
            if (previous != null && !previous.equals(key)) return true;
        }
        return false;
    }

    static boolean knownAction(Rules.Signal row) {
        return !row.reportedAction.sideOnly();
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
