# CV-SCALE-PLAN — on-demand, per-stream, multi-worker CV inference

Status: approved spec (2026-07-29). Turns CV from "one model, every started stream, one
machine" into "any of N models, only the streams someone is watching, spread across a pool
of CV workers you can grow from 1 to 5+ boxes." Builds on REMOTE-CV-PLAN (remote offload,
keepalive, grpc-bom pin) and CV-MODELS-PLAN (the model registry + composite mode).

The three models in play, by the user's names: **wr** = `orion12l.pt` (heavy military
YOLO12-L), **yolo old** = `yolo11n.pt`, **yolo new** = `yolo26n.pt` (current default). All
three already live in cv-service's `ModelRegistry`; composite mode already runs several on
one frame with label prefixes.

## Goals (user's words, made precise)

1. **Pick best-of / choose / change / update models from the UI** — per stream, live, no restart.
2. **Infer only what's watched** — 100 streams can ingest video, but CV compute is spent only
   on the streams a viewer actually has open. Opening a video subscribes it to detection;
   closing it unsubscribes.
3. **Per-stream override with default fallback** — change one stream's model/framerate and only
   that stream changes; every other stream keeps the default. "Subscription-like."
4. **Combine models** — composite (run 2–3 on one frame) selectable per stream, same mechanism.
5. **Scale to 2–3–5 CV machines** — adding a worker is a config line, not a redesign. Load
   spreads automatically; a dead worker fails over.
6. **WebSockets question answered honestly** for the CV transport, at scale.

## Current state (honest)

| Capability | Today | Gap for this plan |
|---|---|---|
| Per-stream config | `PipelineConfig{model, inferenceFps, confidenceThreshold, …}` set **at stream start** | No live reconfigure; changing model = restart the stream |
| Detection gating | Runs on **every** started stream, viewers or not | No demand signal; 100 streams = 100 inference loads |
| Model roster in UI | Static picker (`settings-store#DetectionModelId`), ids hardcoded on the client | No live roster/health from cv-service; can't see latency to pick "best" |
| CV workers | Single `vision.cv.endpoint` | No pool, no balancing, no per-stream worker affinity |
| Frame path | Backend decodes H.264 → pushes frames to worker over gRPC (push model) | Backend decode cost is O(active streams) — the real scale ceiling |

## The pivot that makes everything scale: demand + affinity

Two concepts thread through every phase:

- **Detection demand** — a per-stream refcount of "who wants boxes on this stream right now."
  The signal already exists: the live SSE channel tracks `detections:<assetId>` topic
  subscriptions (`LiveUpdateRegistry`). A viewer opening the player subscribes; closing
  unsubscribes. **That subscription IS the demand signal** — no new client concept. Detection
  for a stream is active iff demand > 0 (or its policy is `always`, see below).
- **Stream→worker affinity** — a given stream's frames always go to the *same* CV worker while
  active (session/model-cache locality, and cv-service's own per-stream `LatestOnlyMailbox`
  assumes one caller). New streams are assigned to the least-loaded healthy worker. This is
  sticky-least-loaded routing, the standard shape for stateful-ish RPC fan-out.

Together these mean: **cost is O(watched streams), not O(ingested streams)**, and watched
streams spread evenly across the worker pool. 100 ingesting + 8 watched on 3 workers ≈ 3
inference loads per box, not 33.

## Phases

### S1 — Live per-stream detection reconfigure (backend, no new infra)
The prerequisite for "change one stream, only it changes."
- `StreamPipeline` gains `reconfigureDetection(DetectionSettings)` — swaps model / inferenceFps
  / confidence / labelFilter on the running pipeline **without** touching the video path
  (the source subscription, publisher, overlay all keep running). Config is currently final at
  construction; make the detection-relevant subset a single mutable, volatile-published holder
  read on each sample decision. The frame/video path is untouched — only what gets sent to
  `DetectionPort#detect` and how often changes.
- `DetectionSettings` = the per-stream detection knobs split out of `PipelineConfig` (model,
  confidenceThreshold, inferenceFps, labelFilter). `PipelineConfig` keeps them for the start
  call; the mutable holder is seeded from it.
- API: `PATCH /api/streams/{streamId}/detection {model?, inferenceFps?, confidenceThreshold?,
  labelFilter?}` — partial, only supplied fields change; 200 returns the effective settings.
