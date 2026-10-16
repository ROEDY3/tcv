# AGENDS — TCV Camera (`com.tcv`)

Dokumen status dan konteks proyek TCV Camera: aplikasi pendamping Android untuk
merekam kamera HP dari Termux, mengalirkan frame ke Python, dan menyediakan
sumber video/audio lokal untuk OBS Studio di HP yang sama.

Versi terakhir di repo: **0.6.1 / versionCode 16**.

## Tujuan proyek

1. Rekam video kamera HP langsung dari Termux tanpa mengubah Termux/Termux:API.
2. Sediakan frame live ke Python melalui TCP localhost.
3. Sediakan video MJPEG dan audio WAV live ke OBS melalui HTTP localhost.
4. Tetap tanpa library Android eksternal dan tanpa root.

## Kondisi penting perangkat

- Tidak ada `/dev/video*`.
- Tidak ada akses root.
- Karena itu plugin OBS **Video Capture Device (V4L2)** tidak dapat dipakai.
- Jalur OBS yang didukung adalah URL HTTP lokal, bukan perangkat V4L2.

## Arsitektur

- `MainActivity.java`
  - Meminta izin kamera, mikrofon, dan notifikasi.
  - Tombol aktivasi layanan foreground.
  - Tombol akses semua file dan pengaturan baterai.
- `CamVideoReceiver.java`
  - Menerima broadcast dari skrip `camvideo`.
  - Menolak perintah bila layanan belum aktif.
- `CamVideoService.java`
  - Foreground service tipe `camera|microphone`.
  - Mengelola rekaman MP4 dan sesi streaming.
  - Menjaga satu kamera tidak dipakai dua mode berbeda secara bersamaan.
- `FramePipe.java`
  - Menerima frame JPEG dari kamera (ImageReader) dan melayani Python via
    TCP `127.0.0.1:8090`.
  - Format tiap frame: panjang big-endian 4 byte + data JPEG.
  - Target default 24 fps (`setTargetFps`), pakai `SystemClock.elapsedRealtime`
    agar tahan lompatan jam dinding.
  - Menyimpan pengukuran fps nyata (frame yang benar-benar di-push) untuk
    dipakai status dan watchdog.
  - `CamVideoService` memasang watchdog: bila frame berhenti bertambah, sesi
    dibangun ulang; bila tetap macet, kamera dibuka ulang.
- `MjpegHttpServer.java`
  - Server HTTP mini di `127.0.0.1:8091`.
  - `/` untuk halaman uji browser.
  - `/mjpeg` untuk video OBS dalam format MJPEG multipart.
  - `/audio.wav` untuk audio OBS.
- `AudioStreamer.java`
  - Menangkap mic 16 kHz mono 16-bit.
  - Menggunakan `VOICE_RECOGNITION` dan `AcousticEchoCanceler` bila tersedia.
  - Setiap klien HTTP punya kursor sendiri.
  - Ring buffer kecil sekitar 80 ms untuk mengurangi audio tertinggal.
- `StatusFile.java`
  - Menulis status JSON ke `Download/tcv_status.json`.
- `MediaFiles.java`
  - Menyimpan MP4 melalui MediaStore ke folder Movies.
- `camvideo`
  - CLI Termux untuk mengirim perintah broadcast ke aplikasi.

## Perintah utama

```bash
cp camvideo $PREFIX/bin/
chmod +x $PREFIX/bin/camvideo

camvideo -d -l 5 -f ujicoba.mp4
camvideo -d -p 30
camvideo -i
camvideo -q
camvideo -s
camvideo -s -r hd
camvideo -S
camvideo -C
```

Catatan opsi: fps rekaman memakai `-p <fps>` (dulu `-r`). Opsi `-r <preset>`
khusus stream, memilih rasio: `4:3` (default), `1:1`, `16:9`/`hd`, `9:16`,
`fhd`. Preset dipetakan ke ukuran JPEG yang benar-benar didukung kamera.

Arti mode stream:

```bash
camvideo -s
```

Perintah itu menyalakan (default 640x480 @ 24 fps, JPEG quality 80):

- TCP Python: `127.0.0.1:8090`
- Video OBS: `http://127.0.0.1:8091/mjpeg`
- Audio OBS: `http://127.0.0.1:8091/audio.wav`

## Integrasi OBS

Gunakan sumber **Media Source**, bukan V4L2:

1. Tambahkan Media Source video:
   - `http://127.0.0.1:8091/mjpeg`
