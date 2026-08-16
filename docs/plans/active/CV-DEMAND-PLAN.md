# CV-DEMAND-PLAN — detection is opt-in, and stops when nobody is looking

Status: **active** (2026-08-16). Branch `feat/cv-demand`.

Today every started stream runs inference from its first frame, forever, whether or not a human is
looking at the result. That is fine for one stream and ruinous for twenty — which is exactly the
measurement the product owner wants to make ("test performance on many videos firstly, and switch
detections on only for the videos I want").

**Goal, in the owner's words:** *"detections off until the operator turns it on. Otherwise — just
video. And when no users are on Fly, or the switch is off, don't run the CV pipeline at all."*

## 1. Two independent gates

Inference runs for a stream iff **both** hold:

| Gate | Means | Owner | Default |
|---|---|---|---|
| `PipelineConfig#detectionEnabled` | **operator intent** — "I want boxes on this stream" | the human, per stream, live via `PATCH /api/streams/{id}/config` | **off** (this plan flips it) |
| detection **demand** | **someone is actually consuming the output** | the system, derived, never edited | on when un-gated |

They are deliberately not merged into one flag. Intent is a decision a person made and must survive
a page reload; demand is a fact about the world that changes as browsers come and go. Collapsing
them would mean either an operator's choice silently reverting when they close a tab, or a stream
burning CPU because someone once ticked a box.

`StreamPipeline` computes `effective = config.detectionEnabled() && detectionDemand`.

## 2. What "someone is watching" actually means

The SPA already tells the backend this, and has since REALTIME-PLAN — it just was never read:

- **SSE**: `detections:<assetId>` is a *ref-counted opt-in* topic (`live-store.ts:233`), materialized
  server-side as `LiveTopic.detections(assetId)` in each connection's topic set
  (`LiveTopic.java:40`). Subscribed **only** by the Fly cockpit (`cockpit-facade.ts:438`) — it is
  literally the "a cockpit is open on this asset" signal.
- **Poll**: the Wall and Live pages take the other path — `detections.track(streamId)` with **no**
  assetId (`wall-tile.ts:103`, `live-facade.ts:195`), which falls back to polling `GET
  /api/streams/{id}/detections`.

So demand must be **protocol-agnostic**, or turning the gate on would silently blank the Wall — a
page whose whole job is watching many streams at once. Demand is therefore:

```
demand(stream) = an SSE connection subscribes to detections:<assetId>
              OR that stream's detections were polled within poll-ttl
```

plus a **grace period** after the last demand, so navigating between pages does not thrash the
detector on and off.

> **Why polled, not event-driven.** A subscribe/unsubscribe callback would have to hook connect,
> `updateTopics`, disconnect *and* connection expiry; one missed decrement leaks CPU forever and one
> missed increment kills detection with no way to notice. A 2-second scan over a handful of live
> connections cannot miss an edge, and costs nothing measurable.

## 3. Frozen contract

Everything here is pinned; waves may parallelize against it.

### 3.1 New driven port — `vision-perception`, `domain.port`

```java
public interface DetectionDemandPort {
    /**
     * Whether anything is currently consuming this stream's detections.
     * @param assetId nullable — the stream's owning asset, absent for a device-only stream
     */
    boolean detectionWanted(StreamId streamId, AssetId assetId);
}
```

Contract: cheap, non-blocking, **must not throw** (the caller catches anyway). Threading: called
from one scheduler thread, never the video path.

### 3.2 `StreamPipeline`

- `void updateDetectionDemand(boolean demanded)` — sets a `volatile` field; hot, no lock, visible to
  the next frame. Mirrors `updateConfig`'s shape.
- `boolean detectionDemand()` — reads it. Field **initialises to `true`**: fail-open, so a
  deployment with no demand port wired behaves exactly as today.
- `maybeDetect` gates on `config.detectionEnabled() && detectionDemand` — one added conjunct at the
  existing early return (`StreamPipeline.java:1084`), which already documents itself as the single
  detection gate.

### 3.3 `DefaultStreamService`

- Nullable `DetectionDemandPort` collaborator (N-1-arg convenience ctor chain, as every other
  optional collaborator in this class).
- One task on the **existing** `retryScheduler`, every `detectionDemandPollInterval`, over all
  running streams:
  1. `assetId` = `usageTracker != null ? usageTracker.resolveAsset(deviceId).orElse(null) : null`
  2. `wanted = port.detectionWanted(streamId, assetId)`
  3. `wanted` → stamp `lastDemandAt`; else `effective = now - lastDemandAt < detectionDemandGrace`
  4. `pipeline.updateDetectionDemand(effective)`
- **The task body must catch `Throwable`.** `scheduleAtFixedRate` cancels all future runs if a task
  throws — a single NPE would silently freeze demand at its last value for the JVM's life.
- Null port → task never scheduled; nothing gates.

### 3.4 Settings — `StreamPipelineSettings` gains two components

`Duration detectionDemandPollInterval` (default 2s), `Duration detectionDemandGrace` (default 30s).

### 3.5 `vision-api` — the port's implementation

New `LiveAndPollDetectionDemand implements DetectionDemandPort`:
- SSE half: `assetId != null && liveUpdateRegistry.watchingDetections(assetId)`.
  `LiveUpdateRegistry` gains `boolean watchingDetections(AssetId)` — `connections.values().stream()
  .anyMatch(c -> c.topics().contains(LiveTopic.detections(assetId)))`.
- Poll half: a `ConcurrentHashMap<StreamId, Instant>` stamped by `void touched(StreamId)`, true
  while within `poll-ttl`. Entries older than the ttl are pruned on read.
- `StreamController`'s detections read endpoint calls `touched(streamId)`.

### 3.6 The gate must be legible — `DetectionState`

A gate the UI cannot see is a gate that reads as a bug. Once inference can stop for two different
reasons, "no boxes" has three causes the operator must be able to tell apart, and `submittedFps == 0`
cannot distinguish them (raised as a backend candidate in `CV-UX-RESEARCH.md` §9.2 — adopted here
rather than deferred, because this plan is what creates the ambiguity):

```java
public enum DetectionState { OFF, IDLE_NO_VIEWERS, RUNNING }
```

- `OFF` — `detectionEnabled` is false. The operator's own choice.
- `IDLE_NO_VIEWERS` — enabled, but demand is absent (past the grace period). Not a fault.
- `RUNNING` — both gates open. Says nothing about whether the detector is *healthy* — a stalled
  cv-service still reads `RUNNING`, which is what the existing outage/`PIPELINE_ERROR` machinery is
  for. This enum reports **gating**, never health, and its javadoc must say so.

`StreamPipeline#detectionState()` → `StreamService#detectionState(StreamId)` (forgiving: empty for
an unknown/stopped stream, like every other read on that interface) → served on
`StreamTracksResponse`, beside the `rate`/`latency` objects it already carries.

### 3.7 Properties — `vision.cv.*` (`VisionCvProperties`, `application.yaml`)

| Property | Default | Meaning |
|---|---|---|
| `detection-default-enabled` | `false` | what a **new** stream's `detectionEnabled` starts at |
| `demand.enabled` | `true` | wire the demand gate at all |
| `demand.poll-interval` | `2s` | how often demand is re-evaluated |
| `demand.grace` | `30s` | keep detecting this long after the last consumer leaves |
| `demand.poll-ttl` | `10s` | how long one detections poll counts as demand |

`detection-default-enabled` is a **deployment** default, so it beats `PipelineConfig.defaults()` but
loses to an explicit request field. That ordering only works if the DTO merges onto a *provided*
default rather than the static one, hence 3.7.

### 3.8 Defaults become injectable (`vision-api` + `vision-app`)

`StartStreamRequest#mergeOntoDefaults()` currently calls the **static** `PipelineConfig.defaults()`
(`StartStreamRequest.java:78`), so no deployment can move the default. It becomes
`mergeOnto(PipelineConfig defaults)`; `vision-app` provides a `PipelineConfig` defaults bean =
`PipelineConfig.defaults()` with `detectionEnabled` from the property. Call sites: `StreamController`,
`StartAssetStreamRequest`, `DemoFleet`.

`PipelineConfig.DEFAULT_DETECTION_ENABLED` also flips to `false`, so the code default and the
deployment default agree and a stream started by any other path is dark too.

## 4. Waves

| Wave | Scope | Module(s) | Est |
|---|---|---|---|
| **D1** | `detectionEnabled` default off; port + pipeline gate + service evaluator + settings | `contexts/vision-perception/**` | M |
| **D2** | demand impl, registry read, poll touch, properties, defaults bean, wiring | `station/vision-api/**`, `station/vision-app/**` | M |
| **D3** | SPA: profiles default off; detection made an obvious, honest act in the cockpit | `station/vision-web/**` | S |

D1 must land before D2 compiles (`vision-api` → `vision-perception`). D3 is independent of both.

## 5. Deliberately NOT in scope — and the one honest gap

**Pull transport is not gated, and `detectionEnabled=false` does not stop it today either.** With
`vision.cv.frame-transport=pull` the Python worker opens the RTSP itself and paces its own
inference; `maybeDetect` returns early for *every* pull stream regardless of the flag
(`StreamPipeline.java:1084`), and `detectionEnabled` appears **nowhere** in `cv/grpc` — it is not on
the pull wire at all. So in pull mode the off-switch is decorative.

This is a pre-existing bug, not one this plan introduces, and it does not affect this deployment:
`frame-transport` defaults to `push` and `docker-compose.yml` does not override it. Closing it needs
a proto/worker change (stop/start the session, or a `detect_enabled` wire field) — its own wave,
listed here so it is not rediscovered as a surprise.

**Consequences of the gate that are inherent, not defects** — all documented in the affected
MODULE.md files:
- Debounced `DetectionEvent`s are not produced while a stream is ungated, so unattended alerting
  stops. `demand.enabled=false` is the escape hatch for a deployment that needs it.
- An event left OPEN when demand ends stays open until detection resumes — `DetectionEventEngine`'s
  absence check only runs on a completed result (already a documented gotcha).
- Training capture and detection persistence see the same gap.

## 6. Status

| Wave | Status |
|---|---|
| D1 | **done** — `contexts/vision-perception` 480/480, BUILD SUCCESS |
| D2 | **done** — `vision-api` 576/576, `vision-app` 240/240; full reactor `./mvnw -B verify -DskipWeb` **BUILD SUCCESS** across every module, ArchUnit included |
| D3 | **done** — `station/vision-web` 115 files / 1934 tests, tsc clean both configs |

**Verified independently, not taken on report.** Both module builds re-run by the coordinator. D1's
grace-period regression test was checked *empirically* against the pre-fix code — the seed reverted to
`Instant.now()`, the suite re-run, exactly one failure observed
(`expected: <Optional[IDLE_NO_VIEWERS]> but was: <Optional[RUNNING]>`), then the fix restored. The
implementing agent had honestly reported verifying that one only "conceptually".

Two corrections the coordinator made to D1 after review:
1. `lastDemandAt` seeded `Instant.now()` → **`Instant.EPOCH`**. The original assumed a full grace
   period of demand at every stream start, so twenty freshly started streams would each have burned
   30s of inference for nobody — precisely the load spike this plan exists to prevent. Demand must be
   *observed*, never assumed; the ≤2s startup window is covered by `detectionDemand`'s fail-open default.
2. `DetectionState` (§3.6) added — the gate creates a three-way ambiguity ("off" vs "idle, unwatched"
   vs "stalled detector") that the SPA cannot resolve from `submittedFps == 0`. A gate the UI cannot
   see reads as a bug.

And one to D2: `LiveAndPollDetectionDemand#detectionWanted` swallowed an internal failure and
returned **`false`** — turning "we could not determine this" into the confident "nobody is watching",
which would gate detection off for every stream at once *and* report `IDLE_NO_VIEWERS` to an operator
demonstrably watching. Now fails **open**, with the direction pinned by its own test.

**Three E2E tests use the escape hatch** (`vision.cv.demand.enabled=false` in
`@DynamicPropertySource`): `CvDetectionE2ETest`, `TrackingAssociateE2ETest`,
`CvDetectionEndpointE2ETest`. Legitimate — none of them ever opens the SSE topic or polls
`.../detections`, so the gate would correctly turn detection off mid-test; they exercise the CV pipe,
not the gate, which has its own unit coverage. Worth knowing that **no E2E test covers
stream + viewer + gate-enabled end to end** — the gate is proven by unit tests only.
