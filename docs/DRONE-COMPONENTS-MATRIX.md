# DRONE-COMPONENTS-MATRIX — position sources, links, components, and what each feature costs

Status: **investigation + decision document (2026-08-09)**. → **The consolidated row-level view of
every capability in this doc now lives in docs/MASTER-MATRIX.md; this doc keeps the reasoning.**
Layer 3 of the strategy set:
docs/MOAT.md (why) → docs/ANY-DRONE-PLAN.md (integration loop) → docs/BASE-COMPUTE-MATRIX.md
(what the base computes) → **this doc** (what hardware each feature actually needs, what it costs
the owner, what it costs us in dev hours, and whether it fits the architecture we have).

Prices are approximate street prices, August 2026, in EUR unless a source is cited. Dev hours are
our side, in the repo's existing effort classes: **S ≤16 h · M ≤80 h · L ≤240 h · XL multi-cycle.**

---

## 1. Position is not one source. It is a stack.

The premise, restated: **GNSS is one input with a small error radius, not the definition of where
the drone is.** An aircraft without a resilient link and without GNSS is not lost — it is running
on a different layer of the stack, with a wider radius, and the system must say so honestly.

### 1.1 The stack

| # | Source | Component needed | Component cost | Drift | Survives jamming | Computed on | Notes |
|---|---|---|---|---|---|---|---|
| **P0** | GNSS (M10-class) | GPS module | €15–40 | none | ✗ | drone | the baseline everyone has |
| P1 | RTK GNSS | RTK rover + base/NTRIP | €150–400 | none | ✗ | drone + base | cm-level, still fully jammable |
| P2 | Anti-jam GNSS (CRPA) | CRPA antenna array | €300–3000+ | none | partial | drone | heavy, power-hungry, export-controlled; **our P6–P8 are the poor man's substitute** |
| P3 | IMU dead reckoning | in every FC | €0 | 100s of m/min | ✓ | drone | seconds of usefulness, not minutes |
| P4 | Baro + magnetometer | in FC / GPS module | €0 | — | mag distorted by EW & motors | drone | altitude + heading aiding only |
| P5 | Optical flow + rangefinder | PMW3901 + TFmini | €20–60 | 1–3 % of distance | ✓ | drone | velocity hold; useful only at low AGL |
| **P6** | **Visual odometry (frame-to-frame)** | **camera only** | **€0** | 1–5 % of distance | ✓ | **base** | already specced: VO+telemetry inverse-variance blend, measured 15.7 m → 10.6 m on real footage (VISUAL-GEO §12.5) |
| **P7** | **Visual place recognition vs reference imagery** | **camera only** | **€0** | **bounded — absolute** | ✓ | **base** | the drift-killer; the only layer that *resets* accumulated error without GNSS |
| P8 | Terrain contour matching (TERCOM) | baro or rangefinder + DEM | €0–150 | bounded | ✓ | **base** | works where P7 fails — forest, snow, featureless water; needs the DEM already flagged in BASE-COMPUTE §4.1 |
| P9 | **Ground-station radio TDOA/AoA** | 2–3 ground receivers | €30–100 each | bounded | ✓ (own spectrum) | **base** | *our own mini-GNSS*: trilaterate the aircraft's own telemetry radio from receivers we control. No drone-side change at all |
| P10 | LEO signals-of-opportunity PNT | SDR | €100–300 | research-grade | ✓ | base | real research area; **flag as research, do not promise** |
| P11 | Operator manual fix | — | €0 | — | ✓ | base | "it is *there*" — always the final layer; never remove it |

**Read the P6/P7/P9 rows again: three independent position sources at zero drone-side cost.** That
is the whole thesis of this project expressed as hardware economics.

### 1.2 Starlink is a link, not a position source

Worth stating because it is easy to conflate. Starlink Mini gives you **BLOS connectivity**
(≈1.1 kg on a drone, 25–40 W, 12–48 V, IP67, $249 / $199 promo) — it does not give you a position
fix. What it gives is *the ability for the base to keep computing your position when nothing else
can reach you.* On a 1.1 kg budget that is a fixed-wing or heavy-multirotor decision, not an FPV
one. LEO signals-of-opportunity PNT (P10) is a genuinely different thing and is not product-ready.

