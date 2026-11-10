"""Loopback-only synthetic Xtream metadata. Every media request returns 404."""

import argparse
import json
import os
import time
from collections import Counter
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape


LIVE_GROUPS = [("10", "Fixture Live One"), ("20", "Fixture Live Two"), ("30", "Fixture Live Three")]
MOVIE_GROUPS = [("100", "Fixture Movies Adventure"), ("200", "Fixture Movies Comedy")]
SERIES_GROUPS = [("300", "Fixture Series Drama"), ("400", "Fixture Series Comedy")]
COUNTS = Counter()


def categories(groups):
    return [{"category_id": key, "category_name": name, "parent_id": 0} for key, name in groups]


def live_streams():
    return [
        {"num": group * 2 + index + 1, "name": f"{name} Channel {index + 1}",
         "stream_type": "live", "stream_id": 101 + group * 10 + index,
         "stream_icon": "", "epg_channel_id": f"fixture.{group}.{index}",
         "added": "1800000000", "category_id": category, "custom_sid": "",
         "tv_archive": 0, "tv_archive_duration": 0, "direct_source": ""}
        for group, (category, name) in enumerate(LIVE_GROUPS) for index in range(2)
    ]


def vod_streams(groups, series=False):
    return [
        {"num": group * 2 + index + 1, "name": f"{name} Title {index + 1}",
         "series_id" if series else "stream_id": (3001 if series else 1001) + group * 10 + index,
         "stream_type": "series" if series else "movie", "stream_icon": "", "cover": "",
         "category_id": category, "container_extension": "mp4", "added": "1800000000",
         "last_modified": "1800000000", "year": "2024", "releaseDate": "2024-01-01",
         "rating": "7.0", "genre": "Fixture", "plot": "Synthetic group-selection fixture.",
         "tmdb": "0", "direct_source": ""}
        for group, (category, name) in enumerate(groups) for index in range(2)
    ]


def filtered(items, query):
    category = query.get("category_id", [None])[0]
    return items if not category else [item for item in items if item["category_id"] == category]


def programmes():
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=2)
    for channel in live_streams():
        for index in range(12):
            began = start + timedelta(hours=index)
            ended = began + timedelta(hours=1)
            yield channel, began, ended, f"Fixture programme {index + 1}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        pass  # Never log incoming query strings, credentials or request headers.

    def send(self, value, content_type="application/json", status=200):
        data = json.dumps(value, ensure_ascii=True).encode() if content_type == "application/json" else value.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        action = query.get("action", [""])[0]
        endpoint = url.path.rsplit("/", 1)[-1]
        known_actions = {
            "get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams",
            "get_series_categories", "get_series", "get_vod_info", "get_series_info",
            "get_short_epg", "get_simple_data_table",
        }
        COUNTS[action if action in known_actions else endpoint if endpoint in {"health", "player_api.php", "get.php", "xmltv.php"} else "other"] += 1
        if url.path == "/health":
            return self.send({"fixture": True, "pid": os.getpid(), "port": self.server.server_port,
                              "groups": {"live": 3, "movies": 2, "series": 2},
                              "items": {"live": 6, "movies": 4, "series": 4},
                              "media": "always HTTP 404", "requests": dict(COUNTS)})
        if endpoint not in {"player_api.php", "get.php", "xmltv.php"}:
            return self.send({"fixture": True, "media": "not served"}, status=404)
        if query.get("username") != ["demo"] or query.get("password") != ["demo"]:
            return self.send({"user_info": {"auth": 0, "status": "Disabled"}})
        if endpoint == "player_api.php":
            payloads = {
                "get_live_categories": lambda: categories(LIVE_GROUPS),
                "get_live_streams": lambda: filtered(live_streams(), query),
                "get_vod_categories": lambda: categories(MOVIE_GROUPS),
                "get_vod_streams": lambda: filtered(vod_streams(MOVIE_GROUPS), query),
                "get_series_categories": lambda: categories(SERIES_GROUPS),
                "get_series": lambda: filtered(vod_streams(SERIES_GROUPS, series=True), query),
                "get_vod_info": lambda: {"info": {}, "movie_data": {}},
                "get_series_info": lambda: {"info": {}, "episodes": {}, "seasons": []},
                "get_short_epg": lambda: {"epg_listings": []},
                "get_simple_data_table": lambda: {"epg_listings": []},
            }
            if action:
                return self.send(payloads[action]() if action in payloads else [])
            now = int(time.time())
            return self.send({
                "user_info": {"username": "demo", "password": "demo", "auth": 1, "status": "Active",
                              "exp_date": str(now + 365 * 86400), "is_trial": "0", "active_cons": "0",
                              "created_at": str(now - 86400), "max_connections": "4",
                              "allowed_output_formats": ["ts", "m3u8"], "message": "Synthetic local fixture"},
                "server_info": {"url": "127.0.0.1", "port": str(self.server.server_port), "https_port": "",
                                "server_protocol": "http", "timezone": "UTC", "timestamp_now": now,
                                "time_now": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")},
            })
        base = f"http://127.0.0.1:{self.server.server_port}"
        if endpoint == "get.php":
            lines = [f'#EXTM3U url-tvg="{base}/xmltv.php?username=demo&password=demo"']
            for channel in live_streams():
                group = dict(LIVE_GROUPS)[channel["category_id"]]
                lines += [f'#EXTINF:-1 tvg-id="{channel["epg_channel_id"]}" group-title="{group}",{channel["name"]}',
                          base + "/empty.ts"]
            return self.send("\n".join(lines) + "\n", "application/x-mpegURL")
        parts = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="Local fixture">']
        for channel in live_streams():
            parts.append(f'<channel id="{channel["epg_channel_id"]}"><display-name>{escape(channel["name"])}</display-name></channel>')
        for channel, start, stop, title in programmes():
            parts.append(f'<programme channel="{channel["epg_channel_id"]}" start="{start:%Y%m%d%H%M%S %z}" stop="{stop:%Y%m%d%H%M%S %z}">'
                         f'<title>{escape(title)}</title><desc>Synthetic UI fixture, no playable media.</desc><category>Fixture</category></programme>')
        parts.append("</tv>")
        return self.send("\n".join(parts), "application/xml")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", choices=["127.0.0.1"])
    parser.add_argument("--port", type=int, default=18081)
    options = parser.parse_args()
    with ThreadingHTTPServer((options.host, options.port), Handler) as server:
        server.daemon_threads = True
        print(json.dumps({"fixture": "ready", "pid": os.getpid(), "host": options.host, "port": options.port}), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
