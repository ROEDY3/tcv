package com.tcv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service permanen (foreground, tipe camera|microphone).
 *
 * Pola standar Android 16: service dinyalakan SEKALI dari layar aplikasi
 * (saat aplikasi terlihat -> selalu diizinkan sistem), lalu perintah
 * record/info/quit dari Termux hanya menitip pesan ke service yang hidup.
 *
 * Aksi: start | stop | record | info | quit
 */
public class CamVideoService extends Service {

    private static final String TAG = "TCV";
    private static final String CHANNEL_ID = "tcv_recording";
    private static final int NOTIF_ID = 1;

    private static final int MIN_DURATION_LIMIT = 1000;
    private static final int DEFAULT_DURATION_LIMIT = 1000 * 60 * 15; // 15 menit

    /** True selama proses service hidup (reset otomatis kalau proses mati). */
    public static boolean serviceAlive = false;

    private static CameraDevice cameraDevice;
    private static CameraCaptureSession captureSession;
    private static MediaRecorder mediaRecorder;
    private static HandlerThread backgroundThread;
    private static ParcelFileDescriptor videoPfd;
    private static Uri videoUri;

    private static boolean isRecording = false;
    private static boolean isAttemptInFlight = false;
    private static String currentShellPath = null;
    private static long recordStartMs = 0;
    private static int openRetryCount = 0;
    private static final int MAX_OPEN_RETRIES = 3;
    private static long sessionGen = 0;
    private static long attemptGen = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
    }

    @Override
    public void onDestroy() {
        cleanup();
        serviceAlive = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(statusLine());
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }
        String command = intent.getAction();
        Log.i(TAG, "command: " + command);
        Context context = getApplicationContext();
        switch (command) {
            case "start":
                StatusFile.write(context, "Layanan TCV aktif dan siaga.", null);
                break;
            case "stop":
                sessionGen++;
                isAttemptInFlight = false;
                cleanup();
                StatusFile.write(context, "Layanan TCV dimatikan.", null);
                stopSelf();
                break;
            case "record":
                handleRecord(context, intent);
                break;
            case "info":
                String extra = "";
                if (isRecording) {
                    long elapsed = (System.currentTimeMillis() - recordStartMs) / 1000;
                    extra = " (jalan " + elapsed + " dtk)";
                }
                StatusFile.writeInfo(context, isRecording,
                        isRecording && currentShellPath != null ? currentShellPath + extra : null);
                break;
            case "quit":
                sessionGen++; // batalkan retry yang mungkin terjadwal
                isAttemptInFlight = false;
                String msg = isRecording && currentShellPath != null
                        ? "Recording finished: " + currentShellPath
                        : "No recording to stop";
                cleanupRecording();
                StatusFile.write(context, msg, null);
                goForeground(statusLine());
                break;
            default:
                StatusFile.write(context, "", "Unknown command: " + command);
                break;
        }
        return START_STICKY;
    }

    private String statusLine() {
        return isRecording ? "Merekam..." : "Siaga — siap menerima perintah camvideo";
    }

    private void goForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "TCV Recording",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TCV Camera")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        // minSdk 29: varian 3-arg tersedia.
        startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    private void handleRecord(final Context context, final Intent intent) {
        if (isRecording || isAttemptInFlight) {
            StatusFile.write(context, "", "Sibuk: sudah ada rekaman/percobaan berjalan. Tunggu atau kirim quit dulu.");
            return;
        }

        String cameraId = intent.getStringExtra("camera");
        if (cameraId == null) cameraId = "0";

        final String movieName = MediaFiles.movieName(intent.getStringExtra("file"));

        int duration = intent.getIntExtra("limit", DEFAULT_DURATION_LIMIT);
        if (duration > 0 && duration < MIN_DURATION_LIMIT) duration = MIN_DURATION_LIMIT;
        final int durationMs = duration;

        final boolean recordAudio = intent.getBooleanExtra("audio", true);
        final int bitrate = intent.getIntExtra("bitrate", 10_000_000);
        final int fps = intent.getIntExtra("fps", 30);

        openRetryCount = 0;
        sessionGen++;
        final long myGen = sessionGen;
        isAttemptInFlight = true;
        attemptOpenCamera(context, intent, cameraId, movieName, durationMs, recordAudio, bitrate, fps, myGen);
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
                                   final String cameraId, final String movieName,
                                   final int durationMs, final boolean recordAudio,
                                   final int bitrate, final int fps, final long myGen) {
        if (myGen != sessionGen) return; // basi
        attemptGen++;
        final long myAttempt = attemptGen;
        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            //noinspection MissingPermission
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (myGen != sessionGen || myAttempt != attemptGen) {
                        try { camera.close(); } catch (RuntimeException ignored) {}
                        return;
                    }
                    cameraDevice = camera;
                    try {
                        final CameraCharacteristics characteristics =
                                manager.getCameraCharacteristics(cameraId);
                        startSession(context, intent, camera, characteristics, movieName,
                                durationMs, recordAudio, bitrate, fps, myGen, myAttempt);
                    } catch (Exception e) {
                        Log.e(TAG, "start session error", e);
                        failAttempt(myGen, myAttempt, "Error starting recording: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    if (myGen != sessionGen || myAttempt != attemptGen) return;
                    Log.i(TAG, "camera disconnected");
                    failAttempt(myGen, myAttempt, "Camera disconnected tiba-tiba.");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    if (myGen != sessionGen || myAttempt != attemptGen) return;
                    Log.e(TAG, "camera error: " + error);
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                            && openRetryCount < MAX_OPEN_RETRIES) {
                        openRetryCount++;
                        Log.i(TAG, "retry open camera (" + openRetryCount + ")");
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> attemptOpenCamera(context, intent, cameraId,
                                        movieName, durationMs, recordAudio, bitrate, fps, myGen), 800);
                        return;
                    }
                    failAttempt(myGen, myAttempt, "Failed opening camera, error code: " + error
                            + " (retry " + openRetryCount + "x)."
                            + " Kamera tersedia: " + availableCameras(manager));
                }
            }, bgHandler());
        } catch (Exception e) {
            if (myGen != sessionGen || myAttempt != attemptGen) return;
            Log.e(TAG, "open camera error", e);
            failAttempt(myGen, myAttempt, "Error opening camera: " + e.getMessage());
        }
    }

    private String availableCameras(CameraManager manager) {
        try {
            return Arrays.toString(manager.getCameraIdList());
        } catch (Exception e) {
            return "?";
        }
    }

    /** Gagal total: hapus baris video setengah-jadi supaya nama bisa dipakai ulang. */
    private void failAttempt(long myGen, long myAttempt, String error) {
        if (myGen != sessionGen || myAttempt != attemptGen) return;
        isAttemptInFlight = false;
        Context context = getApplicationContext();
        if (!isRecording && videoUri != null) {
            MediaFiles.deleteVideo(context, videoUri);
            videoUri = null;
        }
        StatusFile.write(context, "", error);
        cleanupRecording();
        goForeground(statusLine());
    }

    private void startSession(final Context context, final Intent intent,
                              final CameraDevice camera,
                              final CameraCharacteristics characteristics,
                              final String movieName, final int durationMs,
                              final boolean recordAudio, final int bitrate,
                              final int fps, final long myGen, final long myAttempt) throws Exception {

        List<Size> sizes = new ArrayList<>(Arrays.asList(
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(MediaRecorder.class)));
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

        MediaFiles.VideoSlot slot = MediaFiles.createMovie(context, movieName);
        videoUri = slot.uri;
        videoPfd = slot.pfd;
        currentShellPath = slot.shellPath;

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
        mediaRecorder.setOutputFile(videoPfd.getFileDescriptor());
        if (durationMs > 0) {
            mediaRecorder.setMaxDuration(durationMs);
        }
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            if (myGen != sessionGen || myAttempt != attemptGen) return;
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                Log.i(TAG, "limit reached, stopping");
                String done = "Recording finished (batas tercapai): " + currentShellPath;
                cleanupRecording();
                StatusFile.write(getApplicationContext(), done, null);
                goForeground(statusLine());
            }
        });
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            if (myGen != sessionGen || myAttempt != attemptGen) return;
            Log.e(TAG, "recorder error what=" + what + " extra=" + extra);
            Context ctx = getApplicationContext();
            if (videoUri != null) {
                MediaFiles.deleteVideo(ctx, videoUri);
                videoUri = null;
            }
            cleanupRecording();
            StatusFile.write(ctx, "", "Recorder error what=" + what + " extra=" + extra);
            goForeground(statusLine());
        });
        mediaRecorder.prepare();

        final Surface recorderSurface = mediaRecorder.getSurface();
        final List<Surface> surfaces = new ArrayList<>();
        surfaces.add(recorderSurface);

        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                if (myGen != sessionGen || myAttempt != attemptGen) {
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
                    goForeground(statusLine());

                    StatusFile.write(context, "Recording started: " + currentShellPath
                            + "\nSize: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                            + "\nMax Duration: " + (durationMs <= 0 ? "unlimited" : (durationMs / 1000) + "s"), null);
                } catch (Exception e) {
                    if (myGen != sessionGen || myAttempt != attemptGen) return;
                    Log.e(TAG, "onConfigured error", e);
                    failAttempt(myGen, myAttempt, "Error starting capture: " + e.getMessage());
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                if (myGen != sessionGen || myAttempt != attemptGen) return;
                Log.e(TAG, "session configure failed");
                failAttempt(myGen, myAttempt, "Camera session configuration failed");
            }
        }, bgHandler());
    }

    /** Berhenti merekam saja (service tetap hidup siaga). */
    private static void cleanupRecording() {
        if (isRecording) {
            try {
                if (mediaRecorder != null) mediaRecorder.stop();
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
        if (videoPfd != null) {
            try {
                videoPfd.close();
            } catch (Exception ignored) {
            }
            videoPfd = null;
        }
        videoUri = null;
        currentShellPath = null;
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
        }
    }

    private static void cleanup() {
        cleanupRecording();
    }
}
