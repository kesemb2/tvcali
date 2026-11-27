#!/usr/bin/env python3
"""Generates, encodes and verifies every test pattern and clip, then writes manifest.json.

  python3 patterns/build.py out/ [id ...]

Verification (fails the build on any mismatch):
  * ffprobe: codec, profile, size, pixel format, frame rate, frame count and colour tags; for HDR10 also the
    mastering display and content light level side data, value by value.
  * every decoded frame is compared with its source frame (ffmpeg framemd5 against the md5 of the raw planes), so
    the files are bit-exact, not just "close".
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Callable

import hdr
import ratetest
import sdr
from lib import Frame, H, W, pq_nits

VERSION = int(open(os.path.join(os.path.dirname(__file__), "VERSION")).read().strip())
FILM = (24000, 1001)
STATIC_FRAMES = 144  # 6 s at 23.976; the app loops them.

# Mastering display: P3-D65 primaries inside BT.2020 (as most HDR10 masters), in x265 units (0.00002 / 0.0001 cd/m²).
P3_D65 = "G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)"
MIN_LUMINANCE = 50  # 0.005 cd/m²


@dataclass
class Spec:
    id: str
    fmt: str  # "sdr" | "hdr10"
    make: Callable  # () -> Frame for static, (i) -> Frame for motion
    motion: bool = False
    frames: int = STATIC_FRAMES
    fps: tuple[int, int] = FILM
    role: str = "pattern"  # "pattern" (shown in the app) | "ratetest" (frame-rate switch test)
    master: int = 1000  # mastering peak, cd/m² (HDR10)
    declared_cll: int | None = None  # MaxCLL to declare instead of the measured one (tone-mapping tests)
    stats: dict = field(default_factory=dict)


def specs() -> list[Spec]:
    out = [Spec(pid, "sdr", make) for pid, make in sdr.PATTERNS.items()]
    for pid, (make, master, cll) in hdr.PATTERNS.items():
        motion = pid in hdr.MOTION
        out.append(Spec(pid, "hdr10", make, motion=motion, frames=hdr.MOTION_FRAMES if motion else STATIC_FRAMES,
                        master=master, declared_cll=cll))
    for pid, fps in ratetest.RATES.items():
        n = ratetest.frames(*fps)
        label = pid.removeprefix("test_rate_").replace("23976", "23.976") + " fps"
        out.append(Spec(pid, "sdr", ratetest.make(label, n), motion=True, frames=n, fps=fps, role="ratetest"))
    return out


TAGS = {
    "sdr": {"color_primaries": "bt709", "color_transfer": "bt709", "color_space": "bt709", "color_range": "tv"},
    "hdr10": {"color_primaries": "bt2020", "color_transfer": "smpte2084", "color_space": "bt2020nc",
              "color_range": "tv"},
}
FF_TAGS = {
    "sdr": ["-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709"],
    "hdr10": ["-color_range", "tv", "-colorspace", "bt2020nc", "-color_primaries", "bt2020",
              "-color_trc", "smpte2084"],
}


def run(cmd: list[str], **kw) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout


def x265_params(spec: Spec) -> str:
    p = ["lossless=1", "keyint=600", "min-keyint=600", "log-level=error", "range=limited"]
    if spec.fmt == "sdr":
        p += ["colorprim=bt709", "transfer=bt709", "colormatrix=bt709"]
    else:
        cll, fall = spec.stats["max_cll"], spec.stats["max_fall"]
        p += ["colorprim=bt2020", "transfer=smpte2084", "colormatrix=bt2020nc", "hdr10=1", "repeat-headers=1",
              f"master-display={P3_D65}L({spec.master * 10000},{MIN_LUMINANCE})", f"max-cll={cll},{fall}"]
    return ":".join(p)


def light_level(frame: Frame) -> tuple[float, float]:
    """(max, frame-average) luminance in cd/m² of a grey PQ frame: inputs for MaxCLL / MaxFALL."""
    nits = pq_nits(frame.y)
    return float(nits.max()), float(nits.mean())


def encode(spec: Spec, out: str, tmp: str) -> list[str]:
    """Encodes the pattern and returns the md5 of every source frame (what the decoded frames must match)."""
    num, den = spec.fps
    base = ["ffmpeg", "-v", "error", "-y"]
    raw_in = FF_TAGS[spec.fmt] + ["-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{W}x{H}", "-r", f"{num}/{den}"]

    def tail() -> list[str]:
        # Called after spec.stats is known: the HDR10 light levels go into the x265 parameters.
        return (["-frames:v", str(spec.frames), "-sws_flags", "bitexact", "-c:v", "libx265", "-preset", "medium",
                 "-x265-params", x265_params(spec), "-pix_fmt", "yuv420p10le"] + FF_TAGS[spec.fmt]
                + ["-tag:v", "hvc1", "-movflags", "+faststart", "-an", out])

    if not spec.motion:
        frame = spec.make()
        spec.probes = frame.probes
        if spec.fmt == "hdr10":
            peak, avg = light_level(frame)
            spec.stats = {"max_cll": spec.declared_cll or max(1, round(peak)), "max_fall": max(1, round(avg))}
        frame.write_raw(tmp)
        md5 = hashlib.md5(open(tmp, "rb").read()).hexdigest()
        run(base + ["-stream_loop", str(spec.frames - 1)] + raw_in + ["-i", tmp] + tail())
        return [md5] * spec.frames

    # Motion: light levels need every frame first (MaxCLL goes into the stream header), then frames stream to x265.
    spec.probes = []
    if spec.fmt == "hdr10":
        peak = avg = 0.0
        for i in range(spec.frames):
            p, a = light_level(spec.make(i))
            peak, avg = max(peak, p), max(avg, a)
        spec.stats = {"max_cll": spec.declared_cll or max(1, round(peak)), "max_fall": max(1, round(avg))}
    proc = subprocess.Popen(base + raw_in + ["-i", "-"] + tail(),
                            stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    md5s = []
    for i in range(spec.frames):
        frame = spec.make(i)
        frame.write_raw(tmp)
        data = open(tmp, "rb").read()
        md5s.append(hashlib.md5(data).hexdigest())
        proc.stdin.write(data)
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit(f"{out}: ffmpeg failed: {proc.stderr.read().decode()[-2000:]}")
    return md5s


def verify(spec: Spec, out: str, md5s: list[str]) -> None:
    info = json.loads(run(["ffprobe", "-v", "error", "-show_streams", "-count_frames", "-of", "json", out]))
    (s,) = info["streams"]
    num, den = spec.fps
    expect = {"codec_name": "hevc", "profile": "Main 10", "width": W, "height": H, "pix_fmt": "yuv420p10le",
              "r_frame_rate": f"{num}/{den}", "nb_read_frames": str(spec.frames), **TAGS[spec.fmt]}
    wrong = {k: (s.get(k), v) for k, v in expect.items() if s.get(k) != v}
    if wrong:
        raise SystemExit(f"{out}: ffprobe mismatch (got, expected): {wrong}")

    if spec.fmt == "hdr10":
        frames = json.loads(run(["ffprobe", "-v", "error", "-read_intervals", "%+#1", "-show_frames",
                                 "-show_entries", "frame=side_data_list", "-of", "json", out]))["frames"]
        sides = {d.get("side_data_type"): d for d in frames[0].get("side_data_list", [])}
        mdm = sides.get("Mastering display metadata")
        cll = sides.get("Content light level metadata")
        if not mdm or not cll:
            raise SystemExit(f"{out}: HDR10 side data missing (found {list(sides)})")

        def ratio(v: str) -> float:
            n, _, d = str(v).partition("/")
            return float(n) / float(d or 1)

        checks = {
            "max_luminance": (ratio(mdm["max_luminance"]), float(spec.master)),
            "min_luminance": (ratio(mdm["min_luminance"]), MIN_LUMINANCE / 10000),
            "red_x": (ratio(mdm["red_x"]), 0.68), "green_y": (ratio(mdm["green_y"]), 0.69),
            "white_point_x": (ratio(mdm["white_point_x"]), 0.3127),
            "max_content": (float(cll["max_content"]), float(spec.stats["max_cll"])),
            "max_average": (float(cll["max_average"]), float(spec.stats["max_fall"])),
        }
        bad = {k: v for k, v in checks.items() if abs(v[0] - v[1]) > 1e-3 * max(1.0, v[1])}
        if bad:
            raise SystemExit(f"{out}: HDR10 metadata mismatch (got, expected): {bad}")

    lines = run(["ffmpeg", "-v", "error", "-i", out, "-f", "framemd5", "-pix_fmt", "yuv420p10le", "-"]).splitlines()
    got = [l.split(",")[-1].strip() for l in lines if l and not l.startswith("#")]
    if got != md5s:
        bad = sum(a != b for a, b in zip(got, md5s)) + abs(len(got) - len(md5s))
        raise SystemExit(f"{out}: {bad} of {len(md5s)} decoded frames differ from their source")


def tool_versions() -> str:
    ffmpeg = run(["ffmpeg", "-version"]).splitlines()[0]
    x265 = subprocess.run(["ffmpeg", "-hide_banner", "-f", "lavfi", "-i", "color=s=64x64:d=0.04", "-c:v", "libx265",
                           "-f", "null", "-"], capture_output=True, text=True).stderr
    x265 = next((l.strip() for l in x265.splitlines() if "HEVC encoder version" in l), "x265 ?")
    return f"{ffmpeg} | {x265}"


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "out"
    only = set(sys.argv[2:])
    os.makedirs(out_dir, exist_ok=True)
    print(tool_versions(), flush=True)
    tmp = os.path.join(out_dir, "frame.yuv")
    entries = []
    for spec in specs():
        if only and spec.id not in only:
            continue
        out = os.path.join(out_dir, f"{spec.id}.mp4")
        md5s = encode(spec, out, tmp)
        verify(spec, out, md5s)
        data = open(out, "rb").read()
        entry = {
            "id": spec.id, "file": f"{spec.id}.mp4", "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            # Identity of the pattern itself: encoders are not bit-reproducible, sources are.
            "source_md5": hashlib.md5("".join(md5s).encode()).hexdigest(),
            "format": spec.fmt, "role": spec.role, "motion": spec.motion,
            "fps_num": spec.fps[0], "fps_den": spec.fps[1], "frames": spec.frames,
            # Exact luma code values the app's own decoder check compares against.
            "probes": [{"name": p.name, "x": p.x, "y": p.y, "w": p.w, "h": p.h, "y10": p.value} for p in spec.probes],
        }
        if spec.fmt == "hdr10":
            entry["hdr10"] = {"master_cd_m2": spec.master, **spec.stats}
        entries.append(entry)
        extra = f", MaxCLL {spec.stats['max_cll']} / MaxFALL {spec.stats['max_fall']}" if spec.fmt == "hdr10" else ""
        print(f"{spec.id}: {len(data) // 1024} KB, {spec.frames} frames{extra}, bit-exact", flush=True)
    if os.path.exists(tmp):
        os.remove(tmp)
    manifest = {"version": VERSION, "width": W, "height": H, "patterns": entries}
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print(f"manifest v{VERSION}: {len(entries)} files, {sum(e['size'] for e in entries) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