**So: a drone without Starlink is not a drone without a position — it is a drone whose position
must come from P5–P9 and whose fixes reach us later or over a thinner pipe.**

### 1.3 The architecture decision — and the good news

`feat/visual-geo` **already built the right seam**, and this is the single most important finding
in this document:

```
vision-domain:      PositionFix(position, radiusMeters, confidence, at, origin, yaw, footprint)
                    FixOrigin · PositionSource{GPS,FUSED,VISUAL,DEAD_RECKONED,UNCERTAIN}
                    PositionState · VisualFix · VisualTrackingEstimate · GeoPrior
vision-application: PositionFusion (pure, unit-testable) + PositionFusionService (one Tracking
                    per AssetId) — inverse-variance weighting, freshness windows, divergence →
                    UNCERTAIN publishes BOTH hypotheses and asks the operator (D6)
```

Every source in §1.1 produces exactly that record. **Adding a position source is adding a
`FixOrigin` and a producer — not new architecture.** The work is narrower than it looks:

| Change | What | Effort | Alignment |
|---|---|---|---|
| A1 | Generalize `PositionFusion` from 3 hardcoded inputs (GPS / onboard visual / server visual) to an **N-source registry**, each contributing a `PositionFix` with its own freshness window and σ | **M (~60 h)** | refactor inside an existing pure class; no port change, no wire change |
| A2 | Extend `PositionSource` beyond the 5 fusion states to name the *contributing origins* on the wire, so the UI can say "carried by VPR + flow, GNSS rejected" | S (~12 h) | enum + DTO field; SSE payload already carries `source` |
| A3 | **Merge `feat/visual-geo`** (19 commits) — everything above is stranded until this lands | L | the branch is the prerequisite for every P6–P9 row |

**Do A3 first.** Nothing else in this document's position half can start while the fusion seam
lives on an unmerged branch.

### 1.4 The honest-degradation rule

`UNCERTAIN` already publishes both hypotheses rather than silently picking (VISUAL-GEO D6). Extend
that doctrine to the whole stack: **the operator always sees which layer is carrying the estimate
and how wide the radius is.** A drone on P6+P8 with a 60 m radius is useful; a drone claiming GNSS
precision while being spoofed is lethal. This is the same "never fake a read" rule the codebase
already lives by, applied to the most safety-critical number in the system.

---

## 2. The link stack (the real bottleneck)

Base-side compute is worthless without a pipe. Compute is cheap; **the link is the constraint.**

| # | Link | Component | Cost | Range | Bidir | Practical rate |
|---|---|---|---|---|---|---|
| L0 | RC control (ELRS/Crossfire) | RX | €10–25 | 5–40 km LOS | yes, low rate | control + ~2 kB/s telemetry backpack (free) |
| L1 | Analog VTX + camera | VTX + cam | €25–60 | LOS | no | video down only, poor quality |
| L2 | Digital VTX (Walksnail / HDZero / O4) | kit | €100–250 | LOS | video down | good video, closed ecosystems |
| L3 | ESP32 / mavesp8266 WiFi bridge | ESP32 | **€5** | short | yes | telemetry only — **best value in the table** |
| L4 | SiK / LoRa telemetry | pair | €30–60 | 5–20 km | yes | 57–115 kbps |
| L5 | LTE/5G modem + companion | modem + SIM | €30–60 + plan | coverage-bound | yes | Mbps, 50–200 ms RTT |
| L6 | **Starlink Mini** | terminal | **$249** (1.1 kg, 25–40 W) | anywhere | yes | 100–400 Mbps |
| L7 | Drone-to-drone mesh relay | — | — | — | — | punch feature, later |

### 2.1 Link classes — every feature must declare one

