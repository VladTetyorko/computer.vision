# MASTER-MATRIX — every capability, one table

Status: **consolidated decision document (2026-08-09)** · **reconciled 2026-08-11** — K2 delivered,
S2 promoted to the head of the list · **reconciled 2026-08-19** — the integration funnel (I1, I2,
I3, I7) shipped, so rows 3 and 3b are struck; I4/I5 and I9/I10 are now explicitly operator-gated
rather than merely unbuilt. **S2 is still the head of the list, and is now the only thing in front
of it.** This is the single row-level view. The reasoning behind it
lives in the companions and is not repeated here:

| Doc | Answers |
|---|---|
| docs/conclusions/MOAT.md | *why* — the four structural inversions of a vendor platform |
| docs/conclusions/ANY-DRONE-PLAN.md | *how any drone gets in* — PROBE→DIAGNOSE→REMEDIATE→VERIFY |
| docs/conclusions/BASE-COMPUTE-MATRIX.md | *what the base computes* — the offload law, advisory autonomy |
| docs/conclusions/DRONE-COMPONENTS-MATRIX.md | *what it costs* — position stack, link stack, components, Anubis |
| docs/conclusions/FEATURE-MATRIX.md | prior persona/effort survey (2026-07-25), still valid, narrower axes |

---

## 0. Legend — read this once, the whole table depends on it

**Status** · `HAVE` shipped on `master` · `BRANCH` built but unmerged · `COPY` competitors have it,
we should · `PUNCH` differentiated, base-side · `GATE` needs explicit go (risk/doctrine) ·
`NO` deliberately rejected

**Drone-side effort** — the column that decides whether "any drone" is true:

| | Means | Owner cost |
|---|---|---|
| **D0** | nothing — video and/or telemetry already arriving | €0 |
| **D1** | FC config only (params/CLI), no hardware | €0 |
| **D2** | cheap hardware (ESP32 bridge, capture dongle) | €5–30 |
| **D3** | companion computer + bidirectional IP link | €60–120 |
| **D4** | real integration (gimbal, thermal, custom firmware) | €150+ |

**Link class** — compute is cheap, the pipe is the constraint:

| | Needs | RTT |
|---|---|---|
| **A** | telemetry down ~2 kB/s, one-way tolerable | any |
| **B** | telemetry bidirectional, low rate | any |
| **C** | video down ≥ 1 Mbps | any |
| **D** | video + bidirectional, stable | **< 200 ms** ← where promises die |

**Position layers** — `P0` GNSS · `P1` RTK · `P2` CRPA · `P3` IMU DR · `P4` baro/mag ·
`P5` optical flow · `P6` visual odometry · `P7` visual place recognition · `P8` terrain matching ·
`P9` ground TDOA · `P10` LEO signals-of-opportunity · `P11` manual fix

**Dev effort** — our hours: **S ≤16 h · M ≤80 h · L ≤240 h · XL** multi-cycle

**The offload law** — rate/attitude loop (400–1000 Hz) never leaves the FC; position/velocity
(5–20 Hz) FC executes and base may command; guidance, perception and deliberation are always
base-side. **Advisory before commanded, always.**

**The command-transport rule** — added 2026-08-09 after reviewing an external distributed-C2 plan
that proposed routing all MAVLink through a cloud NATS broker. "No broker in the command path" is
too blunt; the correct line falls straight out of the link classes above:

| | Rate | May traverse a broker / relay? |
|---|---|---|
| **Class B** one-shot commands — RTL, mode, arm/disarm | 0.1–2 Hz, ack'd | **Yes**, provided a broker outage surfaces as a *visible refusal*. The FC's own failsafes are untouched, so the failure mode is lost capability, not an unsafe aircraft — precisely what `CommandResult.NO_ACK` already encodes |
| **Class D** closed loops — GUIDED setpoints, `RC_CHANNELS_OVERRIDE` (33 Hz), `LANDING_TARGET` (10–50 Hz) | 5–50 Hz | **No. Direct to FC, single hop.** Broker jitter degrades a loop the aircraft depends on *while engaged*, and adds a failure domain inside it |

**Stale-command guard — mandatory wherever any relay exists.** Brokers buffer and replay. A queued
`arm` or `RTL` delivered five minutes late is a genuine hazard. Every relayed command carries an
issue timestamp and is dropped at the sender once older than a few seconds: **a late command must
fail, never execute.** This applies to Class B too — it is the price of allowing the relay at all.

---

## 1. Top 20, ranked — if only this table is read

