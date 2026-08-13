#!/usr/bin/env python3
"""M0 spike -- proves D8 (latest-wins decode, drops counted on the wire, never silent).

A reader thread pulls frames from the RTSP source as fast as they arrive and pushes each
into a `LatestOnlyMailbox` (sampler.py) -- a not-yet-consumed frame is overwritten and
counted as dropped, never queued. The main loop runs the ported `DeadlineSampler` at
--target-fps and, on every due deadline, takes the mailbox's current frame and hands it to
an ARTIFICIALLY STALLED "detector" (a sleep much longer than the sample interval, so the
consumer falls behind on purpose).

What this demonstrates, with numbers: `dropped_frames` climbs throughout the stall
(evidence the reader never blocks waiting for the slow consumer), while the age of the
frame each stalled "detection" actually receives (wall-clock at consumption minus the
frame's own capture wall-clock) stays low/bounded -- NOT growing with the backlog -- because
the mailbox always hands over the newest frame available at consumption time, not the
oldest queued one. That combination (drops climbing, served-frame age staying fresh) is
exactly plan decision D8.

Usage:
  cv-service/.venv/bin/python rate_stall_bench.py --url rtsp://localhost:18554/push720 \
      --target-fps 10.0 --duration 30 --stall-ms 800 --out results/rate_stall.json
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from pathlib import Path

import cv2

from sampler import DeadlineSampler, LatestOnlyMailbox


def reader_loop(cap, mailbox: LatestOnlyMailbox, stop_event: threading.Event, seen_counter: list) -> None:
    while not stop_event.is_set():
        ok, _frame = cap.read()
        if not ok:
            break
        # ONE clock read per frame -- captured here, at arrival, not re-read later.
        capture_wall = time.time()
        seen_counter[0] += 1
        mailbox.put(capture_wall)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--target-fps", type=float, default=10.0)
    parser.add_argument("--duration", type=float, default=30.0, help="seconds")
    parser.add_argument("--stall-ms", type=float, default=800.0, help="simulated detector latency, ms")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    cap = cv2.VideoCapture(args.url, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.url!r}")

    mailbox = LatestOnlyMailbox()
    stop_event = threading.Event()
    frames_seen = [0]
    reader = threading.Thread(target=reader_loop, args=(cap, mailbox, stop_event, frames_seen), daemon=True)
    reader.start()

    sampler = DeadlineSampler(args.target_fps)
    consumed = []  # (elapsed_s_at_consumption, age_ms, dropped_frames_so_far)
    poll_interval_s = 0.01  # main loop polls for the next deadline; not itself the sample rate

    t_start = time.perf_counter()
    while time.perf_counter() - t_start < args.duration:
        now_ns = time.monotonic_ns()
        if sampler.sample_due(now_ns):
            frame = mailbox.take()
            consumption_wall = time.time()
            if frame is not None:
                age_ms = (consumption_wall - frame) * 1000.0
                consumed.append(
                    {
                        "elapsed_s": round(time.perf_counter() - t_start, 2),
                        "age_ms": round(age_ms, 1),
                        "dropped_frames_so_far": mailbox.dropped_frames,
                    }
                )
            # Simulated stalled detector -- much slower than the sample interval, on
            # purpose, so the mailbox has to absorb a real backlog of source frames.
            time.sleep(args.stall_ms / 1000.0)
        else:
            time.sleep(poll_interval_s)

    stop_event.set()
    reader.join(timeout=2.0)
    cap.release()

    ages = [c["age_ms"] for c in consumed]
    result = {
        "url": args.url,
        "target_fps": args.target_fps,
        "duration_s": args.duration,
        "stall_ms": args.stall_ms,
        "source_frames_seen": frames_seen[0],
        "samples_consumed": len(consumed),
        "missed_deadlines": sampler.missed_deadlines,
        "dropped_frames_final": mailbox.dropped_frames,
        "served_frame_age_ms": {
            "p50": round(sorted(ages)[len(ages) // 2], 1) if ages else None,
            "max": round(max(ages), 1) if ages else None,
            "min": round(min(ages), 1) if ages else None,
        },
        # First vs last few samples' dropped-so-far count -- shows the climb, not just the endpoint.
        "dropped_frames_timeline_sample": [
            {"elapsed_s": c["elapsed_s"], "dropped_frames_so_far": c["dropped_frames_so_far"], "served_age_ms": c["age_ms"]}
            for c in consumed
        ],
    }
    text = json.dumps(result, indent=2)
    print(text)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
