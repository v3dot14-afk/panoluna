package com.panoluna.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.camera.PanoPlan;
import com.panoluna.app.camera.PanoShooter;
import com.panoluna.app.preview.MjpegPreview;
import com.panoluna.app.proto.Ucd2;

/**
 * Ecrane principale: il mirino con anteprima dal vivo, la levetta del gimbal e i comandi
 * della panoramica di 24 scatti — tondi, con una sola pictogramma, senza scritte.
 *
 * Tutti i gestori di azioni sono protetti: nessuna eccezione durante un comando (gimbal,
 * scatto) deve far cadere l'app.
 */
public final class PanoramaActivity extends Activity {

    private PanoLunaApp app;
    private MjpegPreview preview;
    private Handler timer = new Handler();

    private ImageView previewView;
    private TextView previewOff;
    private TextView batteryText;
    private TextView statusText;
    private TextView counter;
    private ProgressBar progress;
    private TextView logText;
    private ImageButton startButton;
    private ImageButton pauseButton;
    private ImageButton resumeButton;
    private ImageButton stopButton;
    private JoystickView joystick;

    private float joyPan;
    private float joyTilt;
    private boolean joystickActive;

    private final Runnable joystickTick = new Runnable() {
        @Override
        public void run() {
            try {
                if (app.isCameraConnected() && joystickActive) {
                    app.gimbal().setVelocity(joyPan, joyTilt, app.camera());
                }
            } catch (Throwable ignored) {
                // nessun comando deve cadere l'app
            }
            timer.postDelayed(this, 25L);
        }
    };

