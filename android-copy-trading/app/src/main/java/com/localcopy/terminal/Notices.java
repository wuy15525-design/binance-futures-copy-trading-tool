package com.localcopy.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

/** 将持续的服务状态、已确认成交和官网划转交给独立的 Android 通知频道。 */
final class Notices {
    static final int AUTO_ID = 932;
    private static final String RUN = "copy_running";
    private static final String TRADES = "copy_trades";
    private static final String BALANCES = "leader_balance_transfers";
    private static final String ALERTS = "_alerts";

    static NotificationManager channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(RUN, "后台运行", NotificationManager.IMPORTANCE_LOW);
        service.setSound(null, null);
        manager.createNotificationChannel(service);
        eventChannel(context, manager, TRADES, "交易事件");
        eventChannel(context, manager, BALANCES, "带单员余额划转");
        return manager;
    }

    static Notification autoStatus(Context context, String message) {
        Intent stop = new Intent(context, AutoFollowService.class).setAction(AutoFollowService.STOP);
        PendingIntent stopAction = PendingIntent.getService(context, AUTO_ID, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, RUN)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setSmallIcon(R.drawable.ic_notice).setContentTitle("合约跟单台")
                .setContentText(message).setContentIntent(openApp(context))
                .addAction(new Notification.Action.Builder(null, "停止", stopAction).build()).build();
    }

    static void trade(Context context, String id, String title, String text, boolean sound) {
        event(context, TRADES, "交易事件", id, title, text, sound, true);
    }

    static boolean balance(Context context, String id, String trader, String text) {
        return event(context, BALANCES, "带单员余额划转", id, trader + " · 余额划转", text, true, true);
    }

    static void testTrade(Context context, String text) {
        event(context, TRADES, "交易事件", "notice-test", "通知测试", text, true, false);
    }

    static String tradeChannelId(Context context) {
        return eventChannel(context, context.getSystemService(NotificationManager.class), TRADES, "交易事件");
    }

    static String soundStatus(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return "系统通知未开启";
        NotificationChannel channel = manager.getNotificationChannel(tradeChannelId(context));
        if (channel == null || channel.getImportance() < NotificationManager.IMPORTANCE_DEFAULT || channel.getSound() == null) {
            return "交易通知类别已静音，可在交易通知声音中修改";
        }
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return "手机处于静音或振动模式";
        int volume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        if (volume == 0) return "通知音量为 0";
        if (manager.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
            return "提示音由系统勿扰规则控制";
        }
        String output = localTone(channel) ? "应用内提示音" : "系统所选铃声";
        return output + " · 通知音量 " + volume + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
    }

    private static String eventChannel(Context context, NotificationManager manager, String id, String title) {
        String next = id + ALERTS;
        if (manager.getNotificationChannel(next) != null) return next;
        NotificationChannel previous = manager.getNotificationChannel(id + "_tone");
        if (previous == null) previous = manager.getNotificationChannel(id + "_audible");
        if (previous == null) previous = manager.getNotificationChannel(id);
        if (previous != null && ((previous.hasUserSetSound() && !previousBundledSound(context, previous.getSound()))
                || previous.getImportance() == NotificationManager.IMPORTANCE_NONE
                || (previous.hasUserSetImportance() && previous.getImportance() < NotificationManager.IMPORTANCE_DEFAULT))) {
            return previous.getId();
        }
        int importance = previous != null && previous.hasUserSetImportance()
                ? previous.getImportance() : NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(next, title, importance);
        channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        if (previous != null) {
            channel.setVibrationPattern(previous.getVibrationPattern());
            channel.enableVibration(previous.shouldVibrate());
            channel.setLightColor(previous.getLightColor());
            channel.enableLights(previous.shouldShowLights());
            channel.setLockscreenVisibility(previous.getLockscreenVisibility());
            channel.setShowBadge(previous.canShowBadge());
        }
        manager.createNotificationChannel(channel);
        return next;
    }

    private static boolean previousBundledSound(Context context, Uri sound) {
        return sound != null && "android.resource".equals(sound.getScheme())
                && context.getPackageName().equals(sound.getAuthority());
    }

    // 沿用系统通知权限、静音和勿扰设置。应用播放内置提示音时先静默投递，避免系统和应用同时播放。
    private static boolean event(Context context, String channel, String channelTitle, String id, String title,
            String text, boolean sound, boolean onlyOnce) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                || !manager.areNotificationsEnabled()) return false;
        String channelId = eventChannel(context, manager, channel, channelTitle);
        NotificationChannel category = manager.getNotificationChannel(channelId);
        if (category == null || category.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        boolean local = sound && localTone(category)
                && manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL
                && isAudible(context);
        boolean alreadyPosted = local && onlyOnce && alreadyPosted(manager, id);
        if (!post(context, manager, channelId, id, title, text, onlyOnce, !sound || local)) return false;
        if (local && !alreadyPosted) NoticeTone.play(context,
                () -> post(context, manager, channelId, id, title, text, false, false));
        return true;
    }

    private static boolean post(Context context, NotificationManager manager, String channelId, String id,
            String title, String text, boolean onlyOnce, boolean silent) {
        Notification.Builder notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notice).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentTitle(title).setContentText(text).setOnlyAlertOnce(onlyOnce)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context)).setAutoCancel(true);
        if (silent) notification.setGroup("silent-" + id).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        try {
            manager.notify(id, 1, notification.build());
            return true;
        } catch (SecurityException revoked) {
            return false;
        }
    }

    private static boolean localTone(NotificationChannel channel) {
        return channel.getId().endsWith(ALERTS) && channel.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                && channel.getSound() != null && !channel.hasUserSetSound();
    }

    private static boolean isAudible(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0;
    }

    private static boolean alreadyPosted(NotificationManager manager, String id) {
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == 1 && id.equals(active.getTag())) return true;
        }
        return false;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
