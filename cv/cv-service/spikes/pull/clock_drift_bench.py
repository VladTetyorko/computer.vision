#!/usr/bin/env python3
"""M0 spike -- §6 capture-clock drift, measured over a 10-minute run.

IMPORTANT SCOPE CAVEAT (measured, not assumed -- read before trusting these numbers):
this spike's source is `ffmpeg -re` running on the SAME host as the puller, publishing
into a LOCAL/LAN mediamtx. There is no independent camera hardware clock in this setup --
the stream's own PTS timebase is itself derived from this host's clock via `-re`'s
real-time pacing. So this script can measure the SOFTWARE-observable component of drift
(decode/jitter-buffer latency stability, `cv2`'s PTS-vs-wallclock bookkeeping) but CANNOT
exercise genuine RTP/RTCP clock-domain drift between a physical camera's oscillator and
the worker's clock -- that requires a real camera and is out of this spike's reach. Report
these numbers as a measured floor, not a ceiling, on real-world drift.

Candidates (§6):
  arrival -- capturedAt := wallclock at the moment the decoded frame is retrieved.
             By definition this has zero "drift" against itself; it is the baseline the
             other two candidates are compared against.
  anchor  -- capturedAt := anchor_wallclock + (pts - anchor_pts), pts read once via
             cv2.CAP_PROP_POS_MSEC and NEVER re-anchored for the full 10 minutes (a
             production implementation would re-anchor past a threshold; this run
             deliberately does not, so the raw, unclamped drift can be observed and
             checked against the plan's 100ms/10min gate).
  ffmpeg_wallclock -- `-use_wallclock_as_timestamps 1` (see CONTRACT CORRECTION in the
             CV-PULL-SPIKE.md write-up: this flag measurably has NO EFFECT on ffmpeg's
             RTSP demuxer -- verified by direct A/B `showinfo` comparison -- because RTSP
             already supplies real timestamps and the flag only applies to demuxers that
             don't (`AVFMT_NOTIMESTAMPS`). What is actually measured under this label is
             an ffmpeg SUBPROCESS decode pipeline, receipt-timestamped in the parent
             Python process exactly like `arrival` -- i.e. this candidate's true behaviour
             collapses onto `arrival`, implemented via a different decoder.

Usage:
  cv-service/.venv/bin/python clock_drift_bench.py --url rtsp://localhost:18554/push720 \
      --duration-s 600 --sample-every-s 2 --out results/clock_drift_720p.json
"""
from __future__ import annotations

import argparse
import json
import subprocess
import threading
import time
from pathlib import Path

import cv2
import numpy as np


def run_cv2_candidates(url: str, duration_s: float, sample_every_s: float, samples: list) -> None:
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        raise SystemExit(f"could not open {url!r} (cv2)")

    anchor_wall = None
    anchor_pts = None
    last_sample_t = 0.0
    t_start = time.perf_counter()
    while time.perf_counter() - t_start < duration_s:
        ok, _frame = cap.read()
        if not ok:
            break
        now_wall = time.time()  # ONE clock read per frame for the wallclock side
        pts_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
        if anchor_wall is None:
            anchor_wall = now_wall
            anchor_pts = pts_ms
        elapsed = time.perf_counter() - t_start
        if elapsed - last_sample_t >= sample_every_s:
            last_sample_t = elapsed
            anchor_estimate = anchor_wall + (pts_ms - anchor_pts) / 1000.0
            arrival_estimate = now_wall
            drift_anchor_ms = (anchor_estimate - arrival_estimate) * 1000.0
            samples.append(
                {
                    "elapsed_s": round(elapsed, 1),
                    "pts_ms": pts_ms,
                    "drift_anchor_vs_arrival_ms": round(drift_anchor_ms, 2),
                }
            )
    cap.release()


def run_ffmpeg_subprocess_candidate(url: str, duration_s: float, sample_every_s: float, samples: list) -> None:
    """"ffmpeg_wallclock" candidate, actually implemented: raw-pipe subprocess decode,
    receipt-timestamped in this process (see module docstring's CONTRACT CORRECTION).
    Compared here against a plain time.time() reference read at the SAME instant a frame
    is pulled off the pipe -- so by construction this candidate's own "drift" is ~0 against
    itself; what's worth recording is whether the subprocess pipe accumulates backlog
    (buffer bloat) over 10 minutes, which would show up as the read cadence falling behind
    real time even though the source keeps publishing at a constant rate.
    """
    width, height = 1280, 720
    frame_bytes = width * height * 3
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-rtsp_transport", "tcp", "-i", url,
        "-f", "rawvideo", "-pix_fmt", "bgr24", "-an", "-",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=10**8)

    last_sample_t = 0.0
    t_start = time.perf_counter()
    frames_read = 0
    while time.perf_counter() - t_start < duration_s:
        buf = bytearray()
        while len(buf) < frame_bytes:
            chunk = proc.stdout.read(frame_bytes - len(buf))
            if not chunk:
                proc.terminate()
                return
            buf.extend(chunk)
        now_wall = time.time()
        frames_read += 1
        elapsed = time.perf_counter() - t_start
        # "Backlog drift" = how far behind real elapsed time this frame's read landed,
        # relative to the expected 1/30s cadence -- a growing value means the pipe is
        # accumulating a backlog (buffer bloat), the practical risk unique to this backend.
        expected_elapsed = frames_read / 30.0
        backlog_ms = (elapsed - expected_elapsed) * 1000.0
        if elapsed - last_sample_t >= sample_every_s:
            last_sample_t = elapsed
            samples.append(
                {
                    "elapsed_s": round(elapsed, 1),
                    "frames_read": frames_read,
                    "pipe_backlog_ms": round(backlog_ms, 2),
                    "wall_time": now_wall,
                }
            )
    proc.terminate()


def summarize_drift(samples: list, key: str) -> dict:
    values = [s[key] for s in samples if key in s]
    if not values:
        return {"n": 0}
    first_10 = values[: max(1, len(values) // 10)]
    last_10 = values[-max(1, len(values) // 10):]
    return {
        "n": len(values),
        "first_decile_mean_ms": round(float(np.mean(first_10)), 2),
        "last_decile_mean_ms": round(float(np.mean(last_10)), 2),
        "min_ms": round(min(values), 2),
        "max_ms": round(max(values), 2),
        "max_abs_ms": round(max(abs(v) for v in values), 2),
        "final_ms": round(values[-1], 2),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--duration-s", type=float, default=600.0)
    parser.add_argument("--sample-every-s", type=float, default=2.0)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    cv2_samples: list = []
    ffmpeg_samples: list = []

    t_cv2 = threading.Thread(
        target=run_cv2_candidates, args=(args.url, args.duration_s, args.sample_every_s, cv2_samples)
    )
    t_ffmpeg = threading.Thread(
        target=run_ffmpeg_subprocess_candidate,
        args=(args.url, args.duration_s, args.sample_every_s, ffmpeg_samples),
    )
    t_cv2.start()
    t_ffmpeg.start()
    t_cv2.join()
    t_ffmpeg.join()

    result = {
        "url": args.url,
        "duration_s": args.duration_s,
        "anchor_vs_arrival_drift_ms": summarize_drift(cv2_samples, "drift_anchor_vs_arrival_ms"),
        "ffmpeg_subprocess_pipe_backlog_ms": summarize_drift(ffmpeg_samples, "pipe_backlog_ms"),
        "raw_samples_cv2": cv2_samples,
        "raw_samples_ffmpeg": ffmpeg_samples,
    }
    text = json.dumps(result, indent=2)
    print(text)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
