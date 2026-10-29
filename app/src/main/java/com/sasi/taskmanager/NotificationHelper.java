package com.sasi.taskmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;

public class NotificationHelper {
    public static final String CHANNEL_REMINDERS = "sasi_reminders";
    public static final String CHANNEL_ALARMS = "sasi_alarms";
    public static final String CHANNEL_DEFAULT = "sasi_default";

    public static final int NOTIF_ID_BASE = 10000;

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Soft reminder channel
        NotificationChannel reminders = new NotificationChannel(
                CHANNEL_REMINDERS,
                "Task Reminders",
                NotificationManager.IMPORTANCE_HIGH
        );
        reminders.setDescription("Reminders for scheduled tasks");
        reminders.enableVibration(true);
        reminders.setVibrationPattern(new long[]{0, 300, 200, 300});
        reminders.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        reminders.setSound(getDefaultSoundUri(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(reminders);

        // Alarm channel (full attention)
        NotificationChannel alarms = new NotificationChannel(
                CHANNEL_ALARMS,
                "Alarms & Must-Do",
                NotificationManager.IMPORTANCE_HIGH
        );
        alarms.setDescription("Loud alarms that can show full-screen");
        alarms.enableVibration(true);
        alarms.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
        alarms.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alarms.setBypassDnd(true);
        alarms.setSound(getDefaultSoundUri(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(alarms);

        // Default / test channel
        NotificationChannel def = new NotificationChannel(
                CHANNEL_DEFAULT,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        nm.createNotificationChannel(def);
    }

    public static Uri getDefaultSoundUri(Context context) {
        File custom = getCustomSoundFile(context);
        if (custom != null && custom.exists()) {
            return Uri.fromFile(custom);
        }
        // Prefer alarm type, fall back to notification
        Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm != null) return alarm;
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    public static File getCustomSoundFile(Context context) {
        File dir = new File(context.getFilesDir(), "sounds");
        File f = new File(dir, "custom_alarm.mp3");
        if (f.exists()) return f;
        // also accept .ogg / .wav
        for (String ext : new String[]{"mp3", "ogg", "wav", "m4a"}) {
            f = new File(dir, "custom_alarm." + ext);
            if (f.exists()) return f;
        }
        return null;
    }

    public static void setCustomSound(Context context, Uri sourceUri) throws Exception {
        File dir = new File(context.getFilesDir(), "sounds");
        if (!dir.exists()) dir.mkdirs();

        // Remove previous
        for (File old : dir.listFiles()) {
            if (old.getName().startsWith("custom_alarm.")) old.delete();
        }

        String name = sourceUri.getLastPathSegment();
        String ext = "mp3";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot + 1).toLowerCase();
        }
        File dest = new File(dir, "custom_alarm." + ext);

        try (java.io.InputStream in = context.getContentResolver().openInputStream(sourceUri);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) throw new Exception("Cannot open selected file");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        // Recreate channels so the new sound is used
        createChannels(context);
    }

    public static void clearCustomSound(Context context) {
        File dir = new File(context.getFilesDir(), "sounds");
        if (dir.exists()) {
            for (File f : dir.listFiles()) {
                if (f.getName().startsWith("custom_alarm.")) f.delete();
            }
        }
        createChannels(context);
    }

    public static void showReminder(Context context, String id, String title, String body, boolean isAlarm) {
        createChannels(context);

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("from_notification", true);
        open.putExtra("task_id", id);

        PendingIntent contentPi = PendingIntent.getActivity(
                context, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Full-screen intent for alarms
        Intent full = new Intent(context, AlarmActivity.class);
        full.putExtra("task_id", id);
        full.putExtra("title", title != null ? title : "Reminder");
        full.putExtra("body", body != null ? body : "");
        full.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(
                context, id.hashCode() + 1, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String channel = isAlarm ? CHANNEL_ALARMS : CHANNEL_REMINDERS;

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title != null ? title : "Sasi Task Reminder")
                .setContentText(body != null ? body : "Time for your task")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(isAlarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(isAlarm ? new long[]{0, 500, 250, 500, 250, 500} : new long[]{0, 300, 200, 300})
                .setSound(getDefaultSoundUri(context));

        if (isAlarm) {
            b.setFullScreenIntent(fullPi, true);
            b.setOngoing(true);
        }

        NotificationManagerCompat.from(context).notify(NOTIF_ID_BASE + Math.abs(id.hashCode() % 50000), b.build());
    }

    public static void cancel(Context context, String id) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_BASE + Math.abs(id.hashCode() % 50000));
    }

    public static boolean areNotificationsEnabled(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    public static void openNotificationSettings(Context context) {
        Intent i = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            i.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
