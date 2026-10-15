package com.tcv;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hasil perintah ditulis ke file JSON di folder Movies,
 * supaya gampang dibaca dari shell Termux:
 *   cat /storage/emulated/0/Movies/.tcv_status.json
 */
public class StatusFile {

    private static final Object LOCK = new Object();

    public static File statusFile() {
        return new File(MovieFiles.moviesDir(), ".tcv_status.json");
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    public static void write(String message, String error) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
                o.put("time", now());
                o.put("message", message == null ? "" : message);
                if (error != null && !error.isEmpty()) {
                    o.put("error", error);
                }
                File f = statusFile();
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                FileWriter w = new FileWriter(f, false);
                w.write(o.toString(2));
                w.flush();
                w.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void writeInfo(boolean isRecording, String outputFile) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
                o.put("time", now());
                o.put("isRecording", isRecording);
                if (isRecording && outputFile != null) {
                    o.put("outputFile", outputFile);
                }
                File f = statusFile();
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                FileWriter w = new FileWriter(f, false);
                w.write(o.toString(2));
                w.flush();
                w.close();
            } catch (Exception ignored) {
            }
        }
    }
}
