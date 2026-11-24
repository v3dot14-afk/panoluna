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
    private android.widget.Spinner themeSpinner;

    /** Тема выбирается до создания окна: после super.onCreate смена не видна. */
    private void applyThemeBeforeWindow() {
        try {
            boolean dark;
            int mode = app.settings().themeMode();
            if (mode == Settings.THEME_DARK) {
                dark = true;
            } else if (mode == Settings.THEME_LIGHT) {
                dark = false;
            } else {
                dark = (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            }
            setTheme(dark ? com.panoluna.app.R.style.AppTheme
                    : com.panoluna.app.R.style.AppTheme_Light);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        app = PanoLunaApp.get(getApplicationContext());
        applyThemeBeforeWindow();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        host = findViewById(R.id.set_host);
        port = findViewById(R.id.set_port);
        timeout = findViewById(R.id.set_timeout);
        panMin = findViewById(R.id.set_pan_min);
        panMax = findViewById(R.id.set_pan_max);
        tiltMin = findViewById(R.id.set_tilt_min);
        tiltMax = findViewById(R.id.set_tilt_max);
        scale = findViewById(R.id.set_scale);
        themeSpinner = findViewById(R.id.set_theme_mode);

        host.setText(app.settings().getHost());
        port.setText(String.valueOf(app.settings().getPort()));
        timeout.setText(String.valueOf(app.settings().getRequestTimeoutMs()));
        panMin.setText(String.valueOf(app.settings().getPanMin()));
        panMax.setText(String.valueOf(app.settings().getPanMax()));
        tiltMin.setText(String.valueOf(app.settings().getTiltMin()));
        tiltMax.setText(String.valueOf(app.settings().getTiltMax()));
        scale.setText(String.valueOf(app.settings().getPanScale()));
        // Спиннер темы: порядок ровно как в strings/settings_theme_options:
        // 0 тёмная, 1 светлая, 2 как в системе. Значения Settings.THEME_* совпадают.
        android.widget.ArrayAdapter<CharSequence> themeAdapter =
                android.widget.ArrayAdapter.createFromResource(this,
                        R.array.settings_theme_options,
                        android.R.layout.simple_spinner_item);
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(themeAdapter);
        themeSpinner.setSelection(app.settings().themeMode(), false);
        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (position == app.settings().themeMode()) {
                    return;
                }
                app.settings().setThemeMode(position);
                // recreate() пересоздаёт активность с новым набором цветов: UiModeManager
                // меняет ночной режим только к следующему созданию окна, поэтому одна
                // смена флага без recreate() не даёт видимого эффекта (это и была
                // причина «тема не переключается»).
                app.applyTheme(SettingsActivity.this);
                recreate();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
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
