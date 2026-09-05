# CAMERA-FIRST — the camera is the base case, the drone is the add-on

Status: **proposed 2026-09-01, DECLINED by the owner the same day as too big a rework; nothing built
and nothing scheduled.** Kept as reference, not as a queue: four tracked docs cite it by path
(`docs/plans/README.md` row CAM, `CREW-CONTROL-PLAN.md` §Crew-without-a-pilot, `SOURCE-ONBOARDING-2-PLAN.md`
N1/U9, `asset-flows/R3-existing-proposals.md` §3), and the decline is recorded in
[`asset-flows/R3-existing-proposals.md`](asset-flows/R3-existing-proposals.md) §3 and
[`asset-flows/O1-SYNTHESIS.md`](asset-flows/O1-SYNTHESIS.md). Two defects were **salvaged** out of it
before the decline and live on elsewhere: mediamtx's open ingest/playback (§C2 → ASSET-FLOWS **S6**,
shipped 2026-09-01) and ONVIF credentials (§C5 → SOURCE-ONBOARDING-2 **N1**, still open).
Had it been built: branch `feat/camera-first`, sub-branch per wave.
Horizon: **to a sellable unit (~3.5–4 months)**. Hardware (SerialLink transport, mast head, receiver BOM,
unit identity/claiming) is deliberately **out of scope here** — it belongs to a companion
`docs/main/UNIT-SPEC.md` that this plan does not depend on and does not block.

**Ask (verbatim, translated):** *"how hard is it to turn our system from drone-oriented into
camera-oriented with drone control as an addition"* … *"we have 2 main features valuable on their
own: retraining of cameras and work with the drone"* … *"how hard is it to make a system for any
surveillance cameras with computer vision, recognition, and memory of objects and their movements"*.

**Grounding:** a read-only sweep on 2026-08-30/09-01 over every context `MODULE.md`, the
`vision-web` feature tree, `cv-service`'s tracking and geo packages, and the wiring layer in
`vision-app`. Every claim in §1 carries a file reference. Companions:
[`MOAT.md`](../../conclusions/MOAT.md) (why), [`PLATFORM-AUDIT-ANALOGS.md`](PLATFORM-AUDIT-ANALOGS.md)
(what the field ships that we do not — moves 1, 3, 4, 7, 8, 9 land here),
[`FIXED-CAMERA-GEO-PLAN`](../done/FIXED-CAMERA-GEO-PLAN.md) (the geolocation half, already built and
shipping **off**), and — **the one this plan sits directly on top of** —
[`ZERO-CONFIG-ONBOARDING-CONTEXT`](ZERO-CONFIG-ONBOARDING-CONTEXT.md), whose Z1–Z5 merged to `master`
on **2026-09-01** (`7356275a`) and closed most of what would otherwise have been this plan's largest
wave. §1.2/3 records exactly what it left.

---

## 0. The one-sentence diagnosis

**The system is already camera-oriented in its core and drone-oriented only in its vocabulary, its
navigation, and one leaked session rule** — `perception` (11.1k lines of ingest + CV + tracking),
`learning` (2.6k lines of the retraining loop) and `map`'s fixed-camera projection know nothing about
flight; `flight` (7.3k) and the MAVLink adapters (10.4k) sit beside them, not underneath. What is
missing for a camera product is not architecture: it is the boring VMS layer (rules, acknowledge,
retention, an event that carries its own picture), zero-configuration onboarding, and the half of
"object memory" that was written for visual geolocation and has never been pointed at a track.

---

## 1. Findings — what is already true

### 1.1 The core is camera-neutral, verified

