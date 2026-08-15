# Deploying cv-service on a GPU box

> **Deployed reality (2026-07-29): the actual remote box, GB4005
> (`vlad@192.168.0.106`), has NO NVIDIA GPU** — it's an Intel Gemini Lake
> mini-PC (2 cores, 7.6 GB RAM, UHD 600). The CUDA-specific parts of this
> runbook (`CV_DEVICE=cuda:0`, `nvidia-smi`, default-PyPI torch wheel) do
> not apply there; what was actually done instead:
> - Sources arrive by **rsync** (no git on the box, repo has no remote):
>   `rsync -az --exclude .venv --exclude cv_service/gen --exclude __pycache__
>   cv/cv-service proto vlad@192.168.0.106:~/vision/` (lands flat as `~/vision/cv-service`)
> - Install with the **CPU torch wheel** (`--extra-index-url
>   https://download.pytorch.org/whl/cpu`), the opposite of step 2's advice.
> - Speedup path is **OpenVINO**, not CUDA: `pip install openvino`, then
>   `yolo export model=yolo26n.pt format=openvino imgsz=416`, and the systemd
>   unit sets `CV_MODEL=yolo26n_openvino_model` (no `CV_DEVICE`).
> - Measured over the wire from the laptop: yolo26n PyTorch CPU ≈ 230 ms/frame,
>   OpenVINO IR ≈ **135–150 ms/frame** (~7 fps ceiling; keep per-stream
>   `inferenceFps` ≤ 5 against this box). `orion12l` is not real-time here.
> - Unit installed as `/etc/systemd/system/cv-service.service`
>   (`systemctl status cv-service`, logs via `journalctl -u cv-service`).
>
> The rest of this runbook remains the reference for a real CUDA box.

Runbook for running `cv-service` on a separate CUDA-capable machine while the
laptop keeps running the Spring Boot backend, the Angular frontend, and
mediamtx. Background/design: `docs/plans/done/REMOTE-CV-PLAN.md` — the short version is
this is a **config change, not an architecture change**: `DetectStream` is a
gRPC bidi call the laptop *initiates*, so the only thing that moves is where
`cv_service.server` runs and what `vision.cv.endpoint` points at.

## 1. Prereqs (GPU box)

- An NVIDIA GPU + a working driver (`nvidia-smi` must succeed before you
  start — if it doesn't, fix the driver install first; nothing below
  installs one).
- Python 3.12 (this repo's own dev environment; `pyproject.toml` only
  requires `>=3.11`, but match 3.12 to avoid surprises).
- `git`.

CUDA itself doesn't need a separate manual install in the usual case — the
PyPI PyTorch wheel installed in step 2 bundles its own CUDA runtime
libraries. You only need the *driver* on the host.

## 2. Install

```bash
git clone <this-repo-url> vision
cd vision/cv/cv-service

python3.12 -m venv .venv
source .venv/bin/activate

# NOTE: no --extra-index-url here. cv/cv-service/MODULE.md and pyproject.toml
# document installing with
#   --extra-index-url https://download.pytorch.org/whl/cpu
# for CPU-only dev boxes -- that index only serves CPU-only torch wheels.
# On this GPU box you want the opposite: the DEFAULT PyPI torch wheel, which
# is CUDA-enabled on Linux. Installing plain `pip install -e '.[cv,dev]'`
# (no extra index) is what pulls the CUDA build.
pip install -e '.[cv,dev]'

scripts/gen_proto.sh
```

Then copy the model weights this repo doesn't check in (`*.pt` is
gitignored — see `cv/cv-service/MODULE.md` "`CV_MODEL` / weights location"):

```bash
# From the laptop (or wherever the checked-out weights currently live):
scp orion12l.pt gpu-box:~/vision/cv-service/
```

`orion12l.pt` is a user-provided checkpoint with no network source of truth
— it only ever moves by direct file transfer (provenance note in
`docs/plans/done/CV-MODELS-PLAN.md`: an earlier draft had the Dockerfile *download* it
from a third-party repo at build time, rejected as an untrusted-code vector;
this repo never fetches model weights over the network).

