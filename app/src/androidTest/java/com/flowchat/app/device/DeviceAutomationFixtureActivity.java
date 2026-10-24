package com.flowchat.app.device;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Runs in the separate test APK, without relying on the target app's Kotlin runtime. */
public class DeviceAutomationFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("overlay_probe", false)) {
            showOverlayProbe();
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 32, 24, 24);
        TextView title = new TextView(this);
        title.setText("FlowChat automation test");
        title.setTextSize(22);
        content.addView(title);
        EditText input = new EditText(this);
        input.setHint("Automation test input");
        input.setInputType(InputType.TYPE_CLASS_TEXT | (getIntent().getBooleanExtra("sensitive", false)
            ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        content.addView(input);
        CheckBox toggle = new CheckBox(this);
        toggle.setText("Test toggle");
        content.addView(toggle);
        TextView status = new TextView(this);
        status.setText("Gesture waiting");
        content.addView(status);
        View pad = new View(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) { return false; }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) status.setText("Gesture completed");
                return true;
            }
        };
        pad.setContentDescription("Gesture pad");
        pad.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        pad.setClickable(true);
        pad.setLongClickable(true);
        pad.setBackgroundColor(0xff90caf9);
        content.addView(pad, new LinearLayout.LayoutParams(-1, 100));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int index = 1; index <= 40; index++) {
            TextView row = new TextView(this);
            row.setText("Test row " + index);
            row.setTextSize(20);
            row.setPadding(10, 25, 10, 25);
            rows.addView(row);
        }
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (getIntent().getBooleanExtra("deep_tree", false)) {
            FrameLayout root = new FrameLayout(this);
            FrameLayout parent = root;
            for (int depth = 0; depth < 48; depth++) {
                FrameLayout nested = new FrameLayout(this);
                nested.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                parent.addView(nested, new FrameLayout.LayoutParams(-1, -1));
                parent = nested;
            }
            parent.addView(content, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
        } else {
            setContentView(content);
        }
    }

    private void showOverlayProbe() {
        TextView status = new TextView(this);
        status.setText("Touch count: 0; Waiting");
        status.setTextSize(22);
        status.setPadding(24, 64, 24, 24);
        FrameLayout pad = new FrameLayout(this) {
            private int touches = 0;
            private float downX;
            private float downY;
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) { return false; }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    touches++;
                    boolean moved = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) > 60;
                    status.setText("Touch count: " + touches + (moved ? "; Swipe completed" : "; Tap completed"));
                }
                return true;
            }
        };
        pad.setContentDescription("Overlay touch target");
        pad.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        pad.setClickable(true);
        pad.setFocusableInTouchMode(true);
        pad.setBackgroundColor(0xffdceaf7);
        pad.addView(status);
        setContentView(pad);
        pad.requestFocus();
    }
}
