# Sasi Task Manager — Android (with real Alarms & Notifications)

This Android Studio project wraps the Sasi Task Manager HTML app in a WebView **and** adds a full native layer so reminders work like a real Android alarm app:

- Exact alarms (even when the app is closed or the phone is locked)
- High-priority notifications + full-screen alarm screen
- Looping custom MP3 / OGG / WAV alarm sound
- Snooze / Done / Dismiss
- Boot-time reschedule
- Custom alarm sound picker
- Automatic sync from the app’s `localStorage` (`sasiTaskV2`)

## Features added

| Feature | How it works |
|---------|----------------|
| **Notifications** | Android Notification Channels (`sasi_reminders`, `sasi_alarms`) |
| **Exact Alarms** | `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM` |
| **Full-screen alarm** | `AlarmActivity` shows over lock-screen with looping sound + vibration |
| **Custom MP3 sound** | Pick any audio file → stored in app private storage → used by notifications & alarm |
| **JS bridge** | `window.SasiNative` (requestNotify, notify, scheduleReminder, cancelReminder, pickAlarmSound, …) |
| **Auto-schedule** | On page load / resume the native side reads task data and schedules upcoming reminders |

## Build an APK

1. Install **Android Studio** (latest stable) + Android SDK Platform **35**.
2. Extract this ZIP.
3. Open the `SasiTaskManagerAndroid` folder in Android Studio.
4. Let Gradle sync finish (first time downloads the Android Gradle Plugin).
5. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## First launch – grant permissions

On first open the app will ask for:

1. **Notifications** (Android 13+)
2. **Alarms & reminders** / Exact alarms (Android 12+) – open the system screen and allow it

Without these two permissions, background reminders cannot fire reliably.

## How to set a custom alarm sound

### From the app UI
The original HTML already has a “Bridge & Alarm Test” section.  
You can also call from the browser console (or from future UI):

```js
window.SasiNative.pickAlarmSound()   // opens system file picker
window.SasiNative.playTestSound()    // plays current sound
window.SasiNative.clearAlarmSound()  // reset to system default
window.SasiNative.hasCustomAlarmSound()
```

### From native side
`MainActivity.pickAlarmSound()` is also available.

Supported formats: **mp3, ogg, wav, m4a, aac**.

## JS bridge API (`window.SasiNative`)

| Method | Description |
|--------|-------------|
| `requestNotify()` | Request notification permission |
| `notify(title, body)` | Show an immediate notification |
| `scheduleReminder(id, title, body, triggerAtMillis, isAlarm)` | Schedule exact alarm |
| `cancelReminder(id)` | Cancel one alarm + its notification |
| `cancelAllReminders()` | Clear everything |
| `pickAlarmSound()` | Open file picker for custom MP3 |
| `clearAlarmSound()` | Remove custom sound |
| `playTestSound()` | Play the current alarm sound once |
| `hasCustomAlarmSound()` | `true` if a custom file is set |
| `canScheduleExactAlarms()` | Whether exact-alarm permission is granted |
| `openExactAlarmSettings()` | Open system exact-alarm screen |
| `openNotificationSettings()` | Open app notification settings |
| `beginSave / appendSave / endSave` | Chunked export used by the original HTML |
| `syncTasksFromJson(json)` | Native parses tasks and schedules upcoming ones |
| `getPlatform()` | returns `"android"` |
| `toast(msg)` | Android Toast |

## Notes

- Package ID: `com.sasi.taskmanager`
- Min SDK: 24 (Android 7.0) – required for reliable exact alarms
- Target / Compile SDK: 35
- Task data still lives in WebView `localStorage`. Use the app’s own backup/export regularly.
- Clearing app data or uninstalling removes both tasks and scheduled alarms.
- After reboot the `BOOT_COMPLETED` receiver automatically restores pending alarms.

## Troubleshooting

- **Alarm doesn’t fire when screen is off** → Make sure “Alarms & reminders” permission is granted (Settings → Apps → Sasi Task Manager → Alarms & reminders).
- **No sound** → Check notification channel importance is High, and that Do-Not-Disturb isn’t blocking the “Alarms & Must-Do” channel. Set a custom MP3 if the system default is silent.
- **Full-screen intent blocked** → On some OEMs you must enable “Display over other apps” or “Full screen notifications” for the app.