| # | Capability | Group | Status | Drone | Link | Owner € | Dev | Why here |
|---|---|---|---|---|---|---|---|---|
| ~~1~~ | ~~**Tracking engine** — persistent target IDs~~ | **K2** | **HAVE** | D0 | C | **€0** | ~~L 180 h~~ | **DELIVERED 2026-08-11**, waves T0–T8, default `ASSOCIATE`. `TrackedObject` is live; C12 and C2 are unblocked. Spec: **docs/plans/done/TRACKING-PLAN.md** |
| 2 | **Fixed-camera geolocation** — tracked object → map coordinate | **S2** | new | D0 | C | **€0** | M 60 h | the touchable demo, and it needs **no aircraft and no VPR** — known camera pose + `GeoProjection` |
| ~~3~~ | ~~Message inventory + readiness report~~ | I | **HAVE** | **D0** | A | **€0** | ~~M 70 h~~ | **DRONE-ONBOARDING O1-O8 + O11-O14, merged 2026-08-19**. Ships behind `vision.onboarding.probe.enabled=false` |
| ~~3b~~ | ~~`SET_MESSAGE_INTERVAL` auto-request~~ | I | **HAVE** | **D0** | B | **€0** | ~~S 12 h~~ | **DRONE-ONBOARDING O1-O8 + O11-O14, merged 2026-08-19** (O8), behind `vision.onboarding.remediate.message-interval.enabled=false` |
| 4 | DEM/terrain dependency | X | new | — | — | €0 | M 40 h | unlocks 4 features + visual geo at once |
| 5 | Energy-aware return decisioning | C3 | PUNCH | **D0** | A | **€0** | M 70 h | answers the pilot's only real question |
| 6 | Wind field from the fleet | C4 | PUNCH | **D0** | A | **€0** | M 60 h | structurally impossible for one aircraft |
| 7 | Link-loss prediction from terrain | C6 | PUNCH | **D0** | A | **€0** | M 70 h | prevents the #1 cause of lost aircraft |
| 8 | Terrain-aware true AGL | C9 | PUNCH | **D0** | A | **€0** | M 50 h | gives a €30 FC terrain awareness |
| 9 | `PositionFusion` N-source generalization | A1 | new | — | — | €0 | M 60 h | turns "GPS or nothing" into an honest stack |
| 10 | GPS spoof/jam detection | C7a | PUNCH | **D0** | C | **€0** | M 60 h | nearly free once #1 and #9 land |
| 11 | Mission tasking layer | M1 | PUNCH | **D0** | A | **€0** | M 80 h | the ISR lifecycle, zero hardware |
| 12 | Navigation pack export | M5 | PUNCH | **D0** | — | **€0** | M 80 h | makes us the *supplier* of nav data |
| 13 | Video enhancement pipeline | C1 | PUNCH | **D0** | C | **€0** | M 80 h | makes a €25 camera a usable sensor |
| 14 | Offline map tiles | B6 | COPY | — | — | €0 | M 40 h | offline product with an online map is a contradiction |
| 15 | Multi-aircraft deconfliction | C5 | PUNCH | **D0** | A | **€0** | M 60 h | no FC can see another aircraft |
| 16 | Battery health per airframe | B2 | COPY | **D0** | A | €0 | M 60 h | table stakes, and feeds #5 |
| 17 | ADS-B / traffic overlay | B1 | COPY | **D0** | — | €0 | S–M 40 h | pure base-side, one feed client |
| 18 | Cross-sensor detection fusion | C12 | PUNCH | **D0** | C | €0 | L 180 h | the COP's actual payoff |
| 19 | Coverage planner + on-screen guidance | M2 | PUNCH | **D0** | A | €0 | M 60 h | mission value without command TX |
| 20 | Tier-A param write + rollback | I4 | new | **D1** | B | €0 | M 60 h | makes the fleet gateway work for real owners |

**Row 1 is delivered (2026-08-11).** The tracking engine shipped as specced — pluggable
`TrackerRegistry`, detect-then-track duty cycle, tracks on the wire and on the COP. Measured on the
GB4005 box: `ASSOCIATE` costs less than run-to-run noise over `OFF`; `FOLLOW` drops detector passes
30× (15.07/s → 0.50/s) and CPU 22.9× (297 % → 13 % of one core). **What is not claimed:** the
camera-facing half of TRACKING-PLAN §10 — stable ids through a real occlusion on live street video,
and the cockpit follow-lock demo — because there is no camera yet (H1 unbought, ~€50). That is a
hardware errand, not a build. **Row 2 (S2) is now the head of the list.**

**Every row here is D0 or D1, at €0 to the drone owner.** That is not a coincidence — it is the
ranking criterion.

**No hardware is on the critical path** (corrected 2026-08-11). An earlier draft ranked the RPi
field node at #2 as "the prerequisite for measuring P7". It is not: **there is no aircraft**, and
P7 evaluation has been running on public AU-AIR frames against Wayback satellite tiles all along
(`cv-service/spikes/geo/` — `auair-manifest`, 17 MB tile cache, eval runs through 2026-08-09).
Retrieval accuracy is a software problem measurable today. T1 stays in Group T as the recipe to
build **when an aircraft exists**, not before.

---

## 2. Group A — HAVE (shipped on `master`)

