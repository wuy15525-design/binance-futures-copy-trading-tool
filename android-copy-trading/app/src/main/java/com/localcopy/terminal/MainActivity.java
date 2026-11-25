package com.localcopy.terminal;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 承载运行、成交和设置页面。界面从服务快照和本机档案取数，通过服务命令控制跟单，
 * 账户查询在后台执行，结果回到主线程刷新。
 */
public final class MainActivity extends Activity {
    private static final int EXPORT_REQUEST = 44;
    private static final int RECORDS_PER_PAGE = 12;
    private static final int RUN_ROWS_PER_PAGE = 6;
    private static final String DONATION_UID = "1218112316";
    private static final String DONATION_ADDRESS = "0x46CE8C06265b4d7667AA2cb693B71193f9F0C6ae";
    private static final long SNAPSHOT_FRESH_MS = 5000;
    private static final Rules.ContractRule DEFAULT_RULE =
            new Rules.ContractRule(false, new BigDecimal("20"), 5, LocalData.DEFAULT_SLIPPAGE);
    private static final ExecutorService ACCOUNT = Executors.newSingleThreadExecutor();
    private static final ExecutorService PROFILE = Executors.newSingleThreadExecutor();
    private static final ExecutorService CATALOG = Executors.newSingleThreadExecutor();
    private static final ExecutorService EXPORT = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean ACCOUNT_BUSY = new AtomicBoolean();
    private LocalData store;
    private LeaderArchive archive;
    private LeaderBalances balanceArchive;
    private LinearLayout page;
    private FrameLayout surface, browserViewport;
    private LinearLayout mainContent;
    private View introCover;
    private AlertDialog usageWarning;
    private long introEndsAt;
    private final Runnable dismissIntro = this::finishIntro;
    private static final int LIGHT_BARS = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
    private TextView leaderView, leaderPositionsView, accountPositionsView;
    private TextView autoView, orderStatus, archiveSummary, copyStatus, balanceSummary;
    private TextView backgroundStatus;
    private LinearLayout websiteHelp;
    private Button openWebsite, retryWebsite, editCopy;
    private AlertDialog websitePrompt;
    private Ui.Overview runStats;
    private Ui.Pager leaderPositions, accountPositions, waits, signals, autoHistory, archiveEntries;
    private Ui.Pager balanceEntries;
    private Ui.Pager initialPositions;
    private LinearLayout initialCard;
    private TextView initialDetails;
    private Button addExisting;
    private AutoFollowService.InitialPositions displayedInitialPositions;
    private Button exportButton;
    private boolean ownRecords, showMoney;
    private String archiveStamp, exportPortfolio;
    private WebsiteCapture nameLookup;
    private String requestedNamePortfolio = "", nameError = "";
    private String waitStamp;
    private String accountStatus = "在手机上保存对应环境的 HMAC API 密钥";
    private TextView accountView;
    private FollowBook displayedBook;
    private String displayedMode = "";
    private String leaderMarginText = "";
    private long leaderRiskExpires;
    private int section;
    private final Handler uiClock = new Handler(Looper.getMainLooper());
    private final Runnable tickClock = new Runnable() {
        @Override public void run() {
            if (runStats != null) drawRunStats(displayedMode, displayedBook);
            if (leaderRiskExpires > 0 && System.currentTimeMillis() > leaderRiskExpires) refresh();
            uiClock.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver runChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AutoFollowService.ACCOUNT_CHANGED.equals(intent.getAction())) refreshAccountDisplay();
            else refresh();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new LocalData(this);
        archive = LeaderArchive.open(this);
        balanceArchive = LeaderBalances.open(this);
        if (state != null) {
            exportPortfolio = state.getString("exportPortfolio");
            ownRecords = state.getBoolean("ownRecords");
        }
        Notices.channels(this);
        IntentFilter changes = new IntentFilter(AutoFollowService.CHANGED);
        changes.addAction(AutoFollowService.ACCOUNT_CHANGED);
        registerReceiver(runChanges, changes, Context.RECEIVER_NOT_EXPORTED);
        surface = new FrameLayout(this);
        surface.setBackgroundColor(Ui.BACKGROUND);
        Ui.applyInsets(getWindow(), surface);
        browserViewport = new FrameLayout(this);
        // Keep the WebView laid out in the activity; cover its background rendering.
        browserViewport.setForeground(new ColorDrawable(Ui.BACKGROUND));
        browserViewport.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surface.addView(browserViewport, new FrameLayout.LayoutParams(-1, -1));
        setContentView(surface);
        show(state == null ? 0 : state.getInt("section"));
        if (!version().equals(store.prefs.getString("last_intro_version", ""))) {
            introEndsAt = state == null ? 0 : state.getLong("introEndsAt");
            introCover = IntroScreen.create(this);
            mainContent.setVisibility(View.INVISIBLE);
            setLightBars(false);
            surface.addView(introCover, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        WebsiteCapture.attachViewport(browserViewport);
        if (!AutoFollowService.running() && !ACCOUNT_BUSY.get() && store.prefs.contains("auto_active")) {
            closeNameLookup();
            AutoFollowService.reserveStart(true);
            try { startForegroundService(new Intent(this, AutoFollowService.class).setAction(AutoFollowService.RESTORE)); }
            catch (RuntimeException failure) {
                AutoFollowService.reserveStart(false);
                store.prefs.edit().putString("auto_status", "系统暂未允许恢复后台服务，请在运行页手动启动").apply();
            }
        }
        resolveLeaderName();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        uiClock.postDelayed(tickClock, 1000);
        if (introCover == null) showUsageWarning();
        else {
            long deadline = SystemClock.uptimeMillis() + IntroScreen.DURATION;
            if (introEndsAt == 0 || introEndsAt > deadline) introEndsAt = deadline;
            uiClock.postAtTime(dismissIntro, introEndsAt);
        }
    }

    @Override protected void onPause() {
        uiClock.removeCallbacks(tickClock);
        uiClock.removeCallbacks(dismissIntro);
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeNameLookup();
        if (usageWarning != null) usageWarning.dismiss();
        unregisterReceiver(runChanges);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("section", section);
        state.putString("exportPortfolio", exportPortfolio);
        state.putBoolean("ownRecords", ownRecords);
        state.putLong("introEndsAt", introEndsAt);
    }

    @Override protected void onStop() {
        if (websitePrompt != null) websitePrompt.dismiss();
        WebsiteCapture.detachViewport(browserViewport);
        super.onStop();
    }

    private void show(int tab) {
        section = Math.max(0, Math.min(2, tab));
        leaderView = leaderPositionsView = accountPositionsView = accountView = null;
        autoView = orderStatus = archiveSummary = copyStatus = balanceSummary = backgroundStatus = null;
        runStats = null;
        leaderPositions = accountPositions = waits = signals = autoHistory = archiveEntries = initialPositions = null;
        balanceEntries = null;
        initialCard = null;
        displayedInitialPositions = null;
        archiveStamp = null;
        waitStamp = null;
        displayedBook = null;
        displayedMode = "";
        leaderMarginText = "";
        leaderRiskExpires = 0;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        page = Ui.contentColumn(this);
        int side = Ui.dp(this, getResources().getConfiguration().screenWidthDp < 350 ? 13 : 18);
        page.setPadding(side, Ui.dp(this, 14), side, Ui.dp(this, 28));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView versionLabel = Ui.text(this, version(), 12, Ui.ACCENT);
        versionLabel.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        versionLabel.setBackground(Ui.fill(this, 0xffe2ebfb, 30, 0));
        if (section == 0) {
            LinearLayout title = Ui.column(this);
            title.addView(Ui.heading(this, "合约跟单台", 29));
            versionLabel.setPadding(0, 0, 0, 0);
            versionLabel.setBackground(null);
            title.addView(versionLabel);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            header.addView(Ui.donationButton(this, v -> showDonation()));
        } else {
            header.addView(Ui.heading(this, "合约跟单台", 29), new LinearLayout.LayoutParams(0, -2, 1f));
            header.addView(versionLabel);
        }
        page.addView(header);
        try {
            if (section == 0) home();
            if (section == 1) records();
            if (section == 2) configure();
        } catch (RuntimeException invalid) {
            page.addView(Ui.text(this, message(invalid), 15, Ui.INK));
        }
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        LinearLayout layout = Ui.column(this);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        layout.addView(bottomNavigation(), new LinearLayout.LayoutParams(-1, -2));
        if (mainContent != null) surface.removeView(mainContent);
        mainContent = layout;
        if (introCover != null) layout.setVisibility(View.INVISIBLE);
        surface.addView(layout, 1, new FrameLayout.LayoutParams(-1, -1));
        refresh();
    }

    private void setLightBars(boolean light) {
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(light ? LIGHT_BARS : 0, LIGHT_BARS);
    }

    private void finishIntro() {
        if (introCover == null || isFinishing()) return;
        surface.removeView(introCover);
        introCover = null;
        mainContent.setVisibility(View.VISIBLE);
        setLightBars(true);
        store.prefs.edit().putString("last_intro_version", version()).apply();
        showUsageWarning();
    }

    private void showUsageWarning() {
        if (store.prefs.getBoolean("usage_notice_seen", false) || usageWarning != null || isFinishing()) return;
        usageWarning = new AlertDialog.Builder(this).setTitle("使用提示")
                .setMessage(InfoActivity.USE_NOTICE)
                .setNeutralButton("查免责声明", (dialog, which) -> {
                    acknowledgeUsageNotice();
                    startActivity(InfoActivity.open(this, true));
                })
                .setPositiveButton("我知道了", (dialog, which) -> acknowledgeUsageNotice())
                .setCancelable(false).show();
    }

    private void acknowledgeUsageNotice() {
        store.prefs.edit().putBoolean("usage_notice_seen", true).apply();
    }

    private void showDonation() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout details = Ui.column(this);
        int padding = Ui.dp(this, 20);
        details.setPadding(padding, Ui.dp(this, 4), padding, Ui.dp(this, 12));
        addDonationChannel(details, "币安转账", DONATION_UID, "复制 UID");
        addDonationChannel(details, "BNB Chain · USDT", DONATION_ADDRESS, "复制地址");
        TextView note = Ui.text(this, "如果这款工具有帮到你，欢迎捐赠，多少都不嫌弃，你的支持是我更新的动力。", 14, Ui.MUTED);
        note.setPadding(Ui.dp(this, 3), Ui.dp(this, 16), Ui.dp(this, 3), 0);
        details.addView(note);
        scroll.addView(details);
        new AlertDialog.Builder(this).setTitle("支持合约跟单台").setView(scroll)
                .setPositiveButton("关闭", null).show();
    }

    private void addDonationChannel(LinearLayout parent, String label, String value, String copyLabel) {
        LinearLayout card = Ui.card(this, parent);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 13));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.heading(this, label, 16), new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = Ui.button(this, copyLabel, false, v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Button pressed = (Button) v;
            pressed.setText("已复制");
            pressed.postDelayed(() -> pressed.setText(copyLabel), 1800);
        });
        copy.setContentDescription(label + " " + copyLabel);
        header.addView(copy);
        card.addView(header);
        TextView address = Ui.text(this, value, 14, Ui.INK);
        address.setTypeface(Typeface.MONOSPACE);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        address.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        address.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
        address.setBackground(Ui.fill(this, 0xffedf3fb, 9, 0));
        card.addView(address, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout bottomNavigation() {
        LinearLayout shell = Ui.column(this);
        shell.setBackgroundColor(android.graphics.Color.WHITE);
        View divider = new View(this);
        divider.setBackgroundColor(Ui.EDGE);
        shell.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        LinearLayout links = Ui.contentColumn(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.gravity = Gravity.CENTER_HORIZONTAL;
        shell.addView(links, row);
        String[] titles = {"运行", "成交记录", "设置"};
        int[] icons = {R.drawable.ic_nav_run, R.drawable.ic_nav_records, R.drawable.ic_nav_settings};
        for (int i = 0; i < titles.length; i++) {
            final int destination = i;
            Button link = Ui.nav(this, titles[i], icons[i], section == i, v -> {
                if (destination != section) show(destination);
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1f);
            cell.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            links.addView(link, cell);
        }
        return shell;
    }

    private void home() {
        runStats = new Ui.Overview(this, page, v -> {
            showMoney = !showMoney;
            drawRunStats(displayedMode, displayedBook);
        });

        LinearLayout automatic = Ui.card(this, page);
        automatic.addView(Ui.heading(this, "自动跟单", 20));
        leaderView = Ui.text(this, "", 15, Ui.MUTED);
        automatic.addView(leaderView);
        autoView = Ui.text(this, "", 13, Ui.MUTED);
        int statusPadding = Ui.dp(this, 11);
        autoView.setPadding(statusPadding, statusPadding, statusPadding, statusPadding);
        autoView.setBackground(Ui.fill(this, Ui.SURFACE, 12, Ui.EDGE));
        LinearLayout.LayoutParams statusBox = new LinearLayout.LayoutParams(-1, -2);
        statusBox.topMargin = Ui.dp(this, 8);
        statusBox.bottomMargin = Ui.dp(this, 5);
        automatic.addView(autoView, statusBox);
        websiteHelp = new LinearLayout(this);
        websiteHelp.setOrientation(LinearLayout.VERTICAL);
        openWebsite = Ui.button(this, "打开币安组合页", false, v -> openWebsite());
        Ui.addButton(websiteHelp, openWebsite);
        retryWebsite = Ui.button(this, "重试官网读取", false, v -> autoCommand(AutoFollowService.RETRY_WEB));
        Ui.addButton(websiteHelp, retryWebsite);
        websiteHelp.setVisibility(View.GONE);
        automatic.addView(websiteHelp);
        copyStatus = Ui.text(this, "", 14, Ui.INK);
        copyStatus.setVisibility(View.GONE);
        automatic.addView(copyStatus);
        editCopy = Ui.button(this, "调整复制系数", false, v -> editCopyFactor());
        Ui.addButton(automatic, editCopy);
        editCopy.setVisibility(View.GONE);
        button(automatic, "启动实盘自动跟单", true, v -> chooseStrategy(AutoFollowService.REAL));
        button(automatic, "开始自动抓取", false, v -> startAuto(AutoFollowService.WATCH));
        button(automatic, "测试网自动开平仓演练", false, v -> startAuto(AutoFollowService.DRILL));
        button(automatic, "用官网成交跟单到测试网", false, v -> chooseStrategy(AutoFollowService.DEMO_WEB));
        button(automatic, "暂停开仓，继续处理平仓", false, v -> autoCommand(AutoFollowService.PAUSE));
        button(automatic, "恢复自动开仓", false, v -> autoCommand(AutoFollowService.RESUME));
        Ui.addButton(automatic, Ui.stopButton(this, "停止自动运行", v -> autoCommand(AutoFollowService.STOP)));

        initialCard = Ui.card(this, page);
        initialCard.addView(Ui.heading(this, "首次跟单 · 已有仓位", 18));
        initialDetails = Ui.text(this, "", 14, Ui.INK);
        initialCard.addView(initialDetails);
        initialPositions = new Ui.Pager(initialCard, RUN_ROWS_PER_PAGE, this::refresh);
        addExisting = Ui.button(this, "选择要补跟的仓位", false, v -> chooseInitialPositions());
        Ui.addButton(initialCard, addExisting);
        button(initialCard, "仅跟后续成交", false, v -> {
            if (displayedInitialPositions != null) submitInitialPositions(displayedInitialPositions, new String[0]);
        });
        initialCard.setVisibility(View.GONE);

        LinearLayout positions = Ui.card(this, page);
        positions.addView(Ui.heading(this, "带单员推算持仓", 18));
        leaderPositionsView = Ui.text(this, "", 14, Ui.INK);
        positions.addView(leaderPositionsView);
        leaderPositions = new Ui.Pager(positions, RUN_ROWS_PER_PAGE, this::refresh);
        LinearLayout ownPositions = Ui.card(this, page);
        ownPositions.addView(Ui.heading(this, "我的当前持仓", 18));
        accountPositionsView = Ui.text(this, "", 14, Ui.INK);
        ownPositions.addView(accountPositionsView);
        accountPositions = new Ui.Pager(ownPositions, RUN_ROWS_PER_PAGE, this::refresh);

        LinearLayout waitCard = Ui.card(this, page);
        waitCard.addView(Ui.heading(this, "等待开仓与调仓", 18));
        waits = new Ui.Pager(waitCard, RUN_ROWS_PER_PAGE, this::refresh);

        LinearLayout signalCard = Ui.card(this, page);
        signalCard.addView(Ui.heading(this, "检测到的新增操作", 18));
        signals = new Ui.Pager(signalCard, RUN_ROWS_PER_PAGE, this::refresh);

        LinearLayout realLogs = Ui.card(this, page);
        realLogs.addView(Ui.heading(this, "自动运行记录", 18));
        orderStatus = Ui.text(this, "", 13, Ui.MUTED);
        realLogs.addView(orderStatus);
        autoHistory = new Ui.Pager(realLogs, RUN_ROWS_PER_PAGE, this::refresh);
    }

    private void records() {
        LinearLayout records = Ui.card(this, page);
        LinearLayout views = new LinearLayout(this);
        views.addView(Ui.button(this, "带单员成交", !ownRecords, v -> selectRecords(false)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        views.addView(Ui.button(this, "我的成交", ownRecords, v -> selectRecords(true)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        records.addView(views);
        archiveSummary = Ui.text(this, "", 14, Ui.MUTED);
        records.addView(archiveSummary);
        exportButton = Ui.button(this, "导出全部 CSV", false, v -> exportRecords());
        exportButton.setVisibility(ownRecords ? View.GONE : View.VISIBLE);
        Ui.addButton(records, exportButton);
        archiveEntries = new Ui.Pager(records, RECORDS_PER_PAGE, this::refresh);
        if (!ownRecords) {
            LinearLayout balances = Ui.card(this, page);
            balances.addView(Ui.heading(this, "带单保证金余额历史", 18));
            balanceSummary = Ui.text(this, "", 13, Ui.MUTED);
            balances.addView(balanceSummary);
            balanceEntries = new Ui.Pager(balances, RUN_ROWS_PER_PAGE, this::refresh);
        }
    }

    private void selectRecords(boolean mine) {
        if (mine == ownRecords) return;
        ownRecords = mine;
        show(1);
    }

    private void configure() {
        Rules.Config initial = store.config();
        LinearLayout form = Ui.card(this, page);
        form.addView(Ui.heading(this, "跟单规则", 18));
        AutoCompleteTextView portfolio = portfolioField(form, initial.portfolio);
        form.addView(Ui.text(this, "跟踪中可保存保证金和点差。修改杠杆前会核对该合约是否空仓。", 13, Ui.MUTED));
        Map<String, Rules.ContractRule> draft = new LinkedHashMap<>(initial.rules);
        LinearLayout whitelist = Ui.column(this);
        Runnable update = () -> showWhitelist(whitelist, draft);
        button(form, "添加合约", false, v -> pickContracts(draft, update));
        Ui.LimitedScroll whitelistScroll = new Ui.LimitedScroll(this, 360);
        whitelistScroll.addView(whitelist, new ScrollView.LayoutParams(-1, -2));
        form.addView(whitelistScroll, new LinearLayout.LayoutParams(-1, -2));
        update.run();
        button(form, "保存规则", true, v -> saveRules(portfolio, draft, (Button) v));
        account();

        LinearLayout system = Ui.card(this, page);
        system.addView(Ui.heading(this, "后台与通知", 18));
        backgroundStatus = Ui.text(this, "", 14, Ui.INK);
        system.addView(backgroundStatus);
        system.addView(Ui.text(this, backgroundGuide(Build.MANUFACTURER, Build.BRAND), 13, Ui.MUTED));
        system.addView(Ui.text(this, "在系统应用信息中允许自启动（如有）。断网恢复后会先核对成交和委托。"
                + "系统强停后，请回到运行页手动启动。", 13, Ui.MUTED));
        button(system, "打开应用系统设置", false, v -> openSystemPage(applicationSettings()));
        button(system, "检查电池优化", false, v -> openSystemPage(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        button(system, "打开通知设置", false, v -> openSystemPage(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        button(system, "交易通知声音", false, v -> openSystemPage(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notices.tradeChannelId(this))));
        button(system, "测试通知与提示音", false, v -> {
            if (!askNotifications()) return;
            FuturesApi.Fills sample = new FuturesApi.Fills();
            sample.quantity = new BigDecimal("0.015");
            sample.value = sample.quantity.multiply(new BigDecimal("59940"));
            sample.realizedPnl = new BigDecimal("-12.45");
            sample.latest = System.currentTimeMillis();
            Notices.testTrade(this, TradeNotice.completed("模拟平多已成交", "BTCUSDT",
                    Rules.Action.CLOSE_LONG, sample, new BigDecimal("0.005"), new BigDecimal("60000"), "模拟带单员均价"));
            Toast.makeText(this, Notices.soundStatus(this), Toast.LENGTH_LONG).show();
        });
        LinearLayout about = Ui.card(this, page);
        about.addView(Ui.heading(this, "关于", 18));
        button(about, "作者信息", false, v -> startActivity(InfoActivity.open(this, false)));
        button(about, "免责声明", false, v -> startActivity(InfoActivity.open(this, true)));
    }

    private AutoCompleteTextView portfolioField(LinearLayout form, String id) {
        form.addView(Ui.text(this, "币安组合 ID 或组合链接", 13, Ui.MUTED));
        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setHint("币安组合 ID 或组合链接");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setThreshold(1);
        input.setText(id, false);
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));
        List<LeaderArchive.Portfolio> past = archive.followedPortfolios();
        if (past.isEmpty()) return input;
        ArrayAdapter<LeaderArchive.Portfolio> choices = new ArrayAdapter<LeaderArchive.Portfolio>(
                this, android.R.layout.simple_dropdown_item_1line, past) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LeaderArchive.Portfolio item = getItem(position);
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setSingleLine(false);
                row.setText((item.name.isEmpty() ? "币安组合" : item.name) + "\n" + item.id);
                row.setTextSize(15);
                return row;
            }
        };
        input.setAdapter(choices);
        input.setOnItemClickListener((parent, view, position, selected) -> {
            LeaderArchive.Portfolio item = choices.getItem(position);
            if (item != null) input.setText(item.id, false);
        });
        button(form, "历史带单员 ▾", false, v -> {
            input.setShowSoftInputOnFocus(false);
            input.requestFocus();
            input.setShowSoftInputOnFocus(true);
            choices.getFilter().filter(null, count -> {
                if (count > 0 && input.isAttachedToWindow() && input.hasFocus()) input.showDropDown();
            });
        });
        return input;
    }

    static String backgroundGuide(String manufacturer, String brand) {
        String device = (manufacturer + " " + brand).toLowerCase(Locale.ROOT);
        if (device.contains("xiaomi") || device.contains("redmi") || device.contains("poco")) {
            return "小米 · 在「电池」中为合约跟单台选择「无限制」，并在「后台自启动」中允许本应用。";
        }
        if (device.contains("honor")) {
            return "荣耀 · 在「应用启动管理」中将合约跟单台设为手动管理，并开启「允许后台活动」。";
        }
        if (device.contains("vivo") || device.contains("iqoo")) {
            return "vivo · 在「电池 → 后台耗电管理」中为合约跟单台允许后台耗电。";
        }
        if (device.contains("oppo") || device.contains("oneplus") || device.contains("realme")) {
            return "OPPO · 在「电池」的应用耗电管理或应用信息中查找「允许后台活动」，并允许合约跟单台后台运行。";
        }
        if (device.contains("samsung")) {
            return "三星 · 在「电池 → 后台使用限制」中，将合约跟单台添加到「从不自动休眠的应用」。";
        }
        return "在应用的电池设置中允许后台运行，并检查锁屏时能否收到跟单通知。";
    }

    private Intent applicationSettings() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
    }

    private void openSystemPage(Intent requested) {
        try {
            startActivity(requested);
        } catch (ActivityNotFoundException | SecurityException missing) {
            try { startActivity(applicationSettings()); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(this, "请从系统设置的应用列表打开合约跟单台", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshBackgroundStatus() {
        if (backgroundStatus == null) return;
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean notification = getSystemService(NotificationManager.class).areNotificationsEnabled();
        boolean restricted = getSystemService(ActivityManager.class).isBackgroundRestricted();
        ConnectivityManager network = getSystemService(ConnectivityManager.class);
        boolean dataSaver = network != null && network.getRestrictBackgroundStatus()
                == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        Ui.setText(backgroundStatus, "通知 " + (notification ? "已开启" : "待开启") + " · 电池优化 "
                + (battery ? "已排除" : "系统管理中")
                + "\n" + Notices.soundStatus(this)
                + (restricted ? "\n系统限制了本应用的后台运行" : "")
                + (dataSaver ? "\n省流量模式已限制本应用的后台数据" : ""));
    }

    private void showWhitelist(LinearLayout list, Map<String, Rules.ContractRule> draft) {
        list.removeAllViews();
        list.addView(Ui.heading(this, "合约白名单 · " + draft.size() + " / 30", 17));
        if (draft.isEmpty()) list.addView(Ui.text(this, "请添加至少一个合约", 14, Ui.MUTED));
        for (Map.Entry<String, Rules.ContractRule> entry : draft.entrySet()) {
            String symbol = entry.getKey();
            LinearLayout row = Ui.card(this, list);
            row.addView(Ui.heading(this, symbol, 16));
            TextView details = Ui.text(this, "", 13, Ui.MUTED);
            Ui.setDetail(details, ruleText(entry.getValue()));
            row.addView(details);
            LinearLayout actions = new LinearLayout(this);
            actions.addView(Ui.button(this, "设置", false, v -> editContract(symbol, draft, () -> showWhitelist(list, draft))),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            actions.addView(Ui.button(this, "移除", false, v -> {
                draft.remove(symbol);
                showWhitelist(list, draft);
            }), new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(actions);
        }
    }

    private static String ruleText(Rules.ContractRule rule) {
        String sizing = rule.ratio ? Rules.show(rule.amount) + "% 钱包余额" : Rules.show(rule.amount) + " USDT";
        return "每笔保证金 " + sizing + " · " + rule.leverage + " 倍\n最大不利价差 " + Rules.show(rule.slippagePercent) + "%";
    }

    private void editContract(String symbol, Map<String, Rules.ContractRule> draft, Runnable update) {
        Rules.ContractRule rule = draft.get(symbol);
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
        Switch ratio = new Switch(this);
        ratio.setText("定比 · 按当前 USDT 钱包余额");
        ratio.setChecked(rule.ratio);
        form.addView(ratio);
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        EditText fixed = field(form, "每笔保证金（USDT）", rule.ratio ? "" : Rules.show(rule.amount), decimal);
        EditText percent = field(form, "每笔保证金比例（%）", rule.ratio ? Rules.show(rule.amount) : "", decimal);
        Runnable sizing = () -> {
            fixed.setEnabled(!ratio.isChecked());
            percent.setEnabled(ratio.isChecked());
        };
        sizing.run();
        ratio.setOnCheckedChangeListener((v, checked) -> sizing.run());
        EditText leverage = field(form, "预设杠杆倍数", Integer.toString(rule.leverage), InputType.TYPE_CLASS_NUMBER);
        EditText slip = field(form, "最大不利价差（%）", Rules.show(rule.slippagePercent), decimal);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(symbol + " 跟单设置").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("应用", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (!text(leverage).matches("[0-9]{1,3}")) throw new IllegalArgumentException("杠杆范围为 1–125 倍");
                Rules.ContractRule changed = new Rules.ContractRule(ratio.isChecked(),
                        parseAmount(ratio.isChecked() ? percent : fixed),
                        Integer.parseInt(text(leverage)), parseAmount(slip));
                draft.put(symbol, changed);
                update.run();
                dialog.dismiss();
            } catch (Exception badInput) { error(badInput); }
        });
    }

    private void pickContracts(Map<String, Rules.ContractRule> draft, Runnable update) {
        if (draft.size() >= 30) {
            error(new IllegalArgumentException("白名单最多添加 30 个合约"));
            return;
        }
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        EditText search = field(form, "搜索合约名称或代码", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView detail = Ui.text(this, "", 13, Ui.MUTED);
        form.addView(detail);
        ListView options = new ListView(this);
        options.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        options.setScrollbarFadingEnabled(false);
        int height = Math.max(140, Math.min(300, getResources().getConfiguration().screenHeightDp / 3));
        form.addView(options, new LinearLayout.LayoutParams(-1, Ui.dp(this, height)));

        Set<String> catalog = new TreeSet<>(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"));
        Set<String> selected = new LinkedHashSet<>();
        List<String> visible = new ArrayList<>();
        TextView catalogInfo = Ui.text(this, "正在读取币安实盘 USDT 永续列表", 13, Ui.MUTED);
        form.addView(catalogInfo);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加合约").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("添加所选", null).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        Button add = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable updateCount = () -> {
            detail.setText("已选 " + selected.size() + " 个 · 还可添加 " + (30 - draft.size() - selected.size()) + " 个");
            add.setEnabled(!selected.isEmpty());
        };
        Runnable filter = () -> {
            String query = text(search).toUpperCase(Locale.ROOT);
            visible.clear();
            List<String> labels = new ArrayList<>();
            Set<String> candidates = new TreeSet<>(catalog);
            candidates.addAll(selected);
            for (String symbol : candidates) {
                if (draft.containsKey(symbol) || !symbol.contains(query)) continue;
                visible.add(symbol);
                labels.add(symbol);
            }
            if (Rules.isUsdtSymbol(query) && !draft.containsKey(query) && !visible.contains(query)) {
                visible.add(query);
                labels.add(query + " · 手动添加");
            }
            options.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
            options.clearChoices();
            for (int i = 0; i < visible.size(); i++) options.setItemChecked(i, selected.contains(visible.get(i)));
            updateCount.run();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        options.setOnItemClickListener((parent, row, index, id) -> {
            String symbol = visible.get(index);
            if (!options.isItemChecked(index)) selected.remove(symbol);
            else if (draft.size() + selected.size() < 30) selected.add(symbol);
            else options.setItemChecked(index, false);
            updateCount.run();
        });
        add.setOnClickListener(v -> {
            for (String symbol : selected) draft.put(symbol, DEFAULT_RULE);
            update.run();
            dialog.dismiss();
        });
        filter.run();
        CATALOG.execute(() -> {
            try {
                List<String> symbols = new FuturesApi(FuturesApi.LIVE, null).tradableSymbols();
                runOnUiThread(() -> {
                    if (isDestroyed() || !dialog.isShowing()) return;
                    catalog.clear();
                    catalog.addAll(symbols);
                    catalogInfo.setText("币安实盘 USDT 永续 · " + symbols.size() + " 个可交易合约");
                    filter.run();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && dialog.isShowing()) {
                        catalogInfo.setText("币安实盘列表暂不可用，可输入完整合约代码，或重新打开列表重试");
                    }
                });
            }
        });
    }

    private void saveRules(EditText portfolio, Map<String, Rules.ContractRule> draft, Button save) {
        Rules.Config config;
        try { config = new Rules.Config(portfolioId(text(portfolio)), draft); }
        catch (Exception badInput) { error(badInput); return; }
        boolean switching = !store.config().portfolio.equals(config.portfolio);
        if (switching && (AutoFollowService.running() || store.prefs.contains("auto_active"))) {
            error(new IllegalStateException("请先停止自动运行，再切换带单员"));
            return;
        }
        if (!ACCOUNT_BUSY.compareAndSet(false, true)) {
            error(new IllegalStateException("请等本次账户操作完成后再保存规则"));
            return;
        }
        save.setEnabled(false);
        save.setText("核对并保存中…");
        if (switching) closeNameLookup();
        ACCOUNT.execute(() -> {
            Exception problem = null;
            try {
                if (switching || (!AutoFollowService.running() && archive.state(config.portfolio).name.isEmpty())) {
                    WebsiteCapture lookup = new WebsiteCapture(this, config.portfolio);
                    try { archive.saveName(config.portfolio, lookup.name()); }
                    catch (Exception failure) {
                        throw new IllegalStateException("组合验证未通过 · " + message(failure), failure);
                    } finally { lookup.close(); }
                }
                DemoOrders.checkWhitelist(store, config.symbols);
                AutoFollowService.saveRules(store, archive, config);
            } catch (Exception failure) { problem = failure; }
            finally { ACCOUNT_BUSY.set(false); }
            Exception outcome = problem;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                save.setText("保存规则");
                save.setEnabled(true);
                if (outcome != null) error(outcome);
                else {
                    resolveLeaderName();
                    refresh();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void account() {
        LinearLayout panel = Ui.card(this, page);
        panel.addView(Ui.heading(this, "币安 U 本位合约账户", 18));
        accountView = Ui.text(this, accountStatus, 14, Ui.MUTED);
        panel.addView(accountView);
        button(panel, "保存实盘只读密钥", false, v -> editKeys(FuturesApi.LIVE_READ));
        button(panel, "查询实盘钱包余额", false, v -> wallet(FuturesApi.LIVE_READ));
        button(panel, "查询实盘持仓", false, v -> readPositions(FuturesApi.LIVE));
        button(panel, "保存实盘交易密钥", false, v -> editKeys(FuturesApi.LIVE));
        button(panel, "保存测试网密钥", false, v -> editKeys(FuturesApi.DEMO));
        button(panel, "查询测试网钱包余额", false, v -> wallet(FuturesApi.DEMO));
        button(panel, "查询测试网持仓", false, v -> readPositions(FuturesApi.DEMO));

    }

    private void wallet(String realm) {
        runAccount(() -> {
            boolean demo = FuturesApi.DEMO.equals(realm);
            String savedRealm = !demo && !store.prefs.contains("credentials_" + realm) ? FuturesApi.LIVE : realm;
            FuturesApi.Wallet wallet = new FuturesApi(realm, Secrets.load(store, savedRealm)).wallet();
            AutoFollowService.publishWallet(demo ? FuturesApi.DEMO : FuturesApi.LIVE, wallet);
            String label = FuturesApi.DEMO.equals(realm) ? "测试网" : "实盘只读";
            return label + " · USDT 钱包余额 " + Rules.show(wallet.balance)
                    + "\n可用余额 " + Rules.show(wallet.available);
        });
    }

    private void readPositions(String realm) {
        runAccount(() -> {
            boolean demo = FuturesApi.DEMO.equals(realm);
            String savedRealm = !demo && store.prefs.contains("credentials_" + FuturesApi.LIVE_READ)
                    ? FuturesApi.LIVE_READ : realm;
            String clientRealm = demo ? FuturesApi.DEMO : FuturesApi.LIVE_READ;
            FuturesApi client = new FuturesApi(clientRealm, Secrets.load(store, savedRealm));
            Map<String, FuturesApi.Position> positions = client.positions();
            AutoFollowService.publishPositions(realm, positions);
            if (!positions.isEmpty()) {
                try { AutoFollowService.publishLeverages(realm, client.symbolLeverages()); }
                catch (Exception unavailable) { AutoFollowService.publishLeverages(realm, Collections.emptyMap()); }
            }
            return accountPositionText(AutoFollowService.accountPositions());
        });
    }

    private void editKeys(String realm) {
        if (ACCOUNT_BUSY.get() || AutoFollowService.running()) {
            error(new IllegalStateException("请先停止自动运行并等待账户操作结束"));
            return;
        }
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 9), Ui.dp(this, 20), Ui.dp(this, 9));
        box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        int flags = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditText key = field(box, "API Key", "", flags);
        EditText secret = field(box, "Secret", "", flags);
        for (EditText field : new EditText[]{key, secret}) {
            field.setSaveEnabled(false);
            field.setImeOptions(field.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        String title = FuturesApi.DEMO.equals(realm) ? "测试网 HMAC 密钥"
                : FuturesApi.LIVE.equals(realm) ? "实盘 HMAC 交易密钥" : "实盘 HMAC 只读密钥";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setMessage("密钥由手机加密保存。请使用对应环境的 API 密钥。")
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnDismissListener(v -> {
            key.setText("");
            secret.setText("");
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (ACCOUNT_BUSY.get() || AutoFollowService.running()) {
                    throw new IllegalStateException("请先停止自动运行并等待账户操作结束");
                }
                Secrets value = new Secrets(text(key), text(secret));
                boolean held = FuturesApi.DEMO.equals(realm) && (DemoOrders.hasWork(store)
                        || AutoFollowService.hasExposure(store, AutoFollowService.DRILL)
                        || AutoFollowService.hasExposure(store, AutoFollowService.DEMO_WEB));
                held |= FuturesApi.LIVE.equals(realm) && AutoFollowService.hasExposure(store, AutoFollowService.REAL);
                if (held
                        && !Secrets.load(store, realm).key.equals(value.key)) {
                    throw new IllegalStateException("跟单仓位和订单对账前，请保留原账户");
                }
                Secrets.save(store, realm, value);
                key.setText("");
                secret.setText("");
                dialog.dismiss();
                accountStatus = title + "已保存在手机";
                refresh();
            } catch (Exception invalid) { error(invalid); }
        });
    }

    private void resolveLeaderName() {
        if (AutoFollowService.running()) return;
        String portfolio = store.config().portfolio;
        if (portfolio.equals(requestedNamePortfolio)) return;
        closeNameLookup();
        requestedNamePortfolio = portfolio;
        nameError = "";
        try {
            if (!archive.state(portfolio).name.isEmpty()) return;
            WebsiteCapture lookup = new WebsiteCapture(this, portfolio);
            nameLookup = lookup;
            PROFILE.execute(() -> {
                String failure = "";
                try { archive.saveName(portfolio, lookup.name()); }
                catch (Exception badRead) { failure = message(badRead); }
                finally { lookup.close(); }
                String problem = failure;
                runOnUiThread(() -> {
                    if (nameLookup != lookup || isDestroyed()) return;
                    nameLookup = null;
                    nameError = problem;
                    refresh();
                });
            });
        } catch (Exception failure) {
            nameError = message(failure);
        }
    }

    private void closeNameLookup() {
        if (nameLookup == null) return;
        nameLookup.close();
        nameLookup = null;
        requestedNamePortfolio = "";
    }

    private void chooseStrategy(String kind) {
        try {
            if (ACCOUNT_BUSY.get() || AutoFollowService.running()) {
                throw new IllegalStateException("请先停止当前自动运行或等待账户操作结束");
            }
            Rules.Config config = store.config();
            FollowBook previous = AutoFollowService.savedFollowBook(store, kind, config);
            Rules.Strategy continuing = previous != null && previous.hasWork() ? previous.strategy : null;
            boolean real = AutoFollowService.REAL.equals(kind);
            LinearLayout body = Ui.column(this);
            int padding = Ui.dp(this, 20);
            body.setPadding(padding, Ui.dp(this, 5), padding, padding);
            body.addView(Ui.text(this, (real ? "实盘" : "测试网") + " · 组合 " + config.portfolio, 13, Ui.MUTED));
            RadioGroup options = new RadioGroup(this);
            options.setOrientation(RadioGroup.VERTICAL);
            RadioButton points = null;
            if (continuing == null) {
                points = strategyOption(options, Rules.Strategy.POINTS, "使用合约设置中的保证金、杠杆和点差。");
                strategyOption(options, Rules.Strategy.EXPOSURE, "按带单成交额占保证金余额的倍数换算仓位，沿用你的杠杆和点差。");
                options.check(points.getId());
                body.addView(options);
            } else {
                body.addView(Ui.heading(this, continuing.label, 17));
                body.addView(Ui.text(this, "沿用原跟单方式，继续处理已有仓位和等待。", 13, Ui.MUTED));
            }
            LinearLayout copyFields = Ui.column(this);
            EditText factor = copyFactorField(copyFields, previous == null ? Rules.ONE : previous.copyFactor);
            EditText slippage = copySlippageField(copyFields, previous == null ? LocalData.DEFAULT_SLIPPAGE : previous.copySlippage);
            if (continuing == Rules.Strategy.EXPOSURE) showCopyAdjustmentRisk(copyFields);
            body.addView(copyFields);
            TextView settings = Ui.text(this, "", 13, Ui.MUTED);
            body.addView(settings);
            int pointsId = points == null ? View.NO_ID : points.getId();
            java.util.function.Supplier<Rules.Strategy> choice = () -> continuing != null ? continuing
                    : options.getCheckedRadioButtonId() == pointsId ? Rules.Strategy.POINTS : Rules.Strategy.EXPOSURE;
            Runnable update = () -> {
                Rules.Strategy selected = choice.get();
                copyFields.setVisibility(selected == Rules.Strategy.EXPOSURE ? View.VISIBLE : View.GONE);
                Ui.setText(settings, strategySettings(config, selected, real));
            };
            options.setOnCheckedChangeListener((group, checked) -> update.run());
            update.run();
            ScrollView scroll = new ScrollView(this);
            scroll.addView(body);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(continuing == null ? "选择跟单方式" : "继续跟单")
                    .setView(scroll).setNegativeButton("取消", null)
                    .setPositiveButton(real ? "下一步" : "启动测试网跟单", null).show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Rules.Strategy selected = choice.get();
                BigDecimal value = Rules.ONE;
                BigDecimal limit = LocalData.DEFAULT_SLIPPAGE;
                if (selected == Rules.Strategy.EXPOSURE) {
                    value = readCopyField(factor, true);
                    limit = readCopyField(slippage, false);
                    if (value == null || limit == null) return;
                }
                dialog.dismiss();
                if (real) confirmLiveStart(config.portfolio, selected, value, limit);
                else startAuto(kind, selected, value, limit);
            });
        } catch (Exception failure) { error(failure); }
    }

    private void confirmLiveStart(String portfolio, Rules.Strategy strategy, BigDecimal factor, BigDecimal slippage) {
        new AlertDialog.Builder(this).setTitle("当前为实盘跟单")
                .setMessage("组合 " + portfolio + "\n" + strategy.label
                        + "\n\n确认后将连接币安实盘账户，并可使用真实资金自动提交合约委托。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认启动实盘", (dialog, which) -> {
                    if (!portfolio.equals(store.config().portfolio)) {
                        error(new IllegalStateException("当前组合已更换，请重新确认实盘跟单"));
                        return;
                    }
                    startAuto(AutoFollowService.REAL, strategy, factor, slippage);
                }).show();
    }

    private RadioButton strategyOption(RadioGroup group, Rules.Strategy strategy, String description) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setMinHeight(Ui.dp(this, 48));
        option.setText(strategy.label);
        option.setTextColor(Ui.INK);
        option.setTextSize(16);
        group.addView(option);
        TextView copy = Ui.text(this, description, 13, Ui.MUTED);
        copy.setPadding(Ui.dp(this, 30), 0, 0, Ui.dp(this, 5));
        copy.setOnClickListener(v -> group.check(option.getId()));
        group.addView(copy);
        return option;
    }

    private static String strategySettings(Rules.Config config, Rules.Strategy strategy, boolean real) {
        List<String> settings = new ArrayList<>();
        for (Map.Entry<String, Rules.ContractRule> row : config.rules.entrySet()) {
            Rules.ContractRule rule = row.getValue();
            settings.add(row.getKey() + " · " + (strategy == Rules.Strategy.POINTS ? ruleText(rule).replace('\n', ' ') :
                    rule.leverage + " 倍 · 最大不利价差 " + Rules.show(rule.slippagePercent) + "%"));
        }
        return "\n" + String.join("\n", settings)
                + (real ? "\n\n启动后会自动向币安实盘提交委托。停止运行后，已成交仓位仍留在账户。" : "");
    }

    private void startAuto(String kind) {
        startAuto(kind, Rules.Strategy.POINTS, Rules.ONE, LocalData.DEFAULT_SLIPPAGE);
    }

    private void startAuto(String kind, Rules.Strategy strategy, BigDecimal factor, BigDecimal slippage) {
        try {
            if (ACCOUNT_BUSY.get() || AutoFollowService.running()) {
                throw new IllegalStateException("请先停止当前自动运行或等待账户操作结束");
            }
            if (!AutoFollowService.WATCH.equals(kind)) {
                String realm = AutoFollowService.REAL.equals(kind) ? FuturesApi.LIVE : FuturesApi.DEMO;
                if (!store.prefs.contains("credentials_" + realm)) {
                    throw new IllegalStateException("请先在「账户」保存" + (FuturesApi.LIVE.equals(realm) ? "实盘交易" : "测试网") + "密钥");
                }
                if (!askNotifications()) return;
                if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
                    throw new IllegalStateException("请先在系统设置允许跟单成交通知");
                }
            }
            closeNameLookup();
            AutoFollowService.reserveStart(true);
            try { startForegroundService(new Intent(this, AutoFollowService.class).setAction(kind)
                    .putExtra(AutoFollowService.EXTRA_STRATEGY, strategy.name())
                    .putExtra(AutoFollowService.EXTRA_COPY_FACTOR, Rules.show(factor))
                    .putExtra(AutoFollowService.EXTRA_COPY_SLIPPAGE, Rules.show(slippage))); }
            catch (Exception failure) {
                AutoFollowService.reserveStart(false);
                throw failure;
            }
        } catch (Exception failure) { error(failure); }
    }

    private EditText copyFactorField(LinearLayout parent, BigDecimal factor) {
        EditText input = field(parent, "复制系数", Rules.show(factor),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        parent.addView(Ui.text(this, "0.5× 为一半 · 1× 为原比例 · 2× 为两倍", 13, Ui.MUTED));
        return input;
    }

    private EditText copySlippageField(LinearLayout parent, BigDecimal percent) {
        return field(parent, "调仓最大不利价差（%）", Rules.show(percent),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    }

    private void showCopyAdjustmentRisk(LinearLayout parent) {
        TextView risk = Ui.text(this, "改系数可能立即触发市价减仓，按实际成交价结算盈亏，并可能产生滑点。"
                + "调仓价差只约束加仓。加仓会增加保证金占用和杠杆敞口，成交可能产生手续费。", 13, Ui.WARNING);
        int padding = Ui.dp(this, 10);
        risk.setPadding(padding, padding, padding, padding);
        risk.setBackground(Ui.fill(this, 0xfffff6e4, 10, 0xffecdbb0));
        parent.addView(risk);
    }

    private static BigDecimal readCopyField(EditText field, boolean factor) {
        try { return factor ? Rules.copyFactor(text(field)) : Rules.adjustmentSlippage(text(field)); }
        catch (IllegalArgumentException invalid) {
            field.setError(invalid.getMessage());
            return null;
        }
    }

    private void editCopyFactor() {
        FollowBook book = displayedBook;
        String mode = displayedMode;
        if (book == null || book.strategy != Rules.Strategy.EXPOSURE || !AutoFollowService.running()
                || !AutoFollowService.followsWebsite(mode) || !mode.equals(store.prefs.getString("auto_active", ""))) return;
        LinearLayout content = Ui.column(this);
        int padding = Ui.dp(this, 20);
        content.setPadding(padding, 0, padding, padding);
        EditText input = copyFactorField(content, book.copyFactor);
        EditText slippage = copySlippageField(content, book.copySlippage);
        content.addView(Ui.text(this, "调仓减仓直接提交市价单。调仓加仓按参考价和这里的最大不利价差等待。"
                + "等待期间带单员加减仓，会按新的仓位比例更新目标。", 13, Ui.MUTED));
        showCopyAdjustmentRisk(content);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("调整复制系数").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            BigDecimal factor = readCopyField(input, true);
            BigDecimal limit = readCopyField(slippage, false);
            if (factor == null || limit == null) return;
            try {
                startService(new Intent(this, AutoFollowService.class).setAction(AutoFollowService.COPY_FACTOR)
                        .putExtra("mode", mode).putExtra("portfolio", book.config.portfolio)
                        .putExtra(AutoFollowService.EXTRA_COPY_FACTOR, Rules.show(factor))
                        .putExtra(AutoFollowService.EXTRA_COPY_SLIPPAGE, Rules.show(limit)));
                dialog.dismiss();
            } catch (Exception failure) {
                error(failure);
            }
        });
    }

    private void autoCommand(String action) {
        if (AutoFollowService.running()) {
            try { startService(new Intent(this, AutoFollowService.class).setAction(action)); }
            catch (Exception failure) { error(failure); }
        }
    }

    // 遇到地区提示或建档卡页时，尽量把服务正在使用的 WebView 挂到对话框，保留原网页的分页状态。
    private void openWebsite() {
        FrameLayout prompt = new FrameLayout(this);
        try {
            String portfolio = store.config().portfolio;
            Uri target = Uri.parse(WebsiteCapture.portfolioUrl(portfolio));
            boolean baseline = store.prefs.getBoolean("auto_baseline_waiting", false)
                    && store.prefs.getString("auto_web_access", "").isEmpty();
            boolean attached = baseline ? WebsiteCapture.attachBaselinePrompt(portfolio, prompt)
                    : WebsiteCapture.attachRegionPrompt(portfolio, prompt);
            if (!attached) {
                if (baseline) autoCommand(AutoFollowService.RETRY_WEB);
                else openInBrowser(target);
                return;
            }
            prompt.setMinimumHeight(Math.max(Ui.dp(this, 240), surface.getHeight() * 2 / 3));
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(baseline ? "在最新操作记录中点「更多」" : "币安官网地区提示")
                    .setView(prompt).setNegativeButton("返回", null)
                    .setPositiveButton(baseline ? "核对并继续" : "重试读取", (opened, which) -> {
                        WebsiteCapture.detachRegionPrompt(prompt);
                        autoCommand(AutoFollowService.RETRY_WEB);
                    });
            if (!baseline) builder.setNeutralButton("用浏览器打开", (opened, which) -> openInBrowser(target));
            websitePrompt = builder.create();
            websitePrompt.setOnDismissListener(dialog -> {
                WebsiteCapture.detachRegionPrompt(prompt);
                websitePrompt = null;
            });
            websitePrompt.show();
        } catch (RuntimeException failure) {
            WebsiteCapture.detachRegionPrompt(prompt);
            websitePrompt = null;
            error(failure);
        }
    }

    private void openInBrowser(Uri target) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, target)); }
        catch (RuntimeException failure) { error(failure); }
    }

    private void chooseInitialPositions() {
        AutoFollowService.InitialPositions preview = displayedInitialPositions;
        if (preview == null || !preview.canAdd() || !AutoFollowService.running()) return;
        List<Rules.Signal> candidates = preview.candidates;
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < labels.length; i++) {
            Rules.Signal row = candidates.get(i);
            labels[i] = row.symbol + (row.action.isLong ? " · 多" : " · 空")
                    + "  " + TradeNotice.positionQuantity(row.quantity) + " " + Rules.base(row.symbol)
                    + " @ " + TradeNotice.positionPrice(row.price);
        }
        Set<String> checked = new LinkedHashSet<>();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择要补跟的仓位")
                .setMultiChoiceItems(labels, null, (d, index, wanted) -> {
                    String id = candidates.get(index).id;
                    if (wanted) checked.add(id);
                    else checked.remove(id);
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!checked.isEmpty());
                }).setNegativeButton("取消", null)
                .setPositiveButton("补跟所选", (d, w) -> submitInitialPositions(preview, checked.toArray(new String[0]))).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    // 用户确认的是所见仓位快照。档案水位或补录笔数变化后，需要按新的预览重新选择。
    private void submitInitialPositions(AutoFollowService.InitialPositions preview, String[] selected) {
        if (!AutoFollowService.running()) return;
        AutoFollowService.InitialPositions latest = displayedInitialPositions;
        if (latest == null || latest.head != preview.head || latest.backfilled != preview.backfilled) {
            error(new IllegalStateException("官网持仓已变化，请重新选择要补跟的仓位"));
            return;
        }
        try {
            startService(new Intent(this, AutoFollowService.class).setAction(AutoFollowService.INITIAL_CHOICE)
                    .putExtra("portfolio", preview.portfolio).putExtra("head", preview.head)
                    .putExtra("backfilled", preview.backfilled).putExtra("positions", selected));
        } catch (RuntimeException failure) { error(failure); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST) return;
        String portfolio = exportPortfolio;
        exportPortfolio = null;
        if (result != RESULT_OK || data == null || data.getData() == null || portfolio == null) return;
        Uri uri = data.getData();
        EXPORT.execute(() -> {
            String outcome;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("无法打开导出文件");
                archive.export(portfolio, output);
                outcome = "全部成交记录已导出";
            } catch (Exception failure) {
                outcome = "导出失败 · " + message(failure);
            }
            String resultText = outcome;
            runOnUiThread(() -> Toast.makeText(this, resultText, Toast.LENGTH_LONG).show());
        });
    }

    private void exportRecords() {
        try {
            exportPortfolio = store.config().portfolio;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "binance-" + exportPortfolio + "-trades.csv");
            startActivityForResult(intent, EXPORT_REQUEST);
        } catch (Exception problem) { error(problem); }
    }

    private boolean askNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 45);
        return false;
    }

    private void refresh() {
        refreshBackgroundStatus();
        if (accountView != null) accountView.setText(accountStatus);
        try {
            if (autoView != null) refreshFollow();
            if (archiveEntries != null) refreshArchive();
            if (balanceEntries != null) refreshBalanceHistory();
        } catch (Exception failure) {
            if (autoView != null) autoView.setText(message(failure));
            if (archiveSummary != null) archiveSummary.setText(message(failure));
            if (balanceSummary != null) balanceSummary.setText(message(failure));
            if (accountView != null) accountView.setText(message(failure));
        }
    }

    private void refreshBalanceHistory() {
        String portfolio = store.config().portfolio;
        long checked = balanceArchive.checked(portfolio);
        String status = checked == 0 ? "跟踪期间每分钟检查一次"
                : "每分钟检查 · 最近成功 " + TradeNotice.fullTime(checked);
        status += " · 仅官网划转";
        String problem = store.prefs.getString("balance_error_" + portfolio, "");
        Ui.setText(balanceSummary, status + (problem.isEmpty() ? "" : "\n本轮读取未完成 · " + problem));
        balanceEntries.prepare(portfolio, balanceArchive.count(portfolio));
        List<String> rows = new ArrayList<>();
        for (LeaderBalances.Event item : balanceArchive.page(portfolio, balanceEntries.size, balanceEntries.offset())) {
            rows.add(item.text());
        }
        balanceEntries.renderPage(rows, "暂无官网划转记录");
    }

    private String leaderTitle(String portfolio, LeaderArchive.State state) {
        if (!state.name.isEmpty()) return state.name + "\n组合 " + portfolio;
        String title = "组合 " + portfolio;
        if (portfolio.equals(requestedNamePortfolio) && !nameError.isEmpty()) return title + "\n名称暂未读取 · " + nameError;
        return title + "\n等待官网确认名称";
    }

    private void refreshArchive() throws Exception {
        String portfolio = store.config().portfolio;
        LeaderArchive.State state = archive.state(portfolio);
        long count = ownRecords ? archive.accountFillCount(portfolio) : archive.count(portfolio);
        String scope = portfolio + (ownRecords ? "|own" : "|leader");
        archiveEntries.prepare(scope, count);
        long offset = archiveEntries.offset();
        long last = Math.min(count, offset + archiveEntries.size);
        String label = ownRecords ? "组合 " + portfolio + " · 本机已核对成交" : leaderTitle(portfolio, state);
        if (!ownRecords && state.started > 0) label += "\n跟踪开始 " + TradeNotice.fullTime(state.started);
        if (count > 0) label += "\n共 " + count + " 笔 · 显示 " + (offset + 1) + "–" + last;
        if (!ownRecords && !state.historyDetail.isEmpty()) label += "\n" + state.historyDetail;
        if (!ownRecords && state.gap) label += "\n官网历史有缺口 · 缺口后的可见记录已存档";
        Ui.setText(archiveSummary, label);
        exportButton.setEnabled(count > 0);
        String stamp = scope + "|" + count + "|" + (ownRecords ? "" : state.captured + "|" + state.head) + "|" + offset;
        if (stamp.equals(archiveStamp)) return;
        archiveStamp = stamp;
        List<String> entries = new ArrayList<>();
        if (ownRecords) {
            for (LeaderArchive.AccountFill fill : archive.accountFills(portfolio, archiveEntries.size, offset)) {
                entries.add(fill.summary + "\n" + AutoFollowService.label(fill.mode));
            }
            archiveEntries.renderPage(entries, "暂无由本机确认的账户成交");
            return;
        }
        List<LeaderArchive.Record> rows = archive.page(portfolio, archiveEntries.size, offset);
        for (LeaderArchive.Record item : rows) {
            Rules.Signal signal = item.signal;
            String detail = signal.symbol + " " + signal.action.label + " · " + Rules.show(signal.quantity) + " "
                    + Rules.base(signal.symbol) + "\n" + TradeNotice.fullTime(signal.time) + " · " + item.source()
                    + "\n均价 " + Rules.show(signal.price) + " USDT · 成交额 " + Rules.show(signal.displayTurnover()) + " USDT";
            if (!signal.action.opens) detail += "\n" + TradeNotice.pnl(signal.realizedPnl);
            BigDecimal multiple = signal.exposureRatio();
            if (multiple != null) {
                detail += "\n带单保证金余额 " + Rules.show(signal.leaderMargin) + " USDT · "
                        + TradeNotice.exposureMultiple(multiple) + " 倍";
            }
            entries.add(detail);
        }
        archiveEntries.renderPage(entries, "尚未抓取到成交记录");
    }

    private interface AccountAction { String get() throws Exception; }

    private void runAccount(AccountAction call) {
        if (AutoFollowService.running()) {
            error(new IllegalStateException("请先停止自动运行再手动操作账户"));
            return;
        }
        if (!ACCOUNT_BUSY.compareAndSet(false, true)) return;
        accountStatus = "正在连接币安";
        refresh();
        ACCOUNT.execute(() -> {
            String outcome;
            try { outcome = call.get(); }
            catch (Exception exc) { outcome = message(exc); }
            finally { ACCOUNT_BUSY.set(false); }
            String result = outcome;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                accountStatus = result;
                refresh();
            });
        });
    }

    private void refreshFollow() throws Exception {
        Rules.Config rules = store.config();
        String portfolio = rules.portfolio;
        LeaderArchive.State state = archive.state(portfolio);
        Ui.setDetail(leaderView, leaderTitle(portfolio, state));
        drawLeaderPositions(portfolio, state);
        refreshInitialPositions(portfolio);
        String mode = store.prefs.getString("auto_last_mode", "");
        String status = store.prefs.getString("auto_status", "待启动");
        boolean matches = portfolio.equals(store.prefs.getString("auto_portfolio", portfolio))
                && store.prefs.getLong("auto_portfolio_selection", 0) == store.portfolioSelection();
        if (!matches) status = "当前组合待启动";
        if (!AutoFollowService.running() && store.prefs.contains("auto_active") && !status.startsWith("系统")) {
            status = "等待系统恢复后台服务，或重新启动自动运行";
        }
        String capture = matches ? store.prefs.getString("auto_capture", "") : "";
        if (capture.isEmpty() && state.captured > 0) capture = TradeNotice.fullTime(state.captured);
        String failure = matches ? store.prefs.getString("auto_capture_error", "") : "";
        boolean accessLimited = matches && !store.prefs.getString("auto_web_access", "").isEmpty();
        boolean baselineWaiting = matches && store.prefs.getBoolean("auto_baseline_waiting", false);
        websiteHelp.setVisibility(accessLimited || baselineWaiting ? View.VISIBLE : View.GONE);
        openWebsite.setText(baselineWaiting && !accessLimited ? "在应用内展开成交" : "打开币安组合页");
        openWebsite.setEnabled(accessLimited || AutoFollowService.running());
        retryWebsite.setEnabled((accessLimited || baselineWaiting) && AutoFollowService.running());
        Ui.setDetail(autoView, status + (capture.isEmpty() ? "" : "\n上次有效抓取 " + capture)
                + (failure.isEmpty() ? "" : "\n上次取数失败 " + failure));
        JSONObject saved = mode.isEmpty() ? null : store.load(AutoFollowService.file(mode));
        FollowBook book = saved == null ? null : FollowBook.restore(saved);
        if (book != null && (!book.config.portfolio.equals(portfolio)
                || book.portfolioSelection != store.portfolioSelection())) book = null;
        String scope = portfolio + "|" + mode;
        displayedMode = mode;
        displayedBook = book;
        boolean copies = book != null && AutoFollowService.followsWebsite(mode) && book.strategy == Rules.Strategy.EXPOSURE;
        copyStatus.setVisibility(copies ? View.VISIBLE : View.GONE);
        editCopy.setVisibility(copies && AutoFollowService.running()
                && mode.equals(store.prefs.getString("auto_active", "")) ? View.VISIBLE : View.GONE);
        if (copies) {
            List<String> waitingForSignal = new ArrayList<>();
            for (FollowBook.Leg leg : book.legs.values()) if (leg.adjustOnSignal) {
                waitingForSignal.add(leg.symbol + (leg.isLong ? " 多" : " 空"));
            }
            Ui.setDetail(copyStatus, "复制系数 " + Rules.show(book.copyFactor) + "× · 调仓价差 "
                    + Rules.show(book.copySlippage) + "%"
                    + (book.copyReplanPending ? "\n复制系数已保存 · 等待官网与账户核对" : "")
                    + (waitingForSignal.isEmpty() ? "" : "\n等待下一笔成交 · " + String.join("、", waitingForSignal)));
        }
        leaderMarginText = "";
        if (matches && AutoFollowService.followsWebsite(mode)) {
            String margin = store.prefs.getString("auto_leader_margin", "");
            long at = store.prefs.getLong("auto_leader_margin_at", 0);
            leaderMarginText = margin.isEmpty() ? "带单保证金余额 · 等待官网读取"
                    : "带单保证金余额 " + margin + " USDT · " + TradeNotice.time(at);
        }
        refreshAccountDisplay();
        renderDetections(portfolio, scope, mode, book, rules);
        drawWaits(scope, mode, book, rules);
        String pending = "";
        if (book != null && book.pending != null) {
            pending = "待核对 · " + book.pending.getString("symbol") + " · " + book.pending.getString("id");
            String issue = book.pending.optString("issue");
            if (!issue.isEmpty()) pending += "\n" + issue;
        }
        Ui.setDetail(orderStatus, pending);
        orderStatus.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        autoHistory.renderText(scope, book == null ? Collections.emptyList() : newestFirst(book.log), "暂无自动跟单委托");
    }

    private void renderDetections(String portfolio, String scope, String mode, FollowBook book, Rules.Config config)
            throws Exception {
        if (AutoFollowService.DRILL.equals(mode)) {
            signals.renderText(scope, book == null ? Collections.emptyList() : newestFirst(book.detections), "暂无新增操作");
            return;
        }
        signals.prepare(portfolio + "|detections", archive.detectionCount(portfolio));
        List<String> rows = new ArrayList<>();
        for (LeaderArchive.Record row : archive.detections(portfolio, signals.size, signals.offset())) {
            rows.add(row.detectionText(config.symbols.contains(row.signal.symbol)));
        }
        signals.renderPage(rows, "暂无新增操作");
    }

    private static List<String> newestFirst(JSONArray history) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = history.length() - 1; i >= 0; i--) result.add(history.getString(i));
        return result;
    }

    private void drawRunStats(String mode, FollowBook book) {
        long now = System.currentTimeMillis();
        AutoFollowService.WalletSnapshot wallet = AutoFollowService.walletSnapshot();
        String realm = mode.isEmpty() ? wallet.realm : AutoFollowService.REAL.equals(mode) || AutoFollowService.WATCH.equals(mode)
                ? FuturesApi.LIVE : FuturesApi.DEMO;
        boolean hasTracked = book != null && (book.trackingSince > 0 || book.trackedMillis > 0);
        String time = hasTracked ? duration(book.trackingMillis(now)) : "尚未记录";
        String pnl = "等待跟单运行";
        BigDecimal total = null;
        List<String> notes = new ArrayList<>();
        if (AutoFollowService.WATCH.equals(mode)) pnl = "监测模式";
        else if (hasTracked) {
            AutoFollowService.AccountPositions account = AutoFollowService.accountPositions();
            long age = now - account.time;
            boolean current = realm.equals(account.realm) && account.time > 0
                    && age >= 0 && age <= SNAPSHOT_FRESH_MS && account.error.isEmpty();
            total = book.totalPnl(current ? account.positions : Collections.emptyMap());
            if (total == null) {
                pnl = "待核对";
                if (showMoney) notes.add("已确认的已实现盈亏 " + Rules.show(book.realizedPnl) + " USDT");
            } else {
                pnl = overviewAmount(total) + " USDT";
                if (book.pnlIncomplete) notes.add("部分盈亏待核对");
            }
            notes.add("本轮已实现 + 跟单浮盈亏 · 手续费和资金费另计");
        }
        String balance = "等待查询";
        if (realm.equals(wallet.realm) && wallet.balance != null) {
            balance = overviewAmount(wallet.balance.balance);
            if (!wallet.error.isEmpty() || now - wallet.time > SNAPSHOT_FRESH_MS) {
                notes.add("钱包上次查询 " + TradeNotice.time(wallet.time));
            }
        }
        if (realm.equals(wallet.realm) && !wallet.error.isEmpty()) notes.add(wallet.error);
        String tracking = mode.isEmpty() ? (realm.isEmpty() ? "等待启动" : "账户 · " + realmLabel(realm))
                : AutoFollowService.label(mode);
        if (book != null && AutoFollowService.followsWebsite(mode)) tracking += " · " + book.strategy.label;
        runStats.show(tracking, pnl, total == null ? 0 : total.signum(), total != null, time, balance,
                String.join("\n", notes), leaderMarginText, showMoney);
    }

    private static String realmLabel(String realm) { return FuturesApi.DEMO.equals(realm) ? "测试网" : "实盘"; }

    private static String overviewAmount(BigDecimal amount) {
        return String.format(Locale.US, "%,.4f", amount).replaceFirst("\\.?0+$", "");
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        String clock = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600 % 24, seconds / 60 % 60, seconds % 60);
        return seconds < 86400 ? clock : seconds / 86400 + " 天 " + clock;
    }

    private void refreshInitialPositions(String portfolio) {
        AutoFollowService.InitialPositions preview = AutoFollowService.initialPositions();
        if (preview == null || !AutoFollowService.running() || !portfolio.equals(preview.portfolio)) {
            displayedInitialPositions = null;
            initialCard.setVisibility(View.GONE);
            return;
        }
        displayedInitialPositions = preview;
        initialCard.setVisibility(View.VISIBLE);
        addExisting.setEnabled(preview.canAdd());
        StringBuilder details = new StringBuilder(preview.strategy.label);
        if (preview.strategy == Rules.Strategy.EXPOSURE) {
            details.append(" · ").append(Rules.show(preview.copyFactor)).append("×")
                    .append("\n按推算开仓均价和数量估算已有名义仓位。");
            if (preview.margin == null) details.append("\n等待读取带单保证金余额");
        } else details.append("\n每个标的方向使用一份配置保证金。");
        details.append("\n账户启动前的同方向仓位保留至带单员新增开仓。");
        List<String> positions = new ArrayList<>();
        for (Rules.Signal row : preview.candidates) {
            positions.add(positionDescription(row.symbol, row.action.isLong, Rules.show(row.quantity) + " " + Rules.base(row.symbol),
                    row.price, row.time, true, null));
        }
        if (preview.withoutPrice > 0) details.append("\n有 ").append(preview.withoutPrice).append(" 个方向缺少可核对的开仓均价");
        Ui.setDetail(initialDetails, details.toString());
        initialPositions.renderText(preview.portfolio + "|" + preview.mode, positions, "暂无可补仓位");
    }

    private void drawLeaderPositions(String portfolio, LeaderArchive.State state) {
        LeaderArchive.Projection projection = archive.projection(portfolio, state);
        LocalData.LeaderBalance balance = store.leaderBalance(portfolio);
        if (balance == null) {
            for (int i = state.window.size() - 1; i >= 0; i--) {
                BigDecimal previous = state.window.get(i).leaderMargin;
                if (previous == null) continue;
                balance = new LocalData.LeaderBalance(previous, 0);
                break;
            }
        }
        long now = System.currentTimeMillis();
        AutoFollowService.LeaderMarket market = AutoFollowService.leaderMarket();
        boolean useMarks = market.canUseMarks(now, balance);
        leaderRiskExpires = useMarks ? market.marksAt + AutoFollowService.LEADER_MARK_FRESH_MS : 0;
        boolean useLatestPrices = market.hasLatestPrices(now);
        if (useLatestPrices) {
            long pricesExpire = market.pricesAt + AutoFollowService.LEADER_MARK_FRESH_MS;
            leaderRiskExpires = leaderRiskExpires == 0 ? pricesExpire : Math.min(leaderRiskExpires, pricesExpire);
        }
        LeaderRisk.Result risk = LeaderRisk.estimate(projection.holdings, balance == null ? null : balance.value,
                useMarks ? market.marks : Collections.emptyMap(), market.tiers);
        String asOf = leaderRiskDescription(state, projection, balance, market, risk, now);
        Ui.setText(leaderPositionsView, asOf);
        leaderPositionsView.setVisibility(asOf.isEmpty() ? View.GONE : View.VISIBLE);
        List<Ui.PositionCard> positions = new ArrayList<>();
        for (Map.Entry<String, LeaderHistory.Holding> entry : new TreeMap<>(projection.holdings).entrySet()) {
            String[] leg = entry.getKey().split("\\|", 2);
            LeaderHistory.Holding holding = entry.getValue();
            BigDecimal quantity = holding.displayQuantity();
            if (quantity.signum() <= 0) continue;
            String amount = (holding.inferredQuantity == null ? "" : "≈ ")
                    + TradeNotice.positionQuantity(quantity) + " " + Rules.base(leg[0]);
            boolean longSide = "LONG".equals(leg[1]);
            String detail = positionDescription(leg[0], longSide, amount,
                    holding.entryPrice, holding.updatedAt, true, null);
            String note = holding.inferredQuantity == null ? "数量来自可见成交" : "数量由成交与盈亏反推";
            if (holding.costFromClose) note += " · 均价含盈亏反推";
            if (holding.uncertainBasis) note += " · 平仓盈亏未能核对均价";
            LeaderRisk.Boundary value = risk.prices.get(leg[0]);
            String liquidation = forecastText(value);
            BigDecimal latestPrice = useLatestPrices ? market.latestPrices.get(leg[0]) : null;
            BigDecimal pnl = holding.unrealized(latestPrice, longSide);
            int pnlColor = pnl == null ? Ui.MUTED : pnl.signum() < 0 ? Ui.NEGATIVE : pnl.signum() > 0 ? Ui.POSITIVE : Ui.INK;
            String pnlText = pnl == null ? "—" : (pnl.signum() > 0 ? "+" : "") + positionMoneyText(pnl, "USDT");
            Ui.PositionCard card = new Ui.PositionCard(detail, note, List.of(
                    new Ui.Metric("估算强平价", liquidation, value == null ? Ui.MUTED : Ui.WARNING),
                    new Ui.Metric("估算浮盈亏", pnlText, pnlColor)));
            positions.add(card);
        }
        leaderPositions.renderPositions(portfolio, positions,
                projection.lastTrade == 0 ? "首次抓取后开始推算" : "可见成交推算仓位为零");
    }

    private static String leaderRiskDescription(LeaderArchive.State state, LeaderArchive.Projection projection,
            LocalData.LeaderBalance balance, AutoFollowService.LeaderMarket market, LeaderRisk.Result risk, long now) {
        List<String> lines = new ArrayList<>();
        if (projection.lastTrade > 0) lines.add("截至 " + TradeNotice.fullTime(projection.lastTrade));
        if (state.gap) lines.add("成交有缺口 · 按已见记录估算");
        if (risk.prices.isEmpty()) return String.join("\n", lines);
        lines.add("全仓估算 · 浮盈亏按最新成交价");
        if (balance != null) lines.add(balance.at > 0 ? "余额快照 " + TradeNotice.fullTime(balance.at) : "使用历史成交附带余额");
        else if (risk.assumptions.contains(LeaderRisk.Assumption.MARGIN)) {
            lines.add("余额未知 · 按 " + Rules.show(LeaderRisk.REFERENCE_LEVERAGE) + " 倍杠杆假设");
        }
        List<String> assumptions = new ArrayList<>();
        if (risk.assumptions.contains(LeaderRisk.Assumption.MARK)) assumptions.add("缺标记价用持仓均价");
        if (risk.assumptions.contains(LeaderRisk.Assumption.TIER)) {
            assumptions.add("缺档位按 " + Rules.show(LeaderRisk.REFERENCE_RATE.movePointRight(2)) + "% 维持率");
        }
        if (risk.assumptions.contains(LeaderRisk.Assumption.EXTENDED_TIER)) assumptions.add("超档位按末档估算");
        if (risk.assumptions.contains(LeaderRisk.Assumption.MISSING_PRICE)) assumptions.add("缺价仓位暂未计入强平估算");
        if (!market.tiers.isEmpty() && now - market.tiersAt > AutoFollowService.LEADER_TIER_FRESH_MS) {
            assumptions.add("沿用档位快照");
        }
        if (!assumptions.isEmpty()) lines.add(String.join(" · ", assumptions));
        if (risk.atMaintenance) lines.add("参考价已到估算维持线");
        return String.join("\n", lines);
    }

    private static String forecastText(LeaderRisk.Boundary value) {
        if (value == null) return "缺可用价格";
        if (value.below == null && value.above == null) return "无正值触发价";
        String down = value.below == null ? "" : "≈ " + TradeNotice.positionPrice(value.below) + " USDT";
        String up = value.above == null ? "" : "≈ " + TradeNotice.positionPrice(value.above) + " USDT";
        if (down.isEmpty() || up.isEmpty()) return down + up;
        return "下行 " + down + "\n上行 " + up;
    }

    private void refreshAccountDisplay() {
        if (accountPositionsView == null) return;
        AutoFollowService.AccountPositions snapshot = AutoFollowService.accountPositions();
        String status = accountPositionStatus(snapshot);
        Ui.setDetail(accountPositionsView, status);
        accountPositionsView.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        accountPositions.renderPositions(snapshot.realm, accountPositionRows(snapshot),
                snapshot.time == 0 ? "可在「账户」保存密钥并查询持仓" : "空仓");
        drawRunStats(displayedMode, displayedBook);
    }

    private static String accountPositionText(AutoFollowService.AccountPositions snapshot) {
        List<String> positions = new ArrayList<>();
        for (Ui.PositionCard card : accountPositionRows(snapshot)) positions.add(card.plainText());
        return accountPositionStatus(snapshot) + "\n" + (positions.isEmpty() ? "空仓" : String.join("\n\n", positions));
    }

    private static String accountPositionStatus(AutoFollowService.AccountPositions snapshot) {
        String realm = realmLabel(snapshot.realm);
        if (snapshot.time == 0 && snapshot.error.isEmpty()) {
            return "等待查询";
        }
        if (snapshot.time == 0) return realm + " · 查询失败\n" + snapshot.error;
        StringBuilder positions = new StringBuilder(realm).append(" · ").append(TradeNotice.time(snapshot.time));
        if (!snapshot.error.isEmpty() || System.currentTimeMillis() - snapshot.time > SNAPSHOT_FRESH_MS) {
            positions.append(" · 上次查询");
        }
        if (!snapshot.error.isEmpty()) positions.append('\n').append(snapshot.error);
        return positions.toString();
    }

    private static List<Ui.PositionCard> accountPositionRows(AutoFollowService.AccountPositions snapshot) {
        List<Ui.PositionCard> positions = new ArrayList<>();
        Map<String, Integer> leverages = AutoFollowService.accountLeverages(snapshot.realm);
        for (Map.Entry<String, FuturesApi.Position> entry : new TreeMap<>(snapshot.positions).entrySet()) {
            FuturesApi.Position row = entry.getValue();
            String symbol = entry.getKey();
            Integer leverage = leverages.get(symbol);
            if (row.longs.signum() > 0) positions.add(accountPosition(symbol, row, true, leverage));
            if (row.shorts.signum() > 0) positions.add(accountPosition(symbol, row, false, leverage));
        }
        return positions;
    }

    private static Ui.PositionCard accountPosition(String symbol, FuturesApi.Position row, boolean isLong, Integer leverage) {
        String pair = symbol.split("_", 2)[0];
        String quote = positionQuote(pair);
        String unit = pair.endsWith(quote) && pair.length() > quote.length() ? Rules.base(pair) : "合约单位";
        BigDecimal qty = isLong ? row.longs : row.shorts;
        String detail = positionDescription(symbol, isLong, Rules.show(qty) + " " + unit,
                isLong ? row.longEntry : row.shortEntry, isLong ? row.longUpdated : row.shortUpdated,
                false, isLong ? row.longUnrealized : row.shortUnrealized);
        FuturesApi.PositionRisk risk = isLong ? row.longRisk : row.shortRisk;
        String marginAsset = risk == null || risk.asset.isEmpty() ? quote : risk.asset;
        BigDecimal liquidation = risk == null ? null : risk.liquidationPrice;
        List<Ui.Metric> metrics = List.of(
                new Ui.Metric("持仓保证金", positionMoneyText(risk == null ? null : risk.initialMargin, marginAsset), Ui.INK),
                new Ui.Metric("强平价", liquidation != null && liquidation.signum() > 0
                        ? TradeNotice.positionPrice(liquidation) + " " + quote : "—",
                        liquidation != null && liquidation.signum() > 0 ? Ui.WARNING : Ui.MUTED),
                new Ui.Metric("杠杆", leverage == null ? "—" : leverage + " 倍", leverage == null ? Ui.MUTED : Ui.ACCENT));
        String note = risk != null && risk.isolatedWallet != null && risk.isolatedWallet.signum() > 0
                ? "逐仓钱包 " + positionMoneyText(risk.isolatedWallet, marginAsset) : "";
        return new Ui.PositionCard(detail, note, metrics);
    }

    private static String positionMoneyText(BigDecimal amount, String unit) {
        return amount == null ? "—" : TradeNotice.positionMoney(amount) + " " + unit;
    }

    private static String positionQuote(String pair) {
        return pair.endsWith("USDC") ? "USDC" : "USDT";
    }

    private static String positionDescription(String symbol, boolean isLong, String quantity,
            BigDecimal entryPrice, long time, boolean estimated, BigDecimal unrealized) {
        String pair = symbol.split("_", 2)[0];
        String quote = positionQuote(pair);
        String line = symbol + (isLong ? " 多" : " 空") + " · " + quantity + "\n"
                + (estimated ? "推算开仓均价 " : "开仓均价 ")
                + (entryPrice == null ? "未提供" : TradeNotice.positionPrice(entryPrice) + " " + quote);
        if (time > 0) line += (estimated ? " · 最近成交 " : " · 更新 ") + TradeNotice.fullTime(time);
        if (unrealized != null) line += "\n浮盈亏 " + TradeNotice.positionMoney(unrealized) + " " + quote;
        return line;
    }

    private void drawWaits(String scope, String mode, FollowBook book, Rules.Config config) {
        List<FollowBook.Entry> waiting = book == null || AutoFollowService.WATCH.equals(mode)
                ? new ArrayList<>() : book.waits();
        Set<String> cancelled = store.waitCancellations(mode);
        waiting.removeIf(entry -> cancelled.contains(entry.id()));
        List<FollowBook.Leg> adjustmentLegs = new ArrayList<>();
        if (book != null && AutoFollowService.followsWebsite(mode)) {
            for (FollowBook.Leg leg : book.legs.values()) {
                if (leg.adjustment != null && !cancelled.contains(leg.adjustment.id())) adjustmentLegs.add(leg);
            }
        }
        AutoFollowService.WaitingPrices prices = AutoFollowService.waitingPrices();
        int total = waiting.size() + adjustmentLegs.size();
        waits.prepare(scope, total);
        int first = (int) waits.offset();
        List<String> descriptions = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        StringBuilder stamp = new StringBuilder(scope).append('|').append(first);
        boolean running = AutoFollowService.running() && mode.equals(store.prefs.getString("auto_active", ""));
        boolean opens = store.prefs.getBoolean("auto_opens", true);
        boolean websiteReady = store.prefs.getString("auto_capture_error", "").isEmpty();
        for (int i = first; i < Math.min(first + waits.size, total); i++) {
            String id, line;
            if (i < waiting.size()) {
                FollowBook.Entry entry = waiting.get(i);
                Rules.ContractRule rule = config.rules.getOrDefault(entry.signal.symbol, book.config.rule(entry.signal.symbol));
                id = entry.id();
                line = waitDescription(entry, mode, prices, rule.slippagePercent)
                        + "\n" + waitRequestStatus(entry, book.pending, running, opens, websiteReady, System.currentTimeMillis());
                if (book.strategy == Rules.Strategy.EXPOSURE) {
                    BigDecimal ratio = entry.signal.exposureRatio();
                    line += ratio == null ? "\n等待带单成交额或保证金余额"
                            : "\n复制系数 " + Rules.show(book.copyFactor)
                                    + "× · 调整后名义倍数 " + TradeNotice.exposureMultiple(ratio.multiply(book.copyFactor, Rules.MC));
                }
            } else {
                FollowBook.Leg leg = adjustmentLegs.get(i - waiting.size());
                FollowBook.CopyAdjustment adjustment = leg.adjustment;
                id = adjustment.id();
                String unit = " " + Rules.base(leg.symbol);
                line = leg.symbol + (leg.isLong ? " 多" : " 空") + " · 复制调仓 " + Rules.show(book.copyFactor) + "×"
                        + "\n本账户持仓 " + Rules.show(leg.owned) + unit
                        + " · 目标 " + (adjustment.target == null ? "待核算" : Rules.show(adjustment.target) + unit);
                if (adjustment.signal != null) {
                    line += "\n带单信号 " + adjustment.signal.action.label + " · " + TradeNotice.fullTime(adjustment.signal.time);
                }
                if (leg.adjustOnSignal) line += "\n差额低于下单要求 · 等待下一笔带单成交";
                else if (adjustment.target != null && adjustment.target.compareTo(leg.owned) != 0) {
                    boolean increase = adjustment.target.compareTo(leg.owned) > 0;
                    if (!increase) line += "\n按新目标市价减仓";
                    else {
                        Rules.Action direction = leg.isLong ? Rules.Action.OPEN_LONG : Rules.Action.OPEN_SHORT;
                        boolean settingPrice = adjustment.fromSetting();
                        BigDecimal limit = book.copySlippage;
                        BigDecimal reference = adjustment.priceReference();
                        line += "\n加仓最大不利价差 " + Rules.show(limit) + "%";
                        if (reference != null) {
                            line += " · " + (settingPrice ? "参考均价 " : "带单成交价 ") + TradeNotice.price(reference) + " USDT";
                            long age = System.currentTimeMillis() - prices.time;
                            Rules.Quote quote = mode.equals(prices.mode) && age >= 0 && age <= 10000 ? prices.quotes.get(leg.symbol) : null;
                            if (quote != null) {
                                BigDecimal price = quote.execution(direction);
                                line += "\n" + (direction.buys() ? "卖一 " : "买一 ") + TradeNotice.price(price)
                                        + (Rules.withinAdversePrice(direction, reference, limit, price) ? " · 价格符合" : " · 等待合适价格");
                            }
                        } else line += " · 等待参考价";
                    }
                }
                if (book.pending != null && id.equals(book.pending.optString("copyAdjustment"))) line += "\n委托待核对";
                else if (!running) line += "\n等待恢复跟单";
            }
            descriptions.add(line);
            ids.add(id);
            stamp.append('\n').append(id).append(line);
        }
        String next = stamp.toString();
        if (next.equals(waitStamp)) return;
        waitStamp = next;
        waits.rows.removeAllViews();
        if (descriptions.isEmpty()) {
            waits.rows.addView(Ui.text(this, "当前没有等待开仓或调仓的委托", 13, Ui.MUTED));
            return;
        }
        for (int i = 0; i < descriptions.size(); i++) {
            String id = ids.get(i);
            TextView details = Ui.text(this, "", 13, Ui.MUTED);
            Ui.setDetail(details, descriptions.get(i));
            int padding = Ui.dp(this, 10);
            details.setPadding(padding, padding, padding, padding);
            details.setBackground(Ui.fill(this, Ui.SURFACE, 12, Ui.EDGE));
            waits.rows.addView(details);
            button(waits.rows, "取消这笔等待", false, v -> cancelWaiting(mode, id));
        }
    }

    private static String waitDescription(FollowBook.Entry entry, String mode,
            AutoFollowService.WaitingPrices prices, BigDecimal slippage) {
        Rules.Signal signal = entry.signal;
        BigDecimal reference = entry.referencePrice();
        String title = signal.symbol + " " + signal.action.label + (entry.existing ? " · 补已有仓位" : "")
                + " · " + TradeNotice.time(signal.time)
                + (entry.recoveryAverage == null ? "\n参考价 " : "\n补页后推算均价 ")
                + TradeNotice.price(reference) + " USDT";
        if (entry.fraction.compareTo(Rules.ONE) < 0) {
            title += " · 剩余跟单意向 " + Rules.show(entry.fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP)) + "%";
        }
        if (!entry.requestIssue.isEmpty()) {
            title += "\n上次请求 · " + entry.requestIssue;
            if (entry.checkFailedAt > 0) title += " · " + TradeNotice.time(entry.checkFailedAt);
            if (entry.checkFailures > 0) title += " · 连续 " + entry.checkFailures + " 次";
        }
        long age = System.currentTimeMillis() - prices.time;
        boolean matchingMode = mode.equals(prices.mode);
        Rules.Quote quote = matchingMode && age >= 0 && age <= 10000 ? prices.quotes.get(signal.symbol) : null;
        if (quote == null) {
            String issue = matchingMode ? prices.issues.get(signal.symbol) : null;
            if (issue != null) return title + "\n" + issue;
            return title + "\n等待可用盘口" + (matchingMode && !prices.error.isEmpty() ? " · " + prices.error : "");
        }
        BigDecimal current = quote.execution(signal.action);
        BigDecimal delta = signal.action.isLong ? current.subtract(reference) : reference.subtract(current);
        BigDecimal adverse = delta.max(Rules.ZERO).divide(reference, Rules.MC).movePointRight(2);
        return title + "\n" + (signal.action.isLong ? "卖一 " : "买一 ") + Rules.show(current)
                + " · 不利价差 " + Rules.show(adverse.setScale(3, RoundingMode.HALF_UP)) + "% / "
                + Rules.show(slippage) + "% · " + (adverse.compareTo(slippage) > 0 ? "超限等待" : "价差达标");
    }

    static String waitRequestStatus(FollowBook.Entry entry, JSONObject pending, boolean running, boolean opens,
            boolean websiteReady, long now) {
        if (pending != null && entry.id().equals(pending.optString("entry"))) {
            return "状态 · 原委托号待核对" + (running ? "" : " · 跟单已停止");
        }
        if (!running) return "状态 · 跟单未运行";
        if (!opens) return "状态 · 开仓已暂停";
        if (pending != null) return "状态 · 等待在途委托核对完成";
        if (!websiteReady) return "状态 · 等待官网成交核对完成";
        if (entry.waitingForCheckRetry(now)) {
            return "状态 · 自动重试排队 · 最早 " + TradeNotice.time(entry.checkRetryAt) + " 后检查";
        }
        if (entry.checkFailures > 0 || entry.requestIssue.endsWith("币安请求超时")) {
            return "状态 · 等待下一轮有效信号，自动重试开仓检查";
        }
        return "状态 · 等待开仓条件满足";
    }

    private void cancelWaiting(String mode, String id) {
        try {
            AutoFollowService.cancelWait(store, mode, id);
            refreshFollow();
        } catch (Exception failure) { error(failure); }
    }

    private String version() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.PackageInfoFlags.of(0)).versionName;
            return name == null ? BuildConfig.VERSION_NAME : name;
        } catch (PackageManager.NameNotFoundException missing) { return BuildConfig.VERSION_NAME; }
    }

    private void button(LinearLayout panel, String label, boolean primary, View.OnClickListener listener) {
        Ui.addButton(panel, Ui.button(this, label, primary, listener));
    }

    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView title = Ui.text(this, label, 13, Ui.MUTED);
        title.setPadding(Ui.dp(this, 2), Ui.dp(this, 9), 0, 0);
        parent.addView(title);
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setInputType(type);
        entry.setTextSize(16);
        entry.setTextColor(Ui.INK);
        entry.setHint(label);
        entry.setText(value);
        parent.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        return entry;
    }

    private static String text(EditText source) { return source.getText().toString().trim(); }

    private static BigDecimal parseAmount(EditText input) {
        String value = text(input);
        if (!value.matches("[0-9]{1,12}(?:\\.[0-9]{1,10})?")) {
            throw new IllegalArgumentException("请输入有效的小数");
        }
        return new BigDecimal(value);
    }

    private static String portfolioId(String input) {
        if (input.matches("[0-9]{8,25}")) return input;
        Uri uri = Uri.parse(input);
        String path = uri.getPath();
        if ("https".equals(uri.getScheme()) && ("www.binance.com".equals(uri.getHost())
                || "binance.com".equals(uri.getHost())) && uri.getUserInfo() == null
                && path != null && path.matches("/(?:[a-zA-Z-]+/)?copy-trading/lead-details/[0-9]{8,25}/?")) {
            String id = uri.getLastPathSegment();
            if (id != null && id.matches("[0-9]{8,25}")) return id;
        }
        throw new IllegalArgumentException("请填写币安组合 ID 或官方组合链接");
    }

    private void error(Exception bad) {
        new AlertDialog.Builder(this).setMessage(message(bad)).setPositiveButton("确定", null).show();
    }

    private static String message(Exception problem) {
        String text = problem.getMessage();
        if (problem instanceof java.io.IOException && !(problem instanceof FuturesApi.Failure)
                && (text == null || !text.startsWith("币安"))) return "连接或读取失败，请检查本机网络";
        return text == null ? "操作失败" : text;
    }
}
