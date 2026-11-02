package com.flowchat.app.device;

import android.app.Activity;
import android.app.assist.AssistContent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Runs as the separate test APK, whose standalone classpath has no Kotlin runtime. */
public class AssistantContextFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 90, 24, 24);
        TextView visible = new TextView(this);
        visible.setText("SYNTHETIC_ASSIST_PAGE_VISIBLE");
        visible.setTextSize(22f);
        root.addView(visible);
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("SYNTHETIC_PASSWORD_MUST_NOT_TRANSFER");
        root.addView(password);
        TextView hidden = new TextView(this);
        hidden.setText("SYNTHETIC_HIDDEN_MUST_NOT_TRANSFER");
        hidden.setVisibility(View.INVISIBLE);
        root.addView(hidden);
        TextView offscreen = new TextView(this);
        offscreen.setText("SYNTHETIC_OFFSCREEN_MUST_NOT_TRANSFER");
        LinearLayout.LayoutParams offscreenParams = new LinearLayout.LayoutParams(-1, 100);
        offscreenParams.topMargin = getResources().getDisplayMetrics().heightPixels * 2;
        root.addView(offscreen, offscreenParams);
        setContentView(root);
    }
    @Override public void onProvideAssistContent(AssistContent content) {
        super.onProvideAssistContent(content);
        if (getIntent().getBooleanExtra("secure", false)) return;
        content.setWebUri(Uri.parse("https://example.test/current-page?private_query=MUST_NOT_TRANSFER#private_fragment"));
    }
}
