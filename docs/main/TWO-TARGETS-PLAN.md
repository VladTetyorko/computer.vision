# TWO-TARGETS-PLAN — hardware to buy, software to build

Status: **execution plan (2026-08-11).** The strategy docs answer *what* and *why*
(docs/main/MASTER-MATRIX.md and its companions). This one answers **what do I do on Monday**, split into
the only two things that actually block: hardware you don't have, and code that isn't written.

**The rule for both tracks: every step ends in something you can point at and show a person.**
No step is "done" because a test passed. It is done when it does something visible.

---

## 0. What you already have (don't buy it twice)

| Have | What it covers |
|---|---|
| **GB4005 inference box** (Intel Gemini Lake, 2 cores, 7.6 GB, UHD 600, no CUDA) | cv-service host. Modest, but real, and already deployed to by rsync |
| **`infra/sitl/` ArduPilot SITL farm** | N real ArduPilot aircraft, genuine PreArm/EKF/failsafe/mode behavior, no hardware |
| **`adapter-simulation`** | synthetic video + telemetry, seeded, deterministic |
| **mediamtx stack** | RTSP push, HLS, WHEP, recording, playback |

**Consequence: the entire software track below needs zero new hardware.** The hardware track exists
to make the product *real to other people*, not to unblock development.

---

## TARGET 1 — HARDWARE

Four tiers. Each one buys a specific capability the previous tier cannot fake. **Stop after any
tier and the platform still works** — nothing here is a prerequisite for the software track.

### H1 — Eyes (buy now) · ~€35–70

The cheapest way to turn the platform from "simulation" into "watching the actual world".

| Item | ~Price | Why this one |
|---|---|---|
| USB webcam, 1080p, UVC | €20–40 | `adapter-v4l2` already ingests it. Zero integration work |
| ESP32-CAM board (+ USB-TTL programmer) | €6–12 | `adapter-mjpeg` already ingests it. **This is the "any device" story made physical** — a €6 camera as a first-class asset |
| Cheap tripod / clamp mount | €10–20 | You need a *fixed, known* camera pose for S2 below. This is not optional — it is the measurement reference |

**What it unlocks:** real CV on real scenes, real tracking (S1), and the fixed-camera geolocation
demo (S2). Two different protocols ingesting simultaneously proves the multi-protocol claim with
hardware instead of a diagram.

**What it cannot do:** motion, altitude, GPS, MAVLink.

### H2 — A vehicle that actually moves · ~€150–250 · **the highest-value purchase in this document**

**An ArduPilot rover, not an aircraft.** This is the recommendation that matters most here.

| Item | ~Price |
|---|---|
| RC car chassis (1/10 or 1/16, brushed is fine) | €60–110 |
| Flight controller with ArduPilot support (F405/H743 class) | €30–60 |
| GPS/compass module (M8N/M10) | €15–35 |
| ELRS RX + transmitter *(if you own no radio yet, add €60–120 for a basic TX)* | €15–25 |
| ESP32 telemetry bridge (or ELRS backpack — free if the TX has one) | €5 |

**Why a rover beats a drone as the first real vehicle:**

- **The MAVLink is identical.** Same HEARTBEAT, same GPS_RAW_INT, same SYS_STATUS, same
  COMMAND_LONG. The code already handles it — `FlightModes` ships an ArduPilot **rover** mode table
  (Manual/Hold/Auto/RTL/Guided) alongside copter and plane
- **It exercises nearly the whole stack**: fleet gateway, heartbeat discovery, onboarding wizard,
  telemetry decode, geofence breach, failsafe banners, command TX (RTL/mode/arm), RC relay,
  the vehicle passport — all of it, for real
- **It cannot fall out of the sky.** No licensing, no airspace, no crash cost, no weather window.
  You can leave it running in a yard for an afternoon and generate hours of genuine flight-shaped
  data
- **It is the honest test of the "any drone" claim** — if a hand-built rover onboards in four
  clicks, the claim is true

**What it cannot do:** altitude, AGL, wind, anything where the third dimension is the point.

### H3 — An aircraft · ~€300–450 · when the ground story is solid

A 5" FPV quad or small fixed-wing with GPS + ArduPilot/INAV + video downlink. Buy this **after**
the rover has proven the stack end to end, because everything it adds (altitude, wind, AGL,
airspace) sits on top of what the rover already validated. Buying it first means debugging software
and flying an aircraft at the same time — two hard things at once.

### H4 — Companion computer · ~€60–120 · only after H3

Pi Zero 2 W / OrangePi + LTE modem. Unlocks Class C/D: one link carrying both video and telemetry,
adaptive bitrate, the commanded tier. **Meaningless without H3** — this was the ordering mistake
made earlier in planning and it is recorded here so it isn't repeated.

**Onboard cv-service profile: see [docs/plans/done/TRACKING-PLAN.md](../plans/done/TRACKING-PLAN.md) §3.3** — the same
package/contract/registries run on ARM64; only a thin onboard frame-feeder is missing, and its
interface is already frozen. That is what turns this tier from MB/s of video into KB/s of tracks.

### Buy order, plainly

1. **H1 now** (~€50) — unblocks the demo you can show this month
2. **H2 next** (~€200) — the real-vehicle validation, safely
3. H3, H4 — later, in that order, when there is a reason

---

