#!/usr/bin/env python3
"""A pretend Xtream Codes panel, for running HillTV without a real provider.

    python3 Tools/mock_xtream.py                      # catalog and guide only
    python3 Tools/mock_xtream.py --media /some/folder # and things that play

Then sign in to the app with any username and password (the username "bad"
is refused) and this server's address:

    Android emulator   http://10.0.2.2:8089
    Same network       http://<this computer's address>:8089

Everything it serves is made up: channel names, guide listings, movies and
artwork are generated here. Nothing comes from a real provider.

The catalog is deliberately messy the way real panels are: numbers as
strings, a row with no id, a stray string in a list, guide rows without
timestamps, series episodes as a dictionary for one show and a list for the
next. That is the point; the app's lenient decoding should shrug it off.

What it can't stand in for: MPEG-TS. Real panels send MPEG-TS, raw at the
.ts address and as the segments of the .m3u8 one. This serves MP4 at both
(the clip itself at .ts, fragmented MP4 segments for HLS), because the
GStreamer here has no TS muxer. Players that go by content rather than by
the address play it all the same, but their MPEG-TS reader is never used.

To make streams play, give --media a folder holding two files:

    live.mp4    a short fragmented MP4, looped forever as a live channel
                (30 seconds unless --loop-seconds says otherwise)
    movie.mp4   an ordinary MP4, served for every movie and episode

GStreamer can make both (no ffmpeg needed). I420 and the main profile matter:
without them x264 picks a format TV decoders show as green stripes.

    gst-launch-1.0 -e videotestsrc num-buffers=750 pattern=ball \\
      ! video/x-raw,format=I420,width=1280,height=720,framerate=25/1 ! timeoverlay \\
      ! x264enc key-int-max=25 bitrate=1500 speed-preset=veryfast ! video/x-h264,profile=main \\
      ! mp4mux name=mux fragment-duration=1000 ! filesink location=live.mp4 \\
      audiotestsrc num-buffers=1292 volume=0.05 ! audio/x-raw,rate=44100,channels=2 \\
      ! avenc_aac ! aacparse ! mux.

    gst-launch-1.0 -e videotestsrc num-buffers=2250 pattern=smpte \\
      ! video/x-raw,format=I420,width=1280,height=720,framerate=25/1 ! timeoverlay \\
      ! x264enc key-int-max=50 bitrate=1500 speed-preset=veryfast ! video/x-h264,profile=main \\
      ! mp4mux name=mux faststart=true ! filesink location=movie.mp4 \\
      audiotestsrc num-buffers=3876 volume=0.05 ! audio/x-raw,rate=44100,channels=2 \\
      ! avenc_aac ! aacparse ! mux.

Needs Pillow for the artwork (pip install pillow). Without it, artwork
links are dead, which is also realistic.
"""
import argparse
import base64
import io
import json
import re
import struct
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

try:
    from PIL import Image, ImageDraw
except ImportError:  # artwork is optional
    Image = None

# Some names are in the forms a real provider uses: a marker between bars,
# a kind word and a dash, a tag in brackets (one with a mistyped bracket).
LIVE_CATEGORIES = [
    ("1", "|NA| USA Sports"), ("2", "NFL"), ("3", "NBA LEAGUE PASS"), ("4", "NHL"), ("5", "MLB"),
    ("6", "PPV EVENTS"), ("7", "UK | SPORTS"), ("8", "SOCCER"), ("9", "GOLF & TENNIS"),
    ("10", "|NA| USA News"), ("11", "UK | NEWS"), ("12", "US | ENTERTAINMENT"), ("13", "CA | LOCALS"),
    ("14", "24/7 SHOWS"), ("15", "US | MOVIES"), ("16", "|NA| USA Kids"), ("17", "MUSIC"),
    ("18", "FR | CINEMA"), ("19", "LATINO"), ("20", "Mystery Box"),
]
CHANNEL_WORDS = ["Summit", "Ridgeline", "Prairie", "Harbor", "Granite", "Copper", "Tetons", "Basin", "Cedar", "Juniper"]
SHOWS = ["Evening News", "Morning Roundup", "Trail Report", "Cooking With Fire", "Small Town Stories", "The Late Game",
         "Weather Now", "Antiques Hour", "High Country", "Quiz Night", "Amélie's Kitchen", "Night Shift"]
