# BASE-COMPUTE-MATRIX — what the base does so the drone doesn't have to

Status: **investigation + decision document (2026-08-09)**. → **The consolidated row-level view of
every capability in this doc now lives in docs/main/MASTER-MATRIX.md; this doc keeps the reasoning.**
Companions: docs/conclusions/FEATURE-MATRIX.md
(effort/value by persona, 2026-07-25), docs/conclusions/ANY-DRONE-PLAN.md (the integration loop),
docs/conclusions/MOAT.md (why this is the product). This doc asks a narrower question than FEATURE-MATRIX:

> **Given a cheap, dumb aircraft, what can the ground do for it — and what must stay onboard?**

Every row carries a **drone-side effort** column, because that is the column that decides whether
"any drone can be integrated" is true or marketing.

---

## 1. The offload law (the physics, before the features)

You cannot move a control loop across a lossy 100–300 ms link. You *can* move everything above it.
This table is the boundary that governs every design decision below:

| Loop | Rate | Must live | Why |
|---|---|---|---|
| Rate / attitude stabilization | 400–1000 Hz | **FC only. Never offload.** | link jitter destabilizes the airframe; no exceptions |
| Position / velocity control | 5–20 Hz | FC executes, **base may command** | ArduPilot GUIDED accepts `SET_POSITION_TARGET_*` from a ground sender at several Hz, and stops the vehicle if commands lapse for 3 s — a built-in link failsafe |
| Guidance (where next, whether to come home) | 0.1–2 Hz | **base** | waypoints, mode changes, RTL decisions — latency-tolerant |
| Perception (what am I looking at) | 1–30 Hz | **base** | needs a GPU the aircraft doesn't carry |
| Deliberation (what does it mean, who else sees it) | seconds–minutes | **base** | inherently multi-asset and human-in-the-loop |

**Consequence:** the base can own everything above the position loop. Perception and deliberation
need *nothing* from the aircraft but a video downlink — that is the entire D0 story below.

### 1.1 The design principle that makes this cheap: advisory autonomy

For most of the value, **the base computes the smart thing and shows it to the pilot instead of
commanding the aircraft.** "Fly this line", "turn back now or you won't make it", "the link dies
past that ridge". Zero drone-side work, zero command-TX risk, no doctrine break — and it captures
most of the benefit of autonomy. Commanded autonomy is the optional upgrade for aircraft that have
earned it (bidirectional link, GUIDED support, operator consent). Nearly every punch feature in §4
has an advisory tier and a commanded tier; **ship the advisory tier first, always.**

### 1.2 Drone-side effort tiers (the column that matters)

| Tier | Means | Cost to the owner |
|---|---|---|
| **D0** | Nothing. Video and/or telemetry already arriving. | €0 |
| **D1** | FC configuration only — params or CLI, no hardware. | €0 + minutes (and docs/ANY-DRONE-PLAN automates it) |
| **D2** | Cheap hardware: ESP32/ELRS telemetry bridge, capture dongle. | €5–30 |
| **D3** | Companion computer + bidirectional link (RPi/OrangePi + LTE/WiFi). | €40–120 |
| **D4** | Real integration: gimbal, thermal, custom firmware, extra sensors. | €150+ |

**A feature at D0/D1 is available to every drone that already talks to us. A feature at D3 is
available to maybe 10% of them.** Rank accordingly.

---

## 2. Column A — what we already have

Verified against the tree at `master` on 2026-08-09 unless marked otherwise.

