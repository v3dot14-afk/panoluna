package com.panoluna.app.camera;

import android.os.Handler;
import android.os.Looper;

import com.panoluna.app.data.Settings;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * La macchina a stati che spara una panoramica.
 *
 * Per ogni punto del piano: porta il gimbal, aspetta che si assesti, scatta, aspetta che la
 * camera abbia finito di scrivere. L'ordine conta: il comando di scatto risponde quando la
 * camera lo accetta, non quando il file e' sulla scheda, e chiedere lo scatto successivo prima
 * della fine della scrittura fa perdere la foto.
 *
 * Pausa e stop sono coopetitivi: non interrompono un comando a meta', lo lasciano finire e
 * si fermano al punto sicuro (fra un scatto e l'altro, con il gimbal fermo).
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

    /** Pausa: il gimbal si ferma appena finisce lo scatto in corso. */
    public void pause() {
        if (state == State.RUNNING) {
            pauseRequested.set(true);
            gimbal.stop(camera);
            setState(State.PAUSED);
            log("Pausa");
        }
    }

    /** Riprende dal punto dove si era fermati. */
    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        pauseRequested.set(false);
        setState(State.RUNNING);
        log("Ripresa");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runPlan();
            }
        });
    }

    /** Stop: ferma il gimbal e lascia il lavoro incompleto, con le foto gia' fatte salvate. */
    public void stop() {
        stopRequested.set(true);
        pauseRequested.set(false);
        gimbal.stop(camera);
        if (state == State.RUNNING || state == State.PAUSED) {
            setState(State.STOPPED);
            log("S_stop");
        }
    }

    private void runPlan() {
        List<PanoPlan.Shot> shots = plan.shots();
        // La camera deve essere in modalità pano prima di scattare: senza
        // photo_sub_mode = PHOTO_INSTA_PANO lo TAKE_PICTURE non produce lo
        // scatto panoramico. Si tenta di attivarla; se la camera не отвечает
        // вовремя, это не повод сбрасывать съемку: возможно, режим уже активен
        // или команда ляжет позже. Логируем и продолжаем.
        if (!enterPanoMode()) {
            log("Не удалось подтвердить панорамный режим: съемка продолжается");
        } else {
            log("Панорамный режим подтверждён");
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
            log("Панорамма завершена: " + shots.size() + " кадров");
        }
    }

    private void moveTo(final PanoPlan.Shot shot) {
        log("Punto " + shot.label() + "  pan " + Math.round(shot.pan) + "  tilt "
                + Math.round(shot.tilt));
        final Object lock = new Object();
        final boolean[] reached = new boolean[1];
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
        // Il gimbal deve essere fermo durante la posa: si lascia un istante di assestamento.
        sleep(350L);
    }

    private void shoot(final PanoPlan.Shot shot) {
        final Object lock = new Object();
        final String[] uri = new String[1];
        final String[] error = new String[1];
        camera.takePicturePano(new CameraClient.Callback<String>() {
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
        // Aspetta la fine della scrittura prima di muovere il gimbal.
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

    /** Chiama enterPanoMode e attende la conferma, con timeout. */
    private boolean enterPanoMode() {
        final Object lock = new Object();
        final boolean[] ok = new boolean[1];
        camera.enterPanoMode(new CameraClient.Callback<Boolean>() {
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
            log("Панорамный режим активирован");
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