| Class | Needs | Unlocks |
|---|---|---|
| **A** | telemetry down, ~2 kB/s, one-way tolerable | all advisory features (C3–C6, C9 in BASE-COMPUTE) |
| **B** | telemetry bidirectional, low rate | commanded features (RTL, mode, fence upload) |
| **C** | video down ≥ 1 Mbps | all CV, VPR (P7), VO (P6), tracking |
| **D** | video + bidirectional, **RTT < 200 ms, stable** | closed loops: aircraft-follow, precision landing, vision-position injection |

**Class D is where promises go to die.** It requires L5/L6 *and* a companion computer, and it is
the only class where link quality is a flight-safety dependency rather than a feature dependency.
Everything we ship should be A/B/C unless there is an explicit, gated decision otherwise.

---

## 3. Component ladder — most needed → single-feature

Ranked by **features unlocked per euro**, which is the only ranking that matters for "any drone
can be integrated".

| Rank | Component | Cost | Features unlocked | Verdict |
|---|---|---|---|---|
| **1** | **Telemetry downlink** — ELRS backpack (**€0**, already on the airframe) or ESP32 bridge (**€5**) | €0–5 | ~15: map position, geofence, failsafe banners, preflight, battery, wind field, energy-aware return, deconfliction, link prediction, terrain AGL, all of ANY-DRONE | **The single best ratio in the entire system.** Every onboarding effort should push here first. |
| **2** | **Video downlink** — already present on any FPV aircraft | €0 (have) / €25 analog / €100+ digital | all CV, detection, VPR (P7), VO (P6), tracking, video enhancement, target geolocation | Already there on nearly every real aircraft. |
| **3** | **GNSS module** (M8N/M10) | €15–40 | map baseline, geofence, RTH, mission | Not required for P6–P8. **That is the point** — but it is cheap and everyone has one. |
| **4** | **Companion + IP link** (Pi Zero 2 W €20 + LTE €40) | €60–100 | Class C/D: quality video + telemetry over one link, all commanded features, adaptive bitrate, **offline nav pack (§5)** | The upgrade that changes what class of aircraft you are. |
| **5** | **Rangefinder** (TFmini) | €40–150 | true AGL, precision landing, better target geolocation, TERCOM (P8) | Multi-feature, cheap. Good value. |
| **6** | **Optical flow** (PMW3901) | €20 | GNSS-denied velocity hold at low AGL (P5) | Cheap, narrow (low altitude only). |
| **7** | **Gimbal** (1-axis €50 / 3-axis €150–400) | €50–400 | click-to-follow, stable geolocation, much better VPR (known camera attitude) | The first genuinely expensive step; big quality jump for P7. |
| 8 | Thermal camera (Lepton/Boson) | €200–1500 | night ops, thermal detection, fusion | Single-domain, high cost. |
| 9 | RTK module | €150–400 | cm-accurate survey, precision landing | **Single-feature.** Still jammable — solves accuracy, not resilience. |
| 10 | CRPA anti-jam antenna | €300–3000 | GNSS under jamming | **Single-feature, and P6–P8 do the same job for €0.** Our strongest cost argument. |
| 11 | **Starlink Mini** | $249 + subscription, 1.1 kg, 25–40 W | BLOS everything; Class D anywhere | Airframe-limited. Fixed-wing / heavy multirotor only. |
| 12 | ADS-B receiver | €200 | traffic awareness onboard | **Single-feature** — and the base can do it from a ground feed for €0 (B1). |

**The punchline, and it should be on the marketing page:** ranks 1–3 cost **under €80 total** and
unlock the overwhelming majority of the platform. Ranks 8–12 are single-purpose. **Rank 10 is the
one we most want to argue against** — a CRPA costs 10–100× what visual geolocation costs, and
covers a narrower failure mode.

---

## 4. Missions — three layers, and only one of them is ours

"Mission" conflates three different products. Separating them resolves the B9 tension in
BASE-COMPUTE (8/8 competitors have mission execution, yet copying it is a trap):