| Capability | Where | Drone-side |
|---|---|---|
| Multi-protocol video ingest — RTSP, MJPEG, V4L2/USB, file, sim | `adapters/adapter-{rtsp,mjpeg,v4l2,simulation}` | D0–D2 |
| MAVLink fleet gateway — N aircraft on one UDP port, demux by (addr, sysid), re-adoption after silence | `adapter-mavlink` (DRONE-INFRA I-a) | D1 |
| Heartbeat discovery + guided onboarding wizard with pre-filled per-firmware configs | I-b, I-g | D1 |
| FC-aware telemetry: firmware, mode, armed, failsafe, GPS fix/sats/HDOP, RSSI, arming blockers | FC-INTEGRATIONS F-a | D1 |
| ArduPilot extras: wind, vibration, EKF variances, rangefinder, mission seq | F-e | D1 |
| CV detection — YOLO, composite multi-model, open-vocabulary YOLOE, per-stream live control | `cv-service`, CV-CONTROL | D0 |
| Detection/telemetry overlay burn-in; HLS + WHEP publishing | `adapter-overlay`, `adapter-publish-hls` | D0 |
| Recording + clip export + replay with scrubbed timeline and event deep links | OPS-CORE R | D0 |
| Tactical COP — layers + visibility grants, affiliation, marks, verify/promote, drawings, scoped SSE | MAP-REWORK | D0 |
| **Target geolocation from a video frame** — click a thing, it gets coordinates | `GeoProjection`, `BearingDistance`, marks | D0 |
| Geofence zones (keep-in/keep-out/ceiling) + breach events | OPS-CORE G | D0 |
| Weather go/no-go chip (Open-Meteo, per-airframe wind limit) | OPS-CORE W | D0 |
| Preflight checklist, failsafe/RTH banners | FC-INTEGRATIONS F-d | D1 |
| **CV training loop** — capture → correct → export → fine-tune → promote live | CV-TRAINING | D0 |
| Command TX — RTL, mode select, arm/disarm, capability-gated + audited | DRONE-INFRA I-e Stage 1–2 | D1 |
| RC relay — physical transmitter → `RC_CHANNELS_OVERRIDE`, watchdog (SITL-verified) | RC-CONTROL Phase 1 | D3 |
| Users, roles, groups, scoped visibility, audit trail, assignments | U-AUTH / U-SCOPE | — |
| Fleet dashboard, Fly cockpit, Wall, Replay, Reports, Roster, Warehouse | `vision-web` | — |
| **Visual geolocation (VPR)** — position from imagery, measured pose, tile index | branch `feat/visual-geo`, **19 commits, not merged** | D0 |

**Honest gaps inside column A:** visual geo is unmerged and its retrieval accuracy is an open
problem (VISUAL-GEO §12); RC relay is SITL-only pending an explicit go on a real airframe.

---

## 3. Column B — what others have that we should copy

Ratios (`n/8`) are from FEATURE-MATRIX's own product survey. Effort: S ≤2 days · M ≤2 weeks ·
L ≤6 weeks · XL multi-cycle.

| # | Feature | Who has it | Effort | Depends on | Time | Drone-side | Verdict |
|---|---|---|---|---|---|---|---|
| B1 | **ADS-B / air-traffic overlay** | 3/8, growing | S-M | map layers (have) | ~1 wk | **D0** | **Copy.** Feed client + layer. Pure base-side. |
| B2 | **Battery health per airframe** (cycles, trend, swap alerts) | ~5/8 | M | AssetUsage (have) | ~2 wk | **D0** | **Copy** — and it feeds punch C3. |
| B3 | **Maintenance log / flight hours per component** | fleet-tool table stakes | M | usages (have) | ~2 wk | D0 | Copy when fleets are real. |
| B4 | **Pilot currency & flight-hours dashboard** | table stakes | M | U-e (have) | ~2 wk | — | Copy. |
| B5 | **Control / watch handoff between operators** | 4/8 | L | U-e, session ownership | ~4 wk | D0 | Copy — 24/7 ops need it. |
| B6 | **Offline map tiles / air-gapped basemap** | GCS table stakes | S-M | `adapter-tiles` (on geo branch) | ~1 wk | — | **Copy — high priority.** An offline product with an online-only map is a contradiction. |
| B7 | **Thermal / IR stream support** | FLIR, DJI, Skydio | M | ingest (have) | ~2 wk | **D4** | Copy the *ingest + fusion* side; the sensor is the owner's problem. |
| B8 | **Alert-rules editor** ("would have fired 47×", one-tap corrections) | ~2/8 | L | detections (have) | ~4 wk | D0 | Copy — trust/noise compounding, feeds the label queue. |
| B9 | **Mission / waypoint execution** | **8/8** | XL | command TX Stage 3 | multi-cycle | D1–D3 | **Do not copy yet.** QGC does it better and free; see MOAT §6. Copy the *planner*, not the executor — see C10. |
| B10 | **Docking / drone-in-a-box** | 3/8 | XL | — | — | D4 | No. Wrong persona. |
| B11 | **Cloud multi-site sync** | 5/8 | L | — | — | — | Later; conflicts with the offline-first positioning until deliberately reconciled. |

**Copy shortlist: B1, B2, B6 first** — all D0/base-side, all under two weeks each.

---

## 4. Column C — punch features (base-side compute, drone stays dumb)

The competitive gap this exploits, in one paragraph: the industry has split into **expensive smart
aircraft** (Skydio — onboard 3D mapping and tracking, priced accordingly) and **vendor clouds for
the vendor's own aircraft** (DJI's 2026 direction is explicitly cloud-side AI inference over
FlightHub streams — for DJI drones). Ground-ISR software exists (Teledyne FLIR's Prism Ground ISR,
June 2026) but targets fixed ground sensors on Orin boxes, not third-party aircraft integration.
**Nobody is making a cheap third-party aircraft smart from the ground.** That is the whole space
below.