VOD_CATEGORIES = [("101", "VOD - NEW ADDED [EN]"), ("102", "VOD - NETFLIX [EN]"), ("103", "VOD - NETFLIX [MULTISUB]"), ("104", "Family")]
MOVIE_WORDS = ["Last", "Silver", "Midnight", "Broken", "Golden", "Quiet", "Wild", "Distant", "Paper", "Iron"]
MOVIE_NOUNS = ["Canyon", "Harvest", "Signal", "Orchard", "Station", "Lantern", "River", "Summit", "Compass", "Garden"]
SERIES_CATEGORIES = [("201", "SRS - HULU [EN}"), ("202", "Comedy Series"), ("203", "Documentaries")]


def channels_in(category_id):
    cid = int(category_id)
    name = dict(LIVE_CATEGORIES)[category_id]
    count = 8 + (cid * 7) % 30
    rows = []
    for index in range(count):
        stream_id = cid * 1000 + index
        word = CHANNEL_WORDS[(cid + index) % len(CHANNEL_WORDS)]
        suffix = ["HD", "FHD", "East", "West", "2", "Plus"][index % 6]
        topic = name.split("|")[-1].split()[-1].title()
        # Every other channel carries a country prefix, as providers' lists do.
        region = ["US | ", "", "UK: ", ""][index % 4]
        row = {
            # Numbers arrive as numbers or strings depending on the row.
            "num": index + 1 if index % 2 else str(index + 1),
            "name": f"{region}{word} {topic} {suffix}",
            "stream_type": "live",
            "stream_id": stream_id if index % 3 else str(stream_id),
            "stream_icon": f"/img/logo/{stream_id}.png" if index % 5 else "",
            "epg_channel_id": f"{word.lower()}{index}.example" if index % 4 else None,
            "category_id": category_id if index % 2 else cid,
            "tv_archive": 1 if index % 7 == 0 else "0",
            "tv_archive_duration": "3" if index % 7 == 0 else 0,
        }
        rows.append(row)
    rows.insert(2, {"name": "A row with no id"})
    rows.insert(4, "garbage")
    return rows


def movies_in(category_id):
    cid = int(category_id)
    rows = []
    for index in range(26):
        stream_id = cid * 100 + index
        title = f"{MOVIE_WORDS[(cid + index) % 10]} {MOVIE_NOUNS[(cid * 3 + index) % 10]}"
        rows.append({
            "num": index + 1,
            "name": f"{title} ({1985 + (stream_id % 40)})",
            "stream_type": "movie",
            "stream_id": str(stream_id) if index % 2 else stream_id,
            "stream_icon": f"/img/poster/{stream_id}.png",
            "rating": str(round(5 + (stream_id % 45) / 10, 1)) if index % 3 else 0,
            "category_id": category_id,
            "container_extension": "mp4",
            "added": str(1_700_000_000 + stream_id * 3600),
        })
    return rows


def series_in(category_id):
    cid = int(category_id)
    rows = []
    for index in range(18):
        series_id = cid * 100 + index
        rows.append({
            "num": index + 1,
            "name": f"{MOVIE_NOUNS[(cid + index) % 10]} {['Road', 'Files', 'County', 'Nights'][index % 4]}",
            "series_id": series_id,
            "cover": f"/img/poster/{series_id}.png",
            "plot": "A made-up show about a made-up place, for testing.",
            "rating": "7.4" if index % 2 else 6,
            "category_id": category_id,
            "releaseDate" if index % 2 else "release_date": f"{2005 + index}-03-1{index % 9}",
            "genre": "Drama / Mystery",
        })
    return rows


def b64(text):
    return base64.b64encode(text.encode()).decode()


