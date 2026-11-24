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
 * Главный экран: видоискатель с живой премотром, рычаг гимбала и команды
 * панорамы из 24 кадров — круглые, с единой пиктограммой, без надписей.
 *
 * Все обработчики действий защищены: ни одно исключение во время команды
 * (гимбал, снимок) не должно ронять приложение.
 */
public final class PanoramaActivity extends Activity {

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
    private MjpegPreview preview;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView previewView;
    private TextView previewOff;
    private TextView batteryText;
    private TextView statusText;
    private TextView counter;
    private ProgressBar progress;
    private TextView logText;
    private android.widget.ScrollView logScroll;
    private final java.util.ArrayDeque<String> logHistory = new java.util.ArrayDeque<String>();
    private static final int LOG_HISTORY_LINES = 500;
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

    /** Инициализация предпросмотра (OSC или поток TCP) уже начата; не запускать её повторно. */
    private volatile boolean liveViewStarted;
    /** Запущен ли поток TCP после сбоя предпросмотра OSC (резервный канал для управления гимбалом). */
    private volatile boolean streamStarted;
    /** Получен хотя бы один кадр OSC-MJPEG — значит, условие разблокировки гимбала выполнено. */
    private volatile boolean previewFrames;

    /** Тема, применённая к этой активити: при возвращении с измененной темой пересоздаём. */
    private int appliedThemeMode = Integer.MIN_VALUE;

