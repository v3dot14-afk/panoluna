package com.panoluna.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import com.panoluna.app.BuildConfig;
import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.data.Settings;

/**
 * Настройки приложения: камера (IP/порт), гимбал и тема.
 * Сетевая пара Wi-Fi больше не сохраняется: подключение к сети камеры делается вручную.
 */
public final class SettingsActivity extends Activity {

    private PanoLunaApp app;
    private EditText host;
    private EditText port;
    private EditText timeout;
    private EditText panMin;
    private EditText panMax;
    private EditText tiltMin;
    private EditText tiltMax;
    private EditText scale;
    private Switch darkTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        setContentView(R.layout.activity_settings);

        host = findViewById(R.id.set_host);
        port = findViewById(R.id.set_port);
        timeout = findViewById(R.id.set_timeout);
        panMin = findViewById(R.id.set_pan_min);
        panMax = findViewById(R.id.set_pan_max);
        tiltMin = findViewById(R.id.set_tilt_min);
        tiltMax = findViewById(R.id.set_tilt_max);
        scale = findViewById(R.id.set_scale);
        darkTheme = findViewById(R.id.set_dark_theme);

        host.setText(app.settings().getHost());
        port.setText(String.valueOf(app.settings().getPort()));
        timeout.setText(String.valueOf(app.settings().getRequestTimeoutMs()));
        panMin.setText(String.valueOf(app.settings().getPanMin()));
        panMax.setText(String.valueOf(app.settings().getPanMax()));
        tiltMin.setText(String.valueOf(app.settings().getTiltMin()));
        tiltMax.setText(String.valueOf(app.settings().getTiltMax()));
        scale.setText(String.valueOf(app.settings().getPanScale()));
        darkTheme.setChecked(app.settings().darkTheme());

        darkTheme.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                app.settings().setThemeMode(isChecked ? Settings.THEME_DARK : Settings.THEME_LIGHT);
                app.applyTheme(SettingsActivity.this);
                recreate();
            }
        });

        Button clear = findViewById(R.id.set_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.settings_clear)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok,
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog,
                                                        int which) {
                                        app.settings().clearAll();
                                        finish();
                                    }
                                })
                        .show();
            }
        });

        TextView version = findViewById(R.id.set_version);
        version.setText(getString(R.string.settings_version) + " "
                + BuildConfig.VERSION_NAME + "  ·  " + app.settings().getHost());
    }

    @Override
    protected void onStop() {
        super.onStop();
        save();
    }

    private void save() {
        app.settings().setHost(host.getText().toString());
        app.settings().setPort(parseInt(port.getText().toString(), 6666));
        app.settings().setRequestTimeoutMs(parseLong(timeout.getText().toString(), 6000L));
        app.settings().setPanLimits(parseFloat(panMin.getText().toString(), -57f),
                parseFloat(panMax.getText().toString(), 235f));
        app.settings().setTiltLimits(parseFloat(tiltMin.getText().toString(), -57f),
                parseFloat(tiltMax.getText().toString(), 120f));
        app.settings().setAngularScale(parseFloat(scale.getText().toString(), 1f),
                parseFloat(scale.getText().toString(), 1f));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
