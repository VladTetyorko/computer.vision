#!/usr/bin/env python3
"""M0 spike -- decode + inference cost, per resolution, per decoder backend.

Pulls an RTSP path already published into a mediamtx instance (see
``push_sources.sh``), decodes ``--frames`` frames after a short warmup, and
reports p50/p95/max decode latency plus process RSS/CPU. When ultralytics is
importable in the running interpreter (true for cv-service's own .venv, false
for the isolated PyAV-only venv this spike also uses), it additionally runs
``yolo26n.pt`` via ``cv_service.inference.detector.YoloDetector`` on every
decoded frame and reports inference cost SEPARATELY from decode cost -- the
two numbers are never blended.

Backends:
  opencv  -- cv2.VideoCapture(url, cv2.CAP_FFMPEG)             (expected default)
  ffmpeg  -- ffmpeg subprocess, raw bgr24 piped over stdout    (fallback A)
  pyav    -- PyAV's high-level decode loop                     (fallback B; run this
             backend from an ISOLATED venv with only `av`, `numpy`, `psutil` installed
             -- deliberately NOT the cv-service .venv, and deliberately NOT ultralytics,
             so this backend is decode-only by construction:
               python3.12 -m venv spikes/pull/.venv-pyav
               spikes/pull/.venv-pyav/bin/pip install av psutil numpy
             (not checked in -- .venv-pyav is a local, disposable venv, ~186 MB)

Usage:
  cv-service/.venv/bin/python decode_bench.py --backend opencv --url rtsp://localhost:18554/push720 \
      --label 720p --width 1280 --height 720 --frames 300 --out results/decode_720p_opencv.json

Prints one JSON object to stdout (also written to --out if given). No
estimates, no fabricated numbers -- every field is either measured this run
or explicitly null with a reason.
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path

try:
    import psutil
except ImportError:  # pragma: no cover - psutil is in both venvs used for this spike
    psutil = None

try:
    import numpy as np
except ImportError as exc:  # pragma: no cover
    raise SystemExit("numpy is required (present in both spike venvs)") from exc


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    return float(np.percentile(values, pct))


class RssCpuSampler:
    """Periodic RSS/CPU% sampling of THIS process, sampled coarsely (not per-frame)."""

    def __init__(self) -> None:
        self.rss_samples: list[int] = []
        self.cpu_samples: list[float] = []
        self._proc = psutil.Process() if psutil else None
        if self._proc:
            self._proc.cpu_percent(interval=None)  # prime; first call is meaningless
        self._last_sample_at = 0.0

    def maybe_sample(self, now: float, min_interval_s: float = 1.0) -> None:
        if not self._proc:
            return
        if now - self._last_sample_at < min_interval_s:
            return
        self._last_sample_at = now
        self.rss_samples.append(self._proc.memory_info().rss)
        self.cpu_samples.append(self._proc.cpu_percent(interval=None))

    def summary(self) -> dict:
        if not self._proc:
            return {"rss_max_mb": None, "rss_final_mb": None, "cpu_percent_avg": None, "note": "psutil unavailable"}
        return {
            "rss_max_mb": round(max(self.rss_samples) / (1024 * 1024), 1) if self.rss_samples else None,
            "rss_final_mb": round(self.rss_samples[-1] / (1024 * 1024), 1) if self.rss_samples else None,
            # first cpu_percent() call after priming is always ~0 (no elapsed interval to measure against);
            # drop it so the average isn't dragged down by a sample that measures nothing.
            "cpu_percent_avg": round(statistics.mean(self.cpu_samples[1:]), 1) if len(self.cpu_samples) > 1 else None,
        }


def open_opencv(url: str):
    import cv2

    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        raise RuntimeError(f"cv2.VideoCapture could not open {url!r}")
    return cap


def read_opencv(cap) -> np.ndarray | None:
    ok, frame = cap.read()
    return frame if ok else None


def open_ffmpeg(url: str, width: int, height: int) -> subprocess.Popen:
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-rtsp_transport",
        "tcp",
        "-i",
        url,
        "-f",
        "rawvideo",
        "-pix_fmt",
        "bgr24",
        "-an",
        "-",
    ]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=10**8)


def read_ffmpeg(proc: subprocess.Popen, width: int, height: int) -> np.ndarray | None:
    frame_bytes = width * height * 3
    buf = bytearray()
    while len(buf) < frame_bytes:
        chunk = proc.stdout.read(frame_bytes - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return np.frombuffer(bytes(buf), dtype=np.uint8).reshape((height, width, 3))


def open_pyav(url: str):
    import av

    container = av.open(url, options={"rtsp_transport": "tcp"})
    stream = container.streams.video[0]
    return container, container.decode(stream)


def read_pyav(gen) -> np.ndarray | None:
    try:
        frame = next(gen)
    except StopIteration:
        return None
    return frame.to_ndarray(format="bgr24")


def build_detector(model_name: str):
    """Constructs a real YoloDetector via the same class the product uses -- not a fake."""
    from cv_service.inference.detector import ENCODING_BGR24, YoloDetector

    detector = YoloDetector(model_name=model_name)
    return detector, ENCODING_BGR24


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", required=True, choices=["opencv", "ffmpeg", "pyav"])
    parser.add_argument("--url", required=True)
    parser.add_argument("--label", required=True, help="e.g. 720p, 1080p")
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--frames", type=int, default=300)
    parser.add_argument("--warmup-frames", type=int, default=15)
    parser.add_argument("--model", default="yolo26n.pt")
    parser.add_argument("--skip-inference", action="store_true", help="decode-only pass")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    inference_available = not args.skip_inference
    detector = None
    encoding = None
    if inference_available:
        try:
            detector, encoding = build_detector(args.model)
        except Exception as exc:  # noqa: BLE001 - report, don't crash a decode-only venv
            print(f"# inference unavailable in this interpreter ({exc}); decode-only", file=sys.stderr)
            inference_available = False

    if args.backend == "opencv":
        handle = open_opencv(args.url)
        reader = lambda: read_opencv(handle)
    elif args.backend == "ffmpeg":
        handle = open_ffmpeg(args.url, args.width, args.height)
        reader = lambda: read_ffmpeg(handle, args.width, args.height)
    else:
        container, gen = open_pyav(args.url)
        handle = container
        reader = lambda: read_pyav(gen)

    sampler = RssCpuSampler()

    # Warmup: drain a few frames uncounted so first-open jitter / codec buffering doesn't bias the stats.
    for _ in range(args.warmup_frames):
        reader()

    decode_ms: list[float] = []
    inference_ms: list[float] = []
    dropped = 0
    t_start = time.perf_counter()
    for i in range(args.frames):
        t0 = time.perf_counter()
        frame = reader()
        t1 = time.perf_counter()
        if frame is None:
            dropped += 1
            continue
        decode_ms.append((t1 - t0) * 1000.0)

        if inference_available:
            try:
                _detections, inf_ms = detector.detect(
                    width=args.width,
                    height=args.height,
                    encoding=encoding,
                    data=frame.tobytes(),
                )
                inference_ms.append(float(inf_ms))
            except Exception as exc:  # noqa: BLE001
                print(f"# inference failed on frame {i}: {exc}", file=sys.stderr)

        sampler.maybe_sample(time.perf_counter())

    t_end = time.perf_counter()
    sampler.maybe_sample(t_end, min_interval_s=0.0)  # final sample

    if args.backend == "opencv":
        handle.release()
    elif args.backend == "ffmpeg":
        handle.terminate()
    else:
        handle.close()

    result = {
        "backend": args.backend,
        "label": args.label,
        "resolution": f"{args.width}x{args.height}",
        "url": args.url,
        "frames_requested": args.frames,
        "frames_decoded": len(decode_ms),
        "frames_dropped_or_eof": dropped,
        "wall_seconds": round(t_end - t_start, 2),
        "decode_ms": {
            "p50": round(percentile(decode_ms, 50), 3) if decode_ms else None,
            "p95": round(percentile(decode_ms, 95), 3) if decode_ms else None,
            "max": round(max(decode_ms), 3) if decode_ms else None,
            "n": len(decode_ms),
        },
        "inference_ms": (
            {
                "p50": round(percentile(inference_ms, 50), 3) if inference_ms else None,
                "p95": round(percentile(inference_ms, 95), 3) if inference_ms else None,
                "max": round(max(inference_ms), 3) if inference_ms else None,
                "n": len(inference_ms),
                "model": args.model,
            }
            if inference_available and inference_ms
            else None
        ),
        "process": sampler.summary(),
    }

    text = json.dumps(result, indent=2)
    print(text)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
