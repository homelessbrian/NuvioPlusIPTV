"""Explicitly prepare immutable dated fixtures; serving is a separately gated action."""
from pathlib import Path
import argparse, collections, hashlib, json, os, threading, time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape
def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def work_path(path):
    """Resolve the explicit fixture path; there is no app-data or device path."""
    return Path(path).resolve()


def new_json(path, value):
    path = work_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, indent=2)
        output.write("\n")

GROUPS = [("10", "Fixture Live One"), ("20", "Fixture Live Two"), ("30", "Fixture Live Three")]
CASES = {"primary": (4000, 4000, "xtream"), "shared-m3u": (26716, 4477, "m3u")}
SLOTS, SLOT_SECONDS, PORT = 24, 7200, 18081

def stamp(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y%m%d%H%M%S +0000")

def prepare(directory, case, epoch=None):
    directory = work_path(directory)
    if directory.exists():
        raise ValueError("Fixture folder already exists; frozen files are never regenerated")
    channels, guides, ingestion = CASES[case]
    frozen_at = int(time.time()) if epoch is None else int(epoch)
    base = frozen_at // SLOT_SECONDS * SLOT_SECONDS - SLOT_SECONDS
    directory.mkdir(parents=True)
    streams, bindings = [], []
    lines = [f'#EXTM3U url-tvg="http://127.0.0.1:{PORT}/xmltv.php?username=demo&password=demo"']
    for index in range(channels):
        category, group = GROUPS[index % len(GROUPS)]
        name = f"{group} Channel {index+1:04d}"
        xid = f"fixture.v2.{index % guides:05d}"
        shift_hours = (0, -1, 1)[index % 3] if ingestion == "m3u" else 0
        streams.append({"num": index+1, "name": name, "stream_type": "live", "stream_id": 100001+index,
            "stream_icon": "", "epg_channel_id": xid, "added": str(frozen_at), "category_id": category,
            "custom_sid": "", "tv_archive": 0, "tv_archive_duration": 0, "direct_source": ""})
        bindings.append({"channel_number": index+1, "epg_id": xid, "shift_ms": shift_hours*3_600_000})
        lines.extend([f'#EXTINF:-1 tvg-id="{xid}" tvg-shift="{shift_hours}" group-title="{group}",{name}',
            f"http://127.0.0.1:{PORT}/live/demo/demo/{100001+index}.ts"])
    (directory/"streams.json").write_text(json.dumps(streams, ensure_ascii=False, separators=(",", ":")), encoding="utf-8", newline="\n")
    (directory/"playlist.m3u").write_text("\n".join(lines)+"\n", encoding="utf-8", newline="\n")
    (directory/"bindings.json").write_text(json.dumps(bindings, separators=(",", ":")), encoding="utf-8", newline="\n")
    with (directory/"guide.xml").open("w", encoding="utf-8", newline="\n") as out:
        out.write('<?xml version="1.0" encoding="UTF-8"?><tv generator-info-name="Frozen RAM v2">\n')
        for index in range(guides):
            name = f"{GROUPS[index % 3][1]} Channel {index+1:04d}"
            out.write(f'<channel id="fixture.v2.{index:05d}"><display-name>{escape(name)}</display-name><display-name>Alias {index+1}</display-name></channel>\n')
        for index in range(guides):
            for slot in range(SLOTS):
                start = base + slot*SLOT_SECONDS
                out.write(f'<programme channel="fixture.v2.{index:05d}" start="{stamp(start)}" stop="{stamp(start+SLOT_SECONDS)}">')
                out.write(f'<title>Team {index%32} vs Team {(index+slot)%32} (1999)</title><desc>Programme information exercising descriptions, Unicode café İstanbul Σports, shared guide bindings, Search and Sports.</desc><category>Sports</category>')
                if slot % 3 == 0: out.write(f'<sub-title>Round {slot}</sub-title>')
                if slot % 4 == 0: out.write(f'<episode-num system="xmltv_ns">1.{slot}.</episode-num>')
                if slot % 8 == 0: out.write('<credits><actor>Actor One</actor><director>Director Two</director></credits>')
                out.write('</programme>\n')
        out.write('</tv>\n')
    manifest = {"version": 2, "case": case, "created_utc": utc(), "frozen_at_epoch": frozen_at,
        "channels": channels, "guide_channels": guides, "stored_programmes_expected": guides*SLOTS,
        "logical_programmes_expected": channels*SLOTS, "slots": SLOTS, "slot_seconds": SLOT_SECONDS,
        "base_epoch": base, "coverage_end_epoch": base+SLOTS*SLOT_SECONDS,
        "conservative_import_no_trim_deadline_epoch": base+24*3600,
        "required_benchmark_settings": {"epgPastHours": 24, "epgFutureDays_minimum": 2},
        "ingestion": ingestion, "binding_shifts_in_fixture": ingestion == "m3u",
        "shift_validation": "M3U tvg-shift source path; native import must verify chan.sh distribution before claiming shift coverage" if ingestion == "m3u" else "none",
        "endpoint": f"http://127.0.0.1:{PORT}", "port": PORT, "media": "all media endpoints404",
        "search_probe": {"query": "Team 9 vs Team 12", "slot": 3, "event_start_epoch": base+3*SLOT_SECONDS,
            "expected_primary_channel_number": 650, "after_browsing_rows": 600,
            "limit": "Verify upcoming and within6h at execution; primary case only. No playback."},
        "files": {name: {"bytes": (directory/name).stat().st_size, "sha256": digest(directory/name)}
            for name in ("guide.xml", "streams.json", "playlist.m3u", "bindings.json")}}
    new_json(directory/"fixture.json", manifest)
    return manifest

def verify(directory, *, for_run=False, now=None):
    directory = work_path(directory)
    manifest = json.loads((directory/"fixture.json").read_text(encoding="utf-8"))
    if manifest.get("version") != 2 or manifest.get("case") not in CASES:
        raise ValueError("Unknown fixture manifest")
    channels,guides,ingestion=CASES[manifest["case"]]
    if manifest.get("endpoint") != f"http://127.0.0.1:{PORT}" or manifest.get("port") != PORT or manifest.get("binding_shifts_in_fixture") != (ingestion == "m3u"):
        raise ValueError("Fixture endpoint or binding-shift declaration differs from the owned local case")
    if (manifest["channels"],manifest["guide_channels"],manifest["ingestion"],manifest["slots"],manifest["slot_seconds"],manifest["logical_programmes_expected"],manifest["stored_programmes_expected"]) != (channels,guides,ingestion,SLOTS,SLOT_SECONDS,channels*SLOTS,guides*SLOTS):
        raise ValueError("Fixture shape differs from its declared case")
    if set(manifest["files"]) != {"guide.xml","streams.json","playlist.m3u","bindings.json"}:raise ValueError("Incomplete fixture manifest")
    for name, entry in manifest["files"].items():
        if name not in {"guide.xml", "streams.json", "playlist.m3u", "bindings.json"}:
            raise ValueError("Unexpected fixture file")
        path = directory/name
        if path.stat().st_size != entry["bytes"] or digest(path) != entry["sha256"]:
            raise ValueError("Frozen fixture bytes changed")
    if for_run:
        at = int(time.time()) if now is None else now
        if not manifest["base_epoch"] <= at < manifest["conservative_import_no_trim_deadline_epoch"]:
            raise ValueError("Fixture too old/future for complete import; prepare a NEW pair, never regenerate this one")
        if manifest["coverage_end_epoch"] < at+24*3600:
            raise ValueError("Fixture no longer covers the next24hours")
    return {**manifest,"manifest_file_sha256":digest(directory/"fixture.json")}

def serve(directory, execute):
    if not execute: raise ValueError("Serving requires --execute; no listener was started")
    manifest = verify(directory, for_run=True)
    directory = work_path(directory)
    streams = json.loads((directory/"streams.json").read_text(encoding="utf-8"))
    counts, lock = collections.Counter(), threading.Lock()
    transfer = {"active": False, "completed": 0, "bytes": 0}
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *_): pass
        def send_bytes(self, data, content_type, status=200):
            self.send_response(status); self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data))); self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close"); self.end_headers()
            if self.command != "HEAD": self.wfile.write(data)
        def send_json(self, value, status=200): self.send_bytes(json.dumps(value).encode(), "application/json", status)
        def do_HEAD(self): self.do_GET()
        def do_GET(self):
            url = urlsplit(self.path); query = parse_qs(url.query); endpoint = url.path.rsplit("/",1)[-1]
            action = query.get("action", [""])[0]
            if url.path == "/health":
                with lock: value = {"pid":os.getpid(), "fixture":manifest, "requests":dict(counts), "xml_transfer":dict(transfer)}
                return self.send_json(value)
            with lock: counts[action or endpoint if endpoint in {"player_api.php","get.php","xmltv.php"} else "media404"] += 1
            if endpoint not in {"player_api.php","get.php","xmltv.php"}: return self.send_json({"media":"not served"},404)
            if query.get("username") != ["demo"] or query.get("password") != ["demo"]: return self.send_json({"auth":0},403)
            if endpoint == "get.php": return self.send_bytes((directory/"playlist.m3u").read_bytes(),"application/x-mpegURL")
            if endpoint == "xmltv.php":
                size=(directory/"guide.xml").stat().st_size
                self.send_response(200); self.send_header("Content-Type","application/xml"); self.send_header("Content-Length",str(size)); self.send_header("Connection","close"); self.end_headers()
                with lock: transfer["active"]=True
                try:
                    with (directory/"guide.xml").open("rb") as source:
                        while data := source.read(256*1024):
                            self.wfile.write(data)
                            with lock: transfer["bytes"] += len(data)
                    with lock: transfer["completed"] += 1
                finally:
                    with lock: transfer["active"]=False
                return
            if action == "get_live_categories": return self.send_json([{"category_id":i,"category_name":n,"parent_id":0} for i,n in GROUPS])
            if action == "get_live_streams":
                category=query.get("category_id",[None])[0]
                return self.send_json(streams if not category else [x for x in streams if x["category_id"]==category])
            if action: return self.send_json([] if action not in {"get_short_epg","get_simple_data_table"} else {"epg_listings":[]})
            now=int(time.time())
            return self.send_json({"user_info":{"username":"demo","password":"demo","auth":1,"status":"Active","exp_date":str(manifest["frozen_at_epoch"]+365*86400),"is_trial":"0","active_cons":"0","max_connections":"4","allowed_output_formats":["ts","m3u8"]},
                "server_info":{"url":"127.0.0.1","port":str(PORT),"server_protocol":"http","timezone":"UTC","timestamp_now":now,"time_now":datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")}})
    with ThreadingHTTPServer(("127.0.0.1",PORT),Handler) as server:
        server.daemon_threads=True
        print(json.dumps({"ready":True,"pid":os.getpid(),"port":PORT,"case":manifest["case"]}),flush=True)
        server.serve_forever()

def main():
    p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="command",required=True)
    a=sub.add_parser("prepare");a.add_argument("--case",choices=CASES,default="primary");a.add_argument("--out",required=True);a.add_argument("--epoch",type=int)
    a=sub.add_parser("verify");a.add_argument("--fixture",required=True);a.add_argument("--for-run",action="store_true")
    a=sub.add_parser("serve");a.add_argument("--fixture",required=True);a.add_argument("--execute",action="store_true")
    args=p.parse_args()
    if args.command=="prepare": print(json.dumps(prepare(args.out,args.case,args.epoch),indent=2))
    elif args.command=="verify": print(json.dumps(verify(args.fixture,for_run=args.for_run),indent=2))
    else: serve(args.fixture,args.execute)
if __name__=="__main__": main()
