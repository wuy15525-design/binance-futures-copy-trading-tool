/**
 * 读取当前组合详情的可见 DOM，并把观测交给 Android 端。
 * 官网标签、日历和成交卡片会分批渲染，脚本报告各自的状态，分页节奏和入档判断由 Java 端控制。
 */
function copyTerminalCapture(request) {
    const path = location.pathname.replace(/\/$/, "");
    if (location.origin !== "https://www.binance.com" || path !== request.path) {
        if (request.profile && location.origin === "https://www.binance.com"
                && /^\/(?:[a-z]{2}(?:-[a-z]{2,4})?\/)?copy-trading$/i.test(path)) {
            return JSON.stringify({ phase: "profile-missing" });
        }
        return JSON.stringify({ phase: "navigation" });
    }
    // reload 返回前仍可能读到旧 DOM。给每份文档保留标识，旧文档继续等待导航。
    if (!document.copyTerminalDocument) document.copyTerminalDocument = request.document;
    const id = document.copyTerminalDocument;
    let name = "";
    let leaderMargin = "";
    let profileMargin = "";
    const answer = (phase, data = {}) => JSON.stringify(Object.assign({ phase, id, name, leaderMargin, profileMargin }, data));
    if (id === request.previous || !document.body || document.readyState === "loading") return answer("navigation");

    const label = node => (node.innerText || node.textContent || "").replace(/\s+/g, "");
    const available = node => node && !node.closest('[hidden],[aria-hidden="true"]') &&
        getComputedStyle(node).display !== "none" && getComputedStyle(node).visibility !== "hidden";
    const rendered = node => available(node) && node.getClientRects().length > 0;
    const visiblePage = document.body.innerText || "";
    const heading = visiblePage.slice(0, 1200);
    const notices = Array.from(document.querySelectorAll(
        '[role="dialog"],[role="alertdialog"],[aria-modal="true"],.bn-modal,[role="alert"]'))
        .filter(node => available(node) && node.getClientRects().length > 0)
        .map(node => node.innerText || "").join("\n");
    const gateText = heading + "\n" + notices;
    const regionGate = [
        /确认(?:所在)?地区限制|確認(?:所在)?地區限制|(?:确认|確認)(?:你|您)(?:目前|所在)?的?(?:国家|國家|地区|地區)/,
        /(?:当前|當前|[您你]所在的?|该|該)(?:国家|國家|地区|地區)[^\n。]{0,45}(?:不可用|不支持|不提供|無法提供|无法提供|限制访问|限制存取|受到限制|受限)/,
        /(?:not (?:available|supported|permitted)|(?:cannot|unable to) provide (?:our )?services)[^\n.]{0,90}(?:your|this|current|restricted) (?:region|country|location|jurisdiction)/i,
        /confirm (?:(?:your|the) (?:current )?(?:region|location|country)|(?:your |the )?(?:regional|region|location|country) restrictions)|unsupported (?:country|region)/i
    ];
    if (regionGate.some(pattern => pattern.test(gateText))) return answer("region");
    if (/verify (?:that )?you are human|human verification|请完成安全验证|确认您是真人|安全验证以继续/i.test(gateText)) {
        return answer("verify");
    }
    if (/access denied|访问被拒绝|拒絕存取/i.test(gateText)) return answer("blocked");
    if (/请先登录(?:币安)?(?:以|后)查看|登录后(?:才能)?查看(?:最新)?操作记录|sign in to view (?:the )?(?:trade|order) history/i.test(gateText)) {
        return answer("signin");
    }

    // The label and a USDT value must be adjacent. Other portfolio metrics have separate labels.
    const marginPattern = /(?:^|[ \t])(带单保证金余额|帶單保證金餘額|Leading Margin Balance|Lead Margin Balance|带单余额|帶單餘額)[ \t]*(?:\(\s*(USDT)\s*\))?\s*([0-9][0-9,]*(?:\.[0-9]+)?)[ \t]*(USDT)?(?=[ \t]*$|[ \t]+[^0-9.,])/gim;
    const balances = new Set();
    const compactBalances = new Set();
    for (const match of visiblePage.normalize("NFKC").matchAll(marginPattern)) {
        if (match[2] || match[4]) {
            const target = /^(带单余额|帶單餘額)$/.test(match[1]) ? compactBalances : balances;
            target.add(match[3]);
        }
    }
    if (balances.size === 1) leaderMargin = balances.values().next().value;
    if (balances.size === 0 && compactBalances.size === 1) profileMargin = compactBalances.values().next().value;

    const findTab = pattern => Array.from(document.querySelectorAll('[role="tab"]'))
        .find(node => available(node) && pattern.test(label(node)))
        || Array.from(document.querySelectorAll('button,[role="button"]'))
            .find(node => available(node) && pattern.test(label(node)));
    const tradeTab = /^(最新操作记录|最新交易记录|最新操作記錄|最新交易記錄|LatestRecords|LatestTrades|LatestOperations|LatestTradingActivity)$/i;
    const title = Array.from(document.querySelectorAll("h1")).filter(available)
        .map(node => (node.innerText || node.textContent || "").trim().replace(/\s+/g, " "))
        .find(value => value.length > 0 && value.length <= 100
            && !/^[\s—–.\-_]+$/.test(value)
            && !/^(带单员总览|组合详情|币安|跟单交易|Binance|Futures Copy Trading|Copy Trading|Lead Trader Overview)$/i.test(value));
    if (request.profile && /(?:该)?(?:带单|跟单)?(?:组合|项目)(?:已删除|不存在|未找到)|(?:portfolio|copy trading project)\s+(?:not found|does not exist)/i.test(gateText)) {
        return answer("profile-missing");
    }
    if (title) name = title;
    if (request.profile) return answer(name && findTab(tradeTab) ? "profile" : "profile-wait");
    const tabLabel = request.positionHistory
        ? /^(仓位历史记录|仓位历史|持仓历史|倉位歷史記錄|倉位歷史|持倉歷史|PositionHistory)$/i
        : request.balance
        ? /^(转账记录|轉帳記錄|转账历史|轉帳歷史|划转记录|劃轉記錄|余额历史|餘額歷史|TransferHistory|BalanceHistory)$/i
        : tradeTab;
    const tab = findTab(tabLabel);
    if (!tab && request.positionHistory) return answer("position-tab");
    if (!tab) return answer(request.balance ? "balance-tab" : "tab");

    const controls = tab.getAttribute("aria-controls");
    const pane = (controls && document.getElementById(controls)) ||
        (tab.id && Array.from(document.querySelectorAll('[role="tabpanel"]'))
            .find(node => node.getAttribute("aria-labelledby") === tab.id)) ||
        document.getElementById(request.positionHistory ? "bn-tab-pane-1" : request.balance ? "bn-tab-pane-3" : "bn-tab-pane-2");
    const selected = tab.getAttribute("aria-selected");
    if (selected === "false" || !available(pane)) {
        if (request.click && !tab.disabled) tab.click();
        return answer("select", { clicked: request.click });
    }

    if (request.positionHistory) {
        const loading = pane.getAttribute("aria-busy") === "true"
            || Array.from(pane.querySelectorAll('[role="progressbar"],[aria-busy="true"]')).some(rendered);
        const busy = /系统繁忙|系統繁忙|请稍后再试|System(?:\s+is)?\s+busy/i.test(notices);
        const positions = Array.from(pane.querySelectorAll("tbody tr,[role=row]")).filter(rendered)
            .map(row => (row.innerText || "").normalize("NFKC").trim())
            .filter(text => /^[A-Z0-9\p{Script=Han}]{1,30}USDT\s+(?=[\s\S]*(?:已平仓量|已平倉量|Closed Vol|Closed Quantity))/iu.test(text));
        const empty = /暂无记录|暂无数据|暫無紀錄|暫無數據|No (?:records|data)/i.test(pane.innerText);
        return answer((positions.length || empty) && !loading && !busy ? "positions" : "position-wait",
            { positions, zone: Intl.DateTimeFormat().resolvedOptions().timeZone });
    }

    if (request.balance) {
        const roles = [/^(时间|時間|日期|交易时间|交易時間|Time|Date|Date\/Time)$/i,
            /^(币种|幣種|资产|資產|Coin|Asset|Currency)$/i, /^(数量|數量|金额|金額|Amount|Quantity)$/i,
            /^(从|從|转出|轉出|转出账户|轉出帳戶|From)$/i, /^(到|至|转入|轉入|转入账户|轉入帳戶|To)$/i];
        const accountTable = Array.from(pane.querySelectorAll("table,[role=table]")).filter(rendered).find(table => {
            const headings = Array.from(table.querySelectorAll("th,[role=columnheader]")).map(label);
            return roles.every((role, i) => role.test(headings[i] || ""));
        });
        const transferTime = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[ T\s]+(\d{1,2}):(\d{2}):(\d{2})$/;
        const transferCells = text => text.normalize("NFKC").trim();
        let transfers = accountTable ? Array.from(accountTable.querySelectorAll("tbody tr,[role=row]")).filter(rendered)
            .map(row => Array.from(row.querySelectorAll("td,[role=cell]")).map(cell => transferCells(cell.innerText)))
            .filter(row => row.length >= 5) : [];
        if (!accountTable) {
            // Narrow layouts can render the same five fields as stacked text instead of table cells.
            const cells = (pane.innerText || "").split(/[\t\r\n]+/).map(transferCells).map(value => {
                const field = /^([^:]{1,20}):\s*(.*)$/.exec(value);
                return field && roles.some(role => role.test(field[1])) ? field[2] : value;
            }).filter(value => value && !roles.some(role => role.test(value)));
            const records = cells.map((value, i) => transferTime.test(value) ? cells.slice(i, i + 5) : null).filter(Boolean);
            if (records.every(row => row.length === 5 && /^[A-Z0-9]{1,24}$/i.test(row[1])
                    && /^[0-9][0-9,.]*(?:\s+[A-Z0-9]{1,24})?$/i.test(row[2])
                    && row.slice(3).every(account => !/^\d+$/.test(account) && !transferTime.test(account)))) {
                transfers = records;
            }
        }
        for (const row of transfers) {
            row[0] = row[0].replace(transferTime, (_, year, month, day, hour, minute, second) =>
                [year, month.padStart(2, "0"), day.padStart(2, "0")].join("-")
                    + " " + [hour.padStart(2, "0"), minute, second].join(":"));
            if (row[2].endsWith(" " + row[1])) row[2] = row[2].slice(0, -(row[1].length + 1)).trim();
        }
        const loading = pane.getAttribute("aria-busy") === "true"
            || Array.from(pane.querySelectorAll('[role="progressbar"],[aria-busy="true"]')).some(rendered);
        const busy = /系统繁忙|系統繁忙|请稍后再试|System(?:\s+is)?\s+busy/i.test(notices + "\n" + (pane.innerText || "").slice(-1200));
        const empty = /暂无记录|暂无数据|没有记录|沒有記錄|暫無數據|No (?:records|data)/i.test(pane.innerText);
        const pagination = Array.from(pane.querySelectorAll(".bn-pagination,[aria-label='Pagination'],[aria-label='分页']"))
            .find(rendered);
        const controls = pagination ? Array.from(pagination.querySelectorAll(
            ".bn-pagination-item,.bn-pagination-next,button,a,[role=button],li,[aria-current]")).filter(rendered) : [];
        const pages = controls.filter(node => /^\d+$/.test(label(node)));
        const page = Number(pages.find(node => node.getAttribute("aria-current") === "page"
            || /(?:^|\s)(?:active|selected|current|\S*pagination\S*-(?:active|selected))(?=\s|$)/
                .test(node.className || ""))?.innerText?.trim()) || 0;
        const desired = request.balancePage || 1;
        const enabled = node => !node.disabled && node.getAttribute("aria-disabled") !== "true"
            && !/(?:^|\s)(?:\S+-)?disabled(?=\s|$)/.test(node.className || "");
        const next = pages.find(node => label(node) === String(desired + 1) && enabled(node))
            || controls.find(node => /(?:^|\s)bn-pagination-next(?=\s|$)/.test(node.className || "") && enabled(node));
        if (request.command === "balance-next") {
            if (!next || busy || loading || (page && page !== desired)) return answer("balance-next", { clicked: false });
            next.click();
            return answer("balance-next", { clicked: true });
        }
        return answer((transfers.length || empty) && !loading && !busy ? "balances" : "balance-wait", {
            transfers, zone: Intl.DateTimeFormat().resolvedOptions().timeZone, page, more: !!next, busy
        });
    }

    // 日期输入框可能晚于成交卡片出现，窄屏也可能用单个按钮显示范围。保留缺失状态给 Java 端判断。
    const readDates = value => Array.from((value || "").normalize("NFKC").matchAll(
        /(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})(?!\d)/g),
        ([, year, month, day]) => `${year}-${month.padStart(2, "0")}-${day.padStart(2, "0")}`);
    const inputs = Array.from(pane.querySelectorAll("input")).filter(input => rendered(input) &&
        (input.getAttribute("date-range") || readDates(input.value).length ||
         /YYYY|日期|date/i.test(input.getAttribute("placeholder") || input.getAttribute("aria-label") || "")));
    const namedStart = inputs.find(input => input.getAttribute("date-range") === "start");
    const namedEnd = inputs.find(input => input.getAttribute("date-range") === "end");
    const namedRange = namedStart || namedEnd;
    const startInput = namedRange ? namedStart : inputs[0];
    const endInput = namedRange ? namedEnd : inputs[1];
    const dateControl = Array.from(pane.querySelectorAll(".date-filter .bn-web-datepicker,.date-filter button,[role=button][aria-label*='日期']"))
        .find(rendered);
    const inputDates = Array.from(new Set([startInput, endInput])).filter(Boolean).flatMap(input => readDates(input.value));
    const dates = inputDates.length >= 2 ? inputDates.slice(0, 2)
        : readDates(dateControl?.innerText).slice(0, 2);
    const datePicker = Boolean(startInput || endInput || dateControl);
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const moreLabel = /^(更多|加载更多|查看更多|显示更多|展开更多(?:记录)?|展開更多(?:紀錄)?|載入更多|查看更多紀錄|更多紀錄|LoadMore|ShowMore|ViewMore|More)$/i;
    const more = Array.from(pane.querySelectorAll('button,[role="button"]')).find(node => {
        const text = label(node);
        const accessible = (node.getAttribute("aria-label") || "").replace(/\s+/g, "");
        return rendered(node) && (moreLabel.test(text) || moreLabel.test(accessible)
            || (/^(?:展开|展開)$/.test(text || accessible) && node.closest(".LatestRecords")));
    });
    // 观察按钮是否存在、能否点击，以及页面是否繁忙。按钮暂时消失时，Java 端仍会等待列表稳定。
    const moreReady = !!more && !more.disabled && more.getAttribute("aria-disabled") !== "true"
        && !/(?:^|\s)(?:\S+-)?(?:disabled|loading)(?=\s|$)/.test(more.className || "");
    const pageFailure = /系统繁忙|系統繁忙|展开失败|展開失敗|加载失败|載入失敗|System(?:\s*is)?\s*busy|Failed\s*to\s*load/i;
    const busy = pageFailure.test(notices + "\n" + (pane.innerText || "").slice(-400))
        || visiblePage.slice(-1200).split("\n").some(line => pageFailure.test(line) &&
            /^(?:系统|系統|展开|展開|加载|載入|system|failed)/i.test(line.trim()))
        || Array.from(document.querySelectorAll('[role="alert"],[role="status"]')).some(node =>
            rendered(node) && (pageFailure.test(label(node)) || /请稍后再试|請稍後再試/.test(label(node))));
    const loading = pane.getAttribute("aria-busy") === "true"
        || Array.from(pane.querySelectorAll('[role="progressbar"],[aria-busy="true"]')).some(rendered);
    const rangePopups = () => Array.from(document.querySelectorAll(".bn-web-datepicker-dropdown-range")).filter(rendered);

    // 部分官网日历先更新输入框，再等用户确认才刷新列表。存在确认按钮时报告仍在应用日期范围。
    if (request.applyDates && dates.length === 2
            && dates.every((date, index) => date === request.applyDates[index])) {
        const popups = rangePopups();
        if (popups.length === 1) {
            const confirm = Array.from(popups[0].querySelectorAll('button,[role="button"]')).find(node =>
                rendered(node) && /^(确认|確認|Confirm|Apply)$/i.test(label(node)));
            if (confirm && (busy || loading || confirm.disabled || confirm.getAttribute("aria-disabled") === "true")) {
                return answer("list", { dates, datePicker, zone, busy, loading: true, dateApplyPending: true });
            }
            if (confirm && request.click) {
                confirm.click();
                return answer("calendar-apply");
            }
            if (confirm) return answer("list", { dates, datePicker, zone, loading: true, dateApplyPending: true });
        }
    }

    if (request.command === "more") {
        if (!moreReady || busy || loading) return answer("more", { clicked: false });
        more.click();
        return answer("more", { clicked: true });
    }

    if (request.command === "date-pick") {
        const field = request.end ? endInput : startInput;
        if (namedRange && !field) return answer("calendar");
        const control = field || dateControl;
        const wrapper = field?.closest(".bn-web-datepicker-input");
        const named = !!(wrapper && field.getAttribute("date-range"));
        const popups = rangePopups();
        const activate = () => {
            if (!request.click || busy || loading || !control || control.disabled
                    || control.getAttribute("aria-disabled") === "true") return answer("calendar");
            // Focus and click can each open the range picker. Observe the focus
            // result before clicking so the second event cannot toggle it closed.
            if (named && document.activeElement !== field) control.focus({ preventScroll: true });
            else control.click();
            return answer("calendar", { opened: true });
        };
        if (busy || loading || popups.length > 1) return answer("calendar");
        if (named && (!wrapper.classList.contains("bn-web-datepicker-input-active") || !popups.length)) return activate();
        const calendar = popups[0] || document;
        const days = Array.from(calendar.querySelectorAll("td[title]")).filter(node => rendered(node) &&
            /^\d{4}-\d{2}-\d{2}$/.test(node.getAttribute("title")) && (!/\bbn-web-datepicker-cell\b/.test(node.className) ||
                /\bbn-web-datepicker-cell-in-view\b/.test(node.className)) && !/cell-not-in-view/.test(node.className));
        if (days.length === 0) return named ? answer("calendar") : activate();
        const matches = days.filter(node => node.getAttribute("title") === request.date);
        const cell = matches.find(node => node.getAttribute("aria-disabled") !== "true" && !/cell-disabled/.test(node.className));
        if (cell) {
            (cell.firstElementChild || cell).click();
            return answer("calendar", { clicked: true });
        }
        if (matches.length > 0) return answer("calendar", { blocked: true });
        const monthDates = days.map(node => node.getAttribute("title")).sort();
        const navigation = request.date < monthDates[0] ? ".bn-web-datepicker-header-prev-btn"
            : request.date > monthDates[monthDates.length - 1] ? ".bn-web-datepicker-header-next-btn" : "";
        const button = navigation && Array.from(calendar.querySelectorAll(navigation)).find(node =>
            rendered(node) && !node.disabled && node.getAttribute("aria-disabled") !== "true");
        if (button) button.click();
        return answer("calendar", { moved: Boolean(button) });
    }

    const candidates = Array.from(pane.querySelectorAll(".LatestRecords .timeline")).filter(rendered)
        .map(timeline => Array.from(timeline.querySelectorAll(":scope > .item")).filter(rendered).map(row => row.innerText.trim()))
        .filter(rows => rows.length > 0).sort((a, b) => b.length - a.length);
    const rows = candidates[0];
    // 响应式切换时宽屏和窄屏时间线可能并存。只读取一份，内容对不上时等待布局和记录稳定。
    if (rows && candidates.slice(1).some(other => !other.every((row, i) => row === rows[i]))) {
        return answer("list", { dates, datePicker, zone, more: !!more, moreReady, busy, loading: true });
    }
    const text = rows ? rows.join("\n") : pane.innerText || "";
    const phase = /成交数量为/.test(text) ? "rows"
        : !rows && /暂无记录|暂无数据|没有记录|暫無記錄|No (?:records|data)(?: found)?/i.test(text) ? "empty" : "list";
    return answer(phase, { text, rowCount: rows ? rows.length : 0, dates, datePicker, zone, more: !!more, moreReady, busy, loading });
}