| Fact | Where |
|---|---|
| An asset is categorized by **data**, not by an enum; a category may legally have zero devices | `warehouse/MODULE.md` §`DeviceCategory.connected`, `Asset` compact ctor |
| The CV/tracking runtime touches `flight` in exactly **one** class | `perception/MODULE.md` — `UsageTracker` opens `TelemetrySourcePort` and runs flight's pure `FlightPhaseRule` |
| The retraining loop has **no** flight dependency at all | `learning/MODULE.md` "Depends on": kernel, platform, warehouse, perception, events |
| Fixed-camera geolocation is **built**: pose CRUD, calibration solver, `ProjectedTrack`, durable trails, `errorRadiusMeters` always populated | `map/MODULE.md` §`application.track` |
| …and ships **off** by default | `FixedCameraGeoWiringConfiguration.java:95` — `@ConditionalOnProperty(vision.geo.fixed-camera.enabled=true)` |
| The pipeline's pose input is already **optional and nullable** | `StreamPipeline.java:392` "no ego-motion telemetry input when absent"; `:715` `CameraAttitude.from(telemetry, hfov)` |
| A vector index over 512-D descriptors exists, persists, and searches tens of thousands of rows in single-digit ms | `cv_service/geo/index.py` — `ReferenceIndex`, numpy cosine, deliberately no FAISS |
| The tracker already has an appearance seam and a cosine metric | `cv_service/tracking/engines/base.py` — `AppearanceExtractor` Protocol, `METRIC_COSINE`; `levels.py:49` L4 = "optional OpenVINO re-ID" |

### 1.2 The five things that actually break for a camera

1. **A camera's session dies while the camera is still streaming.** `DefaultUsageIdleCloseService.java:102`
   reads last activity **only** from `AssetLiveStatePort#latestTelemetry`. A camera has no telemetry, so
   last-activity clamps to `startedAt` and the usage is closed after `idleThreshold` (10 min) mid-stream.
   Every downstream artifact — replay, evidence package, statistics — inherits the lie.
