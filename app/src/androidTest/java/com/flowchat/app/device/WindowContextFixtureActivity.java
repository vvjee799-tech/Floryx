package com.flowchat.app.device;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cross-UID synthetic windows only. No accounts, network, real messages or global rotation settings. */
public class WindowContextFixtureActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private EditText input;
    private int writes;
    private int gestures;
    private int windowGestures;
    private float windowMinX, windowMaxX, windowMinY, windowMaxY;
    private AlertDialog dialog;
    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if ("keyboard".equals(command)) {
                input.requestFocus();
                input.postDelayed(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 150);
            } else if ("dialog".equals(command)) {
                dialog = new AlertDialog.Builder(WindowContextFixtureActivity.this).setTitle("Dialog marker")
                    .setMessage("Synthetic popup owns input").setPositiveButton("Close fixture dialog", (d, which) -> {}).create();
                dialog.show();
            } else if ("rotate".equals(command)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            else if ("finish".equals(command)) finish();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("landscape", false)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
            }
            view.setPadding(12, 12 + top, 12, 12 + bottom);
            return insets;
        });
        input = new EditText(this);
        input.setSingleLine(true); input.setHint("Synthetic editor"); input.setContentDescription("r2_editor");
        content.addView(input, new LinearLayout.LayoutParams(-1, 100));
        status = new TextView(this); status.setText("Fixture ready"); status.setContentDescription("r2_status");
        content.addView(status, new LinearLayout.LayoutParams(-1, 60));
        Button start = new Button(this); start.setText("Start delayed result"); start.setContentDescription("r2_start");
        start.setOnClickListener(view -> {
            writes++; status.setText("Loading"); report("write", writes, null);
            handler.postDelayed(() -> status.setText("Requested result completed"), getIntent().getLongExtra("delay_ms", 2200));
        });
        content.addView(start, new LinearLayout.LayoutParams(-1, 100));
        Button failure = new Button(this); failure.setText("Synthetic business failure"); failure.setContentDescription("r2_failure");
        failure.setOnClickListener(view -> { writes++; status.setText("Business failed"); report("write", writes, null); });
        content.addView(failure, new LinearLayout.LayoutParams(-1, 100));
        Button opaque = new Button(this); opaque.setText("Opaque fixture action"); opaque.setContentDescription("r2_opaque");
        opaque.setFocusable(false);
        opaque.setOnClickListener(view -> { writes++; report("write", writes, null); });
        content.addView(opaque, new LinearLayout.LayoutParams(-1, 60));
        if (getIntent().getBooleanExtra("wide", false)) {
            for (int row = 0; row < 15; row++) {
                LinearLayout cells = new LinearLayout(this);
                for (int column = 0; column < 12; column++) {
                    TextView label = new TextView(this); label.setText("Cell " + (row * 12 + column)); label.setTextSize(3);
                    cells.addView(label, new LinearLayout.LayoutParams(0, 16, 1));
                }
                content.addView(cells);
            }
            Button late = new Button(this); late.setText("Late indexed target"); late.setContentDescription("r2_late");
            late.setOnClickListener(view -> { writes++; status.setText("Late target dispatched"); report("write", writes, null); });
            content.addView(late, new LinearLayout.LayoutParams(-1, 100));
        }
        View pad = new View(this) {
            float minX, maxX, minY, maxY;
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false;
                return super.performAccessibilityAction(action, args);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    minX = maxX = event.getRawX(); minY = maxY = event.getRawY();
                }
                minX = Math.min(minX, event.getRawX()); maxX = Math.max(maxX, event.getRawX());
                minY = Math.min(minY, event.getRawY()); maxY = Math.max(maxY, event.getRawY());
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    gestures++; status.setText("Gesture received " + gestures);
                    Bundle bounds = new Bundle(); bounds.putFloat("minX", minX); bounds.putFloat("maxX", maxX);
                    bounds.putFloat("minY", minY); bounds.putFloat("maxY", maxY); report("gesture", gestures, bounds);
                }
                return true;
            }
        };
        pad.setContentDescription("r2_fallback"); pad.setClickable(true); pad.setBackgroundColor(0xffddeeff);
        content.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content); content.requestFocus(); content.requestApplyInsets();
        IntentFilter filter = new IntentFilter(getPackageName() + ".R2_CONTROL");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(control, filter, Context.RECEIVER_EXPORTED); else registerReceiver(control, filter);
        if (getIntent().getBooleanExtra("offset", false)) {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            WindowManager.LayoutParams attrs = getWindow().getAttributes(); attrs.gravity = Gravity.CENTER;
            attrs.width = metrics.widthPixels * 3 / 4; attrs.height = metrics.heightPixels * 3 / 4;
            getWindow().setAttributes(attrs);
        }
    }

    private void report(String kind, int count, Bundle extra) {
        String target = getPackageName().endsWith(".test") ? getPackageName().substring(0, getPackageName().length() - 5) : getPackageName();
        Intent result = new Intent(target + ".R2_RESULT").setPackage(target).putExtra("kind", kind).putExtra("count", count);
        if (extra != null) result.putExtras(extra);
        sendBroadcast(result);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            windowMinX = windowMaxX = event.getRawX(); windowMinY = windowMaxY = event.getRawY();
        }
        windowMinX = Math.min(windowMinX, event.getRawX()); windowMaxX = Math.max(windowMaxX, event.getRawX());
        windowMinY = Math.min(windowMinY, event.getRawY()); windowMaxY = Math.max(windowMaxY, event.getRawY());
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            Bundle bounds = new Bundle(); bounds.putFloat("minX", windowMinX); bounds.putFloat("maxX", windowMaxX);
            bounds.putFloat("minY", windowMinY); bounds.putFloat("maxY", windowMaxY);
            bounds.putBoolean("cancelled", event.getActionMasked() == MotionEvent.ACTION_CANCEL);
            report("windowTouch", ++windowGestures, bounds);
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); unregisterReceiver(control);
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
