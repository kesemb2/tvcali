#!/usr/bin/env python3
"""Generates, encodes and verifies every test pattern, then writes manifest.json.

  python3 patterns/build.py out/

Verification (fails the build on any mismatch):
  * ffprobe: codec, profile, size, pixel format, frame rate, frame count and the colour tags (and, for HDR, the
    mastering display and content light level side data).
  * every decoded frame is compared with the source frame (ffmpeg framemd5 against the md5 of the raw planes),
    so the files are bit-exact, not just "close".
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys

import sdr
from lib import H, W

VERSION = int(open(os.path.join(os.path.dirname(__file__), "VERSION")).read().strip())
FPS_NUM, FPS_DEN = 24000, 1001
STATIC_FRAMES = 144  # 6 s at 23.976; the app loops them.

SDR_TAGS = {"color_primaries": "bt709", "color_transfer": "bt709", "color_space": "bt709", "color_range": "tv"}


def run(cmd: list[str], **kw) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout


def encode(raw: str, out: str, frames: int, kind: str) -> None:
    assert kind == "sdr"
    x265 = ":".join([
        "lossless=1", "keyint=600", "min-keyint=600", "log-level=error",
        "colorprim=bt709", "transfer=bt709", "colormatrix=bt709", "range=limited",
    ])
    run([
        "ffmpeg", "-v", "error", "-y",
        "-stream_loop", str(frames - 1),
        # Tag the raw input too: without it newer ffmpeg (8.x) may assume another range or matrix and insert a
        # conversion, and the "lossless" file would no longer hold our code values.
        "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
        "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{W}x{H}", "-r", f"{FPS_NUM}/{FPS_DEN}", "-i", raw,
        "-frames:v", str(frames),
        "-sws_flags", "bitexact", "-c:v", "libx265", "-preset", "medium", "-x265-params", x265, "-pix_fmt", "yuv420p10le",
        "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv",
        "-tag:v", "hvc1", "-movflags", "+faststart", "-an", out,
    ])


def verify(out: str, raw: str, frames: int, tags: dict[str, str]) -> None:
    info = json.loads(run(["ffprobe", "-v", "error", "-show_streams", "-count_frames", "-of", "json", out]))
    (s,) = info["streams"]
    expect = {
        "codec_name": "hevc", "profile": "Main 10", "width": W, "height": H, "pix_fmt": "yuv420p10le",
        "r_frame_rate": f"{FPS_NUM}/{FPS_DEN}", "nb_read_frames": str(frames), **tags,
    }
    wrong = {k: (s.get(k), v) for k, v in expect.items() if s.get(k) != v}
    if wrong:
        raise SystemExit(f"{out}: ffprobe mismatch (got, expected): {wrong}")

    source_md5 = hashlib.md5(open(raw, "rb").read()).hexdigest()
    lines = run(["ffmpeg", "-v", "error", "-i", out, "-f", "framemd5", "-pix_fmt", "yuv420p10le", "-"]).splitlines()
    md5s = [l.split(",")[-1].strip() for l in lines if l and not l.startswith("#")]
    if len(md5s) != frames or any(m != source_md5 for m in md5s):
        bad = sum(m != source_md5 for m in md5s)
        raise SystemExit(f"{out}: decoded frames differ from the source ({bad} of {len(md5s)} frames); "
                         f"first frame: {describe_difference(out, raw)}")


def describe_difference(out: str, raw: str) -> str:
    """Where and by how much the first decoded frame differs, per plane."""
    import numpy as np
    decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", out, "-frames:v", "1", "-f", "rawvideo",
                              "-pix_fmt", "yuv420p10le", "-"], check=True, capture_output=True).stdout
    a = np.frombuffer(open(raw, "rb").read(), "<u2").astype(np.int32)
    b = np.frombuffer(decoded, "<u2").astype(np.int32)
    if a.size != b.size:
        return f"size {b.size} vs {a.size} samples"
    n = W * H
    parts = []
    for name, sl in (("Y", slice(0, n)), ("Cb", slice(n, n + n // 4)), ("Cr", slice(n + n // 4, None))):
        d = b[sl] - a[sl]
        if d.any():
            i = int(np.argmax(np.abs(d)))
            parts.append(f"{name}: {int((d != 0).sum())} samples differ, max {int(np.abs(d).max())} "
                         f"(source {int(a[sl][i])} -> {int(b[sl][i])})")
    return "; ".join(parts) or "identical"


def tool_versions() -> str:
    ffmpeg = run(["ffmpeg", "-version"]).splitlines()[0]
    x265 = subprocess.run(["ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "color=s=64x64:d=0.04", "-c:v", "libx265",
                           "-f", "null", "-"], capture_output=True, text=True).stderr
    x265 = next((l.strip() for l in x265.splitlines() if "x265 [info]: HEVC encoder version" in l), "x265 ?")
    return f"{ffmpeg} | {x265}"


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "out"
    os.makedirs(out_dir, exist_ok=True)
    print(tool_versions(), flush=True)
    tmp = os.path.join(out_dir, "frame.yuv")
    entries = []
    for pid, make in sdr.PATTERNS.items():
        frame = make()
        frame.write_raw(tmp)
        out = os.path.join(out_dir, f"{pid}.mp4")
        encode(tmp, out, STATIC_FRAMES, "sdr")
        verify(out, tmp, STATIC_FRAMES, SDR_TAGS)
        data = open(out, "rb").read()
        entries.append({
            "id": pid,
            "file": f"{pid}.mp4",
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            # Identity of the pattern itself (the raw source frame): encoders are not bit-reproducible, sources are.
            "source_md5": hashlib.md5(open(tmp, "rb").read()).hexdigest(),
            "format": "sdr",
            "fps": f"{FPS_NUM}/{FPS_DEN}",
            "frames": STATIC_FRAMES,
            # Exact luma code values the app's own decoder check compares against (x, y, w, h, value).
            "probes": [{"name": p.name, "x": p.x, "y": p.y, "w": p.w, "h": p.h, "y10": p.value} for p in frame.probes],
        })
        print(f"{pid}: {len(data) // 1024} KB, {len(frame.probes)} probes, bit-exact", flush=True)
    os.remove(tmp)
    manifest = {"version": VERSION, "width": W, "height": H, "patterns": entries}
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    total = sum(e["size"] for e in entries)
    print(f"manifest v{VERSION}: {len(entries)} patterns, {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
