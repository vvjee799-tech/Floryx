package com.flowchat.app.device;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class ExecutionDisplayFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        layout.setBackgroundColor(Color.rgb(30, 55, 90));
        TextView title = new TextView(this);
        title.setText("FLORYX EXECUTION DISPLAY FIXTURE");
        title.setTextColor(Color.WHITE);
        layout.addView(title, new LinearLayout.LayoutParams(-1, 60));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setContentDescription("execution_fixture_input");
        input.setHint("Type here");
        layout.addView(input, new LinearLayout.LayoutParams(-1, 80));
        Button button = new Button(this);
        button.setText("APPLY");
        button.setContentDescription("execution_fixture_apply");
        button.setOnClickListener(view -> {
            title.setText("RESULT=" + input.getText());
            layout.setBackgroundColor(Color.rgb(20, 150, 75));
            try {
                JSONObject result = new JSONObject();
                result.put("text", input.getText().toString());
                result.put("displayId", getWindowManager().getDefaultDisplay().getDisplayId());
                report("secondary", result.toString());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        layout.addView(button, new LinearLayout.LayoutParams(-1, 80));
        setContentView(layout);
    }
    private void report(String kind, String data) {
        String target = getPackageName().endsWith(".test") ? getPackageName().substring(0, getPackageName().length() - 5) : getPackageName();
        sendBroadcast(new Intent(target + ".EXECUTION_FIXTURE_RESULT").setPackage(target)
            .putExtra("kind", kind).putExtra("data", data));
    }
    @Override public void onDestroy() {
        report("secondaryDestroyed", "{}");
        new File(getFilesDir(), "execution-display-secondary.json").delete();
        super.onDestroy();
    }
}
