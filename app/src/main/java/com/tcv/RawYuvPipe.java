package com.tcv;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Pipa frame YUV mentah (NV12) ke OBS di HP yang sama via TCP localhost.
 *
 * Kamera mengeluarkan YUV langsung dari hardware (ImageReader YUV_420_888),
 * jadi TIDAK ada proses encode sama sekali — jauh lebih ringan daripada
 * FramePipe (JPEG). Dipakai oleh plugin OBS "tcv-capture" (port 8092).
 *
 * Protokol per frame (4 x uint32 big-endian, lalu payload):
 *   width, height, format (0=I420, 1=NV12), size (byte payload)
 * Payload = NV12 padat: bidang Y (w*h) lalu bidang UV selang-seling (w*h/2).
 */
public class RawYuvPipe {

    private static final String TAG = "TCV";

    private static final int FMT_NV12 = 1;

    private ImageReader imageReader;
    private ServerSocket server;
    private volatile boolean running = false;
    private Thread serverThread;

    private final Object frameLock = new Object();
    private byte[] shared = null;     // frame NV12 terakhir (dipakai ulang)
    private int frameSize = 0;
    private int frameId = 0;
    private long lastPushElapsed = 0;

    // buffer kerja untuk merapikan bidang chroma (dipakai ulang)
    private byte[] uTmp = null;
    private byte[] vTmp = null;

    // pengukuran fps nyata
    private long fpsWindowStart = 0;
    private int fpsWindowCount = 0;
    private volatile double measuredFps = 0.0;

    private volatile int viewers = 0;
    private volatile int width;
    private volatile int height;
    private volatile int port;

    private volatile long minIntervalMs = 1000 / 24;

    public void setTargetFps(int fps) {
        if (fps > 0 && fps <= 60) minIntervalMs = 1000 / fps;
    }

    public synchronized void start(int port, int w, int h, Handler bgHandler) throws Exception {
        if (running) return;
        this.port = port;
        width = w;
        height = h;

        imageReader = ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastPushElapsed < minIntervalMs) return;
                lastPushElapsed = now;

