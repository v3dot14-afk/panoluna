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

    /**
     * Язык интерфейса выбирается в настройках и подставляется здесь: строки
     * берутся из нужного values-* ещё до создания окна, и смена языка применяется
     * без перезапуска приложения.
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(PanoLunaApp.get(base.getApplicationContext()).wrapLanguage(base));
    }

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
    private android.widget.Spinner languageSpinner;

    /** Была ли тёмная тема применена: нужно для автоматического режима. */
    private boolean appliedDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        app = PanoLunaApp.get(getApplicationContext());
        app.applyTheme(this);
        super.onCreate(savedInstanceState);
        appliedDark = app.isDarkNow(this);
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
        languageSpinner = findViewById(R.id.set_language);

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
        // Спиннер языка: 0 как в системе, 1 English, 2 Русский — порядок как в
        // settings_language_options и как в Settings.LANGUAGE_*.
        android.widget.ArrayAdapter<CharSequence> langAdapter =
                android.widget.ArrayAdapter.createFromResource(this,
                        R.array.settings_language_options,
                        android.R.layout.simple_spinner_item);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setSelection(app.settings().languageMode(), false);
        languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (position == app.settings().languageMode()) {
                    return;
                }
                app.settings().setLanguageMode(position);
                // Пересоздаём окно: строки читаются в attachBaseContext, и без
                // пересоздания они остались бы от прежней локали.
                recreate();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (position == app.settings().themeMode()) {
                    return;
                }
                app.settings().setThemeMode(position);
                // Пересоздание обязательно: цвета берутся из темы в attachBaseContext
                // и в onCreate, уже созданное окно их не перечитает.
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

    /**
     * Система сменила день/ночь при открытых настройках: в автоматическом режиме
     * перекрашиваемся сразу, не дожидаясь перезапуска.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (app != null && app.isDarkNow(this) != appliedDark) {
            recreate();
        }
    }
}