def short_epg(stream_id, limit):
    if stream_id % 4 == 0:
        return {"epg_listings": []}  # no guide data for this channel
    slot = 1800 if stream_id % 3 else 3600
    start = int(time.time()) // slot * slot
    rows = []
    for index in range(limit):
        begin = start + index * slot
        title = SHOWS[(stream_id + begin // slot) % len(SHOWS)]
        row = {
            "id": str(begin + stream_id),
            "title": b64(title),
            "description": b64(f"{title}, episode {begin // slot % 90}. Listings from the pretend panel."),
            "start": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(begin)),
            "end": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(begin + slot)),
            "channel_id": f"ch{stream_id}",
        }
        if stream_id % 5:  # some panels leave the timestamps out
            row["start_timestamp"] = str(begin)
            row["stop_timestamp"] = begin + slot
        rows.append(row)
    return {"epg_listings": rows}


def series_info(series_id):
    def episode(season, number):
        return {
            "id": str(series_id * 1000 + season * 100 + number),
            "episode_num": number if number % 2 else str(number),
            "title": f"Chapter {number}",
            "container_extension": "mp4",
            "season": season,
            "info": {"plot": "Things happen, then more things.", "duration_secs": "2580"} if number % 3 else [],
        }

    seasons = {str(s): [episode(s, n) for n in range(1, 9)] for s in range(1, 4)}
    episodes = seasons if series_id % 2 else list(seasons.values())
    return {"seasons": [], "info": {"name": f"Series {series_id}", "cover": f"/img/poster/{series_id}.png",
                                    "plot": "A made-up show about a made-up place, for testing."},
            "episodes": episodes}


def vod_info(stream_id):
    if stream_id % 9 == 0:
        return {"info": [], "movie_data": {"stream_id": stream_id, "name": f"Movie {stream_id}", "container_extension": "mp4"}}
    return {
        "info": {
            "movie_image": f"/img/poster/{stream_id}.png",
            "backdrop_path": [f"/img/backdrop/{stream_id}.png"],
            "plot": "Two strangers, one long road and a secret neither of them wants to carry. Made up for testing.",
            "genre": "Drama / Adventure",
            "director": "A. N. Director",
            "cast": "First Lead, Second Lead, Third Wheel",
            "releasedate": f"{1985 + (stream_id % 40)}-06-01",
            "duration": "01:36:00",
            "rating": "7.1",
        },
        "movie_data": {"stream_id": stream_id, "container_extension": "mp4"},
    }


def picture(kind, seed):
    """A flat colored card with a shape on it. Enough to see artwork load."""
    if Image is None:
        return None
    size = {"logo": (320, 200), "poster": (400, 600), "backdrop": (1280, 720)}.get(kind)
    if size is None:
        return None
    hue = (seed * 47) % 360
    image = Image.new("RGB", size, f"hsl({hue}, 45%, 28%)")
    draw = ImageDraw.Draw(image)
    w, h = size
    draw.ellipse([w * 0.25, h * 0.2, w * 0.75, h * 0.2 + w * 0.5], fill=f"hsl({(hue + 40) % 360}, 60%, 60%)")
    draw.rectangle([0, h * 0.8, w, h], fill=f"hsl({hue}, 45%, 18%)")
    out = io.BytesIO()
    image.save(out, "PNG")
    return out.getvalue()


def top_boxes(data, start, end):
    """(type, offset, size) for each MP4 box between two offsets."""
    offset = start
    while offset + 8 <= end:
        size, kind = struct.unpack(">I4s", data[offset:offset + 8])
        if size == 1:
            size = struct.unpack(">Q", data[offset + 8:offset + 16])[0]
        if size < 8:
            break
        yield kind, offset, size
        offset += size


