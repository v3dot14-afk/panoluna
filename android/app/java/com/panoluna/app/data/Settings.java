package com.panoluna.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения в локальном {@link SharedPreferences}.
 *
 * Здесь хранятся адрес камеры, параметры оценки движения гимбала, тема и язык.
 * Пароль Wi-Fi и настройки качества фотографий приложение не сохраняет. clearAll()
 * очищает только эти локальные предпочтения и не отправляет команд камере.
 */
public final class Settings {

    private static final String PREFS = "panoluna";

    private static final String KEY_HOST = "camera_host";
    private static final String KEY_PORT = "camera_port";
    private static final String KEY_KEEP_ALIVE = "keep_alive_seconds";
    private static final String KEY_TIMEOUT = "request_timeout_ms";

    private static final String KEY_GIMBAL_SPEED = "gimbal_speed";
    private static final String KEY_PAN_SCALE = "pan_angular_scale";
    private static final String KEY_TILT_SCALE = "tilt_angular_scale";
    private static final String KEY_PAN_MIN = "pan_min_deg";
    private static final String KEY_PAN_MAX = "pan_max_deg";
    private static final String KEY_TILT_MIN = "tilt_min_deg";
    private static final String KEY_TILT_MAX = "tilt_max_deg";
    private static final String KEY_INVERT_PAN = "invert_pan";
    private static final String KEY_INVERT_TILT = "invert_tilt";

    private static final String KEY_PANO_ROWS = "pano_rows";
    private static final String KEY_PANO_COLUMNS = "pano_columns";
    private static final String KEY_PANO_OVERLAP = "pano_overlap";
    private static final String KEY_PANO_TILT_TOP = "pano_tilt_top";
    private static final String KEY_PANO_TILT_BOTTOM = "pano_tilt_bottom";
    private static final String KEY_PANO_DELAY = "pano_shot_delay_ms";

    private static final String KEY_USE_MJPEG = "use_mjpeg_preview";

    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LANGUAGE = "language";

    /** Темы. */
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_SYSTEM = 2;

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    // ---- Сеть ----

    public String getHost() {
        return prefs.getString(KEY_HOST, "192.168.42.1");
    }

