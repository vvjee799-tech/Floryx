package com.flowchat.app.device;

import android.os.Bundle;
import android.view.WindowManager;

public class ExecutionDisplaySecureFixtureActivity extends ExecutionDisplayFixtureActivity {
    @Override public void onCreate(Bundle state) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(state);
    }
}