| Capability | Drone | Link | Where |
|---|---|---|---|
| Multi-protocol video ingest (RTSP, MJPEG, V4L2, file, sim) | D0–D2 | C | `adapter-{rtsp,mjpeg,v4l2,simulation}` |
| MAVLink fleet gateway — N aircraft, one UDP port, re-adoption | D1 | A | DRONE-INFRA I-a |
| Heartbeat discovery + guided onboarding wizard | D1 | A | I-b, I-g |
| FC-aware telemetry (mode/armed/failsafe/GPS/RSSI/blockers) | D1 | A | FC-INTEGRATIONS F-a |
| ArduPilot extras (wind, vibration, EKF, rangefinder, mission seq) | D1 | A | F-e |
| CV detection — YOLO, composite, open-vocab YOLOE, live per-stream control | D0 | C | cv-service, CV-CONTROL |
| **Tracking engine — persistent ids, detect-then-track duty cycle, `OFF`/`ASSOCIATE`/`FOLLOW`, tracks on SSE + overlay + COP** | D0 | C | **TRACKING-PLAN T0–T8 (2026-08-11)** |
| Overlay burn-in; HLS + WHEP publishing | D0 | C | `adapter-overlay`, `adapter-publish-hls` |
| Recording + clip export + replay with deep links | D0 | C | OPS-CORE R |
| Tactical COP — layers + grants, affiliation, marks, verify/promote, drawings | D0 | — | MAP-REWORK |
| Target geolocation from a video frame | D0 | C | `GeoProjection`, `BearingDistance` |
| Geofence zones + breach events | D0 | A | OPS-CORE G |
| Weather go/no-go chip | — | — | OPS-CORE W |
| Preflight checklist + failsafe/RTH banners | D1 | A | F-d |
| CV training loop (capture→correct→train→promote) | D0 | C | CV-TRAINING |
| Command TX — RTL, mode select, arm/disarm, audited | D1 | B | I-e Stage 1–2 |
| RC relay — transmitter → `RC_CHANNELS_OVERRIDE` (SITL-only) | D3 | D | RC-CONTROL Ph1 |
| Users, roles, groups, scoped visibility, audit trail | — | — | U-AUTH / U-SCOPE |
| Fleet dashboard, Fly, Wall, Replay, Reports, Roster, Warehouse | — | — | `vision-web` |
| **Visual geolocation (VPR), measured pose, tile index** | D0 | C | **`feat/visual-geo` — BRANCH, 19 commits unmerged** |
| **Vehicle onboarding — message inventory, probed `VehicleProfile`, readiness report, message-interval remediation, flight passport + config drift** | D0/D1 | A | **DRONE-ONBOARDING O1–O8, O11–O14 (2026-08-19)** — behind `vision.onboarding.probe.enabled` / `.passport.enabled`, both default off |
| **Database change audit — `db_audit_log`, trigger-level, unbypassable** | — | — | **V21 (2026-08-19)** — distinct from `audit_entries`, which only holds declared intent |

---

## 3. Group I — integration layer (ANY-DRONE-PLAN)

| # | Capability | Status | Drone | Link | Owner € | Dev | Depends | Architecture alignment |
|---|---|---|---|---|---|---|---|---|
| I1 | Message inventory + `AUTOPILOT_VERSION` + targeted param read → `VehicleProfile` | **HAVE** (O1, O2, O4) | D0 | A | €0 | M 50 h | — | new `VehicleConfigPort`, read half only; rides `MavlinkSocketHub` ack seam |
| I2 | Readiness report (feature × requirement, "why unknown") | **HAVE** (O3, O5, O6) | D0 | A | €0 | M 40 h | I1 | pure evaluator + UI; wizard's final step |
| I3 | `SET_MESSAGE_INTERVAL` auto-request on connect | **HAVE** (O8) | D0 | B | €0 | S 12 h | I1 | same shape as `MavlinkFlightCommander`; **writes nothing** |
| I4 | Tier-A `PARAM_SET` + snapshot + one-click restore + audit | **GATE** (O9) | D1 | B | €0 | M 60 h | I1 | write half of `VehicleConfigPort`; allowlist in domain; `AuditTrailPort` |
| I5 | `SYSID_THISMAV` assignment in the wizard | **GATE** (O9 — and ArduPilot 4.7 renamed it `MAV_SYSID`) | D1 | B | €0 | S 16 h | I4 | makes multi-aircraft gateway actually work |
| I6 | BF/INAV CLI **diff** generation + [Verify] re-probe | new | D1 | A | €0 | M 50 h | I1 | no writes; upgrade of I-g's generic snippet |
| I7 | Vehicle passport — config drift + per-usage param snapshot | **HAVE** (O11-O13) | D0 | A | €0 | M 50 h | I1 | one FK onto `AssetUsage`; maintenance + forensics |
| I8 | "Works with" compatibility matrix (opt-in, aggregated) | new | D0 | — | €0 | M 60 h | I1 | policy decision as much as engineering |
| I9 | One-line companion installer (`curl /setup.sh \| sh`) | **GATE** (O10) | D3 | C | €60–100 | M 50 h | — | I-d recipe made executable, per-asset generated |
| I10 | Video ingest self-diagnosis (codec, GOP, jitter, bitrate vs budget) | **GATE** (O10) | D0 | C | €0 | M 40 h | — | readiness language applied to video |
| — | Tier-B failsafe/link param writes | **GATE** | D1 | B | €0 | M 40 h | I4 | explicit per-item consent; disarmed-only |
| — | **Tier-C flight-critical params (PID, arming, frame)** | **NO** | — | — | — | — | — | **never written. Reported only.** Non-negotiable |

---

## 4. Group B — COPY (competitors have it)

Ratios `n/8` from FEATURE-MATRIX's own product survey.

