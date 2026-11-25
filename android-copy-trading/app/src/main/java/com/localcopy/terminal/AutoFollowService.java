package com.localcopy.terminal;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Android 前台服务，负责轮询官网、维护档案、恢复跟单账本并调度账户查询和执行。
 * 行情、仓位、钱包和官网余额各有查询线程。跟单账本的状态变更通过 TRADE_LOCK 协调。
 */
public final class AutoFollowService extends Service {
    static final String WATCH = "watch";
    static final String DEMO_WEB = "testnet-web";
    static final String DRILL = "testnet-drill";
    static final String MANUAL = "testnet-manual";
    static final String REAL = "mainnet-web";
    static final String STOP = "follow-stop";
    static final String PAUSE = "follow-pause";
    static final String RESUME = "follow-resume";
    static final String RESTORE = "follow-restore";
    static final String RETRY_WEB = "follow-retry-web";
    static final String INITIAL_CHOICE = "follow-initial-choice";
    static final String COPY_FACTOR = "follow-copy-factor";
    static final String EXTRA_STRATEGY = "strategy";
    static final String EXTRA_COPY_FACTOR = "copy-factor";
    static final String EXTRA_COPY_SLIPPAGE = "copy-slippage";
    static final String CHANGED = "com.localcopy.terminal.FOLLOW_CHANGED";
    static final String ACCOUNT_CHANGED = "com.localcopy.terminal.ACCOUNT_CHANGED";
    private static final long PERIOD = 3000;
    private static final long ACCOUNT_PERIOD = 1000;
    private static final long BALANCE_PERIOD = 60000;
    private static final long LEADER_RISK_PERIOD = 5000;
    static final long LEADER_MARK_FRESH_MS = 20000;
    static final long LEADER_TIER_FRESH_MS = 300000;
    private static final long MAX_ACTION_AGE = 20000;
    private static final long BASELINE_RETRY_MS = 8000;
    private static final int MAX_BASELINE_STALLS = 3;
    // A baseline can include the initial read, two date windows and independent reviews.
    private static final long CAPTURE_WAKE_TIMEOUT_MS = 390000;
    private static final Object TRADE_LOCK = new Object();
    private static volatile String runningMode;
    private static volatile boolean startRequested;
    private static volatile WaitingPrices prices = new WaitingPrices("", Collections.emptyMap(), Collections.emptyMap(), 0, "");
    private static volatile AccountPositions accountPositions = new AccountPositions("", Collections.emptyMap(), 0, "");
    private static volatile AccountLeverages leverages = new AccountLeverages("", Collections.emptyMap(), 0);
    private static volatile LeaderMarket leaderMarket = LeaderMarket.empty();
    private static volatile WalletSnapshot wallet = new WalletSnapshot("", null, 0, "");
    private static volatile InitialPositions initialPositions;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService priceWorker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService positionWorker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService walletWorker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService balanceWorker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService metadataWorker = Executors.newScheduledThreadPool(2);
    private final ExecutorService accountJournal = Executors.newSingleThreadExecutor();
    private final AtomicReference<ObservedAccount> latestAccount = new AtomicReference<>();
    private final AtomicBoolean copyChecksQueued = new AtomicBoolean();
    private final AtomicBoolean journalQueued = new AtomicBoolean();
    private final AtomicBoolean webRetryQueued = new AtomicBoolean();
    private ScheduledFuture<?> nextCycle;
    private LocalData store;
    private LeaderArchive archive;
    private LeaderBalances balances;
    private ConnectivityManager connectivity;
    private volatile WebsiteCapture source, balanceSource;
    private long balanceFollowingSince;
    private PowerManager.WakeLock awake;
    private volatile String mode;
    private volatile boolean stopped, opens;
    private FollowBook book;
    private volatile FollowExecutor execution;
    private FuturesApi api;
    private FuturesApi market;
    private FuturesApi positionClient, walletClient;
    private FuturesApi leverageClient, leaderMarkClient, leaderTierClient;
    private String positionRealm = "";
    private long accountReadBarrier;
    private volatile Set<String> watchSymbols = Collections.emptySet();
    private Rules.Config initial;
    private String file;
    private String initialChoiceKey;
    private final AtomicReference<InitialSelection> initialSelection = new AtomicReference<>();
    private long reads, misses;
    private long nextBaselineAttempt, nextPositionReview;
    private long nextBaselineReview, baselineReviewBefore = Long.MAX_VALUE;
    private long baselineOldest = Long.MAX_VALUE;
    private int baselineRowsKept, baselineOldestCount, baselineStalls;
    private boolean baselineWaiting;
    private List<ClosedTradeReview.Position> closedPositionHistory = Collections.emptyList();
    private volatile boolean reconciliationPending = true;
    private boolean coldStart;
    private Rules.Strategy requestedStrategy = Rules.Strategy.POINTS;
    private BigDecimal requestedCopyFactor;
    private BigDecimal requestedCopySlippage;
    private volatile long lastVerifiedSnapshot = -1;
    private BigDecimal verifiedLeaderMargin;
    private volatile Set<String> leaderMarketSymbols = Collections.emptySet();
    private long nextLeaderTierRead;

    private static final class ObservedAccount {
        final Map<String, FuturesApi.Position> positions;
        final long started, at;

        ObservedAccount(Map<String, FuturesApi.Position> positions, long started, long at) {
            this.positions = positions;
            this.started = started;
            this.at = at;
        }
    }

    static boolean running() { return runningMode != null || startRequested; }

    static void reserveStart(boolean requested) { startRequested = requested; }

    static boolean usesDemo() { return DEMO_WEB.equals(runningMode) || DRILL.equals(runningMode); }

    static String label(String mode) {
        if (REAL.equals(mode)) return "实盘跟单";
        if (DEMO_WEB.equals(mode)) return "测试网 · 官网信号";
        if (DRILL.equals(mode)) return "测试网 · 自动开平仓演练";
        if (MANUAL.equals(mode)) return "测试网 · 手动演练";
        if (WATCH.equals(mode)) return "官网监测";
        return "自动跟单";
    }

    static String file(String mode) { return "auto-" + mode; }

    static boolean followsWebsite(String mode) { return REAL.equals(mode) || DEMO_WEB.equals(mode); }

    static FollowBook savedFollowBook(LocalData store, String mode, Rules.Config config) throws Exception {
        JSONObject saved = store.load(file(mode));
        if (saved == null) return null;
        FollowBook previous = FollowBook.restore(saved);
        return previous.portfolioSelection == store.portfolioSelection()
                && FollowBook.sameUniverse(previous.config, config) ? previous : null;
    }

    static final class InitialPositions {
        final String mode, portfolio;
        final Rules.Strategy strategy;
        final BigDecimal margin, copyFactor;
        final long head, backfilled;
        final List<Rules.Signal> candidates;
        final int withoutPrice;

        InitialPositions(String mode, Rules.Config config, LeaderArchive.State state, Rules.Strategy strategy,
                BigDecimal margin, BigDecimal copyFactor) {
            this.mode = mode;
            this.strategy = strategy;
            this.margin = margin;
            this.copyFactor = copyFactor;
            portfolio = config.portfolio;
            head = state.head;
            backfilled = state.backfilled;
            List<Rules.Signal> rows = new ArrayList<>();
            int missing = 0;
            for (String symbol : config.symbols) {
                for (Rules.Action action : new Rules.Action[]{Rules.Action.OPEN_LONG, Rules.Action.OPEN_SHORT}) {
                    LeaderHistory.Holding holding = state.holdings.get(symbol + "|" + action.positionSide());
                    if (holding == null || holding.quantity.signum() <= 0) continue;
                    if (holding.entryPrice == null || holding.updatedAt <= 0) {
                        missing++;
                    } else {
                        BigDecimal turnover = strategy == Rules.Strategy.EXPOSURE
                                ? holding.entryPrice.multiply(holding.quantity) : null;
                        rows.add(new Rules.Signal(symbol, action, holding.updatedAt, holding.entryPrice,
                                holding.quantity, null, turnover, margin));
                    }
                }
            }
            candidates = Collections.unmodifiableList(rows);
            withoutPrice = missing;
        }

        boolean canAdd() { return !candidates.isEmpty() && (strategy != Rules.Strategy.EXPOSURE || margin != null); }
    }

    private static final class InitialSelection {
        final long head, backfilled;
        final Set<String> ids;

        InitialSelection(long head, long backfilled, Set<String> ids) {
            this.head = head;
            this.backfilled = backfilled;
            this.ids = ids;
        }
    }

    static InitialPositions initialPositions() { return initialPositions; }

    static final class WaitingPrices {
        final String mode, error;
        final Map<String, Rules.Quote> quotes;
        final Map<String, String> issues;
        final long time;

        WaitingPrices(String mode, Map<String, Rules.Quote> quotes, Map<String, String> issues, long time, String error) {
            this.mode = mode;
            this.quotes = quotes;
            this.issues = issues;
            this.time = time;
            this.error = error;
        }
    }

    static WaitingPrices waitingPrices() { return prices; }

    static final class AccountPositions {
        final String realm, error;
        final Map<String, FuturesApi.Position> positions;
        final long time;

        AccountPositions(String realm, Map<String, FuturesApi.Position> positions, long time, String error) {
            this.realm = realm;
            this.positions = positions;
            this.time = time;
            this.error = error;
        }
    }

