package com.localcopy.terminal;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 在 WebView 中读取币安组合详情的公开成交、仓位历史与划转记录。
 * 网页只提供当前渲染的内容。脚本报告页面状态和可见数据，由这里判定等待、翻页、续读或失败。
 * 成交从这里交给 AutoFollowService 复核，再由 LeaderArchive 落库并推进档案水位。
 */
final class WebsiteCapture {
    private static final long DEADLINE = 25000;
    private static final long BALANCE_DEADLINE = 40000;
    private static final long BASELINE_DEADLINE = 110000;
    private static final long BASELINE_CONTINUATION_DEADLINE = 65000;
    private static final int BASELINE_CONTINUATIONS = 2;
    private static final long RECOVERY_DEADLINE = 70000;
    private static final long REVIEW_DEADLINE = 50000;
    private static final long POSITION_HISTORY_DEADLINE = 12000;
    private static final long READ_AGAIN = 350;
    private static final long BASELINE_SCRIPT_TIMEOUT = 8000;
    private static final long EMPTY_WAIT = 3500;
    private static final long DATE_APPLY_RETRY = 2200;
    private static final long DATE_APPLY_TIMEOUT = 6500;
    private static final long DATE_CONTROL_TIMEOUT = 8000;
    // 官网历史逐批加载。首次只核对最近 7 天，控制启动跟单前要翻阅的历史量。
    private static final long BASELINE_WINDOW = TimeUnit.DAYS.toMillis(7);
    private static final long BASELINE_EMPTY_WAIT = 5000;
    private static final long REGION_RETRY = 120000;

    private static WeakReference<FrameLayout> viewport = new WeakReference<>(null);

    private static WebsiteCapture mounted;
    private static WebsiteCapture mountedBalance;

    enum Access {
        NONE, REGION, VERIFY, SIGN_IN, DENIED, REDIRECT;

        boolean waitsForRetry() { return this != NONE && this != REGION; }
    }

    static final class Failure extends IOException {
        final long backoffMs;
        final boolean stop;
        final Access access;

        Failure(String detail, long backoffMs, boolean stop) {
            this(detail, backoffMs, stop, Access.NONE);
        }

        Failure(String detail, long backoffMs, boolean stop, Access access) {
            super(detail);
            this.backoffMs = backoffMs;
            this.stop = stop;
            this.access = access;
        }
    }

    static Failure accessFailure(Access access) {
        switch (access) {
            case REGION: return new Failure("币安显示地区限制或要求确认地区，官网读取已暂停",
                    REGION_RETRY, false, access);
            case VERIFY: return new Failure("币安要求完成安全验证，等待手动重试读取", 0, false, access);
            case SIGN_IN: return new Failure("币安要求登录或授权后查看记录，等待手动重试读取", 0, false, access);
            case DENIED: return new Failure("币安拒绝访问此记录，等待手动重试读取", 0, false, access);
            default: return new Failure("币安组合页发生跳转，打开官网查看提示后可重试", 0, false, access);
        }
    }

    static Access redirectAccess(Uri address) {
        String host = address.getHost();
        if (!"https".equals(address.getScheme()) || address.getUserInfo() != null
                || (!"www.binance.com".equals(host) && !"accounts.binance.com".equals(host))) return Access.REDIRECT;
        String path = address.getPath();
        if (path == null) return Access.REDIRECT;
        String route = path.toLowerCase(java.util.Locale.ROOT);
        if (route.matches(".*(?:^|/)(?:restricted-country|restricted-region|region-restrictions?|country-restrictions?|jurisdiction-restrictions?)(?:/.*)?$")) {
            return Access.REGION;
        }
        if (route.matches(".*(?:^|/)(?:login|signin|sign-in)(?:/.*)?$")) return Access.SIGN_IN;
        if (route.matches(".*(?:^|/)(?:captcha|verify-human|human-verification)(?:/.*)?$")) return Access.VERIFY;
        return Access.REDIRECT;
    }

    static Access httpAccess(int status) {
        if (status == 451) return Access.REGION;
        if (status == 401) return Access.SIGN_IN;
        if (status == 403) return Access.DENIED;
        return Access.NONE;
    }

    private static boolean portfolioIndex(Uri address) {
        return "https".equals(address.getScheme()) && "www.binance.com".equals(address.getHost())
                && address.getUserInfo() == null && (address.getPort() == -1 || address.getPort() == 443)
                && address.getPath() != null && address.getPath().matches("(?i)/(?:[a-z]{2}(?:-[a-z]{2,4})?/)?copy-trading/?");
    }

    private static Failure missingPortfolio() {
        return new Failure("币安官网未找到该带单组合，请核对组合 ID 或确认组合仍公开", 0, false);
    }

    private final Context context;
    private final String path, url, script;
    private final boolean detached;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView page;
    private FrameLayout promptViewport;
    private Poll current;
    private BaselineProgress baselineProgress;
    private Read pendingBaseline;
    private String document = "";
    private String manualBaselineDocument = "";
    private long webRetryAfter;
    private boolean closed, regionPrompt;

    private enum DateField { START, END }

    private static final class LostBaselinePage extends IOException {}

    private static final class DateRange {
        final LocalDate start, end;
        final ZoneId zone;

        DateRange(LocalDate start, LocalDate end, ZoneId zone) {
            this.start = start;
            this.end = end;
            this.zone = zone;
        }

        static DateRange displayed(JSONObject data) throws JSONException {
            JSONArray dates = data.optJSONArray("dates");
            if (dates == null || dates.length() < 2) return null;
            LocalDate start = LocalDate.parse(dates.getString(0));
            LocalDate end = LocalDate.parse(dates.getString(1));
            return end.isBefore(start) ? null : new DateRange(start, end, ZoneId.of(data.getString("zone")));
        }

        boolean contains(List<Rules.Signal> rows) {
            return !rows.isEmpty() && !dateOf(rows.get(0).time).isBefore(start)
                    && !dateOf(rows.get(rows.size() - 1).time).isAfter(end);
        }

        private LocalDate dateOf(long stamp) { return Instant.ofEpochMilli(stamp).atZone(zone).toLocalDate(); }

        boolean matches(DateRange other) {
            return other != null && start.equals(other.start) && end.equals(other.end) && zone.equals(other.zone);
        }

