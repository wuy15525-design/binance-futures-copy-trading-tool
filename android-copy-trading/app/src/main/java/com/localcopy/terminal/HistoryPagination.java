package com.localcopy.terminal;

/**
 * 把连续 DOM 观测转换为等待、点击、结束或本轮未完成。翻页请求的完成时间由官网决定，
 * 所以用新增旧记录确认翻页，用安静期和加载状态判定末尾，并为行数、耗时和重试设上限。
 */
final class HistoryPagination {
    enum Step { WAIT, CLICK, END, INCOMPLETE }

    static final int MAX_ROWS = 4000;
    private static final long PAGE_TIMEOUT = 7000;
    private static final long END_QUIET = 1200;
    private static final long MIN_CLICK_GAP = 1200;
    private static final long READ_SETTLE = 600;
    private static final long CONTROL_TIMEOUT = 18000;
    private static final long INITIAL_CONTROL_TIMEOUT = 30000;
    private static final long BASELINE_BUDGET = 90000;
    private static final long RECOVERY_BUDGET = 50000;
    private static final long RETRY_DELAY = 1500;
    private static final long INITIAL_BUSY_RETRY = 4000;

    private final boolean baseline, acceptVisibleEnd;
    private long began = -1, clickedAt = -1, nextClick, endSince = -1, unavailableSince = -1;
    private int attempts, pages, clicks;

    HistoryPagination(boolean baseline) { this(baseline, false); }

    HistoryPagination(boolean baseline, boolean acceptVisibleEnd) {
        this.baseline = baseline;
        this.acceptVisibleEnd = acceptVisibleEnd;
    }

    HistoryPagination resume() {
        HistoryPagination next = new HistoryPagination(baseline, acceptVisibleEnd);
        next.pages = pages;
        next.clicks = clicks;
        next.nextClick = nextClick;
        // 保留页数和退避时间。卡页后的这一轮只剩一次点击机会，仍需等待前一次点击稳定。
        next.attempts = attempts == 0 ? 0 : 3;
        if (acceptVisibleEnd) next.clickedAt = clickedAt;
        return next;
    }

    void manualResume(long now) {
        // 用户返回应用前可能刚点过「更多」，给这次手动点击留出等待新列表的时间。
        attempts = Math.max(1, attempts);
        if (acceptVisibleEnd) clickedAt = now;
        nextClick = Math.max(nextClick, now + MIN_CLICK_GAP);
    }

    Step observe(int rows, int olderAppended, boolean overlaps, boolean more, boolean ready,
            boolean busy, boolean loading, long now) {
        if (began < 0) {
            began = now;
            if (pages == 0) pages = 1;
        }
        if (olderAppended > 0 && attempts > 0) advanced(now);
        if (overlaps && !baseline && !loading && !busy) return Step.END;
        // 首次建档可接受稳定的可见末尾。补读与独立复核还受各自的重合或目标证据约束。
        boolean clickSettled = clickedAt < 0 || now - clickedAt >= PAGE_TIMEOUT;
        if (!more && !busy && !loading && (attempts == 0 || (acceptVisibleEnd && clickSettled && now >= nextClick))) {
            if (endSince < 0) endSince = now;
            if (now - endSince < END_QUIET) return Step.WAIT;
            return overlaps ? Step.END : Step.INCOMPLETE;
        }
        endSince = -1;
        if (rows >= MAX_ROWS || duration(now) >= (baseline ? BASELINE_BUDGET : RECOVERY_BUDGET)) {
            return Step.INCOMPLETE;
        }
        int maxAttempts = baseline ? 4 : 3;
        if (busy && attempts > 0) {
            // 官网繁忙提示可能持续多帧，每次观测都顺延下次点击，给最后一次繁忙留足退避时间。
            nextClick = Math.max(nextClick, now + retryDelay(acceptVisibleEnd ? INITIAL_BUSY_RETRY : RETRY_DELAY));
            clickedAt = -1;
        } else if (clickedAt >= 0 && now - clickedAt >= PAGE_TIMEOUT) {
            clickedAt = -1;
            if (attempts >= maxAttempts) return Step.INCOMPLETE;
            nextClick = Math.max(nextClick, now + retryDelay(RETRY_DELAY));
        }
        if (more && ready && !busy && !loading) {
            unavailableSince = -1;
            if (clickedAt >= 0 || now < nextClick) return Step.WAIT;
            return attempts >= maxAttempts ? Step.INCOMPLETE : Step.CLICK;
        }
        if (unavailableSince < 0) unavailableSince = now;
        long controlTimeout = acceptVisibleEnd ? INITIAL_CONTROL_TIMEOUT : CONTROL_TIMEOUT;
        return now - unavailableSince >= controlTimeout ? Step.INCOMPLETE : Step.WAIT;
    }

    private long retryDelay(long firstDelay) { return firstDelay << Math.min(attempts - 1, 2); }

    private void advanced(long now) {
        pages++;
        attempts = 0;
        clickedAt = endSince = unavailableSince = -1;
        nextClick = Math.max(nextClick, now + READ_SETTLE);
    }

    void clicked(long now) {
        attempts++;
        clicks++;
        clickedAt = now;
        nextClick = now + MIN_CLICK_GAP;
        unavailableSince = -1;
    }

    long duration(long now) { return began < 0 ? 0 : now - began; }

    int pages() { return pages; }

    boolean inFlight() { return attempts > 0; }

    String report(long now, int rows, boolean ended) {
        String status = (baseline ? "官网基线 " : "断档补页 ") + (rows == 0 ? 0 : pages) + " 页";
        return status + (ended ? " · " + (baseline ? "已读到末尾" : "已核对") : " · 分页未完成")
                + " · " + rows + " 笔 · " + clicks
                + " 次点击 · " + Rules.show(java.math.BigDecimal.valueOf(duration(now), 3)) + " 秒";
    }

}
