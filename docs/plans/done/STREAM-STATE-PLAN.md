# STREAM-STATE-PLAN — a stream has a state, and detection intent is readable

Status: **active** (2026-08-19). Branch `feat/stream-state` (off `master`).
Evidence: `STREAM-STATE-CONTEXT.md` — read it first; every decision below answers a measured fact.

**Goal, in the owner's words:** *"make a state — state, not a guess."*

Two things are guesses today and this plan turns both into facts:

1. **Whether a stream is alive.** `GET /api/streams` lists running streams and carries no state, so
   the SPA reconstructs one by latching on a poll gap (`cockpit-facade.ts:207`).
2. **Whether detection is on for a stream.** `PipelineConfig#detectionEnabled` is write-only over
   HTTP, so the cockpit's Detect switch renders *this browser's localStorage draft* instead.

And one thing is nobody's job: **stopping a stream nobody is watching.** Measured cost of that
omission on the owner's machine: ~2.9 cores, indefinitely, for a stream left running by accident.

## 1. The rule this plan is built on: two axes, never collapsed

`DetectionState`'s javadoc already draws the line — it **reports gating, never health**. This plan
adds the third axis and keeps all three apart:

| Axis | Question | Type | Where it lives after this plan |
|---|---|---|---|
| **Video flow** | are frames arriving? | `StreamState` *(new)* | computed per running stream |
| **Detection gating** | are both CV gates open? | `DetectionState` *(exists)* | `StreamPipeline#detectionState()` |
| **Session lifecycle** | did this stream happen, and when did it end? | `AssetUsage` *(exists, persisted)* | `vision-warehouse` |

Collapsing any two rebuilds an ambiguity a previous wave already paid to remove. A stream can be
`LIVE` while detection reads `OFF`, `IDLE_NO_VIEWERS` or `RUNNING`; none of those says the video is
unwell, and `STALLED` says nothing about the detector.

### 1.1 What this plan does *not* build, because it already exists

**There is no new persistence here.** The first draft of this plan proposed an "ended stream"
table to answer the owner's *"where do I store if a stream is archived"*. That was wrong:
`AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId,
phase)` is already exactly that record — persisted, opened by `UsageTracker#onStreamStarted` and
closed by `onStreamStopped`, already surfaced as the cockpit's Replay link
(`fly-logic.ts#latestFinishedUsage`). The only gap is that **`AssetUsageRepositoryPort` has no
lookup by `streamId`**, so nothing can ask "what happened to stream X" — one repository method, not
a schema (wave S5).

## 2. Frozen contract

Everything in this section is pinned; waves may parallelize against it.

### 2.1 New domain type — `vision-perception`, `domain.model`

```java
public enum StreamState { STARTING, LIVE, STALLED, RECONNECTING, UNOBSERVED }
```

- `STARTING` — started, **no frame observed yet**. `start()` is synchronous (`pipeline.start()` has
  already run when it returns), so this is not a handshake state; it is the genuine "opened, nothing
  arrived yet" window.
- `LIVE` — a frame arrived within `videoStaleAfter`.
- `STALLED` — frames were arriving and stopped, with **no reopen in progress**. A fault worth
  surfacing.
- `RECONNECTING` — the source's `SupervisedPublisher` is in reopen backoff. Also a fault, but a
  *self-healing* one, and the UI must say so differently.
- `UNOBSERVED` — a **proxied** source (`MEDIA-SOT-PLAN.md` D4): this JVM opens no `VideoSourcePort`
  at all, so `framesObserved` is 0 forever and liveness is genuinely unknowable from here.
  **Explicitly not a fault** — the enum's javadoc must say so, or it will be wired to a red light.
  This state is the reason `STARTING` cannot double as "no frames yet".

**No `STOPPED` member.** A stopped stream is not a running stream with a state; it is absent from
`GET /api/streams` and present in `AssetUsage` with an `endedAt`. Adding `STOPPED` here would make
the enum answer two axes at once — exactly what §1 forbids.

**Resolution order** (a pure `static` on the enum, so it is unit-testable with no pipeline):

```java
static StreamState resolve(boolean sourceObservable, boolean reconnecting,
                           long framesObserved, long nanosSinceLastFrame, long staleAfterNanos)
```