    static AccountPositions accountPositions() { return accountPositions; }

    static void publishPositions(String realm, Map<String, FuturesApi.Position> positions) {
        accountPositions = new AccountPositions(realm, positions, System.currentTimeMillis(), "");
    }

    private static final class AccountLeverages {
        final String realm;
        final Map<String, Integer> values;
        final long time;

        AccountLeverages(String realm, Map<String, Integer> values, long time) {
            this.realm = realm;
            this.values = values;
            this.time = time;
        }
    }

    static Map<String, Integer> accountLeverages(String realm) {
        AccountLeverages current = leverages;
        long age = System.currentTimeMillis() - current.time;
        return realm.equals(current.realm) && age >= 0 && age <= 45000 ? current.values : Collections.emptyMap();
    }

    static void publishLeverages(String realm, Map<String, Integer> values) {
        leverages = new AccountLeverages(realm, values, System.currentTimeMillis());
    }

    static final class LeaderMarket {
        final Map<String, BigDecimal> marks, latestPrices;
        final Map<String, List<LeaderRisk.Tier>> tiers;
        final long marksAt, pricesAt, tiersAt;

        LeaderMarket(Map<String, BigDecimal> marks, long marksAt, Map<String, BigDecimal> latestPrices, long pricesAt,
                Map<String, List<LeaderRisk.Tier>> tiers, long tiersAt) {
            this.marks = marks;
            this.marksAt = marksAt;
            this.latestPrices = latestPrices;
            this.pricesAt = pricesAt;
            this.tiers = tiers;
            this.tiersAt = tiersAt;
        }

        static LeaderMarket empty() {
            return new LeaderMarket(Collections.emptyMap(), 0, Collections.emptyMap(), 0, Collections.emptyMap(), 0);
        }

        boolean canUseMarks(long now, LocalData.LeaderBalance balance) {
            return marksAt > 0 && now >= marksAt && now - marksAt <= LEADER_MARK_FRESH_MS
                    && (balance == null || (balance.at > 0 && Math.abs(marksAt - balance.at) <= LEADER_MARK_FRESH_MS));
        }

        boolean hasLatestPrices(long now) {
            return pricesAt > 0 && now >= pricesAt && now - pricesAt <= LEADER_MARK_FRESH_MS;
        }
    }

    static LeaderMarket leaderMarket() { return leaderMarket; }

    static final class WalletSnapshot {
        final String realm, error;
        final FuturesApi.Wallet balance;
        final long time;

        WalletSnapshot(String realm, FuturesApi.Wallet balance, long time, String error) {
            this.realm = realm;
            this.balance = balance;
            this.time = time;
            this.error = error;
        }
    }

    static WalletSnapshot walletSnapshot() { return wallet; }

    static void publishWallet(String realm, FuturesApi.Wallet balance) {
        wallet = new WalletSnapshot(realm, balance, System.currentTimeMillis(), "");
    }

    static boolean validMode(String value) {
        return WATCH.equals(value) || DEMO_WEB.equals(value) || DRILL.equals(value) || REAL.equals(value);
    }

    static void cancelWait(LocalData store, String kind, String id) throws Exception {
        if (!(DEMO_WEB.equals(kind) || DRILL.equals(kind) || REAL.equals(kind))) {
            throw new IllegalArgumentException("该模式没有等待开仓");
        }
        store.cancelWait(kind, id);
        if (!kind.equals(runningMode) && !startRequested) {
            JSONObject saved = store.load(file(kind));
            if (saved != null) {
                FollowBook previous = FollowBook.restore(saved);
                if (previous.cancelWait(id)) store.save(file(kind), previous.json());
            }
            store.acknowledgeWaitCancellations(kind, Collections.singleton(id));
        }
    }

    static boolean hasExposure(LocalData store, String mode) throws Exception {
        JSONObject saved = store.load(file(mode));
        return saved != null && FollowBook.restore(saved).hasExposure();
    }

    static void saveRules(LocalData store, LeaderArchive archive, Rules.Config config) throws Exception {
        synchronized (TRADE_LOCK) {
            Rules.Config before = store.config();
            boolean active = running() || store.prefs.contains("auto_active");
            boolean same = FollowBook.sameUniverse(before, config);
            if (active && !same) {
                throw new IllegalStateException("请先停止自动运行，再修改组合或白名单");
            }
            verifyLeverageChanges(store, before.changedLeverages(config));
            if (!active && !same) releaseOldRules(store, archive, config);
            store.saveConfig(config);
        }
    }

    private static void verifyLeverageChanges(LocalData store, Set<String> symbols) throws Exception {
        if (symbols.isEmpty()) return;
        String active = runningMode == null ? store.prefs.getString("auto_active", "") : runningMode;
        for (String realm : new String[]{FuturesApi.LIVE, FuturesApi.DEMO}) {
            boolean demo = FuturesApi.DEMO.equals(realm);
            if (!active.isEmpty() && demo != (DEMO_WEB.equals(active) || DRILL.equals(active))) continue;
            String[] kinds = demo ? new String[]{DEMO_WEB, DRILL} : new String[]{REAL};
            for (String kind : kinds) {
                JSONObject saved = store.load(file(kind));
                JSONObject pending = saved == null ? null : saved.optJSONObject("pending");
                if (pending != null && symbols.contains(pending.getString("symbol"))) {
                    throw new IllegalStateException(label(kind) + " " + pending.getString("symbol") + " 有待核对委托，杠杆暂不能修改");
                }
            }
            String savedRealm = !demo && !store.prefs.contains("credentials_" + realm) ? FuturesApi.LIVE_READ : realm;
            if (!store.prefs.contains("credentials_" + savedRealm)) continue;
            Map<String, FuturesApi.Position> positions;
            try {
                FuturesApi client = new FuturesApi(demo ? FuturesApi.DEMO : FuturesApi.LIVE_READ, Secrets.load(store, savedRealm));
                positions = client.positions();
            } catch (Exception failed) {
                throw new IllegalStateException((demo ? "测试网" : "实盘") + " 杠杆修改前的持仓核对失败 · " + problem(failed), failed);
            }
            requireFlatLeverage(symbols, positions, demo ? "测试网" : "实盘");
        }
    }

    static void requireFlatLeverage(Set<String> symbols, Map<String, FuturesApi.Position> positions, String environment) {
        for (String symbol : symbols) {
            FuturesApi.Position position = positions.get(symbol);
            if (position != null && !position.flat()) {
                throw new IllegalStateException(environment + " " + symbol + " 当前有仓位，杠杆不能修改");
            }
        }
    }