| # | Capability | Who | Status | Drone | Link | Owner € | Dev | Depends | Alignment |
|---|---|---|---|---|---|---|---|---|---|
| B1 | ADS-B / air-traffic overlay | 3/8 | COPY | D0 | — | €0 | S–M 40 h | map layers | feed client + layer; **base-side, drone needs nothing** |
| B2 | Battery health (cycles, trend, swap alerts) | ~5/8 | COPY | D0 | A | €0 | M 60 h | usages | feeds C3; battery identity in attributes |
| B3 | Maintenance log / flight hours per component | table stakes | COPY | D0 | A | €0 | M 60 h | usages | schedule model + notifications |
| B4 | Pilot currency & flight-hours dashboard | table stakes | COPY | — | — | €0 | M 60 h | U-e | certification records; group scoping exists |
| B5 | Control/watch handoff between operators | 4/8 | COPY | D0 | B | €0 | L 160 h | U-e | session ownership + transfer protocol |
| B6 | **Offline map tiles / air-gapped basemap** | GCS stakes | COPY | — | — | €0 | M 40 h | `adapter-tiles` (branch) | **fixes an offline-positioning contradiction** |
| B7 | Thermal / IR stream ingest + fusion | FLIR/DJI/Skydio | COPY | **D4** | C | €200–1500 | M 80 h | ingest | ingest is cheap for us; sensor is theirs |
| B8 | Alert-rules editor ("would have fired 47×") | ~2/8 | COPY | D0 | C | €0 | L 160 h | detections | rules engine + replay-against-history |
| B9 | **Mission/waypoint execution** | **8/8** | **NO** | D1–D3 | B | €0 | XL | command TX St.3 | QGC does it better and free — build M2 instead |
| B10 | Docking / drone-in-a-box | 3/8 | **NO** | D4 | — | €1000+ | XL | — | wrong persona |
| B11 | Cloud multi-site sync | 5/8 | later | — | — | €0 | L | — | conflicts with offline-first until reconciled |

---

## 5. Group C — PUNCH (base-side compute, differentiated)

| # | Capability | Replaces onboard | Drone | Link | Owner € | Dev | Depends | Alignment |
|---|---|---|---|---|---|---|---|---|
| C1 | **Video enhancement** — stabilize, dehaze, denoise, low-light, upscale | gimbal + good sensor + ISP (€200+) | **D0** | C | **€0** | M 80 h | pipeline | new `StreamPipeline` stage (possibly own adapter) |
| C2 | **Click-to-follow tracking** (advisory crop-follow → gimbal → aircraft) | Skydio-class onboard autonomy | D0 / D1 / **D3** | C / **D** | €0 / €50–400 | L 200 h | CV, gimbal, GUIDED | **advisory tier D0**; commanded tier gated like I-e |
| C3 | **Energy-aware return decisioning** — wind-corrected range, point of no return | nothing — FC failsafe is a dumb % threshold | **D0** | A | **€0** | M 70 h | B2, C4, usages | new service on `UsageTracker` choke point |
| C4 | **Wind field from the fleet** — every track is a wind sample | onboard sees only its own point | **D0** | A | **€0** | M 60 h | telemetry | pure math in `vision-application`, no port |
| C5 | **Multi-aircraft deconfliction** — separation, predicted conflicts | no FC sees other aircraft | **D0** | A | **€0** | M 60 h | fleet snapshot | pure evaluator beside `GeofenceMonitor` |
| C6 | **Link-loss prediction** — shade the map where the link *will* drop | impossible onboard | **D0** | A | **€0** | M 70 h | **DEM** | new `TerrainPort` + map layer |
| C7a | **GPS spoof/jam detection** — vision vs GNSS disagreement | EKF sees only gross inconsistency | **D0** | C | **€0** | M 60 h | P7, A1 | **falls out of `PositionFusion`'s `UNCERTAIN`** — mostly UI + alerting |
| C7b | Vision position injection (`VISION_POSITION_ESTIMATE`/`GPS_INPUT`) | €500+ onboard VIO rig | **D3** | **D** | €60–120 | XL | C7a, link | **GATE — highest risk in the repo.** EKF wants consistent, low-latency, timestamped input |
| C8 | Base-side precision landing (`LANDING_TARGET`, `PLND_TYPE=1`) | IR-LOCK / Landmark rig | D1+**D3** | **D** | €60–120 | M 60 h | CV, TX | protocol wants 10–50 Hz — **needs an experiment before it is promised** |
| C9 | **Terrain-aware true AGL + proximity warning** | rangefinder + terrain DB onboard | **D0** | A | **€0** | M 50 h | **DEM** | shares C6's `TerrainPort` |
| C10 | Coverage/search planner *drawn for the pilot* | mission executor + upload | **D0** | A | **€0** | M 60 h | C11, map | pure geometry + layer; **no command TX** |
| C11 | Automatic camera calibration (intrinsics/FOV from flight) | a setup step no owner does right | **D0** | C | **€0** | M 60 h | geo | quiet enabler for C10, P7, C1 |
| C12 | **Cross-sensor detection fusion** — one deduped geolocated track | impossible onboard by definition | **D0** | C | **€0** | L 180 h | CV, geo, COP | new track-association service |
| C13 | After-action evidence package (flight+video+detections+marks+params) | — | **D0** | C | **€0** | S–M 40 h | recording, I7 | export over existing aggregates |
| C14 | Latency-compensated remote piloting (predict by measured RTT) | — | **D0** | **D** | €0 | M 60 h | RC relay | makes LTE piloting flyable |
| C15 | Adaptive bitrate command (base measures link, tells the encoder) | onboard congestion control | **D3** | C | €60–100 | S–M 40 h | I9 | companion agent + control channel |
| C16 | Sector assignment / swarm-lite coverage | — | **D0** | A | €0 | L 160 h | C5, C10 | advisory tier only |
| C17 | **"Virtual companion computer"** (umbrella: guided setpoints, landing target, vision position, obstacle distance) | a €40–120 onboard computer | **D3** | **D** | €60–120 | XL | C2/C7b/C8/C9 | **GATE** — the framing, not a single build |

