package com.john.onesecoff;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 120, 48, 48);

        TextView text = new TextView(this);
        text.setTextSize(20);
        text.setText("One Second Off\n\nTap the button, find \"One Second Off\" "
                + "(under Downloaded apps / Installed apps) and switch it on.\n");

        Button button = new Button(this);
        button.setText("Open Accessibility settings");
        button.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        layout.addView(text);
        layout.addView(button);
        setContentView(layout);
    }
}
