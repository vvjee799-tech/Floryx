package com.flowchat.app.device;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Synthetic pixels without shape/color words in the accessibility tree. No model credentials or user content. */
public final class ScreenVisionFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        final boolean animated = getIntent().getBooleanExtra("animated", false);
        final boolean emptyControls = getIntent().getBooleanExtra("empty_controls", false);
        View picture = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(235, 35, 35));
                float motion = animated ? (android.os.SystemClock.uptimeMillis() % 600) / 600f * .08f : 0f;
                canvas.drawCircle(getWidth() * (.28f + motion), getHeight() * .35f, getWidth() * .15f, paint);
                paint.setColor(Color.rgb(30, 70, 235));
                canvas.drawRect(getWidth() * .58f, getHeight() * .62f, getWidth() * .88f,
                    getHeight() * .62f + getWidth() * .30f, paint);
                paint.setColor(Color.BLACK);
                paint.setTextSize(18f);
                canvas.drawText("Aa01 wM17", getWidth() * .06f, getHeight() * .86f, paint);
                paint.setStrokeWidth(1f);
                for (int line = 0; line < 24; line++) {
                    float x = getWidth() * .06f + line * 2;
                    canvas.drawLine(x, getHeight() * .9f, x, getHeight() * .9f + 24, paint);
                }
            }
        };
        picture.setClickable(!emptyControls);
        picture.setImportantForAccessibility(emptyControls ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        if (animated) {
            final TextView counter = new TextView(this);
            if (emptyControls) counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (!emptyControls) root.addView(counter, new LinearLayout.LayoutParams(-1, 80));
            picture.post(new Runnable() {
                int tick = 0;
                @Override public void run() {
                    if (!picture.isAttachedToWindow()) return;
                    counter.setText("tick " + (++tick));
                    picture.invalidate();
                    picture.postOnAnimation(this);
                }
            });
        }
        if (getIntent().getBooleanExtra("sensitive", false)) {
            EditText password = new EditText(this);
            password.setHint("Password");
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            root.addView(password, new LinearLayout.LayoutParams(-1, 160));
        }
        setContentView(root);
    }
}