    private final PanoShooter.Listener shooterListener = new PanoShooter.Listener() {
        @Override
        public void onStateChanged(final PanoShooter.State state) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyState(state);
                }
            });
        }

        @Override
        public void onProgress(final int done, final int total) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    counter.setText(getString(R.string.main_shots, done, total));
                    progress.setMax(total);
                    progress.setProgress(done);
                }
            });
        }

        @Override
        public void onShot(int index, String uri, float pan, float tilt) {
            log("Снимок " + (index + 1) + " ok");
        }

        @Override
        public void onError(final String message) {
            log("Ошибка: " + message);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PanoramaActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onLog(final String message) {
            log(message);
        }
    };

    private final PanoLunaApp.ConnectionListener connectionListener =
            new PanoLunaApp.ConnectionListener() {
                @Override
                public void onConnectionStateChanged(final boolean connected, final String reason) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyConnection(connected, reason);
                        }
                    });
                }

                @Override
                public void onNotification(Ucd2.Frame frame) {
                    if (frame == null || frame.code != NOTIFICATION_BATTERY_UPDATE) {
                        return;
                    }
                    try {
                        Integer level = new com.panoluna.app.proto.Proto.Reader(frame.payload)
                                .intOrNull(2);
                        Integer scale = new com.panoluna.app.proto.Proto.Reader(frame.payload)
                                .intOrNull(3);
                        if (level != null) {
                            int full = scale == null || scale <= 0 ? 100 : scale;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    batteryText.setText(getString(R.string.main_battery,
                                            Math.max(0, Math.min(100, level * 100 / full))));
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                        // non fallire l'app su una notifica malformata
                    }
                }
            };

    private static final int NOTIFICATION_BATTERY_UPDATE = 8195;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        setContentView(R.layout.activity_panorama);
        preview = new MjpegPreview();

        previewView = findViewById(R.id.preview);
        previewOff = findViewById(R.id.preview_off);
        batteryText = findViewById(R.id.status_battery);
        statusText = findViewById(R.id.status_text);
        counter = findViewById(R.id.pano_counter);
        progress = findViewById(R.id.pano_progress);
        logText = findViewById(R.id.log_text);
        startButton = findViewById(R.id.btn_start);
        pauseButton = findViewById(R.id.btn_pause);
        resumeButton = findViewById(R.id.btn_resume);
        stopButton = findViewById(R.id.btn_stop);
        joystick = findViewById(R.id.joystick);

        app.shooter().setListener(shooterListener);

        joystick.setListener(new JoystickView.Listener() {
            @Override
            public void onMove(float horizontal, float vertical) {
                joyPan = horizontal;
                joyTilt = vertical;
                joystickActive = true;
            }

            @Override
            public void onRelease() {
                joystickActive = false;
                joyPan = 0f;
                joyTilt = 0f;
                try {
                    app.gimbal().stop(app.camera());
                } catch (Throwable ignored) {
                }
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    app.shooter().start(PanoPlan.shot24());
                } catch (Throwable t) {
                    Toast.makeText(PanoramaActivity.this, "Старт не удалось: " + t.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    app.shooter().pause();
                } catch (Throwable ignored) {
                }
            }
        });
        resumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    app.shooter().resume();
                } catch (Throwable ignored) {
                }
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    app.shooter().stop();
                } catch (Throwable ignored) {
                }
            }
        });

        ImageButton connectButton = findViewById(R.id.btn_connect);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PanoramaActivity.this, SplashActivity.class));
            }
        });

        findViewById(R.id.btn_app_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PanoramaActivity.this, SettingsActivity.class));
            }
        });

        applyState(app.shooter().getState());
        applyConnection(app.isCameraConnected(), null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.addConnectionListener(connectionListener);
        timer.post(joystickTick);
        if (app.isCameraConnected()) {
            // Il gimbal risponde solo mentre la camera trasmette il preview:
            // avvia la live view prima di mostrare l'anteprima.
            app.camera().enterLiveView(new com.panoluna.app.camera.CameraClient.Callback<Boolean>() {
                @Override
                public void onResult(Boolean value) {
                    startPreview();
                }

                @Override
                public void onError(String message) {
                    startPreview();
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        app.removeConnectionListener(connectionListener);
        timer.removeCallbacks(joystickTick);
        preview.stop();
        joystickActive = false;
    }

    private void startPreview() {
        try {
            previewOff.setVisibility(View.VISIBLE);
            preview.start(app.settings().getHost(), app.getWifiNetwork(),
                    new MjpegPreview.Listener() {
                        @Override
                        public void onFrame(Bitmap frame) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setVisibility(View.GONE);
                                    previewView.setImageBitmap(frame);
                                }
                            });
                        }

                        @Override
                        public void onError(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setText(message);
                                }
                            });
                        }

                        @Override
                        public void onClosed() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setVisibility(View.VISIBLE);
                                    previewOff.setText(R.string.main_preview_off);
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            previewOff.setText("Предпросмотр недоступен: " + t.getMessage());
        }
    }

    private void applyConnection(boolean connected, String reason) {
        if (connected) {
            statusText.setText(getString(R.string.connect_state_connected,
                    app.settings().getHost()));
            startPreview();
        } else {
            statusText.setText(R.string.connect_state_idle);
            previewOff.setText(reason == null
                    ? getString(R.string.main_preview_off) : reason);
        }
    }

    private void applyState(PanoShooter.State state) {
        boolean running = state == PanoShooter.State.RUNNING;
        boolean paused = state == PanoShooter.State.PAUSED;
        startButton.setEnabled(!running && !paused);
        pauseButton.setEnabled(running);
        resumeButton.setEnabled(paused);
        stopButton.setEnabled(running || paused);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.35f);
        pauseButton.setAlpha(pauseButton.isEnabled() ? 1f : 0.35f);
        resumeButton.setAlpha(resumeButton.isEnabled() ? 1f : 0.35f);
        stopButton.setAlpha(stopButton.isEnabled() ? 1f : 0.35f);

        if (state == PanoShooter.State.IDLE || state == PanoShooter.State.STOPPED
                || state == PanoShooter.State.DONE || state == PanoShooter.State.ERROR) {
            counter.setText("");
            progress.setProgress(0);
        }
        if (running && app.shooter().getPlan() != null) {
            progress.setMax(app.shooter().getPlan().size());
            counter.setText(getString(R.string.main_shots, app.shooter().getCursor(),
                    app.shooter().getPlan().size()));
        }
    }

    private void log(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logText.setText(message);
            }
        });
    }
}
