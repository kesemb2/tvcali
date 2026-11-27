"""HDR10 patterns: PQ (SMPTE ST 2084), BT.2020, 10-bit limited range. Values are absolute luminance in cd/m²."""
from __future__ import annotations

import numpy as np

from lib import Frame, H, Probe, W, even, pq

LABEL = pq(20)


def _nits_label(n: float) -> str:
    return f"{n:g}"


def black_clipping() -> Frame:
    """Black level in HDR: bars below black and just above it, plus 2-code steps, on a 0 cd/m² field."""
    f = Frame()
    f.background(64)
    bar_w, gap, top, bottom = 240, 240, 380, 1500
    x = (W - (3 * bar_w + 2 * gap)) // 2
    for code, name in ((56, "below black 56"), (72, "above black 72"), (80, "above black 80")):
        f.gray(x, top, x + bar_w, bottom, code, probe=name)
        f.text(str(code), x + bar_w // 2, bottom + 40, 56, LABEL, anchor="ma")
        x += bar_w + gap
    steps = [66, 68, 70, 72, 74, 76, 78, 80, 84, 88, 96]
    sw, sh, sy = 240, 240, 1720
    sx = (W - len(steps) * sw) // 2
    for i, code in enumerate(steps):
        f.gray(sx + i * sw, sy, sx + (i + 1) * sw, sy + sh, code, probe=f"step {code}")
        f.text(str(code), sx + i * sw + sw // 2, sy + sh + 24, 40, LABEL, anchor="ma")
    f.probes.append(Probe("background 0 nits", 100, 100, 200, 200, 64))
    return f


def _white_clipping(levels: list[float], title: str) -> Frame:
    f = Frame()
    f.background(pq(40))
    pw, ph = 280, 900
    x0 = (W - len(levels) * pw) // 2
    y0 = (H - ph) // 2 - 60
    for i, n in enumerate(levels):
        f.gray(x0 + i * pw, y0, x0 + (i + 1) * pw, y0 + ph, pq(n), probe=f"{n:g} nits")
        f.text(_nits_label(n), x0 + i * pw + pw // 2, y0 + ph + 40, 56, pq(150), anchor="ma")
    f.text(title, W // 2, y0 - 120, 56, pq(150), anchor="ma")
    return f


def white_clipping_1000() -> Frame:
    return _white_clipping([400, 500, 600, 700, 800, 900, 1000, 1200, 1500, 2000, 4000, 10000],
                           "cd/m²  ·  mastered for 1000")


def white_clipping_4000() -> Frame:
    return _white_clipping([600, 800, 1000, 1200, 1500, 2000, 2500, 3000, 3500, 4000, 6000, 10000],
                           "cd/m²  ·  mastered for 4000")


def grayscale() -> Frame:
    """Steps in cd/m², a 0-1000 cd/m² PQ ramp and a 0-10 cd/m² shadow ramp."""
    f = Frame()
    f.background(64)
    levels = [0, 0.1, 0.5, 1, 5, 10, 25, 50, 100, 203, 400, 600, 800, 1000]
    edges = [even(i * W / len(levels)) for i in range(len(levels) + 1)]
    for i, n in enumerate(levels):
        f.gray(edges[i], 0, edges[i + 1], 760, pq(n), probe=f"step {n:g} nits")
        f.text(_nits_label(n), (edges[i] + edges[i + 1]) // 2, 780, 44, pq(100), anchor="ma")
    x = np.arange(W, dtype=np.float64) / (W - 1)
    top = pq(1000)
    f.y[900:1500, :] = np.round(64 + (top - 64) * x).astype(np.uint16)[None, :]
    dark_top = pq(10)
    f.y[1560:H, :] = np.round(64 + (dark_top - 64) * x).astype(np.uint16)[None, :]
    f.text("PQ 0 – 1000 cd/m²", 40, 920, 44, pq(5))
    f.text("PQ 0 – 10 cd/m²", 40, 1580, 44, pq(100))
    return f


def subtitles() -> Frame:
    """White subtitles (203 cd/m², HDR reference white) on black: halos around letters are local-dimming blooming."""
    f = Frame()
    f.background(64)
    white = pq(203)
    f.text("Local dimming should keep the black around", W // 2, H - 420, 110, white, anchor="ma", weight=600)
    f.text("these letters as black as the rest of the screen.", W // 2, H - 270, 110, white, anchor="ma", weight=600)
    f.probes.append(Probe("black", 100, 100, 400, 400, 64))
    return f


def starfield() -> Frame:
    """Small stars of 2-6 px from 100 to 1000 cd/m² on black (fixed seed, so the file never changes)."""
    f = Frame()
    f.background(64)
    rng = np.random.default_rng(2160)
    for _ in range(700):
        s = int(rng.choice([2, 2, 2, 4, 4, 6]))
        x = even(rng.integers(0, W - 8))
        y = even(rng.integers(0, H - 8))
        f.y[y:y + s, x:x + s] = pq(float(rng.choice([100, 200, 400, 600, 1000])))
    return f


# ---- Moving patterns: a function of the frame index. ---------------------------------------------------------

BOX = 160
MOTION_FRAMES = 240  # 10 s at 23.976


def moving_box(i: int) -> Frame:
    """A 1000 cd/m² box gliding over black along a Lissajous path that closes after MOTION_FRAMES."""
    f = Frame()
    f.background(64)
    t = 2 * np.pi * i / MOTION_FRAMES
    cx = (W - BOX) / 2 + (W - BOX - 200) / 2 * np.sin(t)
    cy = (H - BOX) / 2 + (H - BOX - 200) / 2 * np.sin(2 * t)
    x, y = even(cx), even(cy)
    f.y[y:y + BOX, x:x + BOX] = pq(1000)
    return f


_PAN_TILE: np.ndarray | None = None
PAN_STEP = 8  # px per frame: 192 px/s, slow enough to see 24p judder clearly.


def _pan_tile() -> np.ndarray:
    """A 1920 px wide tile (two of them fill the screen), so a 1920 px shift loops seamlessly."""
    global _PAN_TILE
    if _PAN_TILE is None:
        t = Frame()
        t.background(pq(5))
        for k, x in enumerate(range(0, 1920, 120)):
            t.y[200:1960, x:x + (16 if k % 4 else 48)] = pq(203)
        for k, x in enumerate(range(60, 1920, 240)):
            t.y[1000:1160, x:x + 120] = pq(100 if k % 2 else 400)
        t.text("24p", 960, 560, 300, pq(203), anchor="mm", weight=700)
        _PAN_TILE = t.y[:, :1920].copy()
    return _PAN_TILE


def pan(i: int) -> Frame:
    f = Frame()
    tile = _pan_tile()
    wide = np.concatenate([tile, tile, tile], axis=1)
    shift = (i * PAN_STEP) % 1920
    f.y[:] = wide[:, shift:shift + W]
    return f


PATTERNS = {
    # id: (static frame or per-frame function, mastering peak, declared MaxCLL or None to measure)
    "hdr_black_clipping": (black_clipping, 1000, None),
    "hdr_white_clipping_1000": (white_clipping_1000, 1000, 1000),
    "hdr_white_clipping_4000": (white_clipping_4000, 4000, 4000),
    "hdr_grayscale": (grayscale, 1000, None),
    "hdr_dimming_box": (moving_box, 1000, None),
    "hdr_subtitles": (subtitles, 1000, None),
    "hdr_starfield": (starfield, 1000, None),
    "hdr_pan_24p": (pan, 1000, None),
}
MOTION = {"hdr_dimming_box", "hdr_pan_24p"}
