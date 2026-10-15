# TCV Camera (`com.tcv`)

Aplikasi pendamping kecil untuk merekam video kamera HP langsung dari Termux.
Proyek pribadi — tidak ada hubungan dengan tim Termux, tidak memodifikasi
aplikasi Termux / Termux:API apa pun.

## Cara kerja (orang awam)

- Aplikasi ini yang memegang kamera (punya izin kamera + mikrofon + penyimpanan).
- Skrip `camvideo` di Termux cuma "mengetuk pintu" aplikasi ini lewat broadcast,
  lalu membaca hasilnya dari file status.
- Video tersimpan di folder **Movies** (`~/storage/shared/Movies` dari Termux).

## Instalasi

1. Ambil APK dari halaman **Actions** repo ini (artifact `tcv-app`), salin ke HP.
2. Instal APK seperti biasa (sumber tidak dikenal diizinkan sekali).
3. Buka aplikasi **TCV Camera** dari launcher, tekan tombol izin 1 dan 2.
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
camvideo -c 1 -d            # pakai kamera id 1
camvideo -n -d              # tanpa audio
camvideo -i                 # status rekaman
camvideo -q                 # berhenti
```

## Build sendiri

Push ke branch `main` → workflow **Build** jalan otomatis di GitHub Actions →
unduh artifact `tcv-app`. Tanpa Android SDK di HP.

## Lisensi

GPLv3 — lihat file `LICENSE`. Bagian mesin rekaman diadaptasi dari pola
Camera2 + MediaRecorder (segmen open source Termux:API).
