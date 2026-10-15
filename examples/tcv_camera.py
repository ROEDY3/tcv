#!/data/data/com.termux/files/usr/bin/python3
"""TcvCamera — baca frame JPEG dari aplikasi TCV Camera via TCP localhost.

Pengganti cv2.VideoCapture("http://...:8080/video") tanpa WiFi,
tanpa aplikasi ketiga. Interface mirip VideoCapture sehingga
kode lama hampir tidak berubah:

    from tcv_camera import TcvCamera
    cap = TcvCamera()            # ganti: cv2.VideoCapture(url)
    ok, frame = cap.read()       # frame = bytes JPEG
    # decode ke OpenCV bila perlu:
    #   import numpy as np, cv2
    #   img = cv2.imdecode(np.frombuffer(frame, np.uint8), cv2.IMREAD_COLOR)

Jalan di Python bionic maupun Python glibc (proot): TCP localhost
lolos di keduanya (terbukti oleh pola IP Webcam).

Kontrol stream (nyala/mati) tetap dari shell: camvideo -s / camvideo -S
"""

import socket
import struct
import time

HOST = "127.0.0.1"
PORT = 8090


class TcvCamera:
    """Mirip cv2.VideoCapture tapi sumbernya 127.0.0.1:8090 com.tcv."""

    def __init__(self, host=HOST, port=PORT, timeout=10):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.settimeout(timeout)
        self._sock.connect((host, port))
        self._sock.settimeout(timeout)
        self._opened = True

    def isOpened(self):
        return self._opened

    def _recvn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("stream terputus")
            buf += chunk
        return buf

    def read_jpeg(self):
        """Kembalikan (ok, bytes_jpeg)."""
        try:
            (length,) = struct.unpack(">I", self._recvn(4))
            if length <= 0 or length > 5_000_000:
                return False, None
            return True, self._recvn(length)
        except (ConnectionError, socket.timeout, OSError):
            self._opened = False
            return False, None

    def read(self):
        """Alias gaya OpenCV: (ok, bytes_jpeg)."""
        return self.read_jpeg()

    def release(self):
        self._opened = False
        try:
            self._sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.release()


if __name__ == "__main__":
    # Tes cepat: baca 5 frame, tampilkan ukurannya.
    print("menghubungi", f"{HOST}:{PORT}", "...")
    t0 = time.time()
    with TcvCamera() as cam:
        for i in range(5):
            ok, jpg = cam.read()
            dt = time.time() - t0
            print(f"frame {i}: ok={ok} size={len(jpg) if jpg else 0} t={dt:.1f}s")
    print("selesai")
