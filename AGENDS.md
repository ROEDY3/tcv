# AGENDS — TCV Camera (`com.tcv`)

Dokumen status dan konteks proyek TCV Camera: aplikasi pendamping Android untuk
merekam kamera HP dari Termux, mengalirkan frame ke Python, dan menyediakan
sumber video/audio lokal untuk OBS Studio di HP yang sama.

Versi terakhir di repo: **0.7.0 / versionCode 17**.

## Tujuan proyek

1. Rekam video kamera HP langsung dari Termux tanpa mengubah Termux/Termux:API.
2. Sediakan frame live ke Python melalui TCP localhost.
3. Sediakan video MJPEG dan audio WAV live ke OBS melalui HTTP localhost.
4. Sediakan video **Raw YUV (NV12)** mentah ke plugin OBS native (paling ringan).
5. Tetap tanpa library Android eksternal dan tanpa root.

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
- `RawYuvPipe.java`
  - ImageReader `YUV_420_888` mengambil frame langsung dari kamera (tanpa
    encode JPEG sama sekali — jauh lebih ringan CPU).
  - Melayani plugin OBS via TCP `127.0.0.1:8092`.
  - Protokol per frame: 4 x uint32 big-endian (width, height, format, size),
    lalu payload NV12 padat (bidang Y lalu UV selang-seling).
  - `packNv12()` menyesuaikan rowStride/pixelStride: Y selalu pixelStride 1;
    U/V pixelStride 2 (semi-planar) dianggap NV12 apa adanya; pixelStride 1
    (planar) dijalin manual.
  - Buffer NV12 dipakai ulang + disalin per klien di bawah lock; lewati frame
    dengan `acquireLatestImage` + throttle `minIntervalMs`.
  - Menyimpan fps nyata, jumlah penonton, dan id frame (untuk watchdog).
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
camvideo -n
camvideo -n -r hd
camvideo -N
camvideo -C
```

Catatan opsi: fps rekaman memakai `-p <fps>` (dulu `-r`). Opsi `-r <preset>`
khusus siaran, memilih rasio: `4:3` (default), `1:1`, `16:9`/`hd`, `9:16`,
`fhd`. Preset dipetakan ke ukuran yang benar-benar didukung kamera.
Fitur lama "rekam tanpa audio" (`-n` lama / `--ez audio false`) sudah dihapus
karena tidak dipakai.

Arti mode siaran:

```bash
camvideo -s    # JPEG  (untuk Python/browser)
camvideo -n    # Raw YUV (untuk plugin OBS)
```

`camvideo -s` menyalakan (default 640x480 @ 24 fps, JPEG quality 80):

- TCP Python: `127.0.0.1:8090`
- Video OBS: `http://127.0.0.1:8091/mjpeg`
- Audio OBS: `http://127.0.0.1:8091/audio.wav`

`camvideo -n` menyalakan (default 640x480 @ 24 fps, NV12 mentah):

- TCP plugin OBS: `127.0.0.1:8092`

Jalur JPEG dan Raw YUV bisa jalan bersama, tetapi berarti kamera mengeluarkan
dua aliran sekaligus; di HP kelas menengah bisa menurunkan fps.

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
- `app/src/main/java/com/tcv/RawYuvPipe.java`
- `app/src/main/java/com/tcv/MjpegHttpServer.java`
- `app/src/main/java/com/tcv/AudioStreamer.java`
- `app/src/main/java/com/tcv/StatusFile.java`
- `app/src/main/java/com/tcv/MediaFiles.java`
- `camvideo`
- `examples/tcv_camera.py`
- `.github/workflows/build.yml`
- `README.md`

## Catatan: integrasi OBS via plugin custom (Opsi B, 2026-10-17)

- Opsi A (Media Source OBS baca MJPEG HTTP 8091) = performa buruk.
- Opsi B (SELESAI): plugin OBS native `tcv-capture` baca langsung dari TCP,
  render tanpa HTTP/FFmpeg. Plugin di `~/diskusi/tcv-capture/`.
  Awalnya mode JPEG (TCP 8090) — sudah teruji ujung-ke-ujung dan
  **dikonfirmasi tampil di OBS** (lihat `~/diskusi/obs-studio/agend.md` §15).
