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
 * Высокоуровневые команды камере и разбор ответов протокола {@code insta360.messages}.
 *
 * Операции с I/O выполняются в пуле потоков, callback возвращаются в UI-поток. Ответ камеры
 * сам по себе не подтверждает, что настройка применена: для ряда команд прошивка отвечает
 * одинаковым кодом. В частности, статус режима/скорости по отдельному запросу не читается.
 * Настройки разрешения, RAW, экспозиции, баланса белого и зума этой версией не изменяются.
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
     * Запускает live-view через TCP-команду START_LIVE_STREAM.
     *
     * Перед START текущая реализация best-effort отправляет SET_ACCESS_CAMERA_FILE_STATE
     * с LIVE_VIEW=5. Влияние этой предварительной команды зависит от прошивки и состояния
     * камеры и не подтверждено этим методом. Успешный ответ START также не гарантирует
     * поступление видео: наличие первого кадра проверяет вызывающий код.
     */
    public void enterLiveView(final Callback<Boolean> callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Best-effort объявление состояния перед START. Ожидание ответа сохраняет
                // порядок запросов; отказ не блокирует запуск TCP live-stream.
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
     * Разблокирует камеру, зависшую в состоянии съёмки.
     *
     * Снимок в выбранном на камере режиме проходит через ту же цепочку, что держит
     * live-view. Если камера не закрыла цикл захвата (статус остаётся «занято» дольше
     * заданного таймаута), повторный START_LIVE_STREAM может восстановить цепочку.
     * Это best-effort приём; он не подтверждает целостность записанного файла. Тот же приём, что
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

    /** Best-effort объявление LIVE_VIEW и запуск TCP live-stream; кадр проверяется отдельно. */
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
     * Повторно отправляет объявление LIVE_VIEW и START без предварительного STOP.
     * Используется как best-effort восстановление; фактическое возобновление видеокадров
     * и управления гимбалом этим методом не подтверждается.
     */
    public void restartLiveView() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Сначала best-effort объявление LIVE_VIEW, затем START. STOP не отправляем,
                // чтобы не прерывать уже работающий стрим; результат по кадрам проверяется отдельно.
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
     * Отправляет SET_OPTIONS для photo_sub_mode.
     *
     * SINGLE (0) — обычный кадр, используемый для внешней 24-кадровой сшивки.
     * INSTA_PANO (8) включает отдельный встроенный режим камеры; приложение его не вызывает.
     * Boolean callback означает только, что пришёл ответ, а не что состояние было прочитано
     * обратно и независимо подтверждено.
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
     * Ожидает, пока камера освободится после записи принятого кадра.
     *
     * Ответ TAKE_PICTURE приходит раньше завершения записи файла. Время записи зависит
     * от режима и настроек камеры; перед следующим кадром нужно отдельно дождаться
     * завершения захвата, иначе кадр может быть потерян.
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

    /**
     * Задаёт аппаратный уровень скорости гимбала. Ответ протокола не является чтением
     * фактического уровня; в штатной панораме запрашивается уровень 3.
     */
    public void setGimbalSpeed(final int level, final Callback<Boolean> callback) {
        run(ProtoCodes.SET_PHOTOGRAPHY_OPTIONS, Messages.setGimbalSpeed(level),
                settings.getRequestTimeoutMs(), new ValueReader<Boolean>() {
                    @Override
                    public Boolean read(Ucd2.Frame frame) {
                        return Boolean.TRUE;
                    }
                }, callback);
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

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