    public void setHost(String value) {
        prefs.edit().putString(KEY_HOST, value == null ? "" : value.trim()).apply();
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, 6666);
    }

    public void setPort(int value) {
        prefs.edit().putInt(KEY_PORT, value).apply();
    }

    public int getKeepAliveSeconds() {
        return prefs.getInt(KEY_KEEP_ALIVE, 3);
    }

    public long getRequestTimeoutMs() {
        return prefs.getLong(KEY_TIMEOUT, 6000L);
    }

    public void setRequestTimeoutMs(long value) {
        prefs.edit().putLong(KEY_TIMEOUT, value).apply();
    }

    // ---- Гимбал ----

    public int getGimbalSpeedLevel() {
        return prefs.getInt(KEY_GIMBAL_SPEED, 3);
    }

    public void setGimbalSpeedLevel(int value) {
        prefs.edit().putInt(KEY_GIMBAL_SPEED, value).apply();
    }

    /** Измеренный коэффициент для Luna Ultra: гимбал проходит примерно в 1,235 раза больше заданного. */
    public float getPanScale() {
        // 1.0: базовая скорость взята из замера на реальной Luna Ultra (57 °/с при команде 90).
        return prefs.getFloat(KEY_PAN_SCALE, 1f);
    }

    public float getTiltScale() {
        return prefs.getFloat(KEY_TILT_SCALE, 1f);
    }

    public void setAngularScale(float pan, float tilt) {
        prefs.edit().putFloat(KEY_PAN_SCALE, pan).putFloat(KEY_TILT_SCALE, tilt).apply();
    }

    public float getPanMin() {
        return prefs.getFloat(KEY_PAN_MIN, -57f);
    }

    public float getPanMax() {
        return prefs.getFloat(KEY_PAN_MAX, 235f);
    }

    public float getTiltMin() {
        return prefs.getFloat(KEY_TILT_MIN, -57f);
    }

    public float getTiltMax() {
        return prefs.getFloat(KEY_TILT_MAX, 120f);
    }

    public void setPanLimits(float min, float max) {
        prefs.edit().putFloat(KEY_PAN_MIN, min).putFloat(KEY_PAN_MAX, max).apply();
    }

    public void setTiltLimits(float min, float max) {
        prefs.edit().putFloat(KEY_TILT_MIN, min).putFloat(KEY_TILT_MAX, max).apply();
    }

    public boolean isInvertPan() {
        return prefs.getBoolean(KEY_INVERT_PAN, false);
    }

    public boolean isInvertTilt() {
        return prefs.getBoolean(KEY_INVERT_TILT, false);
    }

    // ---- Panoramica ----

    public int getPanoRows() {
        return prefs.getInt(KEY_PANO_ROWS, 4);
    }

    public void setPanoRows(int value) {
        prefs.edit().putInt(KEY_PANO_ROWS, value).apply();
    }

    public int getPanoColumns() {
        return prefs.getInt(KEY_PANO_COLUMNS, 6);
    }

    public void setPanoColumns(int value) {
        prefs.edit().putInt(KEY_PANO_COLUMNS, value).apply();
    }

    public int getPanoOverlap() {
        return prefs.getInt(KEY_PANO_OVERLAP, 30);
    }

    public void setPanoOverlap(int value) {
        prefs.edit().putInt(KEY_PANO_OVERLAP, value).apply();
    }

    public float getPanoTiltTop() {
        return prefs.getFloat(KEY_PANO_TILT_TOP, 52f);
    }

    public float getPanoTiltBottom() {
        return prefs.getFloat(KEY_PANO_TILT_BOTTOM, -52f);
    }

    public void setPanoTilts(float top, float bottom) {
        prefs.edit().putFloat(KEY_PANO_TILT_TOP, top).putFloat(KEY_PANO_TILT_BOTTOM, bottom).apply();
    }

    /**
     * Пауза после снимка. Снимок по умолчанию 48 МП (8000x6000), запись на карту
     * занимает несколько секунд. Меньшая пауза приводила к «камера ещё занята» и
     * обрывам сессии на середине панорамы (наблюдали на кадрах 13-16).
     */
    public long getPanoShotDelayMs() {
        return prefs.getLong(KEY_PANO_DELAY, 8000L);
    }

    public void setPanoShotDelayMs(long value) {
        prefs.edit().putLong(KEY_PANO_DELAY, value).apply();
    }

    // ---- Anteprima ----

    public boolean useMjpegPreview() {
        return prefs.getBoolean(KEY_USE_MJPEG, true);
    }

    public void setUseMjpegPreview(boolean value) {
        prefs.edit().putBoolean(KEY_USE_MJPEG, value).apply();
    }

    // ---- Тема ----

    /** 0 = тёмная, 1 = светлая, 2 = как в системе. */
    public int themeMode() {
        return prefs.getInt(KEY_THEME, THEME_DARK);
    }

    /**
     * true, если сейчас должна быть тёмная тема.
     *
     * Для THEME_SYSTEM раньше безусловно возвращал true — «как в системе» фактически означало
     * «всегда тёмная». Теперь системный режим читается из ресурсов (values-night сам
     * подставляет нужный bool).
     */
    public boolean darkTheme() {
        return themeMode() == THEME_DARK;
    }

    /**
     * Язык интерфейса: 0 как в системе, 1 английский, 2 русский.
     *
     * Хранится отдельно от системной локали: приложение не меняет язык телефона,
     * а подставляет свой через LocaleList, поэтому остальные приложения остаются
     * на языке пользователя.
     */
    public static final int LANGUAGE_SYSTEM = 0;
    public static final int LANGUAGE_EN = 1;
    public static final int LANGUAGE_RU = 2;

    public int languageMode() {
        return prefs.getInt(KEY_LANGUAGE, LANGUAGE_SYSTEM);
    }

    public void setLanguageMode(int mode) {
        prefs.edit().putInt(KEY_LANGUAGE, mode).apply();
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME, mode).apply();
    }

    /** Cancella tutto: credenziaли Wi-Fi, настройки, работы. Используется из Настроек. */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
