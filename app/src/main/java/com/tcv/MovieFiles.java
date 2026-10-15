package com.tcv;

import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lokasi file video + file status: folder Movies di shared storage,
 * supaya hasilnya langsung terlihat di galeri/file manager
 * dan gampang dibaca dari Termux (~/storage/shared/Movies).
 */
public class MovieFiles {

    public static File moviesDir() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = new File(Environment.getExternalStorageDirectory(), "Movies");
        }
        dir.mkdirs();
        return dir;
    }

    public static File defaultOutputFile() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return new File(moviesDir(), "TCV_" + stamp + ".mp4");
    }

    /** Normalisasi argumen -f: boleh nama file, path relatif Movies, atau path absolut. */
    public static File resolve(String userPath) {
        if (userPath == null || userPath.isEmpty()) {
            return defaultOutputFile();
        }
        File f = new File(userPath);
        if (!f.isAbsolute()) {
            f = new File(moviesDir(), userPath);
        }
        if (!f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
            f = new File(f.getParent(), f.getName() + ".mp4");
        }
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        return f;
    }
}
