# Sasi Task Manager — Android Studio project

This project packages the supplied `Sasi_Task_Manager_2026-10-29.html` app inside an Android WebView. The original HTML/JavaScript interface and its localStorage-based task data are retained.

## Build an APK
1. Install Android Studio (latest stable) and Android SDK Platform 35.
2. Extract this ZIP.
3. In Android Studio choose **Open** and select the `SasiTaskManagerAndroid` folder.
4. Allow Gradle sync to finish. If prompted, install the Android Gradle Plugin/SDK components.
5. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. Android Studio shows the APK location when the build completes.

## Notes
- Package/application ID: `com.sasi.taskmanager`
- Minimum Android version: Android 6.0 (API 23)
- Task data uses WebView local storage on the device. Clearing app data or uninstalling may remove locally stored tasks; use the app's backup/export feature regularly.
- Internet permission is included because the supplied HTML may load Google Fonts or other external resources. Core assets are bundled locally.
- This ZIP is the full editable Android Studio source project, not a prebuilt APK. Gradle downloads the Android build plugin and dependencies on first sync.
