package com.localcopy.terminal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Ui {
    static final int BACKGROUND = 0xffeef3fa;
    static final int INK = 0xff17263c;
    static final int MUTED = 0xff53647d;
    static final int ACCENT = 0xff2452c8;
    static final int EDGE = 0xffdbe4f1;
    static final int SURFACE = 0xfff5f8fd;
    static final int POSITIVE = 0xff087457;
    static final int NEGATIVE = 0xffb6384b;
    static final int WARNING = 0xff865908;
    private static final int HERO_TEXT = 0xfff5f8ff;
    private static final int HERO_MUTED = 0xffbfceea;
    private static final Pattern DIRECTION = Pattern.compile("[开平加减补][多空]|(?<= )[多空](?= )");
    private static final Pattern PNL = Pattern.compile("(?:已实现盈亏|浮盈亏) ([+-]?[0-9]+(?:\\.[0-9]+)?)(?: USDT| USDC)");
    private static final Pattern STATUS = Pattern.compile("超限等待|待核对|查询失败|请求失败|暂停开仓");

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context c) {
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    static LinearLayout contentColumn(Context context) {
        LinearLayout column = new LinearLayout(context) {
            @Override protected void onMeasure(int width, int height) {
                int limit = dp(context, 660);
                int available = MeasureSpec.getMode(width) == MeasureSpec.UNSPECIFIED
                        ? limit : Math.min(limit, MeasureSpec.getSize(width));
                super.onMeasure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), height);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    static void applyInsets(Window window, View root) {
        window.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    static TextView text(Context c, String value, float size, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(c, 4), 1f);
        return view;
    }

    static TextView heading(Context c, String value, float size) {
        TextView view = text(c, value, size, INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static GradientDrawable fill(Context c, int color, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, radius));
        if (stroke != 0) bg.setStroke(dp(c, 1), stroke);
        return bg;
    }

    static LinearLayout card(Context c, LinearLayout parent) {
        LinearLayout out = column(c);
        int pad = dp(c, 17);
        out.setPadding(pad, pad, pad, pad);
        out.setBackground(fill(c, Color.WHITE, 18, EDGE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(c, 12), 0, 0);
        parent.addView(out, params);
        return out;
    }

    static Button button(Context c, String label, boolean primary, View.OnClickListener click) {
        return action(c, label, primary ? ACCENT : 0xffe8effd, primary ? Color.WHITE : ACCENT, click);
    }

    static Button stopButton(Context c, String label, View.OnClickListener click) {
        return action(c, label, 0xfffbeaf0, NEGATIVE, click);
    }

    static Button donationButton(Context c, View.OnClickListener click) {
        Button button = action(c, "捐赠", 0xffe4edfb, ACCENT, click);
        android.graphics.drawable.Drawable heart = c.getDrawable(R.drawable.ic_support).mutate();
        heart.setBounds(0, 0, dp(c, 16), dp(c, 16));
        button.setCompoundDrawablesRelative(heart, null, null, null);
        button.setCompoundDrawablePadding(dp(c, 6));
        button.setPadding(dp(c, 13), dp(c, 6), dp(c, 13), dp(c, 6));
        return button;
    }

    static Button nav(Context c, String label, int icon, boolean selected, View.OnClickListener click) {
        int tint = selected ? ACCENT : MUTED;
        Button button = action(c, label, selected ? 0xffe8effd : Color.TRANSPARENT, tint, click);
        android.graphics.drawable.Drawable glyph = c.getDrawable(icon).mutate();
        glyph.setTint(tint);
        glyph.setBounds(0, 0, dp(c, 23), dp(c, 23));
        button.setCompoundDrawables(null, glyph, null, null);
        button.setCompoundDrawablePadding(dp(c, 3));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(c, 60));
        button.setSelected(selected);
        return button;
    }

    private static Button action(Context c, String label, int background, int ink, View.OnClickListener click) {
        Button button = new Button(c);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, ink}));
        button.setMinimumHeight(dp(c, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        button.setStateListAnimator(null);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, fill(c, SURFACE, 12, 0));
        states.addState(new int[]{}, fill(c, background, 12, 0));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x292452c8), states, fill(c, Color.WHITE, 12, 0)));
        button.setOnClickListener(click);
        return button;
    }

    static void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), 7);
        parent.addView(button, params);
    }

    static void setDetail(TextView view, String value) {
        if (value.contentEquals(view.getText())) return;
        SpannableString styled = new SpannableString(value);
        int end = value.indexOf('\n') < 0 ? value.length() : value.indexOf('\n');
        if (end > 0) {
            styled.setSpan(new ForegroundColorSpan(INK), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new RelativeSizeSpan(1.17f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher directions = DIRECTION.matcher(value.substring(0, end));
        if (directions.find()) {
            int tone = directions.group().endsWith("多") ? POSITIVE : NEGATIVE;
            styled.setSpan(new ForegroundColorSpan(tone), directions.start(), directions.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher profit = PNL.matcher(value);
        while (profit.find()) {
            int sign = new java.math.BigDecimal(profit.group(1)).signum();
            int tone = sign > 0 ? POSITIVE : sign < 0 ? NEGATIVE : INK;
            highlight(styled, tone, profit.start(1), profit.end(1));
        }
        Matcher warning = STATUS.matcher(value);
        while (warning.find()) highlight(styled, WARNING, warning.start(), warning.end());
        view.setText(styled);
    }

    private static void highlight(SpannableString value, int tone, int from, int to) {
        value.setSpan(new ForegroundColorSpan(tone), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.setSpan(new StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static final class Overview {
        private final TextView strategy, pnl, time, wallet, note, leader;
        private final Button visibility;

        Overview(Context c, LinearLayout parent, View.OnClickListener toggle) {
            LinearLayout panel = column(c);
            int pad = dp(c, 18);
            panel.setPadding(pad, pad, pad, pad);
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff132445, 0xff214580});
            background.setCornerRadius(dp(c, 22));
            panel.setBackground(background);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.topMargin = dp(c, 16);
            parent.addView(panel, layout);
            TextView heading = text(c, "跟单概览", 15, HERO_TEXT);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout title = new LinearLayout(c);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
            visibility = action(c, "显示金额", 0xff334e7e, HERO_TEXT, toggle);
            title.addView(visibility);
            panel.addView(title);
            strategy = text(c, "", 12, HERO_MUTED);
            panel.addView(strategy);
            TextView profitLabel = text(c, "当前总盈亏", 13, HERO_MUTED);
            profitLabel.setPadding(0, dp(c, 16), 0, 0);
            panel.addView(profitLabel);
            pnl = metric(c, panel, 30);
            note = text(c, "", 12, HERO_MUTED);
            panel.addView(note);

            View line = new View(c);
            line.setBackgroundColor(0x3fbfceea);
            LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(c, 1));
            divider.topMargin = dp(c, 14);
            divider.bottomMargin = dp(c, 13);
            panel.addView(line, divider);
            LinearLayout columns = new LinearLayout(c);
            panel.addView(columns);
            time = columnMetric(c, columns, "当前跟单时长");
            wallet = columnMetric(c, columns, "USDT 钱包余额");
            leader = text(c, "", 13, HERO_MUTED);
            leader.setPadding(0, dp(c, 11), 0, 0);
            panel.addView(leader);
        }

        private static TextView metric(Context c, LinearLayout parent, int size) {
            TextView value = text(c, "", size, HERO_TEXT);
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            parent.addView(value);
            return value;
        }

        private static TextView columnMetric(Context c, LinearLayout row, String label) {
            LinearLayout item = column(c);
            LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1f);
            width.rightMargin = dp(c, 5);
            row.addView(item, width);
            item.addView(text(c, label, 12, HERO_MUTED));
            return metric(c, item, 20);
        }

        void show(String tracking, String total, int sign, boolean hasTotal, String elapsed,
                String balance, String footer, String margin, boolean reveal) {
            setText(strategy, tracking);
            setText(visibility, reveal ? "隐藏金额" : "显示金额");
            setText(pnl, reveal ? total : "****");
            pnl.setTextSize(hasTotal || !reveal ? 30 : 22);
            pnl.setTextColor(!reveal ? HERO_TEXT : sign > 0 ? 0xff9af2d4 : sign < 0 ? 0xffffbcc7 : HERO_TEXT);
            setText(time, elapsed);
            setText(wallet, reveal ? balance : "****");
            setText(note, footer);
            note.setVisibility(footer.isEmpty() ? View.GONE : View.VISIBLE);
            setText(leader, margin);
            leader.setVisibility(margin.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    static final class LimitedScroll extends ScrollView {
        private final int maxHeight;

        LimitedScroll(Context c, int heightDp) {
            super(c);
            int room = c.getResources().getConfiguration().screenHeightDp;
            maxHeight = dp(c, room <= 0 ? heightDp : Math.min(heightDp, Math.max(116, room * 55 / 100)));
            setNestedScrollingEnabled(true);
            setVerticalScrollBarEnabled(true);
            setScrollbarFadingEnabled(false);
        }

        @Override protected void onMeasure(int width, int height) {
            int limit = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED
                    ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(height));
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    static final class Metric {
        final String label, value;
        final int color;

        Metric(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    static final class PositionCard {
        final String content, note;
        final List<Metric> metrics;

        PositionCard(String content, String note, List<Metric> metrics) {
            this.content = content;
            this.note = note;
            this.metrics = metrics;
        }

        String plainText() {
            StringBuilder text = new StringBuilder(content);
            for (Metric metric : metrics) text.append('\n').append(metric.label).append(' ').append(metric.value);
            if (!note.isEmpty()) text.append('\n').append(note);
            return text.toString();
        }
    }

    private static final class PositionRow extends LinearLayout {
        private final TextView detail, note;
        private final LinearLayout metrics;

        PositionRow(Context c) {
            super(c);
            setOrientation(VERTICAL);
            int padding = dp(c, 11);
            setPadding(padding, padding, padding, padding);
            setBackground(fill(c, SURFACE, 13, EDGE));
            detail = text(c, "", 13, MUTED);
            addView(detail);
            metrics = new LinearLayout(c);
            int metricPadding = dp(c, 8);
            metrics.setPadding(metricPadding, metricPadding, metricPadding, metricPadding);
            metrics.setBackground(fill(c, 0xffe8effb, 9, 0));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.topMargin = dp(c, 8);
            addView(metrics, layout);
            note = text(c, "", 11, MUTED);
            note.setPadding(0, dp(c, 4), 0, 0);
            addView(note);
        }

        void show(PositionCard card) {
            setDetail(detail, card.content);
            Context c = getContext();
            while (metrics.getChildCount() > card.metrics.size()) metrics.removeViewAt(metrics.getChildCount() - 1);
            while (metrics.getChildCount() < card.metrics.size()) {
                LinearLayout group = column(c);
                group.setPadding(0, 0, dp(c, 5), 0);
                group.addView(text(c, "", 11, MUTED));
                TextView value = text(c, "", 14, INK);
                value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                group.addView(value);
                metrics.addView(group, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            for (int i = 0; i < card.metrics.size(); i++) {
                Metric metric = card.metrics.get(i);
                LinearLayout group = (LinearLayout) metrics.getChildAt(i);
                setText((TextView) group.getChildAt(0), metric.label);
                TextView value = (TextView) group.getChildAt(1);
                value.setTextColor(metric.color);
                setText(value, metric.value);
            }
            setText(note, card.note);
            note.setVisibility(card.note.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    static final class Pager {
        final LinearLayout rows;
        final int size;
        private final LimitedScroll scroll;
        private final Button previous, next;
        private final TextView indicator;
        private long page, pages = 1;
        private String scope;

        Pager(LinearLayout parent, int size, Runnable onPage) {
            Context c = parent.getContext();
            this.size = size;
            scroll = new LimitedScroll(c, 240);
            parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            rows = column(c);
            rows.setPadding(0, dp(c, 6), dp(c, 9), dp(c, 3));
            scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));

            LinearLayout footer = new LinearLayout(c);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            previous = button(c, "上一页", false, v -> move(-1, onPage));
            next = button(c, "下一页", false, v -> move(1, onPage));
            indicator = text(c, "", 13, MUTED);
            indicator.setGravity(Gravity.CENTER);
            footer.addView(previous, new LinearLayout.LayoutParams(0, -2, 1f));
            footer.addView(indicator, new LinearLayout.LayoutParams(0, -2, 1.05f));
            footer.addView(next, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams footerLayout = new LinearLayout.LayoutParams(-1, -2);
            footerLayout.topMargin = dp(c, 5);
            parent.addView(footer, footerLayout);
            prepare("", 0);
        }

        void prepare(String key, long count) {
            long before = page;
            boolean changed = !key.equals(scope);
            if (changed) {
                scope = key;
                page = 0;
            }
            pages = count == 0 ? 1 : (count - 1) / size + 1;
            page = Math.min(page, pages - 1);
            if (changed || before != page) scroll.scrollTo(0, 0);
            setText(indicator, "第 " + (page + 1) + " / " + pages + " 页");
            previous.setEnabled(page > 0);
            next.setEnabled(page + 1 < pages);
        }

        long offset() { return page * size; }

        void renderText(String key, List<String> items, String empty) {
            prepare(key, items.size());
            int first = (int) offset();
            renderPage(items.subList(first, Math.min(first + size, items.size())), empty);
        }

        void renderPage(List<String> items, String empty) {
            Context c = rows.getContext();
            if (rows.getChildCount() > 0 && !(rows.getChildAt(0) instanceof TextView)) rows.removeAllViews();
            int count = Math.max(1, items.size());
            while (rows.getChildCount() > count) rows.removeViewAt(rows.getChildCount() - 1);
            while (rows.getChildCount() < count) {
                TextView line = text(c, "", 13, MUTED);
                int padding = dp(c, 10);
                line.setPadding(padding, padding, padding, padding);
                line.setBackground(fill(c, SURFACE, 12, EDGE));
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
                layout.bottomMargin = dp(c, 7);
                rows.addView(line, layout);
            }
            for (int i = 0; i < count; i++) {
                TextView line = (TextView) rows.getChildAt(i);
                if (items.isEmpty()) setText(line, empty);
                else setDetail(line, items.get(i));
            }
        }

        void renderPositions(String key, List<PositionCard> cards, String empty) {
            prepare(key, cards.size());
            if (cards.isEmpty()) {
                renderPage(java.util.Collections.emptyList(), empty);
                return;
            }
            if (rows.getChildCount() > 0 && !(rows.getChildAt(0) instanceof PositionRow)) rows.removeAllViews();
            int first = (int) offset();
            int count = Math.min(size, cards.size() - first);
            Context c = rows.getContext();
            while (rows.getChildCount() > count) rows.removeViewAt(rows.getChildCount() - 1);
            while (rows.getChildCount() < count) {
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
                layout.bottomMargin = dp(c, 7);
                rows.addView(new PositionRow(c), layout);
            }
            for (int i = 0; i < count; i++) ((PositionRow) rows.getChildAt(i)).show(cards.get(first + i));
        }

        private void move(int delta, Runnable onPage) {
            long requested = page + delta;
            if (requested < 0 || requested >= pages) return;
            page = requested;
            scroll.scrollTo(0, 0);
            onPage.run();
        }
    }

    static void setText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
