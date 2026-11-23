package com.panoluna.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.panoluna.app.PanoLunaApp;
import com.panoluna.app.R;
import com.panoluna.app.media.MediaSaver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Galleria del telefono con gestione: selezione multipla, eliminazione e condivisione.
 *
 * Contiene le foto scaricate dalla camera (Pictures/PanoLuna) insieme al resto della galleria.
 */
public final class GalleryActivity extends Activity {

    private static final int REQUEST_STORAGE = 2001;

    private GridView grid;
    private TextView empty;
    private TextView title;
    private ImageButton deleteButton;
    private ImageButton shareButton;

    private final List<MediaSaver.ImageItem> items = new ArrayList<MediaSaver.ImageItem>();
    private final Set<Long> selected = new HashSet<Long>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PanoLunaApp.get(this).applyTheme(this);
        setContentView(R.layout.activity_gallery);
        grid = findViewById(R.id.gallery_grid);
        empty = findViewById(R.id.gallery_empty);
        title = findViewById(R.id.gallery_title);
        deleteButton = findViewById(R.id.gallery_delete);
        shareButton = findViewById(R.id.gallery_share);

        adapter = new Adapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                MediaSaver.ImageItem item = items.get(position);
                long key = item.uri.hashCode();
                if (selected.contains(key)) {
                    selected.remove(key);
                } else {
                    selected.add(key);
                }
                adapter.notifyDataSetChanged();
                paintTitle();
            }
        });
        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view,
                                           int position, long id) {
                view(items.get(position).uri);
                return true;
            }
        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareSelected();
            }
        });
        ImageButton back = findViewById(R.id.gallery_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ensurePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void ensurePermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_STORAGE) {
            load();
        }
    }

    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaSaver.ImageItem> loaded = MediaSaver.listImages(GalleryActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        items.clear();
                        items.addAll(loaded);
                        selected.clear();
                        adapter.notifyDataSetChanged();
                        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        paintTitle();
                    }
                });
            }
        }).start();
    }

    private void paintTitle() {
        if (selected.isEmpty()) {
            title.setText(R.string.gallery_title);
        } else {
            title.setText(getString(R.string.gallery_selected, selected.size()));
        }
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            return;
        }
        final List<MediaSaver.ImageItem> toDelete = new ArrayList<MediaSaver.ImageItem>();
        for (MediaSaver.ImageItem item : items) {
            if (selected.contains((long) item.uri.hashCode())) {
                toDelete.add(item);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.gallery_delete)
                .setMessage(String.valueOf(toDelete.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                for (MediaSaver.ImageItem item : toDelete) {
                                    MediaSaver.delete(GalleryActivity.this, item.uri);
                                    selected.remove((long) item.uri.hashCode());
                                }
                                load();
                            }
                        })
                .show();
    }

    private void shareSelected() {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (MediaSaver.ImageItem item : items) {
            if (selected.contains((long) item.uri.hashCode())) {
                uris.add(item.uri);
            }
        }
        if (uris.isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("image/jpeg");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.gallery_share)));
    }

    private void view(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            Bitmap bitmap = BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(uri), null, options);
            if (bitmap == null) {
                return;
            }
            ImageView view = new ImageView(this);
            view.setImageBitmap(bitmap);
            view.setAdjustViewBounds(true);
            new AlertDialog.Builder(this).setView(view).setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception ignored) {
            // la foto non si può aprire: niente da fare
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_gallery, parent, false);
            }
            MediaSaver.ImageItem item = items.get(position);
            ImageView image = view.findViewById(R.id.item_image);
            CheckBox check = view.findViewById(R.id.item_check);
            TextView name = view.findViewById(R.id.item_name);
            image.setImageBitmap(null);
            name.setText(item.name);
            check.setChecked(selected.contains((long) item.uri.hashCode()));
            loadThumb(image, item.uri);
            return view;
        }
    }

    private void loadThumb(final ImageView target, final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 8;
                    final Bitmap bitmap = BitmapFactory.decodeStream(
                            getContentResolver().openInputStream(uri), null, options);
                    if (bitmap != null) {
                        target.post(new Runnable() {
                            @Override
                            public void run() {
                                target.setImageBitmap(bitmap);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // miniature mancanti: la casella resta vuota
                }
            }
        }).start();
    }
}