1. `!sourceObservable` → `UNOBSERVED`
2. `reconnecting` → `RECONNECTING`
3. `framesObserved == 0` → `STARTING`
4. `nanosSinceLastFrame > staleAfterNanos` → `STALLED`
5. otherwise → `LIVE`

`RECONNECTING` outranks `STARTING`/`STALLED` deliberately: while the supervisor is retrying, "no
frames" is *explained*, and reporting the symptom over the cause loses information.

**Video only.** In pull mode a second `SupervisedPublisher` supervises the *detection result*
stream; it must not feed this enum. A pulled-detection outage is a detector fact, and
`DetectionState`'s javadoc already reserves detector health to the outage/`PIPELINE_ERROR`
machinery.

### 2.2 Accessors the computation needs (all additive, all cheap)

- `StreamPipeline#framesObserved()` → `long`. Field exists (`volatile long framesObserved`); only a
  getter is new.
- `StreamPipeline#nanosSinceLastFrame()` → `long`, `Long.MAX_VALUE` when no frame has ever arrived.
  Reads the pipeline's own injected nanoTime clock, so tests keep the seam they already have.
- `SupervisedPublisher#reconnecting()` → `boolean`. Derived from the existing `pendingRetry`/
  `outageAnnounced` state; **no new field on the hot path**.

### 2.3 `StreamService` — one new read, one widened record

```java
Optional<StreamState> streamState(StreamId streamId);
```

Forgiving: empty for an unknown/stopped stream, exactly like `detectionState`/`trackingStats` and
every other read on this interface.

```java
Optional<PipelineConfig> config(StreamId streamId);
```

**Added during implementation, not in the first draft of this section.** §2.5 declares
`GET /api/streams/{id}/config` but the interface had no way to *read* a running configuration at
all — `updateConfig` was write-only all the way down, not just at the HTTP edge. Same forgiving
contract.

`ActiveStream` gains two components — it is the shape `GET /api/streams` is built from, and the
whole point is that one poll answers both questions the cockpit asks every 5 seconds:

```java
record ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt, boolean burnedIn,
                    StreamState state, boolean detectionEnabled)
```

Per this repo's idiom, the pre-existing 4-arg and 3-arg constructors stay as convenience ctors
(defaulting `state = UNOBSERVED`, `detectionEnabled = false`) so no call site breaks.

### 2.4 Settings — `StreamPipelineSettings` gains one component

`Duration videoStaleAfter`, default **5s**. At the measured ~28 fps source rate a 5s gap is
unambiguous; a deliberately slow source (a 1 fps still camera) must raise it or it will read
`STALLED` forever. Same N-1-arg convenience-ctor chain the record already uses.

### 2.5 Wire — `vision-api`

`ActiveStreamResponse` gains three fields, all always serialized:

| Field | Source | Answers |
|---|---|---|
| `state` | `ActiveStream#state` | is the video flowing? |
| `detectionEnabled` | `ActiveStream#detectionEnabled` | **the operator's own intent — the field that ends the localStorage guess** |
| `detectionState` | `StreamService#detectionState` | are both CV gates open? |

New endpoint — the missing read half of a write-only knob:

```
GET /api/streams/{streamId}/config  → 200 the stream's effective PipelineConfig
                                    → 404 unknown/stopped stream
```

Reuses the DTO shape `PATCH .../config` already echoes, so the drawer renders server truth for every
knob it can edit, not just detection. Without this, `PATCH` remains the only door and every control
in the CV drawer keeps rendering a draft.

### 2.6 Usage lookup — `vision-warehouse`

```java
Optional<AssetUsage> findByStream(StreamId streamId);   // AssetUsageRepositoryPort
```

Implemented in `adapter-persistence` over the existing `stream_id` column — **no migration**. This
is what lets a caller ask "what happened to stream X" and get the ended record instead of an
absence.

### 2.7 Properties

| Property | Default | Meaning |
|---|---|---|
| `vision.pipeline.video-stale-after` | `5s` | no frame for this long → `STALLED` |
| `vision.streams.idle.enabled` | `true` | stop streams nobody is watching (wave S4) |
| `vision.streams.idle.timeout` | `10m` | how long with zero demand before a stream is stopped |
| `vision.streams.idle.check-interval` | `30s` | how often idleness is re-evaluated |

