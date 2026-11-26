package com.flowchat.app.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test-only overlay banner used by the iQOO B-group run: the operator reads every instruction here
 * because the host chat panel is not visible on the phone. Written in Java on purpose: the test APK
 * does not package the Kotlin runtime, so a Kotlin service would fail with NoClassDefFoundError.
 *
 * Start/update:
 *   adb shell am start-foreground-service -n com.floryx.app.debug.test/com.flowchat.app.device.HumanPromptService \
 *     --es title "..." --es text "..." --es footer "..." --ei done 3 --ei total 20
 * Stop:
 *   adb shell am stopservice -n com.floryx.app.debug.test/com.flowchat.app.device.HumanPromptService
 */
public class HumanPromptService extends Service {
    private static final String CHANNEL = "prompt";
    private static final int NOTIFICATION_ID = 7001;

    private WindowManager windowManager;
    private LinearLayout root;
    private TextView titleView;
    private TextView bodyView;
    private TextView footerView;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startAsForeground();
        showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) render(intent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (root != null) {
            try {
                windowManager.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        super.onDestroy();
    }

    private void startAsForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Floryx prompt", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Floryx 验收进行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void showOverlay() {
        if (root != null) return;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.argb(235, 0, 0, 0));
        container.setPadding(36, 40, 36, 40);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22f);
        titleView.setText("Floryx 验收");

        bodyView = new TextView(this);
        bodyView.setTextColor(Color.YELLOW);
        bodyView.setTextSize(34f);
        bodyView.setPadding(0, 22, 0, 22);

        footerView = new TextView(this);
        footerView.setTextColor(Color.GREEN);
        footerView.setTextSize(24f);

        container.addView(titleView);
        container.addView(bodyView);
        container.addView(footerView);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        params.y = 40;

        windowManager.addView(container, params);
        root = container;
    }

    private void render(Intent intent) {
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        String footer = intent.getStringExtra("footer");
        int done = intent.getIntExtra("done", -1);
        int total = intent.getIntExtra("total", -1);
        if (title != null && titleView != null) titleView.setText(title);
        if (text != null && bodyView != null) bodyView.setText(text);
        if (footerView != null) {
            if (done >= 0 && total > 0) {
                footerView.setText("已完成 " + done + " / " + total);
            } else if (footer != null) {
                footerView.setText(footer);
            } else {
                footerView.setText("");
            }
        }
    }
}