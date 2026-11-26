package com.flowchat.app.device;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Deterministic continuously-updating page for explicit-submit acceptance on Android.
 * Live updates happen on an independent Handler schedule while metrics stay outside the UI.
 */
public class DynamicSubmitFixtureActivity extends Activity {
    private static final String[] TICKER_TOPICS = {
        "实时动态：城东图书馆周六开放",
        "实时动态：地铁二号线晚间延时",
        "实时动态：市民中心自助服务维护",
        "实时动态：植物园夜场预约开启",
        "实时动态：科学馆新展本周末试运行"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String mode = "hms_clock";
    private boolean noopSubmit;
    private long intervalMs = 280L;
    private boolean updatesPaused;

    private TextView liveClock;
    private TextView liveTicker;
    private EditText searchInput;
    private TextView submitResult;
    private LinearLayout popupSlot;
    private LinearLayout feedRows;

    private int tickCount;
    private int tickerIndex;
    private int feedCount;
    private int writes;
    private int submitCount;
    private int noopAttempts;
    private String submittedQuery = "";

    private final Runnable updateLoop = new Runnable() {
        @Override
        public void run() {
            if (!updatesPaused && !isFinishing() && !isDestroyed()) {
                advanceLiveState();
                handler.postDelayed(this, intervalMs);
            }
        }
    };

    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if ("finish".equals(command)) {
                finish();
                return;
            }
            if ("pause".equals(command)) {
                updatesPaused = true;
                handler.removeCallbacks(updateLoop);
            } else if ("resume".equals(command)) {
                updatesPaused = false;
                handler.removeCallbacks(updateLoop);
                handler.postDelayed(updateLoop, intervalMs);
            } else if ("popup".equals(command)) {
                showUnexpectedBanner();
            } else if ("tick".equals(command)) {
                advanceLiveState();
            }
            if ("measure".equals(command) && isOrderedBroadcast()) {
                setResultData(snapshotMetrics().toString());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mode = getIntent().getStringExtra("mode");
        if (mode == null || mode.isEmpty()) {
            mode = "hms_clock";
        }
        noopSubmit = getIntent().getBooleanExtra("noop", false);
        intervalMs = Math.max(150L, getIntent().getLongExtra("interval_ms", 280L));

        IntentFilter filter = new IntentFilter(getPackageName() + ".DYNAMIC_SUBMIT_CONTROL");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controls, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(controls, filter);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                insets.getSystemWindowInsetLeft() + dp(16),
                insets.getSystemWindowInsetTop() + dp(16),
                insets.getSystemWindowInsetRight() + dp(16),
                insets.getSystemWindowInsetBottom() + dp(16)
            );
            return insets;
        });

        TextView title = text("动态页搜索验收夹具", 18);
        title.setContentDescription("dyn_title");
        root.addView(title);

        liveClock = text("18:00:00", 15);
        liveClock.setContentDescription("dyn_clock");
        root.addView(liveClock);

        if ("ticker".equals(mode) || "both".equals(mode)) {
            liveTicker = text(TICKER_TOPICS[0], 15);
            liveTicker.setContentDescription("dyn_ticker");
            root.addView(liveTicker);
        }

        searchInput = new EditText(this);
        searchInput.setContentDescription("dyn_input");
        searchInput.setHint("输入搜索关键词并按键盘搜索");
        searchInput.setSingleLine(true);
        searchInput.setShowSoftInputOnFocus(false);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setImeActionLabel("搜索", EditorInfo.IME_ACTION_SEARCH);
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { writes++; }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            if (noopSubmit) {
                noopAttempts++;
                return true;
            }
            submitCount++;
            submittedQuery = view.getText() == null ? "" : view.getText().toString();
            submitResult.setText("已提交结果：" + submittedQuery + " (#" + submitCount + ")");
            return true;
        });
        root.addView(searchInput);

        submitResult = text("等待提交", 16);
        submitResult.setContentDescription("dyn_result");
        root.addView(submitResult);

        if ("feed".equals(mode)) {
            ScrollView feedScroll = new ScrollView(this);
            feedScroll.setContentDescription("dyn_feed_scroll");
            feedRows = new LinearLayout(this);
            feedRows.setOrientation(LinearLayout.VERTICAL);
            feedRows.setContentDescription("dyn_feed_rows");
            for (int i = 0; i < 3; i++) {
                feedRows.addView(text("实时流项目 #" + (i + 1), 14));
            }
            feedScroll.addView(feedRows);
            root.addView(feedScroll, new LinearLayout.LayoutParams(-1, dp(200)));
        }

        popupSlot = new LinearLayout(this);
        popupSlot.setOrientation(LinearLayout.VERTICAL);
        root.addView(popupSlot);

        setContentView(root);
        root.requestApplyInsets();
        searchInput.requestFocus();
        handler.postDelayed(updateLoop, intervalMs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(updateLoop);
        unregisterReceiver(controls);
        super.onDestroy();
    }

    private void advanceLiveState() {
        tickCount++;
        int seconds = tickCount % 60;
        int minutes = (tickCount / 60) % 60;
        liveClock.setText(String.format(java.util.Locale.ROOT, "18:%02d:%02d", minutes, seconds));
        if (liveTicker != null) {
            tickerIndex = (tickerIndex + 1) % TICKER_TOPICS.length;
            liveTicker.setText(TICKER_TOPICS[tickerIndex]);
        }
        if (feedRows != null) {
            feedCount++;
            feedRows.removeAllViews();
            for (int offset = 0; offset < 3 + (feedCount % 2); offset++) {
                feedRows.addView(text("实时流项目 #" + (feedCount + offset), 14));
            }
        }
    }

    private void showUnexpectedBanner() {
        if (popupSlot.getChildCount() > 0) {
            return;
        }
        TextView alert = text("系统提示：确认继续当前操作", 15);
        alert.setContentDescription("dyn_unexpected_banner");
        popupSlot.addView(alert);
    }

    private JSONObject snapshotMetrics() {
        try {
            JSONArray feed = new JSONArray();
            if (feedRows != null) {
                for (int i = 0; i < feedRows.getChildCount(); i++) {
                    feed.put(((TextView) feedRows.getChildAt(i)).getText().toString());
                }
            }
            return new JSONObject()
                .put("mode", mode)
                .put("tick_count", tickCount)
                .put("ticker_index", tickerIndex)
                .put("feed_count", feedCount)
                .put("writes", writes)
                .put("submit_count", submitCount)
                .put("noop_attempts", noopAttempts)
                .put("submitted_query", submittedQuery)
                .put("result_text", submitResult.getText().toString())
                .put("feed_items", feed);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }
}
