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

    /** Гимбал: нужен экранному логу для отладочных строк о векторах. */
    public Gimbal gimbal() {
        return gimbal;
    }

    public void start(final PanoPlan plan) {
        if (isBusy()) {
            return;
        }
        this.plan = plan;
        this.cursor = 0;
        this.shotsDone = 0;
        this.lastError = null;
        pauseRequested.set(false);
        stopRequested.set(false);
        gimbal.resetPosition(0f, 0f);
        setState(State.RUNNING);
        log("Панорама из " + plan.size() + " кадров");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runPlan();
                } catch (Throwable t) {
                    // Сбой в рабочем потоке НИКОГДА не должен быть молчаливым: иначе
                    // панорама просто замирает без единой строки в логе (так и было в
                    // v1.16.11 — лог обрывался на первой точке).
                    lastError = String.valueOf(t);
                    setState(State.ERROR);
                    publishError("Сбой планировщика: " + t);
                    log("СБОЙ ПЛАНИРОВЩИКА: " + t);
                }
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
                try {
                    runPlan();
                } catch (Throwable t) {
                    // Сбой в рабочем потоке НИКОГДА не должен быть молчаливым: иначе
                    // панорама просто замирает без единой строки в логе (так и было в
                    // v1.16.11 — лог обрывался на первой точке).
                    lastError = String.valueOf(t);
                    setState(State.ERROR);
                    publishError("Сбой планировщика: " + t);
                    log("СБОЙ ПЛАНИРОВЩИКА: " + t);
                }
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
        // Фокус на бесконечность: в панораме снимаем далёкие планы, и непрерывный
        // автофокус (AF-C) «дышит» между кадрами — фокус уезжает, кадры не сшиваются.
        // AF-S фиксирует фокус один раз до начала вращения.
        applyFocusForPanorama();
        // Скорость гимбала — photography option 0x55 (Ripwords PROTOCOL-GAP.md). Без явной
        // записи камера остаётся с нулевой/неизвестной скоростью, и векторные команды
        // игнорируются. payload побайтово совпадает с эталоном 08551205aa050210031806.
        log("Устанавливаю скорость гимбала (уровень 3)");
        camera.setGimbalSpeed(3, new CameraClient.Callback<Boolean>() {
            @Override
            public void onResult(Boolean value) {
                log("Скорость гимбала установлена");
            }

            @Override
            public void onError(String message) {
                log("Скорость гимбала не установлена: " + message);
            }
        });
        sleep(400L);
        // Смена режима фото может ронять стрим: оригинал вызывает ensureRunningForCapture()
        // после установки режима. Переустанавливаем стрим перед первым движением.
        log("Проверяю live-stream перед движением");
        camera.announceLiveView();
        // Стрим должен реально идти, прежде чем двигать гимбал: в рабочем probe Ripwords
        // после START ждут ~1.5 с. Камера ack'ает START на всё, но гимбал слушается только
        // при живом стриме. Без паузы первый вектор уходит в пустоту.
        log("Ожидание прогрева live-stream перед движением");
        sleep(1500L);
        // ДЕТЕРМИНИРОВАННАЯ СТАРТОВАЯ ТОЧКА.
        // Камера не отдаёт позицию гимбала, поэтому после прошлой съёмки он стоит
        // где остался, а план считает его за 0 — отсюда «начинает не с того места»
        // и упор в предел с пропуском кадров. BACK_CENTER возвращает к физическому
        // нулю firmware (действие 2, замер apatassini), от него план отсчитывается
        // корректно. Ждём: возврат механический, ему нужно время доехать.
        log("Возвращаю гимбал в физический ноль");
        camera.gimbalAction(ProtoCodes.GimbalAction.BACK_CENTER,
                new CameraClient.Callback<Boolean>() {
                    @Override
                    public void onResult(Boolean value) {
                        log("Гимбал в нуле");
                    }

                    @Override
                    public void onError(String message) {
                        log("Ноль гимбала не подтверждён: " + message);
                    }
                });
        sleep(3500L);
        gimbal.resetPosition(0f, 0f);
        while (cursor < shots.size()) {
            if (stopRequested.get()) {
                setState(State.STOPPED);
                return;
            }
            if (pauseRequested.get()) {
                setState(State.PAUSED);
                return;
            }
            // Проверяем живость сессии перед каждым кадром: на мёртвом сокете гимбал
            // не двигается, снимок не проходит, и раньше мы молча «проходили» все
            // оставшиеся точки, рапортуя успех. Лучше честно остановиться.
            if (!camera.isConnected()) {
                // Камера рвёт TCP во время съёмки (известное поведение Luna — в Ripwords
                // для этого есть auto-reconnect с backoff). Пробуем восстановить сессию
                // и ПРОДОЛЖИТЬ с той же точки, а не терять уже снятые кадры.
                log("Соединение потеряно на кадре " + (cursor + 1) + ": переподключаюсь");
                boolean back = reconnect(shots.size());
                if (!back) {
                    setState(State.ERROR);
                    publishError("Соединение с камерой потеряно на кадре " + (cursor + 1)
                            + " из " + shots.size() + ". Сделано: " + cursor + ".");
                    log("ОСТАНОВ: восстановить соединение не удалось, сделано " + cursor);
                    return;
                }
                log("Соединение восстановлено, продолжаю с кадра " + (cursor + 1));
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
            boolean captured = shoot(shot);
            if (!captured) {
                // Кадр не снят обеими попытками: не выдаём его за снятый. Раньше
                // cursor++ шёл всегда, и итог сообщал «24 кадров», хотя последний
                // кадр был отклонён камерой.
                setState(State.ERROR);
                publishError("Кадр " + (cursor + 1) + " не снят после двух попыток"
                        + (lastError == null ? "" : ": " + lastError));
                return;
            }
            shotsDone++;
            cursor++;
            publishProgress(cursor, shots.size());
        }
        if (stopRequested.get()) {
            setState(State.STOPPED);
        } else if (pauseRequested.get()) {
            setState(State.PAUSED);
        } else {
            setState(State.DONE);
            log("Панорама завершена: " + shotsDone + " из " + shots.size() + " кадров на камере");
        }
    }

    /**
     * Восстанавливает сессию после обрыва: несколько попыток с нарастающей паузой
     * (как auto-reconnect в Ripwords). После успеха заново объявляет live-view —
     * гимбал не станет двигаться на голом соединении после переподключения.
     *
     * @return true, если соединение поднято
     */
    private boolean reconnect(int total) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            if (stopRequested.get()) {
                return false;
            }
            log("Переподключение, попытка " + attempt + " из 4");
            try {
                if (camera.reconnect()) {
                    sleep(800L);
                    camera.announceLiveView();
                    sleep(2500L);
                    return true;
                }
            } catch (Throwable t) {
                log("Попытка " + attempt + " не удалась: " + t);
            }
            sleep(1000L * attempt);
        }
        return false;
    }

    private void moveTo(final PanoPlan.Shot shot) {
        log("Точка " + shot.label() + "  pan " + Math.round(shot.pan) + "  tilt "
                + Math.round(shot.tilt));
        final Object lock = new Object();
        final boolean[] reached = new boolean[1];
        // НЕ объявляем live-view перед каждым движением: 118 на активном стриме сбрасывает
        // его и гимбал замолкает (v1.16.7 — подтверждённая регрессия). Объявление делается
        // ОДИН раз при старте стрима (enterLiveView), как в рабочем probe Ripwords.
        // Поворот гимбала по схеме: GIMBAL_CONTROL, вектор скорости к точке плана.
        final long moveStart = System.currentTimeMillis();
        // ВАЖНО: driveTo блокирующий и вызывает callback ВНУТРИ себя. Если вызвать его
        // прямо здесь, основной поток войдёт в wait() уже ПОСЛЕ notifyAll() — уведомление
        // потеряется, и ждём полный таймаут впустую (наблюдали 39119 мс на первый кадр:
        // 30 с driveTo + 5 с ожидания). Поэтому запускаем в отдельном потоке.
        Thread mover = new Thread(new Runnable() {
            @Override
            public void run() {
                gimbal.driveTo(shot.pan, shot.tilt, 20_000L, camera, new Gimbal.Callback() {
                    @Override
                    public void onDone(boolean value) {
                        reached[0] = value;
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                });
            }
        }, "pano-move");
        mover.start();
        synchronized (lock) {
            try {
                lock.wait(25_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long moveMs = System.currentTimeMillis() - moveStart;
        if (reached[0]) {
            log("Гимбал достиг точки " + shot.label() + " за " + moveMs + " мс");
        } else {
            log("Гимбал НЕ достиг точки " + shot.label() + " за " + moveMs + " мс: фото всё равно");
        }
        // Гимбал должен быть неподвижен в момент съёмки: даём короткий интервал успокоения.
        sleep(350L);
    }

    private boolean shoot(final PanoPlan.Shot shot) {
        // Две попытки: таймаут команды на последнем кадре почти всегда означает, что
        // камера не ответила, а не что кадр не сделан. Повтор дешевле, чем потерять
        // кадр из готовой панорамы: один недостающий кадр — дырка в сфере.
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1) {
                // Перед повтором разблокируем камеру: после таймаута она может
                // остаться в состоянии съёмки, и повтор тоже не ответит.
                log("Кадр " + (cursor + 1) + ": повторная попытка");
                camera.recoverFromStuckCapture();
                sleep(1500L);
            }
            if (shootOnce(shot)) {
                return true;
            }
            if (stopRequested.get()) {
                return false;
            }
        }
        return false;
    }

    /** Одна попытка кадра. true — камера приняла снимок. */
    private boolean shootOnce(final PanoPlan.Shot shot) {
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
            // Состояние ERROR ставит внешний shoot(): здесь ещё есть вторая попытка.
            lastError = error[0];
            log("Снимок " + (cursor + 1) + " отклонён: " + error[0]);
            return false;
        }
        if (uri[0] != null) {
            shot.uri = uri[0];
        }
        publishShot(cursor, shot.uri, shot.pan, shot.tilt);
        if (uri[0] == null) {
            // Ответ есть, но URI пустой: файл всё равно сохранён на камеру.
            log("Снимок " + (cursor + 1) + ": камера не вернула URI (файл на камере)");
        }
        // Камере нужно время записать файл. Без паузы опрос статуса начинается в момент
        // записи и добавляет камере работы — она не справляется и рвёт сессию.
        sleep(500L);
        // Дождаться завершения записи перед движением гимбала к следующей точке.
        // Если камера зависла в SINGLE_SHOOTING (снимок фото проходит через цепочку
        // стрима — при сломленном стриме не закрывается), awaitCaptureIdle не дождётся:
        // перезапускаем стрим, чтобы разблокировать камеру и восстановить стрим,
        // после которого гимбал может двигаться. Как drainCamera() в TimelapseEngine.
        // Ждём, пока камера запишет файл. Снимок 48 МП пишется несколько секунд, и
        // двигать гимбал раньше нельзя: камера занята, вектор игнорируется, а частые
        // опросы конкурируют с записью и рвут сессию (наблюдали обрывы на 13-16 кадрах).
        // Одна длинная проверка вместо цикла с перезапусками стрима.
        final Object idleLock = new Object();
        final boolean[] idle = new boolean[1];
        camera.awaitCaptureIdle(25_000L, new CameraClient.Callback<Boolean>() {
            @Override
            public void onResult(Boolean value) {
                idle[0] = Boolean.TRUE.equals(value);
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
                idleLock.wait(28_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (idle[0]) {
            log("Камера свободна: движение к следующей точке");
        } else {
            // Не дождались за 25 с: всё равно идём дальше, но пауза побольше.
            // Камера зависла в состоянии съёмки дольше 25 с. Это НЕ нормальное
            // поведение: снимок 48 МП пишется максимум ~10 с. Зависание означает, что
            // камера не закрыла цикл съёмки (известная проблема Luna — в эталонах её
            // лечат перезапуском стрима). Пробуем мягко: START_LIVE_STREAM разблокирует
            // цепочку, по которой проходит снимок.
            log("Камера зависла в съёмке дольше 25 с: разблокирую потоком");
            camera.recoverFromStuckCapture();
            sleep(3000L);
            if (camera.isCaptureStuck()) {
                log("Камера всё ещё занята после разблокировки: продолжаю, кадр может быть потерян");
            } else {
                log("Камера разблокирована");
            }
            sleep(settings.getPanoShotDelayMs());
        }
        return true;
    }

    /**
     * Ставит фокус, пригодный для панорамы.
     *
     * Зачем: камера по умолчанию работает в AF-C — непрерывно перефокусируется. Во время
     * панорамы гимбал едет, сцена между кадрами меняется, и AF-C перестраивает фокус
     * на каждом кадре. Кадры получаются с разной резкостью и не сшиваются.
     *
     * Берём AF-S: фокусировка один раз, дальше фокус заперт. Номер типа опции
     * определяет зонд — в прошивке число к имени AF_LENS_INFO зашифровано.
     *
     * Метод ничего не ломает при неудаче: если тип не найден, съёмка идёт как раньше,
     * а в журнал пишется, что именно нашлось.
     */
    private void applyFocusForPanorama() {
        final Object lock = new Object();
        final int[][] found = new int[1][];
        camera.probeFocusOptionType(new CameraClient.Callback<int[]>() {
            @Override
            public void onResult(int[] value) {
                found[0] = value;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(String message) {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                // Зонд ограничен шестью секундами внутри, плюс запас на сеть.
                lock.wait(9_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (found[0] == null || found[0].length == 0) {
            log("Типы опций фокуса не получены: остаётся автофокус камеры");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int t : found[0]) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t);
        }
        log("Неизвестные типы опций (среди них фокус): " + sb);

        // Пробуем запереть фокус по каждому найденному типу. Лишние типы безвредны:
        // камера на неизвестный тип отвечает пустым 200 и ничего не меняет. Какой
        // именно тип оказался фокусом, видно по журналу.
        for (final int type : found[0]) {
            final Object done = new Object();
            final boolean[] accepted = new boolean[1];
            camera.tryLockFocus(type, new CameraClient.Callback<Boolean>() {
                @Override
                public void onResult(Boolean value) {
                    accepted[0] = Boolean.TRUE.equals(value);
                    synchronized (done) {
                        done.notifyAll();
                    }
                }

                @Override
                public void onError(String message) {
                    synchronized (done) {
                        done.notifyAll();
                    }
                }
            });
            synchronized (done) {
                try {
                    done.wait(3_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log("Фокус AF-S по типу " + type + ": " + (accepted[0] ? "принято" : "не принято"));
        }
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

    /** Сколько кадров реально снято: итог не должен выдавать план за результат. */
    private int shotsDone;

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
