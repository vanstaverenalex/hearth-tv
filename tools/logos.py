#!/usr/bin/env python3
"""Normalize channel logos for the Hearth TV guide.

For each channel in channels.json with a logoSrc:
  logos/<id>.png        - for dark mode (dark logos are turned white so they show on dark glass)
  logos/<id>-light.png  - for light mode (white logos are turned ink-dark)
Both are trimmed, padded and fitted into a 320x160 transparent canvas.
"""
import io, json, os, sys, urllib.request
from PIL import Image, ImageChops

W, H, PAD = 320, 160, 8
INK = (11, 23, 38)
UA = "HearthTV-logo-builder/1.0 (https://github.com/vanstaverenalex/hearth-tv)"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return Image.open(io.BytesIO(r.read())).convert("RGBA")


def knock_out_white(im):
    """If the logo sits on a solid white box, make that box transparent."""
    px = im.load(); w, h = im.size
    corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
    if all(c[3] > 250 and min(c[:3]) > 235 for c in corners):
        data = []
        for r, g, b, a in im.getdata():
            m = min(r, g, b)
            data.append((r, g, b, 0) if m > 240 else (r, g, b, a if m < 200 else int(a * (240 - m) / 40)))
        im.putdata(data)
    return im


def trim(im):
    bbox = im.getchannel("A").point(lambda a: 255 if a > 12 else 0).getbbox()
    return im.crop(bbox) if bbox else im


def luminance(im):
    tot = n = 0
    for r, g, b, a in im.resize((64, max(1, int(64 * im.height / im.width)))).getdata():
        if a > 128:
            tot += 0.2126 * r + 0.7152 * g + 0.0722 * b; n += 1
    return (tot / n / 255) if n else 0.5, n


def opaque_box(im):
    """True if the logo is a solid plate (e.g. a coloured box with text knocked out)."""
    a = im.getchannel("A")
    return sum(1 for v in a.getdata() if v > 200) / (im.width * im.height) > 0.9


def recolor(im, rgb):
    solid = Image.new("RGBA", im.size, rgb + (255,))
    solid.putalpha(im.getchannel("A"))
    return solid


def fit(im):
    im = im.copy()
    im.thumbnail((W - 2 * PAD, H - 2 * PAD), Image.LANCZOS)
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((W - im.width) // 2, (H - im.height) // 2))
    return canvas


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data = json.load(open(os.path.join(root, "channels.json")))
    out = os.path.join(root, "logos"); os.makedirs(out, exist_ok=True)
    only = set(sys.argv[1:])
    for ch in data["channels"]:
        cid, src = ch["id"], ch.get("logoSrc")
        if not src or (only and cid not in only):
            continue
        try:
            im = trim(knock_out_white(fetch(src)))
            lum, _ = luminance(im)
            plate = opaque_box(im) and 0.15 < lum < 0.9  # coloured plate: keep as-is
            dark = im if plate or lum > 0.32 else recolor(im, (255, 255, 255))
            light = im if plate or lum < 0.78 else recolor(im, INK)
            fit(dark).save(os.path.join(out, f"{cid}.png"), optimize=True)
            fit(light).save(os.path.join(out, f"{cid}-light.png"), optimize=True)
            print(f"ok   {cid:12s} lum={lum:.2f} plate={plate}")
        except Exception as e:
            print(f"FAIL {cid:12s} {e}")


if __name__ == "__main__":
    main()