| Layer | What | Where it runs | Drone-side | Ours? |
|---|---|---|---|---|
| **M1 — Tasking** | objective, area of interest, assigned assets + pilots, time window, checklist, evidence collection, after-action package | base | **D0** | **Yes — unique, and nothing about it needs an aircraft change** |
| **M2 — Guidance** | computed route/coverage pattern, next-leg arrow, coverage progress — *drawn for the pilot to fly* | base | **D0** | **Yes** — this is BASE-COMPUTE C10, advisory autonomy |
| **M3 — Execution** | waypoint upload, autonomous flight | FC | D1–D3 | **No.** QGC/Mission Planner do it better and free. Interoperate. |

**M1 is the one to build.** It is the ISR mission lifecycle — the thing DroneSense-class products
sell — and it sits directly on top of the COP, marks, layers, usages and recordings we already
have. Effort **M (~80 h)**, new `Mission` aggregate + repository port, fits the existing
service+`Default*` idiom exactly. Zero hardware implications.

### 4.1 Missions when GNSS is gone — and the honest boundary

A mission expressed in WGS-84 waypoints is meaningless to an aircraft that cannot resolve WGS-84.
Three options, and only two survive scrutiny:

1. **Base guides continuously** (fused position → GUIDED setpoints, 5–10 Hz). Requires **Class D**.
   Honest verdict: *fragile.* The moment the link degrades — exactly when jamming is happening —
   guidance stops. ArduPilot's own 3 s command-lapse timeout is the safety net, and it means the
   aircraft coasts to a stop, not to a target.
2. **Onboard visual navigation.** What Anubis does (§6). Needs onboard compute. **We cannot do this
   from the base, and we should say so plainly rather than imply otherwise.**
3. **Precomputed navigation pack** — §5. The one that fits us.

---

## 5. The idea this investigation produced: precompute-and-ship

There are three ways to use base-side compute, and we have only been using two:

| Mode | Latency budget | Link needed | Example |
|---|---|---|---|
| **Live loop** | ms | Class D | click-to-follow, precision landing |
| **Advisory** | seconds | Class A–C | energy-aware return, link prediction, coverage guidance |
| **Precomputed pack** | **none — computed before takeoff** | **none in flight** | ← new |

**The base already builds a reference-imagery index per region** (`adapter-tiles`, `GeoRegion`,
`ReferenceIndexPort`, cv-service indexing). That index is exactly what an onboard visual-navigation
system needs. So: **export a corridor-scoped navigation pack — reference descriptors + DEM tiles +
the mission's landmarks — and ship it to a €20 companion computer before the flight.**

The aircraft then navigates GNSS-denied *using our data*, offline, with no link at all, and
reconciles when the link returns. The heavy computation stays on the base where the GPU is; only
the result flies.

This is the answer to §4.1 and it sidesteps the latency law entirely:

| | Effort | Component | Alignment |
|---|---|---|---|
| Pack export (server side) | **M (~80 h)** | — | new endpoint over the existing `ReferenceIndexPort`/`GeoRegion`; no new module |
| Onboard matcher (companion side) | **XL** | €20–60 companion | **new deliverable outside the Java tree** — a small Python/C++ agent. This is a real scope expansion, not a feature |
| Reconciliation on link return | S–M | — | fits `PositionFix` ingestion (§1.3 A1) verbatim |

**Recommendation: build the pack export (M), publish the format, and let the onboard matcher be a
separate decision.** The export alone is valuable — it makes us the *supplier* of navigation data
to aircraft we do not control, which is a strategically better position than being one more
autopilot.

---

## 6. Anubis — what it is, and what it tells us

**Anubis** (Auterion + Airlogix JV, US/German/Ukrainian): delta-wing, combustion engine, declared
~1600 km range and ~45 kg warhead, matte low-signature finish. Technically the relevant parts:
**Skynode N onboard mission computer (NVIDIA Orin)**, Auterion **Visual Navigation** plus terminal
guidance that works with satellite positioning fully denied, Starlink-based comms, EW resistance.
Publicly announced April 2026; a sibling, Seth-X, launched alongside. Skynode N pricing is not
public (pre-release); the cost-reduced line is Skynode S.

