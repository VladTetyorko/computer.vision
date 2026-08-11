# REMOTE-CV-PLAN — offload YOLO inference to a remote GPU machine

Goal: laptop runs backend + frontend + mediamtx; a second machine (GPU) runs all
PyTorch/YOLO compute. Light JSON-sized detection data flows back; video viewing stays
on the laptop. Must degrade gracefully when the link to the GPU box drops.

## Assessment of the proposed plan (what already exists)

The externally-drafted plan proposed: remote CV pulls RTSP from mediamtx, pushes JSON
detections back over a *new* WebSocket/gRPC ingestion endpoint, backend forwards to SSE,
frontend draws canvas boxes. Checked against the codebase:

| Proposed step | Reality |
|---|---|
| "Send detections back as compact JSON via new WS endpoint" | **Already exists, better**: `DetectStream` gRPC bidi (`proto/vision/v1/cv.proto`) returns detections on the *same* connection the backend initiates. No new endpoint, no listener on the laptop, NAT/firewall-friendly (laptop dials out). |
| "Forward to SSE for the Fly OSD" | **Done**: `StreamPipeline` → `LiveUpdatePublisherPort.publishDetections` → live SSE detection topics (REALTIME-PLAN §4). |
| "Raw video + transparent canvas overlay" | **Done**: `shared/player/player.ts` canvas overlay + `detection-overlay-logic.ts` latency-sync matcher (CD-b §11 item 6), `BoxesMode` = `overlay`/`burned`/`off`. |
| "Expose ML controls (model, confidence)" | **Done**: `PipelineConfig.model` → `FrameRequest.model_id` plumb verified end-to-end; cv-service `ModelRegistry` + composite mode (CV-MODELS-PLAN); model picker in UI (U-c). |
| "Remove local ML execution" | Nothing to remove — cv-service is already a separate process behind `vision.cv.enabled` / `vision.cv.endpoint=localhost:50051`. |
| "CV pulls RTSP from mediamtx" | **The one genuinely new idea.** Not needed for the goal (see "Mode B" below); deferred. |
| "Open laptop firewall port 8554" | Only needed for Mode B. In the current architecture the connection direction is reversed: only the GPU box's `:50051` must be reachable, and the laptop initiates. Strictly simpler and safer. |

**Conclusion: remote offload is a config change, not an architecture change.** Set
`vision.cv.endpoint=<gpu-box>:50051`, run cv-service there. The frame path already
shrinks payloads for the wire: `GrpcDetectionPort` downscales wide BGR24 to ≤640px
JPEG q0.8 (~100–200 KB/frame → ~1–2 MB/s per stream at 10 fps) — fine on LAN/Tailscale,
comparable to what an RTSP pull would cost anyway.

## Fallbacks when the GPU box is unreachable (mostly already built)

- **Video never depends on CV.** Streams, HLS/WebRTC viewing, telemetry, recording all
  keep working; only boxes disappear. This is structural (DetectionPort is a side-branch
  of `StreamPipeline`), not a special case.
- **Outage/backoff**: `StreamPipeline` probes detection at 1s→10s backoff;
  `GrpcDetectionPort` has per-frame 2s timeouts and terminal session teardown, so the
  first probe after recovery succeeds. `PIPELINE_ERROR` events fire on outage begin.
- **cv-service side**: echo fallback (no model → empty detections, never crash-loops),
  `LatestOnlyMailbox` (latest-wins, no backlog on a slow link), `InferenceGate`.
- **Known real bug that matters exactly for remote deployment** (P0 below): the
  `grpc-core` 1.80.0/1.64.0 version skew can wedge the channel **permanently in
  `CONNECTING`** after a genuine TCP connect failure — documented in
  `adapter-cv-grpc/MODULE.md` Gotchas with the confirmed mechanism. On localhost you
  rarely hit real connect failures; over Wi-Fi/VPN you will, constantly.

## Priorities

### P0 — do now
1. **Pin `io.grpc:grpc-core:${grpc.version}` in the root pom `dependencyManagement`**
   and re-verify adapter-cv-grpc tests + a real kill-and-restart of cv-service against
   vision-app. Without this, the first Wi-Fi hiccup permanently kills detection until
   backend restart. (Known follow-up already documented in adapter-cv-grpc/MODULE.md.)
2. **Deploy cv-service to the GPU machine** (no code): clone, `pip install -e '.[cv]'`
   with CUDA torch wheel, `scripts/gen_proto.sh`, copy `orion12l.pt`,
   `python -m cv_service.server`. Laptop: `vision.cv.enabled=true`,
   `vision.cv.endpoint=<gpu-box>:50051`. Networking: same LAN or Tailscale; only
   `:50051` inbound on the GPU box.
3. **Verify GPU is actually used** — Ultralytics `select_device('')` auto-picks CUDA
   when available, but confirm via `inference_millis` + `nvidia-smi`; add an optional
   `CV_DEVICE` env (default auto) passed to `predict(device=…)` for explicitness. Small
   cv-service change, mirrors `CV_IMGSZ` parse idiom.

### P1 — next
4. **Operator visibility of CV health**: surface "detection offline" in the UI (the
   frontend already sees detections stop; add an explicit signal — e.g. a
   `PIPELINE_ERROR`-driven chip on the player / fleet summary) instead of silently
   empty boxes. Respect the documented "no fake sourceState" rule — derive from real
   outage events only.
5. **Wire-path tuning knobs**: `MAX_DETECT_WIDTH` / JPEG quality are package-private
   constants in `GrpcDetectionPort`; promote to `vision.cv.*` properties so per-network
   tuning (e.g. 480px over VPN) needs no rebuild. `inferenceFps` already exists per
   stream in `PipelineConfig`.