| # | Punch feature | What it replaces onboard | Effort | Depends on | Time | Drone-side | Uniqueness |
|---|---|---|---|---|---|---|---|
| **C1** | **Video enhancement pipeline** — stabilization, dehaze, denoise, low-light, upscaling, on ingest | gimbal + good sensor + onboard ISP (€200+) | M | pipeline (have) | ~2 wk | **D0** | ★★★ Nobody ships this. Turns a €25 FPV cam into a usable ISR sensor. |
| **C2** | **Click-to-follow tracking** — operator clicks a target, base runs the tracker and closes the loop (gimbal cmds; GUIDED velocity for aircraft-follow) | Skydio-class onboard autonomy | L | CV (have), gimbal ctrl, GUIDED | ~4 wk | **D1** gimbal · **D3** aircraft-follow | ★★★ Advisory tier (crop-follow in the viewer) is D0 and nearly free. |
| **C3** | **Energy-aware return decisioning** — real wind-corrected range from *this* airframe's history; point-of-no-return; "turn back now" | nothing onboard does this — FC failsafe is a dumb battery-% threshold | M | telemetry+usages (have), B2, C4 | ~2 wk | **D0** | ★★★ Uses data only the base has (fleet history). Genuinely saves aircraft. |
| **C4** | **Wind field map from the fleet** — every aircraft's track-vs-heading is a wind sample; aggregate into a local 3D wind field | onboard estimators see only their own point | M | telemetry (have) | ~2 wk | **D0** | ★★★ Structurally impossible for a single aircraft. Beats any forecast locally. |
| **C5** | **Multi-aircraft deconfliction** — pairwise separation, predicted conflicts, altitude-band assignment | no FC can see other aircraft | M | fleet telemetry (have) | ~2 wk | **D0** advisory · D3 commanded | ★★★ Manned-aviation table stakes, absent from DIY drone ops entirely. |
| **C6** | **Link-loss prediction** — terrain + antenna position + aircraft position → shade the map where the link *will* drop, before flying there | impossible onboard (no terrain, no ground antenna model) | M | DEM (new), map (have) | ~2 wk | **D0** | ★★★ Nobody does this for hobby-grade links. Prevents the #1 cause of lost aircraft. |
| **C7a** | **GPS spoofing / jamming detection** — vision fix vs GPS disagreement → warn | onboard EKF can only detect gross inconsistency | M | visual geo (branch) | ~2 wk | **D0** | ★★★ The defining feature for a contested region. |
| **C7b** | **Vision position injection** — feed `VISION_POSITION_ESTIMATE`/`GPS_INPUT` so the aircraft *navigates* GPS-denied | €500+ onboard VIO rig | XL | C7a, low-latency link | multi-cycle | **D3** | ★★★ but **high risk** — EKF wants consistent, low-latency, timestamped input; marginal over LTE. Gate like I-e. |
| **C8** | **Base-side precision landing** — detect the pad in video, send `LANDING_TARGET` (`PLND_TYPE=1`) | IR-LOCK beacon or Landmark rig | M | CV (have), command TX | ~2 wk | **D1** + **D3** link | ★★ Real but latency-bound: the protocol wants 10–50 Hz. Needs an honest experiment before it's promised. |
| **C9** | **Terrain-aware AGL & proximity warning** — true AGL from DEM, not barometric nonsense | rangefinder + terrain database onboard | M | DEM (new) | ~2 wk | **D0** display · D3 control | ★★★ Gives a €30 FC terrain awareness it can never have. |
| **C10** | **Coverage / search planner with on-screen guidance** — compute the pattern from camera FOV + overlap; *draw the line for the pilot to fly* | mission executor + waypoint upload | M | FOV (C11), map (have) | ~2 wk | **D0** | ★★★ The advisory-autonomy trick: all of the planning value, none of the command-TX risk. Sidesteps B9 entirely. |
| **C11** | **Automatic camera calibration** — estimate intrinsics/FOV from video + telemetry over a flight | a calibration step no owner will do correctly | M | geo pipeline | ~2 wk | **D0** | ★★ Quiet enabler for C10, geolocation and C1. |
| **C12** | **Cross-sensor detection fusion** — same vehicle seen by two aircraft + a fixed camera = **one** geolocated track, deduped, confidence-weighted | impossible onboard by definition | L | CV + geo + COP (have) | ~4 wk | **D0** | ★★★ This is the COP's actual payoff and needs no aircraft change. |
| **C13** | **After-action evidence package** — flight + video + detections + marks + FC params, one export | — | S-M | recording, passport | ~1 wk | **D0** | ★★ Referee/investigator/insurer artifact. |
| **C14** | **Latency-compensated remote piloting** — predict aircraft state forward by measured RTT, render the predicted horizon | — | M | RC relay (have) | ~2 wk | **D0** | ★★★ Makes LTE piloting flyable instead of nauseating. |
| **C15** | **Adaptive bitrate command** — base measures the link and tells the companion/VTX what bitrate to send | onboard congestion control | S-M | companion (D3) | ~1 wk | **D3** | ★★ Small, real, easy once a companion exists. |
| **C16** | **Sector assignment / swarm-lite** — divide an area across N aircraft, monitor coverage, resequence | — | L | C5, C10 | ~4 wk | **D0** advisory | ★★★ Multi-aircraft is base-only ground. |
| **C17** | **Virtual companion computer** (the umbrella) — the base exposes the MAVLink surface a companion would: guided setpoints, landing target, vision position, obstacle distance | a €40–120 onboard computer | XL | C2/C7b/C8/C9 | multi-cycle | **D3** | ★★★ The headline framing for everything commanded. Ship the pieces, name the whole. |

