package com.panoluna.app.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Scrittura delle foto nella galleria del telefono.
 *
 * Da Android 10 in poi la galleria e' MediaStore: si scrive in una cartella relativa
 * {@code Pictures/PanoLuna/<lavoro>} con IS_PENDING, e si toglie il flag a file chiuso.
 * Prima di Android 10 la cartella e' pubblica e serve il permesso di scrittura.
 */
public final class MediaSaver {

    private MediaSaver() {
    }

    public static final String DIRECTORY = "PanoLuna";

    /**
     * Salva un JPEG nella galleria. Restituisce l'Uri, o null se la scrittura e' fallita.
     */
    public static Uri saveJpeg(Context context, String jobFolder, String fileName, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(context, jobFolder, fileName, data);
        }
        return saveViaFile(context, jobFolder, fileName, data);
    }

    private static Uri saveViaMediaStore(Context context, String jobFolder, String fileName,
                                         byte[] data) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, sanitize(fileName));
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + DIRECTORY + "/" + sanitize(jobFolder));
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try {
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            out.write(data);
            out.flush();
            out.close();
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            return null;
        }
    }

    private static Uri saveViaFile(Context context, String jobFolder, String fileName,
                                   byte[] data) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                DIRECTORY + "/" + sanitize(jobFolder));
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File file = new File(dir, sanitize(fileName));
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(data);
            out.flush();
            out.close();
            context.sendBroadcast(new android.content.Intent(
                    android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(file)));
            return Uri.fromFile(file);
        } catch (Exception e) {
            return null;
        }
    }

    /** Elenco delle foto della galleria, dalle piu' recenti alle piu' vecchie. */
    public static java.util.List<ImageItem> listImages(Context context) {
        java.util.List<ImageItem> items = new java.util.ArrayList<ImageItem>();
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
        };
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                ImageItem item = new ImageItem();
                item.uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                item.name = cursor.getString(1);
                item.dateAdded = cursor.getLong(2);
                item.width = cursor.getInt(3);
                item.height = cursor.getInt(4);
                items.add(item);
            }
        } catch (Exception ignored) {
            // Permesso negato o MediaStore non disponibile: la lista resta vuota.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return items;
    }

    public static boolean delete(Context context, Uri uri) {
        try {
            return context.getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static Uri shareTarget(Context context, java.util.List<Uri> uris) {
        if (uris.isEmpty()) {
            return null;
        }
        return uris.get(0);
    }

    private static String sanitize(String name) {
        if (name == null || name.length() == 0) {
            return "image.jpg";
        }
        String cleaned = name.replace('\\', '_').replace('/', '_');
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(cleaned.length() - 80);
        }
        return cleaned;
    }

    public static final class ImageItem {
        public Uri uri;
        public String name;
        public long dateAdded;
        public int width;
        public int height;
    }
}
