package com.panoluna.app.camera;

import android.os.Handler;
import android.os.Looper;

import com.panoluna.app.data.Settings;
import com.panoluna.app.net.CameraConnection;
import com.panoluna.app.proto.Messages;
import com.panoluna.app.proto.Proto;
import com.panoluna.app.proto.ProtoCodes;
import com.panoluna.app.proto.Ucd2;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * I comandi della camera, compositi sui messaggi protobuf reali del namespace
 * {@code insta360.messages}, con callback sul thread principale.
 */
public final class CameraClient {

    /** Callback semplice, sempre invocato sul thread principale. */
    public interface Callback<T> {
        void onResult(T value);

        void onError(String message);
    }

    private final Settings settings;
    private final CameraConnection connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Пул, а не один поток: enterLiveView e awaitCaptureIdle ждут ответа секундами и
    // блокировали бы единый io-поток, мешая другим командам (например enterPanoMode)
    // дойти до камеры вовремя. Камера коррелирует по requestId, поэтому параллельно
    // можно.
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private volatile String lastError;

    public CameraClient(Settings settings, CameraConnection connection) {
        this.settings = settings;
        this.connection = connection;
    }

    public CameraConnection getConnection() {
        return connection;
    }

    public String getLastError() {
        return lastError;
    }

    void rememberError(String reason) {
        lastError = reason;
    }