        long endExclusive() { return end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); }
    }

    static final class Read {
        final String name;
        final List<Rules.Signal> rows;
        final BigDecimal leaderMargin, profileMargin;
        final boolean historyComplete, recovered;
        final String historyDetail, recoveryDetail;
        final long snapshotAt;

        Read(String name, List<Rules.Signal> rows, BigDecimal leaderMargin, BigDecimal profileMargin,
                boolean historyComplete, String historyDetail, boolean recovered, String recoveryDetail, long snapshotAt) {
            this.name = name;
            this.rows = rows;
            this.leaderMargin = leaderMargin;
            this.profileMargin = profileMargin;
            this.historyComplete = historyComplete;
            this.historyDetail = historyDetail;
            this.recovered = recovered;
            this.recoveryDetail = recoveryDetail;
            this.snapshotAt = snapshotAt;
        }

        Read withRows(List<Rules.Signal> verified) {
            return new Read(name, verified, leaderMargin, profileMargin, historyComplete, historyDetail,
                    recovered, recoveryDetail, snapshotAt);
        }
    }

    /**
     * 一轮网页读取的状态。document 标识实际网页，resumeDocument 标识希望续读的那一页。
     * 翻页和日期选择都依赖当前文档里的控件与列表，页面被回收后需要重新验证重合记录。
     */
    static final class Poll {
        final Rules.Signal anchor;
        final Predicate<List<Rules.Signal>> archiveOverlap;
        final boolean profile, baseline;
        final long reviewStamp;
        HistoryPagination pagination;
        String document = UUID.randomUUID().toString();
        final CompletableFuture<Read> result = new CompletableFuture<>();
        CompletableFuture<LeaderBalances.Read> balances;
        CompletableFuture<List<ClosedTradeReview.Position>> positionHistory;
        String balanceSample = "", previousBalancePage = "";
        String positionSample;
        long balanceCutoff, balanceOldest = Long.MAX_VALUE;
        int balancePage = 1, savedBalancePage;
        JSONArray balanceRows = new JSONArray();
        String balanceZone;
        BigDecimal balanceMargin;
        String previous, phase = "navigation";
        String name = "";
        BigDecimal leaderMargin, profileMargin;
        List<Rules.Signal> lastRows = Collections.emptyList();
        long startedAt, clickedAt, emptySince, calendarSince, snapshotAt, textChangedAt, controlsSince, pageErrorUntil;
        long continuationFrom;
        long baselineCutoff, baselineNewest;
        ZoneId baselineZone;
        String lastText = "", parseIssue = "", reviewFallback = "";
        String priorRangeText = "";
        LocalDate rangeStart, rangeEnd;
        DateRange displayedRange, requestedRange;
        DateField selectingDate;
        int calendarMoves, rangeRetries, emptyScriptReplies;
        String resumeDocument;
        boolean triedRange, overlapping, firstPageDisjoint, rereadEmpty, restartedPagination;
        boolean verifyEmptyAgain;
        boolean cutoffReached, rangePriming, rangeRefreshed;
        boolean endBeforeStart, rangeEndAlreadySelected;
        boolean rangeConfirmed, reopenedDatePage;
        boolean baselineEndObserved, allowEmptyPartition;
        boolean reviewPageOverlap;
        boolean javascriptResponded, baselineScriptRestarted;
        Poll(Rules.Signal anchor, Predicate<List<Rules.Signal>> archiveOverlap, boolean profile, boolean baseline) {
            this(anchor, archiveOverlap, profile, baseline, 0);
        }

        Poll(Rules.Signal anchor, Predicate<List<Rules.Signal>> archiveOverlap, boolean profile, boolean baseline, long reviewStamp) {
            this.anchor = anchor;
            this.archiveOverlap = archiveOverlap;
            this.profile = profile;
            this.baseline = baseline;
            this.reviewStamp = reviewStamp;
            this.pagination = newPagination();
            this.verifyEmptyAgain = baseline;
        }

        boolean reviewing() { return reviewStamp > 0; }
        boolean unfilteredReview() { return !reviewFallback.isEmpty(); }
        boolean continuingBaseline() { return continuationFrom > 0; }
        boolean initialBaseline() { return baseline && anchor == null && !continuingBaseline(); }

        HistoryPagination newPagination() {
            return new HistoryPagination(baseline || reviewing(), initialBaseline());
        }

        Poll resumeBaseline() {
            Poll next = new Poll(anchor, archiveOverlap, false, true);
            next.resumeDocument = document;
            next.pagination = pagination.resume();
            next.lastRows = lastRows;
            next.lastText = lastText;
            next.name = name;
            next.leaderMargin = leaderMargin;
            next.profileMargin = profileMargin;
            next.snapshotAt = snapshotAt;
            next.overlapping = overlapping;
            next.firstPageDisjoint = firstPageDisjoint;
            next.displayedRange = displayedRange;
            next.requestedRange = requestedRange;
            next.continuationFrom = continuationFrom;
            next.baselineCutoff = baselineCutoff;
            next.baselineNewest = baselineNewest;
            next.baselineZone = baselineZone;
            next.rangeRefreshed = rangeRefreshed;
            next.triedRange = triedRange;
            next.rangeStart = rangeStart;
            next.rangeEnd = rangeEnd;
            next.rangeConfirmed = rangeConfirmed;
            next.baselineEndObserved = baselineEndObserved;
            return next;
        }

        long deadline() {
            if (positionHistory != null) return POSITION_HISTORY_DEADLINE;
            if (balances != null) return BALANCE_DEADLINE;
            if (profile) return DEADLINE;
            if (continuingBaseline()) return BASELINE_CONTINUATION_DEADLINE;
            return baseline ? BASELINE_DEADLINE : reviewing() ? REVIEW_DEADLINE : RECOVERY_DEADLINE;
        }
    }

    /**
     * 在同一次跟踪期间暂存已核对的建档候选。续读成功才推进 cursor，保留首轮读取确定的时间窗。
     * 重开网页、日期控件迟到或新旧页无法对齐时，调用方可以继续使用已经接受的候选。
     */
    static final class BaselineProgress {
        Poll cursor;
        final Read first;
        List<Rules.Signal> rows;
        LocalDate verifiedBoundary;
        boolean emptyPartitionSeen;

        BaselineProgress(Poll cursor, Read first) {
            this.cursor = cursor;
            this.first = first;
            rows = first.rows;
        }

        boolean accept(Poll poll, Read part) {
            if (part.rows.isEmpty()) return false;
            if (poll.continuingBaseline()) {
                DateRange range = poll.requestedRange;
                if (range == null || !range.matches(poll.displayedRange)) return false;
                rows = LeaderHistory.joinBaseline(rows, part.rows, range.endExclusive());
            } else {
                rows = LeaderHistory.reconcileBaselineOverlap(rows, part.rows);
            }
            cursor = poll;
            return true;
        }

        boolean confirmsShortWindow(Poll reread, Read part) {
            if (!cursor.baselineEndObserved || !reread.baselineEndObserved || reread.displayedRange == null
                    || !reread.baselineZone.equals(reread.displayedRange.zone)) return false;
            LocalDate start = Instant.ofEpochMilli(reread.baselineCutoff).atZone(reread.baselineZone).toLocalDate();
            return !reread.displayedRange.start.isAfter(start) && TradeReview.same(rows, part.rows);
        }

        // 较早日期段必须紧接已核对的交界日。空结果可能来自日期切换时的短暂空白，需重开复查。
        boolean acceptEarlier(Poll poll, Read part) {
            DateRange range = poll.requestedRange;
            if (range == null || !range.matches(poll.displayedRange) || verifiedBoundary == null
                    || !range.end.plusDays(1).equals(verifiedBoundary)
                    || rows.get(0).time < range.endExclusive()) throw new LeaderHistory.Gap();
            if (part.rows.isEmpty()) {
                if (!part.historyComplete) throw new LeaderHistory.Gap();
                boolean confirmed = poll.cutoffReached || emptyPartitionSeen;
                emptyPartitionSeen = true;
                return confirmed;
            }
            if (!range.contains(part.rows)) throw new LeaderHistory.Gap();
            List<Rules.Signal> combined = new ArrayList<>(part.rows.size() + rows.size());
            combined.addAll(part.rows);
            combined.addAll(rows);
            LeaderHistory.verify(combined);
            rows = combined;
            cursor = poll;
            verifiedBoundary = null;
            emptyPartitionSeen = false;
            return part.historyComplete && (poll.cutoffReached || !hasEarlierUnverifiedDates(poll, part.rows));
        }

        Read report(String detail, boolean complete, boolean recovered) {
            return new Read(first.name, rows, first.leaderMargin, first.profileMargin, complete, detail,
                    recovered, first.recoveryDetail, first.snapshotAt);
        }
    }

    WebsiteCapture(Context context, String portfolio) throws IOException {
        this(context, portfolio, loadScript(context));
    }

    WebsiteCapture(Context context, String portfolio, String script) {
        this(context, portfolio, script, false);
    }

    WebsiteCapture(Context context, String portfolio, boolean detached) throws IOException {
        this(context, portfolio, loadScript(context), detached);
    }

    private WebsiteCapture(Context context, String portfolio, String script, boolean detached) {
        this.context = context.getApplicationContext();
        url = portfolioUrl(portfolio);
        path = Uri.parse(url).getPath();
        this.script = script;
        this.detached = detached;
    }

    private static String loadScript(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("website-capture.js");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[2048];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String portfolioUrl(String portfolio) {
        if (!portfolio.matches("[0-9]{8,25}")) throw new IllegalArgumentException("组合 ID 无效");
        return "https://www.binance.com/zh-CN/copy-trading/lead-details/" + portfolio + "?timeRange=7D";
    }

    // Activity 的窗口可以帮助冷启动渲染，服务在 Activity 离开后继续持有同一个 WebView。
    static void attachViewport(FrameLayout host) {
        viewport = new WeakReference<>(host);
        if (mounted != null) mounted.mount();
        if (mountedBalance != null) mountedBalance.mount();
    }

    static void detachViewport(FrameLayout host) {
        if (viewport.get() == host) attachViewport(null);
    }

    static boolean attachRegionPrompt(String portfolio, FrameLayout host) {
        WebsiteCapture capture = mounted;
        return capture != null && capture.regionPrompt && capture.attachPrompt(portfolio, host);
    }

    static boolean attachBaselinePrompt(String portfolio, FrameLayout host) {
        WebsiteCapture capture = mounted;
        if (capture == null || capture.regionPrompt || capture.document.isEmpty()
                || !capture.attachPrompt(portfolio, host)) return false;
        capture.manualBaselineDocument = capture.document;
        return true;
    }

    private boolean attachPrompt(String portfolio, FrameLayout host) {
        if (current != null || promptViewport != null || page == null
                || !url.equals(portfolioUrl(portfolio)) || !safe(page.getUrl())) return false;
        promptViewport = host;
        mount();
        return true;
    }

    static void detachRegionPrompt(FrameLayout host) {
        if (mounted != null && mounted.promptViewport == host) {
            mounted.promptViewport = null;
            mounted.mount();
        }
    }

    Read fetch(Rules.Signal anchor, Predicate<List<Rules.Signal>> archiveOverlap, boolean baseline,
            boolean verifyEmptyAgain) throws Exception {
        Poll poll = new Poll(anchor, archiveOverlap, false, baseline);
        poll.verifyEmptyAgain = verifyEmptyAgain;
        if (!baseline) {
            baselineAccepted();
            return waitFor(poll);
        }
        if (pendingBaseline != null) return pendingBaseline;
        Read result = baselineProgress == null ? readBaseline(poll) : resumeBaseline(baselineProgress, poll);
        // 同秒成交可能需要分几轮独立复核。保留这份候选直到档案提交成功，避免每次复核都重新建档。
        if (result.historyComplete) pendingBaseline = result;
        return result;
    }

    void baselineAccepted() {
        pendingBaseline = null;
        baselineProgress = null;
    }

    // 4.3.9 的首次建档优先沿官网原列表展开。保留同一个文档可避免日期筛选引起的重载和交界重读。
    private Read readBaseline(Poll poll) throws Exception {
        Read read = waitFor(poll);
        if (poll.cutoffReached || read.historyComplete || read.rows.isEmpty() || poll.baselineZone == null) return read;
        BaselineProgress progress = new BaselineProgress(poll, read);
        baselineProgress = progress;
        if (poll.initialBaseline()) return read;
        return continueBaseline(progress, poll, read.historyDetail, false);
    }

    private Read resumeBaseline(BaselineProgress progress, Poll initial) throws Exception {
        if (progress.verifiedBoundary != null) return continueByDatePartitions(progress, initial, "官网基线从已核对日期续读");
        if (initial.initialBaseline() && progress.cursor.initialBaseline()) return resumeInitialBaseline(progress, initial);
        Poll retry = progress.cursor.resumeBaseline();
        String detail = "官网基线从已读页续读";
        try {
            Read part = waitFor(retry);
            if (progress.accept(retry, part)) {
                if (retry.cutoffReached || (part.historyComplete && !hasEarlierUnverifiedDates(retry, part.rows))) {
                    return finishBaseline(progress, initial);
                }
                detail = part.historyDetail;
            }
        } catch (Failure unavailable) {
            if (!retryableBaselineFailure(unavailable)) throw unavailable;
            detail += " · " + unavailable.getMessage();
        } catch (LostBaselinePage lost) {
            return continueBaseline(progress, initial, detail + " · 已保留上一页成交", true);
        } catch (LeaderHistory.Gap mismatch) {
            detail += " · 官网续读与已读记录未衔接";
        }
        return continueBaseline(progress, initial, detail, false);
    }

    // 系统回收原网页时，继承 7 天范围并从新页面开始核对。旧候选要等重合记录对齐后才能合并。
    private Read resumeInitialBaseline(BaselineProgress progress, Poll fresh) throws Exception {
        String detail = "官网基线从已读页续读";
        Poll poll = progress.cursor.resumeBaseline();
        try {
            Read part;
            try {
                part = waitFor(poll);
            } catch (LostBaselinePage lost) {
                part = null;
            }
            if (part == null) {
                inheritBaselineWindow(fresh, progress.cursor);
                poll = fresh;
                part = waitFor(poll);
            }
            if ("empty".equals(poll.phase)) {
                return continueBaseline(progress, fresh, detail + " · 官网暂未返回原有成交", false);
            }
            if (progress.accept(poll, part)) {
                if (part.historyComplete) return finishBaseline(progress, fresh);
                detail = part.historyDetail;
            } else detail += " · 官网暂未返回原有成交";
        } catch (LeaderHistory.Gap mismatch) {
            detail += " · 官网续读与已读记录未衔接";
        } catch (Failure unavailable) {
            if (!retryableBaselineFailure(unavailable)) throw unavailable;
            detail += " · " + unavailable.getMessage();
        }
        return progress.report(detail + " · 本次建档已保留 " + progress.rows.size() + " 笔", false, false);
    }

    /**
     * 原列表卡住时尝试把结束日期缩到最早已读记录所在日，让官网从交界日重新提供成交。
     * 缩窗仍可能遇到卡页或迟到的控件。只接收已应用目标日期且交界记录能对齐的结果，
     * 同一窗口迟迟无法前进时交回已核对的候选和进度，供下一轮继续。
     */
    private Read continueBaseline(BaselineProgress progress, Poll initial, String detail, boolean reopen) throws Exception {
        Poll preceding = progress.cursor;
        for (int attempt = 0; attempt < BASELINE_CONTINUATIONS; attempt++) {
            Poll older = new Poll(null, rows -> true, false, true);
            inheritBaselineBounds(older, preceding);
            older.requestedRange = baselineRange(older);
            if (!reopen && preceding.continuingBaseline() && older.requestedRange.matches(preceding.requestedRange)) {
                detail += " · 已到同一日期窗口，页内仍有成交待核对";
                break;
            }
            older.reopenedDatePage = reopen;
            older.priorRangeText = preceding.lastText;
            try {
                Read part = waitFor(older);
                if (!progress.accept(older, part)) {
                    detail += " · 日期续读未返回可核对记录";
                    break;
                }
                detail += " · 日期续读至 " + older.requestedRange.end;
                if (older.cutoffReached || (part.historyComplete && !hasEarlierUnverifiedDates(older, part.rows))) {
                    return finishBaseline(progress, initial);
                }
                detail += " · " + part.historyDetail;
                if (part.historyComplete) break;
                preceding = older;
                reopen = false;
            } catch (LeaderHistory.Gap mismatch) {
                detail += " · 日期续读和已读成交无连续重合";
                break;
            } catch (Failure unavailable) {
                if (!retryableBaselineFailure(unavailable)) throw unavailable;
                detail += " · 日期续读未完成 · " + unavailable.getMessage();
                break;
            }
        }
        if (hasEarlierUnverifiedDates(progress.cursor, progress.rows)) {
            long oldest = progress.rows.get(0).time;
            Read split = continueByDatePartitions(progress, initial, detail);
            if (split.historyComplete || progress.verifiedBoundary != null || progress.rows.get(0).time < oldest) {
                return split;
            }
            if (retryRawBaseline(progress, initial)) return finishBaseline(progress, initial);
            if (progress.rows.size() != split.rows.size()) {
                return progress.report(detail + " · 刷新列表后已保留 " + progress.rows.size() + " 笔", false, false);
            }
            return split;
        }
        if (retryRawBaseline(progress, initial)) return finishBaseline(progress, initial);
        return progress.report(detail + " · 本次建档已保留 " + progress.rows.size() + " 笔", false, false);
    }

    private static void inheritBaselineBounds(Poll target, Poll from) {
        target.verifyEmptyAgain = false;
        inheritBaselineWindow(target, from);
        target.continuationFrom = from.lastRows.get(0).time;
        target.pagination = target.newPagination();
    }

    private static void inheritBaselineWindow(Poll target, Poll from) {
        target.baselineCutoff = from.baselineCutoff;
        target.baselineNewest = from.baselineNewest;
        target.baselineZone = from.baselineZone;
    }

    private static boolean hasEarlierUnverifiedDates(Poll poll, List<Rules.Signal> rows) {
        if (rows.isEmpty() || poll.baselineZone == null) return false;
        ZoneId zone = poll.baselineZone;
        LocalDate first = Instant.ofEpochMilli(poll.baselineCutoff).atZone(zone).toLocalDate();
        return Instant.ofEpochMilli(rows.get(0).time).atZone(zone).toLocalDate().isAfter(first);
    }

    /**
     * 单独读完交界日后，再查询前一天及更早的范围。把卡住的交界日和更早日期分开读取，
     * 合并时用相邻的日期范围防止跳过整天。日期控件不可用时，调用方可尝试重开原列表核对。
     */
    private Read continueByDatePartitions(BaselineProgress progress, Poll initial, String detail) throws Exception {
        ZoneId zone = progress.cursor.baselineZone;
        LocalDate first = Instant.ofEpochMilli(progress.cursor.baselineCutoff).atZone(zone).toLocalDate();
        try {
            // Each nonempty partition moves the oldest verified trade back at least one date.
            while (hasEarlierUnverifiedDates(progress.cursor, progress.rows)) {
                if (progress.verifiedBoundary == null) {
                    Poll cursor = progress.cursor;
                    LocalDate day = Instant.ofEpochMilli(progress.rows.get(0).time).atZone(zone).toLocalDate();
                    Poll seam = new Poll(null, rows -> true, false, true);
                    inheritBaselineBounds(seam, cursor);
                    seam.requestedRange = new DateRange(day, day, zone);
                    seam.reopenedDatePage = seam.requestedRange.matches(cursor.displayedRange);
                    seam.priorRangeText = cursor.lastText;
                    Read boundary = waitFor(seam);
                    if (!boundary.historyComplete || !progress.accept(seam, boundary)) {
                        detail += " · 交界日 " + day + " 尚待核对";
                        break;
                    }
                    progress.verifiedBoundary = day;
                }
                detail += " · 已核对交界日 " + progress.verifiedBoundary;
                Poll earlier = new Poll(null, rows -> true, false, true);
                inheritBaselineBounds(earlier, progress.cursor);
                LocalDate end = progress.verifiedBoundary.minusDays(1);
                earlier.requestedRange = new DateRange(first, end, zone);
                earlier.allowEmptyPartition = true;
                earlier.reopenedDatePage = progress.emptyPartitionSeen;
                earlier.priorRangeText = progress.cursor.lastText;
                Read part = waitFor(earlier);
                if (progress.acceptEarlier(earlier, part)) return finishBaseline(progress, initial);
                if (!part.historyComplete && !hasEarlierUnverifiedDates(earlier, part.rows)) {
                    detail += " · 更早日期 " + first + " 至 " + end + " 待复核";
                    break;
                }
            }
        } catch (LeaderHistory.Gap mismatch) {
            detail += " · 分段日期与已读成交未衔接";
        } catch (Failure unavailable) {
            if (!retryableBaselineFailure(unavailable)) throw unavailable;
            detail += " · 分段续读未完成 · " + unavailable.getMessage();
        }
        return progress.report(detail + " · 本次建档已保留 " + progress.rows.size() + " 笔", false, false);
    }

    private boolean retryRawBaseline(BaselineProgress progress, Poll initial) throws Exception {
        // A fresh page can still paginate when the date control stays hidden or cannot apply a range.
        Poll reread = new Poll(initial.anchor, initial.archiveOverlap, false, true);
        inheritBaselineWindow(reread, progress.cursor);
        try {
            Read part = waitFor(reread);
            if (part.rows.isEmpty()) return false;
            boolean shortWindow = !hasEarlierUnverifiedDates(progress.cursor, progress.rows)
                    && progress.confirmsShortWindow(reread, part);
            boolean ended = reread.cutoffReached || shortWindow || (reread.initialBaseline() && part.historyComplete);
            // Keep the existing cursor when the fresh page cannot improve or confirm it.
            List<Rules.Signal> merged = LeaderHistory.reconcileBaselineOverlap(progress.rows, part.rows);
            if (!ended && merged.size() <= progress.rows.size()) return false;
            progress.rows = merged;
            progress.cursor = reread;
            return ended;
        } catch (LeaderHistory.Gap mismatch) {
            return false;
        } catch (Failure unavailable) {
            if (!retryableBaselineFailure(unavailable)) throw unavailable;
            return false;
        }
    }

    private static boolean retryableBaselineFailure(Failure failure) {
        return failure.access == Access.NONE && !failure.stop && failure.backoffMs <= 3000;
    }

    private Read finishBaseline(BaselineProgress progress, Poll initial) {
        boolean overlaps = initial.anchor == null || initial.archiveOverlap.test(progress.rows);
        if (overlaps) baselineProgress = null;
        String report = (overlaps ? (progress.cursor.cutoffReached ? "官网已核对 7 天建档窗口" : "官网基线已读到末尾")
                : "官网基线仍未衔接旧档案")
                + " · " + progress.rows.size() + " 笔";
        boolean recovered = overlaps && !progress.first.recoveryDetail.isEmpty();
        return progress.report(report, overlaps, recovered);
    }

    private static DateRange baselineRange(Poll poll) {
        LocalDate first = Instant.ofEpochMilli(poll.baselineCutoff).atZone(poll.baselineZone).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(poll.continuationFrom).atZone(poll.baselineZone).toLocalDate();
        return new DateRange(first, last, poll.baselineZone);
    }

    List<Rules.Signal> review(long stamp) throws Exception {
        return TradeReview.at(waitFor(new Poll(null, rows -> false, false, false, stamp)).rows, stamp);
    }

    List<ClosedTradeReview.Position> closedPositions() throws Exception {
        Poll poll = new Poll(null, rows -> false, false, false);
        poll.positionHistory = new CompletableFuture<>();
        return waitFor(poll, poll.positionHistory);
    }

    String name() throws Exception { return waitFor(new Poll(null, rows -> true, true, false)).name; }

    LeaderBalances.Read balances(long cutoff) throws Exception {
        Poll poll = new Poll(null, rows -> true, false, false);
        poll.balances = new CompletableFuture<>();
        poll.balanceCutoff = cutoff;
        return waitFor(poll, poll.balances);
    }

    private Read waitFor(Poll poll) throws Exception {
        return waitFor(poll, poll.result);
    }

    private <T> T waitFor(Poll poll, CompletableFuture<T> result) throws Exception {
        main.post(() -> start(poll));
        try { return result.get(poll.deadline() + 3000, TimeUnit.MILLISECONDS); }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failed;
        } catch (TimeoutException timeout) {
            main.post(() -> {
                if (current == poll) timeout(poll);
            });
            throw new Failure("系统未及时处理网页任务，本轮跳过", 3000, false);
        }
    }

    private void start(Poll poll) {
        if (promptViewport != null) {
            reject(poll, accessFailure(Access.REGION));
            return;
        }
        if (closed || current != null) {
            reject(poll, new Failure("官网读取尚未就绪", 3000, false));
            return;
        }
        long remainingBackoff = webRetryAfter - SystemClock.elapsedRealtime();
        if (remainingBackoff > 0) {
            reject(poll, new Failure("币安官网限流，等待重试", remainingBackoff, false));
            return;
        }
        current = poll;
        poll.startedAt = SystemClock.elapsedRealtime();
        regionPrompt = false;
        if (detached) mountedBalance = this;
        else mounted = this;
        main.postDelayed(() -> {
            if (current == poll) timeout(poll);
        }, poll.deadline());
        try {
            if (page != null && document.isEmpty()) destroyPage();
            if (page == null) page = newPage();
            mount();
            if (poll.resumeDocument != null) {
                if (!poll.resumeDocument.equals(document) || !safe(page.getUrl())) {
                    current = null;
                    reject(poll, new LostBaselinePage());
                    return;
                }
                poll.document = document;
                poll.calendarSince = poll.startedAt;
                if (document.equals(manualBaselineDocument)) {
                    poll.pagination.manualResume(poll.startedAt);
                    manualBaselineDocument = "";
                }
            } else if ((poll.positionHistory != null || (poll.continuingBaseline() && !poll.reopenedDatePage))
                    && !document.isEmpty() && safe(page.getUrl())) {
                poll.document = document;
            } else {
                poll.previous = document;
                if (safe(page.getUrl())) page.reload();
                else page.loadUrl(url);
            }
            again(poll);
        } catch (RuntimeException unavailable) {
            destroyPage();
            fail(new Failure("系统 WebView 无法打开币安网页", 3000, false));
        }
    }

    private WebView newPage() {
        WebView view = new WebView(context);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.getSettings().setUseWideViewPort(true);
        view.getSettings().setLoadWithOverviewMode(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusable(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) {
                if (!request.isForMainFrame() || safe(request.getUrl().toString())) return false;
                if (source == page && current != null) {
                    if (current.profile && portfolioIndex(request.getUrl())) fail(missingPortfolio());
                    else fail(accessFailure(redirectAccess(request.getUrl())));
                }
                return true;
            }

            @Override public void onReceivedError(WebView source, WebResourceRequest request, WebResourceError error) {
                if (current != null && source == page && request.isForMainFrame()) {
                    fail(new Failure("币安网页连接失败（" + error.getErrorCode() + "）", 3000, false));
                }
            }

            @Override public void onReceivedSslError(WebView source, SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.cancel();
                if (current != null && source == page && safe(error.getUrl())) {
                    fail(new Failure("币安网页的 TLS 证书校验失败", 0, true));
                }
            }

            @Override public void onReceivedHttpError(WebView source, WebResourceRequest request,
                    WebResourceResponse response) {
                if (source != page) return;
                Uri address = request.getUrl();
                if (!request.isForMainFrame() && !("www.binance.com".equals(address.getHost())
                        && address.getPath() != null && address.getPath().endsWith("/lead-portfolio/order-history"))) return;
                int status = response.getStatusCode();
                if (status == 429 || status == 418) {
                    long delay = FuturesApi.retryMillis(response.getResponseHeaders() == null ? null
                            : response.getResponseHeaders().entrySet().stream()
                            .filter(header -> "Retry-After".equalsIgnoreCase(header.getKey()))
                            .map(java.util.Map.Entry::getValue).findFirst().orElse(null), status);
                    webRetryAfter = Math.max(webRetryAfter, SystemClock.elapsedRealtime() + delay);
                    if (current != null) fail(new Failure("币安网页取数 HTTP " + status, delay, false));
                    return;
                }
                if (current == null) return;
                if (current.profile && request.isForMainFrame() && safe(address.toString()) && (status == 404 || status == 410)) {
                    fail(missingPortfolio());
                    return;
                }
                Access access = httpAccess(status);
                if (access != Access.NONE) {
                    fail(accessFailure(access));
                    return;
                }
                if (!request.isForMainFrame() && status >= 500 && !current.lastRows.isEmpty()) {
                    current.pageErrorUntil = SystemClock.elapsedRealtime() + 2000;
                    return;
                }
                fail(new Failure("币安网页取数 HTTP " + status, 3000, false));
            }

            @Override public boolean onRenderProcessGone(WebView source, RenderProcessGoneDetail detail) {
                if (source == page) {
                    destroyPage();
                    fail(new Failure("系统回收了网页进程，下轮重新打开", 3000, false));
                }
                return true;
            }
        });
        return view;
    }

    private void mount() {
        if (page == null) return;
        boolean showingPrompt = promptViewport != null;
        FrameLayout host = showingPrompt ? promptViewport : viewport.get();
        if (page.getParent() == host && host != null) return;
        removeFromParent();
        page.setFocusable(showingPrompt);
        page.setFocusableInTouchMode(showingPrompt);
        page.setImportantForAccessibility(showingPrompt ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (host != null) host.addView(page, new FrameLayout.LayoutParams(-1, -1));
        else {
            float density = context.getResources().getDisplayMetrics().density;
            int width = detached ? Math.round(960 * density) : 900;
            int height = detached ? Math.round(800 * density) : 1600;
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            page.layout(0, 0, width, height);
        }
    }

    private void inspect(Poll poll) {
        if (current != poll || page == null) return;
        long now = SystemClock.elapsedRealtime();
        try {
            JSONObject request = request(poll).put("click", now - poll.clickedAt >= 1100);
            if (poll.selectingDate != null) {
                boolean end = poll.selectingDate == DateField.END;
                request.put("command", "date-pick").put("end", end)
                        .put("date", (end ? poll.rangeEnd : poll.rangeStart).toString());
            } else if (poll.triedRange && !poll.unfilteredReview() && !poll.rangeConfirmed) {
                request.put("applyDates", new JSONArray().put(poll.rangeStart.toString()).put(poll.rangeEnd.toString()));
            }
            evaluate(poll, request, data -> observe(poll, data));
        } catch (Exception invalid) { badSnapshot(invalid); }
    }

    private JSONObject request(Poll poll) throws Exception {
        return new JSONObject().put("path", path).put("previous", poll.previous).put("document", poll.document)
                .put("profile", poll.profile).put("balance", poll.balances != null).put("balancePage", poll.balancePage)
                .put("positionHistory", poll.positionHistory != null);
    }

    private interface Observation { void received(JSONObject data) throws Exception; }

    // JavaScript 回调可能晚于本轮超时或 WebView 重建。回调前重新检查轮次和 WebView，
    // 同一 WebView 内的刷新则由脚本用 document 标记识别旧页面。
    private void evaluate(Poll poll, JSONObject request, Observation callback) {
        WebView view = page;
        Runnable timeout = () -> {
            if (current == poll && view == page) baselineScriptFailed(poll);
        };
        if (poll.baseline) main.postDelayed(timeout, BASELINE_SCRIPT_TIMEOUT);
        view.evaluateJavascript("(function(){try{" + script + "\nreturn copyTerminalCapture("
                + request + ");}catch(error){return JSON.stringify({phase:'script-error'});}})()", value -> {
            if (poll.baseline) main.removeCallbacks(timeout);
            if (current != poll || view != page) return;
            try {
                Object response = value == null ? null : new JSONTokener(value).nextValue();
                if (!(response instanceof String)) {
                    if (poll.baseline && ++poll.emptyScriptReplies >= 5) baselineScriptFailed(poll);
                    else again(poll);
                    return;
                }
                poll.emptyScriptReplies = 0;
                JSONObject data = new JSONObject((String) response);
                if ("script-error".equals(data.optString("phase"))) {
                    if (poll.baseline) baselineScriptFailed(poll);
                    else fail(new Failure("官网网页脚本执行失败", 3000, false));
                    return;
                }
                poll.javascriptResponded = true;
                String id = data.optString("id", "");
                if (poll.document.equals(id)) document = id;
                callback.received(data);
            } catch (Exception invalid) { badSnapshot(invalid); }
        });
    }

    // WebView 偶尔停止回传脚本结果。优先交回已读列表供续读，手里还没有记录时只重建一次页面。
    private void baselineScriptFailed(Poll poll) {
        destroyPage();
        String reason = "网页脚本未返回，已保留可续读记录";
        if (!poll.lastRows.isEmpty()) {
            poll.parseIssue = reason;
            complete(poll, false);
        } else if (!poll.baselineScriptRestarted) {
            poll.baselineScriptRestarted = true;
            poll.javascriptResponded = false;
            poll.rereadEmpty = false;
            poll.triedRange = poll.rangePriming = poll.rangeRefreshed = poll.rangeConfirmed = false;
            poll.rangeRetries = 0;
            poll.emptyScriptReplies = 0;
            try {
                restartRows(poll);
                page = newPage();
                mount();
                page.loadUrl(url);
            } catch (RuntimeException unavailable) {
                destroyPage();
                fail(new Failure(reason, 3000, false));
            }
        } else {
            fail(new Failure(reason, 3000, false));
        }
    }

    /**
     * 处理脚本返回的单次观测。日期字段、成交文本和翻页按钮可能在不同时间就绪，
     * 由这里确认筛选生效、列表稳定和重合证据，再推进分页状态。
     */
    private void observe(Poll poll, JSONObject data) throws Exception {
        String phase = data.optString("phase");
        poll.phase = phase;
        if (poll.profile && "profile-missing".equals(phase)) {
            fail(missingPortfolio());
            return;
        }
        Access access = "region".equals(phase) ? Access.REGION : "verify".equals(phase) ? Access.VERIFY
                : "signin".equals(phase) ? Access.SIGN_IN : "blocked".equals(phase) ? Access.DENIED : Access.NONE;
        if (access != Access.NONE) {
            regionPrompt = access == Access.REGION && !poll.profile && !detached;
            fail(accessFailure(access));
            return;
        }
        if (poll.positionHistory != null) {
            observePositionHistory(poll, data, phase);
            return;
        }
        if (poll.balances != null) {
            observeBalances(poll, data, phase);
            return;
        }
        String name = data.optString("name").trim();
        if (!name.isEmpty() && name.length() <= 100 && !name.matches(".*[\\p{Cntrl}].*")) poll.name = name;
        BigDecimal margin = WebRecords.positiveAmount(data.optString("leaderMargin"));
        if (margin != null) poll.leaderMargin = margin;
        BigDecimal compact = WebRecords.positiveAmount(data.optString("profileMargin"));
        if (compact != null) poll.profileMargin = compact;
        if (poll.profile) {
            if ("profile".equals(phase) && !poll.name.isEmpty()) complete(poll, false);
            else again(poll);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int older = 0;
        boolean listing = "rows".equals(phase) || "list".equals(phase) || "empty".equals(phase);
        boolean busy = data.optBoolean("busy") || now < poll.pageErrorUntil;
        boolean loading = data.optBoolean("loading");
        DateRange displayed = listing ? DateRange.displayed(data) : null;
        if ("calendar-apply".equals(phase)) {
            poll.rangeConfirmed = true;
            poll.calendarSince = now;
            poll.emptySince = 0;
            again(poll);
            return;
        }
        if (poll.continuingBaseline() && !poll.triedRange && listing) {
            if (poll.controlsSince == 0) poll.controlsSince = now;
            DateRange requested = poll.requestedRange;
            if (!busy && !loading && requested != null && data.optBoolean("datePicker")) {
                // 重选相同日期可能无法触发官网刷新。多日窗口先暂时缩短一天，再恢复目标范围。
                poll.rangePriming = requested.matches(displayed) && requested.start.isBefore(requested.end);
                LocalDate start = poll.rangePriming ? requested.start.plusDays(1) : requested.start;
                beginRange(poll, start, requested.end, data, now);
            } else if (now - poll.controlsSince >= DATE_CONTROL_TIMEOUT) {
                String reason = busy || loading ? "官网仍在加载或显示繁忙"
                        : requested == null ? "官网未显示日期范围" : "官网日期控件未就绪";
                if (!busy && reopenDatePage(poll)) return;
                current = null;
                reject(poll, new Failure(reason, 3000, false));
                return;
            }
            again(poll);
            return;
        }
        if (poll.reviewing() && !poll.triedRange && !poll.unfilteredReview() && listing && !busy && !loading) {
            if (poll.controlsSince == 0) poll.controlsSince = now;
            if (selectRange(poll, data, now) || now - poll.controlsSince < 3000) again(poll);
            else fallbackReview(poll, "官网日期控件未加载");
            return;
        }
        if (poll.triedRange && !poll.unfilteredReview() && poll.selectingDate == null
                && listing && (!selectedRange(poll, data) || data.optBoolean("dateApplyPending"))) {
            long waiting = now - poll.calendarSince;
            if (waiting >= DATE_APPLY_RETRY && !busy && !loading && poll.rangeRetries == 0 && data.optBoolean("datePicker")) {
                poll.rangeRetries++;
                pickRange(poll, data, now);
            } else if (waiting >= DATE_APPLY_TIMEOUT) {
                String requested = poll.rangeStart + " 至 " + poll.rangeEnd;
                String reason = "官网未确认所选日期 " + requested;
                if (poll.reviewing()) fallbackReview(poll, reason);
                else if (reopenDatePage(poll)) return;
                else fail(new Failure(reason, 3000, false));
                return;
            }
            again(poll);
            return;
        }
        if (poll.rangePriming && poll.selectingDate == null && listing) {
            if (!busy && !loading) {
                poll.rangePriming = false;
                poll.rangeRefreshed = false;
                poll.rangeRetries = 0;
                beginRange(poll, poll.requestedRange.start, poll.requestedRange.end, data, now);
            }
            again(poll);
            return;
        }
        if (poll.continuingBaseline() && poll.triedRange && poll.selectingDate == null && listing
                && (!"rows".equals(phase) || busy || loading)) poll.rangeRefreshed = true;
        if (poll.resumeDocument != null && "empty".equals(phase) && !busy && !loading) {
            if (poll.emptySince == 0) poll.emptySince = now;
            if (now - poll.emptySince >= BASELINE_EMPTY_WAIT) {
                poll.parseIssue = "官网暂未返回原有成交，重新核对日期";
                complete(poll, false);
            } else again(poll);
            return;
        }
        if ("select".equals(phase) && data.optBoolean("clicked")) poll.clickedAt = now;
        if ("calendar".equals(phase)) {
            if (data.optBoolean("opened")) poll.clickedAt = now;
            if (data.optBoolean("clicked")) {
                if (poll.endBeforeStart) {
                    if (poll.selectingDate == DateField.END) poll.selectingDate = DateField.START;
                    else {
                        poll.selectingDate = null;
                        poll.endBeforeStart = false;
                    }
                } else poll.selectingDate = poll.selectingDate == DateField.START && !poll.rangeEndAlreadySelected
                        ? DateField.END : null;
                poll.calendarMoves = 0;
                poll.calendarSince = now;
                poll.clickedAt = 0;
                if (poll.selectingDate == null) poll.emptySince = 0;
            } else if (poll.reviewing() && poll.selectingDate == DateField.START && data.optBoolean("blocked")
                    && !poll.rangeStart.equals(poll.rangeEnd)) {
                poll.rangeStart = poll.rangeEnd;
                poll.calendarSince = now;
                poll.calendarMoves = 0;
            } else if (data.optBoolean("moved") && ++poll.calendarMoves <= 18) {
                poll.calendarSince = now;
            } else if (data.optBoolean("blocked") || poll.calendarMoves > 18 || now - poll.calendarSince > 4200) {
                String reason = data.optBoolean("blocked") ? "官网日历禁用了目标日期" : "官网日期控件未完成选择";
                if (poll.reviewing()) fallbackReview(poll, reason);
                else if (reopenDatePage(poll)) return;
                else fail(new Failure(reason, 3000, false));
                return;
            }
        } else if ("empty".equals(phase) && poll.lastRows.isEmpty() && poll.selectingDate == null && !busy && !loading
                && (poll.continuingBaseline() || !poll.name.isEmpty()) && !data.optBoolean("more")) {
            if (poll.emptySince == 0) poll.emptySince = now;
            if (now - poll.emptySince >= (poll.continuingBaseline() ? BASELINE_EMPTY_WAIT : EMPTY_WAIT)) {
                if (poll.reviewing()) { fallbackReview(poll, "官网所选日期没有返回记录"); return; }
                if (poll.continuingBaseline()) {
                    if (poll.allowEmptyPartition && poll.triedRange && displayed != null
                            && poll.requestedRange.matches(displayed)) {
                        poll.displayedRange = displayed;
                        complete(poll, true);
                        return;
                    }
                    if (!poll.rereadEmpty && data.optBoolean("datePicker")) {
                        poll.rereadEmpty = true;
                        pickRange(poll, data, now);
                        again(poll);
                    } else if (!reopenDatePage(poll)) {
                        fail(new Failure("重选日期后官网仍暂无记录，保留已核对成交", 3000, false));
                    }
                    return;
                }
                if (poll.anchor == null) {
                    // 首次看到稳定空页仍会重开复查，防止把官网的短暂空白写成一份空基线。
                    if (poll.verifyEmptyAgain && !poll.rereadEmpty) {
                        poll.rereadEmpty = true;
                        restartRows(poll);
                        return;
                    }
                    poll.snapshotAt = now;
                    complete(poll, true);
                    return;
                }
                if (!poll.triedRange && selectRange(poll, data, now)) { again(poll); return; }
                fail(new Failure(poll.triedRange ? "重新选择日期后官网仍暂无记录"
                        : "官网显示暂无记录，本轮未读取成交", 0, false));
                return;
            }
        } else if ("rows".equals(phase) && poll.selectingDate == null) {
            poll.emptySince = 0;
            if (poll.reviewing() && ((!poll.triedRange && !poll.unfilteredReview()) || busy || loading)) {
                again(poll);
                return;
            }
            String text = data.getString("text");
            if (poll.continuingBaseline() && poll.lastRows.isEmpty()
                    && (busy || loading || (!poll.rangeRefreshed && !data.optBoolean("more")
                            && text.equals(poll.priorRangeText) && now - poll.calendarSince < BASELINE_EMPTY_WAIT))) {
                again(poll);
                return;
            }
            if (poll.displayedRange != null && displayed != null && !poll.displayedRange.matches(displayed)) {
                poll.parseIssue = "官网成交日期在分页过程中变化";
            } else if (!text.equals(poll.lastText)) {
                try {
                    if (poll.baselineZone != null && !poll.baselineZone.getId().equals(data.getString("zone"))) {
                        throw new IllegalArgumentException("官网成交时区在建档过程中变化");
                    }
                    List<Rules.Signal> rows = parse(data, displayed == null ? poll.displayedRange : displayed);
                    LeaderHistory.verify(rows);
                    if (poll.lastRows.isEmpty()) {
                        poll.overlapping = poll.anchor == null || poll.archiveOverlap.test(rows);
                        poll.firstPageDisjoint = !poll.overlapping;
                        poll.snapshotAt = now;
                    } else {
                        older = LeaderHistory.pageGrowth(poll.lastRows, rows);
                        if (poll.reviewing() && TradeReview.addsIdenticalTrade(poll.lastRows, rows, poll.reviewStamp)) {
                            poll.reviewPageOverlap = true;
                        }
                        if (!poll.overlapping) poll.overlapping = poll.archiveOverlap.test(rows);
                        long latest = poll.lastRows.get(poll.lastRows.size() - 1).time;
                        if (rows.get(rows.size() - 1).time > latest
                                || TradeReview.at(rows, latest).size() > TradeReview.at(poll.lastRows, latest).size()) {
                            poll.snapshotAt = now;
                        }
                    }
                    poll.lastRows = rows;
                    if (displayed != null) poll.displayedRange = displayed;
                    poll.lastText = text;
                    poll.textChangedAt = now;
                    poll.parseIssue = "";
                } catch (IllegalArgumentException invalidPage) {
                    poll.parseIssue = invalidPage.getMessage();
                }
            } else if (!poll.parseIssue.isEmpty()) {
                // An incomplete render can roll back to the last valid page before retry.
                poll.parseIssue = "";
                poll.textChangedAt = now;
            }
            if (!busy && !loading && poll.displayedRange == null && displayed != null && displayed.contains(poll.lastRows)) {
                // Date inputs can render after the first trades and without any change to their text.
                poll.displayedRange = displayed;
            }
            if (poll.reviewing() && !poll.unfilteredReview() && !poll.parseIssue.isEmpty() && now - poll.calendarSince >= 5000) {
                fallbackReview(poll, "日期切换后官网记录尚未稳定");
                return;
            }
        } else if (listing && poll.selectingDate == null) poll.emptySince = 0;
        if (!poll.lastRows.isEmpty() && listing) {
            boolean settled = now - poll.textChangedAt >= 800;
            if (poll.baseline && poll.baselineZone == null && !busy && !loading
                    && "rows".equals(phase) && poll.parseIssue.isEmpty()) {
                poll.baselineZone = ZoneId.of(data.getString("zone"));
                // 用首批可解析且未在加载的列表锁定上下限，后续新出现的成交留给建档后的轮询。
                poll.baselineNewest = poll.lastRows.get(poll.lastRows.size() - 1).time;
                poll.baselineCutoff = poll.baselineNewest - BASELINE_WINDOW;
            }
            if (poll.baseline && settled && !busy && !loading && "rows".equals(phase)
                    && poll.parseIssue.isEmpty() && poll.lastRows.get(0).time < poll.baselineCutoff) {
                poll.cutoffReached = true;
                complete(poll, poll.overlapping);
                return;
            }
            if (poll.reviewing() && !busy && !loading && "rows".equals(phase) && poll.parseIssue.isEmpty()) {
                if (poll.lastRows.get(0).time < poll.reviewStamp) {
                    if (!settled) { again(poll); return; }
                    if (TradeReview.at(poll.lastRows, poll.reviewStamp).isEmpty()) {
                        fallbackReview(poll, "官网已读页面没有包含待核对的成交");
                    } else complete(poll, true);
                    return;
                }
            }
            boolean moreAvailable = data.optBoolean("more");
            boolean targetPresent = poll.reviewing() ? !TradeReview.at(poll.lastRows, poll.reviewStamp).isEmpty() : poll.overlapping;
            HistoryPagination.Step step = poll.pagination.observe(poll.lastRows.size(), older, targetPresent,
                    moreAvailable, data.optBoolean("moreReady"), busy,
                    loading || !settled || !"rows".equals(phase) || !poll.parseIssue.isEmpty(), now);
            if (step == HistoryPagination.Step.CLICK) {
                evaluate(poll, request(poll).put("command", "more"), more -> {
                    if (more.optBoolean("clicked")) {
                        poll.pagination.clicked(SystemClock.elapsedRealtime());
                    }
                    again(poll);
                });
                return;
            }
            if (step == HistoryPagination.Step.END || step == HistoryPagination.Step.INCOMPLETE) {
                if (step == HistoryPagination.Step.END && poll.baseline && !poll.initialBaseline()
                        && !poll.continuingBaseline()) {
                    poll.baselineEndObserved = true;
                    poll.parseIssue = "官网未显示「更多」，重新选择日期核对";
                    complete(poll, false);
                    return;
                }
                if (step == HistoryPagination.Step.INCOMPLETE && !poll.reviewing() && !poll.baseline
                        && !poll.restartedPagination
                        && (moreAvailable || loading || busy || poll.pagination.inFlight())
                        && now - poll.startedAt < poll.deadline() - 18000) {
                    poll.restartedPagination = true;
                    restartRows(poll);
                    return;
                }
                if (step == HistoryPagination.Step.INCOMPLETE && poll.parseIssue.isEmpty()) {
                    if (busy) poll.parseIssue = "官网显示繁忙，下一页未返回";
                    else if (moreAvailable || poll.pagination.inFlight()) {
                        poll.parseIssue = "第 " + (poll.pagination.pages() + 1) + " 页未返回新增记录";
                    } else poll.parseIssue = "已读列表未衔接旧档案";
                }
                complete(poll, step == HistoryPagination.Step.END);
                return;
            }
        }
        again(poll);
    }

    private void observePositionHistory(Poll poll, JSONObject data, String phase) throws JSONException {
        long now = SystemClock.elapsedRealtime();
        if ("select".equals(phase) && data.optBoolean("clicked")) poll.clickedAt = now;
        if (poll.document.equals(data.optString("id")) && "positions".equals(phase)) {
            String sample = data.getJSONArray("positions").toString();
            if (!sample.equals(poll.positionSample)) {
                poll.positionSample = sample;
                poll.textChangedAt = now;
            } else if (now - poll.textChangedAt >= 900) {
                List<ClosedTradeReview.Position> positions = ClosedTradeReview.parse(data);
                current = null;
                poll.positionHistory.complete(positions);
                return;
            }
        } else poll.positionSample = null;
        again(poll);
    }

    private void observeBalances(Poll poll, JSONObject data, String phase) throws Exception {
        BigDecimal margin = LeaderBalances.parseMargin(data);
        if (margin != null) poll.balanceMargin = margin;
        long now = SystemClock.elapsedRealtime();
        if ("select".equals(phase) && data.optBoolean("clicked")) poll.clickedAt = now;
        int displayed = data.optInt("page");
        if (!"balances".equals(phase) || (displayed != 0 && displayed != poll.balancePage)) {
            if (data.optBoolean("busy")) poll.phase = "balance-busy";
            else if (displayed != 0 && displayed != poll.balancePage) poll.phase = "balance-page";
            poll.balanceSample = "";
            again(poll);
            return;
        }
        JSONArray rows = data.getJSONArray("transfers");
        String rowSample = rows.toString();
        if (poll.balancePage > 1 && rowSample.equals(poll.previousBalancePage)) {
            poll.phase = "balance-page";
            again(poll);
            return;
        }
        if (!rowSample.equals(poll.balanceSample)) {
            poll.balanceSample = rowSample;
            poll.textChangedAt = now;
            again(poll);
            return;
        }
        if (now - poll.textChangedAt < (rows.length() == 0 ? EMPTY_WAIT : 1000)) {
            again(poll);
            return;
        }
        if (poll.savedBalancePage != poll.balancePage) {
            LeaderBalances.Read page;
            try {
                page = LeaderBalances.parse(data);
            } catch (Exception invalid) {
                poll.phase = "balance-invalid";
                again(poll);
                return;
            }
            if (poll.balancePage > 1 && (page.transfers.isEmpty()
                    || page.transfers.get(0).at > poll.balanceOldest)) {
                poll.phase = "balance-page";
                again(poll);
                return;
            }
            if (poll.balanceRows.length() + page.transfers.size() > 1000) {
                completeBalances(poll, false, "余额历史超过单轮读取范围");
                return;
            }
            for (int i = 0; i < page.transfers.size(); i++) {
                poll.balanceRows.put(rows.getJSONArray(i));
                poll.balanceOldest = Math.min(poll.balanceOldest, page.transfers.get(i).at);
            }
            poll.balanceZone = data.getString("zone");
            poll.savedBalancePage = poll.balancePage;
        }
        if (!data.optBoolean("more") || rows.length() == 0 || poll.balanceOldest < poll.balanceCutoff) {
            completeBalances(poll, true, "");
            return;
        }
        poll.phase = "balance-next";
        evaluate(poll, request(poll).put("command", "balance-next"), next -> {
            if (!next.optBoolean("clicked")) {
                again(poll);
                return;
            }
            poll.balancePage++;
            // Do not accept the previous page while the table is switching.
            poll.balanceSample = "";
            poll.previousBalancePage = rowSample;
            poll.textChangedAt = SystemClock.elapsedRealtime();
            again(poll);
        });
    }

    private void completeBalances(Poll poll, boolean done, String issue) throws Exception {
        List<LeaderBalances.Event> transfers = poll.balanceZone == null ? Collections.emptyList()
                : LeaderBalances.parse(new JSONObject().put("zone", poll.balanceZone).put("transfers", poll.balanceRows)).transfers;
        current = null;
        poll.balances.complete(new LeaderBalances.Read(poll.balanceMargin, transfers, done, issue));
    }

    // 到达时间边界后仍保留边界当秒。补旧档还要检查缩窗后的结果能否接上已有水位。
    private void complete(Poll poll, boolean ended) {
        if (poll.reviewing() && !ended) {
            fallbackReview(poll, "官网分页复核未完成");
            return;
        }
        if (poll.reviewing() && poll.reviewPageOverlap) {
            // 重复同样的分页拼接仍无法确定同秒笔数，改用独立读取。
            fallbackReview(poll, "同一笔样式的成交在分页拼接后增多，笔数待核对");
            return;
        }
        if (poll.reviewing() && !poll.lastRows.isEmpty() && poll.lastRows.get(0).time == poll.reviewStamp
                && (poll.unfilteredReview() || poll.pagination.pages() > 1)) {
            fallbackReview(poll, "官网在待核对的同一秒结束，尚未确认该秒完整");
            return;
        }
        current = null;
        List<Rules.Signal> rows = new ArrayList<>(poll.lastRows.size());
        for (Rules.Signal signal : poll.lastRows) {
            if (!poll.baseline || (signal.time >= poll.baselineCutoff
                    && (poll.baselineNewest == 0 || signal.time <= poll.baselineNewest))) {
                rows.add(poll.reviewing() ? signal : signal.withLeaderMargin(poll.leaderMargin));
            }
        }
        if (ended && poll.baseline && poll.anchor != null && !rows.isEmpty()
                && !poll.archiveOverlap.test(rows)) {
            ended = false;
            poll.parseIssue = "7 天建档窗口内仍未衔接旧档案";
        }
        String report = poll.baseline || poll.firstPageDisjoint
                ? poll.pagination.report(SystemClock.elapsedRealtime(), rows.size(), ended) : "";
        if (!ended && !report.isEmpty() && poll.parseIssue != null && !poll.parseIssue.isEmpty()) {
            report += " · " + poll.parseIssue;
        }
        if (ended && poll.cutoffReached) report = "官网已核对 7 天建档窗口 · " + rows.size() + " 笔";
        if (poll.restartedPagination && !report.isEmpty()) report += " · 已重新打开列表核对";
        poll.result.complete(new Read(poll.name, rows, poll.leaderMargin,
                poll.leaderMargin == null ? poll.profileMargin : poll.leaderMargin, poll.baseline && ended,
                poll.baseline ? report : "", ended && poll.firstPageDisjoint, poll.firstPageDisjoint ? report : "", poll.snapshotAt));
    }

    private boolean selectRange(Poll poll, JSONObject data, long now) throws Exception {
        JSONArray dates = data.getJSONArray("dates");
        ZoneId zone = ZoneId.of(data.getString("zone"));
        LocalDate start = dates.length() >= 2 ? LocalDate.parse(dates.getString(0)) : null;
        LocalDate end;
        LocalDate target;
        if (poll.reviewing()) {
            if (start == null && !data.optBoolean("datePicker")) return false;
            end = Instant.ofEpochMilli(poll.reviewStamp).atZone(zone).toLocalDate();
            // If moving the end is enough to refresh the page, keep the current start.
            target = start != null && start.isBefore(end) && end.isBefore(LocalDate.parse(dates.getString(1)))
                    ? start : WebRecords.reviewStart(end, start);
        } else {
            if (start == null) return false;
            end = LocalDate.parse(dates.getString(1));
            long anchorTime = poll.anchor == null ? 0 : poll.anchor.time;
            target = poll.baseline && !end.isBefore(start) ? start : WebRecords.recoveryStart(start, end, anchorTime, zone);
        }
        if (target == null) return false;
        beginRange(poll, target, end, data, now);
        return true;
    }

    private static void beginRange(Poll poll, LocalDate start, LocalDate end, JSONObject data, long now) {
        poll.rangeStart = start;
        poll.rangeEnd = end;
        poll.triedRange = true;
        pickRange(poll, data, now);
    }

    private static void pickRange(Poll poll, JSONObject data, long now) {
        JSONArray dates = data.optJSONArray("dates");
        boolean startMatches = dates != null && poll.rangeStart.toString().equals(dates.optString(0));
        poll.rangeEndAlreadySelected = dates != null && poll.rangeEnd.toString().equals(dates.optString(1));
        // Narrow the end first if moving the start first would temporarily widen
        // the page's range. Calendar widgets can disable those earlier days.
        poll.endBeforeStart = poll.continuingBaseline() && !startMatches && dates != null && dates.length() >= 2
                && poll.rangeStart.toString().compareTo(dates.optString(0)) < 0
                && poll.rangeEnd.toString().compareTo(dates.optString(1)) < 0
                && poll.rangeEnd.toString().compareTo(dates.optString(0)) >= 0;
        poll.selectingDate = startMatches || poll.endBeforeStart ? DateField.END : DateField.START;
        poll.rangeConfirmed = false;
        poll.calendarSince = now;
        poll.calendarMoves = 0;
        poll.clickedAt = 0;
    }

    private boolean reopenDatePage(Poll poll) {
        if (!poll.continuingBaseline() || poll.reopenedDatePage || poll.requestedRange == null) return false;
        // The verified page stays in BaselineProgress. Reload only the date-range attempt.
        poll.reopenedDatePage = true;
        poll.triedRange = poll.rangePriming = poll.rangeRefreshed = poll.rangeConfirmed = false;
        poll.rangeRetries = 0;
        restartRows(poll);
        return true;
    }

    // 日期筛选无法证明待核对的一整秒时，重开无筛选列表，再逐页寻找可独立核对的样本。
    private void fallbackReview(Poll poll, String reason) {
        if (poll.unfilteredReview()) {
            fail(reviewFailure(reason + " · 已尝试逐页核对（" + poll.reviewFallback + "）"));
            return;
        }
        poll.reviewFallback = reason;
        if (poll.triedRange) {
            restartRows(poll);
        } else again(poll);
    }

    private void restartRows(Poll poll) {
        poll.selectingDate = null;
        poll.reviewPageOverlap = false;
        poll.clickedAt = poll.emptySince = poll.controlsSince = poll.pageErrorUntil = 0;
        poll.lastRows = Collections.emptyList();
        poll.displayedRange = null;
        poll.lastText = poll.parseIssue = "";
        poll.pagination = poll.newPagination();
        poll.previous = document;
        poll.document = UUID.randomUUID().toString();
        if (page != null) page.reload();
        again(poll);
    }

    private static boolean selectedRange(Poll poll, JSONObject data) {
        JSONArray dates = data.optJSONArray("dates");
        return dates != null && dates.length() >= 2 && poll.rangeStart.toString().equals(dates.optString(0))
                && poll.rangeEnd.toString().equals(dates.optString(1));
    }

    static Failure reviewFailure(String detail) {
        return new Failure("同秒成交待核对 · " + detail, 12000, false);
    }

    private static List<Rules.Signal> parse(JSONObject data, DateRange range) throws Exception {
        String text = data.getString("text");
        ZoneId zone = ZoneId.of(data.getString("zone"));
        List<Rules.Signal> rows;
        if (range == null) {
            rows = WebRecords.parsePage(text, data.getJSONArray("dates"), zone, LocalDate.now(zone));
        } else {
            if (!range.zone.equals(zone)) throw new IllegalArgumentException("官网成交时区在分页过程中变化");
            rows = WebRecords.parse(text, range.start, range.end, zone);
        }
        int rowCount = data.optInt("rowCount");
        if (rowCount > 0 && rowCount != rows.size()) throw new IllegalArgumentException("官网成交卡片仍未完整加载");
        return rows;
    }

    private void again(Poll poll) { main.postDelayed(() -> inspect(poll), READ_AGAIN); }

    private boolean safe(String address) {
        if (address == null) return false;
        Uri uri = Uri.parse(address);
        return "https".equals(uri.getScheme()) && "www.binance.com".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && (path.equals(uri.getPath()) || (path + "/").equals(uri.getPath()));
    }

    private void timeout(Poll poll) {
        if (poll.positionHistory != null) {
            fail(new Failure("官网仓位历史尚未读到，本轮保留成交记录", 3000, false));
            return;
        }
        if (poll.balances != null) {
            String reason;
            switch (poll.phase) {
                case "navigation": reason = "币安组合页未完成加载"; break;
                case "balance-tab": reason = "官网未显示余额历史或转账记录标签"; break;
                case "select": reason = "官网转账记录标签未打开"; break;
                case "balance-busy": reason = "币安转账记录暂时繁忙"; break;
                case "balance-invalid": reason = "官网划转记录字段尚未完整"; break;
                case "balance-next":
                case "balance-page": reason = "官网转账记录第 " + (poll.savedBalancePage + 1) + " 页未读到"; break;
                default: reason = "官网转账明细尚未读取成功";
            }
            if (poll.balanceZone != null || poll.balanceMargin != null) {
                try { completeBalances(poll, false, reason); }
                catch (Exception invalid) { badSnapshot(invalid); }
            } else fail(new Failure(reason, 3000, false));
            return;
        }
        if (poll.reviewing()) {
            String attempt = poll.unfilteredReview() ? "逐页核对超时 · " + poll.reviewFallback
                    : poll.selectingDate != null ? "官网日历选择超时" : "未读到可确认的完整一秒";
            fail(reviewFailure(attempt));
            return;
        }
        if (!poll.lastRows.isEmpty()) {
            if (poll.parseIssue.isEmpty() && poll.pagination.inFlight()) {
                poll.parseIssue = "第 " + (poll.pagination.pages() + 1) + " 页在本轮时限内未返回";
            }
            complete(poll, false);
            return;
        }
        String reason;
        if (!poll.javascriptResponded) reason = "网页脚本未返回，请检查 Android System WebView";
        else if (poll.selectingDate != null || poll.continuingBaseline()) reason = "币安续读日期或列表未完成加载";
        else if (poll.profile && "profile-wait".equals(poll.phase)) reason = "官网尚未确认该组合，请核对组合 ID 或稍后重试";
        else if ("tab".equals(poll.phase)) reason = "官网未显示最新操作记录标签";
        else if ("select".equals(poll.phase)) reason = "最新操作记录标签未打开";
        else if ("navigation".equals(poll.phase)) reason = "币安网页导航超时";
        else if (poll.parseIssue != null && !poll.parseIssue.isEmpty()) reason = poll.parseIssue;
        else reason = "币安成交列表加载超时";
        if (!poll.javascriptResponded || "navigation".equals(poll.phase)) destroyPage();
        fail(new Failure(reason, 3000, false));
    }

    private void badSnapshot(Exception invalid) {
        fail(new Failure(invalid.getMessage() == null ? "官网记录解析失败" : invalid.getMessage(), 3000, false));
    }

    private void fail(Failure failure) {
        Poll done = current;
        current = null;
        if (page != null) page.stopLoading();
        if (done != null) reject(done, failure);
    }

    private static void reject(Poll poll, Exception failure) {
        if (poll.positionHistory != null) poll.positionHistory.completeExceptionally(failure);
        else if (poll.balances != null) poll.balances.completeExceptionally(failure);
        else poll.result.completeExceptionally(failure);
    }

    private void removeFromParent() {
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
    }

    private void destroyPage() {
        if (page != null) {
            removeFromParent();
            page.destroy();
            page = null;
        }
        document = "";
        manualBaselineDocument = "";
        regionPrompt = false;
        promptViewport = null;
    }

    void releaseIdlePage() {
        main.post(() -> {
            if (current == null && !regionPrompt && promptViewport == null) destroyPage();
        });
    }

    void close() {
        main.post(() -> {
            closed = true;
            fail(new Failure("官网读取已停止", 0, false));
            destroyPage();
            if (mounted == this) mounted = null;
            if (mountedBalance == this) mountedBalance = null;
        });
    }
}