    /**
     * Была ли тёмная тема применена на момент создания окна.
     *
     * Нужно для режима «как в системе»: система может сменить день на ночь, пока
     * приложение в фоне, и тогда возврат на панель должен пересоздать окно с новой
     * палитрой — одной проверки themeMode() для этого мало, режим-то не менялся.
     */
    private boolean appliedDark;

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
                        // Некорректное уведомление не должно приводить к сбою приложения.
                    }
                }
            };

    private static final int NOTIFICATION_BATTERY_UPDATE = 8195;

    /** Подождать первому кадру OSC-MJPEG, прежде чем отдавать инициативу TCP-потоку. */
    private static final long MJPEG_FIRST_FRAME_MS = 6000L;

    /**
     * Максимальное время ожидания реального кадра предпросмотра перед стартом съёмки.
     *
     * Камера отвечает 200 на START_LIVE_STREAM всегда, поэтому ответ не означает, что стрим
     * пошёл. По референсу (Ripwords useGimbal: ready = connected && previewActive) гимбал
     * и съёмка работают только когда предпросмотр действительно отдаёт кадры. Ждём фактического кадра
     * (MJPEG или TCP-декод), иначе гимбал игнорирует вектор скорости.
     */
    private static final long LIVE_VIEW_WAIT_MS = 20000L;

    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Тема применяется ДО super.onCreate: окно создаётся один раз, и если сменить
        // тему после, оно остаётся с прежней — именно поэтому переключение не работало.
        app = PanoLunaApp.get(getApplicationContext());
        app.applyTheme(this);
        super.onCreate(savedInstanceState);
        appliedThemeMode = app.settings().themeMode();
        appliedDark = app.isDarkNow(this);
        setContentView(R.layout.activity_panorama);
        preview = new MjpegPreview();

        previewView = findViewById(R.id.preview);
        previewOff = findViewById(R.id.preview_off);
        batteryText = findViewById(R.id.status_battery);
        statusText = findViewById(R.id.status_text);
        counter = findViewById(R.id.pano_counter);
        progress = findViewById(R.id.pano_progress);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);

        // Долгое нажатие на лог: копирует ВЕСЬ лог в буфер обмена и сохраняет файл.
        // Нужно, чтобы можно было прислать лог целиком, а не пересказывать его.
        android.view.View.OnLongClickListener copyLog = new android.view.View.OnLongClickListener() {
            @Override
            public boolean onLongClick(android.view.View v) {
                exportLog();
                return true;
            }
        };
        logText.setOnLongClickListener(copyLog);
        if (logScroll != null) {
            logScroll.setOnLongClickListener(copyLog);
        }
        // Журнал скрыт по умолчанию — открывается кнопкой в нижней панели.
        findViewById(R.id.btn_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (logScroll == null) {
                    return;
                }
                boolean show = logScroll.getVisibility() != View.VISIBLE;
                logScroll.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    refreshLogText();
                    logScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            logScroll.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
        startButton = findViewById(R.id.btn_start);
        pauseButton = findViewById(R.id.btn_pause);
        resumeButton = findViewById(R.id.btn_resume);
        stopButton = findViewById(R.id.btn_stop);

        app.shooter().setListener(shooterListener);
        // Отладочная строка о первом векторе движения: если камера стоит, видно,
        // дошли ли команды до гимбала и с какими значениями.
        app.shooter().gimbal().setLogListener(new com.panoluna.app.camera.Gimbal.LogListener() {
            @Override
            public void onGimbalCommand(final int panPercent, final int tiltPercent,
                                        final int panDegPerSec, final int tiltDegPerSec) {
                log("Вектор гимбала: pan " + panPercent + " tilt " + tiltPercent
                        + " (расчёт " + panDegPerSec + "/" + tiltDegPerSec + " °/с)");
            }
        });

        // Прямой эфир по TCP-потоку: декодируем MEDIA-фреймы на SurfaceView (резервный вариант для
        // OSC-MJPEG, как в официальном приложении Insta360).
        previewSurface = findViewById(R.id.preview_surface);
        previewHolder = previewSurface.getHolder();
        videoDecoder = new VideoDecoder();
        // Превью в ландшафтном формате (камера снимает 4:3 горизонтально), а экран
        // телефона — вертикальный. SurfaceView растягивает картинку на всю площадь,
        // поэтому подгоняем его размеры под пропорции кадра: подгонка по ширине,
        // высота по соотношению сторон, центрирование. Без этого превью растянуто.
        videoDecoder.setFormatListener(new VideoDecoder.FormatListener() {
            @Override
            public void onVideoSize(final int width, final int height) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyPreviewAspect(width, height);
                    }
                });
            }
        });
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
                startWithLiveViewGate();
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

    /**
     * Система сменила день/ночь при открытом приложении: в режиме «как в системе»
     * сразу перекрашиваемся, не дожидаясь перезапуска.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (app != null && app.isDarkNow(this) != appliedDark) {
            recreate();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Тему можно поменять в Настройках: при возвращении на эту панель активити
        // не пересоздаётся автоматически, и onCreate (где applyTheme) не вызовется.
        // Сравниваем и пересоздаём, чтобы тема применилась без перезапуска приложения.
        if (app.settings().themeMode() != appliedThemeMode
                || app.isDarkNow(this) != appliedDark) {
            recreate();
            return;
        }
        app.addConnectionListener(connectionListener);
        // Гимбал отвечает только во время трансляции предпросмотра. При подключённой камере
        // или повторном подключении запускаем предпросмотр до начала съёмки.
        if (app.isCameraConnected()) {
            ensureLiveView();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Страховка: если активность ушла в фон, блокировки не должны висеть.
        app.releasePanoLocks();
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

    /**
     * Подгоняет SurfaceView превью под пропорции видео, не растягивая картинку.
     *
     * SurfaceView не умеет scaleType: он всегда заполняет свои размеры целиком.
     * Поэтому считаем размеры сами: вписываем кадр в доступную область с сохранением
     * пропорций (аналог fitCenter) и центрируем.
     */
    private void applyPreviewAspect(final int videoWidth, final int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || previewSurface == null) {
            return;
        }
        final android.view.View parent = (android.view.View) previewSurface.getParent();
        if (parent == null) {
            return;
        }
        parent.post(new Runnable() {
            @Override
            public void run() {
                int availWidth = parent.getWidth();
                int availHeight = parent.getHeight();
                if (availWidth <= 0 || availHeight <= 0) {
                    return;
                }
                float videoAspect = (float) videoWidth / (float) videoHeight;
                float areaAspect = (float) availWidth / (float) availHeight;
                int targetWidth;
                int targetHeight;
                if (videoAspect > areaAspect) {
                    // Кадр шире области: вписываем по ширине, по высоте остаётся запас.
                    targetWidth = availWidth;
                    targetHeight = Math.round(availWidth / videoAspect);
                } else {
                    // Кадр выше области: вписываем по высоте.
                    targetHeight = availHeight;
                    targetWidth = Math.round(availHeight * videoAspect);
                }
                android.widget.FrameLayout.LayoutParams params =
                        (android.widget.FrameLayout.LayoutParams) previewSurface.getLayoutParams();
                params.width = targetWidth;
                params.height = targetHeight;
                params.gravity = android.view.Gravity.CENTER;
                previewSurface.setLayoutParams(params);
                android.util.Log.i("PanoLuna", "Превью " + videoWidth + "×" + videoHeight
                        + " -> " + targetWidth + "×" + targetHeight + " (без растяжения)");
            }
        });
    }

    private void startPreview() {
        try {
            previewOff.setVisibility(View.VISIBLE);
            preview.start(app.settings().getHost(), app.getWifiNetwork(),
                    new MjpegPreview.Listener() {
                        @Override
                        public void onFrame(Bitmap frame) {
                            // OSC-MJPEG работает: это основной способ предпросмотра, разблокирующий гимбал.
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
                            // Предпросмотр OSC недоступен: запускаем резервный видеопоток TCP,
                            // необходимый для управления гимбалом.
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
                            // Поток OSC закрылся. Если кадры не поступали, предпросмотр не активен;
                            // переключаемся на резервный поток TCP для управления гимбалом.
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
            // отдаём инициативу TCP-потоку (START_LIVE_STREAM), который тоже разблокирует гимбал.
            handler.removeCallbacks(mjpegWatchdog);
            handler.postDelayed(mjpegWatchdog, MJPEG_FIRST_FRAME_MS);
        } catch (Throwable t) {
            previewOff.setText(getString(R.string.main_preview_off) + ": " + t.getMessage());
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
            // При следующем подключении live view запускается заново.
            liveViewStarted = false;
            streamStarted = false;
        }
    }

    /**
     * Запускает предпросмотр один раз при подключении.
     *
     * TCP-поток START_LIVE_STREAM передаёт изображение (декодирование выполняет
     * VideoDecoder на SurfaceView) и обеспечивает управление гимбалом. По результатам
     * испытаний на Luna Ultra гимбал реагирует именно на TCP-поток START_LIVE_STREAM.
     * OSC-MJPEG передаёт изображение, но сам по себе не разблокирует гимбал; при его
     * одновременном использовании с TCP-потоком управление может не работать. В версии
     * 1.11 управление работало только через TCP, а при использовании одного OSC-MJPEG
     * или обоих каналов одновременно гимбал не реагировал. Поэтому OSC-MJPEG не включён
     * в рекомендуемую последовательность запуска.
     */
    private void ensureLiveView() {
        if (liveViewStarted) {
            return;
        }
        liveViewStarted = true;
        previewFrames = false;
        decodeActive = false;
        // TCP-поток START_LIVE_STREAM обеспечивает предпросмотр и разблокировку гимбала;
        // изображение декодируется на SurfaceView.
        startStreamFallback();
    }

    /** Кадры поступают по OSC-MJPEG либо декодируются из потока TCP. */
    private boolean liveViewActive() {
        return previewFrames || decodeActive;
    }

    /**
     * Ждёт реального кадра предпросмотра, затем стартует панораму.
     *
     * Команда START_LIVE_STREAM отправлена, но камера всегда отвечает кодом 200; это не
     * гарантирует запуск видеопотока. По имеющимся данным, гимбал отвечает только при
     * работающем предпросмотре. Поэтому проверяется поступление кадра, а не только отправка
     * команды. Если за LIVE_VIEW_WAIT_MS кадр не пришёл, съёмка всё равно начинается,
     * но пользователь получает предупреждение.
     */
    private void startWithLiveViewGate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Ждём кадр предпросмотра. Если за 6 секунд он не пришёл — перезапускаем поток
                // (объявление состояния + START): камера отвечает кодом 200 на всё, этот ответ не
                // гарантирует, что видеопоток запущен. Гимбал и съёмка работают ТОЛЬКО при реальном
                // видеопотоке — без кадра панорама замерзнет в активном положении.
                int retries = 0;
                long deadline = System.currentTimeMillis() + LIVE_VIEW_WAIT_MS;
                while (System.currentTimeMillis() < deadline && !liveViewActive()) {
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!liveViewActive() && retries < 3) {
                        retries++;
                        log("Предпросмотр: кадра нет, перезапускаю видеопоток (попытка " + retries + ")");
                        retryLiveStream();
                    }
                }
                final boolean ok = liveViewActive();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!ok) {
                                log("Предпросмотр не подтвердился кадром после повторных попыток; съёмка всё равно начнётся");
                            }
                            app.shooter().start(PanoPlan.shot24());
                        } catch (Throwable t) {
                            Toast.makeText(PanoramaActivity.this,
                                    getString(R.string.pano_start_failed) + ": " + t.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "pano-live-gate").start();
    }

    /**
     * Запускает видеопоток TCP (START_LIVE_STREAM). Это резервный способ, если
     * OSC-MJPEG не передаёт кадры: камера принимает команды гимбала только при
     * активном видеопотоке. Для декодирования кадров используется SurfaceView.
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

    /**
     * Повторно отправляет объявление состояния и START_LIVE_STREAM, обходя проверку
     * startStreamFallback. Если первая команда START не привела к поступлению кадра,
     * гимбал и съёмка не сработают: видеопоток должен быть действительно запущен.
     */
    private void retryLiveStream() {
        try {
            app.camera().enterLiveView(new com.panoluna.app.camera.CameraClient.Callback<Boolean>() {
                @Override
                public void onResult(Boolean value) {
                    log("Live-stream retry: START отправлен"
                            + (value != null && value ? ", подтверждён" : ", без подтверждения"));
                }

                @Override
                public void onError(String message) {
                    log("Live-stream retry: " + message);
                }
            });
        } catch (Throwable t) {
            log("Live-stream retry: " + t.getMessage());
        }
    }

    /**
     * Просит систему не оптимизировать батарею для этого приложения.
     *
     * На части прошивок (Xiaomi, Huawei, Samsung) агрессивная экономия отключает Wi-Fi
     * даже вопреки WifiLock, и камера теряется посередине панорамы. Один системный
     * запрос снимает это. Спрашиваем один раз: повторный показ раздражает.
     */
    private void requestIgnoreBatteryOptimization() {
        try {
            if (batteryPromptShown) {
                return;
            }
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            batteryPromptShown = true;
            android.content.Intent intent = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            // на части прошивок экрана нет — блокировки уже держат сессию
        }
    }

    private boolean batteryPromptShown;

    private void applyState(PanoShooter.State state) {
        boolean running = state == PanoShooter.State.RUNNING;
        boolean paused = state == PanoShooter.State.PAUSED;

        // Пока идёт съёмка — держим экран включённым, процессор и Wi-Fi разбуженными.
        // Панорама идёт ~3 минуты; без этого Android гасит экран через 30 с, уходит в
        // фон и отключает Wi-Fi, и камера теряется посередине съёмки.
        boolean active = running || paused;
        if (active) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            app.acquirePanoLocks();
            requestIgnoreBatteryOptimization();
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            app.releasePanoLocks();
        }
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

    /**
     * Экспорт всего лога: копирует его в буфер обмена И пишет файл в папку приложения.
     *
     * Файл нужен на случай, когда буфер обмена недоступен или его нельзя вставить в
     * мессенджер: `Android/data/com.panoluna.app/files/panoluna-log.txt`. Копия в
     * буфер — чтобы вставить прямо в чат одним движением.
     */
    private void exportLog() {
        final StringBuilder sb = new StringBuilder();
        for (String line : logHistory) {
            sb.append(line).append('\n');
        }
        final String text = sb.length() == 0 ? "Лог пуст" : sb.toString();

        // Копия в буфер обмена.
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("PanoLuna log", text));
            }
        } catch (Throwable ignored) {
            // буфер может быть недоступен — файл всё равно запишем
        }

        // Файл в приватной папке приложения.
        String path = null;
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            java.io.File file = new java.io.File(dir, "panoluna-log.txt");
            java.io.FileWriter writer = new java.io.FileWriter(file, false);
            try {
                writer.write(text);
            } finally {
                writer.close();
            }
            path = file.getAbsolutePath();
        } catch (Throwable t) {
            path = null;
        }

        String report = path != null
                ? "Лог скопирован в буфер и сохранён:\n" + path
                : "Лог скопирован в буфер обмена";
        Toast.makeText(this, report, Toast.LENGTH_LONG).show();
        log("Лог экспортирован (" + logHistory.size() + " строк)");
    }

    /**
     * Пишет строку в экранный лог с историей (последние LOG_HISTORY_LINES строк):
     * пользователь видит весь ход панорамы, а не только последнюю строку — по нему
     * можно понять, на каком шаге камера отказала.
     */
    /** Собирает всю историю журнала в TextView (панель может быть скрыта). */
    private void refreshLogText() {
        if (logText == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : logHistory) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.log_hint));
        }
        logText.setText(sb.toString());
    }

    private void log(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Метка времени нужна для разбора сбоев: по ней видно, сколько реально
                // длилась запись и где камера зависла.
                String stamped = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + "  " + message;
                logHistory.addLast(stamped);
                while (logHistory.size() > LOG_HISTORY_LINES) {
                    logHistory.removeFirst();
                }
                // Скрытый журнал не перерисовываем зря: история копится всегда,
                // а текст собирается при открытии панели. Долгое нажатие и экспорт
                // работают и при закрытой панели.
                if (logScroll == null || logScroll.getVisibility() != View.VISIBLE) {
                    return;
                }
                refreshLogText();
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(android.view.View.FOCUS_DOWN);
                    }
                });
            }
        });
    }
}
