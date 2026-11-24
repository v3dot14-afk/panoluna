package com.panoluna.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;

/**
 * Заставка и экран подключения.
 *
 * Подключение упрощено: пользователь сам подключается к Wi-Fi камеры «Luna Ultra …» в
 * системных настройках, затем нажимает «Подключиться». Приложение находит камеру по IP
 * (по умолчанию 192.168.42.1:6666) по текущей сети и открывает сессию управления.
 */
public final class SplashActivity extends Activity implements PanoLunaApp.ConnectionListener {

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
    private TextView statusText;
    private Button btnConnect;
    private boolean connecting;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Тема — до super.onCreate: иначе окно уже создано с прежней темой.
        app = PanoLunaApp.get(getApplicationContext());
        app.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        statusText = findViewById(R.id.statusText);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SplashActivity.this, SettingsActivity.class));
            }
        });

        app.addConnectionListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.applyTheme(this);
        app.addConnectionListener(this);
        // Уже подключено — сразу в предпросмотр.
        if (app.isCameraConnected()) {
            openMain();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.removeConnectionListener(this);
    }

    private void connect() {
        if (connecting || app.isCameraConnected()) {
            return;
        }
        connecting = true;
        setConnectingState(true);
        // Подключение по сокету блокирует до таймаута — в фоне.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = app.connectCamera();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        connecting = false;
                        if (app.isCameraConnected()) {
                            openMain();
                        } else {
                            setConnectingState(false);
                            showError(ok ? R.string.splash_status_idle
                                    : R.string.splash_connect_failed);
                        }
                    }
                });
            }
        }, "luna-connect").start();
    }

    private void setConnectingState(boolean connecting) {
        if (connecting) {
            statusText.setText(R.string.splash_status_connecting);
            btnConnect.setEnabled(false);
        } else {
            btnConnect.setEnabled(true);
        }
    }

    private void showError(int resId) {
        statusText.setText(resId);
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private void openMain() {
        if (launched) {
            return;
        }
        launched = true;
        Intent intent = new Intent(SplashActivity.this, PanoramaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public void onConnectionStateChanged(final boolean connected, final String reason) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (connected) {
                    connecting = false;
                    openMain();
                } else if (!connecting && !SplashActivity.this.isFinishing()) {
                    setConnectingState(false);
                    if (reason != null && reason.length() > 0) {
                        statusText.setText(getString(R.string.splash_connect_failed)
                                + ": " + reason);
                    } else {
                        statusText.setText(R.string.splash_status_idle);
                    }
                }
            }
        });
    }

    @Override
    public void onNotification(com.panoluna.app.proto.Ucd2.Frame frame) {
        // Управление камерой на следующих экранах.
    }
}
