package com.localcopy.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class InfoActivity extends Activity {
    private static final String DISCLAIMER = "disclaimer";
    private static final String PAGE = "page";
    static final String USE_NOTICE = "请勿用于商业以及违法用途。";

    static Intent open(Context context, boolean disclaimer) {
        return new Intent(context, InfoActivity.class).putExtra(PAGE, disclaimer ? DISCLAIMER : "author");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean disclaimer = DISCLAIMER.equals(getIntent().getStringExtra(PAGE));
        String title = disclaimer ? "免责声明" : "作者信息";
        setTitle(title);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        Ui.applyInsets(getWindow(), root);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.contentColumn(this);
        int padding = Ui.dp(this, 18);
        page.setPadding(padding, padding, padding, Ui.dp(this, 28));
        page.addView(Ui.button(this, "‹ 返回", false, view -> finish()));
        TextView heading = Ui.heading(this, title, 29);
        heading.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 6));
        page.addView(heading);
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (disclaimer) disclaimer(page);
        else author(page);
    }

    private void author(LinearLayout page) {
        LinearLayout card = Ui.card(this, page);
        card.addView(Ui.heading(this, "色胚头子", 26));
        card.addView(Ui.text(this, "crypto trader", 15, Ui.ACCENT));
        contact(card, "X", "@wuyanzuWa");
        contact(card, "邮箱", "wuy15525@gmail.com");
        TextView restriction = Ui.text(this, USE_NOTICE, 14, Ui.WARNING);
        restriction.setPadding(0, Ui.dp(this, 15), 0, 0);
        card.addView(restriction);
    }

    private void contact(LinearLayout parent, String label, String value) {
        TextView title = Ui.text(this, label, 13, Ui.MUTED);
        title.setPadding(0, Ui.dp(this, 17), 0, 0);
        parent.addView(title);
        TextView detail = Ui.heading(this, value, 17);
        detail.setTextIsSelectable(true);
        parent.addView(detail);
    }

    private void disclaimer(LinearLayout page) {
        TextView notice = Ui.text(this, USE_NOTICE, 15, Ui.WARNING);
        int padding = Ui.dp(this, 12);
        notice.setPadding(padding, padding, padding, padding);
        notice.setBackground(Ui.fill(this, 0xfffff4e2, 12, 0xffeddbb3));
        page.addView(notice);
        disclosure(page, "地区限制与使用资格",
                "使用者应自行核对所在地及其他适用司法辖区的法律法规、监管要求和交易平台条款。"
                + "若加密货币交易、合约交易或自动跟单在适用地区受到禁止或限制，或使用者不具备资格，请停止使用相应功能。"
                + "不得使用本工具绕过地区限制、身份验证或其他平台访问限制。");
        disclosure(page, "交易风险与损失承担",
                "本工具提供公开记录查看与用户自主配置的自动委托功能。页面信息和交易信号不构成投资建议、交易邀约或收益承诺。"
                + "行情波动、杠杆、强平、滑点、流动性不足、数据延迟、网络中断及交易所或软件异常均可能造成损失，包括全部投入资金。"
                + "使用者应自行决定是否跟单，并核对账户、参数及委托。在适用法律允许的范围内，作者不承担使用者自行交易或跟单所产生的损失。");
        disclosure(page, "带单员授权与第三方权利",
                "公开可见的组合记录仅表明信息可被查看。场外跟单涉及的使用许可、授权和平台规则应由使用者自行核实。"
                + "本工具与币安及所展示的带单员无官方合作或背书关系。依法或依平台条款需要同意或授权时，使用者应事先取得授权。"
                + "未经所需授权擅自复制交易、再发布信息或侵害他人权益所产生的纠纷及责任，由使用者依法承担。"
                + "作者在适用法律允许的范围内不承担此类行为所产生的责任。");
        disclosure(page, "适用范围",
                "本声明以适用法律允许的范围为限。依法不得免除或限制的责任及使用者的法定权利，均以适用法律为准。");
    }

    private void disclosure(LinearLayout page, String title, String content) {
        LinearLayout card = Ui.card(this, page);
        card.addView(Ui.heading(this, title, 18));
        TextView detail = Ui.text(this, content, 15, Ui.MUTED);
        detail.setPadding(0, Ui.dp(this, 7), 0, 0);
        card.addView(detail);
    }
}
