package com.panoluna.app.camera;

import android.os.Handler;
import android.os.Looper;

import com.panoluna.app.data.Settings;
import com.panoluna.app.proto.ProtoCodes;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Съёмка панорамы из 24 кадров для сшивки на компьютере.
 *
 * По третьему пункту задания: панорама — это **24 отдельных полных кадра**, которые
 * позже сшиваются на компьютере (PTGui/papywizard/pano2vr). Приложение не делает
 * встроенную сшивку: это был режим камеры, который мы отвернули.
 *
 * Как управляет официальное приложение Insta360 (изучено, подтверждено референсом
 * apatassini/Insta360luna):
 *
 * - **Режим фото**: для отдельных кадров ставится photo_sub_mode = PHOTO_SINGLE (0)
 *   (обычный одиночный кадр). Встроенный панорамный режим INSTA_PANO (8) — это для
 *   19-кадровой встроенной сшивки камеры, он нам не нужен.
 * - **Параметры фото**: SET_PHOTOGRAPHY_OPTIONS (разрешение, выдержка, баланс белого…)
 *   настраиваются до съёмки (applyPhotoSettings).
 * - **Поворот гимбала**: GIMBAL_CONTROL — непрерывный вектор скорости (driveTo), гимбал
 *   движется к каждой точке плана, и когда он на месте — обычный TAKE_PICTURE.
 *
 * Порядок важен: TAKE_PICTURE отвечает, когда камера **приняла** кадр, а не когда записала
 * файл. Сделать следующий фото раньше записи предыдущего — значит потерять кадр, поэтому
 * после каждого тикается ожидание завершения захвата (awaitCaptureIdle) перед следующей
 * точкой. Снимки сохраняются **на камеру**.
 *
 * Пауза/стоп кооперативны: они не перебивают команду посредине, а завершают захват в
 * безопасный момент (между кадрами, при неподвижном гимбале).
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

    private final Settings settings;
    private final CameraClient camera;
    private final Gimbal gimbal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile State state = State.IDLE;
    private volatile Listener listener;
    private volatile PanoPlan plan;
    private volatile int cursor;
    private volatile String lastError;

    public PanoShooter(Settings settings, CameraClient camera, Gimbal gimbal) {
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

    public PanoPlan getPlan() {
        return plan;
    }

    public int getCursor() {
        return cursor;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isBusy() {
        State s = state;
        return s == State.RUNNING || s == State.PAUSED;
    }

    public void start(final PanoPlan plan) {
        if (isBusy()) {
            return;
        }
        this.plan = plan;
        this.cursor = 0;
        this.lastError = null;
        pauseRequested.set(false);
        stopRequested.set(false);
        gimbal.resetPosition(0f, 0f);
        setState(State.RUNNING);
        log("Панорамма из " + plan.size() + " кадров");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runPlan();
            }
        });
    }

    /** Пауза: гимбал останавливается сразу, съёмка возобновляется после кадра. */
    public void pause() {
        if (state == State.RUNNING) {
            pauseRequested.set(true);
            gimbal.stop(camera);
            setState(State.PAUSED);
            log("Пауза");
        }
    }

    /** Продолжение с того места, где остановились. */
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
                runPlan();
            }
        });
    }

    /** Стоп: останавливает гимбал и оставляет уже сделанные кадры на камере. */
    public void stop() {
        stopRequested.set(true);
        pauseRequested.set(false);
        gimbal.stop(camera);
        if (state == State.RUNNING || state == State.PAUSED) {
            setState(State.STOPPED);
            log("Стоп");
        }
    }

    private void runPlan() {
        List<PanoPlan.Shot> shots = plan.shots();
        // Режим фото: обычный одиночный кадр. Встроенный INSTA_PANO нужен только для
        // 19-кадровой встроенной сшивки камеры — для 24 кадров на компьютер он не нужен.
        if (!enterSingleMode()) {
            log("Не удалось подтвердить режим фото: съёмка продолжается");
        } else {
            log("Режим фото активирован");
        }
        while (cursor < shots.size()) {
            if (stopRequested.get()) {
                setState(State.STOPPED);
                return;
            }
            if (pauseRequested.get()) {
                setState(State.PAUSED);
                return;
            }
            final PanoPlan.Shot shot = shots.get(cursor);
            moveTo(shot);
            if (stopRequested.get()) {
                setState(State.STOPPED);
                return;
            }
            if (pauseRequested.get()) {
                setState(State.PAUSED);
                return;
            }
            shoot(shot);
            cursor++;
            publishProgress(cursor, shots.size());
        }
        if (stopRequested.get()) {
            setState(State.STOPPED);
        } else if (pauseRequested.get()) {
            setState(State.PAUSED);
        } else {
            setState(State.DONE);
            log("Панорамма завершена: " + shots.size() + " кадров на камере");
        }
    }

    private void moveTo(final PanoPlan.Shot shot) {
        log("Точка " + shot.label() + "  pan " + Math.round(shot.pan) + "  tilt "
                + Math.round(shot.tilt));
        final Object lock = new Object();
        final boolean[] reached = new boolean[1];
        // Поворот гимбала по схеме: GIMBAL_CONTROL, вектор скорости к точке плана.
        gimbal.driveTo(shot.pan, shot.tilt, 30_000L, camera, new Gimbal.Callback() {
            @Override
            public void onDone(boolean value) {
                reached[0] = value;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(35_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!reached[0]) {
            log("Гимбал не достиг точки: фото делается всё равно");
        }
        // Гимбал должен быть неподвижен в момент съёмки: даём короткий интервал успокоения.
        sleep(350L);
    }

    private void shoot(final PanoPlan.Shot shot) {
        final Object lock = new Object();
        final String[] uri = new String[1];
        final String[] error = new String[1];
        // Обычный одиночный кадр: без isInstaPanoEnabled. Файл сохраняется на камеру.
        camera.takePicture(new CameraClient.Callback<String>() {
            @Override
            public void onResult(String value) {
                uri[0] = value;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(String message) {
                error[0] = message;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(20_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (error[0] != null) {
            lastError = error[0];
            setState(State.ERROR);
            publishError("Снимок " + (cursor + 1) + " отклонён: " + error[0]);
            return;
        }
        if (uri[0] != null) {
            shot.uri = uri[0];
        }
        publishShot(cursor, shot.uri, shot.pan, shot.tilt);
        // Дождаться завершения записи перед движением гимбала к следующей точке.
        final Object idleLock = new Object();
        final boolean[] idle = new boolean[1];
        camera.awaitCaptureIdle(settings.getPanoShotDelayMs() + 4000L,
                new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onResult(Boolean value) {
                        idle[0] = value;
                        synchronized (idleLock) {
                            idleLock.notifyAll();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        idle[0] = false;
                        synchronized (idleLock) {
                            idleLock.notifyAll();
                        }
                    }
                });
        synchronized (idleLock) {
            try {
                idleLock.wait(settings.getPanoShotDelayMs() + 5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!idle[0]) {
            log("Камера ещё занята: жду перед следующей точкой");
        }
        sleep(settings.getPanoShotDelayMs());
    }

    /** Установить режим фото (photo_sub_mode = SINGLE) и дождатьсяConfirmation, с таймаутом. */
    private boolean enterSingleMode() {
        final Object lock = new Object();
        final boolean[] ok = new boolean[1];
        camera.setPhotoSubMode(ProtoCodes.PhotoSubMode.SINGLE, new CameraClient.Callback<Boolean>() {
            @Override
            public void onResult(Boolean value) {
                ok[0] = Boolean.TRUE.equals(value);
                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(String message) {
                ok[0] = false;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ok[0]) {
            log("Режим фото активирован");
        }
        return ok[0];
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
