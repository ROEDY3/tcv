package com.tcv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Layar utama. Tugasnya: izin + tombol "Aktifkan layanan".
 * Service dinyalakan dari sini (saat aplikasi terlihat) sesuai aturan Android.
 */
public class MainActivity extends Activity {

    private static final int REQ_CODE = 42;

    private TextView statusText;
    private Button btnService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button btnPermissions = findViewById(R.id.btn_permissions);
        btnService = findViewById(R.id.btn_service);
        Button btnBattery = findViewById(R.id.btn_battery);

        btnPermissions.setOnClickListener(v -> {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.CAMERA);
            perms.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(perms.toArray(new String[0]), REQ_CODE);
        });

        btnService.setOnClickListener(v -> {
            Intent svc = new Intent(this, CamVideoService.class);
            if (CamVideoService.serviceAlive) {
                svc.setAction("stop");
                startService(svc);
            } else {
                svc.setAction("start");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            }
            refreshStatus();
        });

        btnBattery.setOnClickListener(v -> {
            try {
                Intent i = new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                statusText.setText("Tidak bisa membuka pengaturan baterai otomatis. "
                        + "Buka manual: Settings > Apps > TCV Camera > Battery > Unrestricted.");
            }
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        refreshStatus();
    }

    private boolean has(String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshStatus() {
        boolean cam = has(Manifest.permission.CAMERA);
        boolean mic = has(Manifest.permission.RECORD_AUDIO);
        boolean notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean noBatteryOpt = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());

        StringBuilder sb = new StringBuilder();
        sb.append("Izin kamera: ").append(cam ? "OK" : "BELUM").append("\n");
        sb.append("Izin mikrofon: ").append(mic ? "OK" : "BELUM").append("\n");
        sb.append("Izin notifikasi: ").append(notif ? "OK" : "BELUM").append("\n");
        sb.append("Optimasi baterai: ").append(noBatteryOpt ? "mati (bagus)" : "aktif (disarankan matikan via tombol 3)").append("\n");
        sb.append("Layanan: ").append(CamVideoService.serviceAlive ? "AKTIF siaga" : "MATI").append("\n");
        if (cam && mic && CamVideoService.serviceAlive) {
            sb.append("Siap! Kembali ke Termux dan jalankan camvideo.");
        }
        statusText.setText(sb.toString());
        btnService.setText(CamVideoService.serviceAlive ? "Matikan layanan" : "Aktifkan layanan");
    }
}
