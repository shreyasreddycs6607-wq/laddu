#!/usr/bin/env python3
"""Laddu Raspberry Pi camera agent.

Captures frames from a USB webcam and serves them over HTTP so another device (a PC or phone running heavier AI) can
fetch them. Built for a Raspberry Pi B+ (single core, 512 MB): standard library only, ffmpeg does the capture, no
disk writes, no AI on the Pi.

  GET /health        JSON status (camera, frame age, CPU load, memory, temperature)
  GET /snapshot.jpg  latest frame
  GET /stream.mjpg   multipart MJPEG stream

Every request needs the token: header "Authorization: Bearer <token>" or "?token=<token>".
The server speaks plain HTTP: keep it on a trusted network or behind a VPN / TLS proxy (see README.md).
"""
import argparse
import hmac
import json
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

log = logging.getLogger("laddu-pi")
SOI, EOI = b"\xff\xd8", b"\xff\xd9"
DEFAULT_TOKENS = {"", "change-me", "changeme", "token", "password"}


# ---------------------------------------------------------------------------------------------- frames
class JpegSplitter:
    """Cuts a byte stream of back-to-back JPEGs (ffmpeg -f mjpeg) into single frames."""

    def __init__(self, max_frame=2_000_000):
        self.buf = bytearray()
        self.max_frame = max_frame

    def feed(self, data: bytes):
        self.buf += data
        frames = []
        while True:
            start = self.buf.find(SOI)
            if start < 0:
                self.buf.clear()
                break
            end = self.buf.find(EOI, start + 2)
            if end < 0:
                if len(self.buf) - start > self.max_frame:  # runaway/corrupt data: drop it rather than grow forever
                    self.buf.clear()
                else:
                    del self.buf[:start]
                break
            frames.append(bytes(self.buf[start:end + 2]))
            del self.buf[:end + 2]
        return frames


class FrameStore:
    def __init__(self):
        self.lock = threading.Condition()
        self.frame = None
        self.stamp = 0.0
        self.count = 0
        self.prev_size = 0
        self.motion_hint = False

    def put(self, jpeg: bytes):
        with self.lock:
            # Cheap hint only: a big jump in compressed size between frames suggests the scene changed.
            self.motion_hint = bool(self.prev_size) and abs(len(jpeg) - self.prev_size) > 0.15 * self.prev_size
            self.prev_size = len(jpeg)
            self.frame, self.stamp, self.count = jpeg, time.time(), self.count + 1
            self.lock.notify_all()

    def wait_next(self, after_count, timeout=5.0):
        with self.lock:
            self.lock.wait_for(lambda: self.count != after_count, timeout)
            return self.frame, self.count

    def age(self):
        return None if not self.stamp else time.time() - self.stamp


# ---------------------------------------------------------------------------------------------- capture
def ffmpeg_cmd(device, size, fps):
    return ["ffmpeg", "-loglevel", "error", "-f", "v4l2", "-input_format", "mjpeg", "-video_size", size,
            "-framerate", str(fps), "-i", device, "-f", "mjpeg", "-q:v", "7", "-"]


