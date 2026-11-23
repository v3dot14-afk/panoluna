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

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.camera.PanoPlan;
import com.panoluna.app.camera.PanoShooter;
import com.panoluna.app.preview.MjpegPreview;
import com.panoluna.app.proto.Ucd2;

/**
 * Seconda schermata: il mirino.
 *
 * Sopra l'anteprima dal vivo, a destra la levetta del gimbal, in basso al centro i quattro
 * comandi della panoramica — tondi, con una sola pictogramma, senza scritte. In fondo la
 * barra con le quattro sezioni dell'app.
 */
public final class MainActivity extends Activity {

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
            if (!app.isCameraConnected()) {
                return;
            }
            if (joystickActive) {
                app.gimbal().setVelocity(joyPan, joyTilt, app.camera());
            }
            timer.postDelayed(this, 25L);
        }
    };

    private final PanoShooter.Listener shooterListener = new PanoShooter.Listener() {
        @Override
        public void onStateChanged(PanoShooter.State state) {
            applyState(state);
        }

        @Override
        public void onProgress(int done, int total) {
            counter.setText(getString(R.string.main_shots, done, total));
            progress.setMax(total);
            progress.setProgress(done);
        }

        @Override
        public void onShot(int index, String uri, float pan, float tilt) {
            log("Scatto " + (index + 1) + " ok");
        }

        @Override
        public void onError(String message) {
            log("Ошибка: " + message);
        }

        @Override
        public void onLog(String message) {
            log(message);
        }
    };

    private final PanoLunaApp.ConnectionListener connectionListener =
            new PanoLunaApp.ConnectionListener() {
                @Override
                public void onConnectionStateChanged(boolean connected, String reason) {
                    applyConnection(connected, reason);
                }

                @Override
                public void onNotification(Ucd2.Frame frame) {
                    if (frame == null) {
                        return;
                    }
                    if (frame.code == Ucd2FrameCodes.NOTIFICATION_BATTERY_UPDATE) {
                        Integer level = new com.panoluna.app.proto.Proto.Reader(frame.payload)
                                .intOrNull(2);
                        Integer scale = new com.panoluna.app.proto.Proto.Reader(frame.payload)
                                .intOrNull(3);
                        if (level != null) {
                            int full = scale == null || scale <= 0 ? 100 : scale;
                            batteryText.setText(getString(R.string.main_battery,
                                    Math.max(0, Math.min(100, level * 100 / full))));
                        }
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        setContentView(R.layout.activity_main);
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
                app.gimbal().stop(app.camera());
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.shooter().start(PanoPlan.shot24());
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.shooter().pause();
            }
        });
        resumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.shooter().resume();
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.shooter().stop();
            }
        });

        ImageButton connectButton = findViewById(R.id.btn_connect);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ConnectActivity.class));
            }
        });

        findViewById(R.id.btn_pano).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PanoActivity.class));
            }
        });
        findViewById(R.id.btn_camera_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CameraSettingsActivity.class));
            }
        });
        findViewById(R.id.btn_gallery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, GalleryActivity.class));
            }
        });
        findViewById(R.id.btn_app_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
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
            startPreview();
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
        previewOff.setVisibility(View.VISIBLE);
        preview.start(app.settings().getHost(), app.getWifiNetwork(),
                new MjpegPreview.Listener() {
                    @Override
                    public void onFrame(Bitmap frame) {
                        previewOff.setVisibility(View.GONE);
                        previewView.setImageBitmap(frame);
                    }

                    @Override
                    public void onError(String message) {
                        previewOff.setText(message);
                    }

                    @Override
                    public void onClosed() {
                        previewOff.setVisibility(View.VISIBLE);
                        previewOff.setText(R.string.main_preview_off);
                    }
                });
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

        if (state == PanoShooter.State.IDLE || state == PanoShooter.State.STOPPED) {
            counter.setText("");
            progress.setProgress(0);
        }
        if (running && app.shooter().getPlan() != null) {
            progress.setMax(app.shooter().getPlan().size());
            counter.setText(getString(R.string.main_shots, app.shooter().getCursor(),
                    app.shooter().getPlan().size()));
        }
    }

    private void log(String message) {
        logText.setText(message);
    }

    /** Costanti di notifica usate qui, tenute insieme ai codici del protocollo. */
    private static final class Ucd2FrameCodes {
        static final int NOTIFICATION_BATTERY_UPDATE = 8195;
    }
}
