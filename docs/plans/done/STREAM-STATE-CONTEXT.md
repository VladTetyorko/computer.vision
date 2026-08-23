# STREAM-STATE-CONTEXT — what was measured before the plan was written

Companion to `STREAM-STATE-PLAN.md`. This file is the *evidence*; the plan is the *spec*. Written
2026-08-19 from a running deployment, not from reading code alone — every number below came off the
live instance, and the plan's shape is a consequence of them.

## 1. The complaint

> "I have an issue with a State of stream and detection. Where do I store/handle if stream of asset
> is archived? Or where do I turn on or off the detections for the stream? Nowhere. The UI doesn't
> handle it properly and resources of my PC are wasting. Let's check the flow of each button on
> /fly page to backend and see if we can make a state — state, not a guess."

Both questions were rhetorical and both answers are "nowhere". That is the finding, not a figure of
speech: there is no stream lifecycle state anywhere in the system, and the per-stream detection
switch is write-only over HTTP.

## 2. What the machine was doing

One stream, started 18:50:41Z, observed at 19:00:20Z — running ~10 minutes:

```
GET /api/streams   → 1 stream (streamId ba75cb3a…, burnedIn: true)
GET .../tracks     → detectionState: RUNNING, 27 live tracks
ps                 → java 140% CPU · python cv_service 147% CPU   (~2.9 cores)
rate               → targetFps 25, submittedFps 3.20, dropRatio 0.856, missedDeadlines 307
latency            → roundTripP50 607ms, roundTripP95 717ms, worstBoxAge 1045ms
tracking stats     → engine cost, dutyRatio 1.0, trackerP50 157ms
labels observed    → "cloud forest", "landfill" (0.79 conf, full-frame box), "number icon"
```

Three things are wrong in that snapshot and only one of them is a tuning problem:

1. **The stream is running because nobody ever stopped it**, not because anybody wanted it.
2. **Detection is on because it was switched on once**, and nothing has ever said so since.
3. `inferenceFps` is fiction — the sampler asks for ~22 fps and the detector delivers 3.2. The
   in-flight drop is *cheap* (`StreamPipeline.java:1270` rejects before submit/encode, verified), so
   this wastes little CPU, but it makes the operator's own fps knob a lie. Out of scope for the
   plan; recorded here so it is not rediscovered as a surprise.

## 3. Where each fact actually lives

| Fact | Real home | What `/fly` reads | Honest? |
|---|---|---|---|
| stream exists | `DefaultStreamService#activeStreams`, `GET /api/streams` | `fleet.streamFor(deviceId)` | yes |
| stream lifecycle | **nowhere** — no enum, no column, no field | `stopped = explicitlyStopped \|\| (hasBeenLive && !live)` (`cockpit-facade.ts:207`) | **guess** |
| stream ended/archived | **nowhere** — a stopped stream vanishes from the list | absence from an array | **guess** |
| detection intent | `PipelineConfig#detectionEnabled`, in-memory, **write-only over HTTP** | `settings.effective().detectionEnabled` — localStorage, global to the browser | **guess** |
| detection gate result | `DetectionState` on `GET .../tracks` | read, but only by a status line inside a closed-by-default drawer | yes |
| asset archived | `LifecycleState.DELETED` — persisted, audited, soft | — | yes |

`ActiveStreamResponse(streamId, deviceId, startedAt, viewUrl, whepUrl, burnedIn)` carries no state
and no detection fields, and **there is no `GET /api/streams/{id}/config`** — `PATCH` is the only
door. The server knows whether detection is on for a stream and has no way to say it.

## 4. The three defects, located

**D1 — the Detect switch is per-browser, not per-stream.**
`cv-control-panel.html:15` binds `[checked]="settings.effective().detectionEnabled"`. So does the
rail's "Detection is off" dot (`cockpit.html:369`) and the video-surface "Turn on" chip
(`cockpit-facade.ts:700`). Switch drones and the switch keeps its old position while the new
stream's server config is untouched; two browsers disagree; a reload after someone else started the
stream shows fiction. `CV-DEMAND-PLAN.md` §1 was explicit that intent "must survive a page reload" —
it does, but only as *this browser's* intent, which is not the same thing.

**D2 — nothing ever stops a stream.**
`DefaultStreamService` schedules exactly two tasks: the demand poll and source-reopen backoff. There
is **no idle reaper** (grep-verified across `contexts/vision-perception` and `station/vision-app`).
Closing the tab does tear down the SSE topic correctly (`detections-store.ts:151` →
`teardownTracking`), so the CV gate closes after the 30s grace — but decode, Java2D burn-in, encode
and RTSP push to mediamtx run at ~28 fps forever, for nobody, until a human clicks Stop. `suspended`
exists **only in the browser player** (`player.ts:297`); there is no backend suspend at all.

**D3 — `detectionEnabled` is sticky and invisible.**
It lives only in the in-memory `PipelineConfig`, survives every reconnect for the life of the
stream, and has no read surface. Combined with D2, one forgotten click costs ~3 cores indefinitely.

Minor, real: `fleet-store.ts:371` logs `POST /api/streams/{id}/stop` while issuing
`DELETE /api/streams/{id}`.

## 5. What the code can and cannot observe

The plan's state set is bounded by this, and deliberately not larger:

- `start()` is **synchronous** — `pipeline.start()` has run by the time it returns. There is no
  server-side `STARTING` handshake to model; what exists is "started, no frame seen yet".
- `stop()` removes from `activeStreams` synchronously and tears down on a virtual thread. From
  outside, `STOPPING` is unobservable.
- `StreamPipeline` already tracks `framesObserved` and `lastFrameArrivalNanos` (both volatile) — so
  "no frame yet" and "frames stopped arriving" are genuinely distinguishable facts, not inventions.
- `SupervisedPublisher` already tracks `outageAnnounced` + `pendingRetry` — so "the source is in
  reopen backoff" is observable; it just has no accessor.
- **A proxied stream opens no `VideoSourcePort` at all** (`MEDIA-SOT-PLAN.md` D4) — `framesObserved`
  stays 0 forever and the JVM genuinely cannot judge liveness. This is the trap that forces an
  explicit "not observed" state rather than a `STARTING` that never ends.

## 6. The rule this inherits

`DetectionState`'s javadoc already draws the line the new enum must respect: it **reports gating,
never health**. `StreamState` is the other axis — it reports the *video* stream's flow and
lifecycle, never whether the detector is well. Two streams can be `LIVE` with `detectionState`
`OFF`, `IDLE_NO_VIEWERS` and `RUNNING` alike; collapsing the axes would rebuild the exact ambiguity
`CV-DEMAND-PLAN.md` §3.6 spent a wave removing.
