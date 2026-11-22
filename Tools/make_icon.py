#!/usr/bin/env python3
"""Regenerates HillTV's app icon and Top Shelf images.

    python3 Tools/make_icon.py

The icon ("Sunrise") is three stacked layers that tvOS slides against each
other when the icon has focus: a navy sky at the back, a white sun with a
play triangle in the middle, and two blue hills in front. The layers are
drawn with Pillow, oversized and scaled down so the edges are smooth.

The Top Shelf banners carry the wordmark, which needs the Inter typeface.
They are rendered from SVG by headless Chrome, which fetches Inter from
Google Fonts, so that part needs google-chrome and a network connection.
Check the wordmark by eye afterwards: a missing font falls back silently.

Needs: Pillow (pip install pillow).
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / "App" / "Assets.xcassets" / "App Icon & Top Shelf Image.brandassets"

SKY = (15, 26, 46)
SUN = (242, 245, 250)
HILL_BACK = (47, 107, 221)
HILL_FRONT = (91, 155, 255)

# Everything below is laid out on an 800 x 480 canvas and scaled from there.
BASE_W, BASE_H = 800, 480
OVERSAMPLE = 4
INFO = {"author": "xcode", "version": 1}


def canvas(width, height, fill):
    return Image.new("RGBA", (width * OVERSAMPLE, height * OVERSAMPLE), fill)


def finish(image, width, height, opaque=False):
    image = image.resize((width, height), Image.LANCZOS)
    return image.convert("RGB") if opaque else image


def draw_sky(width, height):
    return finish(canvas(width, height, SKY + (255,)), width, height, opaque=True)


def draw_sun(width, height):
    image = canvas(width, height, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    k = width * OVERSAMPLE / BASE_W
    cx, cy, r = 430 * k, 212 * k, 108 * k
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=SUN + (255,))
    # Play triangle with rounded corners: the shape, a thick outline, and a dot on each corner.
    points = [(400 * k, 158 * k), (400 * k, 266 * k), (490 * k, 212 * k)]
    half = 7 * k
    draw.polygon(points, fill=SKY + (255,))
    draw.line(points + [points[0]], fill=SKY + (255,), width=int(round(half * 2)))
    for x, y in points:
        draw.ellipse([x - half, y - half, x + half, y + half], fill=SKY + (255,))
    return finish(image, width, height)


def draw_hills(width, height):
    image = canvas(width, height, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    k = width * OVERSAMPLE / BASE_W
    for cx, cy, rx, ry, color in [(210, 530, 430, 255, HILL_BACK), (630, 570, 450, 245, HILL_FRONT)]:
        draw.ellipse([(cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k], fill=color + (255,))
    return finish(image, width, height)


# Front first, the order tvOS stacks them.
LAYERS = [("Front", "hills", draw_hills), ("Middle", "sun", draw_sun), ("Back", "sky", draw_sky)]


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


def make_stack(name, width, height, scales):
    stack = BRAND / f"{name}.imagestack"
    write_json(stack / "Contents.json", {
        "info": INFO,
        "layers": [{"filename": f"{layer}.imagestacklayer"} for layer, _, _ in LAYERS],
        "properties": {"canvasSize": {"width": width, "height": height}},
    })
    for layer, stem, draw in LAYERS:
        folder = stack / f"{layer}.imagestacklayer"
        write_json(folder / "Contents.json", {
            "info": INFO,
            "properties": {
                "frame-size": {"width": width, "height": height},
                "frame-center": {"x": width // 2, "y": height // 2},
            },
        })
        images = []
        for scale in scales:
            filename = f"{stem}.png" if scale == 1 else f"{stem}@{scale}x.png"
            imageset = folder / "Content.imageset"
            imageset.mkdir(parents=True, exist_ok=True)
            draw(width * scale, height * scale).save(imageset / filename, optimize=True)
            images.append({"idiom": "tv", "filename": filename, "scale": f"{scale}x"})
        write_json(folder / "Content.imageset" / "Contents.json", {"info": INFO, "images": images})


def banner_svg(width, height, scale):
    # Sun, hills and wordmark spread across the wider canvases.
    wide = width > 1920
    sun_x = 640 if wide else 520
    text_x = 930 if wide else 800
    back = (380, 900, 1050, 440) if wide else (300, 900, 900, 440)
    front = (1800, 980, 1300, 440) if wide else (1500, 980, 1100, 440)
    return f"""<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@800&display=block">