---

## 6. Group P — position sources (the odometry stack)

Every row produces the same `PositionFix(position, radiusMeters, confidence, at, origin)`.
**Adding a source = adding a `FixOrigin` + a producer.**

| # | Source | Status | Component | Owner € | Drift | Jam-proof | Computed | Dev | Alignment |
|---|---|---|---|---|---|---|---|---|---|
| P0 | GNSS (M10-class) | HAVE | GPS module | €15–40 | none | ✗ | drone | — | `Telemetry` today |
| P1 | RTK GNSS | later | RTK rover + NTRIP | €150–400 | none | ✗ | drone+base | M 60 h | single-feature; low priority |
| P2 | CRPA anti-jam GNSS | **NO** | CRPA array | **€300–3000** | none | partial | drone | S 8 h | nothing to build — **argue against it; P6–P8 cost €0** |
| P3 | IMU dead reckoning | HAVE | in FC | €0 | 100s m/min | ✓ | drone | — | seconds, not minutes |
| P4 | Baro + magnetometer | HAVE | in FC | €0 | — | mag distorted | drone | — | aiding only |
| P5 | Optical flow + rangefinder | later | PMW3901 + TFmini | €20–60 | 1–3 % | ✓ | drone | M 40 h | low AGL only |
| P6 | **Visual odometry (frame-to-frame)** | BRANCH spec | **camera only** | **€0** | 1–5 % | ✓ | **base** | M 80 h | specced VISUAL-GEO §12.5 (15.7→10.6 m measured) |
| P7 | **Visual place recognition vs reference imagery** | BRANCH | **camera only** | **€0** | **bounded** | ✓ | **base** | — | **the only layer that resets drift**; retrieval accuracy still open (§12) |
| P8 | Terrain contour matching (TERCOM) | new | baro or rangefinder | €0–150 | bounded | ✓ | **base** | L 160 h | reuses C6/C9 `TerrainPort`; works where P7 fails (forest/snow/water) |
| P9 | **Ground-station radio TDOA/AoA** | new | 2–3 receivers **on the ground** | €30–100 ea *(not on the drone)* | bounded | ✓ | **base** | L 200 h | **our own mini-GNSS**; new adapter; highest novelty, hardest R&D |
| P10 | LEO signals-of-opportunity PNT | research | SDR | €100–300 | research | ✓ | base | XL | **flag as research, do not promise** |
| P11 | Operator manual fix | new | — | €0 | — | ✓ | base | S 16 h | always the final layer; never remove |
| A1 | **`PositionFusion` → N-source registry** | new | — | €0 | — | — | — | M 60 h | refactor inside an existing pure class; no port/wire change |
| A2 | Name contributing origins on the wire | new | — | €0 | — | — | — | S 12 h | enum + DTO; SSE already carries `source` |
| A3 | **Merge `feat/visual-geo`** | BRANCH | — | €0 | — | — | — | — | **prerequisite for P6–P9, C7a, C11, M5** |

**Starlink is a link, not a position source** — 1.1 kg, 25–40 W, $249. It does not tell you where
you are; it lets the base keep computing where you are when nothing else reaches you.

---

## 7. Group M — missions

| # | Layer | Status | Drone | Link | Owner € | Dev | Alignment |
|---|---|---|---|---|---|---|---|
| M1 | **Tasking** — objective, AOI, assignments, time window, evidence, after-action | PUNCH | **D0** | A | **€0** | M 80 h | new `Mission` aggregate + repo port; standard service+`Default*` idiom |
| M2 | **Guidance** — computed route/coverage drawn for the pilot to fly | PUNCH | **D0** | A | **€0** | M 60 h | = C10; pure geometry + map layer |
| M3 | Execution — waypoint upload, autonomous flight | **NO** | D1–D3 | B | €0 | XL | QGC/Mission Planner territory — interoperate |
| M4 | GNSS-denied continuous guidance (GUIDED setpoints from fused position) | **GATE** | **D3** | **D** | €60–120 | XL | **fragile** — guidance stops exactly when jamming starts; 3 s lapse timeout coasts to a stop, not to a target |
| M5 | **Navigation pack export** — corridor descriptors + DEM + landmarks, shipped pre-flight | PUNCH | **D0** | — | **€0** | M 80 h | new endpoint over `ReferenceIndexPort`/`GeoRegion`; **no in-flight link needed** |
| M6 | Onboard matcher agent (consumes M5 offline) | GATE | **D3** | — | €20–60 | **XL** | **new non-Java deliverable — a scope decision, not a feature** |

**Terminal guidance must be onboard** — the link may be gone precisely when it matters. Concede
this boundary explicitly; do not market base-side terminal guidance.

