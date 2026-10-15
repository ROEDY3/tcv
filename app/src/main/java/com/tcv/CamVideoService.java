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
 * Dua pekerjaan bisa jalan di SATU sesi kamera:
 *  - rekam file MP4  (record/quit)
 *  - pipa frame JPEG ke Python via soket Unix (stream_start/stream_stop)
 *
 * Aksi: start | stop | record | info | quit | stream_start | stream_stop
 */
public class CamVideoService extends Service {

    private static final String TAG = "TCV";
    private static final String CHANNEL_ID = "tcv_recording";
    private static final int NOTIF_ID = 1;

    private static final int MIN_DURATION_LIMIT = 1000;
    private static final int DEFAULT_DURATION_LIMIT = 1000 * 60 * 15; // 15 menit

    public static boolean serviceAlive = false;
    public static boolean streamOn = false;

    private static CameraDevice cameraDevice;
    private static CameraCaptureSession captureSession;
    private static MediaRecorder mediaRecorder;
    private static Surface recorderSurface;
    private static HandlerThread backgroundThread;
    private static ParcelFileDescriptor videoPfd;
    private static Uri videoUri;
    private static FramePipe pipe;

    private static boolean isRecording = false;
    private static boolean wantRecord = false;
    private static boolean isAttemptInFlight = false;
    private static String currentShellPath = null;
    private static long recordStartMs = 0;
    private static int openRetryCount = 0;
    private static final int MAX_OPEN_RETRIES = 3;
    private static long sessionGen = 0;
    private static long attemptGen = 0;

    // Parameter rekaman yang menunggu/menjalankan.
    private static String recCameraId = "0";
    private static String recMovieName = null;
    private static int recDurationMs = DEFAULT_DURATION_LIMIT;
    private static boolean recAudio = true;
    private static int recBitrate = 10_000_000;
    private static int recFps = 30;

