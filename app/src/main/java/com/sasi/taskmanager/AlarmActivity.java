package com.sasi.taskmanager;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class AlarmActivity extends AppCompatActivity {
    private MediaPlayer player;
    private Vibrator vibrator;
    private String taskId;
    private String title;
    private String body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        taskId = getIntent().getStringExtra("task_id");
        title = getIntent().getStringExtra("title");
        body = getIntent().getStringExtra("body");
        if (title == null) title = "Reminder";
        if (body == null) body = "";

        setContentView(R.layout.activity_alarm);

        TextView tvTitle = findViewById(R.id.alarm_title);
        TextView tvBody = findViewById(R.id.alarm_body);
        Button btnDone = findViewById(R.id.btn_done);
        Button btnSnooze = findViewById(R.id.btn_snooze);
        Button btnDismiss = findViewById(R.id.btn_dismiss);

        tvTitle.setText(title);
        tvBody.setText(body.isEmpty() ? "Time for your task" : body);

        btnDone.setOnClickListener(v -> {
            stopSound();
            NotificationHelper.cancel(this, taskId != null ? taskId : "0");
            // Tell WebView the task is done if possible
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("mark_done", taskId);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        });

        btnSnooze.setOnClickListener(v -> {
            stopSound();
            NotificationHelper.cancel(this, taskId != null ? taskId : "0");
            long snoozeMs = 10 * 60 * 1000L; // 10 min
            AlarmScheduler.schedule(this,
                    taskId != null ? taskId : "snooze_" + System.currentTimeMillis(),
                    title, body,
                    System.currentTimeMillis() + snoozeMs, true);
            finish();
        });

        btnDismiss.setOnClickListener(v -> {
            stopSound();
            NotificationHelper.cancel(this, taskId != null ? taskId : "0");
            finish();
        });

        startSoundAndVibrate();
    }

    private void startSoundAndVibrate() {
        try {
            Uri sound = NotificationHelper.getDefaultSoundUri(this);
            player = new MediaPlayer();
            player.setDataSource(this, sound);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            // Fallback to system ringtone
            try {
                Uri def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                player = MediaPlayer.create(this, def);
                if (player != null) {
                    player.setLooping(true);
                    player.start();
                }
            } catch (Exception ignored) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 600, 400, 600, 400, 600};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopSound() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
            vibrator = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopSound();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent accidental dismiss by back; force use of buttons
        // or allow dismiss
        stopSound();
        super.onBackPressed();
    }
}
