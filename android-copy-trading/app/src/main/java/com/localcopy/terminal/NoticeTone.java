package com.localcopy.terminal;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.HashSet;
import java.util.Set;

final class NoticeTone {
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private static Handler audioThread;
    private static final Set<MediaPlayer> active = new HashSet<>();

    static void play(Context context, Runnable otherwise) {
        Context app = context.getApplicationContext();
        handler().post(() -> {
            MediaPlayer player;
            try {
                player = MediaPlayer.create(app, R.raw.order_alert, ATTRIBUTES, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } catch (RuntimeException invalid) {
                player = null;
            }
            if (player == null) {
                fallback(otherwise);
                return;
            }
            active.add(player);
            player.setOnCompletionListener(NoticeTone::release);
            player.setOnErrorListener((failed, what, details) -> {
                if (release(failed)) fallback(otherwise);
                return true;
            });
            try {
                player.start();
            } catch (IllegalStateException unavailable) {
                if (release(player)) fallback(otherwise);
            }
        });
    }

    private static boolean release(MediaPlayer player) {
        if (!active.remove(player)) return false;
        player.release();
        return true;
    }

    private static void fallback(Runnable otherwise) {
        ToneGenerator tone = null;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85);
            if (tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)) {
                handler().postDelayed(tone::release, 450);
                return;
            }
        } catch (RuntimeException unavailable) {
            // The system notification channel is the final audio path.
        }
        if (tone != null) tone.release();
        otherwise.run();
    }

    private static synchronized Handler handler() {
        if (audioThread == null) {
            HandlerThread thread = new HandlerThread("copy-notification-audio");
            thread.start();
            audioThread = new Handler(thread.getLooper());
        }
        return audioThread;
    }
}