---

## 8. Group T — transport & field infrastructure

Added 2026-08-09 from an external distributed-C2 plan (RPi + mavp2p + MediaMTX + Tailscale field
nodes → NATS broker → Redis GEO/H3 spatial routing → automated ground-station handover → Ansible
fleet ops). **Three claims in the review of that plan were checked against this tree and two came
back different — the corrected verdicts are below.**

| # | Item | Verdict | Drone | Link | Owner € | Dev | Alignment / reason |
|---|---|---|---|---|---|---|---|
| **T1** | **RPi field node** — companion pushing telemetry (mavp2p) + video (MediaMTX) over an IP link | **TAKE** | **D3** | C | €60–120 | M 50 h | **= I9 made concrete.** The survey rig: the only way to capture paired (frame, telemetry) data over a real region and finally measure P7 |
| T2 | MediaMTX **WebRTC/WHEP** low-latency egress | **ALREADY HAVE** | D0 | C | €0 | **S 8 h (config)** | `MediamtxStreamPublisher.whepUrl()`, signaling `:18889`, ICE UDP `:8189`, `MTX_WEBRTCADDITIONALHOSTS`/`VISION_WEBRTC_HOST` in compose, browser player in `vision-web/shared/player/`. **Real remaining gap is only reachability beyond localhost** — set `VISION_WEBRTC_HOST` to the LAN IP (already documented in `adapter-publish-hls/MODULE.md`). A deployment line, not a feature |
| T3 | NATS as **telemetry/event** bus | **OPTIONAL** | — | — | €0 | M 60 h | **The seam already exists and is unused by design:** no broker dependency anywhere in the build, and `EventPublisherPort`'s javadoc names MQTT/Kafka as swap-in implementations for multi-instance deployments; `LiveUpdatePublisherPort` fans out live. Adopting any broker is **one adapter class** — so it can never be urgent. Do it the day a second instance exists, not before |
| T4 | NATS as **command** bus | **DECLINE (refined)** | — | — | — | — | Per the command-transport rule in §0: **Class D closed loops stay direct, single hop.** Class B one-shots *may* relay if outage = visible refusal + stale-command guard. The blanket "no broker in command" is stricter than needed; the blanket "route all MAVLink through NATS" is unsafe |
| T5 | Redis GEO / H3 spatial routing | **DEFER** | — | — | — | — | Real tech, zero current need. One aircraft does not need spatial routing. Revisit at 5+ nodes |
| T6 | Automated ground-station handover | **DEFER** | — | — | — | — | Duplicate of B5 (operator handoff) at the infrastructure layer, for stations that do not exist |
| T7 | Ansible fleet-update of 10+ field nodes | **DEFER** | — | — | — | — | Ops tooling for a fleet that does not exist. Revisit with T5 |
| T8 | Optical flow + LiDAR for GNSS-denied *stability* | **= P5, onboard** | D2 | — | €20–60 | — | Correctly the aircraft's job. **It does stability, not localization** — it does not touch the where-am-I problem P6–P9 solve. Do not conflate the two |

**The one-line summary of that plan for this project:** it is not a C2 architecture to adopt — it
is **a hardware recipe for the single companion node the survey needs.** Take its field-node
phase as a shopping list and a wiring diagram; leave the distribution layer as a description of a
product worth building only if the coordinate punch earns a fleet.

---

## 9. Group K — core consolidation (the feature freeze)

Added 2026-08-11 after reviewing an external "Autonomous Drone Management & CV Analytics Platform"
spec (GPU ingest → TensorRT batching → hexagonal domain engine → WS/WebRTC UI). **Roughly 70 % of
that spec describes subsystems this repo already has**, so its real value was diagnostic: it
surfaced exactly one foundational hole, and it prompted the right strategic question — *should the
core be finished before any new feature lands?*

**Verdict on that question: yes, with a scoped list.** Rows K1–K4 are the freeze. Everything in
Groups B/C/M/T waits behind them. This supersedes §10's ordering for as long as the freeze holds.

