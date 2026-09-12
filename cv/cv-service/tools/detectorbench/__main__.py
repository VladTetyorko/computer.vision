"""How many 10 fps streams does a deployment SHAPE sustain?

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §6 wave W4 and decision E17. The
plan's own §1.6 says every timing number in it is a doc claim nobody
re-measured; this is the script that retires that risk for the one number E17
turns on -- whether the detector should be split out of the GB4005 box.

**What it measures.** N clients each push synthetic frames at a fixed fps into
`Inference.DetectStream` with `TRACKING_MODE_ASSOCIATE` (the deployed default)
and count what comes back. The server's own `LatestOnlyMailbox` is latest-wins,
so a saturated process ANSWERS FEWER FRAMES rather than queueing -- the
response rate IS the sustained throughput, with no separate backlog to reason
about. A stream is "sustained" when it got back at least `--sustain-ratio` of
the frames it sent AND its p95 round trip stayed under `--sustain-latency-ms`.

**Two shapes.**

- `allinone` -- one process, `CV_SERVICE_ROLE=all`, detection in-process. This
  is every deployment that predates W4.
- `split` -- one `CV_SERVICE_ROLE=tracker` process holding the sessions plus
  `--detectors` N `CV_SERVICE_ROLE=detector` processes, wired by
  `CV_DETECTOR_TARGETS`. Identity stays in the tracker; only pixels cross.

**What a single-box run can and cannot tell you.** Both shapes run on the same
cores, and a yolo26n pass is >95% of a frame's cost, so on ONE machine the
split cannot buy throughput -- there is no more silicon to reach. What a
single-box run measures honestly is the split's OVERHEAD (the serialize +
hop + deserialize the all-in-one does not pay) and whether admission behaves.
Run the two shapes on two machines to measure the scaling claim itself.

Usage (both shapes, sweeping stream counts)::

    python -m tools.detectorbench --scenario allinone --streams 1,2,3,4,5,6
    python -m tools.detectorbench --scenario split --detectors 2 --streams 1,2,3,4,5,6

Every process it starts is a child it also stops; nothing is left running.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterator, Optional

import grpc

_CV_SERVICE_DIR = Path(__file__).resolve().parent.parent.parent
if str(_CV_SERVICE_DIR) not in sys.path:  # pragma: no cover - script entry
    sys.path.insert(0, str(_CV_SERVICE_DIR))

from cv_service.gen.vision.v1 import cv_pb2, cv_pb2_grpc  # noqa: E402

# Frame ring depth. Long enough that the model never sees the same frame twice
# in a row (which would be an unrealistically cache-friendly workload) and
# short enough that setup stays a second or two.
_RING_FRAMES = 24
# Wire frame size. The synthetic scenes render at 320x240; production sends
# 640-wide JPEGs (`CV_PULL_MAX_WIDTH`, and adapter-cv-grpc's own detect width),
# so the ring is upscaled once at setup to make the HOP cost realistic. The
# INFERENCE cost is unaffected either way -- ultralytics letterboxes to
# `CV_IMGSZ` (416) before the network sees anything.
_WIRE_WIDTH = 640
_WIRE_HEIGHT = 480
_JPEG_QUALITY = 80
# How long a freshly started process may take to bind its port. Model load
# plus Ultralytics' warmup predict happen BEFORE the server serves
# (`MODULE.md` "First-request latency"), and that is the slow part.
_STARTUP_TIMEOUT_SECONDS = 180.0
_PORT_POLL_SECONDS = 0.25


@dataclass
class StreamResult:
    """One client stream's tally."""

    stream_id: str
    sent: int = 0
    received: int = 0
    latencies_ms: "list[float]" = field(default_factory=list)

    @property
    def answered_ratio(self) -> float:
        return self.received / self.sent if self.sent else 0.0

    @property
    def p50_ms(self) -> float:
        return _percentile(self.latencies_ms, 0.50)

    @property
    def p95_ms(self) -> float:
        return _percentile(self.latencies_ms, 0.95)


