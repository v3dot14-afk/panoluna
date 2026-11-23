package com.panoluna.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.panoluna.app.proto.ProtoCodes;

/**
 * Impostazioni dell'app, persistite in SharedPreferences.
 *
 * La password del Wi-Fi della camera viene ricordata qui: e' quello che permette di connettersi
 * senza reinserirla a ogni volta. In Impostazioni c'e l'interruttore per cancellarla.
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

    private static final String KEY_PHOTO_SIZE = "photo_size";
    private static final String KEY_ZOOM = "zoom_scale";
    private static final String KEY_ISO = "iso";
    private static final String KEY_SHUTTER = "shutter_us";
    private static final String KEY_EV_THIRDS = "ev_thirds";
    private static final String KEY_WB_KELVIN = "wb_kelvin";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_RAW = "raw_capture";
    private static final String KEY_PRO_MODE = "pro_mode";

    private static final String KEY_PANO_ROWS = "pano_rows";
    private static final String KEY_PANO_COLUMNS = "pano_columns";
    private static final String KEY_PANO_OVERLAP = "pano_overlap";
    private static final String KEY_PANO_TILT_TOP = "pano_tilt_top";
    private static final String KEY_PANO_TILT_BOTTOM = "pano_tilt_bottom";
    private static final String KEY_PANO_DELAY = "pano_shot_delay_ms";

    private static final String KEY_USE_MJPEG = "use_mjpeg_preview";

    private static final String KEY_THEME = "theme_mode";

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

    // ---- Rete ----

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

    // ---- Gimbal ----

    public int getGimbalSpeedLevel() {
        return prefs.getInt(KEY_GIMBAL_SPEED, 3);
    }

    public void setGimbalSpeedLevel(int value) {
        prefs.edit().putInt(KEY_GIMBAL_SPEED, value).apply();
    }

    /** Fattore misurato su Luna Ultra: il gimbal si muove ~1,235 volte quanto comandato. */
    public float getPanScale() {
        return prefs.getFloat(KEY_PAN_SCALE, 1.235f);
    }

    public float getTiltScale() {
        return prefs.getFloat(KEY_TILT_SCALE, 1.235f);
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

    // ---- Fotografia ----

    public int getPhotoSize() {
        return prefs.getInt(KEY_PHOTO_SIZE, ProtoCodes.PhotoSize.ULTRA_48MP);
    }

    public void setPhotoSize(int value) {
        prefs.edit().putInt(KEY_PHOTO_SIZE, value).apply();
    }

    public int getZoom() {
        return prefs.getInt(KEY_ZOOM, 1);
    }

    public void setZoom(int value) {
        prefs.edit().putInt(KEY_ZOOM, value).apply();
    }

    public int getIso() {
        return prefs.getInt(KEY_ISO, 0);
    }

    public void setIso(int value) {
        prefs.edit().putInt(KEY_ISO, value).apply();
    }

    /** Otturatore in microsecondi: 0 = automatico. */
    public long getShutterUs() {
        return prefs.getLong(KEY_SHUTTER, 0L);
    }

    public void setShutterUs(long value) {
        prefs.edit().putLong(KEY_SHUTTER, value).apply();
    }

    public int getEvThirds() {
        return prefs.getInt(KEY_EV_THIRDS, 0);
    }

    public void setEvThirds(int value) {
        prefs.edit().putInt(KEY_EV_THIRDS, value).apply();
    }

    public int getWhiteBalanceKelvin() {
        return prefs.getInt(KEY_WB_KELVIN, 0);
    }

    public void setWhiteBalanceKelvin(int value) {
        prefs.edit().putInt(KEY_WB_KELVIN, value).apply();
    }

    public int getBrightness() {
        return prefs.getInt(KEY_BRIGHTNESS, 0);
    }

    public void setBrightness(int value) {
        prefs.edit().putInt(KEY_BRIGHTNESS, value).apply();
    }

    public int getRawCapture() {
        return prefs.getInt(KEY_RAW, ProtoCodes.RawCaptureType.OFF);
    }

    public void setRawCapture(int value) {
        prefs.edit().putInt(KEY_RAW, value).apply();
    }

    public boolean isProMode() {
        return prefs.getBoolean(KEY_PRO_MODE, false);
    }

    public void setProMode(boolean value) {
        prefs.edit().putBoolean(KEY_PRO_MODE, value).apply();
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

    public long getPanoShotDelayMs() {
        return prefs.getLong(KEY_PANO_DELAY, 6000L);
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

    /** true, если должна быть тёмная тема. */
    public boolean darkTheme() {
        int mode = themeMode();
        return mode == THEME_DARK || mode == THEME_SYSTEM;
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME, mode).apply();
    }

    /** Cancella tutto: credenziaли Wi-Fi, настройки, работы. Используется из Настроек. */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
