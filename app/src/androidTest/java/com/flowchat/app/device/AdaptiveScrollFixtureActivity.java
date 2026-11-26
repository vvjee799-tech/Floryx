package com.flowchat.app.device;

import android.app.Activity;
import android.content.*;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;

/** Deterministic, account-free pages. Measurements come from the app, independently of the agent. */
public class AdaptiveScrollFixtureActivity extends Activity {
    private ScrollView list, outer, sibling;
    private TextView metricsView;
    private float downY, upY;
    private int gestures, selected = -1, canvasY;
    private float dragGain = 1f;
    private final JSONArray fingerTrace = new JSONArray();
    private final JSONArray movementTrace = new JSONArray(), clickTrace = new JSONArray();
    private final JSONArray outerPositions = new JSONArray(), siblingPositions = new JSONArray();
    private boolean measureNested, nestedStaged, independentMetrics;
    private int partialRow = -1, downListY, downVisibleHeight;
    private TextView partialTarget;
    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if ("finish".equals(command)) { finish(); return; }
            if ("measure".equals(command) && isOrderedBroadcast()) {
                setResultData(measurements().toString());
                return;
            }
            if ("position".equals(command) && list != null) list.scrollTo(0, intent.getIntExtra("y", 0));
            report();
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        IntentFilter filter = new IntentFilter(getPackageName() + ".SCROLL_CONTROL");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controls, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(controls, filter);
        LinearLayout root = column();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        metricsView = label("Floryx scroll acceptance", 36); root.addView(metricsView);
        String mode = getIntent().getStringExtra("mode");
        partialRow = getIntent().getIntExtra("partial_row", -1);
        measureNested = "nested".equals(mode) && partialRow >= 0;
        independentMetrics = measureNested || getIntent().getBooleanExtra("independent_metrics", false);
        View nestedLayout = null;
        if ("gain".equals(mode)) dragGain = getIntent().getIntExtra("gain_tenths", 58) / 10f;
        if ("canvas".equals(mode)) root.addView(new CanvasList(), new LinearLayout.LayoutParams(-1, 0, 1));
        else if ("web".equals(mode)) {
            WebView web = new WebView(this); web.setContentDescription("scroll_web");
            StringBuilder html = new StringBuilder("<meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0'>");
            for (int i = 0; i < 90; i++) html.append("<div style='height:40px;border-bottom:1px solid #ddd'>Web item ").append(i).append("</div>");
            web.loadDataWithBaseURL(null, html.append("</body>").toString(), "text/html", "UTF-8", null);
            root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        } else if ("nested".equals(mode)) {
            LinearLayout panes = new LinearLayout(this);
            outer = new ScrollView(this); outer.setContentDescription("scroll_outer");
            LinearLayout parent = column(); list = rows("scroll_list");
            parent.addView(list, new LinearLayout.LayoutParams(-1, dp(210)));
            for (int i = 0; i < 35; i++) parent.addView(label("Outer " + i, 40));
            outer.addView(parent); sibling = rows("scroll_sibling");
            panes.addView(outer, new LinearLayout.LayoutParams(0, dp(490), 1));
            panes.addView(sibling, new LinearLayout.LayoutParams(0, dp(490), 1)); root.addView(panes);
            if (measureNested) {
                nestedLayout = panes;
                trackPositions(outer, outerPositions);
                trackPositions(sibling, siblingPositions);
            }
        } else { list = rows("scroll_list"); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); }
        if (partialTarget != null) stageTarget(nestedLayout == null ? list : nestedLayout);
        setContentView(root); root.requestApplyInsets(); root.post(this::report);
    }
    @Override public void onDestroy() { unregisterReceiver(controls); super.onDestroy(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView label(String text, int height) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(16); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setHeight(dp(height)); return v;
    }
    private ScrollView rows(String description) {
        ScrollView scroll = dragGain > 1f ? new GainScrollView() : new ScrollView(this); scroll.setContentDescription(description); scroll.setNestedScrollingEnabled(false);
        LinearLayout rows = column();
        for (int i = 0; i < 90; i++) {
            final int item = i; TextView row = label(String.format(java.util.Locale.ROOT, "Item %02d", i), 32);
            if (i == partialRow && description.equals("scroll_list")) partialTarget = row;
            row.setContentDescription("scroll_item_" + i);
            row.setOnClickListener(v -> {
                if (partialTarget != null) {
                    try {
                        JSONObject click = new JSONObject().put("item", item)
                            .put("row_height", row.getHeight()).put("visible_height", visibleHeight(row));
                        if (measureNested) click.put("container", description);
                        clickTrace.put(click);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
                selected = item; row.setText("Opened Item " + item); report();
            });
            rows.addView(row);
        }
        scroll.addView(rows);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> report()); return scroll;
    }
    private void trackPositions(ScrollView container, JSONArray positions) {
        container.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (nestedStaged && y != oldY) positions.put(y);
            report();
        });
    }
    private void stageTarget(View layout) {
        int initialY = getIntent().getIntExtra("initial_y", -1);
        layout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (partialTarget.getHeight() == 0 || list.getHeight() == 0) return;
                view.removeOnLayoutChangeListener(this);
                int y = initialY >= 0 ? initialY : partialTarget.getTop() + partialTarget.getHeight() * 2 / 3 - list.getHeight();
                list.scrollTo(0, y);
                if (measureNested) {
                    outer.scrollTo(0, getIntent().getIntExtra("initial_outer_y", 0));
                    sibling.scrollTo(0, getIntent().getIntExtra("initial_sibling_y", 0));
                    outerPositions.put(outer.getScrollY());
                    siblingPositions.put(sibling.getScrollY());
                    nestedStaged = true;
                }
                report();
            }
        });
        list.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public boolean performAccessibilityAction(View view, int action, Bundle args) {
                int y = list.getScrollY(), visible = visibleHeight(partialTarget);
                boolean performed = super.performAccessibilityAction(view, action, args);
                if (performed && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                        || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())) {
                    recordMovement("semantic", y, visible, 0);
                    report();
                }
                return performed;
            }
        });
    }
    private static int visibleHeight(View row) {
        Rect rect = new Rect();
        return row.getGlobalVisibleRect(rect) ? rect.height() : 0;
    }
    private void recordMovement(String kind, int fromY, int visible, int fingerPixels) {
        try {
            movementTrace.put(new JSONObject().put("kind", kind).put("from_y", fromY)
                .put("visible_height_at_start", visible).put("finger_pixels", fingerPixels));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { touch(event); return super.dispatchTouchEvent(event); }
    private void touch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downY = e.getRawY();
            if (partialTarget != null) { downListY = list.getScrollY(); downVisibleHeight = visibleHeight(partialTarget); }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            upY = e.getRawY();
            int pixels = Math.round(downY - upY);
            gestures++; fingerTrace.put(pixels);
            if (partialTarget != null && Math.abs(pixels) > ViewConfiguration.get(this).getScaledTouchSlop()) {
                recordMovement("gesture", downListY, downVisibleHeight, pixels);
            }
            report();
        }
    }
    private JSONObject measurements() {
        try {
            JSONObject data = new JSONObject(); data.put("listY", list == null ? canvasY : list.getScrollY());
            data.put("outerY", outer == null ? 0 : outer.getScrollY()); data.put("siblingY", sibling == null ? 0 : sibling.getScrollY());
            data.put("fingerPixels", Math.round(downY - upY)); data.put("gestures", gestures); data.put("selected", selected);
            data.put("fingerTrace", fingerTrace);
            if (partialTarget != null) {
                data.put("target_height", partialTarget.getHeight()).put("target_visible_height", visibleHeight(partialTarget));
                data.put("movements", movementTrace).put("clicks", clickTrace);
            }
            if (measureNested) {
                data.put("nested_staged", nestedStaged);
                data.put("outer_positions", outerPositions).put("sibling_positions", siblingPositions);
            }
            return data;
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void report() {
        if (independentMetrics) return; // The model sees the page, while the test reads measurements separately.
        String data = measurements().toString();
        if (metricsView != null) metricsView.setContentDescription("scroll_metrics:" + data);
        sendBroadcast(new Intent("com.floryx.app.debug.SCROLL_RESULT").setPackage("com.floryx.app.debug").putExtra("data", data));
    }
    /** Fault injection: a target app amplifies actual drag travel; production must observe and correct it. */
    private class GainScrollView extends ScrollView {
        private int origin;
        GainScrollView() { super(AdaptiveScrollFixtureActivity.this); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) origin = getScrollY();
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                scrollTo(0, origin + Math.round((getScrollY() - origin) * dragGain));
                if (partialTarget != null && Math.abs(downY - upY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    try {
                        movementTrace.getJSONObject(movementTrace.length() - 1)
                            .put("to_y", getScrollY()).put("visible_height_at_end", visibleHeight(partialTarget));
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
                report();
            }
            return handled;
        }
        @Override public void fling(int velocityY) { /* The injected gain, rather than platform inertia, is under test. */ }
    }
    private class CanvasList extends View {
        final Paint paint = new Paint(3); float last;
        CanvasList() { super(AdaptiveScrollFixtureActivity.this); setContentDescription("scroll_canvas"); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xfffafafa); paint.setTextSize(dp(18)); paint.setColor(0xff203060);
            for (int i = 0; i < 90; i++) canvas.drawText("Canvas item " + i, dp(12), dp(40 * (i + 1)) - canvasY, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) last = e.getY();
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { canvasY = Math.max(0, Math.min(dp(3600) - getHeight(), canvasY + Math.round(last - e.getY()))); last = e.getY(); invalidate(); }
            return true;
        }
    }
}
