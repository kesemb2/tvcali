"""HDR10+ and Dolby Vision (profile 8.1) versions of the HDR10 patterns.

The HDR10 file is encoded once; the dynamic metadata is injected into the same lossless HEVC stream, so all three
formats carry identical pixels and differ only in metadata. Tools (run in CI only, versions pinned in the workflow):
  hdr10plus_tool  inject ST 2094-40 SEI from per-frame JSON
  dovi_tool       generate a profile 8.1 RPU from a config, inject it
  MP4Box (GPAC)   mux into MP4 with the Dolby Vision configuration box (dvcC) so players detect Dolby Vision
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess

import numpy as np

from lib import pq_nits

TOOLS = {
    "hdr10plus": os.environ.get("HDR10PLUS_TOOL", "hdr10plus_tool"),
    "dovi": os.environ.get("DOVI_TOOL", "dovi_tool"),
    "mp4box": os.environ.get("MP4BOX", "MP4Box"),
}
PERCENTILES = [1, 5, 10, 25, 50, 75, 90, 95, 99]


def available() -> bool:
    return all(shutil.which(t) for t in TOOLS.values())


def run(cmd: list[str]) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"{' '.join(cmd)} failed:\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    return r.stdout + r.stderr


# ---- Per-frame statistics, from the 10-bit luma (all patterns are neutral grey, so max(R,G,B) = luminance). ----

def frame_stats(y: np.ndarray) -> dict:
    nits = pq_nits(y)
    pq12 = np.clip(np.round((y.astype(np.float64) - 64) / 876 * 4095), 0, 4095)
    return {
        # HDR10+ units: 0.1 cd/m².
        "maxscl": int(round(float(nits.max()) * 10)),
        "average": int(round(float(nits.mean()) * 10)),
        "percentiles": [int(round(float(v) * 10)) for v in np.percentile(nits, PERCENTILES)],
        # Dolby Vision L1: 12-bit PQ codes.
        "min_pq": int(pq12.min()), "max_pq": int(pq12.max()), "avg_pq": int(round(float(pq12.mean()))),
    }


def hdr10plus_json(stats: list[dict]) -> dict:
    """Profile A (no Bezier curve, targeted display 0): what most HDR10+ streams carry. One scene."""
    scene_info = []
    for i, s in enumerate(stats):
        scene_info.append({
            "LuminanceParameters": {
                "AverageRGB": min(s["average"], 100000),
                "LuminanceDistributions": {
                    "DistributionIndex": PERCENTILES,
                    "DistributionValues": [min(v, 100000) for v in s["percentiles"]],
                },
                "MaxScl": [min(s["maxscl"], 100000)] * 3,
            },
            "NumberOfWindows": 1,
            "TargetedSystemDisplayMaximumLuminance": 0,
            "SceneFrameIndex": i,
            "SceneId": 0,
            "SequenceFrameIndex": i,
        })
    return {
        "JSONInfo": {"HDR10plusProfile": "A", "Version": "1.0"},
        "SceneInfo": scene_info,
        "SceneInfoSummary": {"SceneFirstFrameIndex": [0], "SceneFrameNumbers": [len(stats)]},
        "ToolInfo": {"Tool": "calibrate patterns", "Version": "1"},
    }


def dovi_config(stats: list[dict], master: int, max_cll: int, max_fall: int) -> dict:
    """Profile 8.1, CM v4.0, one L1 block per frame (a 'shot' per frame for moving patterns)."""
    level6 = {
        "max_display_mastering_luminance": master,
        "min_display_mastering_luminance": 50,  # 0.005 cd/m², in 0.0001 units as in HDR10 mastering metadata
        "max_content_light_level": max_cll,
        "max_frame_average_light_level": max_fall,
    }
    cfg = {"cm_version": "V40", "profile": "8.1", "length": len(stats), "level6": level6,
           "level5": {"active_area_left_offset": 0, "active_area_right_offset": 0,
                      "active_area_top_offset": 0, "active_area_bottom_offset": 0}}

    def l1(s):
        return {"Level1": {"min_pq": s["min_pq"], "max_pq": s["max_pq"], "avg_pq": s["avg_pq"]}}

    if all(s == stats[0] for s in stats):
        cfg["default_metadata_blocks"] = [l1(stats[0])]
    else:
        cfg["default_metadata_blocks"] = [l1(stats[0])]
        cfg["shots"] = [{"start": i, "duration": 1, "metadata_blocks": [l1(s)]} for i, s in enumerate(stats)]
    return cfg


# ---- Building the variants. ----------------------------------------------------------------------------------

def annexb(mp4: str, out: str) -> None:
    run(["ffmpeg", "-v", "error", "-y", "-i", mp4, "-c:v", "copy", "-bsf:v", "hevc_mp4toannexb", "-f", "hevc", out])


def mux(hevc: str, out: str, fps: tuple[int, int], dv: bool) -> None:
    if os.path.exists(out):
        os.remove(out)
    opts = f":fps={fps[0]}/{fps[1]}" + (":dvp=8.1" if dv else "")
    run([TOOLS["mp4box"], "-add", hevc + opts, "-new", out])


def make_hdr10plus(base_mp4: str, out: str, stats: list[dict], fps, work: str) -> None:
    base, meta, injected = (os.path.join(work, n) for n in ("base.hevc", "hdr10plus.json", "hdr10plus.hevc"))
    annexb(base_mp4, base)
    with open(meta, "w") as f:
        json.dump(hdr10plus_json(stats), f)
    run([TOOLS["hdr10plus"], "inject", "-i", base, "-j", meta, "-o", injected])
    mux(injected, out, fps, dv=False)


def make_dovi(base_mp4: str, out: str, stats: list[dict], fps, master: int, max_cll: int, max_fall: int,
              work: str) -> None:
    base, cfg, rpu, injected = (os.path.join(work, n) for n in ("base.hevc", "dovi.json", "rpu.bin", "dovi.hevc"))
    annexb(base_mp4, base)
    with open(cfg, "w") as f:
        json.dump(dovi_config(stats, master, max_cll, max_fall), f)
    run([TOOLS["dovi"], "generate", "-j", cfg, "-o", rpu])
    run([TOOLS["dovi"], "inject-rpu", "-i", base, "--rpu-in", rpu, "-o", injected])
    mux(injected, out, fps, dv=True)


# ---- Verification of the metadata actually in the files. -----------------------------------------------------

def verify_hdr10plus(out: str, stats: list[dict], work: str) -> str:
    hevc, meta = os.path.join(work, "check.hevc"), os.path.join(work, "check_hdr10plus.json")
    annexb(out, hevc)
    run([TOOLS["hdr10plus"], "extract", hevc, "-o", meta])
    got = json.load(open(meta))["SceneInfo"]
    if len(got) != len(stats):
        raise SystemExit(f"{out}: HDR10+ metadata on {len(got)} frames, expected {len(stats)}")
    for i, (g, s) in enumerate(zip(got, stats)):
        lp = g["LuminanceParameters"]
        if lp["MaxScl"] != [min(s["maxscl"], 100000)] * 3 or lp["AverageRGB"] != min(s["average"], 100000):
            raise SystemExit(f"{out}: HDR10+ frame {i} has MaxScl {lp['MaxScl']} / AverageRGB {lp['AverageRGB']}")
    side = json.loads(run(["ffprobe", "-v", "error", "-read_intervals", "%+#1", "-show_frames",
                           "-show_entries", "frame=side_data_list", "-of", "json", out]))["frames"][0]
    types = [d.get("side_data_type", "") for d in side.get("side_data_list", [])]
    if not any("2094-40" in t or "HDR10+" in t for t in types):
        raise SystemExit(f"{out}: ffprobe sees no HDR10+ side data (found {types})")
    return f"HDR10+ on {len(got)} frames"


def verify_dovi(out: str, frames: int, work: str) -> str:
    info = json.loads(run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", out]))["streams"][0]
    conf = next((d for d in info.get("side_data_list", []) if "DOVI" in d.get("side_data_type", "").upper()), None)
    if not conf:
        raise SystemExit(f"{out}: no Dolby Vision configuration record (dvcC) in the MP4")
    want = {"dv_profile": 8, "rpu_present_flag": 1, "bl_present_flag": 1, "dv_bl_signal_compatibility_id": 1}
    wrong = {k: (conf.get(k), v) for k, v in want.items() if conf.get(k) != v}
    if wrong:
        raise SystemExit(f"{out}: Dolby Vision configuration mismatch (got, expected): {wrong}")
    hevc, rpu = os.path.join(work, "check.hevc"), os.path.join(work, "check_rpu.bin")
    annexb(out, hevc)
    run([TOOLS["dovi"], "extract-rpu", hevc, "-o", rpu])
    summary = run([TOOLS["dovi"], "info", "-i", rpu, "-s"])
    if str(frames) not in summary or "8" not in summary:
        raise SystemExit(f"{out}: unexpected RPU summary:\n{summary}")
    first = summary.strip().splitlines()
    return f"Dolby Vision 8.1, dvcC ok, RPU: {' / '.join(l.strip() for l in first[:4])}"