**What it confirms:** GNSS-denied visual navigation is now the top-end standard, not a research
topic. Pillar 1 in MOAT.md is aimed at the right thing.

**What it is not:** Anubis is a **one-way strike platform, vertically integrated** — their
airframe, their computer, their software, their supply chain. The economics are a warhead's
economics: the compute flies once.

**What it tells us to do:**

| Observation | Our move |
|---|---|
| Visual nav lives on a ~€1000+ onboard computer that is consumed on use | For a **reusable ISR** aircraft that math inverts — put the GPU on the ground and reuse it every flight. Our entire thesis, validated by their counter-example. |
| Terminal guidance must be onboard (link may be gone) | **Concede this boundary explicitly** (§4.1). Do not market base-side terminal guidance. |
| They ship reference data to the aircraft | **Precompute-and-ship (§5) is the same architecture at 1/50th the hardware cost** — €20 companion instead of a Skynode. |
| Vertically integrated | Our niche is the exact complement: **the aircraft we serve is one somebody already owns.** Never compete with them on airframes. |

---

## 7. Feature → component → cost → hours → alignment

The consolidated matrix. **Owner cost** = what the drone owner must buy beyond a working
FPV/telemetry setup. **Dev hours** = our side. **Alignment** = how it lands in the current tree.

### 7.1 Zero additional hardware (D0/D1) — build these first

| Feature | Position layers | Link class | Owner cost | Dev hours | Architecture alignment |
|---|---|---|---|---|---|
| ANY-DRONE readiness loop (waves 1–2) | — | A | **€0** | M (~70 h) | new `VehicleConfigPort`; read half is doctrine-safe; rides `MavlinkSocketHub` ack seam |
| Wind field from fleet (C4) | P0 | A | **€0** | M (~60 h) | pure math in `vision-application`; consumes `Telemetry`; no port |
| Energy-aware return (C3) | P0 | A | **€0** | M (~70 h) | new service + `AssetUsage` history; hooks `UsageTracker` choke point |
| Multi-aircraft deconfliction (C5) | P0 | A | **€0** | M (~60 h) | fleet snapshot exists; new pure evaluator alongside `GeofenceMonitor` |
| Link-loss prediction (C6) | P0 | A | **€0** | M (~70 h) | **needs DEM dependency**; new `TerrainPort` + map layer |
| Terrain-aware AGL (C9) | P0 | A | **€0** | M (~50 h) | same `TerrainPort`; shares C6's cost once |
| Mission tasking (M1) | — | A | **€0** | M (~80 h) | new `Mission` aggregate + repo port; standard service idiom |
| Coverage guidance (M2 / C10) | P0 | A | **€0** | M (~60 h) | pure geometry + map layer; no command TX |
| Video enhancement (C1) | — | C | **€0** | M (~80 h) | new stage in `StreamPipeline`; possibly a new adapter |
| Cross-sensor fusion (C12) | P0/P7 | C | **€0** | L (~180 h) | sits on marks + detections + geo; new track-association service |
| GPS spoof detection (C7a) | P0 + P7 | C | **€0** | M (~60 h) | **falls out of `PositionFusion`'s existing `UNCERTAIN` state** — mostly UI + alerting |
| Visual odometry (P6) | P6 | C | **€0** | M (~80 h) | specced in VISUAL-GEO §12.5; cv-service side |
| Ground TDOA positioning (P9) | P9 | A | €30–100 × 2–3 **ground** (not on the drone) | L (~200 h) | new adapter + `PositionFix` producer; **hardest R&D in the doc, highest novelty** |

### 7.2 Cheap hardware (D2) — €5–150

| Feature | Component | Owner cost | Dev hours | Alignment |
|---|---|---|---|---|
| Telemetry for an aircraft that has none | ESP32 bridge | **€5** | S (~8 h, docs) | I-d recipe already written |
| TERCOM position layer (P8) | rangefinder (or baro only) | €0–150 | L (~160 h) | `TerrainPort` + new `FixOrigin`; reuses C6's DEM |
| Precision-landing detection (advisory) | none (video) | €0 | M (~60 h) | CV + `GeoProjection`; advisory tier only |

