package com.tcv;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server HTTP mini di 127.0.0.1 untuk OBS (tanpa library):
 *   /mjpeg      -> video MJPEG multipart (dimakan OBS Media/VLC Source)
 *   /audio.wav  -> mic live format WAV (dimakan OBS Media Source audio)
 *   /           -> halaman info kecil (tes via browser)
 */
public class MjpegHttpServer {

    private static final String TAG = "TCV";
    private static final String BOUNDARY = "tcvframe";

    private ServerSocket server;
    private volatile boolean running = false;
    private Thread acceptThread;
    private volatile int port;
    private volatile int mjpegViewers = 0;
    private volatile int audioViewers = 0;

    private FramePipe pipe;
    private AudioStreamer audio;

    public synchronized void start(FramePipe pipe, AudioStreamer audio, int port) throws Exception {
        if (running) return;
        this.pipe = pipe;
        this.audio = audio;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        this.port = port;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "TCVHttp");
        acceptThread.start();
        Log.i(TAG, "HTTP on 127.0.0.1:" + port);
    }

    public int getPort() {
        return port;
    }

    public int getMjpegViewers() {
        return mjpegViewers;
    }

    public int getAudioViewers() {
        return audioViewers;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = server.accept();
                new Thread(() -> serve(c), "TCVHttpClient").start();
            } catch (Exception e) {
                if (running) Log.i(TAG, "http accept: " + e.getMessage());
            }
        }
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(10000);
            try {
                client.setTcpNoDelay(true);
                client.setSendBufferSize(1 << 18);
            } catch (Exception ignored) {
            }
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream os = client.getOutputStream();
            String request = in.readLine();
            if (request == null) return;
            // buang sisa header request
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // abaikan
            }
            Log.i(TAG, "http: " + request);
            if (request.startsWith("GET /mjpeg")) {
                serveMjpeg(os);
            } else if (request.startsWith("GET /audio")) {
                serveAudio(os);
            } else {
                serveIndex(os);
            }
        } catch (Exception e) {
            Log.i(TAG, "http client: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void serveIndex(OutputStream os) throws Exception {
        String body = "<html><body><h1>TCV Camera</h1>"
                + "<p><a href=\"/mjpeg\">/mjpeg</a> (video untuk OBS)</p>"
                + "<p><a href=\"/audio.wav\">/audio.wav</a> (mic untuk OBS)</p>"
                + "<p><img src=\"/mjpeg\" width=\"320\"></p>"
                + "</body></html>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.US_ASCII));
        os.write(b);
        os.flush();
    }

    private void serveMjpeg(OutputStream os) throws Exception {
        FramePipe p = pipe;
        if (p == null || !p.isRunning()) {
            String msg = "stream video belum jalan";
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            os.write(("HTTP/1.0 503 Busy\r\nContent-Type: text/plain\r\nContent-Length: "
                    + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(b);
            os.flush();
            return;
        }
        mjpegViewers++;
        try {
            String head = "HTTP/1.0 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n";
            os.write(head.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            int lastSeen = -1;
            while (running && p.isRunning()) {
                FramePipe.Frame f = p.awaitFrame(lastSeen, 2000);
                if (f == null) continue;
                lastSeen = f.id;
                String part = "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + f.jpeg.length + "\r\n\r\n";
                os.write(part.getBytes(StandardCharsets.US_ASCII));
                os.write(f.jpeg);
                os.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                os.flush();
            }
        } finally {
            if (mjpegViewers > 0) mjpegViewers--;
        }
    }

    private void serveAudio(OutputStream os) throws Exception {
        AudioStreamer a = audio;
        if (a == null || !a.isRunning()) {
            String msg = "stream audio belum jalan";
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            os.write(("HTTP/1.0 503 Busy\r\nContent-Type: text/plain\r\nContent-Length: "
                    + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(b);
            os.flush();
            return;
        }
        audioViewers++;
        try {
            String head = "HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n";
            os.write(head.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            a.serveClient(os);
        } finally {
            if (audioViewers > 0) audioViewers--;
        }
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
        acceptThread = null;
        mjpegViewers = 0;
        audioViewers = 0;
        Log.i(TAG, "HTTP off");
    }

    public boolean isRunning() {
        return running;
    }
}
