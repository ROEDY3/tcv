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
camvideo -i                 # status rekaman
camvideo -q                 # berhenti merekam (layanan tetap siaga)
camvideo -s                 # stream JPEG (640x480 @ 24 fps) untuk Python/browser
camvideo -s -r hd           # stream JPEG rasio 16:9 (preset: 4:3, 1:1, 16:9, 9:16, fhd)
camvideo -S                 # matikan stream JPEG
camvideo -n                 # stream Raw YUV (640x480 @ 24 fps) untuk plugin OBS
camvideo -n -r hd           # stream Raw YUV rasio 16:9
camvideo -N                 # matikan stream Raw YUV
```

Opsi fps rekaman memakai `-p <fps>` (sebelumnya `-r`). Opsi `-r <preset>`
sekarang khusus siaran untuk memilih rasio.

Dua jalur siaran langsung:

- **JPEG** (`-s`): server TCP `127.0.0.1:8090` + HTTP MJPEG `127.0.0.1:8091`
  (untuk OpenCV/Python/browser). Ada audio mic di `/audio.wav`.
- **Raw YUV** (`-n`): server TCP `127.0.0.1:8092` mengirim NV12 mentah langsung
  dari kamera — tanpa encode, paling ringan, dipakai plugin OBS
  "TCV Camera Capture". Tidak ada audio di jalur ini.

Kalau perlu video dan audio di OBS, tambahkan audio lewat Media Source
`http://127.0.0.1:8091/audio.wav` (butuh `-s` jalan). Catatan: menjalankan
`-n` dan `-s` bersama berarti kamera mengeluarkan dua aliran sekaligus —
di HP kelas menengah bisa menurunkan fps, jadi uji dulu.

## Keamanan server kamera

Server TCV hanya mendengarkan di `127.0.0.1` (loopback), jadi:

- Tidak bisa diakses dari HP lain, Wi-Fi, maupun internet. Gambar tidak pernah
  keluar dari HP.
- Hanya app/shell **di HP yang sama** yang bisa menghubunginya.

Yang perlu diwaspadai:

- App lain di HP yang punya izin internet bisa konek ke port 8090/8091/8092
  **selama siaran hidup** (8090/8092 TCP mentah, 8091 HTTP). Syaratnya app
  nakal itu sudah terpasang di HP lebih dulu.
- Port HTTP 8091 bisa disentuh browser; ada serangan "DNS rebinding" dari situs
  web. Server belum memeriksa header `Host`.
- Server **hanya hidup saat `-s`/`-n`**. Setelah `-S`/`-N` atau layanan
  dimatikan, tidak ada yang mendengarkan sama sekali.

Saran praktis:

- Hentikan siaran kalau tidak dipakai.
- Berhati-hati memasang app dari luar sumber resmi.

Rencana penguatan (belum dikerjakan): kunci rahasia (token) antara app dan
plugin, serta pemeriksaan header `Host` di port 8091.

## Build sendiri

Push ke branch `main` → workflow **Build** jalan otomatis di GitHub Actions →
unduh artifact `tcv-app`. Tanpa Android SDK di HP.

## Lisensi

GPLv3 — lihat file `LICENSE`. Mesin rekaman Camera2 + MediaRecorder mengikuti
pola umum open source (dipakai juga oleh Termux:API).
