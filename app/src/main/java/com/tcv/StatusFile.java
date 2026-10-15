package com.tcv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Hasil perintah ditulis ke Download/tcv_status.json supaya gampang
 * dibaca dari shell Termux:
 *   cat /storage/emulated/0/Download/tcv_status.json
 *
 * Bila tulis gagal, alasannya dimunculkan sebagai Toast supaya
 * terlihat manusia (tidak diam seperti log).
 */
public class StatusFile {

    private static final Object LOCK = new Object();

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static void toastFail(final Context context) {
        final String err = MediaFiles.lastError;
        if (err == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context.getApplicationContext(),
                            "TCV: status gagal disimpan: " + err,
                            Toast.LENGTH_LONG).show());
        } catch (Exception ignored) {
        }
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
                if (!MediaFiles.writeStatus(context, o.toString(2))) {
                    toastFail(context);
                }
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
                if (!MediaFiles.writeStatus(context, o.toString(2))) {
                    toastFail(context);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