@dataclass
class RunResult:
    """One (shape, stream count) measurement."""

    scenario: str
    streams: int
    detectors: int
    seconds: float
    fps: float
    permits: int
    sent: int
    received: int
    answered_ratio: float
    p50_ms: float
    p95_ms: float
    effective_fps: float
    sustained: bool
    per_stream: "list[dict]"


def _percentile(values: "list[float]", fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


# -- synthetic frames -------------------------------------------------------


def _frame_ring() -> "list[bytes]":
    """`_RING_FRAMES` JPEG frames from the trackeval `crossing` scenario.

    Reuses the existing synthetic tooling rather than inventing a second scene
    generator: `crossing` has two moving objects on a textured background, so
    the tracker has real association work to do on every frame instead of an
    empty scene's trivial one.
    """
    import cv2  # local: this module must stay importable for --help alone
    import numpy as np  # noqa: F401 - sequences needs it resolved

    from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS

    sequence = SCENARIOS["crossing"](DEFAULT_SEED)
    ring: "list[bytes]" = []
    for frame in sequence.frames[:_RING_FRAMES]:
        scaled = cv2.resize(frame.image, (_WIRE_WIDTH, _WIRE_HEIGHT), interpolation=cv2.INTER_LINEAR)
        ok, buffer = cv2.imencode(".jpg", scaled, [int(cv2.IMWRITE_JPEG_QUALITY), _JPEG_QUALITY])
        if not ok:  # pragma: no cover - cv2 does not fail on a valid array
            raise RuntimeError("failed to JPEG-encode a synthetic frame")
        ring.append(buffer.tobytes())
    if not ring:  # pragma: no cover
        raise RuntimeError("the crossing scenario produced no frames")
    return ring


# -- process control --------------------------------------------------------


def _port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1.0):
            return True
    except OSError:
        return False


def _spawn(env_overrides: "dict[str, str]", *, port: int, log: Path) -> subprocess.Popen:
    """Start one cv-service process and wait until its port answers."""
    env = dict(os.environ)
    env["PYTHONPATH"] = str(_CV_SERVICE_DIR)
    env["CV_PORT"] = str(port)
    env.update(env_overrides)
    handle = log.open("wb")
    process = subprocess.Popen(
        [sys.executable, "-m", "cv_service.grpc.server"],
        cwd=str(_CV_SERVICE_DIR),
        env=env,
        stdout=handle,
        stderr=subprocess.STDOUT,
    )
    deadline = time.monotonic() + _STARTUP_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"cv-service on :{port} exited at startup; see {log}")
        if _port_open(port):
            return process
        time.sleep(_PORT_POLL_SECONDS)
    process.send_signal(signal.SIGTERM)
    raise RuntimeError(f"cv-service on :{port} did not bind within {_STARTUP_TIMEOUT_SECONDS}s; see {log}")


def _stop(processes: "list[subprocess.Popen]") -> None:
    for process in processes:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
    for process in processes:
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:  # pragma: no cover - belt and braces
            process.kill()


# -- the load ---------------------------------------------------------------


def _requests(
    stream_id: str,
    ring: "list[bytes]",
    *,
    fps: float,
    seconds: float,
    model: str,
    result: StreamResult,
    stop: threading.Event,
) -> "Iterator[cv_pb2.FrameRequest]":
    """Push frames on a wall clock, never faster, never catching up.

    A `max(now, ...)` deadline clamp, the same rule `pull/loop.py`'s
    `DeadlineSampler` uses: after a stall the sender resumes at 10 fps rather
    than firing a catch-up burst that would measure the burst, not the load.
    """
    period = 1.0 / fps
    started = time.monotonic()
    deadline = started
    sequence = 0
    while not stop.is_set() and (time.monotonic() - started) < seconds:
        now = time.monotonic()
        if now < deadline:
            time.sleep(deadline - now)
        deadline = max(time.monotonic(), deadline) + period
        data = ring[sequence % len(ring)]
        result.sent += 1
        yield cv_pb2.FrameRequest(
            stream_id=stream_id,
            sequence=sequence,
            timestamp_millis=int(time.time() * 1000),
            width=_WIRE_WIDTH,
            height=_WIRE_HEIGHT,
            encoding=cv_pb2.IMAGE_ENCODING_JPEG,
            data=data,
            model_id=model,
            tracking=cv_pb2.TrackingConfig(mode=cv_pb2.TRACKING_MODE_ASSOCIATE),
        )
        sequence += 1


