const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/website-capture.js"), "utf8");
const portfolio = "/zh-CN/copy-trading/lead-details/5075281354358777856";
const trade = "11-18, 23:40:31\n平多\n以均价为701.40320USDT 卖出平多MUUSDT永续合约，成交数量为 532.92MU。";

function element(properties = {}) {
    return Object.assign({
        innerText: "",
        attrs: {},
        style: { display: "block", visibility: "visible" },
        getAttribute(key) { return this.attrs[key] || null; },
        closest() { return this.hidden ? this : null; },
        getClientRects() { return [{}]; },
        querySelectorAll() { return []; },
        click() {},
        focus() {},
    }, properties);
}

function fixture() {
    const pane = element({ id: "responsive-history", innerText: "暂无记录", hidden: true });
    const tab = element({
        id: "mobile-tab",
        innerText: "最新\n交易记录",
        attrs: { "aria-controls": pane.id, "aria-selected": "false" },
        click() { this.attrs["aria-selected"] = "true"; pane.hidden = false; },
    });
    let dateOpen = false;
    let from = "";
    let paged = 0;
    const start = element({ value: "2026-11-14", click() { dateOpen = true; } });
    const end = element({ value: "2026-11-20" });
    const startDay = element({ attrs: { title: "2026-11-18" }, click() { from = this.attrs.title; } });
    const endDay = element({
        attrs: { title: "2026-11-20" },
        click() {
            assert.equal(from, "2026-11-18");
            start.value = from;
            pane.innerText = trade;
            dateOpen = false;
        },
    });
    const more = element({ innerText: "查看\n更多", click() { paged++; } });
    pane.querySelectorAll = selector => selector === "input" ? pane.inputs
        : selector === 'button,[role="button"]' ? [more] : [];
    pane.inputs = [start, end];
    const document = {
        body: { innerText: "公开带单组合" },
        notices: [],
        readyState: "interactive",
        querySelectorAll(selector) {
            if (selector === "h1") return [element({ innerText: "组合详情" }), element({ innerText: "熬鹰资本" })];
            if (selector.includes('[role="alertdialog"]')) return this.notices;
            if (selector === '[role="tab"]') return [tab];
            if (selector === '[role="tabpanel"]') return [pane];
            if (selector === "td[title]") return dateOpen ? [startDay, endDay] : [];
            return [];
        },
        getElementById(id) { return id === pane.id ? pane : null; },
    };
    const location = { origin: "https://www.binance.com", pathname: portfolio };
    const sandbox = { document, location, getComputedStyle: node => node.style };
    const capture = vm.runInNewContext(source + "\ncopyTerminalCapture", sandbox);
    return {
        document, location, pane, tab, more,
        read(fields = {}) {
            return JSON.parse(capture({ document: "sample-2", previous: "sample-1", path: portfolio,
                click: true, ...fields }));
        },
        pages() { return paged; },
    };
}

const reload = fixture();
const margin = fixture();
margin.document.body.innerText = "带单员总览\n资产管理规模\n8,217,438.86 USDT\n分润比例\n10%\n带单保证金余额\n524,530.61 USDT";
assert.equal(margin.read({ profile: true }).leaderMargin, "524,530.61");
margin.document.body.innerText = "带单保证金余额（USDT）\n500.29\n资产管理规模\n7,265,822.21 USDT";
assert.equal(margin.read({ profile: true }).leaderMargin, "500.29");
margin.document.body.innerText = "带单保证金余额\n--\n资产管理规模\n7,265,822.21 USDT";
assert.equal(margin.read({ profile: true }).leaderMargin, "");
assert.equal(margin.read({ profile: true }).profileMargin, "");
margin.document.body.innerText = "带单余额（USDT）\n500.29\n资产管理规模(USDT)\n7,265,822.21";
assert.equal(margin.read({ profile: true }).profileMargin, "500.29");
assert.equal(margin.read({ profile: true }).leaderMargin, "");
margin.document.body.innerText += "\n带单保证金余额（USDT）\n502.37";
assert.equal(margin.read({ profile: true }).leaderMargin, "502.37");
assert.equal(margin.read({ profile: true }).profileMargin, "");
console.log("PASS · 用带单保证金余额并排除资产管理规模");
reload.document.copyTerminalDocument = "sample-1";
assert.equal(reload.read().phase, "navigation");
assert.equal(reload.tab.attrs["aria-selected"], "false");
console.log("PASS · 不读取刷新前的旧页面");

