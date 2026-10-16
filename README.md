# TCV Camera (`com.tcv`)

Aplikasi pendamping kecil untuk merekam video kamera HP langsung dari Termux.
Proyek pribadi — tidak ada hubungan dengan tim Termux, tidak memodifikasi
aplikasi Termux / Termux:API apa pun.

## Cara kerja (orang awam)

- Aplikasi ini yang memegang kamera. **Aktifkan dulu layanannya sekali**
  dari aplikasi (tombol "Aktifkan layanan") — ini pola standar Android 16:
  service kamera hanya boleh dibuat saat aplikasi terlihat.
- Setelah layanan aktif (ada notifikasi tetap "TCV Camera"), skrip `camvideo`
  di Termux cuma menitip perintah: rekam / info / berhenti.
- Video tersimpan di folder **Movies** (`~/storage/shared/Movies` dari Termux).
- Saat merekam, titik hijau kamera/mikrofon menyala otomatis (dari sistem).

## Instalasi

1. Ambil APK dari halaman **Actions** repo ini (artifact `tcv-app`), salin ke HP.
2. Instal APK seperti biasa (sumber tidak dikenal diizinkan sekali).
   Update versi baru tinggal instal menimpa (satu tanda tangan tetap).
3. Buka aplikasi **TCV Camera**:
   - Tombol 1: beri izin kamera + mikrofon (+ notifikasi).
   - Tombol 2: **Aktifkan layanan** (wajib — tanpa ini perintah ditolak).
   - Tombol 3: matikan optimasi baterai (disarankan).
4. Dari Termux:
   ```bash
   termux-setup-storage   # kalau belum pernah (untuk baca ~/storage)
   cp camvideo $PREFIX/bin/
   chmod +x $PREFIX/bin/camvideo
   ```
5. Uji:
   ```bash
   camvideo -d -l 5 -f ujicoba.mp4
   camvideo -i
   camvideo -q
   ```
   Cek hasilnya di `~/storage/shared/Movies/ujicoba.mp4`.

## Perintah

```
camvideo -h                 # bantuan
camvideo -d                 # rekam dengan default
camvideo -d -l 5 -f a.mp4   # rekam 5 detik ke Movies/a.mp4
camvideo -d -p 30           # rekam pada 30 fps
camvideo -c 1 -d            # pakai kamera id 1
camvideo -n -d              # tanpa audio
camvideo -i                 # status rekaman
camvideo -q                 # berhenti merekam (layanan tetap siaga)
camvideo -s                 # stream untuk OBS/Python (640x480 @ 24 fps)
camvideo -s -r hd           # stream rasio 16:9 (preset: 4:3, 1:1, 16:9, 9:16, fhd)
camvideo -S                 # matikan stream
```

Opsi fps rekaman memakai `-p <fps>` (sebelumnya `-r`). Opsi `-r <preset>`
sekarang khusus stream untuk memilih rasio.

## Build sendiri

Push ke branch `main` → workflow **Build** jalan otomatis di GitHub Actions →
unduh artifact `tcv-app`. Tanpa Android SDK di HP.

## Lisensi

GPLv3 — lihat file `LICENSE`. Mesin rekaman Camera2 + MediaRecorder mengikuti
pola umum open source (dipakai juga oleh Termux:API).