**`video-stale-after` moved from `vision.streams.*` to `vision.pipeline.*` during S2**, where its
siblings already live (`assumed-source-fps`, `warmup-frames`, the two backoff pairs,
`camera-hfov-degrees`, `adaptive-rate`). It is a threshold over the pipeline's own frame cadence, not
a lifecycle policy; inventing a second root for one key, while the *actual* lifecycle keys (S4's
idle policy) genuinely want `vision.streams.*`, would have split one concern across two roots and
put this key under the wrong one.

## 3. Waves

| Wave | Scope | Module(s) | Est |
|---|---|---|---|
| **S1** | `StreamState` + `resolve`, the three accessors, `streamState()`, widened `ActiveStream`, settings | `contexts/vision-perception/**` | M |
| **S2** | the three response fields + `GET .../config` + properties | `station/vision-api/**`, `station/vision-app/**` | M |
| **S3** | cockpit binds to stream truth; draft demoted to "default for the next Start" | `station/vision-web/**` | M |
| **S4** | idle-stream policy — stop burning cores for nobody | `contexts/vision-perception/**` + config | M |
| **S5** | `findByStream` + the ended-stream read path | `contexts/vision-warehouse/**`, `storage/persistence/**`, `station/vision-api/**` | S |

S1 must land before S2 compiles (`vision-api` → `vision-perception`). S3 is independent of both and
may be written against the frozen contract in §2.5. S4 depends on S1's settings shape only. S5 is
independent of everything above.

### 3.1 S3's one rule — where the switch reads from

The draft is not deleted; it is **demoted to its honest job**. One rule, applied to the Detect
switch, the rail's off-dot and the video-surface "Turn on" chip alike:

- **a stream is running** → every live control renders `stream().detectionEnabled` (server truth),
  and a PATCH applies the server's **echoed** value rather than assuming success.
- **no stream is running** → controls render the draft, which is what the next `Start` will post.

That single rule kills D1: switching drones, reloading, or opening a second browser can no longer
show a switch position that is false for the stream in front of you.

### 3.2 S4's crux — what "nobody is watching" means for *video*

Detection demand (`DetectionDemandPort`) is not reusable as-is: it answers "is anyone consuming
*detections*", and a stream can be watched as plain video with detection off — which, after
`CV-DEMAND-PLAN.md`, is the **default**. Reusing it would auto-stop exactly the streams this
deployment is designed to run.

Video demand is the union of what the app can actually observe:

- an open SSE connection subscribed to this asset's cockpit topics (`LiveUpdateRegistry`),
- a recent HLS request through the app's own `/hls/**` proxy,
- a recent snapshot/detections poll for the stream,
- **mediamtx's reader count for the path** — `GET /v3/paths/get/{streamId}` on the Control API the
  app already talks to (`MediamtxProxyPublisher`, loopback-bound `19997:9997`). This term is not
  optional: **WHEP viewers connect straight to mediamtx and are invisible to the app**, so without
  it S4 would stop a stream someone is actively watching over WebRTC.

Failure direction is pinned, and it is the same one `LiveAndPollDetectionDemand` chose: **an
unreadable signal fails open** — "we could not tell" must never be reported as "nobody is watching",
because that error stops a stream in front of a live operator. A stop must also publish its reason
(`Event`/`EventType`) so the SPA can say *"stopped — no viewers for 10 minutes"* rather than
letting a deliberate policy read as a crash.

## 4. Deliberately NOT in scope

- **`inferenceFps` is fiction.** Measured: the sampler asks ~22 fps, the detector delivers 3.2
  (`dropRatio` 0.856). The in-flight drop is cheap — `StreamPipeline.java:1270` rejects *before*
  submit/encode — so this wastes little CPU, but the operator's own knob lies about what it will
  get. Surfacing the achieved ceiling on the control is its own wave.
- **Stopping the *pull* worker.** Unchanged from `CV-DEMAND-PLAN.md` §7: a gated-off pull stream
  still burns full inference on `cv-service` because `detection_enabled` is not on the pull wire.
  Cross-language, still open.
