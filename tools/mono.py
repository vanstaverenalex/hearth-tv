#!/usr/bin/env python3
"""Turn every guide logo into a clean one-colour mark (white for dark mode, ink for light mode),
like the logo column in Hulu's own guide.

Logos printed on a solid plate (FS1, Fox News, Food Network, TLC...) have the plate knocked out so
only the lettering remains; everything else becomes a silhouette of its own shape.
  python3 tools/mono.py <in_dir> <out_dir> [ids...]
Reads <in_dir>/<id>.png (the colour logo), writes <out_dir>/<id>.png and <out_dir>/<id>-light.png.
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageFilter

W, H, PAD = 320, 160, 10
INK = (11, 23, 38)
RAW = {"espnu", "mlbn"}  # converted from the untouched original (logos/raw) instead of the colour master
FORCE = {  # per-logo overrides when the automatic choice is wrong
    "nfln": "cut", "msnow": "cut", "mtv": "cut", "trutv": "cut", "cn": "cut",
    "tbs": "lum", "food": "lum", "espnu": "cut", "mlbn": "lum",
    "nick": "shape", "nickjr": "shape", "tnt": "shape", "paramount": "shape", "wzzm": "shape", "bravo": "shape",
}


def smooth(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def mono_alpha(im, cid, force=None):
    rgba = np.asarray(im.convert("RGBA")).astype(np.float32)
    rgb, a = rgba[..., :3], rgba[..., 3] / 255.0
    opaque = a > 0.5
    if opaque.sum() < 20:
        return a
    # Pixels on the outer edge of the mark (next to transparency) tell us the plate colour, if any.
    trans = Image.fromarray(((~opaque) * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))
    edge = opaque & (np.asarray(trans) > 0)
    c0 = np.median(rgb[edge], axis=0) if edge.sum() > 10 else np.median(rgb[opaque], axis=0)
    dist = np.sqrt(((rgb - c0) ** 2).sum(-1)) / 441.0
    plate = opaque & (dist < 0.16)
    inner = opaque & (dist > 0.30)
    frac_plate = plate.sum() / opaque.sum()
    frac_inner = inner.sum() / opaque.sum()
    inner_on_edge = (inner & edge).sum() / max(1, inner.sum())
    mode = force or FORCE.get(cid)
    if mode is None:
        mode = "knock" if (frac_plate > 0.35 and frac_inner > 0.05 and inner_on_edge < 0.12) else "shape"
    if mode == "knock":   # plate removed, lettering kept
        return a * smooth(dist, 0.14, 0.32), mode
    if mode == "cut":     # shape kept, inner details punched out (e.g. MTV's "tv")
        return a * (1 - smooth(dist, 0.14, 0.32)), mode
    if mode == "lum":     # keep only the light parts (white lettering on a coloured badge)
        lum = (0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]) / 255
        return a * smooth(lum, 0.55, 0.85), mode
    return a, mode


def fit(alpha):
    m = Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8))
    box = m.point(lambda v: 255 if v > 14 else 0).getbbox()
    if box: m = m.crop(box)
    s = min((W - 2 * PAD) / m.width, (H - 2 * PAD) / m.height)
    m = m.resize((max(1, round(m.width * s)), max(1, round(m.height * s))), Image.LANCZOS)
    out = Image.new("L", (W, H), 0)
    out.paste(m, ((W - m.width) // 2, (H - m.height) // 2))
    return out


def colour(mask, rgb):
    im = Image.new("RGBA", mask.size, rgb + (0,))
    im.putalpha(mask)
    return im


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    ids = sys.argv[3:] or [c["id"] for c in json.load(open("channels.json"))["channels"]]
    os.makedirs(dst, exist_ok=True)
    for cid in ids:
        raw = os.path.join(os.path.dirname(src.rstrip("/")), "raw", f"{cid}.png")
        p = raw if cid in RAW and os.path.exists(raw) else os.path.join(src, f"{cid}.png")
        if not os.path.exists(p):
            print("missing", cid); continue
        alpha, mode = mono_alpha(Image.open(p), cid)
        mask = fit(alpha)
        colour(mask, (255, 255, 255)).save(os.path.join(dst, f"{cid}.png"), optimize=True)
        colour(mask, INK).save(os.path.join(dst, f"{cid}-light.png"), optimize=True)
        print(f"{cid:12s} {mode}")
