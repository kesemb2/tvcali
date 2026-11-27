"""Pixel-exact test pattern frames as 10-bit Y'CbCr 4:2:0 planes.

Patterns are built directly in Y'CbCr code values, so neutral greys are exact (Cb = Cr = 512) and no RGB -> YCbCr
conversion in ffmpeg can round them. Colours go through the matrix of the pattern's colour space, once, here.
Everything is aligned to even pixels so 4:2:0 chroma never straddles an edge.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 3840, 2160

# Luma/chroma matrices (Kr, Kb).
BT709 = (0.2126, 0.0722)
BT2020 = (0.2627, 0.0593)

FONT_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "font", "inter.ttf")


def v8(code8: float) -> int:
    """8-bit video code value -> 10-bit (exact multiples of 4)."""
    return int(round(code8 * 4))


def ire(percent: float) -> int:
    """Percent of the limited video range (0 % = 64, 100 % = 940), 10-bit."""
    return int(round(64 + 876 * percent / 100.0))


def rgb_to_ycbcr(r: float, g: float, b: float, matrix=BT709) -> tuple[int, int, int]:
    """Non-linear R'G'B' in 0..1 -> 10-bit limited-range Y'CbCr codes."""
    kr, kb = matrix
    kg = 1 - kr - kb
    y = kr * r + kg * g + kb * b
    cb = (b - y) / (2 * (1 - kb))
    cr = (r - y) / (2 * (1 - kr))
    return (int(round(64 + 876 * y)), int(round(512 + 896 * cb)), int(round(512 + 896 * cr)))


@dataclass
class Probe:
    """A region whose decoded luma must equal [y] exactly (checked in CI after encode + decode)."""
    name: str
    x: int
    y: int
    w: int
    h: int
    value: int


@dataclass
class Frame:
    y: np.ndarray = field(default_factory=lambda: np.full((H, W), 64, np.uint16))
    cb: np.ndarray = field(default_factory=lambda: np.full((H, W), 512, np.uint16))
    cr: np.ndarray = field(default_factory=lambda: np.full((H, W), 512, np.uint16))
    probes: list[Probe] = field(default_factory=list)

    def fill(self, x0: int, y0: int, x1: int, y1: int, yv: int, cb: int = 512, cr: int = 512) -> None:
        self.y[y0:y1, x0:x1] = yv
        self.cb[y0:y1, x0:x1] = cb
        self.cr[y0:y1, x0:x1] = cr

    def gray(self, x0: int, y0: int, x1: int, y1: int, yv: int, probe: str | None = None) -> None:
        self.fill(x0, y0, x1, y1, yv)
        if probe:
            # Probe the inner part, away from edges and labels.
            mx, my = (x1 - x0) // 4, (y1 - y0) // 4
            self.probes.append(Probe(probe, x0 + mx, y0 + my, x1 - x0 - 2 * mx, y1 - y0 - 2 * my, yv))

    def background(self, yv: int) -> None:
        self.y[:] = yv
        self.cb[:] = 512
        self.cr[:] = 512

    def text(self, s: str, x: int, y: int, size: int, yv: int, anchor: str = "la", weight: int = 500) -> None:
        """Draws neutral text (luma only, hard edged: no anti-aliasing, so every pixel is a known code value)."""
        font = ImageFont.truetype(FONT_PATH, size)
        try:
            font.set_variation_by_axes([18, weight])
        except Exception:
            pass
        mask = Image.new("1", (W, H), 0)
        ImageDraw.Draw(mask).text((x, y), s, font=font, fill=1, anchor=anchor)
        m = np.asarray(mask, dtype=bool)
        self.y[m] = yv
        self.cb[m] = 512
        self.cr[m] = 512

    def planes(self) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Full-resolution chroma -> 4:2:0 by averaging each 2 x 2 block."""
        def sub(c: np.ndarray) -> np.ndarray:
            c = c.astype(np.uint32)
            s = c[0::2, 0::2] + c[1::2, 0::2] + c[0::2, 1::2] + c[1::2, 1::2]
            return ((s + 2) // 4).astype(np.uint16)
        return self.y, sub(self.cb), sub(self.cr)

    def write_raw(self, path: str) -> None:
        y, cb, cr = self.planes()
        with open(path, "wb") as f:
            for p in (y, cb, cr):
                f.write(np.ascontiguousarray(p, dtype="<u2").tobytes())


def even(v: float) -> int:
    return int(round(v / 2.0)) * 2
