# ALWAYS-ON-FLOW — context

**Started 2026-09-06**, on `feat/first-cycle` (stacked on `docs/e2e-flow-audit`, itself on `master`).

## The ask (owner, verbatim)

> u know what, i can see the issue in user's activities tht are impacting performance of app. So,
> user can stop stream- and it will stop the telemetry. I need instead- telemetry flow as a always
> on flow. I need event-based application that shows the user only state and history, but not fully
> gives access to all streams and so on. Because the mediamtx is capable of getting many streams,
> but the UI - is not. And one more thing - allignment of computer vision in this flow.

## What the owner is actually describing

Four statements, each independently verified below:

1. **A user gesture tears down a data flow it should not own.** Stopping a stream stops telemetry.
2. **The app should be event-based**: the default surface is *state and history*, derived from
   facts the backend produced on its own — not a live pipe the UI holds open.
3. **Capacity is asymmetric.** mediamtx ingests many streams; a browser renders few. Today the
   thing that scales (ingest) is gated by the thing that does not (the UI).
4. **CV must be aligned to the same flow** — it currently is not; it is the most viewer-gated
   subsystem in the platform.

## Verification (2026-09-06, against code, not docs)

The audit that preceded this learned the hard way that *a doc's own status line is not evidence*.
Every claim below names the file and line that shows it.

### Finding 1 — telemetry is a side effect of video, by construction

`UsageTracker#subscribeTelemetry` (`contexts/vision-perception/.../pipeline/UsageTracker.java:714`)
has exactly **two** production callers:

- `deviceStreamStarted` (`:621`) — the asset's **first active video device**
- `engage` (`:354`) — an explicit operator gesture

Nothing opens a `TelemetrySourcePort` at boot, at asset registration, or on device discovery.

The teardown, verbatim (`:685`):

```java
tearDownTelemetry = allDevicesStopped
        && !(tracking.usage != null && tracking.usage.origin() == UsageOrigin.OPERATOR);
```

So a plain **STREAM**-origin asset — the ordinary case, since nothing calls `engage` automatically —
loses its MAVLink telemetry subscription when its last video device stops. The owner's report is
exact.

### Finding 2 — it is worse than a manual Stop: the teardown is automatic

`IdleStreamReaper` stops a stream with `StopReason.IDLE_NO_VIEWERS` after **10 minutes** with no
viewer (`IdleStreamReaper.java:156`). It is **enabled by default** and `vision.streams.*` appears
**nowhere** in `application.yaml` or `docker-compose.yml`, so the defaults are live in production.

A "viewer" is inferred, never registered (`LiveHlsAndReaderVideoDemand.java:91`):

```java
return (assetId != null && watchingAsset.test(assetId))   // an SSE topic is open on the asset
        || recentlyTouched(streamId)                      // an HLS segment was fetched within 30s
        || hasReaders.test(streamId);                     // mediamtx reports a reader
```

Closing the last browser tab therefore ends video, then the usage, then telemetry — with no
operator gesture at all.

### Finding 3 — the transport is *already* always-on; only the claim and the sink are gated

This is the finding that makes the fix cheap.

`DiscoveryInboxRunner` calls `mavlinkTelemetrySource.holdLobby(14550)` at boot and every 30 s
(`DiscoveryInboxRunner.java:97`; `vision.discovery.lobby.enabled: true`). The UDP socket is bound,
heartbeats flow, and `MavlinkGateway#unregister` refuses to close a socket while the lobby is held
(`MavlinkGateway.java:236`).

But the lobby **registers no claim** — its own javadoc says so
(`MavlinkTelemetrySource.java:293`). Vehicles heard while held land in `unclaimedVehicles`, which
feeds *discovery*, never `TelemetryService`. The only thing that creates a telemetry data path is
`MavlinkTelemetrySource#open(Device)` (`:143`), building a `SubmissionPublisher<Telemetry>` and
calling `gateway.register(...)` — reachable only from `subscribeTelemetry`.

So: **the socket layer is always-on and correctly reference-counted already.** What is missing is a
per-device claim not keyed off video, and a sink that accepts samples without an open usage.

### Finding 4 — the sink refuses samples without an open usage, on purpose

`applySample` returns early when `tracking.usage == null` (`UsageTracker.java:805`), and
`contexts/vision-perception/MODULE.md:267` records this as doctrine:

> A telemetry-only session's opening is always explicit (`engage`), never an implicit side effect
> of a sample arriving.

