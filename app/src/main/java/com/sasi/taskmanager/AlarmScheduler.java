package com.sasi.taskmanager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

public class AlarmScheduler {
    private static final String TAG = "SasiAlarmScheduler";
    private static final String PREFS = "sasi_alarms";
    private static final String KEY_ALARMS = "pending";

    public static void schedule(Context context, String id, String title, String body,
                                long triggerAtMillis, boolean isAlarm) {
        if (id == null || id.isEmpty()) return;
        if (triggerAtMillis <= System.currentTimeMillis()) {
            // Already past → fire immediately
            Intent i = new Intent(context, ReminderReceiver.class);
            i.setAction(ReminderReceiver.ACTION_REMINDER);
            i.putExtra(ReminderReceiver.EXTRA_ID, id);
            i.putExtra(ReminderReceiver.EXTRA_TITLE, title);
            i.putExtra(ReminderReceiver.EXTRA_BODY, body);
            i.putExtra(ReminderReceiver.EXTRA_IS_ALARM, isAlarm);
            context.sendBroadcast(i);
            return;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_REMINDER);
        intent.putExtra(ReminderReceiver.EXTRA_ID, id);
        intent.putExtra(ReminderReceiver.EXTRA_TITLE, title != null ? title : "Reminder");
        intent.putExtra(ReminderReceiver.EXTRA_BODY, body != null ? body : "");
        intent.putExtra(ReminderReceiver.EXTRA_IS_ALARM, isAlarm);

        int requestCode = Math.abs(id.hashCode());
        PendingIntent pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
            persist(context, id, title, body, triggerAtMillis, isAlarm);
            Log.i(TAG, "Scheduled id=" + id + " at " + triggerAtMillis);
        } catch (SecurityException se) {
            Log.e(TAG, "Exact alarm permission missing", se);
            // Fallback inexact
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            persist(context, id, title, body, triggerAtMillis, isAlarm);
        }
    }

    public static void cancel(Context context, String id) {
        if (id == null) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.setAction(ReminderReceiver.ACTION_REMINDER);
        int requestCode = Math.abs(id.hashCode());
        PendingIntent pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
        removePersisted(context, id);
        NotificationHelper.cancel(context, id);
        Log.i(TAG, "Cancelled id=" + id);
    }

    public static void cancelAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ALARMS, "{}");
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                cancel(context, keys.next());
            }
        } catch (Exception ignored) {}
        prefs.edit().remove(KEY_ALARMS).apply();
    }

    private static void persist(Context context, String id, String title, String body,
                                long trigger, boolean isAlarm) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject all = new JSONObject(prefs.getString(KEY_ALARMS, "{}"));
            JSONObject one = new JSONObject();
            one.put("title", title);
            one.put("body", body);
            one.put("trigger", trigger);
            one.put("isAlarm", isAlarm);
            all.put(id, one);
            prefs.edit().putString(KEY_ALARMS, all.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "persist failed", e);
        }
    }

    private static void removePersisted(Context context, String id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject all = new JSONObject(prefs.getString(KEY_ALARMS, "{}"));
            all.remove(id);
            prefs.edit().putString(KEY_ALARMS, all.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Called after BOOT_COMPLETED or when app starts */
    public static void rescheduleAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ALARMS, "{}");
        long now = System.currentTimeMillis();
        try {
            JSONObject all = new JSONObject(json);
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject one = all.getJSONObject(id);
                long trigger = one.optLong("trigger", 0);
                if (trigger > now) {
                    schedule(context, id,
                            one.optString("title", "Reminder"),
                            one.optString("body", ""),
                            trigger,
                            one.optBoolean("isAlarm", true));
                } else {
                    // Fire past ones
                    schedule(context, id,
                            one.optString("title", "Reminder"),
                            one.optString("body", ""),
                            now,
                            one.optBoolean("isAlarm", true));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "rescheduleAll failed", e);
        }
    }

    /**
     * Parse the app's localStorage JSON (sasiTaskV2) and schedule upcoming reminders.
     * Called from MainActivity after page load / on resume.
     */
    public static void scheduleFromAppData(Context context, String jsonData) {
        if (jsonData == null || jsonData.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(jsonData);
            JSONArray tasks = root.optJSONArray("tasks");
            if (tasks == null) return;

            long now = System.currentTimeMillis();
            // Cancel previous auto-scheduled ones first? We keep user-scheduled and only add new.
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject t = tasks.optJSONObject(i);
                if (t == null) continue;
                if (t.optBoolean("done", false)) continue;

                String id = t.optString("id", null);
                if (id == null) continue;
                String title = t.optString("title", "Task");

                // Look for time / date fields the HTML uses
                String date = t.optString("date", "");
                String time = t.optString("time", "");
                long trigger = 0;

                // Common patterns in the app
                if (t.has("remindAt")) {
                    trigger = t.optLong("remindAt", 0);
                } else if (t.has("when") && "event".equals(t.optString("when")) && !date.isEmpty()) {
                    // try parse date + time
                    trigger = parseDateTime(date, time);
                } else if (!time.isEmpty()) {
                    trigger = parseTodayOrTomorrow(time);
                }

                // Also check nested reminder
                JSONObject rem = t.optJSONObject("reminder");
                if (rem != null) {
                    long rAt = rem.optLong("at", 0);
                    if (rAt > 0) trigger = rAt;
                }

                if (trigger > now && trigger < now + 7L * 24 * 60 * 60 * 1000) {
                    // only schedule next 7 days to avoid flooding
                    boolean isAlarm = t.optBoolean("must", false)
                            || "must".equals(t.optString("kind", ""))
                            || t.optBoolean("alarm", false);
                    schedule(context, "task_" + id, title,
                            t.optString("note", t.optString("body", "Time for this task")),
                            trigger, isAlarm);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "scheduleFromAppData parse error", e);
        }
    }

    private static long parseDateTime(String date, String time) {
        try {
            // Expect date YYYY-MM-DD, time HH:MM
            java.text.SimpleDateFormat sdf;
            if (time != null && !time.isEmpty()) {
                sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
                return sdf.parse(date + " " + time).getTime();
            } else {
                sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                return sdf.parse(date).getTime() + 9 * 60 * 60 * 1000L; // 9 AM default
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseTodayOrTomorrow(String time) {
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, h);
            cal.set(java.util.Calendar.MINUTE, m);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