class Fragments:
    """Splits a fragmented MP4 into its init section and playable segments.

    GStreamer writes one fragment per track, sound and picture in turn. A
    segment here is one fragment of every track, so each one plays alone.
    """

    def __init__(self, path):
        self.data = path.read_bytes()
        boxes = list(top_boxes(self.data, 0, len(self.data)))
        moofs = [index for index, box in enumerate(boxes) if box[0] == b"moof"]
        if not moofs:
            raise SystemExit(f"{path} has no fragments. Make it with mp4mux fragment-duration=1000.")
        self.init = self.data[:boxes[moofs[0]][1]]

        fragments = []
        for position, index in enumerate(moofs):
            start = boxes[index][1]
            end = boxes[moofs[position + 1]][1] if position + 1 < len(moofs) else len(self.data)
            # Trailing index boxes are not part of the last fragment.
            for kind, box_offset, _ in boxes[index:]:
                if kind in (b"mfra", b"moov") and box_offset < end:
                    end = box_offset
            fragments.append((self.tracks(boxes[index]), self.data[start:end]))

        every_track = set().union(*(tracks for tracks, _ in fragments))
        self.segments = []
        seen, chunk = set(), b""
        for tracks, data in fragments:
            if seen & tracks:
                seen, chunk = set(), b""  # a track came round again before the others: start over
            seen |= tracks
            chunk += data
            if seen == every_track:
                self.segments.append(chunk)
                seen, chunk = set(), b""

    def tracks(self, moof):
        """Track IDs a fragment carries."""
        _, offset, size = moof
        found = set()
        for kind, traf_offset, traf_size in top_boxes(self.data, offset + 8, offset + size):
            if kind != b"traf":
                continue
            for inner, inner_offset, _ in top_boxes(self.data, traf_offset + 8, traf_offset + traf_size):
                if inner == b"tfhd":
                    found.add(struct.unpack(">I", self.data[inner_offset + 12:inner_offset + 16])[0])
        return found


