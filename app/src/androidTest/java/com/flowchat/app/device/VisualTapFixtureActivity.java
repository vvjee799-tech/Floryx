package com.flowchat.app.device;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Only synthetic pixels; the target deliberately has no accessibility text, click action or index. */
public final class VisualTapFixtureActivity extends Activity {
    public static final String CONTROL = "com.flowchat.visual.fixture.CONTROL";
    private LinearLayout root;
    private TargetView picture;
    private android.app.Dialog transitionDialog;
    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra("command");
            if ("move".equals(command)) { picture.moved = true; picture.invalidate(); }
            if ("secure".equals(command)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if ("sensitive".equals(command)) addPassword();
            if ("landscape".equals(command)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            if ("portrait".equals(command)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    };

    @Override public void onCreate(Bundle state) {
        boolean offset = getIntent().getBooleanExtra("offset", false);
        if (offset) setTheme(android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        super.onCreate(state);
        if (getIntent().getBooleanExtra("portrait", false)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        picture = new TargetView(this);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        if (getIntent().getBooleanExtra("sensitive", false)) addPassword();
        setContentView(root);
        if (offset) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            getWindow().getDecorView().setPadding(0, 0, 0, 0);
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.gravity = Gravity.TOP | Gravity.LEFT;
            attributes.x = 110; attributes.y = 170; attributes.dimAmount = 0;
            attributes.width = (int) (getResources().getDisplayMetrics().widthPixels * .65f);
            attributes.height = (int) (getResources().getDisplayMetrics().heightPixels * .65f);
            getWindow().setAttributes(attributes);
        }
        // This Activity runs in the separate test APK process, without the app's AndroidX classes.
        registerReceiver(controls, new IntentFilter(CONTROL),
            android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0);
        if (getIntent().getBooleanExtra("animated", false)) picture.post(new Runnable() {
            @Override public void run() {
                if (!picture.isAttachedToWindow()) return;
                picture.invalidate();
                picture.postOnAnimation(this);
            }
        });
    }

    private void addPassword() {
        EditText field = new EditText(this);
        field.setHint("Password");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(field, new LinearLayout.LayoutParams(-1, 130));
    }

    @Override protected void onDestroy() {
        if (transitionDialog != null) transitionDialog.dismiss();
        unregisterReceiver(controls);
        super.onDestroy();
    }

    private final class TargetView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF target = new RectF();
        private boolean moved;
        private boolean activated;
        private long downAt;
        private boolean downInside;
        private float lastTouchX = -1, lastTouchY = -1;
        private float downX, downY;
        private int page = 0;

        TargetView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            if (getIntent().getBooleanExtra("swipe_pages", false) && page > 0) canvas.drawColor(Color.rgb(210, 235, 250));
            float centerX = getWidth() * (moved ? .28f : getIntent().getBooleanExtra("top_target", false) ? .22f : .70f);
            float centerY = getHeight() * (getIntent().getBooleanExtra("top_target", false) ? .158f :
                getIntent().getBooleanExtra("bottom_target", false) ? .86f : .68f);
            float radius = Math.min(getWidth(), getHeight()) * (getIntent().getBooleanExtra("small_target", false) ? .012f : .09f);
            target.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            paint.setColor(activated ? Color.rgb(35, 185, 85) : Color.rgb(30, 70, 235));
            canvas.drawRoundRect(target, radius * .15f, radius * .15f, paint);
            paint.setColor(Color.WHITE); paint.setTextSize(radius * .45f);
            canvas.drawText(activated ? "DONE" : "GO", target.left + radius * .25f, centerY + radius * .15f, paint);
            if (activated) {
                paint.setColor(Color.rgb(35, 185, 85));
                canvas.drawRect(getWidth() * .15f, getHeight() * .15f, getWidth() * .85f, getHeight() * .25f, paint);
            }
            if (getIntent().getBooleanExtra("animated", false)) {
                paint.setColor(Color.RED);
                float motion = (SystemClock.uptimeMillis() % 1000) / 1000f * getWidth() * .12f;
                canvas.drawRect(20 + motion, 20, 70 + motion, 70, paint);
            }
            if (getIntent().getBooleanExtra("trace_touches", false) && lastTouchX >= 0) {
                paint.setColor(Color.RED);
                canvas.drawCircle(lastTouchX, lastTouchY, 4, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY();
                downAt = event.getEventTime(); downInside = target.contains(event.getX(), event.getY());
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (getIntent().getBooleanExtra("swipe_pages", false) &&
                        Math.abs(event.getY() - downY) + Math.abs(event.getX() - downX) > 60) {
                    if (!getIntent().getBooleanExtra("ignore_swipes", false)) { page++; invalidate(); }
                    return true;
                }
                lastTouchX = event.getX(); lastTouchY = event.getY();
                if (downInside && target.contains(event.getX(), event.getY()) &&
                    !getIntent().getBooleanExtra("ignore_touches", false) &&
                    (!getIntent().getBooleanExtra("require_long_press", false) || event.getEventTime() - downAt >= 500)) {
                    activated = true; invalidate();
                    if (getIntent().getBooleanExtra("transition_on_tap", false)) postDelayed(() -> {
                        transitionDialog = new android.app.Dialog(VisualTapFixtureActivity.this);
                        TextView ready = new TextView(VisualTapFixtureActivity.this);
                        ready.setText("READY"); ready.setBackgroundColor(Color.rgb(35, 185, 85));
                        ready.setPadding(80, 80, 80, 80); transitionDialog.setContentView(ready); transitionDialog.show();
                    }, 80);
                }
                return true;
            }
            return true;
        }
    }
}
