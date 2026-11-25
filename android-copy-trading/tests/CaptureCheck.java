package com.localcopy.terminal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CaptureCheck {
    static String compare(List<Rules.Signal> previous, List<Rules.Signal> current) {
        if (previous.isEmpty()) return "首次读取，已保存本机快照";
        Map<String, String> oldSlots = new HashMap<>();
        Map<String, String> newSlots = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (Rules.Signal row : previous) {
            oldSlots.put(row.slot(), row.id);
            known.add(row.id);
        }
        boolean overlap = false;
        int newRows = 0;
        long watermark = previous.get(previous.size() - 1).time;
        for (Rules.Signal row : current) {
            newSlots.put(row.slot(), row.id);
            String oldId = oldSlots.get(row.slot());
            if (oldId != null && !oldId.equals(row.id)) return "网页成交字段变化，需核对";
            if (known.contains(row.id)) overlap = true;
            else if (row.time > watermark) newRows++;
        }
        if (!overlap || !newSlots.containsValue(previous.get(previous.size() - 1).id)) {
            return "与上次最新记录衔接不上，可在网页加载更多";
        }
        long earliest = current.get(0).time;
        long latest = current.get(current.size() - 1).time;
        for (Rules.Signal row : previous) {
            if (row.time >= earliest && row.time <= latest && !newSlots.containsKey(row.slot())) {
                return "重叠区间缺少上次记录，需核对";
            }
        }
        for (Rules.Signal row : current) {
            if (row.time >= previous.get(0).time && row.time <= watermark && !known.contains(row.id)) {
                return "重叠区间出现较早记录，需核对";
            }
        }
        return newRows == 0 ? "可见记录与上次一致" : "比上次多 " + newRows + " 笔较新的记录";
    }
}
