package com.flowchat.app.device;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.JSONObject;

/** Cross-UID synthetic forms and nested/sibling scroll containers; no accounts or network. */
public class ControlledInteractionFixtureActivity extends Activity {
    private EditText first, second, limited, truncated, formatted, unsupported;
    private ScrollView inner, outer, sibling;
    private TextView status;
    private int writes, editorActions, opaqueActions;
    private String editorLabel = "搜索";
    private int editorId = EditorInfo.IME_ACTION_SEARCH;
    private boolean unlabeledEditor;
    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override public void onReceive(Context owner, Intent intent) {
            String command = intent.getStringExtra("command");
            if ("finish".equals(command)) { finish(); return; }
            if ("lists".equals(command)) lists();
            else if ("draft".equals(command)) { first.setText("用户新草稿"); first.setSelection(first.length()); }
            else if ("cursor".equals(command)) first.setSelection(0);
            else if ("focus_second".equals(command)) second.requestFocus();
            else if ("next".equals(command)) editor("下一项", EditorInfo.IME_ACTION_NEXT);
            else if ("done".equals(command)) editor("完成", EditorInfo.IME_ACTION_DONE);
            else if ("unlabeled".equals(command)) { unlabeledEditor = true; first.sendAccessibilityEvent(2048); }
            report();
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        IntentFilter filter = new IntentFilter(getPackageName() + ".R3_CONTROL");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controls, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(controls, filter);
        forms();
    }
    @Override public void onDestroy() { unregisterReceiver(controls); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView text(String value, int height) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(16); view.setHeight(dp(height)); return view;
    }
    private EditText field(String description, String value, int kind) {
        EditText field;
        if (kind == 1) field = new EditText(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setMaxTextLength(5);
            }
        };
        else if (kind == 2) field = new EditText(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setMaxTextLength(-1);
            }
        };
        else if (kind == 3) field = new EditText(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
            }
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) throw new AssertionError("Unsupported SET_TEXT must never be dispatched");
                return super.performAccessibilityAction(action, args);
            }
        };
        else if (kind == 4) field = new EditText(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                if (unlabeledEditor && Build.VERSION.SDK_INT >= 30) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId(), null));
                }
            }
        };
        else field = new EditText(this);
        field.setContentDescription(description); field.setText(value); field.setTextSize(16);
        field.setSingleLine(false); field.setMaxLines(2); field.setShowSoftInputOnFocus(false);
        field.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(58)));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { writes++; }
            @Override public void afterTextChanged(Editable value) { report(); }
        });
        return field;
    }
    private void forms() {
        LinearLayout content = column(); content.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(text("R3 synthetic form", 32));
        first = field("r3_first", "甲😀乙", 4); second = field("r3_second", "second draft", 0);
        limited = field("r3_limited", "", 1); limited.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        truncated = field("r3_truncated", "", 2); truncated.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        formatted = field("r3_formatted", "", 0); formatted.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
        unsupported = field("r3_unsupported", "unchanged", 3);
        for (EditText view : new EditText[]{first, second, limited, truncated, formatted, unsupported}) content.addView(view);
        status = text("No editor action", 52); status.setContentDescription("r3_status"); content.addView(status);
        setContentView(content); editor("搜索", EditorInfo.IME_ACTION_SEARCH); first.setSelection(3); writes = 0;
        first.setOnEditorActionListener((view, action, event) -> {
            editorActions++; status.setText("editor=" + editorLabel + " count=" + editorActions + " id=" + action);
            if (action == EditorInfo.IME_ACTION_NEXT) second.requestFocus();
            report(); return true;
        });
        content.post(this::report);
    }
    private void editor(String label, int id) {
        editorLabel = label; editorId = id; first.setImeOptions(id); first.setImeActionLabel(label, id);
        first.requestFocus(); first.sendAccessibilityEvent(2048);
    }
    private ScrollView scroll(String description, String prefix, int count) {
        ScrollView scroll = new ScrollView(this); scroll.setContentDescription(description);
        LinearLayout rows = column();
        for (int index = 0; index < count; index++) rows.addView(text(prefix + " item " + index, 48));
        scroll.addView(rows); scroll.setOnScrollChangeListener((view, x, y, ox, oy) -> report()); return scroll;
    }
    private void lists() {
        LinearLayout root = column(); root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(text("R3 independent nested containers", 36));
        LinearLayout panes = new LinearLayout(this); panes.setOrientation(LinearLayout.HORIZONTAL);
        outer = new ScrollView(this); outer.setContentDescription("r3_outer");
        LinearLayout outerRows = column(); inner = scroll("r3_inner", "Inner", 36);
        outerRows.addView(inner, new LinearLayout.LayoutParams(-1, dp(180)));
        for (int i = 0; i < 30; i++) outerRows.addView(text("Outer item " + i, 48));
        outer.addView(outerRows); outer.setOnScrollChangeListener((view, x, y, ox, oy) -> report());
        sibling = scroll("r3_sibling", "Sibling", 36);
        panes.addView(outer, new LinearLayout.LayoutParams(0, dp(380), 1));
        panes.addView(sibling, new LinearLayout.LayoutParams(0, dp(380), 1)); root.addView(panes);
        LinearLayout opaque = new LinearLayout(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    opaqueActions++; status.setText("Unrelated counter " + opaqueActions); report(); return true;
                }
                return super.performAccessibilityAction(action, args);
            }
        };
        opaque.setContentDescription("r3_opaque"); opaque.addView(text("Opaque scroll result", 40)); root.addView(opaque);
        status = text("No container scroll", 40); status.setContentDescription("r3_status"); root.addView(status);
        setContentView(root); root.post(this::report);
    }
    private void report() {
        try {
            JSONObject value = new JSONObject();
            value.put("displayId", getDisplay().getDisplayId()); value.put("writes", writes); value.put("editorActions", editorActions);
            value.put("first", first == null ? "" : first.getText().toString()); value.put("second", second == null ? "" : second.getText().toString());
            value.put("start", first == null ? -1 : first.getSelectionStart()); value.put("end", first == null ? -1 : first.getSelectionEnd());
            value.put("innerY", inner == null ? 0 : inner.getScrollY()); value.put("outerY", outer == null ? 0 : outer.getScrollY());
            value.put("siblingY", sibling == null ? 0 : sibling.getScrollY()); value.put("opaqueActions", opaqueActions);
            sendBroadcast(new Intent(getPackageName().replace(".test", "") + ".R3_RESULT")
                .setPackage(getPackageName().replace(".test", "")).putExtra("data", value.toString()));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
