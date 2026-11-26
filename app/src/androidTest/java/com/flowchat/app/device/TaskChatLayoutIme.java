package com.flowchat.app.device;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;

/** A docked keyboard window for layout tests on emulators with a virtual hardware keyboard. */
public final class TaskChatLayoutIme extends InputMethodService {
    @Override public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override public View onCreateInputView() {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        int keyHeight = Math.round(54 * getResources().getDisplayMetrics().density);
        for (String keys : new String[] {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"}) {
            LinearLayout row = new LinearLayout(this);
            for (char key : keys.toCharArray()) {
                Button button = new Button(this);
                button.setText(String.valueOf(key));
                button.setMinWidth(0);
                button.setPadding(0, 0, 0, 0);
                button.setOnClickListener(view -> insert(String.valueOf(Character.toLowerCase(key))));
                row.addView(button, new LinearLayout.LayoutParams(0, keyHeight, 1));
            }
            keyboard.addView(row);
        }
        Button space = new Button(this);
        space.setText("Space");
        space.setOnClickListener(view -> insert(" "));
        keyboard.addView(space, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyHeight));
        return keyboard;
    }

    private void insert(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.commitText(text, 1);
    }
}
