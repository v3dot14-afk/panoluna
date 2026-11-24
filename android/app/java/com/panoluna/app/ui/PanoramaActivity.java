package com.panoluna.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.panoluna.app.preview.VideoDecoder;
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
    private final Handler handler = new Handler(Looper.getMainLooper());

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
    private ImageButton connectButton;

    /** Декодёр прямого эфира по TCP-потоку (MediaCodec), если OSC-MJPEG недоступен. */
    private VideoDecoder videoDecoder;
    private android.view.SurfaceView previewSurface;
    private android.view.SurfaceHolder previewHolder;
    private volatile boolean decodeActive;

    /** 已经开始了 live-view 初始化（OSC 预览或 TCP 流），避免重复启动。 */
    private volatile boolean liveViewStarted;
    /** OSC 预览失败后，是否已经启动了 TCP 流（gimbal 门控的兜底）。 */
    private volatile boolean streamStarted;
    /** Пришёл хотя бы один кадр OSC-MJPEG — тогда gate гимбала уже удовлетворён. */
    private volatile boolean previewFrames;

    /** Тема, применённая к этой активити: при возвращении с измененной темой пересоздаём. */
    private int appliedThemeMode = Integer.MIN_VALUE;

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

    /** Подождать первому кадру OSC-MJPEG, прежде чем отдавать инициативу TCP-потоку. */
    private static final long MJPEG_FIRST_FRAME_MS = 6000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        appliedThemeMode = app.settings().themeMode();
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

        app.shooter().setListener(shooterListener);

        // Прямой эфир по TCP-потоку: декодируем MEDIA-фреймы на SurfaceView (fallback для
        // OSC-MJPEG, как в официальном приложении Insta360).
        previewSurface = findViewById(R.id.preview_surface);
        previewHolder = previewSurface.getHolder();
        videoDecoder = new VideoDecoder();
        previewHolder.addCallback(new android.view.SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(android.view.SurfaceHolder holder) {
                app.setVideoFrameListener(new PanoLunaApp.VideoFrameListener() {
                    @Override
                    public void onVideoFrame(byte[] annexB) {
                        PanoramaActivity.this.onVideoFrame(annexB);
                    }
                });
            }

            @Override
            public void surfaceChanged(android.view.SurfaceHolder holder, int format,
                                       int width, int height) {
            }

            @Override
            public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                app.setVideoFrameListener(null);
                videoDecoder.release();
                decodeActive = false;
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

        connectButton = findViewById(R.id.btn_connect);
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
        // Тему можно поменять в Настройках: при возвращении на эту панель активити
        // не пересоздаётся автоматически, и onCreate (где applyTheme) не вызовется.
        // Сравниваем и пересоздаём, чтобы тема применилась без перезапуска приложения.
        if (app.settings().themeMode() != appliedThemeMode) {
            recreate();
            return;
        }
        app.addConnectionListener(connectionListener);
        // Il gimbal risponde solo mentre la camera trasmette il preview. Quando la
        // camera è già connessa (o si ricolleca), avvia la live view prima dell'anteprima.
        if (app.isCameraConnected()) {
            ensureLiveView();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        app.removeConnectionListener(connectionListener);
        preview.stop();
    }

    /** Приходит MEDIA-фрейм прямого эфира по TCP: декодируем на SurfaceView. */
    private void onVideoFrame(byte[] annexB) {
        try {
            android.view.Surface surface = previewHolder.getSurface();
            if (surface == null || !surface.isValid()) {
                return;
            }
            videoDecoder.feed(surface, annexB);
            if (!decodeActive && videoDecoder.getFramesDecoded() > 0 && !previewFrames) {
                decodeActive = true;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        previewSurface.setVisibility(View.VISIBLE);
                        previewView.setVisibility(View.GONE);
                        previewOff.setVisibility(View.GONE);
                    }
                });
            }
        } catch (Throwable ignored) {
            // декодирование не должно ронять приложение
        }
    }

    private void startPreview() {
        try {
            previewOff.setVisibility(View.VISIBLE);
            preview.start(app.settings().getHost(), app.getWifiNetwork(),
                    new MjpegPreview.Listener() {
                        @Override
                        public void onFrame(Bitmap frame) {
                            // OSC-MJPEG работает: это главный путь и он открывает gate гимбала.
                            previewFrames = true;
                            handler.removeCallbacks(mjpegWatchdog);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setVisibility(View.GONE);
                                    previewSurface.setVisibility(View.GONE);
                                    previewView.setVisibility(View.VISIBLE);
                                    previewView.setImageBitmap(frame);
                                }
                            });
                        }

                        @Override
                        public void onError(final String message) {
                            // L'anteprima OSC non è disponibile: avvii il flusso TCP di live
                            // view, che è la condizione per far rispondere il gimbal.
                            handler.removeCallbacks(mjpegWatchdog);
                            startStreamFallback();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setText(message);
                                }
                            });
                        }

                        @Override
                        public void onClosed() {
                            // Поток OSC закрылся. Если кадров не пришло, preview не активен,
                            // и gate гимбала не удовлетворён: бросаемся на TCP-поток.
                            handler.removeCallbacks(mjpegWatchdog);
                            if (!previewFrames) {
                                startStreamFallback();
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    previewOff.setVisibility(View.VISIBLE);
                                    previewOff.setText(R.string.main_preview_off);
                                }
                            });
                        }
                    });
            // Ни одного кадра OSC-MJPEG за отведённое время — камера его не предлагает:
            // отдаём инициативу TCP-потоку (START_LIVE_STREAM), который тоже открывает gate.
            handler.removeCallbacks(mjpegWatchdog);
            handler.postDelayed(mjpegWatchdog, MJPEG_FIRST_FRAME_MS);
        } catch (Throwable t) {
            previewOff.setText("Предпросмотр недоступен: " + t.getMessage());
        }
    }

    /** Следит, чтобы MJPEG либо принёс кадр, либо отдал инициативу TCP-потоку. */
    private final Runnable mjpegWatchdog = new Runnable() {
        @Override
        public void run() {
            if (previewFrames || streamStarted) {
                return;
            }
            log("OSC-MJPEG не дал кадра, запускаю TCP-поток прямого эфира");
            startStreamFallback();
        }
    };

    private void applyConnection(boolean connected, String reason) {
        if (connected) {
            statusText.setText(getString(R.string.connect_state_connected,
                    app.settings().getHost()));
            // Значок связи зелёный, когда связь с камерой активна.
            connectButton.setColorFilter(Color.rgb(0x16, 0xA3, 0x4A));
            ensureLiveView();
        } else {
            statusText.setText(R.string.connect_state_idle);
            previewOff.setText(reason == null
                    ? getString(R.string.main_preview_off) : reason);
            connectButton.setColorFilter(Color.rgb(0x9A, 0xA0, 0xA6));
            // La prossima connessione riavvia la live view da zero.
            liveViewStarted = false;
            streamStarted = false;
        }
    }

    /**
     * Avvia la live view una sola volta alla connessione.
     *
     * Два канала, и оба нужны:
     * - **TCP-поток START_LIVE_STREAM** — открывает gate гимбала и съёмки: без него
     *   камера игнорирует GIMBAL_CONTROL и TAKE_PICTURE (даже отвечая 200). Отправляется
     *   всегда, не зависимо от MJPEG.
     * - **OSC-MJPEG** — картинка для экрана, если камера его предлагает.
     *
     * Если MJPEG не дал кадра, картинку даёт TCP-поток через декодёр (VideoDecoder),
     * который уже работает от того же START_LIVE_STREAM.
     */
    private void ensureLiveView() {
        if (liveViewStarted) {
            return;
        }
        liveViewStarted = true;
        previewFrames = false;
        // Gate гимбала и съёмки: TCP-поток START_LIVE_STREAM, всегда.
        startStreamFallback();
        // Картинка: OSC-MJPEG (если доступен), иначе TCP-поток через декодёр.
        startPreview();
    }

    /**
     * Avvia il flusso TCP di live view (START_LIVE_STREAM). Fallback quando il
     * MJPEG OSC не принёс кадра: la camera honora i comandi del gimbal solo
     * mentre trasmette il preview, e questo flusso apre il gate. Per decodificare
     * i frame serviamo la SurfaceView.
     */
    private void startStreamFallback() {
        if (streamStarted) {
            return;
        }
        streamStarted = true;
        // SurfaceView должна быть видимой, чтобы у неё появился Surface для декодера.
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                previewSurface.setVisibility(View.VISIBLE);
                previewView.setVisibility(View.GONE);
            }
        });
        try {
            app.camera().enterLiveView(new com.panoluna.app.camera.CameraClient.Callback<Boolean>() {
                @Override
                public void onResult(Boolean value) {
                    log(value != null && value
                            ? "Live-stream запущен (гимбал активирован)"
                            : "Live-stream отправлен без подтверждения");
                }

                @Override
                public void onError(String message) {
                    log("Live-stream TCP недоступен: " + message);
                }
            });
        } catch (Throwable t) {
            log("Live-stream TCP недоступен: " + t.getMessage());
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
