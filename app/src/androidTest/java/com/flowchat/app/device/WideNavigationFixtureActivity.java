package com.flowchat.app.device;

import android.os.Bundle;

/** The same read-only fixture, with bounded control output on every page. */
public class WideNavigationFixtureActivity extends NavigationFixtureActivity {
    @Override public void onCreate(Bundle state) {
        getIntent().putExtra("wide_page", true);
        super.onCreate(state);
    }
}