2. **A camera's session has no honest phase.** `UsagePhase` is `PREFLIGHT/IN_FLIGHT/LINK_LOST/POSTFLIGHT/
   ABANDONED/CLOSED`, advanced only by telemetry samples. A camera opens in `PREFLIGHT` and stays there.
3. **Onboarding is *mostly* solved as of yesterday — and the remainder is precisely the authenticated
   half.** ZERO-CONFIG's Z1–Z5 landed the standing MAVLink lobby, a persisted **Discovery Inbox**
   (`DiscoveryCandidate`, `CandidateStatus`, `V31__discovery_inbox.sql`, SSE, one-click
   `registerFromCandidate`), the mediamtx push registry (path = identity, `MediamtxPathScanner`), Improv
   Wi-Fi provisioning, and a real ONVIF media chain — `OnvifDeviceClient` does
   `GetCapabilities → GetProfiles → GetStreamUri`. What it explicitly does **not** do is the half that
   most real cameras require: *"no credentials, no WS-Security header — every request is anonymous by
   construction"* (`OnvifDeviceClient.java:30`). The client already models the outcome that means
   **"try again with credentials"** and there is nothing on the other side of it: no credential store,
   no retry, no vendor-path fallback. And `MdnsScanner.java:275` still writes the ESP32-CAM's real
   stream path into `details["note"]` **for a human to read** instead of applying it.
4. **An alert has no picture.** `DetectionEvent` carries `label`/`peakConfidence`/`firstSeen`/`lastSeen`/
   `state`/`position?` — no frame reference, no stored box, no image id (documented at length in
   `features/alerts/alert-detail-panel.ts`). In the surveillance market an alert without an image is
   not an alert.
5. **The media plane ships wide open.** `mediamtx.yml:45` — `user: any`, empty password, `ips: []`,
   permissions `publish/read/playback/api/metrics/pprof`. Deliberate, and explained in the file's own
   header (docker networking), but a unit cannot ship this.

### 1.3 The commercial fact that decides the ordering

The surveillance market **already has a unit of sale and a price for it**: the channel. Milestone,
Genetec and Avigilon all license per camera. A camera product inherits a countable, comparable,
tender-legible unit for free; a drone product would have to invent one. That is the strongest argument
for camera-first, and it is independent of the code.

The floor of that market is **zero** (Frigate). Our price above zero is not paid for by detection —
it is paid for by *geolocated tracks on a shared map* and *a model that improves on the customer's own
data*. Both already exist here and neither exists there.

---

## 2. Decisions this plan freezes

| # | Decision | Consequence |
|---|---|---|
| **D1** | **Pose is one concept, not two.** A fixed camera is a stationary vehicle: its stored `CameraPose` is fed into the pipeline's existing `Supplier<Telemetry>` seam as a constant sample | no change in `perception`; wiring only, in `vision-app`, which legally sees both contexts |
| **D2** | **`camera` and `full` are Spring profiles of one binary**, never a fork | "add drone control" is a flag on an already-installed unit, not a new delivery |
| **D3** | **`UsagePhase` gains `OBSERVING`** — warehouse owns the enum and stays the pure leaf; perception sets it for a stream-origin usage with no telemetry source | no camera is ever labelled `PREFLIGHT`, and no flight dependency is added to warehouse |
| **D4** | **Liveness is `max(latest telemetry, latest frame)`** | one honest rule for both worlds; fixes 1.2/1 without special-casing cameras |
| **D5** | **Zones are ground polygons, not image rectangles** | reuses the map and the geo projection we already have; it is also the thing no image-space VMS can copy |
| **D6** | **Every event stores its own frame and box** | an alert is evidence, not a row; also feeds the label queue for free |
| **D7** | **Object memory ships in two halves**: single-camera re-acquisition first, cross-camera identity behind gate **G1** | the risky 6–10 weeks never blocks the sellable unit |
| **D8** | **Vehicles and objects first; person/face biometrics is a separate, gated decision** | GDPR/AI-Act exposure is opted into deliberately, never inherited by default |
| **D9** | **The unit ships closed** — credentialed media plane, machine tokens, published OpenAPI | prerequisite for any delivery, not a later hardening pass |

**Non-negotiable constraint on every wave:** the `full` profile stays green. This plan adds a second
posture; it does not remove the drone one. Any wave that reds a `contexts/vision-flight` or
`drone-link/**` test has failed its own exit criterion.

---

## 3. Waves

Effort classes as elsewhere in this repo: **S ≤ 16 h · M ≤ 80 h · L ≤ 240 h.**
"Parallel with" means the file scopes are disjoint and two agents may hold both at once.

### Phase 1 — an honest camera (~4 weeks)

| # | Wave | Files (disjoint scope) | Effort | Exit criterion | Parallel with |
|---|---|---|---|---|---|
| **C1** | **Camera profile.** `vision.mavlink.enabled`; make `MavlinkTelemetrySource`/commander/manual-control beans conditional; `camera`/`full` profiles with per-profile defaults (`vision.geo.fixed-camera.enabled=true` under `camera`) | `station/vision-app/config/wiring/TelemetryWiring.java`, `config/properties/VisionMavlinkProperties.java`, `application.yaml`, profile YAMLs | **S** | `--spring.profiles.active=camera` boots with **no UDP socket bound and no MAVLink bean**; an RTSP camera streams end-to-end; `full` profile tests unchanged | C2, C3 |
| **C2** | **Close the box.** Real mediamtx credentials (separate publish / read / api users, vision-app carries its own), machine API tokens, springdoc OpenAPI published | `mediamtx.yml`, `docker-compose.yml`, `video-output/publish-hls`, `contexts/vision-identity` (tokens), `station/vision-api` (springdoc) | **M** | anonymous RTSP/HLS/playback/control-API access is refused; `GET /v3/api-docs` serves a complete contract; an integrator can call us with a token and no session | C1, C3 |
| **C3** | **One pose source (D1).** A `CameraPose`-backed `Supplier<Telemetry>` wired per fixed-camera asset; fixed-camera geo on by default in the `camera` profile | `station/vision-app/config/wiring/FixedCameraGeoWiringConfiguration.java` + one new supplier class there | **S–M** | a camera with a saved pose produces a `ProjectedTrack` with an error radius on the map, from a real clip, with a screenshot in the context ledger | C1, C2 |
| **C4** | **A session a camera can have (D3, D4).** `UsagePhase.OBSERVING`; `AssetLiveStatePort` gains a frame-activity fact; `DefaultUsageIdleCloseService` uses `max(telemetry, frame)`; perception stamps `OBSERVING`; UI labels per category | `contexts/vision-warehouse` (`UsagePhase`, `AssetLiveStatePort`, `DefaultUsageIdleCloseService`), `contexts/vision-perception` (`StreamBackedAssetLiveState`, `UsageTracker`), `vision-web` labels | **M** | a camera streaming for 2 h holds **one** open session; its replay window covers the whole 2 h; no camera ever displays `PREFLIGHT` | — (touches warehouse+perception) |
| **C5** | **Onboarding: the authenticated half.** WS-UsernameToken on `OnvifDeviceClient`'s existing chain; a per-camera credential store + a default try-list, driven by the "try again with credentials" outcome the client already returns; vendor stream-path table with probe fallback for non-ONVIF cameras; **apply** the mDNS hints the scanner already computes (`MdnsScanner.java:275`) instead of writing them into a note | `device-discovery/onvif-mdns-v4l2` (`OnvifDeviceClient`, `MdnsScanner`), `contexts/vision-warehouse` (credential holder on the candidate/device), `storage/persistence` (+ Flyway), `vision-web/features/onboarding` | **M** | on a LAN with **two different real password-protected cameras**, both reach the inbox with a playable stream and **nothing is typed but the password**; measured, not asserted | C4 (different modules) |

**Phase 1 exit:** a camera-only station that discovers its cameras, streams them, detects, tracks,
puts tracks on the map with an honest error radius, keeps truthful sessions, and cannot be read by a
stranger.

### Phase 2 — the VMS layer (~5 weeks)

| # | Wave | Files | Effort | Exit criterion | Parallel with |
|---|---|---|---|---|---|
| **C6** | **Rules + acknowledge (D5, D6).** Rule = label × ground-zone × dwell × confidence → action; ack state on events; **persist the event's frame and box** | `contexts/vision-perception` (rules service, `DetectionEventEngine`), `contexts/vision-map` (ground zones), `storage/persistence` (+ Flyway), `station/vision-api`, `vision-web/features/alerts` | **L** | "person in zone A for > 5 s at conf > 0.6" fires once, carries its frame, and can be acknowledged; a duplicate does not re-fire | C7, C8 |
| **C7** | **Retention + disk budget.** Per-asset and per-event-class retention, disk budget, **shortfall raises an event** rather than silently deleting | `video-output/publish-hls` (mediamtx control API is already wired on :9997), `contexts/vision-events`, `vision-web/features/settings` | **M** | configured retention actually deletes on schedule; filling the disk produces a visible event, not a gap | C6, C8 |
| **C8** | **Event egress.** Webhook + MQTT v5 publishers behind the existing `EventPublisherPort` | new `interop/webhook`, `interop/mqtt`, `station/vision-app` wiring | **S–M** | an alert reaches an external broker and an external URL, with delivery failures visible | C6, C7 |

**Phase 2 exit:** the unit is operable by someone who is not us — noisy alerts can be tuned and
acknowledged, storage has a policy, and other systems can consume what we see.

### Phase 3 — object memory, one camera (~5 weeks)

| # | Wave | Files | Effort | Exit criterion | Parallel with |
|---|---|---|---|---|---|
| **C9** | **Appearance engine + gallery (D7, D8).** An embedding extractor behind the existing `AppearanceExtractor` Protocol (OpenVINO re-ID, L4 tier); per-track embedding + crop + time window persisted; re-acquisition after occlusion or exit/re-entry **on the same camera** | `cv/cv-service/cv_service/tracking/**`, new gallery module, `proto/vision/v1/cv.proto`, `cv/grpc`, `storage/persistence` (+ Flyway) | **L** | on a recorded clip, an object that leaves frame and returns after N minutes gets its **original** track id back, with a measured number (recall @ N minutes) — not a claim | C10 after its API lands |
| **C10** | **Forensic search.** "Where else did this object appear" over the single-camera gallery; timeline + thumbnails; hand-off into the evidence package | `station/vision-api`, `vision-web` (new `features/search`) | **M** | click a track → every appearance with times → jump to that moment in replay → export | — |

**Phase 3 exit — and the plan's exit:** a post of 1–4 cameras, with no drone anywhere near it, that a
person can buy: it finds its own cameras, sees, tracks, geolocates, remembers, alerts with an
acknowledged state and a picture, retains video by policy, exports evidence, speaks webhook/MQTT, and
gets better on the customer's own corrections.

---

## 4. Gates — decisions that must be taken before, not during

| Gate | Question | Blocks | Why it is a gate, not a wave |
|---|---|---|---|
| **G1** | Cross-camera identity: gallery across cameras, camera-adjacency topology, vector search at scale | a separate plan | 6–10 weeks and **all** of the technical risk; accuracy degrades hard across viewpoint/lighting; must be sold as "0.72 similar, three candidates, confirm" — and that confirmation is the same gesture that already mints a training label |
| **G2** | Person / face biometrics | any face feature | GDPR + AI Act put this in a different legal category. Vehicles and objects carry none of it |
| **G3** | ONVIF **PTZ control** and slew-to-cue | a later wave | needs C5's ONVIF client first, and it shares a pointing port with a future antenna tracker — design the port once, in `UNIT-SPEC`'s company, not ad hoc |

---

## 5. What this plan deliberately does not do

- **No hardware.** SerialLink/`SerialScanner`, mast head, receiver, unit identity and claiming all live
  in `UNIT-SPEC`. Nothing above waits on them.
- **No cross-camera re-ID** (G1), **no biometrics** (G2), **no PTZ** (G3).
- **No model-export factory** (per-target NPU export + post-quantization re-measurement). It is a good
  idea and a real differentiator, but it serves the drone buyer, not the camera unit.
- **No rewrite of the drone side.** `flight` and the MAVLink adapters are untouched; they become a
  profile, not a legacy.
- **No new dashboards framework.** ThingsBoard-style user-built dashboards are worth stealing later;
  they are not what makes the first unit sellable.

---

## 6. Open questions for the owner

1. **C5's credential model.** Per-camera secrets have to live somewhere. Postgres column encrypted with
   an app key is the cheap answer; a real secret store is the correct one. Which, on a box with no
   internet?
2. **C6's zone authoring.** Ground polygons (D5) are drawn on the map — but a camera whose pose is not
   yet calibrated has no ground. Fall back to image-space for uncalibrated cameras, or refuse to author
   a zone until the pose exists? Refusing is more honest and more annoying.
3. **C9's storage budget.** Embeddings + crops per track are not free. Cap per camera per day, or let
   retention (C7) own it?
4. **Pricing unit.** Per channel is the market's answer (§1.3). Does the drone side then bill per
   airframe, or is a drone simply "a channel that moves"?
5. **C5's exit criterion needs hardware we do not own.** Two real password-protected IP cameras —
   tier **T1** of [`HARDWARE-BUYLIST.md`](../../main/HARDWARE-BUYLIST.md) (~€40–70). Without them C5
   ends in an assertion instead of a measurement, which is the one thing this repo does not accept.

---

## 7. For the record — what this plan changes about earlier decisions

- `MOAT.md` §2 ranks the four pillars for a drone buyer. This plan does not contradict it, but it
  reorders delivery: pillar 3 (one verified picture) and pillar 2 (CV that compounds) are reachable
  **without an aircraft**, and pillar 1's fixed-camera half is already built and switched off.
- `PLATFORM-AUDIT-ANALOGS.md` §3's moves 1, 3, 4, 7, 8 and 9 are absorbed here as C2, C5, C6, C7 and
  C8 rather than being scheduled as an interop cycle of their own.
- `ZERO-CONFIG-ONBOARDING-CONTEXT`'s Z1–Z5 are a **prerequisite already met**, not a parallel effort.
  This plan does not redo the inbox, the lobby, the mediamtx push registry or the anonymous ONVIF
  chain; C5 is the credentialed remainder only. Its open Z6 (firmware, outside this repo) belongs to
  `UNIT-SPEC`, not here.
- `docs/plans/README.md` §3's open rows **7** (OpenAPI + machine tokens), **8** (webhook + MQTT),
  **12** (alert rules + acknowledge, per-asset retention) are absorbed as C2, C8, C6 and C7. Row **9**
  (KML/GeoJSON/GPX) is *not* — it serves the defence lane, not the camera unit, and stays where it is.
- `BASE-COMPUTE-MATRIX.md` §4.1's "strongest cluster" (C3+C4+C6+C9 there — wind, energy, link, terrain)
  stays a drone-side cluster and is **not** on this plan's critical path. It should follow, not precede,
  the first sold unit.
