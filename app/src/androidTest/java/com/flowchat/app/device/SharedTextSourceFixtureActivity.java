package com.flowchat.app.device;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import java.util.Collections;

/** A different-package source for the actual Android sharesheet; synthetic text only. */
public final class SharedTextSourceFixtureActivity extends Activity {
    static final String SUMMARY_LAST_LINE = "周六开放手作课，上午10点开始，须提前预约。";
    static final String PLAIN_TEXT = "社区图书馆周末活动 😀\n" + SUMMARY_LAST_LINE
        + "\nhttps://r6-source.invalid/page";
    static final String SUMMARY_TEXT = "社区图书馆周末活动\n"
        + String.join("", Collections.nCopies(640, "活动地点和装备以公告为准。\n")) + SUMMARY_LAST_LINE;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText("R6_SHARE_SYNTHETIC_TEXT");
        button.setOnClickListener(view -> {
            String text = getIntent().getBooleanExtra("summary", false) ? SUMMARY_TEXT : PLAIN_TEXT;
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(share, "R6_SYNTHETIC_SHARE")
                .putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false));
        });
        setContentView(button);
    }
}
