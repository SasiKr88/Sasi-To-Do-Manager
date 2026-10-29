package com.sasi.taskmanager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Exposed to the WebView as window.SasiNative
 * Methods expected / useful for the Sasi Task Manager HTML app.
 */
public class SasiNativeBridge {
    private static final String TAG = "SasiNative";
    private final WeakReference<Activity> activityRef;
    private final WeakReference<WebView> webViewRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // For chunked save (export) used by the original HTML
    private StringBuilder saveBuffer;
    private String saveFilename;
    private String saveMime;
    private String saveMode;

    public SasiNativeBridge(Activity activity, WebView webView) {
        this.activityRef = new WeakReference<>(activity);
        this.webViewRef = new WeakReference<>(webView);
    }

    private Activity act() { return activityRef.get(); }
    private Context ctx() {
        Activity a = act();
        return a != null ? a.getApplicationContext() : null;
    }

    // ---------- Notification / Alarm API ----------

    @JavascriptInterface
    public void requestNotify() {
        Activity a = act();
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a instanceof MainActivity) {
                ((MainActivity) a).requestNotificationPermission();
            }
        });
    }

    @JavascriptInterface
    public void notify(String title, String body) {
        Context c = ctx();
        if (c == null) return;
        NotificationHelper.showReminder(c, "test_" + System.currentTimeMillis(),
                title != null ? title : "Sasi Task Reminder",
                body != null ? body : "", false);
    }

    @JavascriptInterface
    public void scheduleReminder(String id, String title, String body, long triggerAtMillis, boolean isAlarm) {
        Context c = ctx();
        if (c == null) return;
        AlarmScheduler.schedule(c, id, title, body, triggerAtMillis, isAlarm);
    }

    @JavascriptInterface
    public void cancelReminder(String id) {
        Context c = ctx();
        if (c == null) return;
        AlarmScheduler.cancel(c, id);
    }

    @JavascriptInterface
    public void cancelAllReminders() {
        Context c = ctx();
        if (c == null) return;
        AlarmScheduler.cancelAll(c);
    }

    // ---------- Custom alarm sound ----------

    @JavascriptInterface
    public void pickAlarmSound() {
        Activity a = act();
        if (a instanceof MainActivity) {
            a.runOnUiThread(() -> ((MainActivity) a).pickAlarmSound());
        }
    }

    @JavascriptInterface
    public void clearAlarmSound() {
        Context c = ctx();
        if (c == null) return;
        NotificationHelper.clearCustomSound(c);
        toast("Custom alarm sound cleared");
    }

    @JavascriptInterface
    public boolean hasCustomAlarmSound() {
        Context c = ctx();
        if (c == null) return false;
        File f = NotificationHelper.getCustomSoundFile(c);
        return f != null && f.exists();
    }

    @JavascriptInterface
    public void playTestSound() {
        Context c = ctx();
        if (c == null) return;
        mainHandler.post(() -> {
            try {
                Uri uri = NotificationHelper.getDefaultSoundUri(c);
                MediaPlayer mp = MediaPlayer.create(c, uri);
                if (mp != null) {
                    mp.setOnCompletionListener(MediaPlayer::release);
                    mp.start();
                }
            } catch (Exception e) {
                Log.e(TAG, "playTestSound", e);
            }
        });
    }

    // ---------- Chunked save (used by original HTML export) ----------

    @JavascriptInterface
    public void beginSave(String filename, String mime, String mode) {
        saveBuffer = new StringBuilder();
        saveFilename = filename != null ? filename : "sasi_export.json";
        saveMime = mime != null ? mime : "application/json";
        saveMode = mode != null ? mode : "save";
    }

    @JavascriptInterface
    public void appendSave(String base64Chunk) {
        if (saveBuffer == null || base64Chunk == null) return;
        try {
            byte[] decoded = android.util.Base64.decode(base64Chunk, android.util.Base64.DEFAULT);
            saveBuffer.append(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "appendSave decode error", e);
        }
    }

    @JavascriptInterface
    public boolean endSave() {
        Activity a = act();
        if (a == null || saveBuffer == null) return false;
        final String content = saveBuffer.toString();
        final String name = saveFilename;
        final String mime = saveMime;
        saveBuffer = null;
        a.runOnUiThread(() -> {
            if (a instanceof MainActivity) {
                ((MainActivity) a).saveExportedFile(name, content, mime);
            }
        });
        return true;
    }

    // ---------- Helpers the HTML can call ----------

    @JavascriptInterface
    public void toast(String msg) {
        Context c = ctx();
        if (c == null) return;
        mainHandler.post(() -> Toast.makeText(c, msg, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public String getPlatform() {
        return "android";
    }

    @JavascriptInterface
    public boolean canScheduleExactAlarms() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true;
        Context c = ctx();
        if (c == null) return false;
        android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    @JavascriptInterface
    public void openExactAlarmSettings() {
        Activity a = act();
        if (a == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        }
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        Context c = ctx();
        if (c != null) NotificationHelper.openNotificationSettings(c);
    }

    /** Called by injected JS after reading localStorage */
    @JavascriptInterface
    public void syncTasksFromJson(String json) {
        Context c = ctx();
        if (c == null) return;
        AlarmScheduler.scheduleFromAppData(c, json);
    }
}
