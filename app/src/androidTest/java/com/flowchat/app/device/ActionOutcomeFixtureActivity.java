package com.flowchat.app.device;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;

/** Cross-UID actions whose acknowledgements do not imply the requested app-side outcome. */
public class ActionOutcomeFixtureActivity extends Activity {
    private int delayedClicks;
    private int opaqueClicks;
    private int acknowledgements;
    private int completions;
    private String runId;
    private BroadcastReceiver stateReader;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        runId = getIntent().getStringExtra("run_id");
        final int delayMillis = Math.max(350, Math.min(5000, getIntent().getIntExtra("delay_ms", 350)));
        final boolean progress = getIntent().getBooleanExtra("show_progress", false);
        final boolean acknowledgeOnly = getIntent().getBooleanExtra("acknowledge_only", false);
        stateReader = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (runId != null && runId.equals(intent.getStringExtra("run_id"))) {
                    report("snapshot", 0, intent.getStringExtra("query_id"));
                }
            }
        };
        IntentFilter queryFilter = new IntentFilter(getPackageName() + ".ACTION_OUTCOME_FIXTURE_QUERY");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReader, queryFilter, Context.RECEIVER_EXPORTED);
        else registerReceiver(stateReader, queryFilter);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 40, 24, 24);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(24, 40 + top, 24, 24 + bottom);
            return insets;
        });
        TextView status = new TextView(this);
        status.setContentDescription("outcome_fixture_status");
        status.setText("Outcome fixture ready");
        content.addView(status);
        Button delayed = new Button(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    report("acknowledged", ++acknowledgements);
                    // Controlled fault: Android acknowledges the request but the app does no work.
                    if (acknowledgeOnly) return true;
                }
                return super.performAccessibilityAction(action, arguments);
            }
        };
        delayed.setFocusable(false);
        delayed.setText("Delayed action");
        delayed.setContentDescription("outcome_fixture_delayed");
        delayed.setOnClickListener(view -> {
            report("delayed", ++delayedClicks);
            if (progress) status.setText("Action acknowledged; result pending");
            handler.postDelayed(() -> {
                status.setText("Delayed action completed");
                report("completed", ++completions);
            }, delayMillis);
        });
        content.addView(delayed);
        Button opaque = new Button(this);
        opaque.setText("Opaque side effect");
        opaque.setContentDescription("outcome_fixture_opaque");
        opaque.setFocusable(false);
        opaque.setOnClickListener(view -> report("opaque", ++opaqueClicks));
        content.addView(opaque);
        // Keep the limit undisclosed to continue testing post-write truncation detection.
        // Advertised length limits are tested separately by ControlledInteractionDiagnosticTest.
        EditText input = new EditText(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setMaxTextLength(-1);
            }
        };
        input.setSingleLine(true);
        input.setHint("At most four characters");
        input.setContentDescription("outcome_fixture_input");
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });
        content.addView(input);
        content.setFocusableInTouchMode(true);
        setContentView(content);
        content.requestApplyInsets();
        content.requestFocus();
    }

    private void report(String kind, int count) {
        report(kind, count, null);
    }

    private void report(String kind, int count, String queryId) {
        String target = getPackageName().endsWith(".test")
            ? getPackageName().substring(0, getPackageName().length() - 5) : getPackageName();
        sendBroadcast(new Intent(target + ".ACTION_OUTCOME_FIXTURE_RESULT").setPackage(target)
            .putExtra("run_id", runId).putExtra("query_id", queryId)
            .putExtra("kind", kind).putExtra("count", count)
            .putExtra("acknowledgements", acknowledgements).putExtra("delayed", delayedClicks)
            .putExtra("opaque", opaqueClicks).putExtra("completed", completions));
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (stateReader != null) unregisterReceiver(stateReader);
        super.onDestroy();
    }
}