| # | Item | Verdict | Effort | Why |
|---|---|---|---|---|
| **K1** | **`feat/visual-geo`** | **PARK — do not merge** (revised 2026-08-11) | — | **The engine does not deliver.** Latest eval (`angle-probe-wire-eval-20260809`): correct tile top-1 in **0 of 12** frames, `GEO_NO_FIX` × 12, sequence never converged. Merging 337 files / 57k insertions of research code into `master` is a tax on every future refactor, not an asset. **One redeeming number: `n_confident_wrong: 0`** — it never produced a confident wrong fix even when a wrong tile ranked #1 all 12 times. The abstention machinery is sound; the *ranking* is what fails (correct tile at median rank 14 of ~40 — signal present, not random; matches the plan's own recall@5 0.87–1.00). Revive if and when ranking is solved. **Harvest `adapter-tiles` separately when B6 needs it** — small targeted extraction, not a branch merge |
| **K2** | **Tracking engine — persistent target IDs across frames** | **BUILT — 2026-08-11** | ~~L ~180 h~~ | Was the one real hole: `TrackedObject` was a dead type. Now live end to end — detect-then-track duty cycle, pluggable `TrackerRegistry` (bytetrack/botsort/norfair), frozen additive proto contract, `OFF`/`ASSOCIATE`/`FOLLOW` per stream, tracks through domain → SSE → overlay → COP, waves T0–T8 all green with T8 flipping the default to `ASSOCIATE`. **C12 and C2 are unblocked.** Open tail, both hardware-gated, neither a build: the camera-facing demos of §10 (no camera until H1) and ARM CI for the §3.3 portability claim (resolution-checked only, no Pi). **Spec: docs/plans/done/TRACKING-PLAN.md**, companion docs/extracts/TRACKING-ORCHESTRATION.md |
| **K3** | `StreamPipeline` decomposition | **PAID 2026-09-13** (CV-ORCHESTRATION W2 + W8, `a0fcf736` on `feat/cv-orchestration`) | M ~60 h | Was 927 lines carrying sampling, in-flight bounding, overlay, fan-out, telemetry and usage tracking at once. Now composes unit-tested collaborators — `WorldModel` + `PipelineTrace` (W2), `FrameSampler` + `OutageSupervisor` + `DetectionGate` (W8); 1485 lines of which 504 are code, telemetry assembly and label filtering still inline (optional W8b, CV-ORCHESTRATION-PLAN.md §8 E26). `DefaultSimulationService` (759) is the next one |
| **K4** | Dead-type / dead-port audit | **DO** | S ~16 h | K2 was found by grepping one type's usages. Do it for every domain type and port — anything referenced only by its own test is either a gap like K2 or removable |
| K5 | Cross-stream dynamic batching `[B,C,H,W]` | **DEFER — hardware-gated** | M | Genuinely absent. But it is a *GPU* optimization, and the deployment box has no GPU (see below). Revisit when the hardware does |
| K6 | Zero-copy NVDEC → CUDA IPC frame path | **N/A on current hardware** | — | We decode via FFmpeg, downscale + JPEG-encode, ship over gRPC (`DetectionFrameCodec`). Real architectural difference, real cost — **and inapplicable**: it needs NVDEC + CUDA, and GB4005 is Intel UHD 600 |
| K7 | In-memory domain event bus | **DECLINE** | — | The spec's central dispatcher is *weaker* than what we have. Direct port calls are traceable, statically typed and ArchUnit-enforced; an in-memory bus trades that for indirection. `EventPublisherPort`/`LiveUpdatePublisherPort` already cover genuine fan-out |
| K8 | TimescaleDB / InfluxDB for telemetry | **DEFER** | M | Postgres + JPA is not the bottleneck at current volume. Revisit on measured query pain, not on principle |
| K9 | Automated failsafe recovery (auto-RTL, land-in-place, rally divert) | **GATE — doctrine** | L | The spec puts an automatic recovery authority *above* operator input. That is the exact inversion of "the base is never a flight dependency". We detect and advise; the FC recovers. Would need an explicit go and would change the product's safety story |

### What the spec got right that we already do — and do more strictly

| Spec claim | Our state |
|---|---|
| Ring buffer, `QueueSize=1`, `DropStrategy=LatestFrameOnly` | **Solved twice, independently.** `StreamPipeline` bounds in-flight inferences and *skips rather than queues*; cv-service has `LatestOnlyMailbox` + `InferenceGate` |
| Dual path — full-rate video to UI, subsampled to analytics | Have: publish at source rate, detect at sample rate |
| Hexagonal ports & adapters | Have, **ArchUnit-enforced** — the spec only describes the discipline, we test it |
| Low-latency WebRTC to UI | Have (WHEP + browser player) |
| MAVLink ingest → domain events; MAVLink command egress | Have (gateway I-a; commander I-e, capability-gated + audited) |
| 2D pixel → 3D world ray | Have flat-earth (`GeoProjection`, `BearingDistance`). **DEM intersection is row X** — still the missing half |
| Geofence monitoring | Have (OPS-CORE G) |
| Mission/waypoint + QGC `.plan` import | **Deliberately declined** — M3 |

### The premise check that matters most

The spec's entire performance architecture — NVDEC, CUDA IPC zero-copy, TensorRT FP16/INT8,
dynamic GPU batching — assumes an NVIDIA box. **The actual inference host, GB4005, is an Intel
Gemini Lake mini-PC: 2 cores, 7.6 GB RAM, UHD 600, no CUDA** (`cv-service/DEPLOY-GPU.md`, CPU torch
wheel, rsync deploys). K5 and K6 are not deferred because they are unimportant; they are deferred
because **they cannot run on the hardware this system deploys to.** Any plan that opens with a GPU
pipeline needs that premise checked before its effort estimates mean anything.

---

## 10. Component ladder — features unlocked per euro