class Capture(threading.Thread):
    """Runs ffmpeg and restarts it (with back-off) whenever the camera stops delivering frames."""

    def __init__(self, store, device, size, fps, stale_after=10.0):
        super().__init__(daemon=True)
        self.store, self.device, self.size, self.fps, self.stale_after = store, device, size, fps, stale_after
        self.restarts = 0
        self.last_error = None
        self.stop_flag = threading.Event()

    def run(self):
        delay = 2
        while not self.stop_flag.is_set():
            if not os.path.exists(self.device):
                self.last_error = f"{self.device} not found (is the webcam plugged in?)"
                log.warning(self.last_error)
            else:
                got = self._run_once()
                delay = 2 if got else min(delay * 2, 30)
            self.restarts += 1
            self.stop_flag.wait(delay)

    def _run_once(self):
        splitter, got = JpegSplitter(), False
        try:
            proc = subprocess.Popen(ffmpeg_cmd(self.device, self.size, self.fps), stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        except FileNotFoundError:
            self.last_error = "ffmpeg is not installed (sudo apt install ffmpeg)"
            log.error(self.last_error)
            return False
        log.info("capture started on %s", self.device)
        last = time.time()
        try:
            while not self.stop_flag.is_set():
                chunk = proc.stdout.read(65536)
                if not chunk:
                    break
                for f in splitter.feed(chunk):
                    self.store.put(f)
                    got, last, self.last_error = True, time.time(), None
                if time.time() - last > self.stale_after:
                    self.last_error = "camera stopped delivering frames"
                    break
        finally:
            proc.kill()
            err = proc.stderr.read().decode(errors="replace").strip() if proc.stderr else ""
            if err and not got:
                self.last_error = err.splitlines()[-1][:200]
            log.warning("capture ended (%s)", self.last_error or "stopped")
        return got


# ---------------------------------------------------------------------------------------------- health
def read_health(store, cap):
    def cat(p):
        try:
            with open(p) as f:
                return f.read()
        except OSError:
            return None
    load = os.getloadavg()[0] if hasattr(os, "getloadavg") else None
    mem = {}
    for line in (cat("/proc/meminfo") or "").splitlines():
        k, _, v = line.partition(":")
        if k in ("MemTotal", "MemAvailable"):
            mem[k] = int(v.split()[0]) // 1024
    t = cat("/sys/class/thermal/thermal_zone0/temp")
    age = store.age()
    return {
        "camera_ok": age is not None and age < 10,
        "frame_age_s": None if age is None else round(age, 1),
        "frames": store.count, "motion_hint": store.motion_hint,
        "capture_restarts": cap.restarts, "last_error": cap.last_error,
        "load1": load, "mem_mb": mem, "temp_c": None if t is None else round(int(t) / 1000, 1),
        "time": int(time.time()),
    }


# ---------------------------------------------------------------------------------------------- http
def make_handler(store, cap, token):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "LadduPi"

        def log_message(self, fmt, *a):  # keep logs short; never log tokens
            log.debug("http %s", fmt % a)

        def _auth(self):
            q = parse_qs(urlparse(self.path).query).get("token", [""])[0]
            h = self.headers.get("Authorization", "")
            supplied = h[7:] if h.startswith("Bearer ") else q
            return hmac.compare_digest(supplied.encode(), token.encode())

        def _send(self, code, body, ctype="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = urlparse(self.path).path
            if not self._auth():
                return self._send(401, b'{"error":"unauthorized"}')
            if path == "/health":
                return self._send(200, json.dumps(read_health(store, cap)).encode())
            if path == "/snapshot.jpg":
                f = store.frame
                return self._send(200, f, "image/jpeg") if f else self._send(503, b'{"error":"no frame yet"}')
            if path == "/stream.mjpg":
                return self._stream()
            self._send(404, b'{"error":"not found"}')

        def _stream(self):
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            n = -1
            try:
                while True:
                    f, n2 = store.wait_next(n)
                    if f is None or n2 == n:
                        continue
                    n = n2
                    self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n" % len(f) + f + b"\r\n")
            except (BrokenPipeError, ConnectionResetError):
                pass
    return H


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default="/etc/laddu-pi/config.json")
    ap.add_argument("--test-pattern", action="store_true", help="serve a generated image instead of a webcam (server testing only)")
    args = ap.parse_args(argv)

    cfg = {"device": "/dev/video0", "size": "640x480", "fps": 2, "host": "0.0.0.0", "port": 8080, "token": ""}
    if os.path.exists(args.config):
        with open(args.config) as f:
            cfg.update(json.load(f))
    cfg["token"] = os.environ.get("LADDU_PI_TOKEN", cfg["token"])
    if cfg["token"] in DEFAULT_TOKENS or len(cfg["token"]) < 12:
        sys.exit("Refusing to start: set a token of at least 12 characters in the config (or LADDU_PI_TOKEN).")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    store = FrameStore()
    if args.test_pattern:
        from PIL import Image  # test only; not needed in normal use
        import io

        def pump():
            i = 0
            while True:
                b = io.BytesIO(); Image.new("RGB", (320, 240), (i * 7 % 255, 60, 120)).save(b, "JPEG"); store.put(b.getvalue()); i += 1; time.sleep(0.2)
        threading.Thread(target=pump, daemon=True).start()
        cap = Capture(store, "none", cfg["size"], cfg["fps"])
    else:
        if not shutil.which("ffmpeg"):
            log.warning("ffmpeg not found; install it: sudo apt install ffmpeg")
        cap = Capture(store, cfg["device"], cfg["size"], cfg["fps"])
        cap.start()
    srv = ThreadingHTTPServer((cfg["host"], int(cfg["port"])), make_handler(store, cap, cfg["token"]))
    log.info("serving on %s:%s", cfg["host"], cfg["port"])
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        cap.stop_flag.set()


if __name__ == "__main__":
    main()
