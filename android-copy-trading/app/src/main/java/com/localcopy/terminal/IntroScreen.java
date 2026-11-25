package com.localcopy.terminal;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class IntroScreen {
    static final long DURATION = 3000;

    static View create(Context context) {
        ScrollView cover = new ScrollView(context);
        cover.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff081325, 0xff172d52}));
        cover.setFillViewport(true);
        cover.setClickable(true);
        cover.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.contentColumn(context);
        content.setGravity(Gravity.CENTER);
        int padding = Ui.dp(context, 24);
        content.setPadding(padding, padding, padding, padding);
        Configuration configuration = context.getResources().getConfiguration();
        int diameter = Math.max(90, Math.min(232, Math.min(configuration.screenWidthDp / 2, configuration.screenHeightDp / 2)));
        ImageView coin = new ImageView(context);
        coin.setImageResource(R.drawable.intro_coin);
        coin.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(coin, new LinearLayout.LayoutParams(Ui.dp(context, diameter), Ui.dp(context, diameter)));

        TextView motto = Ui.text(context, "场外跟单，无需求人。", 34, 0xffffdf9b);
        motto.setTypeface(context.getResources().getFont(R.font.intro_calligraphy));
        motto.setGravity(Gravity.CENTER);
        motto.setMaxLines(1);
        motto.setAutoSizeTextTypeUniformWithConfiguration(14, 35, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(-1, Ui.dp(context, 74));
        text.topMargin = Ui.dp(context, 5);
        content.addView(motto, text);
        cover.addView(content, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        return cover;
    }
}
