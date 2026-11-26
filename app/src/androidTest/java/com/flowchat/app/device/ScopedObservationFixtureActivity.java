package com.flowchat.app.device;

import android.app.Activity;
import android.content.*;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Independent UID, synthetic controls and Canvas pixels; no accounts, network or real submissions. */
public class ScopedObservationFixtureActivity extends Activity {
    private final Handler handler = new Handler();
    private LinearLayout content, group;
    private TextView status;
    private View pixels;
    private boolean changed, animate, flash;
    private int writes;
    private View recoveryButton;
    private TextView recoveryLabel;
    private LinearLayout originalRecoveryRow;
    private int wrongClicks;
    private TextView recoveryLedger;
    private int relayouts;
    private boolean recoveryFixture;
    private int targetDelayMillis;
    private final Runnable animation = new Runnable() {
        @Override public void run() { if (animate) { flash = !flash; pixels.invalidate(); handler.postDelayed(this, 80); } }
    };
    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            switch (intent.getStringExtra("command")) {
                case "target_relayout": if (recoveryFixture) {
                    TextView inserted = new TextView(ScopedObservationFixtureActivity.this);
                    inserted.setText("Newly loaded row " + (++relayouts));
                    group.addView(inserted, 0, new LinearLayout.LayoutParams(-1, 44)); report();
                } break;
                case "target_reparent": if (recoveryFixture && recoveryLabel != null) {
                    originalRecoveryRow.removeView(recoveryLabel);
                    LinearLayout newRow = new LinearLayout(ScopedObservationFixtureActivity.this);
                    newRow.setClickable(true);
                    newRow.addView(recoveryLabel, new LinearLayout.LayoutParams(-1, -1));
                    newRow.setOnClickListener(view -> { writes++; status.setText("Scoped completed"); report(); });
                    group.addView(newRow, new LinearLayout.LayoutParams(-1, 70)); report();
                } break;
                case "target_loading": if (recoveryFixture) {
                    recoveryButton.setEnabled(false); report();
                    handler.postDelayed(() -> { recoveryButton.setEnabled(true); report(); }, targetDelayMillis);
                } break;
                case "target_overlay": if (recoveryFixture) {
                    android.app.AlertDialog popup = new android.app.AlertDialog.Builder(ScopedObservationFixtureActivity.this)
                        .setMessage("Temporary loading notice").create();
                    popup.show(); handler.postDelayed(popup::dismiss, targetDelayMillis);
                } break;
                case "target_duplicate": if (recoveryFixture) {
                    Button duplicate = new Button(ScopedObservationFixtureActivity.this);
                    duplicate.setText("Scoped action"); duplicate.setContentDescription("r2b_action");
                    duplicate.setOnClickListener(view -> { writes++; report(); });
                    group.addView(duplicate, 0, new LinearLayout.LayoutParams(-1, 70));
                } break;
                case "target_duplicate_row": if (recoveryFixture) {
                    LinearLayout duplicateRow = new LinearLayout(ScopedObservationFixtureActivity.this);
                    duplicateRow.setClickable(true);
                    TextView duplicateLabel = new TextView(ScopedObservationFixtureActivity.this);
                    duplicateLabel.setText("Scoped action");
                    duplicateLabel.setContentDescription("r2b_action_duplicate");
                    duplicateRow.addView(duplicateLabel, new LinearLayout.LayoutParams(-1, -1));
                    duplicateRow.setOnClickListener(view -> { wrongClicks++; report(); });
                    group.addView(duplicateRow, 0, new LinearLayout.LayoutParams(-1, 70));
                    report();
                } break;
                case "remove": content.removeView(group); break;
                case "duplicate": TextView duplicate = new TextView(ScopedObservationFixtureActivity.this);
                    duplicate.setText("Duplicate root"); duplicate.setContentDescription("r2b_scope");
                    content.addView(duplicate, 0, new LinearLayout.LayoutParams(-1, 30)); break;
                case "sensitive": EditText secret = new EditText(ScopedObservationFixtureActivity.this);
                    secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    content.addView(secret, 0, new LinearLayout.LayoutParams(-1, 40)); break;
                case "secure": getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); break;
                case "animate": animate = true; animation.run(); break;
                case "change": changed = true; pixels.invalidate(); break;
                case "delayed": handler.postDelayed(() -> { changed = true; pixels.invalidate(); }, 1600); break;
                case "report": report(); break;
                case "finish": finish(); break;
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        recoveryFixture = getIntent().getBooleanExtra("recovery_fixture", false);
        targetDelayMillis = Math.max(500, Math.min(4000, getIntent().getIntExtra("target_delay_ms", 1800)));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
            }
            view.setPadding(12, top + 12, 12, bottom + 12); return insets;
        });
        for (int row = 0; row < 15; row++) {
            LinearLayout cells = new LinearLayout(this);
            for (int column = 0; column < 12; column++) {
                TextView label = new TextView(this); label.setText("Outside " + (row * 12 + column)); label.setTextSize(2);
                cells.addView(label, new LinearLayout.LayoutParams(0, 8, 1));
            }
            content.addView(cells);
        }
        group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL);
        group.setContentDescription("r2b_scope"); group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        status = new TextView(this); status.setText("Scoped ready"); status.setContentDescription("r2b_status");
        group.addView(status, new LinearLayout.LayoutParams(-1, 40));
        if (recoveryFixture && getIntent().getBooleanExtra("search_echo", false)) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            EditText searchEcho = new EditText(this);
            searchEcho.setText("Scoped action");
            searchEcho.setContentDescription("r2b_search_echo");
            searchEcho.setFocusable(true);
            searchEcho.setFocusableInTouchMode(true);
            searchEcho.setOnClickListener(view -> { wrongClicks++; report(); });
            group.addView(searchEcho, new LinearLayout.LayoutParams(-1, 70));
            searchEcho.requestFocus();
        }
        Button button = new Button(this); button.setText("Scoped action"); button.setContentDescription("r2b_action");
        recoveryButton = button;
        button.setOnClickListener(view -> { writes++; status.setText("Scoped completed"); report(); });
        if (recoveryFixture && getIntent().getBooleanExtra("label_row", false)) {
            originalRecoveryRow = new LinearLayout(this); originalRecoveryRow.setClickable(true);
            recoveryLabel = new TextView(this); recoveryLabel.setText("Scoped action"); recoveryLabel.setContentDescription("r2b_action");
            originalRecoveryRow.addView(recoveryLabel, new LinearLayout.LayoutParams(-1, -1));
            originalRecoveryRow.setOnClickListener(view -> {
                if (recoveryLabel.getParent() == originalRecoveryRow) { writes++; status.setText("Scoped completed"); }
                else wrongClicks++;
                report();
            });
            recoveryButton = originalRecoveryRow;
            group.addView(originalRecoveryRow, new LinearLayout.LayoutParams(-1, 70));
        } else group.addView(button, new LinearLayout.LayoutParams(-1, 70));
        if (recoveryFixture) {
            recoveryLedger = new TextView(this); recoveryLedger.setContentDescription("r2b_ledger");
            group.addView(recoveryLedger, new LinearLayout.LayoutParams(-1, 52));
            report();
        }
        SeekBar slider = new SeekBar(this); slider.setMax(100); slider.setProgress(42); slider.setContentDescription("r2b_range");
        group.addView(slider, new LinearLayout.LayoutParams(-1, 60));
        ScrollView scroll = new ScrollView(this) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setScrollable(true);
                info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(12, 1, false));
            }
        };
        scroll.setContentDescription("r2b_scroll");
        LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 12; row++) {
            final int index = row;
            TextView item = new TextView(this) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info);
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(index, 1, 0, 1, false));
                }
            };
            item.setText("Scoped item " + row); items.addView(item, new LinearLayout.LayoutParams(-1, 36));
        }
        scroll.addView(items); group.addView(scroll, new LinearLayout.LayoutParams(-1, 120));
        content.addView(group);
        pixels = new View(this) {
            final Paint paint = new Paint();
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(changed ? Color.BLUE : Color.WHITE);
                paint.setColor(flash ? Color.RED : Color.GREEN);
                canvas.drawRect(0, 0, getWidth(), getHeight() / 4f, paint);
            }
        };
        pixels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(pixels, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content); content.requestApplyInsets();
        IntentFilter filter = new IntentFilter(getPackageName() + ".R2B_CONTROL");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(control, filter, Context.RECEIVER_EXPORTED); else registerReceiver(control, filter);
    }

    private void report() {
        if (recoveryLedger != null) recoveryLedger.setText("writes=" + writes + ";relayouts=" + relayouts + ";ready=" + recoveryButton.isEnabled() + ";wrong=" + wrongClicks);
        String target = getPackageName().substring(0, getPackageName().length() - 5);
        sendBroadcast(new Intent(target + ".R2B_RESULT").setPackage(target).putExtra("writes", writes));
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(control); super.onDestroy(); }
}
