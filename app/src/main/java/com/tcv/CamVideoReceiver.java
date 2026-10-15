package com.tcv;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Pintu masuk dari Termux. Contoh dari shell:
 *   am broadcast -n com.tcv/.CamVideoReceiver -a record \
 *     --es file /storage/emulated/0/Movies/uji.mp4 --ei limit 5000
 *
 * Aksi: record | info | quit
 */
public class CamVideoReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!hasPermission(context, Manifest.permission.CAMERA)
                || !hasPermission(context, Manifest.permission.RECORD_AUDIO)
                || !hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            StatusFile.write("Izin belum lengkap. Buka aplikasi TCV Camera dan beri izin kamera, mikrofon, penyimpanan.",
                    "PERMISSION_MISSING");
            // Bantu pengguna: bukakan layarnya.
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return;
        }

        Intent svc = new Intent(context, CamVideoService.class);
        svc.setAction(intent.getAction());
        if (intent.getExtras() != null) {
            svc.putExtras(intent.getExtras());
        }
        try {
            // startForegroundService: pola yang benar untuk jalan dari background.
            // Service kami langsung startForeground() di onStartCommand.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            // Jangan diam: tulis penyebabnya supaya terlihat dari Termux,
            // lalu bukakan aplikasi agar jadi foreground.
            StatusFile.write("", "Gagal menjalankan service: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ". Aplikasi TCV dibuka; ulangi perintah.");
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    private boolean hasPermission(Context context, String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}