- **A backend `suspend`** (keep the source open, stop publishing). `suspended` exists only in the
  browser player. S4 stops streams instead; suspend is a larger lifecycle change and is not needed
  to recover the measured cores.
- **`fleet-store.ts:371` logs `POST /api/streams/{id}/stop` while issuing `DELETE`.** A one-line log
  lie; fixed in passing during S3, noted here so it is not mistaken for a contract change.

## 5. Status

| Wave | Status |
|---|---|
| S1 | **done** — `contexts/vision-perception` **522/522**, BUILD SUCCESS. `RECONNECTING`-outranks-`STARTING` verified *empirically* (branch order reversed → exactly one failure observed → restored), not by inspection |
| S2 | **done** — `station/vision-api` **722/722**, BUILD SUCCESS; `station/vision-app` compiles and its property binding is wired |
| S3 | **done** — `station/vision-web` **2255/2255**, 129 files, `npm run test:ci` (bundles the app first, so every new template binding is type-checked) |
| S4 | **done** — `vision-perception` **536/536**, `adapter-publish-hls` control-api **18/18**, `vision-api` demand **8/8**, and `station/vision-app` **237/237** BUILD SUCCESS (real Spring contexts, so `StreamLifecycleWiring`'s three beans and the ArchUnit rules are proven, not assumed) |
| S5 | **done** — `vision-warehouse` **186/186**, `adapter-persistence` **183/183** against a real Postgres 16 container (incl. the two new `findByStream` cases), `vision-api` **734/734**. No migration, as pinned |

## 6. Found during implementation

**The SSE snapshot builds the same response as the REST poll, and the SPA prefers SSE.**
`StreamController#list` and `LiveUpdateRegistry#freshDevicesEnvelope` each constructed
`ActiveStreamResponse` by hand from an `ActiveStream`. Adding fields to the controller alone would
have left them **missing from the transport actually in use** — `FleetStore` takes the `devices`
snapshot when the live connection is open and falls back to `GET /api/streams` only when it is not,
so the new fields would have looked correct in a curl and been absent in the browser. Both call
sites now go through `ActiveStreamResponse#from`, which is where any future field lands once.

**`vision-api` compiled against a stale `vision-perception` jar.** `./mvnw -pl station/vision-api`
without `-am` resolved the previous jar out of `~/.m2` and reported `cannot find symbol` for types
that existed in the source tree. Scoped builds in this repo need `-am` whenever an upstream module
changed in the same task — already recorded as a project-wide gotcha, re-confirmed here.

**One unrelated flake, not caused by this work.** `drone-link/mavlink-core`'s
`RequestResponseTest.aClassifierExtensionKeepsWaitingWithoutResendingUntilATerminalReplyArrives`
failed once during a `-am` run and passed 3/3 in isolation immediately after. It is a resend-timing
test, and the machine was simultaneously running the app at ~140% CPU plus cv-service at ~147% —
this task's diff touches no file under `drone-link/`.

**A second load-flake, same cause, different module.** `station/vision-app`'s
`TrackingAssociateE2ETest` failed once under the full `-am` run with *"expected >=3 detector passes
in the window, saw 2"* and passed in isolation immediately after. Like the mavlink one above it is a
wall-clock-window test, and the machine was still running the app plus cv-service at ~2.9 cores
between them — which is, with some irony, the exact waste this plan's wave S4 exists to stop.
`-Dmaven.test.failure.ignore=true` plus reading `target/surefire-reports/*.txt` directly is how both
were separated from real failures; a reactor-level "BUILD FAILURE" line would have hidden which
module actually broke.

**S3 found a fourth defect, outside the three `STREAM-STATE-CONTEXT.md` measured.** The Detect
*status sentence* under the switch (`detectionStatus`, wave U3's "one honest sentence") read the
draft too — so "Off — video only, zero detection cost." could sit directly beneath a switch the
backend had **on**, each half of the same hero section contradicting the other while both were
"correct" by their own rule. It now reads the same resolved value the switch does. This is the
argument for §3.1 being *one* rule rather than one fix per surface: there were four surfaces, not
three, and only a single shared resolver makes that count irrelevant.

**The PATCH response does not echo the config, so §3.1's "echoed value" is served by a re-read.**
`UpdateStreamConfigResponse` carries `(streamId, modelReArmed, trackingChanged)` — no
`detectionEnabled`. Rather than widen it, `setDetection` PATCHes and then calls
`FleetStore#refresh()`, which re-reads `GET /api/streams` — the same list the control is bound to.
Same guarantee (nothing renders until the wire says so), no new field, and the refresh is scoped to
this one control rather than added to `patchStreamConfig`, which every debounced slider drag also
goes through.

**S4's video-demand terms, as actually built.** §3.2 listed four; three shipped, and the fourth was
checked and rejected on evidence:

| §3.2 term | Shipped as | Notes |
|---|---|---|
| open SSE subscription | `LiveUpdateRegistry#watchingAsset` | new, and deliberately broader than `watchingDetections` — that one asks about *boxes*, which are off by default and say nothing about video |
| recent HLS request through `/hls/**` | `HlsProxyController` → `LiveHlsAndReaderVideoDemand#touched` | stamped **before** the upstream call, so a viewer still counts while mediamtx is slow |
| recent snapshot/detections poll | **rejected** | `GET /api/streams/{id}/snapshot`'s only SPA callers are the calibration wizard and the alert panel, each showing one static JPEG. Counting it would have kept streams alive for a page nobody has open. Checked in the SPA rather than assumed |
| mediamtx reader count | `MediamtxReaderProbe` → `MediamtxControlApi#hasReaders` | the WHEP term; a presence test, not a count |

**A fourth property beyond §2.7's table: `vision.streams.idle.demand-ttl` (30s).** The HLS-proxy term
is a stamp, and a stamp needs a window. It must comfortably exceed the segment duration or a viewer
would drop out of the count between two segment fetches — a value the plan's table simply had no row
for because it predated the decision to make HLS a *touch* rather than a live count.

**Rejecting the snapshot endpoint changed the shape of S4.** Wiring it would have meant a fourth
collaborator on `StreamController`, which sits at this codebase's five-parameter ceiling — the whole
reason `StreamDetectionSupport` exists. Widening that bundle to carry video demand would have made its
name a lie, and renaming it would have touched seven files. Establishing that the endpoint is not a
watching signal removed all of it: `HlsProxyController` (two constructor args) was the only touch
point needed.

**No new `EventType`.** The first sketch published a separate "stopped because idle" event, which
would have meant two `STREAM_STOPPED`-shaped events per stop. Instead `stop` took a `StopReason` and
the existing event carries it in both the message and a structured `reason` attribute — the message
for a human reading the ticker, the attribute for a client that must branch without parsing English.

**S5 needed no migration and no new DTO, and both were checked rather than assumed.** The column
(`stream_id`, `V4__usage_stream_id.sql`) has existed since MVP2's R-a2 — R-a2 wrote the link,
S5 reads it back. The wire type is `UsageSummaryResponse`, the row `GET /api/usages` already
serves, rather than the older `AssetUsageResponse`: that one carries positions but no `assetName`
or `durationSeconds`, and no controller returns it directly today (it is embedded in
`AssetDetailsResponse`), so reusing it would have introduced a second usage row shape for clients
to handle.

**What S5 did *not* buy, stated plainly: `stream_id` is unindexed.** The plan pinned "no
migration", so `findByStream` is a sequential scan over `asset_usages`. That is defensible — one
row per flight, and the query runs once when someone asks about one stream, never on a hot path —
but it is a real property of the shipped code, recorded here and in `storage/persistence`'s
MODULE.md rather than left for whoever first runs it against a large fleet to discover.

**The endpoint went on `UsageTimelineController`, and `StreamController` was the trap.** "What
happened to stream X" reads like a stream endpoint, and `StreamController` is at the
five-parameter ceiling — the same wall S4 hit and dodged by rejecting the snapshot signal. But the
resource here is a *usage*: the stream is gone, which is the whole reason the lookup exists. Under
`/api/usages` it needed no new collaborator at all (`UsageService` was already injected for the
list), and it keeps §1's axis split intact instead of quietly re-collapsing "running stream" and
"stream that happened" onto one controller.
