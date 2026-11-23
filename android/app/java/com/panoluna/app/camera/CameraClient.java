package com.panoluna.app.camera;

import android.os.Handler;
import android.os.Looper;

import com.panoluna.app.data.Settings;
import com.panoluna.app.net.CameraConnection;
import com.panoluna.app.proto.Messages;
import com.panoluna.app.proto.Proto;
import com.panoluna.app.proto.ProtoCodes;
import com.panoluna.app.proto.Ucd2;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
     * Mettere la camera in live view (START_LIVE_STREAM).
     *
     * Il gimbal risponde alla camera solo mentre trasmette il preview: senza
     * questa sequenza i comandi del gimbal vengono ignorati. START_LIVE_STREAM
     * avvia la trasmissione del video sul sottore TCP (i frame MEDIA vengono già
     * scartati dal lettore della connessione).
     *
     * Si invia SOLO START_LIVE_STREAM: i riferimenti verificati sulla Luna Ultra
     * (Insta360luna di Apatassini, insta360-luna-ultra-desktop di Ripwords) non
     * inviano SET_ACCESS_CAMERA_FILE_STATE. Questi comandi di accesso ai file
     * mutano lo stato della camera e, su firmware 1.0.288, la allontanano dal
     * estado in cui il gate del gimbal si attiva, lasciando la connessione ma
     * senza controllo.
     */
    public void enterLiveView(final Callback<Boolean> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
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
     * Spostare la camera in modalità panoramica (photo_sub_mode = PHOTO_INSTA_PANO)
     * prima di scattare la sequenza. Senza di questo lo TAKE_PICTURE non produce
     * lo scatto panoramimico.
     */
    public void enterPanoMode(final Callback<Boolean> callback) {
        run(ProtoCodes.SET_OPTIONS,
                Messages.setPhotoSubMode(ProtoCodes.PhotoSubMode.INSTA_PANO),
                settings.getRequestTimeoutMs(), new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
    }

    /**
     * Impostare la proporzione della panoramica in-camera: 360-sfera.
     *
     * Dopo enterPanoMode (photo_sub_mode = INSTA_PANO) la camera già знает, что
     * делать панораму, но не знает, какую — 360-сферу или 2:1. official-приложение
     * отправляет PANO_ASPECT = SPHERE_360 (1) под function_mode NORMAL_POWER_PANO_IMAGE,
     * и именно после этого единственный TAKE_PICTURE запускает встроенный панорамный
     * цикл камеры: 19 кадров, встроенный stitching, один готовый 360-JPG. Без этой
     * команды камера остаётся в INSTA_PANO без активной панорамы.
     */
    public void setPanoAspect(final Callback<Boolean> callback) {
        run(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS,
                Messages.setPanoAspect(
                        ProtoCodes.PanoAspect.SPHERE_360,
                        ProtoCodes.FunctionMode.NORMAL_POWER_PANO_IMAGE),
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
                while (System.currentTimeMillis() < deadline) {
                    final boolean[] idle = new boolean[]{false};
                    final Object lock = new Object();
                    try {
                        Ucd2.Frame frame = connection.request(
                                ProtoCodes.GET_CURRENT_CAPTURE_STATUS, new byte[0], 2500L).get();
                        int state = Messages.captureState(frame.payload, true);
                        idle[0] = !ProtoCodes.CaptureState.isBusy(state);
                    } catch (Exception e) {
                        idle[0] = false;
                    }
                    if (idle[0]) {
                        postBoolean(callback, true);
                        return;
                    }
                    try {
                        Thread.sleep(500L);
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

    /**
     * Скачать файл с камеры по HTTP: http://&lt;host&gt;&lt;uri&gt;.
     *
     * Результат встроенной панорамы — один готовый 360-JPG, который камера записывает
     * сама. Чтобы выдать пользователю готовый снимок, его нужно скачать с камеры:
     * официальная приложение делает то же (она не оставляет 19 оригиналов).
     * Возвращает содержимое файла.
     */
    public void downloadFile(final String uri, final Callback<byte[]> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String host = settings.getHost();
                    URL url = new URL("http://" + host + uri);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(30_000);
                    conn.setRequestProperty("Accept", "*/*");
                    final int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new java.io.IOException("HTTP " + code + " при скачивании " + uri);
                    }
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    final byte[] data = out.toByteArray();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(data);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(describeError(e));
                        }
                    });
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        });
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