### P2 — worth doing, not urgent
6. **Local degraded-mode fallback**: optional second endpoint
   (`vision.cv.fallbackEndpoint=localhost:50051`) running yolo11n on the laptop CPU;
   `DetectionPort` failover wrapper switches when the primary is in outage, back when
   it recovers. Gives "some boxes" instead of "no boxes" offline. Design as a
   composing `DetectionPort` decorator in vision-app wiring — no adapter changes.
7. **TensorRT/half-precision export on the GPU box** (mirror of the existing OpenVINO
   CPU export step in the Dockerfile) once CUDA inference is measured.

### P3 — Mode B: cv-service pulls RTSP from mediamtx (the pasted plan's transport)
Do this **only when a concrete need appears**: full-frame-rate inference/tracking
(ByteTrack-style, needs every frame, not sampled ones) or offloading H.264 *decode* to
the GPU box. Design constraint if/when built: keep the laptop as connection initiator —
add an RPC (`StartPullDetect(stream_url, stream_id, …)` → server-streamed
`DetectionResponse`) to the existing proto instead of a new WebSocket ingest endpoint,
so detections re-enter through the existing `DetectionPort` result path (storage, SSE,
events, replay all unchanged). Requires: mediamtx `:8554` reachable from the GPU box,
capturedAt timestamps taken from RTSP RTP time (the frontend overlay sync matcher
depends on honest capture timestamps), and stream lifecycle mapping (path name ↔
StreamId).

## Transport decisions (WebSocket vs what we have)

Evaluated 2026-07-29, prompted by "should we use WebSockets for stability/weight?".
Answer: **no transport swap — both existing choices are already the stable-and-light
option for their leg; the stability lever is tuning, not replacing.**

**Browser ↔ backend — keep SSE.** The live channel is one multiplexed connection
(topics added/removed via PATCH without reconnecting — no per-topic connection
explosion), `EventSource` native auto-reconnect, `Last-Event-ID` resume backed by
`LiveRingBuffer` replay, reconnect topic restoration in `live-store.ts`, plus a polling
fallback. WebSocket has *none* of that natively — switching means rebuilding reconnect,
resume, and heartbeat by hand to arrive at equal resilience, for a frame-overhead saving
of a few bytes on a channel whose payloads are already tiny. Data flow is one-directional
(commands go over REST), so WS's bidirectionality buys nothing today.
*Revisit trigger:* browser-originated low-latency pilot input (MVP4 command TX) — that
wants a WebSocket or WebRTC data channel, decided in that plan, not this one.

*Deep-dive addendum (2026-07-29, second look at FE↔BE specifically):* the frontend opens
exactly **one** `EventSource` total (`live-store.ts`); the server side
(`LiveUpdateRegistry`) already implements resume (`Last-Event-ID` + `LiveRingBuffer`),
per-topic coalescing (`COALESCE_MILLIS`), proxy-keepalive heartbeat comments, and
no-reconnect topic updates (PATCH). A WS migration would rebuild all of that by hand
(reconnect/backoff, resume protocol, ping/pong, an in-band topic protocol) to arrive at
the same latency — SSE and WS are identical once the connection is up. Per-message
framing difference (~10 bytes) is noise at this app's event rates. The one honest
SSE limitation found: over plain HTTP (no TLS → browsers stay on HTTP/1.1) each open
tab's SSE connection counts against the browser's ~6-per-host connection cap alongside
HLS segment fetches — irrelevant at 1–2 tabs (the single-operator reality), and the
correct fix at 3+ tabs is TLS+HTTP/2 (SSE then multiplexes for free) or a SharedWorker
sharing the one connection, not a protocol change (WS sidesteps the cap but still pays
one socket per tab and loses the built-ins).

**Laptop ↔ GPU box — keep gRPC bidi.** Binary protobuf detections (~10× smaller than
JSON-over-WS), HTTP/2 flow control, per-frame deadlines, and the whole
correlation/backpressure/teardown machinery in `GrpcDetectionPort` that a WS channel
would force us to reimplement. What a flaky Wi-Fi/VPN link actually needs is **channel
keepalive tuning** so half-open TCP dies fast instead of lingering:
- **P1 task (after the grpc-bom pin lands, same module):** configure the
  `ManagedChannelBuilder` with `keepAliveTime(~20s)`, `keepAliveTimeout(~5s)`,
  `keepAliveWithoutCalls(true)`, and set the matching server-side permit knobs in
  cv-service's `grpc.server` options (`grpc.keepalive_permit_without_calls`,
  `grpc.http2.min_ping_interval_without_data_ms`) so the server doesn't GOAWAY the
  pinging client. Expose as `vision.cv.keepalive-*` properties only if defaults prove
  wrong in the field.

**Multi-node future — MQTT, but not yet.** If the platform grows past two machines
(several GPU workers, multiple operator stations, field relays), a broker with QoS,
retained messages, and last-will (free "node online/offline" presence) is the standard
drone-ops answer for telemetry/detection fan-out. Adding a broker for a 2-machine setup
is pure overhead. *Trigger:* 3+ independent producers/consumers, or a link that needs
store-and-forward.

## Non-goals

- No WebSocket detection-ingest endpoint on the backend (gRPC bidi already covers it,
  in the safer connection direction).
- No removal of the in-process pipeline path — `sim`/local dev must keep working with
  no GPU box present (`vision.cv.enabled=false` or local echo cv-service).