| Rank | Component | Owner € | Unlocks | Verdict |
|---|---|---|---|---|
| **1** | **Telemetry downlink** — ELRS backpack (€0, already fitted) or ESP32 bridge (€5) | **€0–5** | ~15 rows: all of Group I, C3–C6, C9, B2, geofence, banners, preflight | **best ratio in the system** |
| **2** | **Video downlink** — present on any FPV aircraft | €0 have / €25 analog / €100+ digital | all CV, P6, P7, C1, C11, C12, target geolocation | already there on nearly every real aircraft |
| **3** | GNSS module (M8N/M10) | €15–40 | map baseline, geofence, RTH, C3–C5 | **not required for P6–P8 — that is the point** |
| **4** | Companion + IP link (Pi Zero 2 W + LTE) | €60–100 | Class C/D, **T1**, I9, C15, C2, C7b, C8, M6 | changes what class of aircraft you are — **and it is the survey rig (T1), so this one gets bought first** |
| 5 | Rangefinder (TFmini) | €40–150 | true AGL, C8, P8, better target geolocation | multi-feature, good value |
| 6 | Optical flow (PMW3901) | €20 | P5 at low AGL | cheap, narrow |
| 7 | Gimbal (1-axis / 3-axis) | €50–400 | C2, stable geolocation, much better P7 | first genuinely expensive step |
| 8 | Thermal (Lepton/Boson) | €200–1500 | B7 night ops | single-domain |
| 9 | RTK | €150–400 | P1 cm accuracy | **single-feature**; still jammable |
| 10 | **CRPA** | **€300–3000** | P2 anti-jam GNSS | **single-feature — and P6–P8 do the job for €0** |
| 11 | Starlink Mini | $249 + sub (**1.1 kg**, 25–40 W) | BLOS Class D anywhere | airframe-limited; almost no dev work — document it |
| 12 | ADS-B receiver | €200 | onboard traffic | **single-feature** — B1 does it from a ground feed for €0 |

**Ranks 1–3 cost under €80 total and unlock the overwhelming majority of the platform.**
Ranks 8–12 are single-purpose.

---

## 11. Sequence

**Feature freeze in effect (2026-08-11):** nothing from Groups B / C / M / T starts until K1–K4 are
done. **Single track — there is no hardware on it.** Development runs on `adapter-simulation` and
the `infra/sitl/` ArduPilot farm, both already built; P7 measurement runs on the existing bakeoff
harness. Nothing here waits on a delivery.

**Execution plan: docs/main/TWO-TARGETS-PLAN.md** — the hardware and software tracks, tiered, with a
touchable outcome per step.

| Order | What | Rows | Time |
|---|---|---|---|
| ~~1~~ | ~~**Tracking engine**~~ — **DONE 2026-08-11** (docs/plans/done/TRACKING-PLAN.md) | **K2** | ~~~4 wk~~ |
| **1** | **Fixed-camera geolocation** — the first demo you can show someone | **S2** | ~1.5 wk |
| **2** | `StreamPipeline` decomposition + dead-type audit | **K3, K4** | ~2 wk |
| ~~4~~ | ~~Integration funnel~~ — **DONE 2026-08-19** (docs/plans/active/DRONE-ONBOARDING-PLAN.md, O1-O8 + O11-O14; O9/O10 stay operator-gated) | **I1, I2, I3, I7** | ~~~2 wk~~ |
| 5 | DEM + the advisory cluster | X, C3, C4, C6, C9 | ~6 wk |
| 6 | Honest position stack | A1, A2, C7a | ~2 wk |
| 7 | Mission tasking + nav pack | M1, M2, M5 | ~5 wk |
| 8 | Reach features | C1, B1, B2, B6 | ~5 wk |
| 9 | COP payoff (**C12 now buildable — K2 landed**) | C12, C13, I4, I5, I7 | ~7 wk |
| later, gated | P8, P9, C2, C7b, C8, C17, M4, M6, K9 | — | each needs its own go |

**The freeze is two thirds served.** K2 landed 2026-08-11; K1 was decided by parking the branch, not
by building it. What remains of it is **K3 + K4** — no capability a user can see, which is the
point: they finish the core. C12 and C2 are no longer blocked. Every P-row remains blocked on K1,
which is now a *research* dependency (tile ranking), not a merge.

**S2 is the exception worth taking first.** It is not part of the freeze — it is one week and a half
that turns the freeze's invisible work into the first thing showable to a stranger, and it is the
only row on this page whose prerequisite (K2) just landed.

**Sources:** [ArduPilot Guided Mode](https://ardupilot.org/copter/docs/ac2_guidedmode.html) ·
[MAVLink Offboard Control](https://mavlink.io/en/services/offboard_control.html) ·
[MAVLink Landing Target](https://mavlink.io/en/services/landing_target.html) ·
[ArduPilot Precision Landing](https://ardupilot.org/copter/docs/precision-landing-and-loiter.html) ·
[DJI cloud-AI direction 2026](https://enterprise-insights.dji.com/blog/drone-onboard-ai-challenge-2026) ·
[Skydio X10](https://www.skydio.com/x10) ·
[Teledyne FLIR Prism Ground ISR](https://letsdatascience.com/news/teledyne-flir-launches-prism-ground-isr-platform-9d5846dd) ·
[Anubis / Airlogix + Auterion](https://united24media.com/war-in-ukraine/meet-anubis-the-new-long-range-drone-combining-ai-starlink-and-45kg-warhead-already-hunting-russians-18605) ·
[Auterion Skynode](https://auterion.com/product/skynode-x/) ·
[Starlink Mini specs/pricing](https://www.satelliteinternet.com/resources/starlink-mini-review/) ·
[GNSS-denied navigation survey](https://link.springer.com/article/10.1186/s43020-025-00162-z) ·
[CRPA anti-jam](https://infinidome.com/crpa-anti-jamming-antenna/)