def _drive_stream(
    target: str,
    stream_id: str,
    ring: "list[bytes]",
    *,
    fps: float,
    seconds: float,
    model: str,
    result: StreamResult,
    stop: threading.Event,
) -> None:
    channel = grpc.insecure_channel(target)
    try:
        stub = cv_pb2_grpc.InferenceStub(channel)
        responses = stub.DetectStream(
            _requests(stream_id, ring, fps=fps, seconds=seconds, model=model, result=result, stop=stop)
        )
        for response in responses:
            result.received += 1
            result.latencies_ms.append(time.time() * 1000 - response.timestamp_millis)
    except grpc.RpcError as exc:  # pragma: no cover - reported, never fatal
        print(f"  ! stream {stream_id}: {exc.code().name if exc.code() else exc}", file=sys.stderr)
    finally:
        channel.close()


def _measure(
    target: str,
    *,
    streams: int,
    ring: "list[bytes]",
    fps: float,
    seconds: float,
    model: str,
) -> "list[StreamResult]":
    results = [StreamResult(stream_id=f"bench-{index}") for index in range(streams)]
    stop = threading.Event()
    threads = [
        threading.Thread(
            target=_drive_stream,
            args=(target, result.stream_id, ring),
            kwargs=dict(fps=fps, seconds=seconds, model=model, result=result, stop=stop),
            daemon=True,
        )
        for result in results
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        # Generous: the generator stops itself at `seconds`; this only bounds a
        # stuck stream, and a stuck stream is itself a reportable outcome.
        thread.join(timeout=seconds + 60)
    stop.set()
    return results


# -- scenarios --------------------------------------------------------------


def _run_scenario(args, ring: "list[bytes]", streams: int, log_dir: Path) -> RunResult:
    processes: "list[subprocess.Popen]" = []
    permits = args.permits
    base_env = {
        "CV_MODEL": args.model,
        "CV_MAX_CONCURRENT_INFERENCES": str(permits),
        # Tracking is the point: without it the "tracker" role has no work and
        # the split measures nothing. ASSOCIATE is the deployed default.
        "CV_TRACK_CAPABILITY_LEVEL": "0",
    }
    try:
        if args.scenario == "allinone":
            processes.append(
                _spawn({**base_env, "CV_SERVICE_ROLE": "all"}, port=args.port, log=log_dir / "allinone.log")
            )
            target = f"127.0.0.1:{args.port}"
        else:
            detector_ports = [args.port + 1 + index for index in range(args.detectors)]
            for index, port in enumerate(detector_ports):
                processes.append(
                    _spawn(
                        {**base_env, "CV_SERVICE_ROLE": "detector"},
                        port=port,
                        log=log_dir / f"detector-{index}.log",
                    )
                )
            targets = ",".join(f"127.0.0.1:{port}" for port in detector_ports)
            processes.append(
                _spawn(
                    {**base_env, "CV_SERVICE_ROLE": "tracker", "CV_DETECTOR_TARGETS": targets},
                    port=args.port,
                    log=log_dir / "tracker.log",
                )
            )
            target = f"127.0.0.1:{args.port}"

        if args.warmup > 0:
            _measure(target, streams=1, ring=ring, fps=args.fps, seconds=args.warmup, model=args.model)
        results = _measure(
            target, streams=streams, ring=ring, fps=args.fps, seconds=args.seconds, model=args.model
        )
    finally:
        _stop(processes)

    sent = sum(result.sent for result in results)
    received = sum(result.received for result in results)
    latencies = [value for result in results for value in result.latencies_ms]
    sustained = all(
        result.answered_ratio >= args.sustain_ratio and result.p95_ms <= args.sustain_latency_ms
        for result in results
    )
    return RunResult(
        scenario=args.scenario,
        streams=streams,
        detectors=args.detectors if args.scenario == "split" else 0,
        seconds=args.seconds,
        fps=args.fps,
        permits=permits,
        sent=sent,
        received=received,
        answered_ratio=received / sent if sent else 0.0,
        p50_ms=_percentile(latencies, 0.50),
        p95_ms=_percentile(latencies, 0.95),
        effective_fps=received / args.seconds if args.seconds else 0.0,
        sustained=sustained,
        per_stream=[
            {
                "stream_id": result.stream_id,
                "sent": result.sent,
                "received": result.received,
                "answered_ratio": round(result.answered_ratio, 4),
                "p50_ms": round(result.p50_ms, 1),
                "p95_ms": round(result.p95_ms, 1),
            }
            for result in results
        ],
    )


def _machine_facts() -> dict:
    """Everything a reader needs to know the number is not portable."""
    model_name = ""
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                model_name = line.split(":", 1)[1].strip()
                break
    except OSError:  # pragma: no cover - non-Linux
        pass
    try:
        import importlib.util

        openvino = importlib.util.find_spec("openvino") is not None
    except Exception:  # pragma: no cover
        openvino = False
    try:
        import torch

        cuda = bool(torch.cuda.is_available())
        torch_version = torch.__version__
    except Exception:  # pragma: no cover
        cuda, torch_version = False, "absent"
    return {
        "cpu": model_name,
        "nproc": os.cpu_count(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "torch": torch_version,
        "cuda_available": cuda,
        "openvino_installed": openvino,
    }


def main() -> None:
    parser = argparse.ArgumentParser(prog="detectorbench", description=__doc__)
    parser.add_argument("--scenario", choices=("allinone", "split"), required=True)
    parser.add_argument("--streams", default="1,2,3,4", help="comma-separated stream counts to sweep")
    parser.add_argument("--detectors", type=int, default=2, help="detector processes in the split shape")
    parser.add_argument("--seconds", type=float, default=30.0, help="measured window per stream count")
    parser.add_argument("--warmup", type=float, default=5.0, help="unmeasured single-stream warmup")
    parser.add_argument("--fps", type=float, default=10.0)
    parser.add_argument("--model", default="yolo26n.pt")
    parser.add_argument("--port", type=int, default=50551, help="tracker/all-in-one port; detectors take port+1..")
    parser.add_argument(
        "--permits",
        type=int,
        default=2,
        help="CV_MAX_CONCURRENT_INFERENCES per process; pinned so both shapes are comparable",
    )
    parser.add_argument("--sustain-ratio", type=float, default=0.95)
    parser.add_argument("--sustain-latency-ms", type=float, default=500.0)
    parser.add_argument("--out", default="", help="write the full JSON result here as well as stdout")
    args = parser.parse_args()

    log_dir = Path(os.environ.get("DETECTORBENCH_LOGS", "/tmp/detectorbench"))
    log_dir.mkdir(parents=True, exist_ok=True)

    print(f"building a {_RING_FRAMES}-frame ring at {_WIRE_WIDTH}x{_WIRE_HEIGHT} ...", flush=True)
    ring = _frame_ring()

    facts = _machine_facts()
    print(json.dumps(facts, indent=2), flush=True)

    runs: "list[RunResult]" = []
    for streams in [int(piece) for piece in args.streams.split(",") if piece.strip()]:
        print(f"\n== {args.scenario} / {streams} stream(s) ==", flush=True)
        run = _run_scenario(args, ring, streams, log_dir)
        runs.append(run)
        print(
            f"   answered {run.received}/{run.sent} ({run.answered_ratio:.1%}), "
            f"{run.effective_fps:.2f} fps total, p50 {run.p50_ms:.0f}ms p95 {run.p95_ms:.0f}ms, "
            f"sustained={run.sustained}",
            flush=True,
        )

    report = {"machine": facts, "args": vars(args), "runs": [asdict(run) for run in runs]}
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=2))
        print(f"\nwrote {args.out}", flush=True)


if __name__ == "__main__":  # pragma: no cover
    main()
