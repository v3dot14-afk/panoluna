package com.panoluna.app.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.panoluna.app.data.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Съёмка 360-панорамы встроенным режимом камеры.
 *
 * В отличие от ранних версий (24 отдельных кадра по serpentine + внешние шаблоны),
 * официальная приложение Insta360 делает так:
 *
 *   1. enterPanoMode: photo_sub_mode = PHOTO_INSTA_PANO — камера переходит в панорамный режим;
 *   2. setPanoAspect: PANO_ASPECT = SPHERE_360 под function_mode NORMAL_POWER_PANO_IMAGE —
 *      камера знает, что нужна 360-сфера;
 *   3. ОДИН TAKE_PICTURE (isInstaPanoEnabled=true) — и камера **сама** ведёт гимбал по
 *      19 позициям, снимает и **stitching выполняет внутри себя**, сохраняя один готовый
 *      360-JPG. Оригиналы 19 кадров не сохраняются — камера их не выдаёт.
 *
 * Приложение не двигает гимбал при этом режиме: гимбал ведёт камера. Задача приложения —
 * выполнить три команды, дождаться результата, скачать готовый снимок и сохранить его.
 *
 * Пауза/стоп кооперативны: они не перебивают камеру посреди её цикла, а отмечают
 * отмену, и сборка завершается в безопасный момент (после шага, перед следующим).
 */
public final class PanoShooter {

    public enum State {
        IDLE, RUNNING, PAUSED, DONE, STOPPED, ERROR
    }

    public interface Listener {
        void onStateChanged(State state);

        void onProgress(int done, int total);

        void onShot(int index, String uri, float pan, float tilt);

        void onError(String message);

        void onLog(String message);
    }

    private final Context context;
    private final Settings settings;
    private final CameraClient camera;
    private final Gimbal gimbal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile State state = State.IDLE;
    private volatile Listener listener;
    private volatile String lastError;