    private static int streamW = 640;
    private static int streamH = 480;
    private static int streamFps = 10;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
    }

    @Override
    public void onDestroy() {
        stopPipe();
        cleanupRecording();
        serviceAlive = false;
        streamOn = false;
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
                wantRecord = false;
                wantStreamOff();
                cleanupRecording();
                releaseCameraIfIdle();
                StatusFile.write(context, "Layanan TCV dimatikan.", null);
                stopSelf();
                break;
            case "record":
                handleRecord(context, intent);
                break;
            case "info":
                StatusFile.writeInfo(context, isRecording,
                        isRecording && currentShellPath != null
                                ? currentShellPath + elapsedExtra() : null,
                        streamOn, streamInfo());
                break;
            case "quit":
                sessionGen++;
                isAttemptInFlight = false;
                wantRecord = false;
                String msg = isRecording && currentShellPath != null
                        ? "Recording finished: " + currentShellPath
                        : "No recording to stop";
                cleanupRecording();
                rebuildOrRelease();
                StatusFile.write(context, msg, null);
                goForeground(statusLine());
                break;
            case "stream_start":
                handleStreamStart(context, intent);
                break;
            case "stream_stop":
                wantStreamOff();
                rebuildOrRelease();
                StatusFile.write(context, "Stream dimatikan.", null);
                goForeground(statusLine());
                break;
            default:
                StatusFile.write(context, "", "Unknown command: " + command);
                break;
        }
        return START_STICKY;
    }

    private String elapsedExtra() {
        long elapsed = (System.currentTimeMillis() - recordStartMs) / 1000;
        return " (jalan " + elapsed + " dtk)";
    }

    private String statusLine() {
        if (isRecording && streamOn) return "Merekam + streaming...";
        if (isRecording) return "Merekam...";
        if (streamOn) return "Streaming...";
        return "Siaga — siap menerima perintah camvideo";
    }

    private String streamInfo() {
        if (!streamOn || pipe == null) return null;
        return "socket=" + FramePipe.SOCKET_NAME
                + " " + pipe.getWidth() + "x" + pipe.getHeight()
                + " penonton=" + pipe.getViewers();
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
        startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    // ---------------- REKAM ----------------

    private void handleRecord(final Context context, final Intent intent) {
        if (isRecording || isAttemptInFlight) {
            StatusFile.write(context, "", "Sibuk: sudah ada rekaman/percobaan berjalan. Tunggu atau kirim quit dulu.");
            return;
        }

        recCameraId = intent.getStringExtra("camera");
        if (recCameraId == null) recCameraId = "0";
        recMovieName = MediaFiles.movieName(intent.getStringExtra("file"));

        int duration = intent.getIntExtra("limit", DEFAULT_DURATION_LIMIT);
        if (duration > 0 && duration < MIN_DURATION_LIMIT) duration = MIN_DURATION_LIMIT;
        recDurationMs = duration;
        recAudio = intent.getBooleanExtra("audio", true);
        recBitrate = intent.getIntExtra("bitrate", 10_000_000);
        recFps = intent.getIntExtra("fps", 30);

        wantRecord = true;
        openRetryCount = 0;
        sessionGen++;
        final long myGen = sessionGen;
        isAttemptInFlight = true;
        attemptOpenCamera(context, recCameraId, myGen);
    }

    // ---------------- STREAM ----------------

    private void handleStreamStart(final Context context, final Intent intent) {
        if (streamOn) {
            StatusFile.write(context, "", "Stream sudah jalan. Matikan dulu (stream_stop) untuk ganti setting.");
            return;
        }
        streamW = intent.getIntExtra("sw", 640);
        streamH = intent.getIntExtra("sh", 480);
        streamFps = intent.getIntExtra("sfps", 10);
        if (streamW <= 0 || streamW > 1920) streamW = 640;
        if (streamH <= 0 || streamH > 1080) streamH = 480;
        if (streamFps <= 0 || streamFps > 30) streamFps = 10;

        try {
            if (pipe == null) pipe = new FramePipe();
            pipe.setTargetFps(streamFps);
            pipe.start(streamW, streamH, bgHandler());
        } catch (Exception e) {
            Log.e(TAG, "pipe start gagal", e);
            StatusFile.write(context, "", "Gagal menyalakan pipa frame: " + e.getMessage());
            return;
        }
        streamOn = true;
        sessionGen++;
        final long myGen = sessionGen;
        isAttemptInFlight = true;
        if (cameraDevice != null) {
            buildSession(myGen, nextAttempt());
        } else {
            openRetryCount = 0;
            attemptOpenCamera(context, streamCameraId(), myGen);
        }
        goForeground(statusLine());
    }

    private String streamCameraId() {
        return (wantRecord ? recCameraId : "0");
    }

    private void wantStreamOff() {
        streamOn = false;
        stopPipe();
    }

    private void stopPipe() {
        if (pipe != null) {
            try {
                pipe.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- KAMERA (gabungan) ----------------

    /** Handler yang selalu valid: bikin ulang thread kalau sudah mati. */
    private static synchronized Handler bgHandler() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("TCVBackground");
            backgroundThread.start();
        }
        return new Handler(backgroundThread.getLooper());
    }

    private static synchronized long nextAttempt() {
        attemptGen++;
        return attemptGen;
    }

    private void attemptOpenCamera(final Context context, final String cameraId, final long myGen) {
        if (myGen != sessionGen) return;
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
                        if (wantRecord && mediaRecorder == null) {
                            prepareRecorder(context);
                        }
                        buildSession(myGen, myAttempt);
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
                                () -> attemptOpenCamera(context, cameraId, myGen), 800);
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

    /** Siapkan MediaRecorder (tanpa start). Dipanggil sekali per perintah record. */
    private void prepareRecorder(Context context) throws Exception {
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(recCameraId);

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

        MediaFiles.VideoSlot slot = MediaFiles.createMovie(context, recMovieName);
        videoUri = slot.uri;
        videoPfd = slot.pfd;
        currentShellPath = slot.shellPath;

        mediaRecorder = new MediaRecorder();
        if (recAudio) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (recAudio) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoFrameRate(recFps);
        mediaRecorder.setVideoEncodingBitRate(recBitrate);
        mediaRecorder.setOutputFile(videoPfd.getFileDescriptor());
        if (recDurationMs > 0) {
            mediaRecorder.setMaxDuration(recDurationMs);
        }
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                Log.i(TAG, "limit reached, stopping record (stream tetap jalan bila aktif)");
                wantRecord = false;
                String done = "Recording finished (batas tercapai): " + currentShellPath;
                cleanupRecording();
                rebuildOrRelease();
                StatusFile.write(getApplicationContext(), done, null);
                goForeground(statusLine());
            }
        });
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "recorder error what=" + what + " extra=" + extra);
            Context ctx = getApplicationContext();
            wantRecord = false;
            if (videoUri != null) {
                MediaFiles.deleteVideo(ctx, videoUri);
                videoUri = null;
            }
            cleanupRecording();
            rebuildOrRelease();
            StatusFile.write(ctx, "", "Recorder error what=" + what + " extra=" + extra);
            goForeground(statusLine());
        });
        mediaRecorder.prepare();
        recorderSurface = mediaRecorder.getSurface();
        Log.i(TAG, "recorder siap: " + currentShellPath + " " + videoSize.getWidth() + "x" + videoSize.getHeight());
    }

    /** Bangun (ulang) sesi capture sesuai kebutuhan saat ini. */
    private void buildSession(final long myGen, final long myAttempt) {
        if (myGen != sessionGen || myAttempt != attemptGen) return;
        if (cameraDevice == null) return;
        final List<Surface> surfaces = new ArrayList<>();
        if (wantRecord && recorderSurface != null) surfaces.add(recorderSurface);
        if (streamOn && pipe != null && pipe.surface() != null) surfaces.add(pipe.surface());
        if (surfaces.isEmpty()) return;
        try {
            if (captureSession != null) {
                try { captureSession.close(); } catch (RuntimeException ignored) {}
                captureSession = null;
            }
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (myGen != sessionGen || myAttempt != attemptGen) {
                        try { session.close(); } catch (RuntimeException ignored) {}
                        return;
                    }
                    captureSession = session;
                    try {
                        CaptureRequest.Builder req = cameraDevice.createCaptureRequest(
                                isRecording || wantRecord
                                        ? CameraDevice.TEMPLATE_RECORD
                                        : CameraDevice.TEMPLATE_PREVIEW);
                        for (Surface s : surfaces) req.addTarget(s);
                        req.set(CaptureRequest.CONTROL_AF_MODE,
                                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        session.setRepeatingRequest(req.build(), null, bgHandler());

                        Context context = getApplicationContext();
                        if (wantRecord && !isRecording && mediaRecorder != null) {
                            mediaRecorder.start();
                            isRecording = true;
                            recordStartMs = System.currentTimeMillis();
                        }
                        isAttemptInFlight = false;
                        goForeground(statusLine());

                        if (wantRecord && isRecording && currentShellPath != null) {
                            StatusFile.write(context, "Recording started: " + currentShellPath
                                    + (streamOn ? "\n+ streaming via " + FramePipe.SOCKET_NAME : "")
                                    + "\nMax Duration: " + (recDurationMs <= 0 ? "unlimited"
                                    : (recDurationMs / 1000) + "s"), null);
                        } else if (streamOn) {
                            StatusFile.write(context, "Stream jalan: soket " + FramePipe.SOCKET_NAME
                                    + " (" + streamW + "x" + streamH + ")", null);
                        }
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
        } catch (Exception e) {
            if (myGen != sessionGen || myAttempt != attemptGen) return;
            failAttempt(myGen, myAttempt, "Error building session: " + e.getMessage());
        }
    }

    /** Setelah mode berubah: bangun ulang sesi, atau lepas kamera bila menganggur. */
    private void rebuildOrRelease() {
        if (cameraDevice == null) return;
        if (!wantRecord && !streamOn) {
            releaseCameraIfIdle();
            return;
        }
        sessionGen++;
        buildSession(sessionGen, nextAttempt());
    }

    private void releaseCameraIfIdle() {
        if (isRecording || wantRecord || streamOn) return;
        if (captureSession != null) {
            try { captureSession.close(); } catch (RuntimeException ignored) {}
            captureSession = null;
        }
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (RuntimeException ignored) {}
            cameraDevice = null;
        }
        Log.i(TAG, "kamera dilepas (menganggur)");
    }

    /** Gagal total: hapus file setengah-jadi supaya nama bisa dipakai ulang. */
    private void failAttempt(long myGen, long myAttempt, String error) {
        if (myGen != sessionGen || myAttempt != attemptGen) return;
        isAttemptInFlight = false;
        wantRecord = false;
        Context context = getApplicationContext();
        if (!isRecording && videoUri != null) {
            MediaFiles.deleteVideo(context, videoUri);
            videoUri = null;
        }
        StatusFile.write(context, "", error);
        cleanupRecording();
        goForeground(statusLine());
    }

    /** Berhenti merekam saja (stream/service tidak diganggu). */
    private static void cleanupRecording() {
        if (isRecording) {
            try {
                if (mediaRecorder != null) mediaRecorder.stop();
            } catch (RuntimeException e) {
                Log.i(TAG, "stop threw: " + e.getMessage());
            }
            isRecording = false;
        }
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }
        recorderSurface = null;
        if (videoPfd != null) {
            try {
                videoPfd.close();
            } catch (Exception ignored) {
            }
            videoPfd = null;
        }
        videoUri = null;
        currentShellPath = null;
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (RuntimeException ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null && !streamOn && !wantRecord) {
            try {
                cameraDevice.close();
            } catch (RuntimeException ignored) {
            }
            cameraDevice = null;
        }
        if (backgroundThread != null && cameraDevice == null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
        }
    }

    private static void cleanup() {
        wantRecord = false;
        cleanupRecording();
    }
}
