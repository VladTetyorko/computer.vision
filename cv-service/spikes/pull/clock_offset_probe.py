#!/usr/bin/env python3
"""M0 spike -- app<->worker wallclock offset (§6), measured over SSH the same way NTP's
own client estimates offset from a round trip: for each probe, record local time before
and after a remote `date` call, take the remote timestamp, and estimate
    offset = remote_time - (local_before + local_after) / 2
with an uncertainty bound of (local_after - local_before) / 2 (half the round trip). Runs
several probes and reports the one with the smallest round trip (least uncertain).

Usage: python3 clock_offset_probe.py --host vlad@192.168.0.106 --probes 15
"""
from __future__ import annotations

import argparse
import json
import subprocess
import time


def probe_once(host: str) -> dict:
    local_before = time.time()
    out = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ControlPath=/tmp/gb4005-ssh.sock", host, "date +%s.%N"],
        capture_output=True, text=True, timeout=10, check=True,
    )
    local_after = time.time()
    remote_time = float(out.stdout.strip())
    rtt = local_after - local_before
    offset_ms = (remote_time - (local_before + local_after) / 2) * 1000.0
    return {"rtt_ms": round(rtt * 1000, 2), "offset_ms": round(offset_ms, 2)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--probes", type=int, default=15)
    args = parser.parse_args()

    probes = [probe_once(args.host) for _ in range(args.probes)]
    best = min(probes, key=lambda p: p["rtt_ms"])
    result = {
        "host": args.host,
        "probes": probes,
        "best_probe": best,
        "offset_ms_estimate": best["offset_ms"],
        "uncertainty_ms": round(best["rtt_ms"] / 2, 2),
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
