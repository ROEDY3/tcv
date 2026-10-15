package com.tcv;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Pipa frame JPEG ke Python di HP yang sama via TCP localhost.
 *
 * Kenapa bukan soket Unix abstract: proot-glibc memblokirnya
 * (Permission denied). Kenapa bukan soket file: API publik Android
 * tidak menyediakannya. TCP 127.0.0.1 terbukti jalan di env glibc
 * (pola yang sama dipakai IP Webcam), tetap 100% di dalam HP:
 * tanpa WiFi, tak terlihat dari luar.
 *
 * Protokol per frame: 4 byte big-endian panjang + isi JPEG.
 */
public class FramePipe {

    private static final String TAG = "TCV";

    private ImageReader imageReader;
    private ServerSocket server;
    private volatile boolean running = false;
    private Thread serverThread;

    private final Object frameLock = new Object();
    private byte[] latestJpeg = null;
    private int frameId = 0;
    private long lastPushMs = 0;

    private volatile int viewers = 0;
    private volatile int width;
    private volatile int height;
    private volatile int port;

    private long minIntervalMs = 100; // ~10 fps default

    public void setTargetFps(int fps) {
        if (fps > 0 && fps <= 30) minIntervalMs = 1000 / fps;
    }

    public synchronized void start(int port, int w, int h, Handler bgHandler) throws Exception {
        if (running) return;
        this.port = port;
        width = w;
        height = h;
        imageReader = ImageReader.newInstance(w, h, android.graphics.ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (now - lastPushMs < minIntervalMs) return;
                lastPushMs = now;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                synchronized (frameLock) {
                    latestJpeg = data;
                    frameId++;
                    frameLock.notifyAll();
                }
            } catch (Exception e) {
                Log.i(TAG, "frame skip: " + e.getMessage());
            } finally {
                if (img != null) img.close();
            }
        }, bgHandler);

        server = new ServerSocket();
        server.setReuseAddress(true); // pipa lama yang nyangkut langsung bisa dipakai ulang
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        running = true;
        serverThread = new Thread(this::acceptLoop, "TCVFrames");
        serverThread.start();
        Log.i(TAG, "FramePipe on 127.0.0.1:" + port + " " + w + "x" + h);
    }

    public Surface surface() {
        return imageReader != null ? imageReader.getSurface() : null;
    }

    public int getViewers() {
        return viewers;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                viewers++;
                serveClient(client);
            } catch (Exception e) {
                if (running) Log.i(TAG, "accept: " + e.getMessage());
                closeQuietly(client);
            } finally {
                if (viewers > 0) viewers--;
            }
        }
    }

    private void serveClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            OutputStream os = client.getOutputStream();
            InputStream is = client.getInputStream();
            int lastSeen = -1;
            byte[] lenBuf = new byte[4];
            while (running && client.isConnected() && !client.isClosed()) {
                byte[] jpeg;
                int id;
                synchronized (frameLock) {
                    while (running && frameId == lastSeen) {
                        try {
                            frameLock.wait(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (!running) return;
                    jpeg = latestJpeg;
                    id = frameId;
                }
                if (jpeg == null) continue;
                lastSeen = id;
                lenBuf[0] = (byte) ((jpeg.length >> 24) & 0xFF);
                lenBuf[1] = (byte) ((jpeg.length >> 16) & 0xFF);
                lenBuf[2] = (byte) ((jpeg.length >> 8) & 0xFF);
                lenBuf[3] = (byte) (jpeg.length & 0xFF);
                os.write(lenBuf);
                os.write(jpeg);
                os.flush();
                if (is.available() > 0) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "client done: " + e.getMessage());
        } finally {
            closeQuietly(client);
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
        latestJpeg = null;
        viewers = 0;
        Log.i(TAG, "FramePipe off");
    }

    public boolean isRunning() {
        return running;
    }
}