    public PanoShooter(Context context, Settings settings, CameraClient camera, Gimbal gimbal) {
        this.context = context;
        this.settings = settings;
        this.camera = camera;
        this.gimbal = gimbal;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public int getCursor() {
        return state == State.DONE ? 1 : 0;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isBusy() {
        State s = state;
        return s == State.RUNNING || s == State.PAUSED;
    }

    /** Запустить встроенную 360-панораму камеры. */
    public void start() {
        if (isBusy()) {
            return;
        }
        this.lastError = null;
        pauseRequested.set(false);
        stopRequested.set(false);
        setState(State.RUNNING);
        publishProgress(0, 1);
        log("Запуск 360-панорамы (режим камеры)");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runPano();
            }
        });
    }

    /** Пауза: завершить текущий шаг и удовлетворить отмену на следующем. */
    public void pause() {
        if (state == State.RUNNING) {
            pauseRequested.set(true);
            setState(State.PAUSED);
            log("Пауза");
        }
    }

    /** Возобновление после паузы. */
    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        pauseRequested.set(false);
        setState(State.RUNNING);
        log("Продолжение");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runPano();
            }
        });
    }

    /** Стоп: отмечает отмену; камера доделает текущий шаг, снимок не скачивается. */
    public void stop() {
        stopRequested.set(true);
        pauseRequested.set(false);
        if (state == State.RUNNING || state == State.PAUSED) {
            setState(State.STOPPED);
            log("Стоп");
        }
    }

    private void runPano() {
        if (stopRequested.get()) {
            setState(State.STOPPED);
            return;
        }
        // 1. Перевести камеру в панорамный режим.
        if (!enterPanoMode()) {
            log("Не удалось подтвердить панорамный режим: продолжаем");
        }
        if (stopped()) {
            return;
        }
        // 2. Задать 360-сферу.
        if (!setPanoAspect()) {
            log("Не удалось задать 360-аспект: продолжаем");
        }
        if (stopped()) {
            return;
        }
        // 3. Один TAKE_PICTURE — камера делает всю панораму внутри.
        log("Снимок 360-панорамы отправлен");
        String uri = doStep(new Step<String>() {
            @Override
            public void run(CameraClient.Callback<String> cb) {
                camera.takePicturePano(cb);
            }
        }, settings.getRequestTimeoutMs() + 20_000L);
        if (stopped()) {
            return;
        }
        if (uri == null || uri.length() == 0) {
            lastError = "Камера не вернула файл панорамы";
            setState(State.ERROR);
            publishError(lastError);
            return;
        }
        log("Камера приняла панораму: " + uri);
        // 4. Дождаться, пока камера допишет сшитый файл (ответ — когда приняла, не когда записала).
        publishProgress(0, 1);
        awaitCaptureIdle();
        if (stopped()) {
            return;
        }
        // 5. Скачать готовый 360-JPG.
        byte[] data = doStep(new Step<byte[]>() {
            @Override
            public void run(CameraClient.Callback<byte[]> cb) {
                camera.downloadFile(uri, cb);
            }
        }, 40_000L);
        if (stopped()) {
            return;
        }
        if (data == null || data.length == 0) {
            lastError = "Не удалось скачать панораму с камеры";
            setState(State.ERROR);
            publishError(lastError);
            return;
        }
        // 6. Сохранить на устройство.
        String saved = savePano(data);
        if (saved == null) {
            lastError = "Не удалось сохранить панораму на устройстве";
            setState(State.ERROR);
            publishError(lastError);
            return;
        }
        publishProgress(1, 1);
        publishShot(0, saved, 0f, 0f);
        setState(State.DONE);
        log("360-панорама готова: " + saved);
    }

    private boolean stopped() {
        if (stopRequested.get()) {
            setState(State.STOPPED);
            return true;
        }
        return false;
    }

    private boolean enterPanoMode() {
        Boolean ok = doStep(new Step<Boolean>() {
            @Override
            public void run(CameraClient.Callback<Boolean> cb) {
                camera.enterPanoMode(cb);
            }
        }, 10_000L);
        if (Boolean.TRUE.equals(ok)) {
            log("Панорамный режим активирован");
        }
        return Boolean.TRUE.equals(ok);
    }

    private boolean setPanoAspect() {
        Boolean ok = doStep(new Step<Boolean>() {
            @Override
            public void run(CameraClient.Callback<Boolean> cb) {
                camera.setPanoAspect(cb);
            }
        }, 10_000L);
        if (Boolean.TRUE.equals(ok)) {
            log("360-аспект установлен");
        }
        return Boolean.TRUE.equals(ok);
    }

    private void awaitCaptureIdle() {
        Boolean idle = doStep(new Step<Boolean>() {
            @Override
            public void run(CameraClient.Callback<Boolean> cb) {
                camera.awaitCaptureIdle(15_000L, cb);
            }
        }, 18_000L);
        if (!Boolean.TRUE.equals(idle)) {
            log("Камера ещё занята: жду перед скачиванием");
        }
    }

    /** Сохранить скачанный 360-JPG в каталог приложения на устройстве. */
    private String savePano(byte[] data) {
        try {
            File dir = context.getExternalFilesDir("pano360");
            if (dir == null) {
                dir = context.getFilesDir();
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String name = "PanoLuna_360_" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            log("Ошибка сохранения: " + e.getMessage());
            return null;
        }
    }

    /** Запустить один шаг камеры и подождать результата на рабочем потоке. */
    private interface Step<T> {
        void run(CameraClient.Callback<T> cb);
    }

    private <T> T doStep(Step<T> step, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        final boolean[] failed = new boolean[1];
        step.run(new CameraClient.Callback<T>() {
            @Override
            public void onResult(T value) {
                result[0] = value;
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                failed[0] = true;
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failed[0]) {
            return null;
        }
        return (T) result[0];
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setState(final State value) {
        state = value;
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onStateChanged(value);
                }
            }
        });
    }

    private void publishProgress(final int done, final int total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onProgress(done, total);
                }
            }
        });
    }

    private void publishShot(final int index, final String uri, final float pan, final float tilt) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onShot(index, uri, pan, tilt);
                }
            }
        });
    }

    private void publishError(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onError(message);
                }
            }
        });
    }

    private void log(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onLog(message);
                }
            }
        });
    }
}