### 4.1 The strongest cluster

**C3 + C4 + C6 + C9 form one coherent product: "the base knows the air and the ground better than
the aircraft does."** They share inputs (fleet telemetry, DEM, wind estimation), they are all
**D0**, all advisory, all ~2 weeks, none require command TX, and together they answer the pilot's
only real question — *can I fly there and get back?* No competitor at any price does this for
third-party aircraft. **If one thing gets built from this document, build this cluster.**

Its one new dependency is a **terrain elevation source (DEM)** — needed by C6, C9 and C3, and
reusable by visual geo. That is the single highest-leverage new dependency in the doc.

---

## 5. What must stay on the drone (so we stop wondering)

| Stays onboard | Why | Can the base help? |
|---|---|---|
| Rate/attitude stabilization | 400 Hz; link jitter kills it | No. Ever. |
| RC failsafe / geofence enforcement | must work when the link is gone — which is exactly when it matters | Base can *upload* the fence and *verify* it; enforcement stays onboard |
| Basic RTL execution | same reason | Base decides *when* (C3) and *by which route* (C9); FC flies it |
| Sensor fusion / EKF | needs 100+ Hz IMU that never leaves the aircraft | Base can inject position aiding (C7b), never replace the EKF |
| Motor/ESC protection, arming checks | safety-critical, deterministic | Report only. Tier C in ANY-DRONE-PLAN — **never written** |

**The honest rule to publish:** *if the link dies, the aircraft must still be safe on its own.*
Everything the base adds is capability, never a dependency. Any feature that violates this is
disqualified regardless of how impressive it demos.

---

## 6. Recommended sequence

| Order | What | Why here | Time |
|---|---|---|---|
| **1** | ANY-DRONE waves 1–2 (readiness report + `SET_MESSAGE_INTERVAL`) | the funnel in front of everything; zero risk | ~2 wk |
| **2** | **C3+C4+C6+C9 cluster** + the DEM dependency | the strongest differentiated block in the doc; all D0, all advisory | ~6 wk |
| **3** | B1, B2, B6 (ADS-B, battery health, offline tiles) | cheap table stakes; B2 feeds C3, B6 fixes an offline-positioning contradiction | ~3 wk |
| **4** | C1 (video enhancement) | makes every cheap camera useful — widest reach of any single feature | ~2 wk |
| **5** | Merge + fix `feat/visual-geo` retrieval, then C7a (spoofing detection) | the most valuable asset has the open gap | multi-cycle |
| **6** | C12 (cross-sensor fusion), C10 (coverage planner) | the COP's payoff; still D0 | ~6 wk |
| **later, gated** | C2 aircraft-follow, C7b, C8, C17 | all D3 + command TX; explicit go, SITL-first, per I-e doctrine | — |

**Sources:** [ArduPilot Copter Guided Mode](https://ardupilot.org/copter/docs/ac2_guidedmode.html) ·
[MAVLink Offboard Control](https://mavlink.io/en/services/offboard_control.html) ·
[MAVLink Landing Target Protocol](https://mavlink.io/en/services/landing_target.html) ·
[ArduPilot Precision Landing](https://ardupilot.org/copter/docs/precision-landing-and-loiter.html) ·
[DJI onboard/cloud AI direction 2026](https://enterprise-insights.dji.com/blog/drone-onboard-ai-challenge-2026) ·
[Skydio X10 autonomy](https://www.skydio.com/x10) ·
[Teledyne FLIR Prism Ground ISR](https://letsdatascience.com/news/teledyne-flir-launches-prism-ground-isr-platform-9d5846dd)
