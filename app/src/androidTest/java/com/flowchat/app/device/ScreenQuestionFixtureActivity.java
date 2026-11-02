package com.flowchat.app.device;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Synthetic homework pixels, deliberately absent from the accessibility text tree. */
public class ScreenQuestionFixtureActivity extends Activity {
    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        IntentFilter filter = new IntentFilter(getPackageName() + ".FINISH_SCREEN_QUESTION");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(finishReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(finishReceiver, filter);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.BLACK);
                paint.setTextSize(getWidth() * .052f);
                canvas.drawText("1. 7 x 8 = ?", getWidth() * .08f, getHeight() * .24f, paint);
                canvas.drawText("2. x + 9 = 21, x = ?", getWidth() * .08f, getHeight() * .36f, paint);
                paint.setColor(Color.rgb(30, 70, 235));
                canvas.drawRect(getWidth() * .12f, getHeight() * .60f, getWidth() * .42f, getHeight() * .72f, paint);
            }
        });
    }
    @Override public void onDestroy() { unregisterReceiver(finishReceiver); super.onDestroy(); }
}
