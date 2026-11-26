"""Serve a loopback-only, real-time 1 Mbit/s MPEG-TS fixture for FastTuneEmulatorTest.

Generate the media once using an FFmpeg build with libx264:
  ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 82 -map 0:v -map 1:a -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 25 -keyint_min 25 -sc_threshold 0 -b:v 550k -c:a aac -b:a 64k -muxrate 1000000 -mpegts_flags +resend_headers -pat_period 0.1 -f mpegts fixture.ts
  python rewind_ts_provider.py fixture.ts --port 18961

With the provider running, build and install the opt-in APKs on an isolated
Android TV emulator. The example uses serial emulator-5588 and an x86_64 build:
  gradlew :app:assembleFullDebug :app:assembleFullDebugAndroidTest -PfastTsRewind=true -Pandroid.injected.build.abi=x86_64
  adb -s emulator-5588 install -r -g app/build/outputs/apk/full/debug/app-full-universal-debug.apk
  adb -s emulator-5588 install -r -g app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
  adb -s emulator-5588 shell am instrument -w -e class com.nuvio.tv.livetv.player.FastTuneEmulatorTest -e fixtureBase http://10.0.2.2:18961 -e fixtureLabel sample -e fixtureModes off,on,on,off -e fixtureSeconds 61 -e fixtureControls true com.nuvio.livetv.test/androidx.test.runner.AndroidJUnitRunner
  adb -s emulator-5588 pull /sdcard/Android/data/com.nuvio.livetv/files/fast-rewind-sample.jsonl .
  curl http://127.0.0.1:18961/stats

Use the generated full/debug output-metadata.json for the APK filename when ABI
splits are enabled. Each receipt row contains firstFrameMs, historyReadyMs,
stalls and stallMs. Group provider opens by the same channel label (sample-0-off,
sample-1-on, etc.). Use a fresh fixtureLabel for each run.

The emulator reaches this host at 10.0.2.2. /stats counts GETs actually
connected to the provider. Samples are paced in TS packet units including
the first GOP. A close is recorded when a write observes the peer disconnect
or the sample ends, so the count reflects the provider's view of a handoff.
"""

import argparse
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Provider(ThreadingHTTPServer):
    def __init__(self, port, fixture):
        super().__init__(("127.0.0.1", port), Connection)
        self.fixture = fixture
        self.lock = threading.Lock()
        self.active = 0
        self.peak = 0
        self.events = []

    def event(self, action, channel):
        with self.lock:
            self.active += 1 if action == "open" else -1
            self.peak = max(self.peak, self.active)
            self.events.append(dict(action=action, channel=channel, active=self.active,
                                    ms=time.monotonic_ns() // 1_000_000))


class Connection(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        s = self.server
        if self.path == "/stats":
            with s.lock:
                data = json.dumps(dict(active=s.active, peak=s.peak, events=s.events)).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not re.fullmatch(r"/[a-zA-Z0-9_-]{1,80}\.ts", self.path):
            self.send_error(404)
            return
        channel = self.path[1:-3]
        s.event("open", channel)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.end_headers()
            start = time.monotonic()
            sent = 0
            with s.fixture.open("rb") as media:
                while chunk := media.read(188 * 12):
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    time.sleep(max(0, start + sent / 125_000 - time.monotonic()))
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        finally:
            s.event("close", channel)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--port", type=int, default=18961)
    args = parser.parse_args()
    if not args.fixture.is_file():
        parser.error("generate the fixture.ts file first")
    Provider(args.port, args.fixture).serve_forever()