- **Default inheritance**: a global `DetectionDefaults` (later: per-category) supplies any field
  a stream hasn't overridden. Overriding is copy-on-write per stream; clearing an override
  reverts that field to the default. This is the "subscription" semantics: streams share the
  default until they diverge.
- Model swap is honest-cheap on the cv-service side (registry is lazy; first frame on the new
  model pays its warmup once, already handled). A composite id (`"yolo26n.pt,orion12l.pt"`)
  is just another value here — combine-the-flow needs no extra mechanism.

### S2 — On-demand detection gating (backend, the 100-streams answer)
- `DetectionDemand` (application) — per-stream refcount fed by `LiveUpdateRegistry`'s
  `detections:<assetId>` subscribe/unsubscribe. `StreamPipeline` consults it: a sampled frame
  is submitted to `DetectionPort#detect` only when demand > 0, else skipped (video still flows).
- **Grace window**: demand dropping to 0 keeps inference alive for a short cooldown (default
  30s, configurable) so quick tab-flips don't thrash model warmup / worker reassignment.
- **Per-stream detection policy**: `on-view` (default — cheap, cost only while watched) vs
  `always` (event alerting / recording keeps detecting with no viewer). Set on the same PATCH
  as S1. Honest tradeoff surfaced in the UI: `always` streams show they consume compute
  continuously. Events (`DetectionEventEngine`) only fire while detection is active — documented,
  not hidden: an `on-view` stream nobody watches raises no alerts (that's the point).
- Metrics: expose active-detection count and per-worker load so the UI can show "8 of 100
  streams currently analysed, 3 workers, 62% headroom."

### S3 — Model roster & health from cv-service (choose "best" informed)
- cv-service: new `ListModels` RPC (or reuse a lightweight admin path) → roster with per-model
  metadata: id, family, warm/cold, last measured `inference_millis`, resident (loaded) flag.
  Optionally a `ReloadModels` RPC to rescan the model dir so a newly-copied `.pt` becomes
  routable **without restart** (the "update models" ask) — lazy registry already supports the
  load; this just refreshes discovery.
- Backend: `GET /api/cv/models` aggregates the roster across the worker pool (union of ids,
  min/median latency per id) so the picker shows real numbers, not client-hardcoded ids.
- UI: the existing model picker (`settings-store`, fly rail) becomes data-driven from this
  endpoint — each model shows a latency/weight hint ("yolo new ~140ms · wr ~340ms · combine
  both ~350ms") so the user picks best-value per stream. Composite is a multi-select in the
  same control.

### S4 — Multi-worker pool (scale to N boxes)
- `vision.cv.endpoints=host1:50051,host2:50051,…` (supersedes single `endpoint`; single value
  still works = pool of one). Each becomes a `GrpcDetectionPort` (keepalive-tuned, per
  REMOTE-CV-PLAN).
- `PooledDetectionPort implements DetectionPort` — sticky-least-loaded router: new stream →
  least-loaded healthy worker, remembered for that stream's life; per-worker health from the
  gRPC channel state + a cheap periodic ping (reuse the connect-failure-recovery guarantees
  from the grpc-bom pin). A worker going down re-routes its streams to survivors (their next
  frame opens a fresh session elsewhere — cv-service is stateless-per-stream enough for this,
  detections just resume). Composes with the S2 demand gate (only active streams hold a slot)
  and the REMOTE-CV-PLAN failover decorator (a pool of one can still have a local fallback).
- GB4005 stays in the story as one small Intel/OpenVINO worker in a heterogeneous pool; routing
  is load-based so a slow worker simply gets fewer streams.

### S5 — Pull-based frames (the real scale ceiling, biggest change) — **DECIDED: GO**
The decision was taken on 2026-08-12 and its execution is owned by
[MEDIA-SOT-PLAN.md](MEDIA-SOT-PLAN.md), which widens it from "the worker pulls frames" to "mediamtx is
the video source of truth for viewing *and* CV". The body below stands as the original statement of the
problem; the frozen contract, the phasing and the waves live in that plan.

Today the **backend** decodes H.264 for every active stream and pushes frames to workers. That
decode cost is O(active streams) on the backend — the ceiling that caps how far S4 scales.
The scalable answer flips it: **the CV worker pulls RTSP from mediamtx and decodes locally**;
the backend only sends control (start/stop/reconfigure detecting stream X) and receives
detections. Decode load then lives on the CV pool, which is exactly the thing you're scaling.
- New cv-service RPC `DetectPulled(stream_url, stream_id, config) → stream of DetectionResponse`
  (the REMOTE-CV-PLAN §P3 idea, now load-bearing). The demand gate (S2) drives
  start/stop; reconfigure (S1) rides the control channel; the pool (S4) picks the worker.
- Detections re-enter the **existing** `DetectionPort` result path — storage, SSE, events,
  replay all unchanged. Only the transport of *frames* changes (push→pull); the transport of
  *results* is untouched.
- Requires: mediamtx `:8554` reachable from every worker; capturedAt taken from RTP time
  (the overlay latency-sync matcher depends on honest capture timestamps); path-name ↔ StreamId
  mapping. This is the one phase that needs a real architecture decision and a measured before/
  after — hence its own phase, sequenced last and gated on S1–S4 proving the model.

## WebSockets to cv-service — verdict (asked directly, at scale)

**Keep gRPC; do not move the CV transport to WebSockets — at every layer gRPC is the better
fit, and more so as workers multiply.**
- **Results/frames channel** (worker ↔ backend): binary protobuf (~10× smaller than JSON),
  HTTP/2 flow control + per-frame deadlines, and the correlation/backpressure/teardown machinery
  already in `GrpcDetectionPort`. A WS rebuild reaches parity at best, after reimplementing all
  of it by hand.
- **Control channel** (S5 start/stop/reconfigure): gRPC server-streaming or a small unary
  control RPC covers it with the same typed contract — no second protocol, no hand-rolled
  framing. WS would only win if CV workers were polyglot/browser-based, which they are not.
- **The real scaling lever is pull-vs-push frames (S5) and demand-gating (S2), not the wire
  protocol.** Swapping gRPC→WS changes bytes-on-the-wire by noise while leaving the actual
  ceiling (backend decode cost) exactly where it is. Spend the effort on S2/S5, not a transport
  migration. (If a future worker is a third-party CV service that only speaks WS, wrap it behind
  a `DetectionPort` adapter — the pool router doesn't care what protocol a worker uses.)

## Best-value-for-the-user summary

- **Cheapest win, do first**: S1 + S2 — live per-stream model/fps choice and pay-for-what-you-
  watch. Turns "100 streams melts one machine" into "8 watched streams sip one machine," on the
  hardware you have today, no new boxes.
- **Then**: S3 (informed model choice with real latency) + S4 (add boxes as a config line).
- **Then, deliberately**: S5 pull-pivot when backend decode becomes the measured ceiling —
  it's the difference between "scales to a few dozen watched streams" and "scales to hundreds
  across a worker fleet."

## Sequencing & waves

- **Wave 1** (parallel, disjoint): S1 backend (domain/application/api reconfigure) · S3
  cv-service roster/reload RPC + proto.
- **Wave 2** (after S1): S2 demand gating (application + live-registry hook + api policy field) ·
  S3 backend aggregate endpoint + UI data-driven picker (after S3 cv-service).
- **Wave 3**: S4 pool router (vision-app wiring + a `PooledDetectionPort` in adapter-cv-grpc or
  vision-app) — after S2 so the demand gate already bounds worker load.
- **Wave 4** (decision + measure): S5 pull-based frames — its own spike first (one worker
  pulling one RTSP path end-to-end), then a go/no-go with real decode-cost numbers. Executed as
  MEDIA-SOT-PLAN waves M0–M9; the spike this wave asked for is M0.
- Every wave: read module MODULE.mds first, update after, scoped builds only, local-only. The
  frozen wire contracts (PATCH detection, GET /api/cv/models, endpoints property) are pinned
  above so UI and backend waves parallelize.

## Non-goals / guardrails

- No change to the detection **result** contract (DetectionResult/SSE/overlay) in S1–S4 — only
  when/where/which-model inference runs changes, not what a detection looks like.
- No fake "best model" auto-selection — the platform shows honest per-model cost and lets the
  user choose; an auto-picker (pick model by scene/latency budget) is a possible S6, explicitly
  out of scope now.
- On-demand gating must never affect the **video** path — an unwatched stream still records/
  publishes exactly as before; only boxes are demand-gated (same doctrine as REMOTE-CV-PLAN's
  fallback: video never depends on CV).