    private static void releaseOldRules(LocalData store, LeaderArchive archive, Rules.Config config) throws Exception {
        for (String kind : new String[]{REAL, DEMO_WEB, DRILL}) {
            JSONObject saved = store.load(file(kind));
            if (saved == null) continue;
            FollowBook old = FollowBook.restore(saved);
            if (old.hasExposure() && !FollowBook.sameUniverse(old.config, config)) {
                try {
                    String realm = REAL.equals(kind) ? FuturesApi.LIVE : FuturesApi.DEMO;
                    Secrets savedKeys = Secrets.load(store, realm);
                    if (!old.account.equals(FuturesApi.accountFingerprint(savedKeys))) {
                        throw new IllegalStateException("请恢复原账户密钥后核对");
                    }
                    FollowExecutor prior = new FollowExecutor(old, new FuturesApi(realm, savedKeys),
                            () -> store.save(file(kind), old.json()), (id, message, sound) -> {},
                            id -> true, (id, at, message) -> archive.saveAccountFill(old.config.portfolio, kind, id, at, message),
                            DRILL.equals(kind) ? "演练参考价" : "带单员成交均价");
                    prior.releaseFlatAccount();
                } catch (Exception failure) {
                    throw new IllegalStateException(label(kind) + "核对失败 · " + problem(failure), failure);
                }
            }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new LocalData(this);
        archive = LeaderArchive.open(this);
        balances = LeaderBalances.open(this);
        connectivity = getSystemService(ConnectivityManager.class);
        Notices.channels(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (RETRY_WEB.equals(action)) {
            if (stopped || mode == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (webRetryQueued.compareAndSet(false, true)) {
                try { worker.execute(this::retryWebsite); }
                catch (RejectedExecutionException shuttingDown) { webRetryQueued.set(false); }
            }
            return START_STICKY;
        }
        if (INITIAL_CHOICE.equals(action)) {
            InitialPositions preview = initialPositions;
            if (!stopped && mode != null && preview != null && mode.equals(preview.mode)
                    && preview.portfolio.equals(intent.getStringExtra("portfolio"))
                    && preview.head == intent.getLongExtra("head", -1)
                    && preview.backfilled == intent.getLongExtra("backfilled", 0)) {
                String[] selected = intent.getStringArrayExtra("positions");
                if (selected != null) {
                    Set<String> ids = new LinkedHashSet<>(java.util.Arrays.asList(selected));
                    if (ids.isEmpty() || (preview.canAdd() && preview.candidates.stream()
                            .filter(row -> ids.contains(row.id)).count() == ids.size())) {
                        initialSelection.set(new InitialSelection(preview.head, preview.backfilled, ids));
                    }
                }
            }
            if (mode == null || stopped) stopSelf(startId);
            return mode != null && !stopped ? START_STICKY : START_NOT_STICKY;
        }
        if (COPY_FACTOR.equals(action)) {
            if (!stopped && followsWebsite(mode) && mode.equals(intent.getStringExtra("mode"))) {
                String portfolio = intent.getStringExtra("portfolio");
                String factor = intent.getStringExtra(EXTRA_COPY_FACTOR);
                String slippage = intent.getStringExtra(EXTRA_COPY_SLIPPAGE);
                try { accountJournal.execute(() -> changeCopySettings(portfolio, factor, slippage)); }
                catch (RejectedExecutionException shuttingDown) { /* The service is stopping. */ }
            }
            if (mode == null || stopped) stopSelf(startId);
            return mode != null && !stopped ? START_STICKY : START_NOT_STICKY;
        }
        if (STOP.equals(action)) {
            startRequested = false;
            stopped = true;
            store.prefs.edit().remove("auto_active").commit();
            WebsiteCapture browser = source;
            if (browser != null) browser.close();
            if (balanceSource != null) balanceSource.close();
            worker.execute(() -> end("已停止。已发送的委托会在重新启动后核对。"));
            return START_NOT_STICKY;
        }
        if (PAUSE.equals(action) || RESUME.equals(action)) {
            if (mode != null && !stopped) {
                opens = RESUME.equals(action);
                store.prefs.edit().putBoolean("auto_opens", opens).apply();
                status(opens ? "已恢复开仓" : "已暂停开仓，继续处理平仓");
                Notices.channels(this).notify(Notices.AUTO_ID, Notices.autoStatus(this,
                        label(mode) + (opens ? " · 后台运行中" : " · 已暂停开仓")));
            } else stopSelf(startId);
            return mode != null && !stopped ? START_STICKY : START_NOT_STICKY;
        }
        if (mode != null) return START_STICKY;
        boolean restoring = action == null || RESTORE.equals(action);
        String requested = restoring ? store.prefs.getString("auto_active", "") : action;
        if (!validMode(requested)) {
            startRequested = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (restoring && stoppedByUser()) {
            startRequested = false;
            store.prefs.edit().remove("auto_active").putString("auto_status", "系统报告应用已被用户停止").apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            if (!restoring && followsWebsite(requested)) {
                requestedStrategy = Rules.Strategy.read(intent.getStringExtra(EXTRA_STRATEGY));
                String value = intent.getStringExtra(EXTRA_COPY_FACTOR);
                if (value != null && requestedStrategy == Rules.Strategy.EXPOSURE) requestedCopyFactor = Rules.copyFactor(value);
                String limit = intent.getStringExtra(EXTRA_COPY_SLIPPAGE);
                if (limit != null && requestedStrategy == Rules.Strategy.EXPOSURE) {
                    requestedCopySlippage = Rules.adjustmentSlippage(limit);
                }
            }
        } catch (IllegalArgumentException invalid) {
            startRequested = false;
            store.prefs.edit().putString("auto_status", problem(invalid)).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        mode = requested;
        initialPositions = null;
        coldStart = !restoring;
        opens = coldStart || store.prefs.getBoolean("auto_opens", true);
        try {
            startForeground(Notices.AUTO_ID, Notices.autoStatus(this,
                    label(mode) + (opens ? " · 后台运行中" : " · 已暂停开仓")), foregroundType(Build.VERSION.SDK_INT));
        } catch (RuntimeException blocked) {
            mode = null;
            startRequested = false;
            store.prefs.edit().putString("auto_status", "系统未允许启动后台服务，请检查「设置 → 后台与通知」").apply();
            changed();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        runningMode = mode;
        startRequested = false;
        worker.execute(this::begin);
        return START_STICKY;
    }

    static int foregroundType(int sdk) {
        return sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    private void begin() {
        try {
            if (stopped) return;
            initial = store.config();
            file = file(mode);
            String fingerprint = "";
            String positionError = "";
            Secrets liveRead = null;
            if (!WATCH.equals(mode)) {
                ReleaseIntegrity.requireTrustedPackage(this);
                String realm = REAL.equals(mode) ? FuturesApi.LIVE : FuturesApi.DEMO;
                if (FuturesApi.DEMO.equals(realm) && hasExposure(store, DRILL.equals(mode) ? DEMO_WEB : DRILL)) {
                    throw new IllegalStateException("测试网有其他演练的仓位或待核对委托，请先结束原演练");
                }
                Secrets secrets = Secrets.load(store, realm);
                fingerprint = FuturesApi.accountFingerprint(secrets);
                api = new FuturesApi(realm, secrets);
                if (FuturesApi.DEMO.equals(realm)) DemoOrders.releaseFlatJournal(store, api, fingerprint);
                market = new FuturesApi(realm, null);
                positionRealm = realm;
                positionClient = new FuturesApi(realm, secrets);
                walletClient = new FuturesApi(realm, secrets);
                leverageClient = new FuturesApi(realm, secrets);
                if (REAL.equals(mode)) liveRead = secrets;
            } else {
                positionRealm = FuturesApi.LIVE;
                try {
                    String savedRealm = store.prefs.contains("credentials_" + FuturesApi.LIVE_READ)
                            ? FuturesApi.LIVE_READ : FuturesApi.LIVE;
                    if (store.prefs.contains("credentials_" + savedRealm)) {
                        Secrets secrets = Secrets.load(store, savedRealm);
                        positionClient = new FuturesApi(FuturesApi.LIVE_READ, secrets);
                        walletClient = new FuturesApi(FuturesApi.LIVE_READ, secrets);
                        leverageClient = new FuturesApi(FuturesApi.LIVE_READ, secrets);
                        liveRead = secrets;
                    }
                } catch (Exception keyProblem) {
                    positionError = problem(keyProblem);
                }
            }
            JSONObject saved = store.load(file);
            FollowBook previous = saved == null ? null : FollowBook.restore(saved);
            long selection = store.portfolioSelection();
            boolean sameSelection = previous != null && previous.portfolioSelection == selection;
            if (previous != null && previous.hasExposure()
                    && (!sameSelection || !FollowBook.sameUniverse(previous.config, initial) || !fingerprint.equals(previous.account))) {
                throw new IllegalStateException("请用原账户和规则继续核对已有仓位和订单");
            }
            boolean resume = sameSelection && (!coldStart || previous.hasWork() || WATCH.equals(mode))
                    && FollowBook.sameUniverse(previous.config, initial) && fingerprint.equals(previous.account);
            if (resume && coldStart && followsWebsite(mode) && requestedStrategy != previous.strategy) {
                throw new IllegalStateException("请以「" + previous.strategy.label + "」继续核对已有跟单");
            }
            book = resume ? previous : new FollowBook(initial, fingerprint, requestedStrategy);
            book.portfolioSelection = selection;
            if (requestedCopyFactor != null) {
                BigDecimal limit = requestedCopySlippage == null ? book.copySlippage : requestedCopySlippage;
                if (resume) book.queueCopySettings(requestedCopyFactor, limit, System.currentTimeMillis());
                else {
                    book.copyFactor = requestedCopyFactor;
                    book.copySlippage = limit;
                }
            }
            if (resume && coldStart && followsWebsite(mode)) book.reseedIdlePositions();
            if (!resume && previous != null && previous.config.portfolio.equals(initial.portfolio)) {
                book.log = previous.log;
                book.notice = previous.notice;
                book.detections = previous.detections;
            }
            if (followsWebsite(mode)) {
                initialChoiceKey = "initial_choice_" + mode + "_" + initial.portfolio + "_" + fingerprint
                        + (book.strategy == Rules.Strategy.EXPOSURE ? "_exposure" : "")
                        + (selection == 0 ? "" : "_selection_" + selection);
                boolean matched = sameSelection && previous.config.portfolio.equals(initial.portfolio)
                        && previous.account.equals(fingerprint) && previous.strategy == book.strategy;
                book.initialChoiceDone = store.prefs.getBoolean(initialChoiceKey, false)
                        || (matched && previous.initialChoiceDone);
                if (book.initialChoiceDone) store.prefs.edit().putBoolean(initialChoiceKey, true).apply();
            }
            if (api != null) execution = new FollowExecutor(book, api, () -> store.save(file, book.json()),
                    (id, message, sound) -> Notices.trade(this, id, label(mode), message, sound),
                    id -> !store.waitCancellations(mode).contains(id),
                    (id, at, text) -> archive.saveAccountFill(initial.portfolio, mode, id, at, text),
                    DRILL.equals(mode) ? "演练参考价" : "带单员成交均价", !DRILL.equals(mode));
            if (execution != null && followsWebsite(mode)) {
                execution.prepareCopyAdjustmentsWith(api::independentReader, this::wakeCopyChecks);
                if (stopped) execution.closeCopyChecks();
            }
            if (WATCH.equals(mode)) {
                for (FollowBook.Leg leg : book.legs.values()) leg.entries.clear();
            }
            if (stopped) return;
            if (!DRILL.equals(mode)) {
                source = new WebsiteCapture(this, initial.portfolio);
                migrateLegacy(previous);
                LeaderArchive.State state = archive.state(initial.portfolio);
                leaderMarketSymbols = archive.projection(initial.portfolio, state).openSymbols();
                LocalData.LeaderBalance previousBalance = store.leaderBalance(initial.portfolio);
                if (previousBalance != null) {
                    store.rememberLeaderBalance(initial.portfolio, previousBalance.value, previousBalance.at);
                }
                leaderMarkClient = new FuturesApi(FuturesApi.LIVE_READ, null);
                if (DEMO_WEB.equals(mode)) {
                    String savedRealm = store.prefs.contains("credentials_" + FuturesApi.LIVE_READ)
                            ? FuturesApi.LIVE_READ : FuturesApi.LIVE;
                    try {
                        if (store.prefs.contains("credentials_" + savedRealm)) liveRead = Secrets.load(store, savedRealm);
                    } catch (Exception unavailable) {
                        // The estimate can use the reference maintenance rate while testnet continues.
                    }
                }
                if (liveRead != null) leaderTierClient = new FuturesApi(FuturesApi.LIVE_READ, liveRead);
            }
            if (book.trackingSince == 0) book.trackingSince = System.currentTimeMillis();
            persist();
            if (!store.prefs.edit().putString("auto_active", mode).putString("auto_last_mode", mode)
                    .putString("auto_portfolio", initial.portfolio)
                    .putLong("auto_portfolio_selection", book.portfolioSelection)
                    .putBoolean("auto_opens", opens).putLong("auto_started", System.currentTimeMillis())
                    .putString("auto_capture", "").remove("auto_capture_error").remove("auto_web_access")
                    .remove("auto_baseline_waiting")
                    .remove("auto_leader_margin").remove("auto_leader_margin_at").commit()) {
                throw new IllegalStateException("无法保存后台运行状态");
            }
            PowerManager power = getSystemService(PowerManager.class);
            awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "copyterminal:follow");
            awake.setReferenceCounted(false);
            prices = new WaitingPrices(mode, Collections.emptyMap(), Collections.emptyMap(), 0, "");
            accountPositions = new AccountPositions(positionRealm, Collections.emptyMap(), 0, positionError);
            leverages = new AccountLeverages(positionRealm, Collections.emptyMap(), 0);
            wallet = new WalletSnapshot(positionRealm, null, 0, positionError);
            leaderMarket = LeaderMarket.empty();
            if (market != null) priceWorker.execute(this::priceCycle);
            if (positionClient != null) positionWorker.execute(this::positionCycle);
            if (walletClient != null) walletWorker.execute(this::walletCycle);
            if (leverageClient != null) metadataWorker.execute(this::leverageCycle);
            if (leaderMarkClient != null) metadataWorker.execute(this::leaderMarketCycle);
            if (!DRILL.equals(mode)) {
                String key = "balance_since_" + initial.portfolio;
                long savedSince = coldStart ? 0 : store.prefs.getLong(key, 0);
                balanceFollowingSince = savedSince == 0 ? System.currentTimeMillis() : savedSince;
                store.prefs.edit().putLong(key, balanceFollowingSince).apply();
                balanceWorker.execute(this::leaderBalanceCycle);
            }
            cycle();
        } catch (Exception failure) { end(problem(failure)); }
    }

    // 官网读取可能卡在分页或暂时不可用。每轮先处理已提交委托，再决定是否继续读网页或做测试网演练。
    private void cycle() {
        nextCycle = null;
        if (stopped) return;
        long began = SystemClock.elapsedRealtime();
        long backoff = 0;
        try {
            awake.acquire(CAPTURE_WAKE_TIMEOUT_MS);
            if (!networkAvailable()) {
                lastVerifiedSnapshot = -1;
                reconciliationPending = true;
                status("手机网络暂不可用，恢复后先核对委托和官网成交");
                nextCycle = worker.schedule(this::cycle, PERIOD, TimeUnit.MILLISECONDS);
                return;
            }
            synchronized (TRADE_LOCK) {
                applyCancellations();
                if (!baselineWaiting) status(DRILL.equals(mode) ? "正在核对测试网" : "正在刷新官网成交记录");
                if (execution != null) execution.flushNotice();
                if (book.pending != null) {
                    try { status(execution.reconcile()); }
                    finally { accountReadBarrier = SystemClock.elapsedRealtimeNanos(); }
                }
                if (!stopped && DRILL.equals(mode) && book.pending == null) drill();
            }
            if (baselineWaiting) backoff = BASELINE_RETRY_MS;
            else if (!stopped && !DRILL.equals(mode)) captureAndFollow();
        } catch (WebsiteCapture.Failure failure) {
            if (stopped) return;
            lastVerifiedSnapshot = -1;
            reconciliationPending = true;
            misses++;
            android.content.SharedPreferences.Editor update = store.prefs.edit().putString("auto_capture_error",
                    TradeNotice.time(System.currentTimeMillis()) + " · " + failure.getMessage());
            if (failure.access != WebsiteCapture.Access.NONE) update.putString("auto_web_access", failure.access.name());
            update.apply();
            if (failure.stop) { end(failure.getMessage()); return; }
            status(failure.getMessage());
            if (failure.access.waitsForRetry()) {
                if (awake.isHeld()) awake.release();
                return;
            }
            backoff = failure.backoffMs;
        } catch (FollowExecutor.JournalFailure fatal) {
            end(fatal.getMessage());
            return;
        } catch (FuturesApi.Failure account) {
            if (account.status == 401 || account.code == -2015 || account.code == -1022) {
                end("请核对币安 API 密钥及合约权限 · " + account.getMessage());
                return;
            }
            status(account.getMessage());
        } catch (Exception transientFailure) {
            if (stopped) return;
            String detail = problem(transientFailure);
            if (!DRILL.equals(mode)) {
                lastVerifiedSnapshot = -1;
                reconciliationPending = true;
                misses++;
                store.prefs.edit().putString("auto_capture_error",
                        TradeNotice.time(System.currentTimeMillis()) + " · " + detail).apply();
            }
            status(detail);
            backoff = 3000;
        }
        if (!stopped) {
            if (baselineWaiting && awake.isHeld()) awake.release();
            updateWatchSymbols();
            long delay = Math.max(backoff, nextDelay(SystemClock.elapsedRealtime() - began));
            nextCycle = worker.schedule(this::cycle, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void retryWebsite() {
        try {
            if (stopped || source == null || (!baselineWaiting && store.prefs.getString("auto_web_access", "").isEmpty())) return;
            if (nextCycle != null) nextCycle.cancel(false);
            baselineWaiting = false;
            baselineStalls = 0;
            store.prefs.edit().remove("auto_baseline_waiting").apply();
            nextBaselineAttempt = 0;
            cycle();
        } finally {
            webRetryQueued.set(false);
        }
    }

    /**
     * 从档案的最新成交出发读网页。首次、切换组合或旧基线不完整时展开历史，
     * 日常轮询只需接上水位。能确认分页结果时才启动成交复核，之后在锁内入档和消费。
     */
    private void captureAndFollow() throws Exception {
        LeaderArchive.State before = archive.state(initial.portfolio);
        boolean switched = needsSwitchBaseline();
        synchronized (TRADE_LOCK) {
            if (!switched && !before.window.isEmpty() && book.portfolioSelection == 0 && !book.baselineReady
                    && book.window.isEmpty() && before.head > 0) establishBookBaseline(before);
        }
        boolean archivedEmpty = before.head > 0 && before.window.isEmpty();
        boolean expand = switched || !before.historyComplete || archivedEmpty;
        long now = SystemClock.elapsedRealtime();
        if (expand && now < nextBaselineAttempt) {
            throw new WebsiteCapture.Failure("等待补齐官网基线", nextBaselineAttempt - now, false);
        }
        if (expand) {
            if (baselineRowsKept > 0) status("正在继续核对官网基线 · 已保留 " + baselineRowsKept + " 笔");
            else status(switched || before.head == 0 ? "正在逐页建立本次跟单基线" : "正在展开官网历史以补录旧基线");
        }
        WebsiteCapture.Read read;
        try {
            LeaderArchive.State reference = before;
            read = source.fetch(switched ? null : reference.latest(),
                    rows -> archive.connectsToTail(initial.portfolio, reference, rows), expand,
                    switched || !reference.historyComplete || archive.needsEmptyRecheck(initial.portfolio, reference));
        }
        finally {
            if (expand) nextBaselineAttempt = SystemClock.elapsedRealtime() + BASELINE_RETRY_MS;
        }
        long detected = System.currentTimeMillis();
        if (before.historyComplete && (!expand || read.historyComplete)) {
            recheckArchivedBaseline(initial.portfolio);
            before = archive.state(initial.portfolio);
        }
        if ((expand && read.historyComplete) || (!expand && (read.recoveryDetail.isEmpty() || read.recovered))) {
            read = verifyWebsiteRows(read, switched);
            before = archive.state(initial.portfolio);
        }
        synchronized (TRADE_LOCK) {
            if (stopped) return;
            acceptCapture(before, read, read.snapshotAt, detected);
        }
    }

    private boolean needsSwitchBaseline() {
        return book.portfolioSelection > 0 && book.portfolioSelection != archive.verifiedSelection(initial.portfolio);
    }

    // 每轮最多独立复核两组同秒成交。未完成的组留在 SQLite，下一轮继续复核后再接纳本批记录。
    private WebsiteCapture.Read verifyWebsiteRows(WebsiteCapture.Read read, boolean switched) throws Exception {
        if (read.rows.isEmpty()) return read;
        String portfolio = initial.portfolio;
        reviewClosedPositions(portfolio, read.rows, read.historyComplete);
        read = read.withRows(archive.normalizeReviewedDuplicates(portfolio, read.rows, read.leaderMargin));
        archive.queueReviews(portfolio, TradeReview.repeatedSeconds(read.rows));
        long since = switched ? Long.MAX_VALUE : archive.currentSegmentTime(portfolio);
        if (since == Long.MAX_VALUE && !read.rows.isEmpty()) since = read.rows.get(0).time;
        LeaderArchive.Review review = archive.nextReview(portfolio, since);
        Map<Long, List<Rules.Signal>> justChecked = new LinkedHashMap<>();
        for (int checked = 0; review != null && checked < 2; checked++) {
            initialPositions = null;
            status("正在复核同秒成交 · " + TradeNotice.fullTime(review.stamp));
            List<Rules.Signal> official = source.review(review.stamp);
            synchronized (TRADE_LOCK) {
                if (stopped) throw WebsiteCapture.reviewFailure("本轮跟踪已停止");
                if (archive.resolveReview(portfolio, review, official)) {
                    lastVerifiedSnapshot = -1;
                    initialSelection.set(null);
                    changed();
                }
            }
            justChecked.put(review.stamp, official);
            review = archive.nextReview(portfolio, since);
        }
        if (review != null) throw WebsiteCapture.reviewFailure("还有其他成交等待独立读取");
        return read.withRows(archive.verifiedRows(portfolio, read.rows, read.leaderMargin, justChecked));
    }

    private void recheckArchivedBaseline(String portfolio) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (now < nextBaselineReview) return;
        nextBaselineReview = now + 60000;
        LeaderArchive.Review review = archive.nextBaselineReview(portfolio, baselineReviewBefore);
        if (review == null) {
            baselineReviewBefore = Long.MAX_VALUE;
            return;
        }
        // One older second per pass. Rotate past an unavailable second so the other reviews can proceed.
        baselineReviewBefore = review.stamp;
        try {
            status("正在复核已建档成交 · " + TradeNotice.fullTime(review.stamp));
            List<Rules.Signal> checked = source.review(review.stamp);
            synchronized (TRADE_LOCK) {
                if (stopped) return;
                if (archive.resolveBaselineReview(portfolio, review, checked)) {
                    lastVerifiedSnapshot = -1;
                    initialSelection.set(null);
                    initialPositions = null;
                    changed();
                }
            }
        } catch (WebsiteCapture.Failure unavailable) {
            if (unavailable.stop || unavailable.access != WebsiteCapture.Access.NONE || unavailable.backoffMs > 12000) {
                throw unavailable;
            }
        }
    }

    private void reviewClosedPositions(String portfolio, List<Rules.Signal> rows, boolean fullHistory) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (now < nextPositionReview) return;
        boolean checkDirection = OneWayTrades.hasSideReports(rows) && (fullHistory
                || !archive.resolveTradeSides(portfolio, archive.state(portfolio), rows, closedPositionHistory).pending.isEmpty());
        if (!checkDirection && TradeReview.repeatedSeconds(rows).isEmpty() && !archive.hasArchivedRepeats(portfolio)) return;
        nextPositionReview = now + 60000;
        List<ClosedTradeReview.Position> positions;
        try {
            status(checkDirection ? "正在用官网仓位历史复核开平方向" : "正在用官网仓位历史复核重复成交");
            positions = source.closedPositions();
        } catch (WebsiteCapture.Failure unavailable) {
            if (unavailable.stop || unavailable.access != WebsiteCapture.Access.NONE || unavailable.backoffMs > 3000) {
                throw unavailable;
            }
            return;
        }
        closedPositionHistory = positions;
        synchronized (TRADE_LOCK) {
            if (stopped) return;
            if (archive.confirmClosedPositionReviews(portfolio, rows, positions) > 0) {
                lastVerifiedSnapshot = -1;
                initialSelection.set(null);
                initialPositions = null;
                changed();
            }
        }
    }

    /**
     * 先确认基线、连续性和方向，再更新档案与账本。基线连续多轮没有进展时转为手动续读，
     * 避免不断点击官网。档案有缺口、白名单新增成交开平不明或仍有委托待核对时，暂停新委托。
     */
    private void acceptCapture(LeaderArchive.State before, WebsiteCapture.Read read, long received, long detected) throws Exception {
        // Completion callbacks may use a confirmed snapshot only after this capture has fully settled.
        lastVerifiedSnapshot = -1;
        boolean switched = needsSwitchBaseline();
        boolean archivedEmpty = before.head > 0 && before.window.isEmpty();
        if ((switched || !before.historyComplete || archivedEmpty) && !read.historyComplete) {
            initialPositions = null;
            store.prefs.edit().remove("auto_web_access").apply();
            String detail = read.historyDetail.isEmpty() ? "官网基线仍在展开" : read.historyDetail;
            noteBaselineRows(read.rows);
            if (baselineStalls >= MAX_BASELINE_STALLS) {
                baselineWaiting = true;
                store.prefs.edit().putBoolean("auto_baseline_waiting", true).apply();
                detail += " · 连续 " + baselineStalls + " 轮未读到更早成交，请点「重试官网读取」继续";
            } else {
                detail += " · 无进展 " + baselineStalls + "/" + MAX_BASELINE_STALLS + " 轮 · "
                        + BASELINE_RETRY_MS / 1000 + " 秒后续读";
            }
            if (!before.historyComplete) archive.noteBaselineProgress(initial.portfolio, read.name, detail);
            throw new WebsiteCapture.Failure(detail, BASELINE_RETRY_MS, false);
        }
        if (!read.recoveryDetail.isEmpty() && !read.recovered) {
            archive.observeGap(initial.portfolio, read.name, read.rows, detected);
            initialPositions = null;
            throw new WebsiteCapture.Failure(read.recoveryDetail + " · 等待衔接档案后恢复跟单", 6000, false);
        }
        if (OneWayTrades.hasSideReports(read.rows)) {
            LeaderArchive.TradeSides sides = archive.resolveTradeSides(initial.portfolio, before, read.rows, closedPositionHistory);
            read = read.withRows(sides.rows);
            if (execution != null && before.historyComplete && !before.window.isEmpty() && !switched) {
                for (String symbol : sides.pending) {
                    if (!book.config.symbols.contains(symbol)) continue;
                    archive.observeGap(initial.portfolio, read.name, read.rows, detected);
                    initialPositions = null;
                    throw new WebsiteCapture.Failure(symbol + " 官网买卖方向已记录 · 开平仓待核对，暂停本轮自动委托", 6000, false);
                }
            }
        }
        boolean recover = before.head > 0 && (reconciliationPending || read.recovered || before.gap);
        LeaderArchive.Update update;
        try {
            update = archive.capture(initial.portfolio, read.name, read.rows, detected,
                    read.historyComplete, read.historyDetail, recover, book.portfolioSelection);
        } catch (LeaderHistory.Gap conflict) {
            throw new WebsiteCapture.Failure(conflict.getMessage(), 6000, false);
        }
        leaderMarketSymbols = archive.projection(initial.portfolio, update.state).openSymbols();
        if (read.profileMargin != null) store.rememberLeaderBalance(initial.portfolio, read.profileMargin, detected);
        if (read.leaderMargin != null) {
            store.prefs.edit().putString("auto_leader_margin", Rules.show(read.leaderMargin))
                    .putLong("auto_leader_margin_at", detected).apply();
        }
        if (!update.continuous) {
            initialPositions = null;
            String detail = read.recoveryDetail.isEmpty() ? "" : read.recoveryDetail + " · ";
            throw new WebsiteCapture.Failure(detail + "可见成交已存档，尚未核对到档案水位，暂停自动委托", 6000, false);
        }
        if (!update.state.historyComplete) {
            initialPositions = null;
            throw new WebsiteCapture.Failure(update.state.historyDetail, BASELINE_RETRY_MS, false);
        }
        if (source != null) source.baselineAccepted();
        nextBaselineAttempt = 0;
        baselineOldest = Long.MAX_VALUE;
        baselineRowsKept = baselineOldestCount = baselineStalls = 0;
        if (update.baseline || update.added > 0) changed();
        applyCancellations();
        reads++;
        LeaderArchive.State current = update.state;
        if (book.pending == null && execution != null
                && (!book.accountSeeded || recover || book.copyReplanPending || book.archiveCursor < current.recoveryHead)) {
            long checked = System.currentTimeMillis();
            try { execution.refreshAccount(checked); }
            finally { accountReadBarrier = SystemClock.elapsedRealtimeNanos(); }
        }
        if (book.pending == null) {
            if (archivedEmpty && update.baseline && !current.window.isEmpty() && !book.hasWork()) {
                establishBookBaseline(current);
                if (initialChoiceKey != null) {
                    book.initialChoiceDone = false;
                    store.prefs.edit().remove(initialChoiceKey).apply();
                    persist();
                }
            } else syncBook(current);
            boolean updated = book.enrichWaiting(current.window);
            updated |= book.supplyAdjustmentMargin(read.leaderMargin);
            if (updated) persist();
            reconciliationPending = false;
        }
        if (book.pending == null) {
            verifiedLeaderMargin = read.leaderMargin;
            lastVerifiedSnapshot = received;
        } else lastVerifiedSnapshot = -1;
        updateWatchSymbols();
        Rules.Signal last = current.latest();
        String now = TradeNotice.time(System.currentTimeMillis());
        store.prefs.edit().putString("auto_capture", now + " · 读到 " + read.rows.size() + " 笔"
                + " · 新增 " + update.added + (last == null ? "\n官网目前无成交" : "\n最新 " + TradeNotice.time(last.time) + " "
                + last.symbol + " " + last.action.label + " " + Rules.show(last.quantity)
                + " " + Rules.base(last.symbol) + " · " + TradeNotice.price(last.price) + " USDT"
                + (last.action.opens ? "" : " · " + TradeNotice.pnl(last.realizedPnl)))
                + (read.recoveryDetail.isEmpty() ? "" : "\n" + read.recoveryDetail))
                .remove("auto_capture_error").remove("auto_web_access").apply();
        if (book.pending != null) {
            status("官网成交已归档 · 账户委托仍待核对");
        } else if (execution == null) {
            status(update.baseline ? "已建立官网基线" : "官网记录已核对");
        } else if (read.historyComplete && !valid(received)) {
            initialPositions = null;
            status("官网基线已建档 · 等待核对最新成交");
        } else if (waitingForInitialChoice() && current.head > 0 && current.window.isEmpty()) {
            status("官网当前无可见成交，保留旧档案并等待记录");
        } else if (waitingForInitialChoice() && !applyInitialChoice(current, read.leaderMargin)) {
            status("等待选择如何处理已有仓位");
        } else {
            if (valid(received) && book.prepareRequestedCopy(current.holdings, read.leaderMargin)) {
                persist();
                updateWatchSymbols();
            }
            Rules.Config currentRules = store.config();
            try { status(execution.decide(currentRules, () -> valid(received), () -> openingAllowed(currentRules))); }
            finally { accountReadBarrier = SystemClock.elapsedRealtimeNanos(); }
        }
    }

    private boolean waitingForInitialChoice() { return initialChoiceKey != null && !book.initialChoiceDone; }

    private void noteBaselineRows(List<Rules.Signal> rows) {
        baselineRowsKept = rows.size();
        if (!rows.isEmpty()) {
            long oldest = rows.get(0).time;
            int count = TradeReview.at(rows, oldest).size();
            if (oldest < baselineOldest || (oldest == baselineOldest && count > baselineOldestCount)) {
                baselineOldest = oldest;
                baselineOldestCount = count;
                baselineStalls = 0;
                return;
            }
        }
        baselineStalls++;
    }

    private boolean applyInitialChoice(LeaderArchive.State state, BigDecimal margin) throws Exception {
        InitialPositions next = new InitialPositions(mode, initial, state, book.strategy, margin, book.copyFactor);
        InitialSelection selection = initialSelection.getAndSet(null);
        boolean noPositions = next.candidates.isEmpty() && next.withoutPrice == 0;
        List<Rules.Signal> entries = new ArrayList<>();
        if (selection != null) for (Rules.Signal candidate : next.candidates) {
            if (selection.ids.contains(candidate.id)) entries.add(candidate);
        }
        if (!noPositions && (selection == null || (!selection.ids.isEmpty()
                && (selection.head != next.head || selection.backfilled != next.backfilled || !next.canAdd()
                    || entries.size() != selection.ids.size())))) {
            initialPositions = next;
            return false;
        }
        int accepted = book.finishInitialChoice(entries);
        if (accepted > 0) book.log("首次跟单 · " + accepted + " 个已有仓位已加入等待");
        if (accepted < entries.size()) book.log("首次跟单 · 本账户启动前已有同方向仓位，保留至带单员该方向新增开仓");
        persist();
        store.prefs.edit().putBoolean(initialChoiceKey, true).apply();
        initialPositions = null;
        updateWatchSymbols();
        return true;
    }

    private void migrateLegacy(FollowBook modeBook) throws Exception {
        if (archive.state(initial.portfolio).head != 0) return;
        FollowBook previous = modeBook != null && modeBook.config.portfolio.equals(initial.portfolio)
                && modeBook.latest() != null ? modeBook : null;
        for (String other : new String[]{REAL, DEMO_WEB, WATCH}) {
            JSONObject saved = store.load(file(other));
            if (saved == null) continue;
            FollowBook candidate = FollowBook.restore(saved);
            if (candidate.latest() != null && candidate.config.portfolio.equals(initial.portfolio)
                    && (previous == null || candidate.latest().time < previous.latest().time)) previous = candidate;
        }
        if (previous != null) archive.migrate(initial.portfolio, previous.window, previous.knownPositions(),
                System.currentTimeMillis());
    }

    private void establishBookBaseline(LeaderArchive.State state) throws Exception {
        book.seedPositions(state.estimate);
        book.window = state.window;
        book.archiveCursor = state.head;
        book.baselineBackfilled = state.backfilled;
        book.archiveRevision = archive.revision(initial.portfolio);
        book.baselineReady = true;
        persist();
    }

    /**
     * 按档案序号推进持久化账本。补录和历史修订只在原水位重算仓位，恢复段按净仓位变化跟单，
     * 避免把离线期间已完成的开平重新执行。恢复段之后的新增成交再逐笔处理。
     */
    private void syncBook(LeaderArchive.State state) throws Exception {
        if (!book.baselineReady && book.window.isEmpty()) {
            establishBookBaseline(state);
            return;
        }
        long cursor = book.archiveCursor;
        if (cursor == 0 && !book.window.isEmpty()) cursor = archive.sequenceOf(initial.portfolio, book.anchor());
        if ((cursor == 0 && !book.window.isEmpty()) || cursor > state.head || book.baselineBackfilled > state.backfilled) {
            throw new WebsiteCapture.Failure("旧跟单账本与档案没有共同锚点，暂停发单", 6000, false);
        }
        long archiveRevision = archive.revision(initial.portfolio);
        boolean revised = book.archiveRevision < archiveRevision;
        boolean merged = book.baselineBackfilled < state.backfilled || revised;
        if (merged) {
            Map<String, BigDecimal> oldModel = archive.visibleAt(initial.portfolio, cursor, book.baselineBackfilled, book.archiveRevision);
            Map<String, BigDecimal> newModel = archive.visibleAt(initial.portfolio, cursor, state.backfilled, archiveRevision);
            book.rebaseLeaderPositions(oldModel, newModel);
            if (revised) book.removeStaleInitialEntries(oldModel, newModel);
            book.baselineBackfilled = state.backfilled;
            book.archiveRevision = archiveRevision;
        }
        if (!merged && cursor == state.head && book.archiveCursor == cursor) return;
        if (cursor < state.recoveryHead) {
            List<Rules.Signal> recovered = new ArrayList<>();
            Map<String, Long> detected = new LinkedHashMap<>();
            while (cursor < state.recoveryHead) {
                List<LeaderArchive.Record> page = archive.after(initial.portfolio, cursor, state.recoveryHead);
                if (page.isEmpty()) throw new IllegalStateException("成交档案序号不连续");
                for (LeaderArchive.Record row : page) {
                    if (row.ordinal != ++cursor) throw new IllegalStateException("成交档案序号不连续");
                    if (row.excluded) continue;
                    recovered.add(row.signal);
                    detected.put(row.signal.id, row.detectedAt);
                }
            }
            Map<String, LeaderHistory.Holding> holdings = cursor == state.head
                    ? state.holdings : archive.holdingsAt(initial.portfolio, cursor);
            book.acceptRecovered(recovered, execution != null && !waitingForInitialChoice(), detected, holdings);
        }
        while (cursor < state.head) {
            List<LeaderArchive.Record> page = archive.after(initial.portfolio, cursor, state.head);
            if (page.isEmpty()) throw new IllegalStateException("成交档案序号不连续");
            List<Rules.Signal> rows = new ArrayList<>();
            Map<String, Long> detected = new LinkedHashMap<>();
            for (LeaderArchive.Record row : page) {
                if (row.ordinal != ++cursor) throw new IllegalStateException("成交档案序号不连续");
                if (row.excluded) continue;
                rows.add(row.signal);
                detected.put(row.signal.id, row.detectedAt);
            }
            book.acceptNew(rows, execution != null && !waitingForInitialChoice(), detected, 0);
            book.archiveCursor = cursor;
        }
        book.window = state.window;
        book.archiveCursor = cursor;
        persist();
    }

    private void applyCancellations() throws Exception {
        Set<String> queued = store.waitCancellations(mode);
        if (queued.isEmpty()) return;
        boolean changed = false;
        for (String id : queued) if (book.cancelWait(id)) changed = true;
        if (changed) persist();
        store.acknowledgeWaitCancellations(mode, queued);
        updateWatchSymbols();
        if (changed) changed();
    }

    private void updateWatchSymbols() {
        synchronized (TRADE_LOCK) {
            Set<String> symbols = new LinkedHashSet<>();
            for (FollowBook.Entry entry : book.waits()) symbols.add(entry.signal.symbol);
            for (FollowBook.Leg leg : book.legs.values()) if (leg.adjustment != null) symbols.add(leg.symbol);
            watchSymbols = Collections.unmodifiableSet(symbols);
        }
    }

    private void changeCopySettings(String portfolio, String value, String slippage) {
        try {
            synchronized (TRADE_LOCK) {
                if (stopped || book == null || book.strategy != Rules.Strategy.EXPOSURE
                        || !book.config.portfolio.equals(portfolio)) return;
                BigDecimal factor = Rules.copyFactor(value);
                BigDecimal limit = slippage == null ? book.copySlippage : Rules.adjustmentSlippage(slippage);
                long snapshot = lastVerifiedSnapshot;
                LeaderArchive.State state = archive.state(portfolio);
                boolean verified = copySnapshotReady(snapshot) && state.historyComplete && !state.gap
                        && state.head == book.archiveCursor && !needsSwitchBaseline();
                boolean changed = verified
                        ? book.changeCopySettings(factor, limit, System.currentTimeMillis(), state.holdings, verifiedLeaderMargin)
                        : book.queueCopySettings(factor, limit, System.currentTimeMillis());
                if (!changed) return;
                persist();
                updateWatchSymbols();
                status("复制系数已保存为 " + Rules.show(factor) + "× · 最大不利价差 " + Rules.show(limit) + "%"
                        + (book.copyReplanPending ? " · 等待官网与账户核对后自动调仓" : ""));
                if (verified) runCopyChecks(snapshot, true);
            }
        } catch (Exception failure) {
            reportCopyFailure(failure);
        }
    }

    private boolean copySnapshotReady(long snapshot) {
        return execution != null && book != null && book.accountSeeded && book.baselineReady
                && book.pending == null && !reconciliationPending && !waitingForInitialChoice()
                && snapshot == lastVerifiedSnapshot && valid(snapshot);
    }

    private void wakeCopyChecks() {
        if (stopped || !copyChecksQueued.compareAndSet(false, true)) return;
        try {
            accountJournal.execute(() -> {
                copyChecksQueued.set(false);
                try {
                    synchronized (TRADE_LOCK) {
                        long snapshot = lastVerifiedSnapshot;
                        if (copySnapshotReady(snapshot) && !book.copyReplanPending) runCopyChecks(snapshot, false);
                    }
                } catch (Exception failure) {
                    reportCopyFailure(failure);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            copyChecksQueued.set(false);
        }
    }

    private void runCopyChecks(long snapshot, boolean scheduleChecks) throws Exception {
        Rules.Config current = store.config();
        java.util.function.BooleanSupplier active = () -> snapshot == lastVerifiedSnapshot
                && !reconciliationPending && valid(snapshot) && !book.copyReplanPending;
        java.util.function.BooleanSupplier open = () -> active.getAsBoolean() && openingAllowed(current);
        try {
            FollowExecutor.AdjustmentStep result = scheduleChecks ? execution.decideCopyAdjustments(current, active, open)
                    : execution.finishReadyCopyAdjustments(current, active, open);
            if (!result.message.isEmpty()) status(result.message);
        } finally {
            accountReadBarrier = SystemClock.elapsedRealtimeNanos();
            updateWatchSymbols();
        }
    }

    private void reportCopyFailure(Exception failure) {
        if (stopped) return;
        if (failure instanceof FollowExecutor.JournalFailure) {
            end(failure.getMessage());
        } else if (failure instanceof FuturesApi.Failure
                && FollowExecutor.invalidCredentials(failure)) {
            end("请核对币安 API 密钥及合约权限 · " + failure.getMessage());
        } else status(problem(failure));
    }

    private void priceCycle() {
        if (stopped) return;
        long began = SystemClock.elapsedRealtime();
        Set<String> symbols = watchSymbols;
        if (market != null && !symbols.isEmpty() && networkAvailable()) {
            WaitingPrices updated;
            try {
                FuturesApi.BookTickers tickers = market.quotes(symbols);
                updated = new WaitingPrices(mode, tickers.quotes, tickers.issues, System.currentTimeMillis(), "");
            } catch (Exception unavailable) {
                updated = new WaitingPrices(mode, Collections.emptyMap(), Collections.emptyMap(), 0, problem(unavailable));
            }
            if (stopped) return;
            prices = updated;
            changed();
        }
        if (!stopped) priceWorker.schedule(this::priceCycle,
                nextDelay(SystemClock.elapsedRealtime() - began), TimeUnit.MILLISECONDS);
    }

    private void positionCycle() {
        if (stopped) return;
        if (!networkAvailable()) {
            positionWorker.schedule(this::positionCycle, PERIOD, TimeUnit.MILLISECONDS);
            return;
        }
        long began = SystemClock.elapsedRealtimeNanos();
        long at = System.currentTimeMillis();
        long backoff = 0;
        try {
            Map<String, FuturesApi.Position> positions = positionClient.positions();
            if (stopped) return;
            publishPositions(positionRealm, positions);
            if (execution != null && !DRILL.equals(mode)) {
                latestAccount.set(new ObservedAccount(positions, began, at));
                scheduleJournal();
            }
        } catch (Exception unavailable) {
            AccountPositions previous = accountPositions;
            if (!stopped) accountPositions = new AccountPositions(positionRealm,
                    previous.positions, previous.time, problem(unavailable));
            backoff = accountBackoff(unavailable);
        }
        if (!stopped) {
            accountChanged();
            long delay = Math.max(backoff, nextAccountDelay(elapsedMillis(began)));
            positionWorker.schedule(this::positionCycle, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void walletCycle() {
        if (stopped) return;
        if (!networkAvailable()) {
            walletWorker.schedule(this::walletCycle, PERIOD, TimeUnit.MILLISECONDS);
            return;
        }
        long began = SystemClock.elapsedRealtimeNanos();
        long backoff = 0;
        try {
            FuturesApi.Wallet balance = walletClient.wallet();
            if (!stopped) publishWallet(positionRealm, balance);
        } catch (Exception unavailable) {
            WalletSnapshot previous = wallet;
            if (!stopped) wallet = new WalletSnapshot(positionRealm, previous.balance, previous.time, problem(unavailable));
            backoff = accountBackoff(unavailable);
        }
        if (!stopped) {
            accountChanged();
            long delay = Math.max(backoff, nextAccountDelay(elapsedMillis(began)));
            walletWorker.schedule(this::walletCycle, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void leaderBalanceCycle() {
        if (stopped) return;
        long started = SystemClock.elapsedRealtime(), delay = BALANCE_PERIOD;
        String portfolio = initial.portfolio;
        String errorKey = "balance_error_" + portfolio;
        try {
            if (!networkAvailable()) throw new java.io.IOException("手机网络暂不可用");
            if (balanceSource == null) balanceSource = new WebsiteCapture(this, portfolio, true);
            if (stopped) {
                balanceSource.close();
                return;
            }
            long checked = balances.checked(portfolio);
            LeaderBalances.Read read = balanceSource.balances(Math.max(balanceFollowingSince, checked - BALANCE_PERIOD));
            if (stopped) return;
            balances.record(portfolio, read, System.currentTimeMillis(), balanceFollowingSince);
            String name = archive.state(portfolio).name;
            if (name.isEmpty()) name = "组合 " + portfolio;
            for (LeaderBalances.Event event : balances.pending(portfolio)) {
                if (stopped) return;
                if (!Notices.balance(this, portfolio + "|" + event.id, name, event.text())) break;
                balances.notified(portfolio, event.id);
            }
            if (read.complete) store.prefs.edit().remove(errorKey).apply();
            else store.prefs.edit().putString(errorKey, read.issue
                    + (!read.transfers.isEmpty() ? " · 已保存本轮读到的划转" : "")).apply();
        } catch (Exception failure) {
            if (stopped) return;
            store.prefs.edit().putString(errorKey, problem(failure)).apply();
            if (failure instanceof WebsiteCapture.Failure) {
                delay = Math.max(delay, ((WebsiteCapture.Failure) failure).backoffMs);
            }
        }
        if (!stopped) {
            changed();
            long spent = SystemClock.elapsedRealtime() - started;
            try { balanceWorker.schedule(this::leaderBalanceCycle, Math.max(500, delay - spent), TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException ended) { /* Service stopped while publishing the last read. */ }
        }
    }

    private void leverageCycle() {
        if (stopped) return;
        AccountPositions actual = accountPositions;
        if (positionRealm.equals(actual.realm) && !actual.positions.isEmpty()) {
            try {
                Map<String, Integer> found = leverageClient.symbolLeverages();
                if (!stopped) {
                    publishLeverages(positionRealm, found);
                    accountChanged();
                }
            } catch (Exception unavailable) {
                // Last successful settings keep their own freshness limit.
            }
        }
        if (!stopped) metadataWorker.schedule(this::leverageCycle,
                actual.positions.isEmpty() ? 2000 : 12000, TimeUnit.MILLISECONDS);
    }

    private void leaderMarketCycle() {
        if (stopped) return;
        Set<String> symbols = leaderMarketSymbols;
        if (!symbols.isEmpty()) {
            try {
                Map<String, BigDecimal> last = leaderMarkClient.latestPrices(symbols);
                if (!stopped && !last.isEmpty()) {
                    LeaderMarket previous = leaderMarket;
                    leaderMarket = new LeaderMarket(previous.marks, previous.marksAt, last,
                            System.currentTimeMillis(), previous.tiers, previous.tiersAt);
                    changed();
                }
            } catch (Exception unavailable) {
                // Last trade prices expire independently of the marks.
            }
            try {
                Map<String, BigDecimal> marks = leaderMarkClient.markPrices(symbols);
                if (!stopped && !marks.isEmpty()) {
                    LeaderMarket previous = leaderMarket;
                    leaderMarket = new LeaderMarket(marks, System.currentTimeMillis(), previous.latestPrices,
                            previous.pricesAt, previous.tiers, previous.tiersAt);
                    changed();
                }
            } catch (Exception unavailable) {
                // Brief outages retain the last mark until its freshness window ends.
            }
            if (!stopped && leaderTierClient != null && SystemClock.elapsedRealtime() >= nextLeaderTierRead) {
                nextLeaderTierRead = SystemClock.elapsedRealtime() + 60000;
                try {
                    Map<String, List<LeaderRisk.Tier>> tiers = leaderTierClient.maintenanceTiers();
                    if (!stopped && !tiers.isEmpty()) {
                        LeaderMarket current = leaderMarket;
                        leaderMarket = new LeaderMarket(current.marks, current.marksAt, current.latestPrices,
                                current.pricesAt, tiers, System.currentTimeMillis());
                        nextLeaderTierRead = SystemClock.elapsedRealtime() + LEADER_TIER_FRESH_MS;
                        changed();
                    }
                } catch (Exception unavailable) {
                    // Each contract can fall back to an assumed rate.
                }
            }
        }
        if (!stopped) metadataWorker.schedule(this::leaderMarketCycle, LEADER_RISK_PERIOD, TimeUnit.MILLISECONDS);
    }

    private void scheduleJournal() {
        if (stopped || !journalQueued.compareAndSet(false, true)) return;
        try { accountJournal.execute(this::syncAccountJournal); }
        catch (RejectedExecutionException destroyed) { journalQueued.set(false); }
    }

    private void syncAccountJournal() {
        try {
            synchronized (TRADE_LOCK) {
                ObservedAccount reading = latestAccount.getAndSet(null);
                if (stopped || reading == null || reading.started < accountReadBarrier) return;
                try {
                    if (execution.syncAccount(reading.positions, reading.at)) changed();
                } catch (Exception writeFailed) {
                    stopped = true;
                    if (source != null) source.close();
                    worker.execute(() -> end("账户仓位无法安全写入 · " + problem(writeFailed)));
                }
            }
        } finally {
            journalQueued.set(false);
            if (latestAccount.get() != null) scheduleJournal();
        }
    }

    private static long elapsedMillis(long beganNanos) {
        return TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - beganNanos);
    }

    private static long accountBackoff(Exception unavailable) {
        if (unavailable instanceof FuturesApi.Failure) {
            FuturesApi.Failure rejected = (FuturesApi.Failure) unavailable;
            if (rejected.status == 401 || rejected.code == -2015 || rejected.code == -1022) return 15000;
        }
        return 3000;
    }

    private void drill() throws Exception {
        if (book.drillStage == 3) {
            end("测试网演练通过。开平仓已成交，仓位已核对为 0。");
            return;
        }
        String symbol = initial.symbols.contains("BTCUSDT") ? "BTCUSDT" : initial.symbols.iterator().next();
        FollowBook.Leg leg = book.leg(symbol, true);
        Rules.Config currentRules = store.config();
        BigDecimal slippage = currentRules.rule(symbol).slippagePercent;
        if (book.drillStage == 0) {
            FuturesApi.Contract contract = execution.limits(symbol);
            execution.verifyPosition(symbol, api.position(symbol));
            Rules.Quote quote = api.quote(symbol);
            long at = System.currentTimeMillis();
            BigDecimal impossible = quote.ask.divide(BigDecimal.ONE.add(slippage.movePointLeft(2))
                    .multiply(new BigDecimal("1.5")), Rules.MC);
            BigDecimal boundary = Rules.entryLimit(Rules.Action.OPEN_LONG, impossible, slippage, contract.tick);
            if (Rules.acceptable(Rules.Action.OPEN_LONG, boundary, quote)) {
                throw new IllegalStateException("测试网价差规则未通过");
            }
            book.log(symbol + " · 不利价差超限用例通过，未发委托");
            book.apply(new Rules.Signal(symbol, Rules.Action.OPEN_LONG, at,
                    quote.ask.multiply(new BigDecimal("1.005")), BigDecimal.ONE), true);
            book.drillStage = 1;
            persist();
        } else if (book.drillStage == 1 && leg.owned.signum() == 0 && book.countEntries() == 0) {
            end("测试网演练开仓等待已取消。");
            return;
        } else if (book.drillStage == 1 && leg.owned.signum() > 0) {
            book.apply(new Rules.Signal(symbol, Rules.Action.CLOSE_LONG, System.currentTimeMillis(),
                    BigDecimal.ONE, BigDecimal.ONE), true);
            book.drillStage = 2;
            persist();
        } else if (book.drillStage == 2 && leg.owned.signum() == 0) {
            execution.verifyPosition(symbol, api.position(symbol));
            book.drillStage = 3;
            book.log("测试网演练完成 · " + symbol + " · 已核对开仓、平仓和剩余仓位 0");
            persist();
            end("测试网演练通过。开平仓已成交，仓位已核对为 0。");
            return;
        }
        String step = book.drillStage == 2 ? "等待测试网平仓" : "等待测试网开仓";
        long received = SystemClock.elapsedRealtime();
        status(step + " · " + execution.decide(currentRules, () -> valid(received), () -> openingAllowed(currentRules)));
    }

    private void persist() throws FollowExecutor.JournalFailure {
        if (execution != null) execution.commit();
        else {
            try { store.save(file, book.json()); }
            catch (Exception failure) { throw new FollowExecutor.JournalFailure(failure); }
        }
    }

    private boolean valid(long received) {
        long age = SystemClock.elapsedRealtime() - received;
        return !stopped && received >= 0 && age >= 0 && age <= MAX_ACTION_AGE && networkAvailable();
    }

    private boolean networkAvailable() {
        if (connectivity == null) return true;
        try {
            Network current = connectivity.getActiveNetwork();
            if (current == null) return false;
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(current);
            return capabilities == null || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private boolean openingAllowed(Rules.Config current) {
        if (!opens) return false;
        Rules.Config latest = store.config();
        return FollowBook.sameRules(current, latest) && current.sameSlippage(latest);
    }

    static long nextDelay(long spent) {
        return nextDelay(spent, PERIOD);
    }

    static long nextAccountDelay(long spent) {
        return nextDelay(spent, ACCOUNT_PERIOD);
    }

    private static long nextDelay(long spent, long period) {
        long slots = Math.max(1, (spent + period - 1) / period);
        return Math.max(0, slots * period - spent);
    }

    private boolean stoppedByUser() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
        return !exits.isEmpty() && exits.get(0).getReason() == ApplicationExitInfo.REASON_USER_REQUESTED
                && exits.get(0).getTimestamp() >= store.prefs.getLong("auto_started", Long.MAX_VALUE);
    }

    private void status(String detail) {
        if (stopped) return;
        String counts = DRILL.equals(mode) ? "" : " · 有效 " + reads + " / 空或失败 " + misses;
        String text = label(mode) + " · " + detail + counts;
        store.prefs.edit().putString("auto_status", text).apply();
        changed();
    }

    private void end(String message) {
        stopped = true;
        initialPositions = null;
        if (source != null) source.close();
        if (balanceSource != null) balanceSource.close();
        if (awake != null && awake.isHeld()) awake.release();
        synchronized (TRADE_LOCK) {
            try {
                JSONObject saved = file == null ? null : store.load(file);
                if (saved != null) {
                    FollowBook ended = FollowBook.restore(saved);
                    if (ended.trackingSince > 0) {
                        ended.trackedMillis = ended.trackingMillis(System.currentTimeMillis());
                        ended.trackingSince = 0;
                        store.save(file, ended.json());
                    }
                }
            } catch (Exception failed) { message += " · 跟单时长未保存"; }
        }
        store.prefs.edit().remove("auto_active").putString("auto_status", label(mode) + " · " + message).commit();
        changed();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void changed() { sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); }

    private void accountChanged() { sendBroadcast(new Intent(ACCOUNT_CHANGED).setPackage(getPackageName())); }

    private static String problem(Exception failure) {
        if (failure instanceof javax.net.ssl.SSLException) return "连接币安时 TLS 校验失败";
        if (failure instanceof java.net.SocketTimeoutException || failure instanceof java.net.UnknownHostException) {
            return "币安连接超时或无法解析，请检查手机网络";
        }
        return failure.getMessage() == null ? "本轮运行失败，等待下次核对" : failure.getMessage();
    }

    @Override public void onDestroy() {
        stopped = true;
        runningMode = null;
        initialPositions = null;
        startRequested = false;
        if (source != null) source.close();
        if (balanceSource != null) balanceSource.close();
        if (awake != null && awake.isHeld()) awake.release();
        FollowExecutor currentExecution = execution;
        if (currentExecution != null) currentExecution.closeCopyChecks();
        worker.shutdownNow();
        priceWorker.shutdownNow();
        positionWorker.shutdownNow();
        walletWorker.shutdownNow();
        balanceWorker.shutdownNow();
        metadataWorker.shutdownNow();
        accountJournal.shutdownNow();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            if (balanceSource != null) balanceSource.releaseIdlePage();
            if (source != null) source.releaseIdlePage();
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
