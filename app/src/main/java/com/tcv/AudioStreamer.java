package com.tcv;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Mic HP -> aliran PCM untuk OBS, via endpoint HTTP /audio.wav.
 * Format: WAV 16 kHz mono 16-bit dengan header "tak berujung".
 *
 * 1) Per-klien (masing-masing punya kursor).
 * 2) Ring kecil, bukan antrean 3 dtk (biar tidak telat).
 * 3) Klien baru mulai dari chunk BERIKUTNYA, bukan dari yang sudah lewat.
 */
public class AudioStreamer {

    private static final String TAG = "TCV";
    public static final int SAMPLE_RATE = 16000;

    private AudioRecord recorder;
    private android.media.audiofx.AcousticEchoCanceler aec;
    private volatile boolean running = false;
    private Thread readThread;

    private static class Chunk {
        final long seq;
        final byte[] data;

        Chunk(long seq, byte[] data) {
            this.seq = seq;
            this.data = data;
        }
    }

    private final Object bufLock = new Object();
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private volatile long seqCounter = 0;
    private static final int MAX_CHUNKS = 4; // ~80ms @16k mono (mimpi d chunks)

    public synchronized void start() throws Exception {
        if (running) return;
        int ch = AudioFormat.CHANNEL_IN_MONO;
        int enc = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, ch, enc);
        if (minBuf <= 0) throw new Exception("AudioRecord tidak didukung di HP ini");
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, ch, enc, minBuf * 4);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            throw new Exception("AudioRecord init gagal (mic dipakai app lain?)");
        }
        aec = android.media.audiofx.AcousticEchoCanceler.create(recorder.getAudioSessionId());
        if (aec != null) {
            aec.setEnabled(true);
            Log.i(TAG, "AEC enabled");
        }
        synchronized (bufLock) {
            seqCounter = 0;
            chunks.clear();
        }
        recorder.startRecording();
        running = true;
        readThread = new Thread(this::readLoop, "TCVAudio");
        readThread.start();
        Log.i(TAG, "AudioStreamer on 16kHz mono raw (ring kecil)");
    }

    private void readLoop() {
        byte[] buf = new byte[640]; // 20ms @16k
        while (running) {
            try {
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    byte[] copy = new byte[n];
                    System.arraycopy(buf, 0, copy, 0, n);
                    synchronized (bufLock) {
                        long id = seqCounter++;
                        chunks.addLast(new Chunk(id, copy));
                        while (chunks.size() > MAX_CHUNKS) chunks.removeFirst();
                        bufLock.notifyAll();
                    }
                } else if (n < 0) {
                    Log.i(TAG, "audio read error: " + n);
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                Log.i(TAG, "audio loop: " + e.getMessage());
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
    }

    public long currentSeq() {
        synchronized (bufLock) {
            return seqCounter;
        }
    }

    private List<Chunk> takeSince(long lastSeq) {
        synchronized (bufLock) {
            if (chunks.isEmpty() && running) {
                try {
                    bufLock.wait(100);
                } catch (InterruptedException ignored) {}
            }
            List<Chunk> out = new ArrayList<>();
            for (Chunk c : chunks) {
                if (c.seq > lastSeq) out.add(c);
            }
            // Klien tertinggal: buang tumpukan, ikuti terbaru.
            if (out.size() > 3 && !out.isEmpty()) {
                Chunk newest = out.get(out.size() - 1);
                out.clear();
                out.add(newest);
            }
            return out;
        }
    }

    /** Header WAV "live". */
    public static void writeWavHeader(OutputStream os) throws Exception {
        int dataLen = 0x7FFFF000;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putInt(h, 4, 36 + dataLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putInt(h, 16, 16);
        putShort(h, 20, (short) 1); // PCM
        putShort(h, 22, (short) 1); // mono
        putInt(h, 24, SAMPLE_RATE);
        putInt(h, 28, SAMPLE_RATE * 2);
        putShort(h, 32, (short) 2);
        putShort(h, 34, (short) 16);
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putInt(h, 40, dataLen);
        os.write(h);
        os.flush();
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /**
     * Kirim ke satu klien. Mulai dari chunk yang AKAN datang setelah konek.
     */
    public void serveClient(OutputStream os) {
        try {
            writeWavHeader(os);
            long lastSeq = currentSeq();
            while (running) {
                List<Chunk> batch = takeSince(lastSeq);
                if (batch.isEmpty()) continue;
                for (Chunk c : batch) {
                    os.write(c.data);
                    lastSeq = c.seq;
                }
                os.flush();
            }
        } catch (Exception e) {
            Log.i(TAG, "audio client done: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        synchronized (bufLock) {
            bufLock.notifyAll();
            chunks.clear();
        }
        if (aec != null) {
            try { aec.release(); } catch (Exception ignored) {}
            aec = null;
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        readThread = null;
        Log.i(TAG, "AudioStreamer off");
    }

    public boolean isRunning() {
        return running;
    }
}
