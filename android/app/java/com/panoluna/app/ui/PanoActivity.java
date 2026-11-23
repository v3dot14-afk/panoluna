package com.panoluna.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.camera.CameraClient;
import com.panoluna.app.camera.PanoPlan;
import com.panoluna.app.camera.PanoShooter;
import com.panoluna.app.media.CameraDownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Il pannello della panoramica: il piano degli scatti, l'avvio, il download delle foto sul
 * telefono e l'esportazione del template PTGui.
 */
public final class PanoActivity extends Activity {

    private PanoLunaApp app;
    private EditText rows;
    private EditText columns;
    private EditText overlap;
    private EditText delay;
    private EditText tiltBottom;
    private EditText tiltTop;
    private TextView total;
    private ProgressBar downloadProgress;

    private PanoPlan currentPlan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pano);
        app = PanoLunaApp.get(this);

        rows = findViewById(R.id.pano_rows);
        columns = findViewById(R.id.pano_columns);
        overlap = findViewById(R.id.pano_overlap);
        delay = findViewById(R.id.pano_delay);
        tiltBottom = findViewById(R.id.pano_tilt_bottom);
        tiltTop = findViewById(R.id.pano_tilt_top);
        total = findViewById(R.id.pano_total);
        downloadProgress = findViewById(R.id.pano_download_progress);

        rows.setText(String.valueOf(app.settings().getPanoRows()));
        columns.setText(String.valueOf(app.settings().getPanoColumns()));
        overlap.setText(String.valueOf(app.settings().getPanoOverlap()));
        delay.setText(String.valueOf(app.settings().getPanoShotDelayMs()));
        tiltBottom.setText(String.valueOf(app.settings().getPanoTiltBottom()));
        tiltTop.setText(String.valueOf(app.settings().getPanoTiltTop()));

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshPlan();
            }
        };
        rows.addTextChangedListener(watcher);
        columns.addTextChangedListener(watcher);
        overlap.addTextChangedListener(watcher);
        tiltBottom.addTextChangedListener(watcher);
        tiltTop.addTextChangedListener(watcher);

        preset(R.id.preset_24, 4, 6);
        preset(R.id.preset_18, 3, 6);
        preset(R.id.preset_12, 3, 4);
        preset(R.id.preset_36, 6, 6);

        findViewById(R.id.pano_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                app.shooter().start(currentPlan);
                finish();
            }
        });
        findViewById(R.id.pano_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                download();
            }
        });
        findViewById(R.id.pano_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportTemplate();
            }
        });
        findViewById(R.id.pano_export_csv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportCsv();
            }
        });
        findViewById(R.id.pano_export_job).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportJob();
            }
        });

        refreshPlan();
    }

    private void preset(int id, final int rowCount, final int columnCount) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rows.setText(String.valueOf(rowCount));
                columns.setText(String.valueOf(columnCount));
                refreshPlan();
            }
        });
    }

    private void refreshPlan() {
        currentPlan = buildPlan();
        if (currentPlan != null) {
            total.setText(getString(R.string.pano_total, currentPlan.size()));
        } else {
            total.setText("");
        }
    }

    private PanoPlan buildPlan() {
        try {
            int r = Integer.parseInt(rows.getText().toString().trim());
            int c = Integer.parseInt(columns.getText().toString().trim());
            int o = Integer.parseInt(overlap.getText().toString().trim());
            float bottom = Float.parseFloat(tiltBottom.getText().toString().trim());
            float top = Float.parseFloat(tiltTop.getText().toString().trim());
            return new PanoPlan(r, c, bottom, top, o);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void save() {
        try {
            app.settings().setPanoRows(Integer.parseInt(rows.getText().toString().trim()));
            app.settings().setPanoColumns(Integer.parseInt(columns.getText().toString().trim()));
            app.settings().setPanoOverlap(Integer.parseInt(overlap.getText().toString().trim()));
            app.settings().setPanoShotDelayMs(Long.parseLong(delay.getText().toString().trim()));
            app.settings().setPanoTilts(Float.parseFloat(tiltTop.getText().toString().trim()),
                    Float.parseFloat(tiltBottom.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            // i campi non validi non si salvano
        }
    }

    private void download() {
        save();
        final List<String> paths = new ArrayList<String>();
        PanoPlan shot = app.shooter().getPlan();
        if (shot != null) {
            for (PanoPlan.Shot s : shot.shots()) {
                if (s.uri != null) {
                    paths.add(s.uri);
                }
            }
        }
        if (paths.isEmpty()) {
            Toast.makeText(this, R.string.gallery_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final String job = "Pano_" + System.currentTimeMillis();
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setMax(paths.size());
        downloadProgress.setProgress(0);
        app.downloader().downloadAll(this, paths, app.settings().getHost(),
                app.getWifiNetwork(), job, new CameraDownloader.Progress() {
                    @Override
                    public void onProgress(int done, int total, String currentName) {
                        downloadProgress.setMax(total);
                        downloadProgress.setProgress(done);
                    }

                    @Override
                    public void onDone(int ok, int failed) {
                        downloadProgress.setVisibility(View.GONE);
                        Toast.makeText(PanoActivity.this,
                                ok + " фото сохранено в " + job + (failed > 0 ? " (не удалось " + failed + ")" : ""),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        downloadProgress.setVisibility(View.GONE);
                        Toast.makeText(PanoActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void exportTemplate() {
        save();
        PanoPlan plan = currentPlan == null ? PanoPlan.shot24() : currentPlan;
        File dir = new File(getExternalFilesDir(null), "templates");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "PanoLuna_" + plan.size() + "scatti.pts");
        if (write(file, plan.toPtGuiTemplate("PanoLuna"))) {
            Toast.makeText(this, getString(R.string.pano_export_ok, file.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Запись не удалась", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportCsv() {
        save();
        PanoPlan plan = currentPlan == null ? PanoPlan.shot24() : currentPlan;
        File dir = new File(getExternalFilesDir(null), "templates");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "PanoLuna_angoli.csv");
        if (write(file, plan.toCsv())) {
            Toast.makeText(this, getString(R.string.pano_export_ok, file.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Запись не удалась", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Il lavoro completo in una cartella: PTGui, Papywizard, Pano2VR e la tabella degli angoli.
     *
     * E' il pacchetto che si porta ai due programmi di stitch: i tre file si chiamano tutti come
     * il lavoro, cosi' in PTGui il template si applica alle 24 foto nell'ordine giusto e in
     * pano2vr il progetto punta gia' all'equirettangolare cucito.
     */
    private void exportJob() {
        save();
        PanoPlan plan = currentPlan == null ? PanoPlan.shot24() : currentPlan;
        String job = "PanoLuna_" + plan.size() + "scatti";
        File dir = new File(getExternalFilesDir(null), job);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String stem = job;

        boolean ok = true;
        ok &= write(new File(dir, stem + ".pts"), plan.toPtGuiTemplate(job));
        ok &= write(new File(dir, stem + ".papywizard.xml"), plan.toPapywizardXml());
        ok &= write(new File(dir, stem + ".p2vr"),
                plan.toPano2vrProject(stem + "_equirectangular.jpg"));
        ok &= write(new File(dir, stem + ".csv"), plan.toCsv());
        ok &= write(new File(dir, "README.txt"), readme(plan));

        if (ok) {
            Toast.makeText(this, getString(R.string.pano_export_job_ok, dir.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Запись не удалась", Toast.LENGTH_SHORT).show();
        }
    }

    private String readme(PanoPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("PanoLuna - работа из ").append(plan.size()).append(" снимков\n");
        sb.append("Камера: Insta360 Luna Ultra, объектив 1x\n");
        sb.append("Сетка: ").append(plan.getRows()).append(" строк x ")
                .append(plan.getColumns()).append(" столбцов\n");
        sb.append("Перекрытие: ").append(plan.getOverlapPercent()).append("%\n");
        sb.append("Tilt: ").append(plan.getTiltBottom()).append(" .. ")
                .append(plan.getTiltTop()).append(" градусов\n\n");
        sb.append("1) PTGui: откройте проект ").append("PanoLuna_").append(plan.size()).append("scatti.pts\n");
        sb.append("   File > Открыть шаблон, либо перетащите ").append(plan.size())
                .append(" фотографии в проект:\n");
        sb.append("   углы pan/pitch уже заданы по индексу.\n");
        sb.append("   Выход: equirectangular 27654x13827 (2:1).\n\n");
        sb.append("2) Pano2VR: два пути.\n");
        sb.append("   a) проект ").append("PanoLuna_").append(plan.size()).append("scatti.p2vr, который указывает на equirectangular\n");
        sb.append("      сшитую в PTGui (переименуйте в ").append("PanoLuna_").append(plan.size())
                .append("scatti_equirectangular.jpg).\n");
        sb.append("   b) Pro > File > Import Papywizard, с PanoLuna_").append(plan.size())
                .append("scatti.papywizard.xml:\n");
        sb.append("      pano2vr сам выровняет отдельные фотографии.\n\n");
        sb.append("3) Таблица ").append("PanoLuna_").append(plan.size()).append("scatti.csv - углы по строкам.\n");
        return sb.toString();
    }

    private boolean write(File file, String content) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