<style>html,body{{margin:0;background:#0f1a2e}}svg{{display:block}}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="{width * scale}" height="{height * scale}" viewBox="0 0 {width} {height}">
<rect width="{width}" height="{height}" fill="#0f1a2e"/>
<circle cx="{sun_x}" cy="330" r="170" fill="#f2f5fa"/>
<path d="M{sun_x - 48} 246 L{sun_x - 48} 414 L{sun_x + 92} 330 Z" fill="#0f1a2e" stroke="#0f1a2e" stroke-width="20" stroke-linejoin="round"/>
<ellipse cx="{back[0]}" cy="{back[1]}" rx="{back[2]}" ry="{back[3]}" fill="#2f6bdd"/>
<ellipse cx="{front[0]}" cy="{front[1]}" rx="{front[2]}" ry="{front[3]}" fill="#5b9bff"/>
<text x="{text_x}" y="400" font-family="Inter, sans-serif" font-weight="800" font-size="200" letter-spacing="-6" fill="#f2f5fa">Hill<tspan fill="#5b9bff">TV</tspan></text>
</svg></body></html>"""


def make_banner(name, width, height):
    chrome = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    if chrome is None:
        sys.exit("Top Shelf banners need google-chrome or chromium on the PATH.")
    folder = BRAND / f"{name}.imageset"
    folder.mkdir(parents=True, exist_ok=True)
    stem = name.lower().replace(" ", "-")
    images = []
    for scale in (1, 2):
        filename = f"{stem}.png" if scale == 1 else f"{stem}@{scale}x.png"
        with tempfile.TemporaryDirectory() as work:
            page = Path(work) / "banner.html"
            shot = Path(work) / "banner.png"
            page.write_text(banner_svg(width, height, scale))
            subprocess.run([
                chrome, "--headless=new", "--no-sandbox", "--hide-scrollbars",
                f"--window-size={width * scale},{height * scale}", "--virtual-time-budget=8000",
                f"--screenshot={shot}", page.as_uri(),
            ], check=True, capture_output=True)
            image = Image.open(shot).convert("RGB")
            if image.size != (width * scale, height * scale):
                sys.exit(f"{filename}: Chrome made {image.size}, wanted {(width * scale, height * scale)}")
            image.save(folder / filename, optimize=True)
        images.append({"idiom": "tv", "filename": filename, "scale": f"{scale}x"})
    write_json(folder / "Contents.json", {"info": INFO, "images": images})


def main():
    if BRAND.exists():
        shutil.rmtree(BRAND)
    write_json(BRAND.parent / "Contents.json", {"info": INFO})
    write_json(BRAND / "Contents.json", {
        "info": INFO,
        "assets": [
            {"filename": "App Icon - App Store.imagestack", "size": "1280x768", "idiom": "tv", "role": "primary-app-icon"},
            {"filename": "App Icon.imagestack", "size": "400x240", "idiom": "tv", "role": "primary-app-icon"},
            {"filename": "Top Shelf Image.imageset", "size": "1920x720", "idiom": "tv", "role": "top-shelf-image"},
            {"filename": "Top Shelf Image Wide.imageset", "size": "2320x720", "idiom": "tv", "role": "top-shelf-image-wide"},
        ],
    })
    make_stack("App Icon", 400, 240, scales=(1, 2))
    make_stack("App Icon - App Store", 1280, 768, scales=(1,))
    make_banner("Top Shelf Image", 1920, 720)
    make_banner("Top Shelf Image Wide", 2320, 720)
    print(f"Wrote {BRAND.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