2. Tambahkan Media Source audio:
   - `http://127.0.0.1:8091/audio.wav`
3. Jangan mengaktifkan monitoring audio OBS ke speaker HP bila tidak memakai headset.
4. Bila Media Source tidak tersedia, alternatif darurat adalah menampilkan stream
   melalui `ffplay`/browser lalu menangkap jendelanya dengan Screen Capture.

## Temuan audio terakhir

- Echo berulang yang makin mengeras awalnya diduga bug server.
- Pengujian menunjukkan penyebab dominannya adalah loop akustik:
  mic menangkap suara → OBS/browser memutar ke speaker HP → mic menangkap lagi.
- Sumber `MIC` dengan AGC memperparah sisa echo tersebut.
- v0.5.2 mengganti sumber menjadi `VOICE_RECOGNITION` dan mencoba AEC.
- v0.5.3 mengubah buffer audio menjadi ring kecil per-klien.
- Audio WAV terverifikasi sebagai `pcm_s16le`, 16 kHz, mono.
- Pengujian dua klien bersamaan menerima jumlah byte yang sama.

## Masalah aktif

0. **FPS untuk OpenCV rendah — ditangani di v0.6.0, perlu verifikasi.**
   - Penyebab: stream default hanya 10 fps, tanpa `JPEG_QUALITY` maupun
     `CONTROL_AE_TARGET_FPS_RANGE`, sehingga kamera juga turun fps di cahaya
     redup.
   - v0.6.0: default stream 24 fps, `JPEG_QUALITY` 80, AE target fps range
     mengikuti target, watchdog sesi, dan fps nyata tampil di `camvideo -i`.
   - v0.6.1: pemilihan `CONTROL_AE_TARGET_FPS_RANGE` diprioritaskan ke rentang
     sempit/mendekati tetap supaya kamera tidak mengubah fps dinamis
     mengikuti cahaya (penyebab judder).
   - Patah-patah sisi Python juga diperbaiki di `tmp/main.py`: inferensi
     MediaPipe dipindah ke thread, sumber default TCP mentah, lerp prediktif.
   - Yang belum: ukur fps nyata di HP dan pastikan `fhd` tidak terlalu berat.
1. **Audio masih terasa telat dibanding video.**
   - Penyebab yang sudah diperbaiki: antrean audio lama sekitar 3 detik dan
     klien baru mulai dari data lama.
   - v0.5.3 memakai ring sekitar 80 ms dan mulai dari chunk setelah koneksi dibuat.
   - OBS sendiri masih dapat menambah buffering internal.
2. **Belum ada verifikasi sinkronisasi akhir setelah v0.5.3 diaktifkan.**
3. **Daftar source OBS milik pengguna belum dipastikan.**
   - Perlu memastikan apakah `Media Source` benar-benar tidak ada atau hanya
     belum ditemukan.

## Build dan distribusi

- Push ke branch `main` menjalankan workflow GitHub Actions `Build`.
- Artifact bernama `tcv-app`.
- APK memakai keystore tetap supaya update menimpa tanpa uninstall.
- Setelah update APK, layanan harus diaktifkan ulang dari aplikasi.
- Tanpa aktivasi ulang, `camvideo` akan melaporkan layanan belum aktif.

## File penting

- `app/build.gradle`
- `app/src/main/java/com/tcv/MainActivity.java`
- `app/src/main/java/com/tcv/CamVideoReceiver.java`
- `app/src/main/java/com/tcv/CamVideoService.java`
- `app/src/main/java/com/tcv/FramePipe.java`
- `app/src/main/java/com/tcv/MjpegHttpServer.java`
- `app/src/main/java/com/tcv/AudioStreamer.java`
- `app/src/main/java/com/tcv/StatusFile.java`
- `app/src/main/java/com/tcv/MediaFiles.java`
- `camvideo`
- `examples/tcv_camera.py`
- `.github/workflows/build.yml`
- `README.md`

## Langkah berikutnya

1. Instal APK v0.6.0.
2. Aktifkan layanan dari aplikasi.
3. Jalankan `camvideo -s` lalu `camvideo -i` untuk melihat fps nyata.
4. Uji FPS di Python (`tmp/main.py`) dan coba `camvideo -s -r hd`.
5. Uji video dan audio OBS bersamaan.
6. Ukur jeda nyata antara perubahan gambar dan suara.
7. Bila jeda masih besar, selidiki buffering Media Source OBS sebelum mengubah kode lagi.
