package com.panoluna.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.camera.CameraClient;
import com.panoluna.app.proto.ProtoCodes;

/**
 * Parametri della fotocamera e dello scatto.
 *
 * Le modifiche si applicano alla camera solo premendo "Applica": cosi' non si cambia lo stato
 * della camera mentre si spara, e il flusso delle 24 foto non subisce i ritardi delle scritture.
 */
public final class CameraSettingsActivity extends Activity {

    private PanoLunaApp app;
    private final SettingsHolder holder = new SettingsHolder();

    private Button sizeStandard;
    private Button sizeUltra;
    private Button zoom1;
    private Button zoom2;
    private Button zoom3;
    private Button zoom6;
    private Button zoom12;
    private Button modeAuto;
    private Button modePro;
    private Button gspeed1;
    private Button gspeed2;
    private Button gspeed3;
    private EditText iso;
    private EditText shutter;
    private EditText ev;
    private EditText wb;
    private EditText brightness;
    private CheckBox raw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = PanoLunaApp.get(this);
        app.applyTheme(this);
        setContentView(R.layout.activity_camera_settings);

        sizeStandard = findViewById(R.id.size_standard);
        sizeUltra = findViewById(R.id.size_ultra);
        zoom1 = findViewById(R.id.zoom_1);
        zoom2 = findViewById(R.id.zoom_2);
        zoom3 = findViewById(R.id.zoom_3);
        zoom6 = findViewById(R.id.zoom_6);
        zoom12 = findViewById(R.id.zoom_12);
        modeAuto = findViewById(R.id.mode_auto);
        modePro = findViewById(R.id.mode_pro);
        gspeed1 = findViewById(R.id.gspeed_1);
        gspeed2 = findViewById(R.id.gspeed_2);
        gspeed3 = findViewById(R.id.gspeed_3);
        iso = findViewById(R.id.cam_iso);
        shutter = findViewById(R.id.cam_shutter);
        ev = findViewById(R.id.cam_ev);
        wb = findViewById(R.id.cam_wb);
        brightness = findViewById(R.id.cam_brightness);
        raw = findViewById(R.id.cam_raw);

        holder.photoSize = app.settings().getPhotoSize();
        holder.zoom = app.settings().getZoom();
        holder.pro = app.settings().isProMode();
        holder.gimbalSpeed = app.settings().getGimbalSpeedLevel();

        iso.setText(String.valueOf(app.settings().getIso()));
        shutter.setText(String.valueOf(app.settings().getShutterUs()));
        ev.setText(String.valueOf(app.settings().getEvThirds()));
        wb.setText(String.valueOf(app.settings().getWhiteBalanceKelvin()));
        brightness.setText(String.valueOf(app.settings().getBrightness()));
        raw.setChecked(app.settings().getRawCapture() == ProtoCodes.RawCaptureType.DNG);