class Panel(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    media = None
    fragments = None
    connections = 2
    segment_seconds = 1.0
    started = time.time()

    def log_message(self, fmt, *args):
        if not self.quiet:
            print("%s %s" % (self.address_string(), fmt % args))

    def send(self, body, kind="application/json", status=200):
        if not isinstance(body, bytes):
            # Artwork links are written as paths above; panels send whole addresses.
            host = self.headers.get("Host", "localhost")
            body = json.dumps(body).replace('"/img/', f'"http://{host}/img/').encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_file(self, path, kind):
        """Whole file or one byte range, so the player can seek."""
        size = path.stat().st_size
        start, end = 0, size - 1
        match = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if match and (match.group(1) or match.group(2)):
            if match.group(1):
                start = int(match.group(1))
                if match.group(2):
                    end = min(int(match.group(2)), size - 1)
            else:
                start = max(0, size - int(match.group(2)))
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        if self.command == "HEAD":
            return
        with path.open("rb") as handle:
            handle.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = handle.read(min(256 * 1024, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(chunk)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        query = {key: values[0] for key, values in parse_qs(url.query).items()}
        try:
            if url.path.endswith("/player_api.php"):
                return self.api(query)
            if url.path.startswith("/img/"):
                return self.image(url.path)
            if url.path.startswith("/live/"):
                return self.live(url.path)
            if url.path.startswith(("/movie/", "/series/")):
                return self.vod()
            if url.path.startswith("/segment/"):
                return self.segment(url.path)
            self.send({"error": "not found"}, status=404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def api(self, query):
        if query.get("username") == "bad":
            return self.send({"user_info": {"auth": 0}})
        action = query.get("action")
        category = query.get("category_id")
        if action is None:
            return self.send({
                "user_info": {
                    "username": query.get("username"), "password": "not echoed", "auth": 1, "status": "Active",
                    "exp_date": str(int(time.time()) + 200 * 86400), "is_trial": "0", "active_cons": "0",
                    "max_connections": str(self.connections), "allowed_output_formats": ["m3u8", "ts"],
                },
                "server_info": {"url": "mock.example", "port": "8089", "https_port": 443,
                                "server_protocol": "http", "timezone": "America/Denver"},
            })
        if action == "get_live_categories":
            return self.send([{"category_id": cid, "category_name": name, "parent_id": 0} for cid, name in LIVE_CATEGORIES])
        if action == "get_live_streams":
            ids = [category] if category else [cid for cid, _ in LIVE_CATEGORIES]
            return self.send([row for cid in ids if cid in dict(LIVE_CATEGORIES) for row in channels_in(cid)])
        if action == "get_vod_categories":
            return self.send([{"category_id": cid, "category_name": name} for cid, name in VOD_CATEGORIES])
        if action == "get_vod_streams":
            ids = [category] if category else [cid for cid, _ in VOD_CATEGORIES]
            return self.send([row for cid in ids if cid in dict(VOD_CATEGORIES) for row in movies_in(cid)])
        if action == "get_series_categories":
            return self.send([{"category_id": cid, "category_name": name} for cid, name in SERIES_CATEGORIES])
        if action == "get_series":
            ids = [category] if category else [cid for cid, _ in SERIES_CATEGORIES]
            return self.send([row for cid in ids if cid in dict(SERIES_CATEGORIES) for row in series_in(cid)])
        if action == "get_series_info":
            return self.send(series_info(int(query.get("series_id", 0))))
        if action == "get_vod_info":
            return self.send(vod_info(int(query.get("vod_id", 0))))
        if action == "get_short_epg":
            return self.send(short_epg(int(query.get("stream_id", 0)), int(query.get("limit", 4))))
        self.send([])

    def image(self, path):
        parts = path.strip("/").split("/")
        data = picture(parts[1], int(re.sub(r"\D", "", parts[2]) or 0)) if len(parts) == 3 else None
        if data is None:
            return self.send(b"", "text/plain", 404)
        self.send(data, "image/png")

    def live(self, path):
        if self.fragments is None:
            return self.send(b"", "text/plain", 404)
        if path.endswith(".m3u8"):
            # A sliding window over the looped fragments, like a real live playlist.
            count = len(self.fragments.segments)
            newest = int((time.time() - self.started) / self.segment_seconds) + 6
            first = newest - 5
            lines = ["#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{int(self.segment_seconds + 0.999)}",
                     f"#EXT-X-MEDIA-SEQUENCE:{first}", f"#EXT-X-DISCONTINUITY-SEQUENCE:{(first - 1) // count}",
                     '#EXT-X-MAP:URI="/segment/init.mp4"']
            for sequence in range(first, newest + 1):
                if sequence % count == 0:
                    lines.append("#EXT-X-DISCONTINUITY")
                lines.append(f"#EXTINF:{self.segment_seconds:.3f},")
                lines.append(f"/segment/{sequence % count}.m4s")
            return self.send(("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl")
        # The "raw" stream: the loop file once through, then the connection ends,
        # the way a panel drops a raw stream now and then.
        self.send_file(self.media / "live.mp4", "video/mp4")

    def segment(self, path):
        if self.fragments is None:
            return self.send(b"", "text/plain", 404)
        name = path.rsplit("/", 1)[-1]
        if name == "init.mp4":
            return self.send(self.fragments.init, "video/mp4")
        index = int(name.split(".")[0]) % len(self.fragments.segments)
        self.send(self.fragments.segments[index], "video/iso.segment")

    def vod(self):
        movie = self.media / "movie.mp4" if self.media else None
        if movie is None or not movie.exists():
            return self.send(b"", "text/plain", 404)
        self.send_file(movie, "video/mp4")


def main():
    parser = argparse.ArgumentParser(description="A pretend Xtream Codes panel for testing HillTV.")
    parser.add_argument("--port", type=int, default=8089)
    parser.add_argument("--media", type=Path, help="folder with live.mp4 (fragmented) and movie.mp4")
    parser.add_argument("--loop-seconds", type=float, default=30, help="how long live.mp4 runs")
    parser.add_argument("--connections", type=int, default=2, help="max_connections the account reports")
    parser.add_argument("--quiet", action="store_true", help="don't log requests")
    args = parser.parse_args()

    Panel.media = args.media
    Panel.connections = args.connections
    Panel.quiet = args.quiet
    if args.media and (args.media / "live.mp4").exists():
        Panel.fragments = Fragments(args.media / "live.mp4")
        Panel.segment_seconds = args.loop_seconds / len(Panel.fragments.segments)
        print(f"Live loop: {len(Panel.fragments.segments)} fragments of {Panel.segment_seconds:.2f} s")
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Panel)
    print(f"Pretend panel on port {args.port}. Sign in with any username and password.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