Changing that is a **doctrine change, not a bug fix**, and this plan must treat it as one. The
reason the rule exists is sound: a straggler sample racing a legitimate close must not fabricate a
phantom flight. The resolution is not to delete the rule but to stop conflating two different
things — *a flight* (a session, which should stay explicit) and *a live link* (a fact about the
world, which should not need permission to be true).

### Finding 5 — CV is the most viewer-gated subsystem, including its event path

The gate (`StreamPipeline.java:936`):

```java
private boolean detectionGateOpen() {
    return config.detectionEnabled() && detectionDemand;
}
```

`detectionDemand` is re-derived every 2 s from "is a browser subscribed to the `detections:<assetId>`
SSE topic, or did someone poll `GET /api/streams/{id}/detections` within 10 s", with a 30 s grace.

Crucially, **the entire durable fan-out sits behind the same gate** (`:1200`): with no viewer, there
is no `DetectionRepositoryPort#save`, no `DETECTION` platform `Event`, and no `DetectionEvent`
open/close from `DetectionEventEngine`. Unattended alerting does not happen. This is acknowledged,
not accidental — `application.yaml:566` calls `vision.cv.demand.enabled=false` *"the escape hatch
for a deployment that wants unattended alerting to keep running with nobody watching."*

Two assets make the fix cheaper than it looks:

- **A viewer-independent precedent already exists.** `LiveAndPollDetectionDemand.java:129` ORs in
  `hasCameraPose` — a calibrated fixed camera is permanently demanded, with no viewer. An
  "always-on" term is structurally identical.
- **The vocabulary is already specified.** `CV-SCALE-PLAN.md` §S2 defines a per-stream policy
  `on-view` (default) vs `always`. Nothing named `on-view`/`always`/`DetectionPolicy` exists in code
  yet (repo-wide grep: zero hits).

### Finding 6 — in pull mode we already pay for always-on CV and throw the result away

With `vision.cv.frame-transport=pull`, the Python worker dials mediamtx itself and infers
continuously; nothing tells it to stop. The JVM then **discards** every result while the gate is
closed (`StreamPipeline.java:1347`). `contexts/vision-perception/MODULE.md:286` states it plainly.
That is the cost of always-on with none of the benefit.

### Finding 7 — the honest ceiling: CV does not scale to "many streams"

There is **no platform-wide inference budget**. `maxInFlightInferences` defaults to **2** *per
stream* and has no property key at all. Nothing counts total concurrent inferences across streams;
`vision.cv.inference.targets` is gRPC `pick_first` **failover**, not load balancing
(`CV-SCALE-PLAN` §S4/S5 unbuilt).

Measured (`docs/conclusions/CV-RATE-BUDGET.md:112`): p50 round trip **53.8 ms** on `yolo26n`,
achieved **7.58 fps** against a configured 10. One cv-service process therefore saturates at roughly
**3–4 streams at 10 fps** — and ~0.3 streams for `orion12l` at ~343 ms/frame.

**So "CV always on for every stream" is not free, and any plan that implies it is lying.** Aligning
CV to an always-on flow requires a *budget and a scheduler* — many streams at a low, fair rate —
not the current per-stream 10 fps applied N times.

## The shape of the answer

Today three concerns are collapsed into one lifecycle. Separating them is the whole plan:

| Plane | What it is | Should be governed by | Is governed by today |
|---|---|---|---|
| **Ingest** | telemetry claim, video into mediamtx, CV inference | the asset's own policy — "is this thing supposed to be running?" | whether a browser is open |
| **State** | last-known position/phase/battery/link/detection verdict, + history | always available, cheap to read | only exists while ingest runs |
| **View** | decoded live video for the 1–N streams someone is actually watching | genuine viewer demand | — (correctly demand-driven already) |

**Demand should govern the View plane only.** It currently governs all three. The inversion is the
architecture change; everything else is consequence.

## Grounding sources

- `docs/plans/active/E2E-FLOW-AUDIT-2026-09-05.md` — the audit this continues (proposals S1/U1/N2
  shipped 2026-09-06 in the same cycle).
- `contexts/vision-perception/MODULE.md` (451 lines) — the pipeline/usage/telemetry truth.
- `docs/plans/active/CV-SCALE-PLAN.md` §S2 — the `on-view | always` vocabulary, already specified.
- `docs/plans/done/CV-DEMAND-PLAN.md` §5/§7 — why demand gating exists, and its known consequences.
- `docs/conclusions/CV-RATE-BUDGET.md` — the measured inference ceiling.

## Not in scope

Writing product code from this document. It produces a plan; each wave becomes its own branch under
the delegation model.
