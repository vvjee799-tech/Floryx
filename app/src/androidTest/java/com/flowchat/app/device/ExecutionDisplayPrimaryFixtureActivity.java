package com.flowchat.app.device;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ExecutionDisplayPrimaryFixtureActivity extends Activity {
    private int touches = 0;
    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };
    private void record() {
        String target = getPackageName().endsWith(".test") ? getPackageName().substring(0, getPackageName().length() - 5) : getPackageName();
        sendBroadcast(new Intent(target + ".EXECUTION_FIXTURE_RESULT").setPackage(target)
            .putExtra("kind", "primary").putExtra("data", "{\"touches\":" + touches + "}"));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Floryx 后台执行屏验证\n主屏不应接收副屏输入");
        text.setTextSize(22);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(25, 45, 75));
        text.setGravity(android.view.Gravity.CENTER);
        setContentView(text);
        IntentFilter filter = new IntentFilter(getPackageName() + ".FINISH_EXECUTION_PRIMARY");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finishReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(finishReceiver, filter);
        record();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { touches++; record(); }
        return super.dispatchTouchEvent(event);
    }
    @Override public void onDestroy() {
        new File(getFilesDir(), "execution-display-primary.json").delete();
        unregisterReceiver(finishReceiver); super.onDestroy();
    }
}