Also copy whichever checkpoint `cv_service/config.py`'s `DEFAULT_MODEL`
constant (or your own `CV_MODEL` override) currently names — check that
constant in the checkout you cloned, since it's a code default and can
change independently of this doc. If it names a well-known Ultralytics
alias (e.g. `yolo11n.pt`), `ultralytics.YOLO(...)` *can* download it
automatically on first load instead, but copying it avoids that box needing
internet access at all, and avoids surprises if the alias isn't resolvable
from wherever the GPU box actually sits on the network. Whatever you copy,
`registry.discover_roster()` picks up every `*.pt` file present in
`cv/cv-service/` automatically (see `cv/cv-service/MODULE.md` "Model registry &
composite mode") — no code change needed to make an extra checkpoint
routable via `model_id`.

## 3. Run

```bash
CV_DEVICE=cuda:0 python -m cv_service.grpc.server
```

Other env knobs (all documented in `cv/cv-service/MODULE.md`'s API surface —
none of these are new, `CV_DEVICE` is the only one this deployment adds):

| Var | Purpose | Default |
|---|---|---|
| `CV_DEVICE` | Inference device passed to `predict()`. Unset/blank = ultralytics auto-selects (would normally pick CUDA here anyway; set it explicitly so it's *observable* in logs and `nvidia-smi`, not just assumed). | unset (auto) |
| `CV_MODEL` | Default model id/path if `FrameRequest.model_id` doesn't name a known one. | `yolo26n.pt` |
| `CV_IMGSZ` | Square inference input size in pixels (must be a multiple of 32 by Ultralytics convention). | `416` |
| `CV_MAX_CONCURRENT_INFERENCES` | Process-wide cap on concurrent `detect()` calls across streams (`InferenceGate`). Raise this on a GPU box once measured — the CPU-tuned default (`min(2, cpu_count // 2)`) is a CPU-contention number, not a GPU one. | `min(2, cpu_count // 2)`, floored at 1 |

Startup log confirms both the model roster and the resolved device (see
"Verify" below) — nothing here silently no-ops.

### Optional: systemd unit

Modeled on this repo's existing edge-device units
(`infra/edge/systemd/vision-rtsp-push.service`,
`infra/edge/systemd/mavlink-router.service`) — same `Restart=on-failure`/
`RestartSec=2` shape, no CHANGE-ME sprawl beyond the two paths.

```ini
## /etc/systemd/system/cv-service.service
#
# Runs cv-service's gRPC inference server on this GPU box, CUDA-explicit via
# CV_DEVICE. See vision/cv-service/DEPLOY-GPU.md for the full deploy runbook.
#
#   sudo cp cv-service.service /etc/systemd/system/
#   sudo systemctl daemon-reload
#   sudo systemctl enable --now cv-service
#
# Edit WorkingDirectory/ExecStart's venv path below if you cloned somewhere
# other than ~/vision.

[Unit]
Description=cv-service: Python gRPC YOLO inference (GPU)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/home/CHANGE-ME-user/vision/cv-service
Environment=CV_DEVICE=cuda:0
ExecStart=/home/CHANGE-ME-user/vision/cv-service/.venv/bin/python -m cv_service.server
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
```

## 4. Network

Open only **inbound `50051/tcp`** on the GPU box. The connection direction
is the laptop dialing out to the GPU box (that's the whole point of the
gRPC-bidi design in `docs/plans/done/REMOTE-CV-PLAN.md` — no listener needed on the
laptop, no new firewall hole there). Either works:

- **Same LAN**: point at the GPU box's LAN IP/hostname.
- **Tailscale**: install Tailscale on both machines, then use the GPU box's
  **tailnet hostname** (e.g. `gpu-box.tailXXXX.ts.net:50051`) as the
  endpoint — works across networks/off-LAN without opening anything on your
  actual router, and Tailscale's own ACLs give you the port restriction for
  free instead of hand-managing `ufw`/`iptables`.

## 5. Laptop side

In `station/vision-app/src/main/resources/application.yaml`, under its `cv:` block (or
override via env — Spring's relaxed binding maps these to `VISION_CV_ENABLED` /
`VISION_CV_ENDPOINT`, see `docker-compose.yml` for the same pattern used
against the in-compose `cv-service` container):

```yaml
vision:
  cv:
    enabled: true
    endpoint: <gpu-box-host-or-tailnet-name>:50051
```

`vision.cv.endpoint` must be `host:port` (`VisionCvProperties` validates
this and fails fast on anything else). No other backend config changes —
`GrpcDetectionPort` already downscales/JPEG-encodes frames before sending
(≤640px, q0.8, ~100-200 KB/frame), so LAN or Tailscale bandwidth is not a
concern at typical stream counts.

## 6. Verify

1. **Startup log on the GPU box** should show both the model roster and the
   resolved device — the exact model names will match whatever `*.pt` files
   you copied plus the code's current `DEFAULT_MODEL`/your `CV_MODEL`
   override, e.g.:
   ```
   cv-service model registry roster: ['orion12l.pt', 'yolo11n.pt'] (default='yolo11n.pt')
   cv-service inference device=cuda:0 (model='yolo11n.pt')
   ```
   If the second line says `device=auto` instead of `device=cuda:0`, `CV_DEVICE`
   wasn't actually set in the process's environment — check the systemd
   `Environment=` line or your shell export. If the default detector fails
   to load at all (e.g. `DEFAULT_MODEL`/`CV_MODEL` names a checkpoint you
   didn't copy and isn't a resolvable Ultralytics alias from this box), the
   servicer degrades to echo mode — see "Then copy the model weights..."
   above.
2. **`nvidia-smi`** on the GPU box, while a stream with detection is active
   on the laptop, should list the `python` process (`cv_service.server`)
   under GPU processes with non-zero memory usage.
3. **`inference_millis` drops vs. the CPU baselines.** `cv/cv-service/MODULE.md`'s
   measured table (12-core CPU, plain PyTorch `.pt`, `imgsz=416`) is the
   comparison point:

   | model | CPU `detect()` mean |
   |---|---|
   | `yolo11n.pt` | ~58.5ms |
   | `orion12l.pt` | ~343ms |
   | composite (`yolo11n.pt,orion12l.pt`) | ~346ms |

   A modern CUDA GPU should land these well under 20ms for `yolo11n.pt` and
   under ~50-80ms for `orion12l.pt` — if `inference_millis` (visible in
   backend logs / detection responses) is still in the CPU ballpark, device
   selection didn't actually take (re-check step 1's log line and
   `nvidia-smi`, in that order).
4. **Fallback when the GPU box is unreachable.** This is the scenario
   `docs/plans/done/REMOTE-CV-PLAN.md`'s "Fallbacks" section exists for: pull the
   network cable, stop the `cv-service` process, or block `50051` and watch
   the laptop side. Video, HLS/WebRTC viewing, telemetry, and recording all
   keep working unmodified — `DetectionPort` is a side-branch of
   `StreamPipeline`, not something video depends on — only the detection
   boxes disappear. `StreamPipeline` probes detection on a 1s→10s backoff
   while the endpoint stays down, so the first probe after the GPU box (or
   the network path to it) comes back succeeds without a backend restart;
   `PIPELINE_ERROR` events fire on outage begin so this is observable, not
   silent. (The root-pom `grpc-core` version pin — `docs/plans/done/REMOTE-CV-PLAN.md`
   P0 item 1 — is what makes this recovery reliable rather than
   occasionally wedging the gRPC channel permanently in `CONNECTING` after
   a genuine connect failure; confirm it's in place if reconnection ever
   looks stuck.)
5. **Keepalive on a half-open link (`docs/plans/done/REMOTE-CV-PLAN.md` "Transport
   decisions" P1).** Item 4 above covers a *clean* stop (process killed, port
   blocked) — TCP/gRPC notices that quickly on its own. Keepalive exists for
   the messier case: a link that goes silently dead with no FIN/RST at all
   (a Wi-Fi drop, a VPN tunnel blackhole, a NAT/router mapping expiring) —
   the OS TCP stack itself can take minutes to notice a socket like that is
   dead (its own retransmission-timeout defaults are *not* seconds), and
   nothing above it would find out any sooner without an explicit
   liveness check. HTTP/2 keepalive pings are that explicit check: the
   client (`GrpcDetectionPort`) pings every `KEEPALIVE_TIME_SECONDS`
   (20s, including on an otherwise-idle stream — `keepAliveWithoutCalls`)
   and gives up on the connection after `KEEPALIVE_TIMEOUT_SECONDS` (5s)
   with no ack; this service's own `_KEEPALIVE_SERVER_OPTIONS` permits
   those pings (`grpc.keepalive_permit_without_calls`) instead of GOAWAY-ing
   the client for "abusive" pinging, and pings back
   (`grpc.keepalive_time_ms`/`grpc.keepalive_timeout_ms`) to detect a dead
   client and free that stream's server-side thread just as promptly. To
   verify: with a stream actively detecting, firewall-drop the network path
   between the laptop and the GPU box for under a minute (rather than
   stopping `cv-service`, so the socket goes half-open instead of closing
   cleanly), then restore it. Backend logs (`GrpcDetectionPort`'s
   `WARNING`-level transport-error/timeout lines) should report the outage
   within roughly `KEEPALIVE_TIME_SECONDS + KEEPALIVE_TIMEOUT_SECONDS` (~25s
   worst case) instead of the connection quietly sitting on the OS's own,
   much longer, TCP-level failure detection — and detection should resume
   automatically once the link is back, via the same probe/backoff recovery
   item 4 describes.
