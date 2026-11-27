"""SDR (BT.709, limited range) patterns. Each function returns a Frame; values are 10-bit codes."""
from __future__ import annotations

import numpy as np

from lib import BT709, Frame, H, W, even, ire, rgb_to_ycbcr, v8

LABEL = v8(60)


def pluge() -> Frame:
    """Black level. Bars below black (12), at black (16) and just above (20, 25) on a black field, plus 1-code steps."""
    f = Frame()
    f.background(v8(16))
    # Three tall bars in the middle: -2 % (12), +2 % (20), +4 % (25).
    bar_w, gap, top, bottom = 240, 240, 380, 1500
    total = 3 * bar_w + 2 * gap
    x = (W - total) // 2
    for code, name in ((12, "below black 12"), (20, "above black 20"), (25, "above black 25")):
        f.gray(x, top, x + bar_w, bottom, v8(code), probe=name)
        f.text(str(code), x + bar_w // 2, bottom + 40, 56, LABEL, anchor="ma")
        x += bar_w + gap
    # Near-black steps, one 8-bit code apart.
    steps = [17, 18, 19, 20, 21, 22, 23, 24, 26, 28, 30]
    sw, sh, sy = 240, 240, 1720
    sx = (W - len(steps) * sw) // 2
    for i, code in enumerate(steps):
        f.gray(sx + i * sw, sy, sx + (i + 1) * sw, sy + sh, v8(code), probe=f"step {code}")
        f.text(str(code), sx + i * sw + sw // 2, sy + sh + 24, 40, LABEL, anchor="ma")
    f.probes.append(_probe_black(f))
    return f


def _probe_black(f: Frame):
    from lib import Probe
    return Probe("background black 16", 100, 100, 200, 200, v8(16))


def white_clipping() -> Frame:
    """Contrast / white clipping: patches around reference white (235) up to the top of the video range (254)."""
    f = Frame()
    f.background(v8(128))
    codes = [225, 228, 230, 233, 235, 238, 240, 243, 245, 248, 250, 254]
    pw, ph = 280, 900
    x0 = (W - len(codes) * pw) // 2
    y0 = (H - ph) // 2 - 60
    for i, code in enumerate(codes):
        f.gray(x0 + i * pw, y0, x0 + (i + 1) * pw, y0 + ph, v8(code), probe=f"white {code}")
        f.text(str(code), x0 + i * pw + pw // 2, y0 + ph + 40, 56, v8(220), anchor="ma")
    f.text("235 = reference white", W // 2, y0 - 120, 56, v8(200), anchor="ma")
    return f


def grayscale() -> Frame:
    """11 steps 0-100 %, a 10-bit ramp, the same ramp in 8-bit steps, and a 0-20 % shadow ramp."""
    f = Frame()
    f.background(v8(16))
    band = [0, 860, 1240, 1620, H]
    # Steps (each 10 %), labels underneath in the band itself.
    edges = [even(i * W / 11) for i in range(12)]
    for i in range(11):
        f.gray(edges[i], band[0], edges[i + 1], band[1] - 100, ire(i * 10), probe=f"step {i * 10}%")
        f.text(f"{i * 10}%", (edges[i] + edges[i + 1]) // 2, band[1] - 80, 48, v8(180), anchor="ma")
    x = np.arange(W, dtype=np.float64) / (W - 1)
    ramp10 = np.round(64 + 876 * x).astype(np.uint16)
    ramp8 = (np.round((16 + 219 * x)) * 4).astype(np.uint16)
    dark = np.round(64 + 876 * 0.2 * x).astype(np.uint16)
    for (y0, y1), row in zip(((band[1], band[2] - 20), (band[2], band[3] - 20), (band[3], band[4])),
                             (ramp10, ramp8, dark)):
        f.y[y0:y1, :] = row[None, :]
    f.text("10-bit", 40, band[1] + 20, 44, v8(235))
    f.text("8-bit", 40, band[2] + 20, 44, v8(235))
    f.text("0-20 %", 40, band[3] + 20, 44, v8(235))
    return f


def color_bars() -> Frame:
    """75 % and 100 % colour bars (BT.709): white, yellow, cyan, green, magenta, red, blue."""
    f = Frame()
    f.background(v8(16))
    colors = [(1, 1, 1), (1, 1, 0), (0, 1, 1), (0, 1, 0), (1, 0, 1), (1, 0, 0), (0, 0, 1)]
    names = ["white", "yellow", "cyan", "green", "magenta", "red", "blue"]
    edges = [even(i * W / 7) for i in range(8)]
    for level, (y0, y1) in ((0.75, (0, 1300)), (1.0, (1340, 2000))):
        for i, (rgb, name) in enumerate(zip(colors, names)):
            yv, cb, cr = rgb_to_ycbcr(*(c * level for c in rgb), BT709)
            f.fill(edges[i], y0, edges[i + 1], y1, yv, cb, cr)
            mx, my = 60, 60
            from lib import Probe
            f.probes.append(Probe(f"{int(level * 100)}% {name}", edges[i] + mx, y0 + my,
                                  edges[i + 1] - edges[i] - 2 * mx, y1 - y0 - 2 * my, yv))
    f.text("75 %", 40, 1240, 44, v8(16))
    f.text("100 %", 40, 1940, 44, v8(16))
    return f


def sharpness() -> Frame:
    """1-3 px line groups, a 1 px checkerboard (proof of native 4K) and thin lines on grey to see edge halos."""
    f = Frame()
    grey = v8(128)
    f.background(grey)
    black, white = v8(16), v8(235)

    def grille(x0, y0, w, h, period, vertical):
        for i in range(0, w if vertical else h, period * 2):
            if vertical:
                f.y[y0:y0 + h, x0 + i:x0 + min(i + period, w)] = white
                f.y[y0:y0 + h, x0 + i + period:x0 + min(i + 2 * period, w)] = black
            else:
                f.y[y0 + i:y0 + min(i + period, h), x0:x0 + w] = white
                f.y[y0 + i + period:y0 + min(i + 2 * period, h), x0:x0 + w] = black

    # Left column: vertical and horizontal grilles at 1, 2, 3 px.
    for row, period in enumerate((1, 2, 3)):
        y0 = 200 + row * 560
        grille(200, y0, 480, 420, period, vertical=True)
        grille(760, y0, 480, 420, period, vertical=False)
        f.text(f"{period} px", 1290, y0 + 170, 60, v8(235))
    # Centre: 1 px checkerboard. Upscaled 1080p would show it as flat grey.
    cx0, cy0, cs = 1560, 640, 880
    yy, xx = np.mgrid[0:cs, 0:cs]
    f.y[cy0:cy0 + cs, cx0:cx0 + cs] = np.where((xx + yy) % 2 == 0, white, black).astype(np.uint16)
    f.text("1 px checkerboard", cx0 + cs // 2, cy0 + cs + 40, 56, v8(235), anchor="ma")
    # Right: thin black and white lines on grey, plus a black box with a white outline: look for halos.
    rx0 = 2700
    for i, (thick, val) in enumerate(((1, white), (2, white), (4, white), (1, black), (2, black), (4, black))):
        x = rx0 + i * 140
        f.y[200:1300, x:x + thick] = val
        f.y[1400 + i * 70:1400 + i * 70 + thick, rx0:rx0 + 900] = val
    f.y[1880:2060, rx0:rx0 + 900] = black
    f.y[1880:1884, rx0:rx0 + 900] = white
    f.y[2056:2060, rx0:rx0 + 900] = white
    from lib import Probe
    f.probes.append(Probe("grey field", 1700, 200, 300, 300, grey))
    return f


def overscan() -> Frame:
    """Edge frames: a 1 px white line on the outermost pixels, then 1 px lines every 2 px, and 1/2.5/5 % markers."""
    f = Frame()
    f.background(v8(16))
    white, mid = v8(235), v8(126)

    def rect(inset, val, t=1):
        f.y[inset:inset + t, inset:W - inset] = val
        f.y[H - inset - t:H - inset, inset:W - inset] = val
        f.y[inset:H - inset, inset:inset + t] = val
        f.y[inset:H - inset, W - inset - t:W - inset] = val

    rect(0, white)
    for k in range(1, 8):
        rect(2 * k, mid)
    # Safe-area markers, labelled at different points along the top so the labels never collide.
    for pct, lx in ((1, 0.30), (2.5, 0.50), (5, 0.70)):
        inset = even(H * pct / 100)
        rect(inset, v8(90), t=2)
        f.text(f"{pct:g} %", int(W * lx), inset + 14, 44, v8(140), anchor="ma")
    f.text("3840 × 2160", W // 2, H // 2 - 120, 120, v8(200), anchor="mm", weight=600)
    f.text("All four outer white edges must be visible", W // 2, H // 2 + 60, 60, v8(160), anchor="mm")
    from lib import Probe
    f.probes.append(Probe("outer line top", 100, 0, 3640, 1, white))
    f.probes.append(Probe("outer line left", 0, 100, 1, 1960, white))
    return f


PATTERNS = {
    "sdr_pluge": pluge,
    "sdr_white_clipping": white_clipping,
    "sdr_grayscale": grayscale,
    "sdr_color_bars": color_bars,
    "sdr_sharpness": sharpness,
    "sdr_overscan": overscan,
}
