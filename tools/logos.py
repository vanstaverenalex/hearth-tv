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
    """Polite download: pause between requests and back off if the host rate-limits us."""
    import time, urllib.error
    for attempt in range(5):
        time.sleep(2.5)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return Image.open(io.BytesIO(r.read())).convert("RGBA")
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            time.sleep(20 * (attempt + 1))


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


def _stats(im):
    lowsat = dark = light = n = 0
    for r, g, b, a in im.resize((96, max(1, int(96 * im.height / im.width)))).getdata():
        if a < 128:
            continue
        n += 1
        l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        if max(r, g, b) - min(r, g, b) < 40: lowsat += 1
        if l < 0.25: dark += 1
        if l > 0.75: light += 1
    n = max(n, 1)
    return lowsat / n, dark / n, light / n


def _map(im, fn):
    out = Image.new("RGBA", im.size)
    out.putdata([fn(p) for p in im.getdata()])
    return out


def for_dark(im):
    """Make a logo readable on dark glass without flattening it."""
    lowsat, dark, light = _stats(im)
    if lowsat > 0.85 and dark > 0.08 and light > 0.08:
        # Black-and-white logo (e.g. a black badge with white letters): invert it.
        return _map(im, lambda p: (255 - p[0], 255 - p[1], 255 - p[2], p[3]))
    def f(p):
        r, g, b, a = p
        l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        if a and (l < 0.2 or (l < 0.38 and max(r, g, b) - min(r, g, b) < 40)):
            return (255, 255, 255, a)
        return p
    return _map(im, f)


def for_light(im):
    """Make a logo readable on light glass: only near-white parts turn ink-dark."""
    lowsat, dark, light = _stats(im)
    if lowsat > 0.85 and dark > 0.08 and light > 0.08:
        return im
    def f(p):
        r, g, b, a = p
        l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        if a and l > 0.86 and max(r, g, b) - min(r, g, b) < 30:
            return INK + (a,)
        return p
    return _map(im, f)


def fit(im):
    im = im.copy()
    im.thumbnail((W - 2 * PAD, H - 2 * PAD), Image.LANCZOS)
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((W - im.width) // 2, (H - im.height) // 2))
    return canvas


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data = json.load(open(os.path.join(root, "channels.json")))
    # Colour masters live in logos/color; tools/mono.py turns them into the one-colour marks the guide shows.
    out = os.path.join(root, "logos", "color"); os.makedirs(out, exist_ok=True)
    only = set(sys.argv[1:])
    for ch in data["channels"]:
        cid, src = ch["id"], ch.get("logoSrc")
        if not src or (only and cid not in only):
            continue
        done = os.path.exists(os.path.join(out, f"{cid}.png"))
        if done and not only and os.environ.get("FORCE") != "1":
            continue
        try:
            im = trim(knock_out_white(fetch(src)))
            # Untouched copy too: some logos convert to one colour better from their original colours.
            os.makedirs(os.path.join(root, "logos", "raw"), exist_ok=True)
            fit(im).save(os.path.join(root, "logos", "raw", f"{cid}.png"), optimize=True)
            lum, _ = luminance(im)
            plate = opaque_box(im) and 0.15 < lum < 0.9  # coloured plate: keep as-is
            dark = im if plate else for_dark(im)
            light = im if plate else for_light(im)
            fit(dark).save(os.path.join(out, f"{cid}.png"), optimize=True)
            print(f"ok   {cid:12s} lum={lum:.2f} plate={plate}")
        except Exception as e:
            print(f"FAIL {cid:12s} {e}")


if __name__ == "__main__":
    main()