const profile = fixture();
assert.equal(profile.read({ profile: true }).phase, "profile");
assert.equal(profile.read({ profile: true }).name, "熬鹰资本");
assert.equal(profile.tab.attrs["aria-selected"], "false");
assert.equal(profile.read({ profile: true, path: "/zh-CN/copy-trading/lead-details/11111111" }).name, undefined);
const headingLookup = profile.document.querySelectorAll.bind(profile.document);
profile.document.querySelectorAll = selector => selector === "h1" ? [element({ innerText: "---" })] : headingLookup(selector);
assert.equal(profile.read({ profile: true }).phase, "profile-wait");
profile.document.body.innerText = "该带单组合不存在";
assert.equal(profile.read({ profile: true }).phase, "profile-missing");
profile.location.pathname = "/en/copy-trading";
assert.equal(profile.read({ profile: true }).phase, "profile-missing");
assert.equal(profile.read().phase, "navigation");
console.log("PASS · 只从指定组合详情读取带单员名称");

const positions = fixture();
const closedCard = "BTCUSDT\n永续\n全仓\n做空\n全部平仓\n开仓时间\n2026-11-21 22:04:32\n最大持仓量\n66.554 BTC"
    + "\n全部平仓\n2026-11-21 23:19:05\n已平仓量\n66.554 BTC";
positions.tab.innerText = "仓位历史记录";
positions.pane.innerText = closedCard;
positions.pane.querySelectorAll = selector => selector === "tbody tr,[role=row]"
    ? [element({ innerText: closedCard }), element({ innerText: closedCard, hidden: true })] : [];
assert.equal(positions.read({ positionHistory: true }).phase, "select");
assert.deepEqual(positions.read({ positionHistory: true }).positions, [closedCard]);
assert.equal(positions.read({ positionHistory: true }).phase, "positions");
assert.equal(positions.read().phase, "tab");
positions.pane.attrs["aria-busy"] = "true";
assert.equal(positions.read({ positionHistory: true }).phase, "position-wait");
positions.pane.attrs["aria-busy"] = "false";
const hanCard = closedCard.replaceAll("BTC", "币安人生");
positions.pane.querySelectorAll = selector => selector === "tbody tr,[role=row]"
    ? [element({ innerText: closedCard }), element({ innerText: hanCard })] : [];
assert.deepEqual(positions.read({ positionHistory: true }).positions, [closedCard, hanCard]);
console.log("PASS · 独立读取币安仓位历史标签的可见已平仓卡片");

const mobile = fixture();
assert.equal(mobile.read().phase, "select");
assert.equal(mobile.read().phase, "empty");
assert.equal(mobile.read({ command: "date-pick", date: "2026-11-18" }).opened, true);
assert.equal(mobile.read({ command: "date-pick", date: "2026-11-18" }).clicked, true);
assert.equal(mobile.read({ command: "date-pick", date: "2026-11-20", end: true }).clicked, true);
assert.equal(mobile.read().text, trade);
assert.equal(mobile.read().name, "熬鹰资本");
assert.deepEqual(mobile.read().dates, ["2026-11-18", "2026-11-20"]);
assert.equal(mobile.read({ command: "more" }).clicked, true);
assert.equal(mobile.pages(), 1);
mobile.more.disabled = true;
assert.equal(mobile.read().more, true);
assert.equal(mobile.read().moreReady, false);
assert.equal(mobile.read({ command: "more" }).clicked, false);
mobile.more.disabled = false;
mobile.document.body.innerText = "公开带单组合\n系统繁忙，请稍后再试";
assert.equal(mobile.read().busy, true);
assert.equal(mobile.read({ command: "more" }).clicked, false);
mobile.document.body.innerText = "公开带单组合\n展开失败，请稍后重试";
assert.equal(mobile.read().busy, true);
assert.equal(mobile.read({ command: "more" }).clicked, false);
mobile.document.body.innerText = "公开带单组合";
assert.equal(mobile.read({ command: "more" }).clicked, true);
assert.equal(mobile.pages(), 2);
mobile.more.hidden = true;
assert.equal(mobile.read().more, false);
console.log("PASS · 移动端标签、自动重选日期和可见分页");

