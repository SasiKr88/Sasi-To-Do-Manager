package com.sasi.taskmanager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SasiMain";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int SOUND_PICK_REQUEST = 1002;
    private static final int EXPORT_SAVE_REQUEST = 1003;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private SasiNativeBridge bridge;

    private String pendingExportName;
    private String pendingExportContent;
    private String pendingExportMime;

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Please enable notifications for reminders", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> multiPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // continue regardless
            });

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationHelper.createChannels(this);
        AlarmScheduler.rescheduleAll(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        bridge = new SasiNativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "SasiNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // After page loads, pull task data and schedule native alarms
                injectSyncScript();
                // Also ensure the bridge is visible
                view.evaluateJavascript(
                        "window.SasiNative = window.SasiNative || {}; " +
                        "console.log('SasiNative ready', !!window.SasiNative.requestNotify);", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    // open external links outside
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Unable to open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // Request runtime permissions early
        requestAllNeededPermissions();

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }

        // Handle deep-link from notification / alarm
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("from_notification", false)) {
            String id = intent.getStringExtra("task_id");
            // Optionally focus the task in the web UI
            if (webView != null && id != null) {
                webView.evaluateJavascript(
                        "try{window.dispatchEvent(new CustomEvent('sasi-focus-task',{detail:{id:'" +
                                id.replace("'", "") + "'}}))}catch(e){}", null);
            }
        }
        if (intent.hasExtra("mark_done")) {
            String id = intent.getStringExtra("mark_done");
            if (webView != null && id != null) {
                // Best-effort: the HTML may listen for this event
                webView.evaluateJavascript(
                        "try{window.dispatchEvent(new CustomEvent('sasi-mark-done',{detail:{id:'" +
                                id.replace("'", "") + "'}}))}catch(e){}", null);
            }
        }
    }

    private void requestAllNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        // Exact alarms on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        }
    }

    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                Toast.makeText(this, "Notifications already enabled", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show();
        }
    }

    public void pickAlarmSound() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp3", "audio/ogg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/aac"});
        try {
            startActivityForResult(intent, SOUND_PICK_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open sound picker", Toast.LENGTH_SHORT).show();
        }
    }

    public void saveExportedFile(String name, String content, String mime) {
        pendingExportName = name;
        pendingExportContent = content;
        pendingExportMime = mime != null ? mime : "application/json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingExportMime);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, EXPORT_SAVE_REQUEST);
        } catch (Exception e) {
            // Fallback: write to app external files
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "Saved to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void injectSyncScript() {
        // Read localStorage sasiTaskV2 and push to native for scheduling
        String js =
                "(function(){" +
                "  try {" +
                "    var raw = localStorage.getItem('sasiTaskV2') || localStorage.getItem('sasiTaskV1');" +
                "    if (raw && window.SasiNative && window.SasiNative.syncTasksFromJson) {" +
                "      window.SasiNative.syncTasksFromJson(raw);" +
                "    }" +
                "  } catch(e) { console.warn('sync failed', e); }" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-sync on resume so newly edited tasks get scheduled
        if (webView != null) {
            webView.postDelayed(this::injectSyncScript, 800);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }

        if (requestCode == SOUND_PICK_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    // Persist permission
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                try {
                    NotificationHelper.setCustomSound(this, uri);
                    Toast.makeText(this, "Custom alarm sound set ✓", Toast.LENGTH_LONG).show();
                    // Tell JS
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "try{window.dispatchEvent(new CustomEvent('sasi-sound-changed'))}catch(e){}", null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "setCustomSound", e);
                    Toast.makeText(this, "Failed to set sound: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        if (requestCode == EXPORT_SAVE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null
                    && pendingExportContent != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) {
                        out.write(pendingExportContent.getBytes(StandardCharsets.UTF_8));
                        Toast.makeText(this, "Exported successfully", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Export write failed", Toast.LENGTH_SHORT).show();
                }
            }
            pendingExportContent = null;
            pendingExportName = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
