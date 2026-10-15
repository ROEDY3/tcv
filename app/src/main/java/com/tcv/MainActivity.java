package com.tcv;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/**
 * Layar sekali-pakai: meminta izin yang dibutuhkan perekaman,
 * lalu pengguna kembali ke Termux. Tidak ada logika rekaman di sini.
 */
public class MainActivity extends Activity {

    private static final int REQ_CODE = 42;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button btnPermissions = findViewById(R.id.btn_permissions);
        Button btnStorage = findViewById(R.id.btn_storage);

        btnPermissions.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        }, REQ_CODE));

        btnStorage.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQ_CODE));

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
        boolean storage = has(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (cam && mic && storage) {
            statusText.setText("Semua izin OK. Silakan kembali ke Termux dan jalankan camvideo.");
        } else {
            StringBuilder sb = new StringBuilder("Belum lengkap:");
            if (!cam) sb.append("\n- kamera BELUM");
            if (!mic) sb.append("\n- mikrofon BELUM");
            if (!storage) sb.append("\n- penyimpanan BELUM");
            statusText.setText(sb.toString());
        }
    }
}