    /** Esegue un comando sul thread di I/O e consegna il risultato al thread principale. */
    private <T> void run(final int code, final byte[] payload, final long timeoutMs,
                         final ValueReader<T> reader, final Callback<T> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Ucd2.Frame frame = connection.request(code, payload, timeoutMs).get();
                    final T value = reader.read(frame);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(value);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(describeError(e));
                        }
                    });
                }
            }
        });
    }

    private interface ValueReader<T> {
        T read(Ucd2.Frame frame) throws Exception;
    }

    private static String describeError(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    // ---- Stato ----

    public static final class CameraInfo {
        public String model;
        public String serial;
        public String firmware;
    }

    public static final class CameraStatus {
        public Integer batteryPercent;
        public Long freeSpaceBytes;
        public Long totalSpaceBytes;
        public Integer captureState;
        public Integer captureSeconds;
    }

    public void fetchCameraInfo(final Callback<CameraInfo> callback) {
        run(ProtoCodes.GET_OPTIONS,
                Messages.getOptions(ProtoCodes.OptionType.CAMERA_TYPE,
                        ProtoCodes.OptionType.SERIAL_NUMBER,
                        ProtoCodes.OptionType.FIRMWARE_REVISION),
                settings.getRequestTimeoutMs(), new ValueReader<CameraInfo>() {
                    @Override
                    public CameraInfo read(Ucd2.Frame frame) {
                        Proto.Reader r = new Proto.Reader(Messages.optionsValue(frame.payload));
                        CameraInfo info = new CameraInfo();
                        info.model = r.stringOrNull(ProtoCodes.OptionsField.CAMERA_TYPE);
                        info.serial = r.stringOrNull(ProtoCodes.OptionsField.SERIAL_NUMBER);
                        info.firmware = r.stringOrNull(ProtoCodes.OptionsField.FIRMWARE_REVISION);
                        return info;
                    }
                }, callback);
    }

    public void fetchStatus(final Callback<CameraStatus> callback) {
        run(ProtoCodes.GET_OPTIONS,
                Messages.getOptions(ProtoCodes.OptionType.BATTERY_STATUS,
                        ProtoCodes.OptionType.STORAGE_STATE),
                settings.getRequestTimeoutMs(), new ValueReader<CameraStatus>() {
                    @Override
                    public CameraStatus read(Ucd2.Frame frame) {
                        Proto.Reader r = new Proto.Reader(Messages.optionsValue(frame.payload));
                        CameraStatus status = new CameraStatus();
                        Integer level = r.intOrNull(ProtoCodes.OptionsField.BATTERY_STATUS,
                                ProtoCodes.BatteryField.BATTERY_LEVEL);
                        Integer scale = r.intOrNull(ProtoCodes.OptionsField.BATTERY_STATUS,
                                ProtoCodes.BatteryField.BATTERY_SCALE);
                        if (level != null) {
                            int full = (scale == null || scale <= 0) ? 100 : scale;
                            status.batteryPercent = Math.max(0, Math.min(100,
                                    Math.round(level * 100f / full)));
                        }
                        status.freeSpaceBytes = r.longOrNull(ProtoCodes.OptionsField.STORAGE_STATE,
                                ProtoCodes.StorageField.FREE_SPACE);
                        status.totalSpaceBytes = r.longOrNull(ProtoCodes.OptionsField.STORAGE_STATE,
                                ProtoCodes.StorageField.TOTAL_SPACE);
                        return status;
                    }
                }, callback);
    }

    /** Legge lo stato di cattura corrente: serve a sapere quando la camera e' di nuovo libera. */
    public void fetchCaptureState(final Callback<Integer> callback) {
        run(ProtoCodes.GET_CURRENT_CAPTURE_STATUS, new byte[0], settings.getRequestTimeoutMs(),
                new ValueReader<Integer>() {
                    @Override
                    public Integer read(Ucd2.Frame frame) {
                        return Messages.captureState(frame.payload, true);
                    }
                }, callback);
    }

    public void fetchPhotoOptions(final Callback<PhotoOptions> callback) {
        final int[] types = new int[]{
                ProtoCodes.PhotographyOptionType.PHOTO_SIZE_ID,
                ProtoCodes.PhotographyOptionType.ZOOM_SCALE,
                ProtoCodes.PhotographyOptionType.STILL_EXPOSURE_OPTIONS,
                ProtoCodes.PhotographyOptionType.WHITE_BALANCE,
                ProtoCodes.PhotographyOptionType.WHITE_BALANCE_VALUE,
                ProtoCodes.PhotographyOptionType.EXPOSURE_BIAS,
                ProtoCodes.PhotographyOptionType.BRIGHTNESS,
                ProtoCodes.PhotographyOptionType.RAW_CAPTURE_TYPE,
        };
        run(ProtoCodes.GET_PHOTOGRAPHY_OPTIONS,
                Messages.getPhotographyOptions(types, ProtoCodes.FunctionMode.NORMAL_IMAGE),
                settings.getRequestTimeoutMs(), new ValueReader<PhotoOptions>() {
                    @Override
                    public PhotoOptions read(Ucd2.Frame frame) {
                        Proto.Reader r = new Proto.Reader(frame.payload);
                        PhotoOptions o = new PhotoOptions();
                        o.photoSize = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.PHOTO_SIZE_ID);
                        o.zoom = r.doubleOrNull(2, ProtoCodes.PhotographyOptionsField.ZOOM_SCALE);
                        o.iso = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.STILL_EXPOSURE_OPTIONS,
                                ProtoCodes.ExposureOptionsField.ISO);
                        o.program = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.STILL_EXPOSURE_OPTIONS,
                                ProtoCodes.ExposureOptionsField.PROGRAM);
                        o.shutter = r.doubleOrNull(2, ProtoCodes.PhotographyOptionsField.STILL_EXPOSURE_OPTIONS,
                                ProtoCodes.ExposureOptionsField.SHUTTER_SPEED);
                        o.whiteBalance = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.WHITE_BALANCE);
                        o.kelvin = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.WHITE_BALANCE_VALUE);
                        o.evThirds = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.EXPOSURE_BIAS);
                        o.brightness = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.BRIGHTNESS);
                        o.raw = r.intOrNull(2, ProtoCodes.PhotographyOptionsField.RAW_CAPTURE_TYPE);
                        return o;
                    }
                }, callback);
    }

    public static final class PhotoOptions {
        public Integer photoSize;
        public Double zoom;
        public Integer iso;
        public Integer program;
        public Double shutter;
        public Integer whiteBalance;
        public Integer kelvin;
        public Integer evThirds;
        public Integer brightness;
        public Integer raw;
    }

    // ---- Scatto ----

    /** Scatto singolo. La risposta arriva quando la camera ha accettato; il file arriva dopo. */
    public void takePicture(final Callback<String> callback) {
        run(ProtoCodes.TAKE_PICTURE,
                Messages.takePicture(ProtoCodes.TakePictureMode.NORMAL),
                settings.getRequestTimeoutMs() + 9000L, new ValueReader<String>() {
                    @Override
                    public String read(Ucd2.Frame frame) {
                        return Messages.photoUri(frame.payload);
                    }
                }, callback);
    }

    /**
     * Scattare una panoramica: la camera deve essere già in modalità pano e il
     * corpo deve portare isInstaPanoEnabled=true.
     */
    public void takePicturePano(final Callback<String> callback) {
        run(ProtoCodes.TAKE_PICTURE,
                Messages.takePicturePano(),
                settings.getRequestTimeoutMs() + 9000L, new ValueReader<String>() {
                    @Override
                    public String read(Ucd2.Frame frame) {
                        return Messages.photoUri(frame.payload);
                    }
                }, callback);
    }

    /**
     * Поставить камеру в live view (START_LIVE_STREAM).
     *
     * Гимбал отвечает только во время трансляции preview: без этой последовательности
     * команды гимбала игнорируются. START_LIVE_STREAM запускает трансляцию видео по
     * TCP (фреймы MEDIA уже читает ридер коннекции).
     *
     * Перед START отправляется SET_ACCESS_CAMERA_FILE_STATE(LIVE_VIEW=5) = [0x08 0x05]
     * — best-effort, как в референсе Ripwords/insta360-luna-ultra-desktop (liveview.rs,
     * send_start). С некоторого firmware камера складывается при подключении приложения
     * и остаётся сложенной через START_LIVE_STREAM: только это объявление разворачивает
     * её. Без него гимбал остаётся запертым — вот почему гимбал не отвечал.
     */
    public void enterLiveView(final Callback<Boolean> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Объявление live-view. ОТПРАВЛЯЕТСЯ ОДИН РАЗ и ожидается ответ: в рабочем
                // probe Ripwords 118 идёт как request с ожиданием, затем START — иначе
                // гонка, и камера не успевает войти в live-view до стрима.
                try {
                    connection.request(ProtoCodes.SET_ACCESS_CAMERA_FILE_STATE,
                            Messages.setAccessCameraFileState(5),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // старое firmware может не знать команду — START всё равно пойдёт
                }
                try {
                    connection.request(ProtoCodes.START_LIVE_STREAM,
                            Messages.startLiveStream(),
                            settings.getRequestTimeoutMs()).get();
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(describeError(e));
                        }
                    });
                    return;
                }
                final boolean result = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(result);
                    }
                });
            }
        });
    }

    /**
     * Объявление live-view + START: пара 118 → START, как в официальном приложении.
     *
     * Подтверждено на устройстве 2026-11-07 (Ripwords PROTOCOL-GAP.md): свежая прошивка
     * СКЛАДЫВАЕТ камеру при подключении приложения, и голый START_LIVE_STREAM её больше
     * не разворачивает — нужен 118 с LIVE_VIEW(5) перед КАЖДЫМ START. При этом 118
     * отправляется именно перед START, а НЕ перед каждым движением гимбала: на активном
     * стриме лишний 118 ломает движение (регрессия v1.16.7).
     */
    /**
     * Разблокирует камеру, зависшую в состоянии съёмки.
     *
     * Снимок 48 МП проходит через ту же цепочку, что держит стрим live-view. Если
     * камера не закрыла цикл (наблюдали зависание на 3-м кадре: статус «занято»
     * дольше 25 с при реальном времени записи ~5 с), повторный START_LIVE_STREAM
     * восстанавливает цепочку и отпускает состояние. Тот же приём, что
     * ensureRunningForCapture() в PreviewController эталона.
     */
    public void recoverFromStuckCapture() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connection.request(ProtoCodes.SET_ACCESS_CAMERA_FILE_STATE,
                            Messages.setAccessCameraFileState(5),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // старое firmware может не знать команду
                }
                try {
                    connection.request(ProtoCodes.START_LIVE_STREAM,
                            Messages.startLiveStream(),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // ack не гарантирует стрим; важен сам факт повторного START
                }
            }
        });
    }

    /** Занята ли камера съёмкой прямо сейчас (короткий запрос, не блокирует надолго). */
    public boolean isCaptureStuck() {
        try {
            Ucd2.Frame frame = connection.request(ProtoCodes.GET_CURRENT_CAPTURE_STATUS,
                    new byte[0], 2500L).get();
            int state = Messages.captureState(frame.payload, true);
            return ProtoCodes.CaptureState.isBusy(state);
        } catch (Exception e) {
            // Нет ответа — считаем, что камера ещё не освободилась.
            return true;
        }
    }

    /** Живо ли управляющее соединение: на мёртвом сокете команды не уходят. */
    public boolean isConnected() {
        return connection.isConnected();
    }

    /** Сеть, к которой привязан сокет: нужна для переподключения. */
    private android.net.Network boundNetwork;

    public void setBoundNetwork(android.net.Network network) {
        this.boundNetwork = network;
    }

    /**
     * Переподключается к камере после обрыва.
     *
     * Камера рвёт управляющий TCP во время съёмки — это известное поведение Luna
     * (в Ripwords есть auto-reconnect с backoff). Переподключаемся на ту же сеть,
     * что была при первом подключении, иначе Android уйдёт в мобильную сеть.
     *
     * @return true, если сокет снова открыт
     */
    public boolean reconnect() {
        try {
            connection.connect(settings.getHost(), settings.getPort(), boundNetwork, 6000L);
            return connection.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public void announceLiveView() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connection.request(ProtoCodes.SET_ACCESS_CAMERA_FILE_STATE,
                            Messages.setAccessCameraFileState(5),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // старое firmware может не знать команду — START всё равно пойдёт
                }
                try {
                    connection.request(ProtoCodes.START_LIVE_STREAM,
                            Messages.startLiveStream(),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // START ack не гарантирует кадр; повтор — только страховка
                }
            }
        });
    }

    /**
     * Перезапускает стрим live view: повторная отправка объявления разворачивания + START.
     *
     * Безопасная разблокировка: не останавливает стрим (STOP_LIVE_STREAM рискует убить
     * работающий стрим — ложный restart ломал панораму в v1.16.9), а переотправляет
     * объявление + START. Если стрим был включён — безвременен; если выключен/сломан —
     * восстанавливает. Разблокирует камеру, зависшую в SINGLE_SHOOTING, как
     * ensureRunningForCapture() в PreviewController оригинала.
     */
    public void restartLiveView() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Пара 118 → START, как требует свежая прошивка: складённую камеру голый
                // START не разворачивает. STOP не шлём — он убивает работающий стрим.
                try {
                    connection.request(ProtoCodes.SET_ACCESS_CAMERA_FILE_STATE,
                            Messages.setAccessCameraFileState(5),
                            settings.getRequestTimeoutMs()).get();
                } catch (Throwable ignored) {
                    // старое firmware может не знать команду
                }
                try {
                    connection.request(ProtoCodes.START_LIVE_STREAM,
                            Messages.startLiveStream(),
                            settings.getRequestTimeoutMs()).get();
                } catch (final Exception e) {
                    // START ack не означает, что стрим пошёл; команды гимбала
                    // отправляются на каждом движении, и стрим вернётся
                }
            }
        });
    }

    /**
     * Установить режим фото: photo_sub_mode.
     *
     * SINGLE (0) — обычный одиночный кадр (для 24-кадровой панорамы на компьютер).
     * INSTA_PANO (8) — встроенный панорамный режим камеры (19 кадров + встроенная
     * сшивка), который не используется для внешней сшивки.
     */
    public void setPhotoSubMode(final int subMode, final Callback<Boolean> callback) {
        run(ProtoCodes.SET_OPTIONS,
                Messages.setPhotoSubMode(subMode),
                settings.getRequestTimeoutMs(), new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
    }

    /**
     * Aspetta che la camera abbia finito di salvare, prima di muovere il gimbal.
     *
     * Il comando di scatto risponde appena la camera lo accetta, non quando il file e' sulla
     * scheda: fra le due cose passano piu' di due secondi su una foto da otto megapixel. Chi
     * scatta una sequenza e va avanti sulla risposta chiede lo scatto successivo mentre il
     * precedente si sta ancora scrivendo, e la camera lo lascia cadere senza dirlo.
     */
    public void awaitCaptureIdle(final long timeoutMs, final Callback<Boolean> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + timeoutMs;
                int polls = 0;
                while (System.currentTimeMillis() < deadline) {
                    final boolean[] idle = new boolean[]{false};
                    try {
                        // Опрос РАЗРЕЖЕН: каждые 1500 мс, а не 500. Снимок 48 МП пишется
                        // на камеру несколько секунд, и частые запросы статуса
                        // конкурируют с записью — камера не справляется и рвёт сессию.
                        Ucd2.Frame frame = connection.request(
                                ProtoCodes.GET_CURRENT_CAPTURE_STATUS, new byte[0], 2500L).get();
                        int state = Messages.captureState(frame.payload, true);
                        idle[0] = !ProtoCodes.CaptureState.isBusy(state);
                    } catch (Exception e) {
                        // Таймаут статуса — не повод считать камеру свободной: она может
                        // писать файл. Продолжаем ждать до общего дедлайна.
                        idle[0] = false;
                    }
                    polls++;
                    if (idle[0]) {
                        postBoolean(callback, true);
                        return;
                    }
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                postBoolean(callback, false);
            }
        });
    }

    private void postBoolean(final Callback<Boolean> callback, final boolean value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(value);
            }
        });
    }

    // ---- Gimbal ----

    /** Movimento a velocita': panPercent e tiltPercent vanno da -1 a +1. */
    public boolean gimbalVelocity(float panPercent, float tiltPercent) {
        float pan = clamp(panPercent, -1f, 1f);
        float tilt = clamp(tiltPercent, -1f, 1f);
        if (settings.isInvertPan()) {
            pan = -pan;
        }
        if (settings.isInvertTilt()) {
            tilt = -tilt;
        }
        return connection.fire(ProtoCodes.GIMBAL_CONTROL,
                Messages.gimbalMove(Math.round(pan * 100f), Math.round(tilt * 100f)));
    }

    /** Stop del gimbal: il vettore nullo, ripetuto perche' un pacchetto puo' perderci. */
    public void gimbalStop() {
        for (int i = 0; i < 4; i++) {
            connection.fire(ProtoCodes.GIMBAL_CONTROL, Messages.gimbalMove(0, 0));
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void gimbalAction(final int action, final Callback<Boolean> callback) {
        run(ProtoCodes.GIMBAL_CONTROL, Messages.gimbalAction(action),
                settings.getRequestTimeoutMs(), new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
    }

    public void setGimbalSpeed(final int level, final Callback<Boolean> callback) {
        run(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS, Messages.setGimbalSpeed(level),
                settings.getRequestTimeoutMs(), new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
    }

    // ---- Impostazioni fotografia ----

    public void applyPhotoSettings(final Callback<Boolean> callback) {
        final Settings s = settings;
        final int mode = ProtoCodes.FunctionMode.NORMAL_IMAGE;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                            Messages.setPhotoSize(s.getPhotoSize(), mode),
                            settings.getRequestTimeoutMs()).get();
                    if (s.isProMode()) {
                        double shutter = s.getShutterUs() / 1_000_000.0;
                        int program = shutter > 0.0
                                ? ProtoCodes.ExposureProgram.MANUAL
                                : ProtoCodes.ExposureProgram.AUTO;
                        connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                                Messages.setExposure(program, s.getIso(), shutter, mode),
                                settings.getRequestTimeoutMs()).get();
                        connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                                Messages.setWhiteBalance(s.getWhiteBalanceKelvin(), mode),
                                settings.getRequestTimeoutMs()).get();
                        connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                                Messages.setExposureBias(s.getEvThirds(), mode),
                                settings.getRequestTimeoutMs()).get();
                        connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                                Messages.setBrightness(s.getBrightness(), mode),
                                settings.getRequestTimeoutMs()).get();
                    }
                    connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                            Messages.setZoomScale(s.getZoom(), mode),
                            settings.getRequestTimeoutMs()).get();
                    connection.request(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                            Messages.setRawCapture(s.getRawCapture(), mode),
                            settings.getRequestTimeoutMs()).get();
                    postBoolean(callback, true);
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(describeError(e));
                        }
                    });
                }
            }
        });
    }

    // ---- File ----

    public static final class FilePage {
        public String[] uris;
        public Integer total;
    }

    public void getFileList(final int mediaType, final int start, final int limit,
                            final Callback<FilePage> callback) {
        run(ProtoCodes.GET_FILE_LIST, Messages.getFileList(mediaType, start, limit),
                settings.getRequestTimeoutMs() + 6000L, new ValueReader<FilePage>() {
                    @Override
                    public FilePage read(Ucd2.Frame frame) {
                        FilePage page = new FilePage();
                        page.uris = Messages.fileListUris(frame.payload);
                        page.total = Messages.fileListTotal(frame.payload);
                        return page;
                    }
                }, callback);
    }

    public void deleteFiles(final String[] uris, final Callback<Boolean> callback) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        for (String uri : uris) {
            if (uri != null && uri.length() > 0) {
                list.add(uri);
            }
        }
        run(ProtoCodes.DELETE_FILES, Messages.deleteFiles(list),
                settings.getRequestTimeoutMs() + 10000L, new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
