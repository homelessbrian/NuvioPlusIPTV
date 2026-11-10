"""Fixed 4k-channel/96k-programme loopback fixture; media always returns HTTP 404."""

import argparse
import hashlib
import json
import os
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape

import small_fixture as fixture


CHANNELS, SLOTS = 4000, 24
DEFAULT_OUTPUT = Path(__file__).resolve().parent.parent / ".run" / "ram-fixture"


def prepare(output, base_epoch=None):
    output.mkdir(parents=True, exist_ok=True)
    manifest_path, xml_path = output / "fixture.json", output / "guide.xml"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    if manifest and base_epoch is not None and manifest["base_epoch"] != base_epoch:
        raise ValueError("--base-epoch differs from existing fixture; use a new --output-dir")
    if not manifest and xml_path.exists():
        raise ValueError("guide.xml has no fixture.json; use a new --output-dir")
    if not manifest:
        base = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=2)
        manifest = {"channels": CHANNELS, "programmes": CHANNELS * SLOTS, "slots": SLOTS,
                    "base_epoch": int(base.timestamp()) if base_epoch is None else base_epoch,
                    "slot_seconds": 1800, "media": "always404"}
    base = datetime.fromtimestamp(manifest["base_epoch"], timezone.utc)
    streams = []
    for index in range(CHANNELS):
        category, name = fixture.LIVE_GROUPS[index % len(fixture.LIVE_GROUPS)]
        streams.append({"num": index + 1, "name": f"{name} Channel {index + 1:04d}", "stream_type": "live",
                        "stream_id": 100001 + index, "stream_icon": "", "epg_channel_id": f"fixture.large.{index:05d}",
                        "added": "1800000000", "category_id": category, "custom_sid": "", "tv_archive": 0,
                        "tv_archive_duration": 0, "direct_source": ""})
    fixture.live_streams = lambda: streams
    if not xml_path.exists():
        with xml_path.open("w", encoding="utf-8", newline="\n") as output_file:
            output_file.write('<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="Fixed RAM fixture">\n')
            for channel in streams:
                output_file.write(f'<channel id="{channel["epg_channel_id"]}"><display-name>{escape(channel["name"])}</display-name><display-name>Alias {channel["num"]}</display-name></channel>\n')
            for channel in streams:
                index = channel["num"] - 1
                for slot in range(SLOTS):
                    start = base + timedelta(minutes=slot * 30)
                    stop = start + timedelta(minutes=30)
                    output_file.write(f'<programme channel="{channel["epg_channel_id"]}" start="{start:%Y%m%d%H%M%S %z}" stop="{stop:%Y%m%d%H%M%S %z}">')
                    output_file.write(f'<title>Team {index % 32} vs Team {(index + slot) % 32} (1999)</title><desc>Programme information with enough text to exercise common guide descriptions and preserve every listing for Search and Sports.</desc><category>Sports</category>')
                    if slot % 3 == 0:
                        output_file.write(f'<sub-title>Round {slot}</sub-title>')
                    if slot % 4 == 0:
                        output_file.write(f'<episode-num system="xmltv_ns">1.{slot}.</episode-num>')
                    if slot % 8 == 0:
                        output_file.write('<credits><actor>Actor One</actor><director>Director Two</director></credits>')
                    output_file.write('</programme>\n')
            output_file.write('</tv>\n')
    facts = {"xml_bytes": xml_path.stat().st_size,
             "xml_sha256": hashlib.sha256(xml_path.read_bytes()).hexdigest(),
             "live_streams_sha256": hashlib.sha256(json.dumps(streams, sort_keys=True).encode()).hexdigest()}
    if any(key in manifest and manifest[key] != value for key, value in facts.items()):
        raise ValueError("existing fixture hashes differ; preserve the files and use a new --output-dir")
    manifest.update(facts)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest, xml_path


def handler_for(manifest, xml_path):
    transfer = {"active": False, "completed": 0, "bytes": 0}

    class Handler(fixture.Handler):
        def do_GET(self):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            if url.path == "/health":
                return self.send({"fixture": "fixed-guide-ram", "pid": os.getpid(),
                                  "port": self.server.server_port, **manifest,
                                  "requests": dict(fixture.COUNTS), "xml_transfer": dict(transfer)})
            if url.path.rsplit("/", 1)[-1] != "xmltv.php":
                return super().do_GET()
            fixture.COUNTS["xmltv.php"] += 1
            if query.get("username") != ["demo"] or query.get("password") != ["demo"]:
                return self.send({"auth": 0}, status=403)
            self.send_response(200)
            self.send_header("Content-Type", "application/xml; charset=utf-8")
            self.send_header("Content-Length", str(xml_path.stat().st_size))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command == "HEAD":
                return
            transfer["active"] = True
            try:
                with xml_path.open("rb") as source:
                    while data := source.read(256 * 1024):
                        self.wfile.write(data)
                        transfer["bytes"] += len(data)
                transfer["completed"] += 1
            finally:
                transfer["active"] = False

    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--base-epoch", type=int, help="UTC epoch for a new fixture; otherwise reuse the manifest")
    parser.add_argument("--port", type=int, default=18081, help="loopback port (0 selects an unused port)")
    parser.add_argument("--generate-only", action="store_true", help="write/verify files and exit without listening")
    options = parser.parse_args()
    manifest, xml_path = prepare(options.output_dir, options.base_epoch)
    if options.generate_only:
        print(json.dumps(manifest), flush=True)
    else:
        with ThreadingHTTPServer(("127.0.0.1", options.port), handler_for(manifest, xml_path)) as server:
            server.daemon_threads = True
            print(json.dumps({"fixture": "ready", "host": "127.0.0.1", "port": server.server_port, **manifest}), flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
