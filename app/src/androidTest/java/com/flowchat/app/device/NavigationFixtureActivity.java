package com.flowchat.app.device;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Read-only pages with independent navigation counters; every page replaces its root. */
public class NavigationFixtureActivity extends Activity {
    private int page, clicks, wrong;
    private boolean rearranged, widePage, titleFirst, stayOnPage;
    private int evidencePhase;
    private TextView evidenceStatus;
    private LinearLayout fixtureContent;
    private final BroadcastReceiver evidenceControl = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (getDisplay() == null || intent.getIntExtra("display_id", -1) != getDisplay().getDisplayId()) return;
            evidencePhase++;
            if (evidenceStatus == null) addEvidenceStatus();
            evidenceStatus.setText("Observation phase " + evidencePhase);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page = getIntent().getIntExtra("start_page", 0);
        rearranged = getIntent().getBooleanExtra("rearranged", false);
        widePage = getIntent().getBooleanExtra("wide_page", false);
        titleFirst = getIntent().getBooleanExtra("title_first", true);
        stayOnPage = getIntent().getBooleanExtra("stay_on_page", false);
        registerReceiver(evidenceControl, new IntentFilter(getPackageName() + ".NAVIGATION_EVIDENCE"), Context.RECEIVER_EXPORTED);
        render();
    }

    @Override public void onDestroy() {
        unregisterReceiver(evidenceControl);
        super.onDestroy();
    }

    private void addEvidenceStatus() {
        evidenceStatus = new TextView(this);
        evidenceStatus.setContentDescription("nav_evidence_phase");
        evidenceStatus.setText("Observation phase " + evidencePhase);
        fixtureContent.addView(evidenceStatus, new LinearLayout.LayoutParams(-1, 70));
    }

    private void render() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        if (widePage && titleFirst) {
            TextView current = new TextView(this); current.setText("Route page " + page);
            current.setContentDescription("nav_current_page:" + page);
            content.addView(current, new LinearLayout.LayoutParams(-1, 60));
        }
        if (widePage) {
            for (int row = 0; row < 15; row++) {
                LinearLayout cells = new LinearLayout(this);
                for (int column = 0; column < 12; column++) {
                    TextView filler = new TextView(this); filler.setText("Unrelated " + (row * 12 + column)); filler.setTextSize(2);
                    cells.addView(filler, new LinearLayout.LayoutParams(0, 8, 1));
                }
                content.addView(cells);
            }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setContentDescription("nav_page_" + page);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(24, bars.top + 24, 24, bars.bottom + 24); return insets;
        });
        TextView heading = new TextView(this);
        heading.setText(page == 99 ? "Unrelated branch" : "Route page " + page);
        root.addView(heading, new LinearLayout.LayoutParams(-1, 80));
        if (rearranged) {
            TextView extra = new TextView(this); extra.setText("Updated layout: read the current labels");
            root.addView(extra, new LinearLayout.LayoutParams(-1, 100));
        }
        if (page < 8 || page == 99) {
            Button next = new Button(this);
            next.setText(page == 99 ? "Return to routes" : "Open route " + (page + 1));
            next.setContentDescription(page == 99 ? "nav_return" : "nav_next_" + (page + 1));
            next.setOnClickListener(view -> {
                clicks++;
                if (!stayOnPage) { page = page == 99 ? 0 : page + 1; render(); }
                else {
                    // Change only an outside status, leaving the source pane untouched.
                    ((TextView)content.findViewWithTag("outside_ledger")).setText("page=" + page + ";clicks=" + clicks + ";wrong=" + wrong);
                }
            });
            Button decoy = new Button(this); decoy.setText("Unrelated option"); decoy.setContentDescription("nav_decoy");
            decoy.setOnClickListener(view -> { wrong++; render(); });
            if (rearranged) root.addView(decoy, new LinearLayout.LayoutParams(-1, 90));
            root.addView(next, new LinearLayout.LayoutParams(-1, 90));
            if (!rearranged) root.addView(decoy, new LinearLayout.LayoutParams(-1, 90));
        } else {
            TextView result = new TextView(this); result.setText("Result: ORCHID-42"); result.setContentDescription("nav_result");
            root.addView(result, new LinearLayout.LayoutParams(-1, 90));
        }
        TextView ledger = new TextView(this); ledger.setContentDescription("nav_ledger");
        ledger.setText("page=" + page + ";clicks=" + clicks + ";wrong=" + wrong);
        if (!stayOnPage) root.addView(ledger, new LinearLayout.LayoutParams(-1, 90));
        content.addView(root);
        fixtureContent = content;
        evidenceStatus = null;
        if (evidencePhase > 0) addEvidenceStatus();
        if (stayOnPage) { ledger.setTag("outside_ledger"); content.addView(ledger, new LinearLayout.LayoutParams(-1, 90)); }
        setContentView(content); content.requestApplyInsets();
    }
}