mobile.pane.inputs = [];
assert.equal(mobile.read().phase, "rows");
assert.equal(mobile.read().dates.length, 0);
assert.equal(mobile.read().datePicker, false);
console.log("PASS · 页面没有日期输入框时仍提取成交文本");

const calendar = fixture();
calendar.read();
const [fromField, untilField] = calendar.pane.inputs;
fromField.attrs["date-range"] = "start";
untilField.attrs["date-range"] = "end";
fromField.value = untilField.value = "";
assert.equal(calendar.read().datePicker, true);
fromField.value = "2026/11/5";
untilField.value = "2026年11月20日";
assert.deepEqual(calendar.read().dates, ["2026-11-05", "2026-11-20"]);
let month = 11;
let chosen = "";
const openCalendar = calendar.document.querySelectorAll.bind(calendar.document);
const oldCell = element({ attrs: { title: "2026-10-29" }, className: "bn-web-datepicker-cell bn-web-datepicker-cell-in-view",
    click() { chosen = "2026-10-29"; } });
const ghostCell = element({ attrs: { title: "2026-10-29" }, className: "bn-web-datepicker-cell", click() { assert.fail(); } });
const disabledDate = element({ attrs: { title: "2026-10-30" }, className: "bn-web-datepicker-cell bn-web-datepicker-cell-in-view bn-web-datepicker-cell-disabled" });
const nextDate = element({ attrs: { title: "2026-11-01" }, className: "bn-web-datepicker-cell bn-web-datepicker-cell-in-view" });
const prevMonth = element({ click() { month = 10; } });
calendar.document.querySelectorAll = selector => selector === "td[title]"
    ? (month === 11 ? [ghostCell, nextDate] : [ghostCell, oldCell, disabledDate])
    : selector === ".bn-web-datepicker-header-prev-btn" ? [prevMonth] : openCalendar(selector);
assert.equal(calendar.read({ command: "date-pick", date: "2026-10-29" }).moved, true);
assert.equal(calendar.read({ command: "date-pick", date: "2026-10-29" }).clicked, true);
assert.equal(chosen, "2026-10-29");
assert.equal(calendar.read({ command: "date-pick", date: "2026-10-30" }).blocked, true);
console.log("PASS · 日期字段迟到、不同分隔符、翻月和日历禁用状态");

const applyRange = fixture();
applyRange.read();
let applied = 0;
const confirmRange = element({ innerText: "确认", disabled: true, click() { applied++; } });
const rangePopup = element({ querySelectorAll: () => [confirmRange] });
const originalLookup = applyRange.document.querySelectorAll.bind(applyRange.document);
applyRange.document.querySelectorAll = selector => selector === ".bn-web-datepicker-dropdown-range"
    ? [rangePopup] : originalLookup(selector);
const selectedDays = ["2026-11-14", "2026-11-20"];
assert.equal(applyRange.read({ applyDates: selectedDays }).dateApplyPending, true);
assert.equal(applied, 0);
confirmRange.disabled = false;
assert.equal(applyRange.read({ applyDates: ["2026-11-14", "2026-11-19"] }).phase, "empty");
assert.equal(applyRange.read({ applyDates: selectedDays, click: false }).dateApplyPending, true);
assert.equal(applyRange.read({ applyDates: selectedDays }).phase, "calendar-apply");
assert.equal(applied, 1);
console.log("PASS · 只确认匹配的日期范围，等待日历确认按钮生效");

