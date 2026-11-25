package com.localcopy.terminal;

final class TradeCsv {
    private TradeCsv() {}

    // 按 CSV 规则转义引号，并让可能被表格软件当成公式的文本按原样显示，保留有符号数值。
    static String line(String... fields) {
        StringBuilder output = new StringBuilder();
        for (String field : fields) {
            if (output.length() > 0) output.append(',');
            String trimmed = field.trim();
            boolean formula = !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0
                    && !trimmed.matches("[+-]?[0-9]+(?:\\.[0-9]+)?");
            output.append('"');
            if (formula || field.startsWith("\t") || field.startsWith("\r")) output.append('\'');
            output.append(field.replace("\"", "\"\"")).append('"');
        }
        return output.append("\r\n").toString();
    }
}