### 7.3 Companion-class (D3) — €60–120

| Feature | Owner cost | Dev hours | Alignment |
|---|---|---|---|
| **Navigation pack export (§5)** | €0 server-side | **M (~80 h)** | new endpoint over `ReferenceIndexPort`; **highest strategic value per hour in this doc** |
| Onboard matcher agent | €20–60 companion | **XL** | **new non-Java deliverable — scope decision, not a feature** |
| Adaptive bitrate (C15) | €60–100 | S–M (~40 h) | companion-side agent + control channel |
| Click-to-follow, aircraft (C2) | €60–100 + gimbal | L (~200 h) | **Class D — gated like I-e**, SITL-first |
| Vision position injection (C7b) | €60–100 | XL | **Class D, highest risk in the repo.** EKF wants consistent low-latency timestamped input; marginal over LTE. Explicit go required |

### 7.4 Specialist (D4) — €200+

| Feature | Component | Owner cost | Dev hours | Verdict |
|---|---|---|---|---|
| Thermal ingest + fusion | Lepton/Boson | €200–1500 | M (~80 h) | ingest side is cheap for us; sensor is theirs |
| RTK precision workflows | RTK pair | €150–400 | M (~60 h) | single-feature; low priority |
| Starlink BLOS ops | Starlink Mini | $249 + sub, **1.1 kg** | S (~16 h, mostly config) | almost no dev work — it is just an IP link. **Document it, don't build for it** |
| CRPA integration | CRPA | €300–3000 | S (~8 h) | nothing to build; **argue against it — P6–P8 cost €0** |

---

## 8. Revised recommendation

Merged with BASE-COMPUTE §6, this is the whole sequence:

| Order | What | Why | Time |
|---|---|---|---|
| **1** | **Merge `feat/visual-geo`** | 19 commits stranded; the `PositionFix`/`PositionFusion` seam is the prerequisite for the entire position stack | — |
| **2** | ANY-DRONE waves 1–2 | the funnel; €0 to the owner | ~2 wk |
| **3** | DEM dependency + C3/C4/C6/C9 cluster | strongest differentiated block; all €0, all Class A, all advisory | ~6 wk |
| **4** | `PositionFusion` N-source generalization (A1+A2) | turns "GPS or nothing" into an honest stack; unlocks P6/P8/P9 as pluggable producers | ~2 wk |
| **5** | C7a spoof detection + M1 mission tasking | both nearly free on top of 3–4 | ~4 wk |
| **6** | **Navigation pack export (§5)** | makes us the supplier of nav data to aircraft we don't control | ~2 wk |
| **7** | C1 video enhancement, B1/B2/B6 | widest reach per hour | ~5 wk |
| **later, gated** | P9 ground TDOA · C2 · C7b · onboard matcher | R&D or Class D or new deliverable — each needs its own decision | — |

**Sources:** [Anubis / Airlogix + Auterion](https://united24media.com/war-in-ukraine/meet-anubis-the-new-long-range-drone-combining-ai-starlink-and-45kg-warhead-already-hunting-russians-18605) ·
[Anubis + Seth-X](https://thedefensepost.com/2026/04/21/ukraine-anubis-seth-x-drone/) ·
[Auterion Skynode](https://auterion.com/product/skynode-x/) ·
[Starlink Mini specs/pricing](https://www.satelliteinternet.com/resources/starlink-mini-review/) ·
[Starlink for drones](https://skyfront.com/learn/starlink-drones) ·
[GNSS-denied navigation survey](https://link.springer.com/article/10.1186/s43020-025-00162-z) ·
[CRPA anti-jam explained](https://infinidome.com/crpa-anti-jamming-antenna/) ·
[ArduPilot Guided Mode](https://ardupilot.org/copter/docs/ac2_guidedmode.html)
