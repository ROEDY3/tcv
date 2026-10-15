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
 *     --es file uji.mp4 --ei limit 5000
 *
 * Aksi: record | info | quit   (service harus sudah diaktifkan dari aplikasi)
 *
 * Receiver ini TIDAK menyalakan service sendiri: service kamera hanya boleh
 * dibuat saat aplikasi terlihat (aturan Android 12+). Ia hanya meneruskan
 * perintah ke service yang sudah hidup.
 */
public class CamVideoReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!hasPermission(context, Manifest.permission.CAMERA)
                || !hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            StatusFile.write(context,
                    "Izin belum lengkap. Buka aplikasi TCV Camera dan beri izin kamera + mikrofon.",
                    "PERMISSION_MISSING");
            openApp(context);
            return;
        }

        if (!CamVideoService.serviceAlive) {
            StatusFile.write(context,
                    "Layanan TCV belum aktif. Buka aplikasi TCV Camera lalu tekan 'Aktifkan layanan'.",
                    "SERVICE_NOT_RUNNING");
            openApp(context);
            return;
        }

        Intent svc = new Intent(context, CamVideoService.class);
        svc.setAction(intent.getAction());
        if (intent.getExtras() != null) {
            svc.putExtras(intent.getExtras());
        }
        try {
            // Service SUDAH hidup: mengirim perintah ke service jalan
            // selalu diizinkan sistem.
            context.startService(svc);
        } catch (Exception e) {
            StatusFile.write(context, "", "Gagal mengirim perintah: " + e.getMessage()
                    + ". Buka aplikasi TCV Camera lalu tekan 'Aktifkan layanan'.");
            openApp(context);
        }
    }

    private void openApp(Context context) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private boolean hasPermission(Context context, String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}
