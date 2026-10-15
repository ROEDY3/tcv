package com.tcv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mesin perekam video. Hidup sebagai foreground service + notifikasi
 * supaya sistem tidak mematikan kamera saat merekam.
 *
 * Pola perintah: record | info | quit (dikirim CamVideoReceiver).
 */
public class CamVideoService extends Service {

    private static final String TAG = "TCV";
    private static final String CHANNEL_ID = "tcv_recording";
    private static final int NOTIF_ID = 1;

    private static final int MIN_DURATION_LIMIT = 1000;
    private static final int DEFAULT_DURATION_LIMIT = 1000 * 60 * 15; // 15 menit

    private static CameraDevice cameraDevice;
    private static CameraCaptureSession captureSession;
    private static MediaRecorder mediaRecorder;
    private static HandlerThread backgroundThread;

    private static boolean isRecording = false;
    private static boolean isAttemptInFlight = false;
    private static long sessionGen = 0;
    private static File file;
    private static long recordStartMs = 0;
    private static int openRetryCount = 0;
    private static final int MAX_OPEN_RETRIES = 3;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent == null || intent.getAction() == null) {
            StatusFile.write("", "Perintah kosong.");
            stopSelfIfIdle();
            return START_NOT_STICKY;
        }
        String command = intent.getAction();
        Log.i(TAG, "command: " + command);
        switch (command) {
            case "record":
                handleRecord(getApplicationContext(), intent);
                break;
            case "info":
                String infoExtra = "";
                if (isRecording && file != null) {
                    long elapsed = (System.currentTimeMillis() - recordStartMs) / 1000;
                    infoExtra = " (jalan " + elapsed + " dtk)";
                }
                StatusFile.writeInfo(isRecording, isRecording && file != null
                        ? file.getAbsolutePath() + infoExtra : null);
                stopSelfIfIdle();
                break;
            case "quit":
                sessionGen++; // batalkan retry yang mungkin terjadwal
                isAttemptInFlight = false;
                String msg = isRecording && file != null
                        ? "Recording finished: " + file.getAbsolutePath()
                        : "No recording to stop";
                cleanup();
                StatusFile.write(msg, null);
                stopSelf();
                break;
            default:
                StatusFile.write("", "Unknown command: " + command);
                stopSelfIfIdle();
                break;
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "TCV Recording",
                    NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("TCV Camera")
                .setContentText(isRecording ? "Sedang merekam..." : "Siap merekam")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private void stopSelfIfIdle() {
        if (!isRecording) stopSelf();
    }

    private void handleRecord(final Context context, final Intent intent) {
        if (isRecording || isAttemptInFlight) {
            StatusFile.write("", "Sibuk: sudah ada rekaman/percobaan berjalan. Tunggu atau kirim quit dulu.");
            return;
        }

        String cameraId = intent.getStringExtra("camera");
        if (cameraId == null) cameraId = "0";

        File outFile = MovieFiles.resolve(intent.getStringExtra("file"));
        if (outFile.exists()) {
            StatusFile.write("", "File: " + outFile.getName() + " already exists! Please specify a different filename");
            stopSelfIfIdle();
            return;
        }

        int duration = intent.getIntExtra("limit", DEFAULT_DURATION_LIMIT);
        if (duration > 0 && duration < MIN_DURATION_LIMIT) duration = MIN_DURATION_LIMIT;
        final int durationMs = duration;

        final boolean recordAudio = intent.getBooleanExtra("audio", true);
        final int bitrate = intent.getIntExtra("bitrate", 10_000_000);
        final int fps = intent.getIntExtra("fps", 30);

        file = outFile;
        openRetryCount = 0;
        sessionGen++;
        final long myGen = sessionGen;
        isAttemptInFlight = true;
        attemptOpenCamera(context, intent, cameraId, outFile, durationMs, recordAudio, bitrate, fps, myGen);
    }

    /** Handler yang selalu valid: bikin ulang thread kalau sudah mati. */
    private static synchronized Handler bgHandler() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("TCVBackground");
            backgroundThread.start();
        }
        return new Handler(backgroundThread.getLooper());
    }

    private void attemptOpenCamera(final Context context, final Intent intent,
                                   final String cameraId, final File outFile,
                                   final int durationMs, final boolean recordAudio,
                                   final int bitrate, final int fps, final long myGen) {
        if (myGen != sessionGen) return; // basi: sudah ada perintah baru
        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            //noinspection MissingPermission
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (myGen != sessionGen) {
                        try { camera.close(); } catch (RuntimeException ignored) {}
                        return;
                    }
                    cameraDevice = camera;
                    try {
                        final CameraCharacteristics characteristics =
                                manager.getCameraCharacteristics(cameraId);
                        startSession(context, intent, camera, characteristics, file,
                                durationMs, recordAudio, bitrate, fps, myGen);
                    } catch (Exception e) {
                        Log.e(TAG, "start session error", e);
                        failAttempt(myGen, outFile, "Error starting recording: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    if (myGen != sessionGen) return;
                    Log.i(TAG, "camera disconnected");
                    failAttempt(myGen, outFile, "Camera disconnected tiba-tiba.");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    if (myGen != sessionGen) return;
                    Log.e(TAG, "camera error: " + error);
                    // Error 4 (perangkat) sering sesaat: coba lagi maksimal 3x.
                    // Dijadwalkan via main looper supaya tetap jalan walau thread mati.
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                            && openRetryCount < MAX_OPEN_RETRIES) {
                        openRetryCount++;
                        Log.i(TAG, "retry open camera (" + openRetryCount + ")");
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> attemptOpenCamera(context, intent, cameraId,
                                        outFile, durationMs, recordAudio, bitrate, fps, myGen), 800);
                        return;
                    }
                    failAttempt(myGen, outFile, "Failed opening camera, error code: " + error
                            + " (retry " + openRetryCount + "x)."
                            + " Kamera tersedia: " + availableCameras(manager));
                }
            }, bgHandler());
        } catch (Exception e) {
            if (myGen != sessionGen) return;
            Log.e(TAG, "open camera error", e);
            failAttempt(myGen, outFile, "Error opening camera: " + e.getMessage());
        }
    }

    private String availableCameras(CameraManager manager) {
        try {
            return Arrays.toString(manager.getCameraIdList());
        } catch (Exception e) {
            return "?";
        }
    }

    /** Gagal total: hapus file setengah-jadi supaya nama bisa dipakai ulang. */
    private void failAttempt(long myGen, File outFile, String error) {
        if (myGen != sessionGen) return; // basi
        isAttemptInFlight = false;
        if (outFile != null && outFile.exists() && !isRecording) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
        StatusFile.write("", error);
        cleanup();
        stopSelf();
    }

    private void startSession(final Context context, final Intent intent,
                              final CameraDevice camera,
                              final CameraCharacteristics characteristics,
                              final File outputFile, final int durationMs,
                              final boolean recordAudio, final int bitrate,
                              final int fps, final long myGen) throws Exception {

        // Pilih ukuran video terbesar yang muat 1080p.
        android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        List<Size> sizes = new ArrayList<>(Arrays.asList(map.getOutputSizes(MediaRecorder.class)));
        Size chosen = sizes.get(0);
        for (Size s : sizes) {
            boolean chosenOverCap = chosen.getHeight() > 1080;
            boolean sFits = s.getHeight() <= 1080;
            if ((sFits && (long) s.getWidth() * s.getHeight()
                    > (long) chosen.getWidth() * chosen.getHeight())
                    || (chosenOverCap && sFits)) {
                chosen = s;
            }
        }
        final Size videoSize = chosen;

        mediaRecorder = new MediaRecorder();
        if (recordAudio) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (recordAudio) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoFrameRate(fps);
        mediaRecorder.setVideoEncodingBitRate(bitrate);
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        if (durationMs > 0) {
            mediaRecorder.setMaxDuration(durationMs);
        }
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                Log.i(TAG, "limit reached, stopping");
                String done = isRecording && file != null
                        ? "Recording finished (batas tercapai): " + file.getAbsolutePath()
                        : "Recording finished (batas tercapai)";
                cleanup();
                StatusFile.write(done, null);
                stopSelf();
            }
        });
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "recorder error what=" + what + " extra=" + extra);
            String path = file != null ? file.getAbsolutePath() : "?";
            cleanup();
            StatusFile.write("", "Recorder error what=" + what + " extra=" + extra + " file=" + path);
            stopSelf();
        });
        mediaRecorder.prepare();

        final Surface recorderSurface = mediaRecorder.getSurface();
        final List<Surface> surfaces = new ArrayList<>();
        surfaces.add(recorderSurface);

        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                if (myGen != sessionGen) {
                    try { session.close(); } catch (RuntimeException ignored) {}
                    return;
                }
                captureSession = session;
                try {
                    CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    req.addTarget(recorderSurface);
                    req.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    session.setRepeatingRequest(req.build(), null, bgHandler());

                    mediaRecorder.start();
                    isRecording = true;
                    isAttemptInFlight = false;
                    recordStartMs = System.currentTimeMillis();
                    goForeground();

                    StatusFile.write("Recording started: " + outputFile.getAbsolutePath()
                            + "\nSize: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                            + "\nMax Duration: " + (durationMs <= 0 ? "unlimited" : (durationMs / 1000) + "s"), null);
                } catch (Exception e) {
                    if (myGen != sessionGen) return;
                    Log.e(TAG, "onConfigured error", e);
                    isAttemptInFlight = false;
                    StatusFile.write("", "Error starting capture: " + e.getMessage());
                    cleanup();
                    stopSelf();
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                if (myGen != sessionGen) return;
                Log.e(TAG, "session configure failed");
                isAttemptInFlight = false;
                StatusFile.write("", "Camera session configuration failed");
                cleanup();
                stopSelf();
            }
        }, bgHandler());
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private static void cleanup() {
        if (isRecording) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException e) {
                Log.i(TAG, "stop threw: " + e.getMessage());
            }
            isRecording = false;
        }
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (RuntimeException ignored) {
            }
            captureSession = null;
        }
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (RuntimeException ignored) {
            }
            cameraDevice = null;
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
        }
    }
}
