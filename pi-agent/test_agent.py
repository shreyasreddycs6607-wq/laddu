"""Tests for the Pi agent's logic (no webcam, no ffmpeg needed). Run:  python pi-agent/test_agent.py"""
import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import laddu_pi_agent as agent  # noqa: E402

JPEG_A = agent.SOI + b"A" * 50 + agent.EOI
JPEG_B = agent.SOI + b"B" * 80 + agent.EOI
TOKEN = "a-long-test-token"


class SplitterTest(unittest.TestCase):
    def test_two_frames_in_one_chunk(self):
        self.assertEqual(agent.JpegSplitter().feed(JPEG_A + JPEG_B), [JPEG_A, JPEG_B])

    def test_frame_split_across_chunks(self):
        s = agent.JpegSplitter()
        data = JPEG_A + JPEG_B
        out = []
        for i in range(0, len(data), 7):
            out += s.feed(data[i:i + 7])
        self.assertEqual(out, [JPEG_A, JPEG_B])

    def test_garbage_before_a_frame_is_skipped(self):
        self.assertEqual(agent.JpegSplitter().feed(b"junkjunk" + JPEG_A), [JPEG_A])

    def test_runaway_data_is_dropped_not_buffered_forever(self):
        s = agent.JpegSplitter(max_frame=1000)
        s.feed(agent.SOI + b"x" * 5000)
        self.assertEqual(len(s.buf), 0)


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.store = agent.FrameStore()
        cls.cap = agent.Capture(cls.store, "/dev/none", "640x480", 2)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), agent.make_handler(cls.store, cls.cap, TOKEN))
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def get(self, path, token=None, header=False):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}")
        if token and header:
            req.add_header("Authorization", f"Bearer {token}")
        elif token:
            req.full_url += ("&" if "?" in path else "?") + f"token={token}"
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_requires_token(self):
        self.assertEqual(self.get("/health")[0], 401)
        self.assertEqual(self.get("/health", "wrong-wrong-wrong")[0], 401)
        self.assertEqual(self.get("/snapshot.jpg")[0], 401)

    def test_health_reports_no_camera_before_any_frame(self):
        self.store.frame, self.store.stamp = None, 0.0
        code, body = self.get("/health", TOKEN, header=True)
        self.assertEqual(code, 200)
        self.assertFalse(json.loads(body)["camera_ok"])
        self.assertEqual(self.get("/snapshot.jpg", TOKEN, header=True)[0], 503)

    def test_snapshot_and_health_after_a_frame(self):
        self.store.put(JPEG_A)
        code, body = self.get("/snapshot.jpg", TOKEN)
        self.assertEqual((code, body), (200, JPEG_A))
        self.assertTrue(json.loads(self.get("/health", TOKEN, header=True)[1])["camera_ok"])

    def test_unknown_path(self):
        self.assertEqual(self.get("/nope", TOKEN, header=True)[0], 404)

    def test_default_or_short_token_is_refused_at_startup(self):
        with self.assertRaises(SystemExit):
            agent.main(["--config", "/nonexistent/config.json"])


if __name__ == "__main__":
    unittest.main()
