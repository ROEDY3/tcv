package com.tcv;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.util.Locale;

/**
 * Semua file lewat MediaStore (standar Android 10+):
 * - video -> koleksi Movies (terlihat di galeri)
 * - status -> koleksi Download (terbaca shell Termux)
 * Tanpa izin storage apa pun.
 */
public class MediaFiles {

    private static final String TAG = "TCV";

    public static final String STATUS_NAME = "tcv_status.json";

    /** Rapikan escape JSON (\/ -> /) supaya enak dibaca manusia. */
    private static String cleanJson(String json) {
        return json.replace("\\/", "/");
    }

    /** Path file status seperti terlihat dari Termux. */
    public static String statusPathForShell() {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/Download/" + STATUS_NAME;
    }

    /** Nama file aman di dalam Movies: hanya basename + .mp4 */
    public static String movieName(String userPath) {
        String name = "TCV_" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                Locale.US).format(new java.util.Date()) + ".mp4";
        if (userPath != null && !userPath.isEmpty()) {
            String base = userPath;
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);
            if (!base.isEmpty()) name = base;
            if (!name.toLowerCase(Locale.US).endsWith(".mp4")) name += ".mp4";
        }
        return name;
    }

    /** Hapus baris MediaStore lama dengan nama yang sama (supaya bisa timpa). */
    private static void deleteByName(ContentResolver cr, Uri collection, String name) {
        try {
            cr.delete(collection,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{name});
        } catch (Exception e) {
            Log.i(TAG, "deleteByName: " + e.getMessage());
        }
    }

    /** Hasil pegangan file video baru di Movies. */
    public static class VideoSlot {
        public final Uri uri;
        public final ParcelFileDescriptor pfd;
        public final String shellPath;

        VideoSlot(Uri uri, ParcelFileDescriptor pfd, String shellPath) {
            this.uri = uri;
            this.pfd = pfd;
            this.shellPath = shellPath;
        }
    }

    public static VideoSlot createMovie(Context context, String name) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        deleteByName(cr, collection, name);

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/");
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new Exception("MediaStore insert video gagal");
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w");
        if (pfd == null) {
            cr.delete(uri, null, null);
            throw new Exception("MediaStore open video gagal");
        }
        String shellPath = Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/Movies/" + name;
        return new VideoSlot(uri, pfd, shellPath);
    }

    public static void deleteVideo(Context context, Uri uri) {
        if (uri == null) return;
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    public static void writeStatus(Context context, String json) {
        try {
            ContentResolver cr = context.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            // Hapus SEMUA baris lama bernama tcv_status* satu per satu via Uri
            // (pola delete-by-where terbukti tidak mempan di sebagian HP).
            try (android.database.Cursor c = cr.query(collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"tcv_status%"}, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        try {
                            cr.delete(android.content.ContentUris.withAppendedId(collection, id),
                                    null, null);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, STATUS_NAME);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            Uri uri = cr.insert(collection, v);
            if (uri == null) return;
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) {
                cr.delete(uri, null, null);
                return;
            }
            os.write(cleanJson(json).getBytes("UTF-8"));
            os.flush();
            os.close();
        } catch (Exception e) {
            Log.e(TAG, "writeStatus gagal: " + e.getMessage());
        }
    }
}