## TARGET 2 — SOFTWARE

Three steps. Each is demoable, and each is a prerequisite for the next.

### S1 — Tracking engine · ~4 weeks · **the one real hole in the core**

**→ Authoritative spec: [docs/plans/done/TRACKING-PLAN.md](../plans/done/TRACKING-PLAN.md)** — frozen proto contract, the
detect-then-track duty cycle (tracker every frame, detector duty-cycled down to a verify pass), a
pluggable `TrackerRegistry`, and waves T0–T8.

`TrackedObject` exists in `vision-domain/model` and its own test and **nowhere else in the tree.**
No tracker, no ID association, no occlusion handling. Everything about "that object, over time" is
blocked on this.

**Build:**
- Tracker in cv-service (ByteTrack-class association over existing detections — IoU + motion
  prediction; no new model, no GPU requirement, works on the Intel box)
- `trackId` through the proto, `Detection`, and the domain — `TrackedObject` finally becomes live
- Track lifecycle: born → confirmed → lost → expired, with an age/hit threshold so flicker doesn't
  spawn IDs
- Persist tracks alongside detections; expose them on the existing SSE detection topic

**Touchable outcome:** point a camera at a street. Each car keeps **the same box number** as it
crosses the frame, and keeps it through a brief occlusion behind a pole. That is the difference
between "detections flicker" and "the system is watching something".

**Unblocks:** C12 cross-sensor fusion, C2 click-to-follow, target trajectories, C8 — all currently
listed in the matrix as if buildable, none of which are without this.

### S2 — Fixed-camera geolocation · ~1.5 weeks · **the demo**

The insight that makes this cheap: **if the camera's position and orientation are known, turning a
pixel into a map coordinate is already-solved math you have shipped.** `GeoProjection` +
`BearingDistance` do it today for drone frames. A tripod-mounted camera is just a drone that isn't
moving — and its pose is *more* accurate, because you measured it instead of estimating it.

**This needs no aircraft and no VPR.** It is completely independent of the parked geo branch.

**Build:**
- Camera pose as asset attributes: lat, lon, height AGL, yaw, pitch, horizontal FOV
- A one-time calibration flow: click 2–3 landmarks in the frame, click the same points on the map,
  solve for yaw/pitch/FOV (this is also C11's foundation, arriving early and cheap)
- Project each *tracked* object's ground-contact point (box bottom-centre) through the pose to a
  ground coordinate — flat-ground assumption first, DEM later when row X lands
- Publish as marks on the existing COP with the track ID, so a tracked object leaves a **trail**

**Touchable outcome:** a camera on a windowsill, and cars moving along the actual street **on your
map**, each with a stable ID and a trail behind it. Nobody needs an explanation for what they're
looking at. This is the first thing in the project that is genuinely showable to a stranger.

**Then, immediately after — the moat demo, nearly free:** point the USB camera *and* the ESP32-CAM
at the same street from different angles. Same car, two sensors, **one track on the map.** That is
C12 cross-sensor fusion with €50 of hardware, and it is a thing no vendor platform can do at all,
because it requires sensors that aren't theirs.

### S3 — Core cleanup · ~2 weeks · rides along

- `StreamPipeline` decomposition (927 lines, largest class in `vision-application`; carries
  sampling, in-flight bounding, overlay, fan-out, telemetry and usage tracking at once)
- Dead-type / dead-port audit — S1 exists because `TrackedObject` was found dead by grep. Do that
  systematically: anything referenced only by its own test is either a hole or removable
- `DefaultSimulationService` (759 lines) is next after `StreamPipeline`

**Touchable outcome:** none, honestly — and that is why it rides along rather than leading.

---

## The two tracks, together

| Week | Hardware | Software | What you can show |
|---|---|---|---|
| 1 | Order H1 (~€50) | S1 starts — tracker in cv-service | — |
| 2–4 | H1 arrives, mount it, ingest both cameras | S1 — trackId through proto → domain → SSE | Stable IDs on live video |
| 5 | Order H2 (~€200) | S2 — pose attributes + calibration flow | Camera pose calibrated against the map |
| 6 | H2 arrives, build the rover | S2 — projection + marks + trails | **Cars on your map, with trails** |
| 7 | Rover onboarding through the real wizard | S3 cleanup | **A vehicle you built, on the map, live** |
| 8 | Rover: geofence, RTL command, passport | S3 | The "any drone" claim, demonstrated |

**Total hardware: ~€250.** Total software: ~7 weeks, no new dependencies, nothing gated.

## What stays parked, deliberately

- **`feat/visual-geo`** — the engine does not deliver (correct tile top-1 in 0 of 12 on the most
  recent eval). Not merged. Harvest `adapter-tiles` alone if B6 offline maps needs it
- **Everything else in the matrix** — Groups B, C, M, T. The freeze holds until S1–S3 are done
- **H3/H4** — until the rover has proven the stack

## Why this order is right

The three earlier planning rounds all made the same mistake in different clothes: they optimized
for *breadth of capability* when the actual constraint is **nothing in this project has yet been
pointed at the real world.** Simulation and SITL are excellent and they have carried the build a
long way — but no stranger has ever looked at this system and understood it in five seconds.

S1 + S2 + €50 of hardware produces that moment. The rover then proves the claim the whole product
rests on. Everything else can wait, and most of it should.
