package com.flowchat.app.device;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Real semantic acknowledgement with independent counters, without moving the readable subtree. */
public class SemanticScrollFixtureActivity extends Activity {
    private int scrolls;
    private int closes;
    private int gestures;
    private int ticks;
    private TextView metrics;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private void report() { metrics.setContentDescription("semantic_metrics:" + scrolls + ":" + closes + ":" + gestures); }
    private void animateOutsideContainer() {
        long end = android.os.SystemClock.elapsedRealtime() + 8000;
        Runnable tick = new Runnable() { public void run() {
            if (android.os.SystemClock.elapsedRealtime() < end) { metrics.setText("Outside counter " + ++ticks); handler.postDelayed(this, 60); }
        } };
        handler.post(tick);
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean canvas = "canvas".equals(getIntent().getStringExtra("mode"));
        boolean progress = "progress".equals(getIntent().getStringExtra("mode"));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(30, 150, 30, 120);
        metrics = new TextView(this); metrics.setText("Independent semantic action counters"); page.addView(metrics); report();
        Button close = new Button(this); close.setText("Dismiss blocker"); close.setContentDescription("semantic_close");
        close.setOnClickListener(v -> { closes++; close.setText("Readable article ready"); report(); });
        page.addView(close);
        ScrollView body = new ScrollView(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setScrollable(true); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { scrolls++; report(); return true; }
                return super.performAccessibilityAction(action, arguments);
            }
        };
        body.setContentDescription("semantic_body");
        if (progress || "moving".equals(getIntent().getStringExtra("mode"))) body.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) { gestures++; report(); animateOutsideContainer(); }
            return false;
        });
        if (canvas) body.addView(new View(this), new ScrollView.LayoutParams(-1, 1500));
        else {
            LinearLayout paragraphs = new LinearLayout(this); paragraphs.setOrientation(LinearLayout.VERTICAL);
            for (String text : new String[]{"First readable paragraph", "Second readable paragraph", "Third readable paragraph"}) {
                TextView label = new TextView(this); label.setText(text); label.setTextSize(22); label.setPadding(0, 30, 0, 30); paragraphs.addView(label);
            }
            if (progress) for (int index = 4; index <= 30; index++) {
                TextView label = new TextView(this); label.setText("Readable paragraph " + index);
                label.setTextSize(22); label.setPadding(0, 30, 0, 30); paragraphs.addView(label);
            }
            body.addView(paragraphs);
        }
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page);
    }
}
