package com.sasi.taskmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_REMINDER = "com.sasi.taskmanager.ACTION_REMINDER";
    public static final String ACTION_SNOOZE = "com.sasi.taskmanager.ACTION_SNOOZE";
    public static final String EXTRA_ID = "task_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_IS_ALARM = "is_alarm";

    private static final String TAG = "SasiReminder";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SasiTask:ReminderWake");
            wl.acquire(30_000L);
        }

        try {
            if (ACTION_REMINDER.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    // Reschedule from persisted data on boot
                    AlarmScheduler.rescheduleAll(context);
                    return;
                }

                String id = intent.getStringExtra(EXTRA_ID);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String body = intent.getStringExtra(EXTRA_BODY);
                boolean isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, true);

                Log.i(TAG, "Alarm fired id=" + id + " title=" + title);

                // Show notification + full-screen activity
                NotificationHelper.showReminder(context, id != null ? id : "0",
                        title != null ? title : "Reminder",
                        body != null ? body : "Time for your task",
                        isAlarm);

                // Also launch AlarmActivity so sound plays even if notification is muted
                if (isAlarm) {
                    Intent full = new Intent(context, AlarmActivity.class);
                    full.putExtra("task_id", id);
                    full.putExtra("title", title != null ? title : "Reminder");
                    full.putExtra("body", body != null ? body : "");
                    full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    context.startActivity(full);
                }
            } else if (ACTION_SNOOZE.equals(action)) {
                String id = intent.getStringExtra(EXTRA_ID);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String body = intent.getStringExtra(EXTRA_BODY);
                long snoozeMs = intent.getLongExtra("snooze_ms", 10 * 60 * 1000L);
                AlarmScheduler.schedule(context, id, title, body, System.currentTimeMillis() + snoozeMs, true);
            }
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }
}
