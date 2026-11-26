package com.flowchat.app.device;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Different-UID standard-protocol source plus an unmodified native-menu compatibility probe. */
public final class ProcessTextSourceFixtureActivity extends Activity {
    public static final String SELECTED = "R6_SELECTED 文字 😀 https://selection.invalid/page";
    public static final String ORIGINAL = "UNSELECTED_PREFIX | " + SELECTED + " | UNSELECTED_SUFFIX";
    private TextView readOnly;
    private EditText editable;
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private boolean requestedReadOnly;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 160, 32, 120);
        readOnly = new TextView(this);
        readOnly.setText(ORIGINAL, TextView.BufferType.SPANNABLE);
        readOnly.setTextIsSelectable(true);
        readOnly.setContentDescription("r6_process_readonly_source");
        readOnly.setCustomSelectionActionModeCallback(selectionEvidence("readonly"));
        root.addView(readOnly);
        editable = new EditText(this);
        editable.setText(ORIGINAL);
        editable.setShowSoftInputOnFocus(false);
        editable.setContentDescription("r6_process_editable_source");
        editable.setCustomSelectionActionModeCallback(selectionEvidence("editable"));
        root.addView(editable);
        for (boolean immutable : new boolean[] { true, false }) {
            Button button = new Button(this);
            button.setText((immutable ? "R6_PROCESS_READONLY_" : "R6_PROCESS_EDITABLE_") + instanceId);
            button.setOnClickListener(view -> {
                requestedReadOnly = immutable;
                recordResult("launched", RESULT_CANCELED, null);
                // Explicit standard protocol verification, separate from native-menu discovery.
                String target = getPackageName().substring(0, getPackageName().length() - 5);
                Intent request = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .setPackage(target).putExtra(Intent.EXTRA_PROCESS_TEXT, SELECTED)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, immutable);
                if (getPackageManager().queryIntentActivities(request, 0).size() != 1) {
                    throw new IllegalStateException("The exact test target must resolve uniquely");
                }
                startActivityForResult(request, 6206);
            });
            root.addView(button);
        }
        setContentView(root);
        recordResult("ready", RESULT_CANCELED, null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 6206) return;
        if (!requestedReadOnly && resultCode == RESULT_OK && data != null && data.hasExtra(Intent.EXTRA_PROCESS_TEXT)) {
            // A real editable caller would replace the selection on a successful returned value.
            int start = ORIGINAL.indexOf(SELECTED);
            editable.getText().replace(start, start + SELECTED.length(), data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        }
        recordResult("returned", resultCode, data);
    }

    private void recordResult(String stage, int code, Intent data) {
        try (java.io.OutputStream output = openFileOutput("r6-selection-result.json", MODE_PRIVATE)) {
            org.json.JSONObject record = new org.json.JSONObject();
            record.put("instance", instanceId).put("stage", stage).put("readOnly", requestedReadOnly)
                .put("resultCode", code).put("returnedText", data != null && data.hasExtra(Intent.EXTRA_PROCESS_TEXT))
                .put("readOnlyUnchanged", ORIGINAL.contentEquals(readOnly.getText()))
                .put("editableUnchanged", ORIGINAL.contentEquals(editable.getText()));
            output.write(record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    private ActionMode.Callback selectionEvidence(String field) {
        return new ActionMode.Callback() {
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                onPrepareActionMode(mode, menu);
                return true;
            }
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                StringBuilder evidence = new StringBuilder("field=" + field + "\n");
                for (android.content.pm.ResolveInfo handler : getPackageManager().queryIntentActivities(
                        new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)) {
                    evidence.append("handler=").append(handler.activityInfo.packageName).append(" label=")
                        .append(handler.loadLabel(getPackageManager())).append('\n');
                }
                for (int index = 0; index < menu.size(); index++) {
                    MenuItem item = menu.getItem(index);
                    evidence.append("menu=").append(item.getTitle()).append(" action=")
                        .append(item.getIntent() == null ? "none" : item.getIntent().getAction()).append('\n');
                }
                try (java.io.OutputStream output = openFileOutput("r6-selection-menu.txt", MODE_PRIVATE)) {
                    output.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                return false; // Evidence only: never add or alter a platform action.
            }
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            public void onDestroyActionMode(ActionMode mode) {}
        };
    }
}