                synchronized (frameLock) {
                    if (shared == null) {
                        frameSize = w * h * 3 / 2;
                        shared = new byte[frameSize];
                    }
                    packNv12(img, shared);
                    frameId++;
                    if (fpsWindowStart == 0) fpsWindowStart = now;
                    fpsWindowCount++;
                    long span = now - fpsWindowStart;
                    if (span >= 1000) {
                        measuredFps = fpsWindowCount * 1000.0 / span;
                        fpsWindowStart = now;
                        fpsWindowCount = 0;
                    }
                    frameLock.notifyAll();
                }
            } catch (Exception e) {
                Log.i(TAG, "yuv frame skip: " + e.getMessage());
            } finally {
                if (img != null) img.close();
            }
        }, bgHandler);

        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        running = true;
        serverThread = new Thread(this::acceptLoop, "TCVRawYuv");
        serverThread.start();
        Log.i(TAG, "RawYuvPipe on 127.0.0.1:" + port + " " + w + "x" + h);
    }

    public Surface surface() {
        return imageReader != null ? imageReader.getSurface() : null;
    }

    public int getViewers() { return viewers; }

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    public int getPort() { return port; }

    /** Id frame terakhir (untuk watchdog: mendeteksi stream macet). */
    public int getFrameId() {
        synchronized (frameLock) {
            return frameId;
        }
    }

    public double getMeasuredFps() { return measuredFps; }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                viewers++;
                serveClient(client);
            } catch (Exception e) {
                if (running) Log.i(TAG, "yuv accept: " + e.getMessage());
                closeQuietly(client);
            } finally {
                if (viewers > 0) viewers--;
            }
        }
    }

    private void serveClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            try {
                client.setTcpNoDelay(true);
                client.setSendBufferSize(1 << 18);
            } catch (Exception ignored) {
            }
            OutputStream os = client.getOutputStream();
            byte[] lenBuf = new byte[16];
            byte[] local = new byte[frameSize > 0 ? frameSize : width * height * 3 / 2];
            int lastSeen = -1;
            while (running && client.isConnected() && !client.isClosed()) {
                int id;
                int len;
                synchronized (frameLock) {
                    long deadline = SystemClock.elapsedRealtime() + 2000;
                    while (running && frameId == lastSeen) {
                        long wait = deadline - SystemClock.elapsedRealtime();
                        if (wait <= 0) break;
                        try {
                            frameLock.wait(Math.min(wait, 500));
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (!running) break;
                    if (frameId == lastSeen || shared == null) continue;
                    id = frameId;
                    len = frameSize;
                    if (local.length != len) local = new byte[len];
                    System.arraycopy(shared, 0, local, 0, len);
                }
                lastSeen = id;
                writeU32(lenBuf, 0, width);
                writeU32(lenBuf, 4, height);
                writeU32(lenBuf, 8, FMT_NV12);
                writeU32(lenBuf, 12, len);
                os.write(lenBuf);
                os.write(local, 0, len);
                os.flush();
            }
        } catch (Exception e) {
            Log.i(TAG, "yuv client done: " + e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    private static void writeU32(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    /**
     * Kemas bidang YUV_420_888 menjadi NV12 padat.
     * Y selalu pixelStride 1. U/V punya rowStride & pixelStride sama.
     *  - pixelStride 2  : semi-planar (U dan V terjalin) -> NV12 apa adanya.
     *  - pixelStride 1  : planar -> U/V dijalin manual.
     */
    private void packNv12(Image image, byte[] out) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        Image.Plane yP = planes[0], uP = planes[1], vP = planes[2];
        ByteBuffer yB = yP.getBuffer(), uB = uP.getBuffer(), vB = vP.getBuffer();

        int yRow = yP.getRowStride();
        int ySize = w * h;

        // ---- Y ----
        if (yRow == w && yB.capacity() >= ySize) {
            yB.position(0);
            yB.get(out, 0, ySize);
        } else {
            for (int r = 0, pos = 0; r < h; r++, pos += w) {
                yB.position(r * yRow);
                yB.get(out, pos, w);
            }
        }

        // ---- Chroma -> NV12 (U,V selang-seling) ----
        int cRow = uP.getRowStride();
        int cPix = uP.getPixelStride();
        int cw = w / 2, ch = h / 2;
        int pos = ySize;

        if (cPix == 2) {
            // Semi-planar: salin seluruh bidang U (berisi UV terjalin) lalu
            // V = byte di sebelah U (U+1). Cukup satu salinan.
            int uCap = uB.capacity();
            if (uTmp == null || uTmp.length < uCap) uTmp = new byte[uCap];
            uB.position(0);
            uB.get(uTmp, 0, uCap);
            for (int r = 0; r < ch; r++) {
                int base = r * cRow;
                for (int c = 0; c < cw; c++) {
                    int off = base + c * 2;
                    out[pos++] = uTmp[off];
                    out[pos++] = (off + 1 < uCap) ? uTmp[off + 1] : uTmp[off];
                }
            }
        } else {
            // Planar: salin bidang U dan V, lalu jalin manual.
            int vRow = vP.getRowStride();
            int uCap = uB.capacity(), vCap = vB.capacity();
            if (uTmp == null || uTmp.length < uCap) uTmp = new byte[uCap];
            if (vTmp == null || vTmp.length < vCap) vTmp = new byte[vCap];
            uB.position(0);
            uB.get(uTmp, 0, uCap);
            vB.position(0);
            vB.get(vTmp, 0, vCap);
            for (int r = 0; r < ch; r++) {
                int ub = r * cRow, vb = r * vRow;
                for (int c = 0; c < cw; c++) {
                    out[pos++] = uTmp[ub + c];
                    out[pos++] = vTmp[vb + c];
                }
            }
        }
    }

    private void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    public synchronized void stop() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
        serverThread = null;
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        shared = null;
        frameId = 0;
        viewers = 0;
        Log.i(TAG, "RawYuvPipe off");
    }

    public boolean isRunning() {
        return running;
    }
}
