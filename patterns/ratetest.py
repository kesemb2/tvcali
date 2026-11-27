"""Short SDR clips at broadcast and film frame rates for the app's practical frame-rate-switch test."""
from __future__ import annotations

from lib import Frame, H, W, even, v8

RATES = {"test_rate_23976": (24000, 1001), "test_rate_25": (25, 1), "test_rate_50": (50, 1)}
SECONDS = 6


def frames(fps_num: int, fps_den: int) -> int:
    return round(SECONDS * fps_num / fps_den)


def make(label: str, total: int):
    """A white bar sweeping across grey, one sweep per clip, so dropped or repeated frames show as a stutter."""
    def frame(i: int) -> Frame:
        f = Frame()
        f.background(v8(60))
        x = even((W - 64) * i / max(total - 1, 1))
        f.y[300:H - 300, x:x + 64] = v8(235)
        f.text(label, W // 2, 120, 120, v8(200), anchor="ma", weight=600)
        return f
    return frame