const directEnd = fixture();
directEnd.read();
const [existingStart, desiredEnd] = directEnd.pane.inputs;
existingStart.attrs["date-range"] = "start";
desiredEnd.attrs["date-range"] = "end";
let activeField = "start";
let focusCalls = 0;
let clickedDates = 0;
let popupOpen = true;
for (const field of [existingStart, desiredEnd]) {
    const role = field.getAttribute("date-range");
    field.closest = rule => rule === ".bn-web-datepicker-input"
        ? { classList: { contains: name => name === "bn-web-datepicker-input-active" && activeField === role } }
        : null;
    field.focus = () => {
        focusCalls++;
        directEnd.document.activeElement = field;
    };
    field.click = () => { activeField = role; popupOpen = true; };
}
const seamDay = element({
    attrs: { title: "2026-11-18" }, className: "bn-web-datepicker-cell bn-web-datepicker-cell-in-view",
    click() {
        assert.equal(activeField, "end");
        clickedDates++;
        desiredEnd.value = this.attrs.title;
    },
});
const datePopup = element({ querySelectorAll: selector => selector === "td[title]" ? [seamDay] : [] });
const previousLookup = directEnd.document.querySelectorAll.bind(directEnd.document);
directEnd.document.querySelectorAll = selector => {
    if (selector === ".bn-web-datepicker-dropdown-range") return popupOpen ? [datePopup] : [];
    if (selector === "td[title]") assert.fail("选日期时只能读取交易页对应的弹层");
    return previousLookup(selector);
};
const endRequest = { command: "date-pick", date: "2026-11-18", end: true };
assert.equal(directEnd.read(endRequest).opened, true);
assert.equal(focusCalls, 1);
assert.equal(clickedDates, 0);
assert.equal(directEnd.read({ ...endRequest, click: false }).opened, undefined);
assert.equal(directEnd.read(endRequest).opened, true);
assert.equal(clickedDates, 0);
popupOpen = false;
assert.equal(directEnd.read({ ...endRequest, click: false }).opened, undefined);
assert.equal(directEnd.read(endRequest).opened, true);
assert.equal(activeField, "end");
assert.equal(clickedDates, 0);
assert.equal(directEnd.read(endRequest).clicked, true);
assert.equal(clickedDates, 1);
assert.deepEqual(directEnd.read().dates, ["2026-11-14", "2026-11-18"]);
console.log("PASS · 开始日期保持原样，结束日期控件取得焦点后仅点所属日历");

const cards = fixture();
cards.read();
const visibleTrade = element({ innerText: trade });
const hiddenTrade = element({ innerText: trade, hidden: true });
const timeline = element({ querySelectorAll: () => [visibleTrade, hiddenTrade] });
cards.pane.innerText = `${trade}\n${trade}`;
const originalQuery = cards.pane.querySelectorAll;
cards.pane.querySelectorAll = selector => selector === ".LatestRecords .timeline"
    ? [timeline] : originalQuery(selector);
assert.equal(cards.read().text, trade);
hiddenTrade.hidden = false;
assert.equal(cards.read().text, `${trade}\n${trade}`);
cards.pane.querySelectorAll = selector => selector === ".LatestRecords .timeline"
    ? [timeline, timeline] : originalQuery(selector);
assert.equal(cards.read().rowCount, 2);
assert.equal(cards.read().loading, false);
const other = element({ querySelectorAll: () => [element({ innerText: trade.replace("532.92", "500") })] });
cards.pane.querySelectorAll = selector => selector === ".LatestRecords .timeline"
    ? [timeline, other] : originalQuery(selector);
assert.equal(cards.read().loading, true);
console.log("PASS · 同一时间线的响应式副本只读一次，不一致的时间线等待页面稳定");

const blocked = fixture();
blocked.document.body.innerText = "请完成安全验证";
assert.equal(blocked.read().phase, "verify");
blocked.document.body.innerText = "Access denied";
assert.equal(blocked.read().phase, "blocked");
blocked.document.body.innerText = "此服务在您所在地区不可用";
assert.equal(blocked.read().phase, "region");
blocked.document.body.innerText = "This service is not available in your region.";
assert.equal(blocked.read().phase, "region");
blocked.document.body.innerText = "請確認您所在的地區";
assert.equal(blocked.read().phase, "region");
blocked.document.body.innerText = "Confirm your country of residence";
assert.equal(blocked.read().phase, "region");
blocked.document.body.innerText = "登录后查看最新操作记录";
assert.equal(blocked.read().phase, "signin");
blocked.location.origin = "https://unrelated.example";
assert.equal(blocked.read().phase, "navigation");
console.log("PASS · 拒绝验证页和非目标来源");

const regional = fixture();
regional.read();
regional.pane.innerText = trade;
regional.document.body.innerText = "公开带单组合\n页脚：部分地区可能存在产品限制";
regional.document.notices = [element({ innerText: "确认地区限制\n请确认后继续", hidden: true })];
assert.equal(regional.read().phase, "rows");
regional.document.notices[0].hidden = false;
assert.equal(regional.read().phase, "region");
assert.equal(regional.read({ command: "more" }).phase, "region");
assert.equal(regional.pages(), 0);
regional.document.notices = [];
assert.equal(regional.read({ command: "more" }).clicked, true);
console.log("PASS · 地区确认挡住追加时保留网页状态，公告与隐藏弹窗不影响取数");

