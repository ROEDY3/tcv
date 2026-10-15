package com.tcv;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

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

    public static void write(String message, String error) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
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
            } catch (IOException ignored) {
            }
        }
    }
}