- Bug tutup OBS ditemukan & diperbaiki: event stop bertipe `OS_EVENT_TYPE_AUTO`
  → sinyal dikonsumsi di loop dalam → loop luar reconnect terus →
  `pthread_join` menggantung → OBS tidak mau tutup. Fix: `OS_EVENT_TYPE_MANUAL`
  + `SO_RCVTIMEO` 300 ms + `shutdown()` di `tcv_destroy`/`tcv_update`.
  Diverifikasi: fase 1 aman.
- Opsi B2 (SELESAI di v0.7.0): app menambah `RawYuvPipe.java` yang mengeluarkan
  YUV mentah (NV12) di `127.0.0.1:8092` langsung dari `ImageReader`
  `YUV_420_888` — tanpa encode, hemat CPU. Plugin diubah jadi **Raw YUV saja**
  (hapus jalur JPEG + libjpeg), dengan properti dropdown **"Perangkat TCV"**
  yang mengarah ke `127.0.0.1:8092`.
- Jalur JPEG tetap ada di app untuk OpenCV/Python (TCP 8090 + HTTP 8091);
  plugin OBS tidak lagi memakainya.
- Karena plugin kini hanya menerima YUV, sumber video OBS lama yang tersimpan
  dengan format JPEG harus di-reset (buka sumber → pilih ulang "Perangkat TCV")
  setelah APK v0.7.0 terpasang.

## Keamanan server lokal

Semua server bind ke `127.0.0.1` saja
(`InetAddress.getByName("127.0.0.1")`):
`FramePipe` (8090), `MjpegHttpServer` (8091), `RawYuvPipe` (8092).
Artinya tidak bisa dijangkau dari HP lain, Wi-Fi, maupun internet — gambar
tidak pernah keluar dari HP.

Batas ancaman yang tersisa (hanya dari DALAM HP yang sama):

- App lain yang punya izin INTERNET bisa konek selama siaran hidup dan membaca
  frame tanpa izin CAMERA. Perlu app jahat sudah terpasang lebih dulu.
- HTTP 8091 tidak memvalidasi header `Host` → rentan "DNS rebinding" lewat
  browser. Port 8090/8092 TCP mentah tidak bisa disentuh browser.
- Server hanya hidup saat `-s`/`-n`; setelah `-S`/`-N` (atau layanan mati)
  tidak ada socket yang bind.

Rencana penguatan (BELUM dikerjakan):

- **Token rahasia app↔plugin** (disarankan): permintaan pertama harus memuat
  token; tanpa token koneksi ditutup. Menutup celah app lain & situs web.
  Perlu ubah app + plugin + `camvideo` + rebuild APK.
- Validasi `Host: 127.0.0.1` di `MjpegHttpServer` (kecil, cegah DNS rebinding).
- Opsi paling kuat: AF_UNIX + `SO_PEERCRED` (batasi ke UID Termux/OBS), tetapi
  jalur Python/proot perlu dicek ulang.

## Langkah berikutnya

1. Build & instal APK v0.7.0 (push ke `main` → Actions → artifact `tcv-app`).
2. Aktifkan layanan dari aplikasi.
3. Jalankan `camvideo -n` lalu `camvideo -i` untuk melihat fps nyata Raw YUV.
4. Di OBS, sumber "TCV Camera Capture" pilih "Perangkat TCV"
   (127.0.0.1:8092) — pastikan gambar tampil dan FPS stabil.
5. Ukur fps nyata saat YUV 640x480; coba `camvideo -n -r 16:9` / `fhd` bila berat.
6. **Rotasi YUV**: frame mentah tidak membawa info orientasi, jadi di OBS
   gambar kemungkinan miring 90° (sensor kamera). Untuk sementara betulkan
   dengan transform sumber di OBS (Klik kanan → Transform → Rotate 90°).
   Bila mengganggu, opsi berikutnya: kirim sudut rotasi di header (byte sisa
   `format`) lalu plugin memutarnya, atau putar di app.
7. Uji jalan bersama `camvideo -n` + `camvideo -s` (video YUV + audio JPEG
   path) dan catat penurunan fps bila ada.
8. Bila kualitas kurang bagus, selidiki matrix warna NV12 di plugin
   (`full_range`, `VIDEO_CS_DEFAULT`).
9. (Opsional, ditunda atas permintaan) Pengaman server: token app↔plugin +
   validasi `Host` di 8091 — lihat bagian "Keamanan server lokal". Belum
   dikerjakan.