const transfers = fixture();
transfers.tab.innerText = "转账记录";
transfers.document.body.innerText = "带单保证金余额\n525,096.62 USDT";
const header = ["时间", "币种", "数量", "从", "到"].map(innerText => element({ innerText }));
const firstTransfer = ["2026-11-21 00:38:32", "USDT", "80,000.00000000", "现货账户", "带单交易"];
const row = element({ querySelectorAll() { return firstTransfer.map(innerText => element({ innerText })); } });
const hidden = element({ hidden: true, querySelectorAll() { assert.fail("隐藏行不可读取"); } });
const table = element({ querySelectorAll: selector => selector === "th,[role=columnheader]" ? header : [row, hidden] });
const noRecords = element({ innerText: "暂无记录" });
transfers.pane.innerText = firstTransfer.join("\n");
transfers.pane.querySelectorAll = selector => selector === "table,[role=table]" ? [table] : [];
assert.equal(transfers.read({ balance: true }).phase, "select");
assert.equal(transfers.read().phase, "tab");
assert.equal(transfers.read({ balance: true }).phase, "balances");
assert.equal(transfers.read({ balance: true }).leaderMargin, "525,096.62");
assert.deepEqual(transfers.read({ balance: true }).transfers, [firstTransfer]);
const pageOne = element({ innerText: "1", className: "bn-pagination-item active" });
const pageTwo = element({ innerText: "2", className: "bn-pagination-item", click() {
    pageOne.className = "bn-pagination-item";
    this.className = "bn-pagination-item active";
    nextPage.className = "bn-pagination-next disabled";
} });
const nextPage = element({ className: "bn-pagination-next", click() { pageTwo.click(); } });
const pagination = element({ querySelectorAll: selector => selector.includes(".bn-pagination-item")
    ? [pageOne, pageTwo, nextPage] : [] });
const transferQuery = transfers.pane.querySelectorAll;
transfers.pane.querySelectorAll = selector => selector.startsWith(".bn-pagination") ? [pagination] : transferQuery(selector);
assert.equal(transfers.read({ balance: true, balancePage: 1 }).page, 1);
assert.equal(transfers.read({ balance: true, balancePage: 1 }).more, true);
transfers.pane.innerText += "\n系统繁忙，请稍后再试";
assert.equal(transfers.read({ balance: true, balancePage: 1, command: "balance-next" }).clicked, false);
transfers.pane.innerText = firstTransfer.join("\n");
assert.equal(transfers.read({ balance: true, balancePage: 1, command: "balance-next" }).clicked, true);
assert.equal(transfers.read({ balance: true, balancePage: 2 }).page, 2);
assert.equal(transfers.read({ balance: true, balancePage: 2 }).more, false);
pageOne.className = "bn-pagination-item active";
nextPage.className = "bn-pagination-next";
pagination.querySelectorAll = selector => selector.includes(".bn-pagination-next") ? [pageOne, nextPage] : [];
assert.equal(transfers.read({ balance: true, balancePage: 1, command: "balance-next" }).clicked, true);
transfers.pane.attrs["aria-busy"] = "true";
assert.equal(transfers.read({ balance: true }).phase, "balance-wait");
delete transfers.pane.attrs["aria-busy"];
transfers.pane.querySelectorAll = () => [];
transfers.pane.innerText = "时间：2026/11/21 0:38:32\n币种：USDT\n数量：80,000.00000000 USDT\n从：现货账户\n到：带单交易";
assert.deepEqual(transfers.read({ balance: true }).transfers, [firstTransfer]);
transfers.pane.innerText = "时间\n2026/11/21 0:38:32\n币种\nUSDT\n数量\n80,000\n从\n现货账户\n到";
assert.equal(transfers.read({ balance: true }).phase, "balance-wait");
transfers.pane.innerText = noRecords.innerText;
assert.deepEqual(transfers.read({ balance: true }).transfers, []);
console.log("PASS · 余额转账表、实际链接分页、移动端字段和繁忙状态");
