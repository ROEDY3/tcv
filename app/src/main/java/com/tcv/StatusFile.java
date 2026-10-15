package com.tcv;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hasil perintah ditulis ke Download/tcv_status.json via MediaStore,
 * supaya gampang dibaca dari shell Termux:
 *   cat /storage/emulated/0/Download/tcv_status.json
 */
public class StatusFile {

    private static final Object LOCK = new Object();

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    public static void write(Context context, String message, String error) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
                o.put("time", now());
                o.put("message", message == null ? "" : message);
                if (error != null && !error.isEmpty()) {
                    o.put("error", error);
                }
                MediaFiles.writeStatus(context, o.toString(2));
            } catch (Exception ignored) {
            }
        }
    }

    public static void writeInfo(Context context, boolean isRecording, String outputFile,
                               boolean streamOn, String streamInfo) {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
                o.put("time", now());
                o.put("isRecording", isRecording);
                if (isRecording && outputFile != null) {
                    o.put("outputFile", outputFile);
                }
                o.put("streamOn", streamOn);
                if (streamOn && streamInfo != null) {
                    o.put("stream", streamInfo);
                }
                MediaFiles.writeStatus(context, o.toString(2));
            } catch (Exception ignored) {
            }
        }
    }
}