        sizeStandard.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.photoSize = ProtoCodes.PhotoSize.STANDARD_12MP;
                paint();
            }
        }));
        sizeUltra.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.photoSize = ProtoCodes.PhotoSize.ULTRA_48MP;
                paint();
            }
        }));
        zoom1.setOnClickListener(zoomChoice(1));
        zoom2.setOnClickListener(zoomChoice(2));
        zoom3.setOnClickListener(zoomChoice(3));
        zoom6.setOnClickListener(zoomChoice(6));
        zoom12.setOnClickListener(zoomChoice(12));
        modeAuto.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.pro = false;
                paint();
            }
        }));
        modePro.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.pro = true;
                paint();
            }
        }));
        gspeed1.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.gimbalSpeed = 1;
                paint();
            }
        }));
        gspeed2.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.gimbalSpeed = 2;
                paint();
            }
        }));
        gspeed3.setOnClickListener(choice(new Runnable() {
            @Override
            public void run() {
                holder.gimbalSpeed = 3;
                paint();
            }
        }));

        paint();

        findViewById(R.id.cam_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply();
            }
        });
    }

    private View.OnClickListener choice(final Runnable action) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        };
    }

    private View.OnClickListener zoomChoice(final int value) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder.zoom = value;
                paint();
            }
        };
    }

    private void paint() {
        int size = holder.photoSize;
        sizeStandard.setBackgroundResource(size == ProtoCodes.PhotoSize.STANDARD_12MP
                ? R.drawable.bg_round_accent : R.drawable.bg_round);
        sizeUltra.setBackgroundResource(size == ProtoCodes.PhotoSize.ULTRA_48MP
                ? R.drawable.bg_round_accent : R.drawable.bg_round);
        sizeStandard.setTextColor(color(size == ProtoCodes.PhotoSize.STANDARD_12MP
                ? R.color.background : R.color.text));
        sizeUltra.setTextColor(color(size == ProtoCodes.PhotoSize.ULTRA_48MP
                ? R.color.background : R.color.text));

        paintZoom(zoom1, 1);
        paintZoom(zoom2, 2);
        paintZoom(zoom3, 3);
        paintZoom(zoom6, 6);
        paintZoom(zoom12, 12);

        modeAuto.setBackgroundResource(holder.pro
                ? R.drawable.bg_round : R.drawable.bg_round_accent);
        modeAuto.setTextColor(color(holder.pro ? R.color.text : R.color.background));
        modePro.setBackgroundResource(holder.pro
                ? R.drawable.bg_round_accent : R.drawable.bg_round);
        modePro.setTextColor(color(holder.pro ? R.color.background : R.color.text));

        paintSpeed(gspeed1, 1);
        paintSpeed(gspeed2, 2);
        paintSpeed(gspeed3, 3);
    }

    private int color(int res) {
        return getResources().getColor(res);
    }

    private void paintZoom(Button button, int value) {
        boolean selected = holder.zoom == value;
        button.setBackgroundResource(selected ? R.drawable.bg_round_accent : R.drawable.bg_round);
        button.setTextColor(color(selected ? R.color.background : R.color.text));
    }

    private void paintSpeed(Button button, int value) {
        boolean selected = holder.gimbalSpeed == value;
        button.setBackgroundResource(selected ? R.drawable.bg_round_accent : R.drawable.bg_round);
        button.setTextColor(color(selected ? R.color.background : R.color.text));
    }

    private void apply() {
        int size = holder.photoSize;
        int zoom = holder.zoom;
        boolean pro = holder.pro;
        int speed = holder.gimbalSpeed;

        app.settings().setPhotoSize(size);
        app.settings().setZoom(zoom);
        app.settings().setProMode(pro);
        app.settings().setGimbalSpeedLevel(speed);

        int isoValue = parseInt(iso.getText().toString(), 0);
        long shutterValue = parseLong(shutter.getText().toString(), 0L);
        int evValue = parseInt(ev.getText().toString(), 0);
        int wbValue = parseInt(wb.getText().toString(), 0);
        int brightnessValue = parseInt(brightness.getText().toString(), 0);

        app.settings().setIso(isoValue);
        app.settings().setShutterUs(shutterValue);
        app.settings().setEvThirds(evValue);
        app.settings().setWhiteBalanceKelvin(wbValue);
        app.settings().setBrightness(brightnessValue);
        app.settings().setRawCapture(raw.isChecked()
                ? ProtoCodes.RawCaptureType.DNG : ProtoCodes.RawCaptureType.OFF);

        if (!app.isCameraConnected()) {
            Toast.makeText(this, R.string.connect_state_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        app.camera().applyPhotoSettings(new CameraClient.Callback<Boolean>() {
            @Override
            public void onResult(Boolean value) {
                Toast.makeText(CameraSettingsActivity.this, R.string.cam_applied,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(CameraSettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
        app.camera().setGimbalSpeed(speed, new CameraClient.Callback<Boolean>() {
            @Override
            public void onResult(Boolean value) {
                // gia' segnalato dal apply
            }

            @Override
            public void onError(String message) {
                // la velocita hardware e' un extra: non blocca il lavoro
            }
        });
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class SettingsHolder {
        int photoSize;
        int zoom;
        boolean pro;
        int gimbalSpeed;
    }
}
