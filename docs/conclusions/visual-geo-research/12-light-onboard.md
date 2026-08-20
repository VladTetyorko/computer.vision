# The LIGHT sub-way — onboard, GNSS-denied visual navigation from a preloaded corridor pack

Scope: navigation approaches where the mission corridor (satellite/aerial tiles, road/building
vectors, DEM strip, sometimes multi-date references) is packaged and pushed to the aircraft
*before* flight, and a cheap onboard computer keeps position by fusing IMU/optical-flow dead
reckoning with periodic absolute fixes against that local pack — never a live map-server call in
flight. This is the "LIGHT" tier: it explicitly excludes heavy onboard SLAM-plus-full-scene-graph
systems and excludes systems that phone home to a ground map server per frame.

Our platform (`drone-link/mavlink`, `station/vision-api`, `cv/cv-service`) is a ground station that
already speaks MAVLink to ArduPilot/PX4 aircraft, streams `GLOBAL_POSITION_INT`/`ATTITUDE` at
~2 Hz, and has a MAVLink-FTP client stub (`Ftp(Duration timeout, int retries)` in
`MavlinkCoreSettings`, `drone-link/mavlink-core`). It does not yet push files to aircraft or ingest
an external position fix. This research asks what a LIGHT sub-way would need on both ends of that
link, and cross-checks vendor claims against our own branch's measured numbers in
`docs/conclusions/visual-geo-research/00-existing-state.md` (`docs/VISUAL-GEO-PLAN.md`), which ran
a heavier version of the same idea (retrieval + geometric verification against downloaded tiles)
and left a specific, well-documented failure signature that every LIGHT candidate below has to
answer for.

---

## 1. Products and systems (2022–2026)

Legend: **M** = marketing/press-only claim, no published methodology or number; **P** = a number
appears in a published paper, DoD test report, or named flight-test result with a stated setting.

| # | System | Org | Year | Method (public) | Compute (public) | Accuracy | Licence / price | M/P |
|---|---|---|---|---|---|---|---|---|
| 1 | Raptor | Vantor (formerly Maxar Intelligence) | 2025 | Live camera vs. preloaded 3-m Precision3D terrain model, proprietary correlation, feeds INS | Runs on "commodity" onboard camera + compute, vendor unspecified | Vendor states **<10 m RMSE** absolute; VINS-fused flight held 35 m horiz / 5 m vert / 0.9 m/s over unstated duration [1][2][5] | Proprietary SaaS, partnered into Saab/AIDC/Anduril systems, no public price | M+P (numbers stated but methodology/test conditions not published) |
| 2 | ViDAR | Sentient Vision Systems | 2022–2025 | Wide-area EO/IR + deep learning, primarily wide-area *search*, not itself a nav-position solution | Edge Autonomy UAV integration, no compute spec public | No GNSS-denied position accuracy published — this is a detection/search sensor, not a positioning system | Proprietary, integrated per-platform | M |
| 3 | Hivemind | Shield AI | 2018–2026 | "AI pilot" stack; GPS-denied nav via tracked visual features / RF signals of opportunity, full autonomy stack (nav+plan+control) | Undisclosed, runs on V-BAT and Nova-class quadcopters | No public accuracy number; "first fully AI-piloted combat drone in GPS/comms-denied ops" (2018, Middle East) | Proprietary, DoD/allied contracts | M |
| 4 | Skynode X/S | Auterion | 2023–2026 | PX4-based flight computer; supports external VIO/SLAM input via standard PX4 `VISION_POSITION_ESTIMATE`, no proprietary vision-nav algorithm bundled | Qualcomm-class SoC on Skynode, integrator supplies the VIO/SLAM node | No accuracy claim — it's an *integration point*, not a shipped visual-nav algorithm | Open PX4 stack + Auterion OS, commercial hardware pricing not public | — (infra, not a nav method) |
| 5 | The Fourth Law TFL-1 | The Fourth Law (Ukraine) | 2023–2026 | Onboard autonomy module for FPV strike drones; GNSS-independent terminal guidance, claims 2–4× mission-success uplift | Edge module, ~10% added unit cost, compute undisclosed | No published accuracy number; success-rate multiplier only | Proprietary, ~50+ Ukrainian units fielded, Axon-backed | M |
| 6 | Bavovna.ai Hybrid INS | Bavovna.ai (Ukraine) | 2023–2026 | AI-compensated inertial nav (RNN drift correction) + "last-mile" CV guidance fusion | Edge inference, RNN model, undisclosed SoC | Vendor: <1% endpoint positioning error; case study claims 99.98% "accuracy" (metric undefined) [13][15] | Proprietary, $2.7M seed (2024) | M |
| 7 | Raybird-3 | Skyeton (Ukraine) | 2023–2026 | Long-endurance ISR platform; markets "GPS-denied" resilience, mechanism not published (assumed INS/AHRS-led, no stated vision-nav algorithm) | Undisclosed | 350,000+ combat flight hours cited, no positioning-accuracy number tied to GNSS-denied mode | Proprietary | M |
| 8 | Altra / HX-2 | Helsing (Germany) | 2024–2026 | Downward camera continuously matches terrain features against a **preloaded digital map** — closest public description to the LIGHT pattern in this document | Onboard compute for HX-2, spec undisclosed | No published metric accuracy; "operates safely and precisely despite jamming" is the only claim | Proprietary, mass-production for Ukraine/EU armies | M |
| 9 | Vector AI | Quantum Systems (Germany) | 2025–2026 | VIO (camera+IMU) for relative nav, absolute correction via onboard mapping ("Receptor AI" optical nav upgrade) | **Dual Jetson Orin SOM** stated explicitly — one public compute spec point in this whole table | No published error number | Proprietary, fielded to Ukraine (3rd Vector order, 2023) | M |
| 10 | VNS01 / GNSS-Denied Navigation Kit | UAV Navigation S.L. (Spain) | 2022–2026 | Visual odometry + pattern ID fused with POLAR-300 AHRS, standard (not exotic) imaging sensor | Compact peripheral module, compute spec not public | Vendor states **<1% error** (of distance travelled, i.e. VO-class drift rate, not absolute fix) [21][22] | Proprietary hardware kit, priced per integration | M |
| 11 | X3 IMU | ANELLO Photonics | 2023–2026 | **Not vision** — SiPhOG optical-gyro IMU, included per prompt as the non-visual alternative/complement | 8 in³ module, PX4/ArduPilot compatible | Manufacturer navigation-grade drift specs (not independently published in these results) | Proprietary hardware, sold direct | M |
| 12 | TERPROM family / radar-TRN research | Airbus/BAE lineage; academic radar-TRN | 1950s–2026 | Classic terrain-referenced navigation: barometric/radar altimeter profile matched to a stored DEM, INS-aided. A 2026 MDPI paper demonstrates a **low-cost automotive-class FMCW radar** doing the ranging instead of a laser altimeter | Automotive radar module (cheap, COTS) + DEM matching filter — directly relevant as a *non-camera* LIGHT precedent | Published paper reports the concept validated on real flight data; specific error numbers in [26] | TERPROM itself is legacy/classified avionics; the radar-TRN paper is open academic work | P (paper) / M (legacy TERPROM) |
| 13 | Vision-Aided Navigation (VAN) / HANA | Honeywell | 2022–2026 | Downward camera correlated against a **compressed, whole-theater preloaded map** — explicitly markets "very low map storage" as a design goal, matching the LIGHT-pack philosophy directly | Demonstrated on AW139 helicopter (low alt) and E170 (high alt) prototypes, compute unspecified | No published quantitative accuracy; qualitative "drift-free" claim only | Proprietary avionics program, ION conference papers exist but abstracts only in these results [36][37] | M |
| 14 | Black Recon / SkyCarrier | Teledyne FLIR Defense | 2025–2026 | Computer vision + dead reckoning for RF-silent/GPS-degraded return-to-platform; visual markers (NIR beacons, AprilTags) for terminal guidance, not corridor-scale nav | Onboard compute unspecified | No GNSS-denied *position* accuracy published (terminal-guidance beacon lock, not corridor nav) | Proprietary | M |
| 15 | EGI-M | Northrop Grumman | 2024–2026 | M-code GPS/INS embedded receiver, jam-resistant — **not visual nav**, included as the "hardened GPS" alternative baseline this document's approach must beat in cost/weight, not accuracy | Line-replaceable avionics unit, fighter/UAS-class | DoD-qualified, no public error number | Military program, classified pricing | M |
| 16 | VNav | Palantir (flown on Red Cat Black Widow) | 2025–2026 | Edge sensor fusion: IMU + optical flow + satellite-imagery reference matching, no additional hardware beyond the drone's existing sensors | Runs entirely on Black Widow's onboard compute (COTS small-UAS class) | **Published flight-test number: mean positional error ~7 m over a 2.7 km simulated recon route** [30][31] — the single most concrete, sourced accuracy claim in this table | Proprietary software, integrated into a U.S. Army program of record | **P** |
| 17 | Shark-M (GNSS-denied variant) | Ukrspecsystems (Ukraine) | 2026 | GNSS-denied launch algorithms + in-flight optical nav matching live feed to preloaded satellite imagery | Undisclosed | No published number | Proprietary, fielded | M |
| 18 | OSCAR | Twist Robotics (Ukraine) | 2026 | Onboard camera matched against **mapped landmarks** (not raw tile correlation) fed directly to autopilot; explicitly markets spoofing/jamming resistance | Undisclosed | No published number | Proprietary | M |
| 19 | FirePoint navigation stack ("7 generations") | Fire Point (Ukraine, makers of FP-1) | 2024–2026 | Iterative in-house GPS-independent terrain-matching navigation across 7 stated hardware/software generations, 200 drones/day production claim | Undisclosed | No published number | Proprietary, state-adjacent Ukrainian production | M |
| 20 | SPRIN-D Funke Challenge winning system | Academic (TU/consortium, ICRA 2026 paper) | 2025–2026 | LiDAR-derived local heightmap matched to a **prior geodata heightmap** via gradient-template matching, fused with odometry in a clustered particle filter — a DEM-strip LIGHT-pack pattern using LiDAR instead of camera | Stated as embedded-platform-feasible; paper is explicit about compute limits being the design driver | **Published, peer-reviewed**: 9 km waypoint nav <25 m AGL, urban/forest/field, first place [35] | Open academic publication (arXiv 2510.01348), code per repo | **P** |
| 21 | Puma VNS | AeroVironment | 2022–2025 | VIO via downward sensors/cameras + onboard compute module, auto-transitions GPS↔visual with no operator input | Dedicated onboard compute module, spec sheet exists but no chip named in these results | Datasheet exists [39] but no accuracy number surfaced in search text | Proprietary, sold as a Puma 2/3 AE / Puma LE kit | M |

**Marketing vs. published split.** Of the 21 entries above, only **#16 (Palantir VNav, ~7 m/2.7 km)**
and **#20 (SPRIN-D winner, peer-reviewed ICRA paper)** carry a number from a described, repeatable
test. Everything else — including the two biggest brand names, Maxar/Vantor Raptor and Honeywell
VAN — publishes accuracy adjectives ("<10 m RMSE" for Raptor is the closest to a number, but no test
methodology, altitude, terrain type, or sample size is public) rather than a reproducible result.
This matches our own project's finding: §12.4 of `00-existing-state.md` treats "Theseus 51.95 m
median/550 km", "Palantir VNav ~7 m/2.7 km" and "Vantor Raptor ~1 m claimed" purely as **accuracy
comparables never independently run**, not as ground truth.

---

## 2. Open-source / research stacks

### 2a. Flight-stack integration points (what the aircraft actually accepts)

| Interface | Stack | What it carries | Rate / constraints |
|---|---|---|---|
| `VISION_POSITION_ESTIMATE` / `ODOMETRY` (MAVLink) → EKF2 | PX4 | Local position + orientation from a companion-computer VIO/SLAM node (typically via MAVROS) | EKF2 only fuses the message types it first sees consistently; needs **30–50 Hz** streaming; delay compensated via `EKF2_EV_DELAY` [40] |
| `GPS_INPUT` (MAVLink) | ArduPilot | A synthetic "GPS fix" (lat/lon/alt/vel/accuracy) injected as if from a real GNSS receiver — the natural wire format for a LIGHT pack's periodic absolute fix | Consumed by `AP_GPS`, selectable per `EK3_SRC` source set |
| `AP_VisualOdom` + `EK3_SRC1..3` source switching | ArduPilot | Continuous relative VO/VIO stream as an EKF3 source, switchable at runtime between GPS/OpticalFlow/ExternalNav (Lua, RC, or MAVLink) | Multiple open GitHub issues around source-switching position jumps (`#27193`, `#27729`, `#15859`) — this is real, unresolved integration friction, not a solved corner [41][42] |
| MAVLink FTP | Both | Generic file push/pull to the autopilot's own filesystem | Our own `drone-link/mavlink-core` module already carries an `Ftp(Duration timeout, int retries)` settings record — built but, per `MODULE.md`, not yet used to push arbitrary payloads such as a map pack |

**Practical read:** ArduPilot's `GPS_INPUT` path is the cleanest LIGHT-tier integration — the
aircraft doesn't need a new EKF source concept, it just gets fed a position "as GPS" at whatever
cadence the onboard fix arrives (need not be 30–50 Hz). PX4's `VISION_POSITION_ESTIMATE` path is
higher-rate and tighter-coupled (designed for continuous VIO, not a slow periodic correction),
which matters for architecture choice in §6.

### 2b. VIO/SLAM stacks (the "keep position cheaply between fixes" layer)

| Stack | Type | 2025 benchmark verdict | License |
|---|---|---|---|
| OpenVINS | Filter-based VIO (MSCKF-family) | Competitive accuracy, efficient; recommended as a light VIO backbone in multiple comparisons [43] | GPLv3/Apache mix, academic-friendly |
| VINS-Fusion | Optimization-based VIO/SLAM | Failures on several datasets in a 2025 unstructured-outdoor benchmark; beaten by Kimera in most cases [43][45] | GPLv3 |
| ORB-SLAM3 | Feature-based visual/visual-inertial SLAM | "Consistently among the most accurate" across five diverse datasets in a Skoltech/Sberbank comparison [43] | GPLv3 (non-commercial-friction for a defense/commercial product) |
| Kimera(2) | Metric-semantic VIO+SLAM | Outperforms VINS-Fusion in most 2025 outdoor tests; heavier (semantic layer) than pure VIO [43][44] | BSD |
| Basalt | Optimization-based VIO | Strong EuRoC results, efficient memory use — good CPU-tier candidate [43] | BSD-3-like (MIT/GPL mixed by component) |
| MSCKF-VIO | Filter-based VIO | Lineage for OpenVINS; lighter-weight, older baseline | BSD-3 |

None of these six is corridor-scale absolute localization on its own — they all drift over km-scale
flights without a periodic external fix, which is exactly the gap a LIGHT map pack is meant to
close.

### 2c. Map-anchored VO / absolute-fix research (the "periodic correction from the pack" layer)

| Work | Year | Approach | Reported result | Note |
|---|---|---|---|---|
| Kinnari, Verdoja, Kyrki — season-invariant GNSS-denied localization [46] | 2022 | CNN descriptor trained to be winter/summer-invariant, matched query frame to georeferenced orthophoto | Major improvement over baseline under high seasonal variation (IEEE RA-L 2022) | Code+data public (Aalto) |
| LSVL (Kinnari follow-up) [47] | 2022 | Same family, scaled to 100 km² | 17.9–51 m RMS with yaw in particle-filter state; 12.6–18.7 m converged over fields/forest | Direct precedent for a particle-filter sequence localizer; our branch cites this exact number in §12.10/§13.6 |
| Goforth & Lucey, "GPS-Denied UAV Localization using Pre-existing Satellite Imagery" [48] | 2019 | CNN representations + joint optimization of adjacent-frame + satellite-map alignment | <8 m average error, 850 m flight at 200 m altitude (ICRA 2019) | Homography-pose literature comparable, cited in our §12.10 |
| UAV-VisLoc dataset [49] | 2024 | 6,742 real drone images / 11 satellite maps across China, varied terrain, metadata incl. date | Dataset only — used as a benchmark by others (e.g., Game4Loc fine-tune: 24.9%→80.2% R@1) | 0.1–0.2 m/px drone, 0.3 m/px satellite |
| FoundLoc [50] | 2023 | VIO + Visual Place Recognition using a **foundation model** (AnyLoc/DINOv2), nadir camera + IMU + preloaded satellite imagery only | Average localization error <20 m, min <1 m under drastic appearance change, no initial-pose assumption | AirLab/CMU, code public |
| SatLoc + SatLoc-Fusion [51] | 2025 | Three-layer fusion: absolute geo-loc via DINOv2, high-freq relative motion via **XFeat**, velocity via optical flow, adaptive confidence weighting | **Deployed on a 6 TFLOPS edge computer, >2 Hz real-time, absolute error <15 m, >90% trajectory coverage** — closest published number to a deployable LIGHT-tier system | New (2025) benchmark + framework, direct architecture precedent for §6 candidate 2 |
| WildNav [52] | 2022 | Photogrammetry-based GNSS-free localization for long-distance, high-altitude flights (not classic short-baseline VO) | Published in "Vision-based GNSS-Free Localization for UAVs in the Wild"; open GitHub (TIERS) | Open source, reusable baseline |
| AnyLoc [53] | 2023 | Training-free universal Visual Place Recognition using DINOv2 features + VLAD/GeM pooling, no VPR-specific training | Up to 4× over prior VPR approaches across urban/aerial/indoor/underwater domains (RA-L 2023) | Our branch measured AnyLoc-vits14 directly: **Recall@1 = 0.025–0.050 on real AU-AIR aerial footage** — far below the paper's cross-domain headline, confirming the aerial-specific literature ceiling (CosPlace 4.3%, EigenPlaces 7.1%, AnyLoc 21.7% per the 2024 aerial-VPR survey cited in our own §12.8) |
| OrienterNet [54] | 2023 | Neural BEV matched against **OpenStreetMap 2D vector data** (not raster tiles) for sub-meter image localization, supervised only by camera pose | CVPR 2023; ground-level (car/bike/pedestrian) training data, not validated for oblique aerial in the cited results | Directly relevant as the OSM-vector matching precedent named in the prompt; no confirmed Jetson benchmark found in this research pass |
| VecMapLocNet [55] | 2025 | UAV localization against **vector maps** (not raster satellite tiles) in GNSS-denied settings | 25.23 ms latency on Jetson Orin — the one confirmed embedded-latency number for vector-map UAV localization | ScienceDirect, closest concrete answer to the prompt's "OrienterNet on Jetson?" question, via a sibling method |
| NCC tile template matching [56] | ongoing/lit. | Normalized cross-correlation between live frame and reference tile; SIMD/SSE2-accelerated on modern CPUs; a re-localization system in this space runs at **~10 Hz overall** | Classical, no learned weights, cheapest possible matcher; brittle to season/lighting | Public method, no license issue |

**Cross-check against our own measured numbers.** Our branch already ran the closest local analogue
to entries above: EigenPlaces/CosPlace/Sample4Geo/AnyLoc retrieval on real AU-AIR aerial footage all
landed at Recall@1 between 0.000 and 0.525 — i.e., global-descriptor ranking alone is **not**
sufficient at corridor scale on real camera frames, matching the "encoder family ceiling is
architectural, not tuning" conclusion already reached in §12.8. FoundLoc's <20 m claim and SatLoc's
<15 m claim both pair the same DINOv2-class retrieval with a *second* signal (VIO or XFeat relative
motion) rather than trusting ranking alone — which is the architectural lesson this document carries
into §6.

---

## 3. Compute budgets

| Platform | Peak AI perf | Power | Price (2026) | Weight (module only) | What runs at what FPS |
|---|---|---|---|---|---|
| Raspberry Pi 5 / CM4/CM5 | No NPU (CPU-only ~vision workloads) | ~3 W idle, ~8.8 W full CPU load [68] | Pi 5 board ~$60–80; CM5 from $45 | ~46 g (Pi 5 board) | NCC/ORB-class classical matching only at useful rate; MobileNetV3 segmentation is a CPU-bound ~1–5 FPS class without an accelerator HAT |
| Raspberry Pi 5 + AI HAT+ (Hailo-8/8L) | 13/26 TOPS (8L/8) | HAT adds ~1.5–2.5 W [62][69] | HAT+ ~$70–120 | HAT ~30 g | YOLOv8s ≈**127.85 FPS** at 640×640 on Hailo-8L (measured, Seeed benchmark) [63]; realistic sustained multi-model budget lower |
| Jetson Orin Nano (Super) | 40 TOPS | 4.5 W idle / 8–12 W typical / 28 W peak [66] | $249–299 | ~140 g module | XFeat/ALIKED(lite)/SuperPoint-lite + LightGlue hold **30 FPS** without sustained thermal throttling on a "LightWeight-Arbiter" deployment; DINOv2-small feasible at reduced rate with TensorRT |
| Jetson Orin NX | 117–157 TOPS | 10 W idle / 14–20 W typical / 40 W peak [66] | $399–599 | ~70×45 mm module | AGX-Orin-class ALIKED/SuperPoint/XFeat+LightGlue combos hit **29–47 FPS monocular / 18–33 FPS stereo** with TensorRT (measured on the larger AGX Orin sibling — NX sits between Nano and AGX) |
| Rockchip RK3588 (Radxa/Orange Pi 5, CM5) | 6 TOPS NPU | NPU alone ~2–3 W at INT4; board power a few W more | Boards ~$80–150 | Board-dependent, ~30–60 g SoM | Cited numbers vary sharply by pipeline: **~55 FPS YOLOv5s@1080p** on one benchmark vs. **8–10 FPS** on another real Orange Pi 5 run at 640 px — a red flag that RK3588 NPU numbers are pipeline/quantization-sensitive, not a stable spec |
| Hailo-8 / Hailo-8L (standalone M.2/USB) | 26 / 13 TOPS | ~2.5 W / ~1.5 W sustained [62] | ~$70 (8L) to ~$150 (8) | ~10–20 g module | YOLOv8n 640×640: **130–160 FPS (8)**, **60–80 FPS (8L)** |
| Google Coral Edge TPU | ~4 TOPS (int8) | Low-single-digit W | USB accelerator ~$60 | ~20 g (USB stick) | Faster than Intel NCS2 for small-input models in a direct comparison [augmented startups]; TFLite-only ecosystem constraint |
| Intel NCS2 / Myriad X | ~1 TOPS int8 measured, 4 TOPS theoretical | ~1 W class | ~$70–90 (now largely EOL) | ~20 g (USB stick) | OpenVINO-only toolchain; slower than Coral for comparable small models |
| Luxonis OAK-D (Myriad X onboard) | 4 TOPS (Myriad X) | Up to 5 W [via USB-C] | ~$199–399 depending on variant | Camera+compute combined, ~90 g class | Onboard stereo depth + NN inference in one unit — relevant because it removes the "separate camera + separate compute" weight/power line entirely |
| Qualcomm RB5 | 15 TOPS (5th-gen AI Engine) | Higher-power SoM class (not FPV-class) | Dev kit ~$600+ | Heavier SoM board, not micro-drone class | Octa-core Kryo 585 + Adreno 650 — general robotics-class compute, oversized for a LIGHT sub-way's stated cheap-compute goal |

**FPV-class weight/power limits, for calibration.** A typical 5-inch FPV quad weighs 0.6–0.8 kg
fully built, leaving only ~0.1–0.3 kg of spare payload for anything beyond the FC/VTX/battery —
which is why most of the fielded Ukrainian systems in §1 run their vision-nav module as a small
add-on board rather than a full Jetson-class SOM; Quantum Systems' dual-Jetson-Orin Vector is a
much larger fixed-wing platform, not an FPV analogue. Micro/mini racing-class airframes draw
50–200 W at hover total, so a companion computer's power draw competes directly with flight time,
not just weight.

**Benchmark-cited models for reference:**

- **DINOv2-small**: no stable ONNX/embedded FPS number surfaced in this research pass; largest
  model in the same family drops from 1995 ms→1281 ms with 4-bit quantization on a CPU-only C++
  engine (`dinov2.cpp`) — small variant would be materially faster but no measured number is public.
- **SuperPoint+LightGlue / XFeat / ALIKED**: 29–47 FPS monocular on Jetson AGX Orin with TensorRT;
  XFeat specifically claims up to 5× speedup over SuperPoint-class detectors via early downsampling
  and 64-D descriptors — the best-fit "cheap matcher" for the Nano/RK3588 tier.
- **ORB**: classical, near-free on CPU, but has no learned robustness to season/lighting change —
  same brittleness class as NCC template matching.
- **NetVLAD**: no fresh 2025–2026 embedded benchmark surfaced; superseded in most recent aerial-VPR
  literature by DINOv2-based retrieval (AnyLoc, FoundLoc, SatLoc all use DINOv2, not NetVLAD).
- **MobileNetV3 segmentation**: 18.2 FPS on Jetson Nano (GPU-accelerated DeepLabV3+MobileNetV3);
  Cityscapes-class accuracy at 659 ms CPU time per frame on a phone-class chip — segmentation is the
  most CPU-expensive candidate layer in this whole stack.

---

## 4. Map pack design precedents

### 4a. Container formats

| Format | Backing | Fit for a LIGHT pack | Notes |
|---|---|---|---|
| MBTiles | SQLite | Best "existing tooling, local use" fit — mature, widely supported, mutable | Default output of Tippecanoe pipelines [70][73] |
| PMTiles | Single flat file, Hilbert-curve directory, byte-range reads | Best for immutable, minimal-server "load once, fly" packs — no SQLite runtime needed onboard | Immutable — any pack update means a full rewrite, which is actually fine for a per-mission pack [70][71] |
| GeoPackage | SQLite (OGC standard) | Good when the pack must carry vector features (roads/buildings) *and* raster tiles *and* DEM in one interoperable, non-proprietary container | Directly requested by the prompt as a vector/raster combined precedent |
| Cloud-Optimized GeoTIFF (COG) | Tiled, internally-pyramided GeoTIFF | Best fit for the **DEM strip** component specifically — native raster-with-overviews, no separate tiling step needed | Not a multi-layer "pack" format by itself — one COG per raster layer |

### 4b. Typical sizes (approximate, from tiling math — not vendor-published, computed from standard
XYZ tile-pixel-count conventions; treat as order-of-magnitude, not a spec)

| Zoom | Ground res. (~equator) | Tiles per linear km of corridor (1 km-wide swath) | Approx. size per km² (JPEG-compressed 256px tiles) |
|---|---|---|---|
| z16 | ~2.4 m/px | ~6–8 tiles | a few MB |
| z17 | ~1.2 m/px | ~12–16 tiles | ~10–20 MB |
| z18 | ~0.6 m/px | ~25–32 tiles | ~40–80 MB |
| z19 | ~0.3 m/px | ~50–64 tiles | ~150–300 MB |

Our own project's shipped default is **z17 plain**, chosen empirically (§12.3: z16 fails
*dangerously* — 0.75–0.96 false-fix rate — while z17 is a measured local optimum on real AU-AIR
footage). That empirical result, not a hardware constraint, is the strongest reason to default a
LIGHT pack to z17 rather than pushing to z19 "for more detail."

### 4c. Tooling

| Tool | Role |
|---|---|
| Tippecanoe | GeoJSON/FlatGeobuf/GeoPackage → vector tiles (MBTiles/PMTiles); zoom-aware simplification [73] |
| gdal2tiles / MapTiler Engine | Raster (ortho/satellite/DTED) → XYZ tile pyramid |
| rio-mbtiles / rio-pmtiles | Rasterio-based Python CLI/plugin for raster tile packaging |
| QGIS | Manual corridor selection, DEM clipping, pack authoring/QA before push |

### 4d. Licensing constraints — the load-bearing table for this section

| Source | Offline-cache / pre-load allowed? | Notes |
|---|---|---|
| Google Maps | **No** — ToS explicitly prohibits pre-fetching, indexing, storing, or offline caching of Maps content outside live API calls [74] | Disqualifying for a preloaded pack, full stop |
| Bing Maps | **No** — ToS does not allow caching or storing content [75] | Same disqualification |
| Esri World Imagery | Conditional — the *standard* World Imagery layer is not for offline export; a separate **"World Imagery (for Export)"** layer exists specifically for this use case [76] | Usable, but requires the correct licensed layer, not the default one |
| Mapbox Satellite | Imagery sourced from Maxar Vivid (z8–18) + Vexcel aerial (z14+, US/Canada/Europe) [77] | Licensing tied to underlying provider contracts — not free-and-clear for redistribution to a fielded aircraft without a commercial agreement |
| Maxar/Vantor direct | Commercial imagery provider, licensing per contract (feeds Esri/Mapbox too) | Same underlying source as #1 in this table's product list — expensive, contract-gated |
| **Sentinel-2 (raw, ESA Copernicus)** | **Yes** — free and open, commercial use allowed, 10 m/px, 5-day revisit, 13 bands | The only fully-open *global* medium-resolution source; too coarse (10 m/px) for z17-class corridor packs but fine for DEM-adjacent context or the "coarse fallback" layer |
| **EOX Sentinel-2 cloudless** | **Yes** — CC BY-SA 4.0, free with attribution | Pre-mosaicked, cloud-free-composited 10 m global basemap — best zero-cost global starting layer, still 10 m/px [78][79] |
| **NAIP (US)** | Yes, US-government open data, ~0.6–1 m/px, US-only coverage | Not usable outside the US |
| **OpenAerialMap** | **Yes** — CC-BY 4.0 (plus per-upload SA/NC variants); crowd-sourced, patchy but sometimes very high resolution (10–30 cm/px, UAV-captured) [80] | Best zero-cost *high*-resolution option where coverage happens to exist; not reliable for an arbitrary corridor |
| **OpenStreetMap roads/buildings/water** | Yes — ODbL, free, global, exactly the vector layer OrienterNet/VecMapLocNet-class matching needs | The obvious free source for the "road network + buildings" vector layer of a pack |
| **National ortho — Ukraine (StateGeoCadastre / NSDI portal)** | Government-held; official orthophoto/DEM production exists via `nsdi.gov.ua`, licensing/access terms not confirmed in this research pass [82] | Would need direct confirmation with the agency before assuming reusability in a fielded pack |
| **National ortho — EU (national mapping agencies, `data.europa.eu`)** | Mixed — many EU national agencies publish open ortho under INSPIRE/open-data mandates, but terms vary by country | Needs per-country license check, not a blanket "EU = open" assumption |

**Bottom line for our platform:** the only sources that are unambiguously safe to bundle into a
pack pushed onto a fielded aircraft, at global scale, with no commercial contract, are **Sentinel-2
/ EOX cloudless (10 m, coarse)**, **OpenStreetMap vectors**, and **OpenAerialMap where it happens to
cover the corridor**. Everything sharper than 10 m/px and globally available (Esri, Mapbox, Google,
Bing, Maxar/Vantor) is either contractually blocked from offline caching outright or requires a
specific licensed export layer and a paid agreement.

---

## 5. Link/bandwidth reality

**Telemetry today, in our own stack.** `drone-link/mavlink`'s shipped defaults stream
`GLOBAL_POSITION_INT` and seven other message types at a **500 ms / 2 Hz** nominal rate over the
primary MAVLink link (per `drone-link/mavlink/MODULE.md`), with heartbeat at 1 Hz. This is the same
order of magnitude ArduPilot/PX4 ship by default (`SRx_` stream-rate params, confirmed via the
Rpanion telemetry-rate guide). That 2 Hz position stream is the **outbound** side of the picture —
what the aircraft already tells the ground.

**Reporting an onboard fix back.** Three options, in increasing integration cost:

1. **`GPS_INPUT` (ArduPilot)** — inject the onboard fix as a synthetic GPS message consumed by
   `AP_GPS`/`EK3_SRC`. Lowest integration cost on the *aircraft* side because ArduPilot already has
   a first-class "external GPS" concept; does not require a companion-computer VIO stream running
   continuously at 30–50 Hz, which matters because a LIGHT pack's absolute fix is inherently
   periodic (matched against a tile, not computed every frame).
2. **`VISION_POSITION_ESTIMATE` / `ODOMETRY` (PX4)** — designed for continuous 30–50 Hz VIO, a
   worse fit for a periodic tile-match correction unless paired with a continuous VIO layer feeding
   the same topic between fixes (this is exactly SatLoc's [51] and FoundLoc's [50] architecture:
   VIO for the 30 Hz layer, tile/place match for the periodic correction).
3. **A custom message / companion-link side-channel** — bypasses the autopilot's own EKF entirely
   and reports position only to the ground station over the existing telemetry radio, letting the
   *ground* side fuse it. Cheapest to build, but abandons the "aircraft trusts its own corrected
   position for guidance" property that makes visual nav useful when the datalink itself is also
   degraded — which is usually the same jamming event that took GNSS away in the first place.

**Pushing a map pack to the aircraft.** Three plausible paths, matched to our platform's actual
capabilities:

1. **Pre-flight over USB/wired** — highest bandwidth, zero risk of a corrupted in-flight transfer,
   but requires physical aircraft access before launch. This is the only option that scales cleanly
   to a z18–19 pack (tens to low-hundreds of MB) without a multi-minute radio transfer.
2. **MAVLink FTP over the existing datalink** — our `drone-link/mavlink-core` module already has an
   `Ftp(Duration timeout, int retries)` settings record (`MavlinkCoreSettings`), i.e. the client
   machinery exists but per `MODULE.md` is not yet wired to push arbitrary files. MAVLink FTP is
   chunked and was designed for small parameter/log/mission files, not bulk multi-MB tile packs —
   feasible for a small z16–z17 corridor slice over a healthy link, painful over a
   degraded/low-bandwidth one.
3. **Companion HTTP/SSH over a secondary datalink** (cellular, dedicated Wi-Fi bridge, SATCOM as
   Skyeton's Raybird now carries) — highest throughput of the wireless options, but adds an entire
   second radio/network dependency that itself may be jammed in the exact scenario the pack exists
   to survive.

**The honest framing:** a LIGHT pack is fundamentally a *pre-flight* logistics problem, not an
in-flight bandwidth problem — every credible precedent in §1 (Raptor, Honeywell VAN's "whole-theater
map... on any platform" framing, Bavovna's edge-resident model) assumes the pack is loaded before
the aircraft loses its link, not streamed during the mission. In-flight MAVLink FTP push should be
scoped as a *contingency/update* path (small deltas, corridor-extension patches), not the primary
loading mechanism.

---

## 6. Recommendation set — three candidate LIGHT-tier architectures

Each candidate is scored on accuracy, compute cost, integration effort against our existing
`drone-link/mavlink` + `vision-api`/`cv-service` stack, and robustness over the specific terrain
classes (open fields, forest) our own testing already measured as hard. The **risk column names the
exact failure mode our branch already hit**, not a generic caveat.

### Candidate 1 — Periodic tile/template correction of a continuous IMU+optical-flow dead-reckon (Honeywell VAN / classic TRN pattern)

- **Mechanism:** cheap optical-flow or wheel-odometry-class dead reckoning between fixes; every
  N seconds, correlate the downward frame against the preloaded tile pack near the current
  dead-reckoned position (NCC or a lightweight learned matcher — XFeat-class), inject the result as
  `GPS_INPUT`.
- **Accuracy:** honestly bounded by dead-reckoning drift between fixes plus tile-match precision;
  Honeywell VAN and the SPRIN-D winner (heightmap-gradient variant of this same pattern) are the
  strongest public precedents.
- **Compute:** lowest tier viable — RK3588 or even Pi 5 + Hailo-8L class, because the correction
  step runs at a low rate (§5), not every frame.
- **Integration effort:** **lowest** against our stack — `GPS_INPUT` is a well-trodden ArduPilot
  path, and our `mavlink-core` FTP client already half-exists for pre-flight pack loading.
- **Robustness over fields/forest:** **this is exactly where our own §12.12 result applies** —
  homogeneous terrain (bare fields, unbroken forest canopy) correctly produces **no confident match**
  rather than a wrong one, provided the matcher is gated the way our shipped `shouldVerify` policy
  is (match-count/inlier-count threshold, not raw similarity score). The named risk for this
  candidate is the one our project already fixed once and must not regress: **an ungated or
  poorly-gated matcher will produce a confident-wrong fix on a homogeneous or aliased tile**
  (§12.13's found real gap) rather than honestly refusing.

### Candidate 2 — VIO (continuous) + foundation-model place recognition (periodic) fused with confidence weighting — SatLoc/FoundLoc pattern

- **Mechanism:** a real VIO stack (OpenVINS or Basalt, §2b) runs continuously for smooth
  short-horizon position; DINOv2-class retrieval against the pack runs periodically for absolute
  correction; a confidence-weighted fusion layer arbitrates (SatLoc's published architecture) [51].
- **Accuracy:** best published number in this whole survey for a deployable system —
  **SatLoc: <15 m absolute error, >90% trajectory coverage, >2 Hz on a 6 TFLOPS edge computer**;
  FoundLoc: <20 m average, <1 m best case.
- **Compute:** Jetson Orin Nano/NX tier — needs both a VIO thread and a DINOv2-class retrieval
  model resident; this is the SatLoc paper's own stated compute budget.
- **Integration effort:** **highest** of the three — needs PX4's `VISION_POSITION_ESTIMATE` path
  (§2a) for the continuous VIO component, not just a periodic `GPS_INPUT` nudge, plus a fusion layer
  that doesn't exist in our stack today.
- **Robustness over fields/forest:** **the named risk is exactly what our own §12.1–§12.9 already
  measured and this document's prompt calls out explicitly** — global-descriptor ranking
  (EigenPlaces/CosPlace/AnyLoc/Sample4Geo, the same DINOv2-lineage retrieval SatLoc/FoundLoc rely
  on) scored Recall@1 between 0.000 and 0.525 on real aerial footage, and the failure driver was
  **not** viewing angle (§12.6/§12.7 explicitly falsified the oblique-angle hypothesis) but
  **sensor/domain gap** — a real consumer camera frame vs. a professionally orthorectified tile.
  SatLoc and FoundLoc's own published numbers are still meaningfully worse (<15 m, <20 m) than
  candidate-1's tile-template pattern's best cases (single-digit meters when it fires at all),
  because retrieval alone is a coarse locator, not a precise one — it needs a second geometric
  verification stage (à la our own homography-pose step, §12.11) to sharpen, which SatLoc/FoundLoc
  do not appear to include.

### Candidate 3 — OSM-vector / semantic road-network matching (OrienterNet/VecMapLocNet pattern)

- **Mechanism:** segment the live frame for roads/buildings/water, match the resulting shape
  fingerprint against a lightweight OSM vector layer in the pack (not a raster tile at all).
- **Accuracy:** OrienterNet reports sub-meter accuracy, but only in ground-level (car/bike/
  pedestrian) training/eval — **no confirmed oblique-aerial validation** in this research pass.
  VecMapLocNet is the aerial-specific sibling with a confirmed 25.23 ms Jetson Orin latency, but no
  absolute-error number surfaced.
- **Compute:** cheapest per-inference of the three once the segmentation model is resident (a
  MobileNetV3-class segmenter, §3) — but segmentation itself is the single most CPU-expensive
  candidate layer measured in §3 (18.2 FPS on a full Jetson Nano GPU, materially slower on RK3588 or
  Pi-class CPU-only).
- **Integration effort:** medium — vector-map matching output is naturally a sparse periodic fix,
  same `GPS_INPUT` integration story as candidate 1, but requires an OSM extract + a trained
  segmentation model neither of which exist in our pack tooling today.
- **Robustness over fields/forest:** **the named risk is structural absence of signal, and our own
  data already measured it directly** — §13.8's Phase 0/A found real drone photos over a homogeneous
  parking-lot-class tile carry ~6× fewer long structural segments than a built-up tile, and our own
  §12.13 found `btn-road-x` (a real farmland+road region) has **zero mapped OSM highways** in that
  specific area — i.e., the vector-map approach fails exactly where there is nothing in OSM to match
  against, which for rural/frontline corridors (fields, forest, unmapped rural roads) is common, not
  rare. This candidate is the *least* proven over the terrain classes this document's brief
  specifically asks about.

### Ranked recommendation

1. **Candidate 1** (periodic tile-template correction of dead reckoning) — lowest integration
   effort, lowest compute, and its core risk (homogeneous-terrain false-fix) is a solved problem in
   our own codebase already, provided the same match-count/inlier gating discipline is carried over.
2. **Candidate 2** (VIO + foundation-model retrieval fusion) — best published accuracy of the three,
   but higher compute and integration cost, and its core risk (global-descriptor ranking failing on
   real oblique/consumer-camera frames due to domain gap, not angle) is the exact failure mode our
   own project spent the most measurement effort on and never fully solved with retrieval alone —
   any adoption should budget for a geometric-verification second stage, not trust ranking alone.
3. **Candidate 3** (OSM-vector matching) — cheapest steady-state compute once running, but the least
   evidenced over rural/homogeneous corridors specifically, and depends on OSM coverage density that
   is uneven exactly where GNSS-denied flight is most likely to happen (contested rural/frontline
   terrain, not mapped cities).

---

## Annotated bibliography

1. FlightGlobal — "Commercial satellite operator Maxar offering GPS-denied navigation solution" (2025). https://www.flightglobal.com/military-uavs/commercial-satellite-operator-maxar-offering-gps-denied-navigation-solution/162664.article
2. GPS World — "Maxar helps accelerate the resilience of Taiwan's UAV industry against GPS interference" (2025). https://www.gpsworld.com/maxar-helps-accelerate-the-resilience-of-taiwans-uav-industry-against-gps-interference/
3. arXiv 2103.14381 — "GNSS-denied geolocalization of UAVs by visual matching of onboard camera images with orthophotos". https://arxiv.org/pdf/2103.14381
4. Businesswire — "Vantor Rebrands from Maxar Intelligence, Unveils AI-Powered Platform" (Oct 2025). https://www.businesswire.com/news/home/20251001760322/en/Vantor-Rebrands-from-Maxar-Intelligence-Unveils-AI-Powered-Platform
5. SpaceNews — "Maxar launches GPS-alternative navigation system for drones" (2025). https://spacenews.com/maxar-launches-gps-alternative-navigation-system-for-drones/
6. GPS World — "Sentient ViDAR sensors successfully integrated on Edge Autonomy UAV" (2023). https://www.gpsworld.com/sentient-vidar-sensors-successfully-integrated-on-edgewater-autonomy-uav/
7. Shield AI — "Hivemind" product page. https://shield.ai/hivemind/
8. Medium/buvcg-research — "Shield AI — Deep Dive". https://medium.com/buvcg-research/shield-ai-deep-dive-8e516dc117a0
9. PX4 User Guide (main) — "Auterion Skynode". https://docs.px4.io/main/en/companion_computer/auterion_skynode
10. Dronecode/PX4 discourse — "GPS denied / Fully indoor Navigation with VIO". https://discuss.px4.io/t/gps-denied-fully-indoor-navigation-with-vio/15707
11. Kyiv Post — "Ukraine's The Fourth Law Secures Investment From Axon to Scale Drone AI" (2026). https://www.kyivpost.com/post/70214
12. TechUkraine — "The AI Co-Pilot: Yaroslav Azhnyuk's The Fourth Law Unlocks a New Era for Drones with TFL-1 Module" (2025). https://techukraine.org/2025/07/22/the-ai-co-pilot-yaroslav-azhnyuks-the-fourth-law-unlocks-a-new-era-for-drones-with-tfl-1-module/
13. TechUkraine — "Bavovna.AI, Ukrainian Drone Navigation Developer, Secures $2.7 Million in Seed Funding" (2024). https://techukraine.org/2024/11/15/bavovna-ai-ukrainian-drone-navigation-developer-secures-2-7-million-in-seed-funding/
14. Bavovna.ai — official site. https://bavovna.ai/
15. FlyAPS case study — "Bavovna AI's RNN AI Model Achieves 99.98% Accuracy". https://flyaps.com/case-studies/bavovna-ai/
16. Wikipedia — "Skyeton Raybird-3". https://en.wikipedia.org/wiki/Skyeton_Raybird-3
17. Helsing — "Altra" product page. https://helsing.ai/altra
18. Helsing — "HX-2" product page. https://helsing.ai/hx-2
19. Quantum Systems — "Discover Vector AI". https://quantum-systems.com/vector-ai/
20. Quantum Systems — "Quantum Systems Unveils Vector AI" (2025). https://quantum-systems.com/blog/2025/03/24/quantum-systems-unveils-vector-ai/
21. UAV Navigation — "VNS01 - Visual Navigation System". https://www.uavnavigation.com/products/navigation-systems/vns01-visual-navigation-system
22. GPS World — "UAV Navigation releases Visual Navigation System for GNSS-denied environments". https://www.gpsworld.com/uav-navigation-releases-visual-navigation-system-for-gnss-denied-environments/
23. ANELLO Photonics — "ANELLO Airborne | GPS-Denied Solution". https://www.anellophotonics.com/x3-imu-airborne
24. Defense Advancement — "Product Spotlight: ANELLO X3 Advanced Optical Gyroscope IMU for GPS-Denied Navigation". https://www.defenseadvancement.com/feature/product-spotlight-anello-x3-advanced-optical-gyroscope-imu-for-gps-denied-navigation/
25. Wikipedia — "TERPROM". https://en.wikipedia.org/wiki/TERPROM
26. MDPI (Drones/Aerospace journal) — "Drone-Based Radar Terrain-Referenced Navigation Using a Low-Cost Automotive-Class FMCW Radar to Enable GNSS-Denied Navigation". https://www.mdpi.com/2673-4591/88/1/11
27. Soldier Systems Daily — "Teledyne FLIR Defense Unveils SkyCarrier Autonomous UAS Launch and Recovery Platform" (2025). https://soldiersystems.net/2025/09/19/teledyne-flir-defense-unveils-skycarrier-autonomous-uas-launch-and-recovery-platform/
28. Interesting Engineering — "Autonomous micro-drones maintain surveillance despite GPS jamming" (Teledyne FLIR Black Recon). https://interestingengineering.com/military/teledyne-flir-black-recon-micro-drone-system
29. Northrop Grumman — "Northrop Grumman Delivers Resilient Airborne Navigation System Resistant to GPS Jamming" (EGI-M). https://news.northropgrumman.com/navigation-systems/northrop-grumman-delivers-resilient-airborne-navigation-system-resistant-to-gps-jamming
30. Palantir Engineering Blog — "The Future of Drone Navigation" (VNav). https://blog.palantir.com/the-future-of-drone-navigation-7236075fdedf
31. Red Cat Holdings IR — "Red Cat Successfully Completes Flight Testing of Palantir's VNav Software on Black Widow Drone". https://ir.redcatholdings.com/news-events/press-releases/detail/198/red-cat-successfully-completes-flight-testing-of-palantirs-vnav-software-on-black-widow-drone
32. The Defense Post — "Ukraine Gives Drones Vision-Based Navigation to Push Past Heavy Jamming" (OSCAR/Twist Robotics, 2026). https://thedefensepost.com/2026/01/29/ukraine-drones-vision-navigation/
33. The Defense Post — "Ukraine's Shark-M Drone Adds GNSS-Denied Flight Capability" (2026). https://thedefensepost.com/2026/07/28/ukraine-shark-m-drone/
34. DroneXL — "Ukraine's FirePoint: 200 Drones Daily, Seven Navigation Generations, Zero GPS Required" (2026). https://dronexl.co/2026/03/09/firepoint-ukraine-200-drones-day-production/
35. arXiv 2510.01348 — "Kilometer-Scale GNSS-Denied UAV Navigation via Heightmap Gradients: A Winning System from the SPRIN-D Challenge". https://arxiv.org/pdf/2510.01348 (project page: https://gnssdenied.github.io/)
36. Honeywell Aerospace — "Vision Navigation for GPS-Denied Environments". https://aerospace.honeywell.com/us/en/products-and-services/products/navigation-and-sensors/navigation-systems/vision-aided-navigation
37. GPS World — "Honeywell launches alternative navigation software to counter threats" (HANA). https://www.gpsworld.com/honeywell-launches-alternative-navigation-software-to-counter-threats/
38. AeroVironment — "AeroVironment Introduces Puma VNS, a Visual-Based Navigation System That Enables GPS-Denied Navigation Across GPS-Contested Environments". https://www.avinc.com/resources/press-releases/view/aerovironment-introduces-puma-vns-a-visual-based-navigation-system-that-enables-gps-denied-navigation-across-gps-contested-environments
39. AeroVironment — Puma VNS datasheet (PDF). https://www.avinc.com/images/uploads/product_docs/Puma_VNS_Datasheet_v04_220912.pdf
40. PX4 Guide (main) — "Visual Inertial Odometry (VIO)". https://docs.px4.io/main/en/computer_vision/visual_inertial_odometry
41. ArduPilot GitHub — `AP_VisualOdom.cpp` source and related source-switching issues (`#27193`, `#27729`, `#15859`). https://github.com/ArduPilot/ardupilot/blob/master/libraries/AP_VisualOdom/AP_VisualOdom.cpp
42. ArduPilot Discourse — "GPS Denied Navigation for long (20km) outdoor UAV missions". https://discuss.ardupilot.org/t/gps-denied-navigation-for-long-20km-outdoor-uav-missions/95277
43. arXiv 2108.01654 / 2107.07589 — "A Comparison of Modern General-Purpose Visual SLAM Approaches" (Skoltech/Sberbank Robotics Lab). https://arxiv.org/pdf/2108.01654
44. arXiv 2401.06323 — "Kimera2: Robust and Accurate Metric-Semantic SLAM in the Real World". https://arxiv.org/pdf/2401.06323
45. Journal of Field Robotics (Wiley, 2025) — "Visual-Inertial SLAM for Unstructured Outdoor Environments: Benchmarking the Benefits and Computational Costs of Loop Closing". https://onlinelibrary.wiley.com/doi/10.1002/rob.22581
46. arXiv 2110.01967 — Kinnari, Verdoja, Kyrki, "Season-invariant GNSS-denied visual localization for UAVs" (IEEE RA-L 2022). https://arxiv.org/abs/2110.01967
47. arXiv 2212.03581 — "LSVL: Large-scale season-invariant visual localization for UAVs". https://arxiv.org/abs/2212.03581
48. CMU Robotics Institute — Goforth & Lucey, "GPS-Denied UAV Localization using Pre-existing Satellite Imagery" (ICRA 2019). https://publications.ri.cmu.edu/gps-denied-uav-localization-using-pre-existing-satellite-imagery
49. arXiv 2405.11936 — "UAV-VisLoc: A Large-scale Dataset for UAV Visual Localization". https://arxiv.org/abs/2405.11936
50. arXiv 2310.16299 — "FoundLoc: Vision-based Onboard Aerial Localization in the Wild". https://arxiv.org/abs/2310.16299
51. MDPI Remote Sensing — "Towards UAV Localization in GNSS-Denied Environments: The SatLoc Dataset and a Hierarchical Adaptive Fusion Framework" (2025). https://doi.org/10.3390/rs17173048
52. arXiv 2210.09727 — "Vision-based GNSS-Free Localization for UAVs in the Wild" (WildNav). https://arxiv.org/pdf/2210.09727 (code: https://github.com/TIERS/wildnav)
53. arXiv 2308.00688 — "AnyLoc: Towards Universal Visual Place Recognition" (IEEE RA-L 2023). https://arxiv.org/abs/2308.00688
54. arXiv 2304.02009 — "OrienterNet: Visual Localization in 2D Public Maps with Neural Matching" (CVPR 2023). https://arxiv.org/abs/2304.02009
55. ScienceDirect — "VecMapLocNet: Vision-based UAV localization using vector maps in GNSS-denied environments" (2025). https://www.sciencedirect.com/science/article/abs/pii/S0924271625001455
56. Scientific.Net — "A Real-Time NCC-Based Template Matching on Modern CPUs". https://www.scientific.net/AMM.764-765.1288
57. GitHub (verlab) — "accelerated_features" (XFeat, CVPR 2024 implementation). https://github.com/verlab/accelerated_features
58. GitHub (cvg) — "LightGlue: Local Feature Matching at Light Speed" (ICCV 2023). https://github.com/cvg/LightGlue
59. NVIDIA Jetson AI Lab — "Benchmarks" archive. https://www.jetson-ai-lab.com/archive/benchmarks.html
60. TinyComputers.io — "Rockchip RK3588 NPU Deep Dive: Real-World AI Performance Across Multiple Platforms". https://tinycomputers.io/posts/rockchip-rk3588-npu-benchmarks.html
61. CNX Software — "Rockchip RK3588's NPU open-source driver performs object detection at 30 FPS" (2024). https://www.cnx-software.com/2024/04/21/rockchip-rk3588-npu-open-source-driver-object-detection30-fps/
62. Hackster.io (AlbertaBeef) — "Edge AI Power Benchmarking — Part 1: Hailo-8, the Reference". https://www.hackster.io/AlbertaBeef/edge-ai-power-benchmarking-part-1-hailo-8-the-reference-ee73df
63. Seeed Studio Wiki — "Benchmark of Multistream Inference on Raspberrypi with Hailo8". https://wiki.seeedstudio.com/benchmark_of_multistream_inference_on_raspberrypi5_with_hailo8/
64. AugmentedStartups — "Movidius NCS vs. Google Edge TPU (Coral) vs. Nvidia Jetson Nano". https://www.augmentedstartups.com/blog/Movidius%20NCS%20(with%20Raspberry%20Pi)%20vs-%20Google%20Edge%20TPU%20(Coral)%20vs-%20Nvidia%20Jetson%20Nano
65. Jaycon — "Top 10 Edge AI Hardware Innovations for 2025" (Qualcomm RB5 spec). https://www.jaycon.com/top-10-edge-ai-hardware-for-2025/
66. EdgeAIStack — "Jetson Orin Nano vs NX: 40 vs up to 157 TOPS — Which Do You Need?" (2026). https://edgeaistack.ai/blog/jetson-orin-nano-vs-orin-nx-2026/
67. Luxonis — "OAK-D" product page. https://shop.luxonis.com/products/oak-d
68. raspberry.tips — "Raspberry Pi Power Consumption 2026: Pi 4, Pi 5 & Zero Tested". https://raspberry.tips/en/raspberrypi-tutorials/raspberry-pi-power-consumption-update-2026-all-models-compared
69. Raspberry Pi Foundation — "Buy a Raspberry Pi AI HAT+". https://www.raspberrypi.com/products/ai-hat/
70. Protomaps docs — "Creating PMTiles" (Tippecanoe). https://docs.protomaps.com/pmtiles/create
71. Atlas — "MBTiles vs PMTiles: Tile Archive Formats Compared". https://atlas.co/comparisons/mbtiles-vs-pmtiles/
72. Corvus Intelligence — "MBTiles and PMTiles for offline military mapping". https://corvusintell.com/blog/field-apps/mbtiles-pmtiles-offline-maps/
73. GitHub (mapbox) — "tippecanoe: Build vector tilesets from large collections of GeoJSON features". https://github.com/mapbox/tippecanoe
74. Google Cloud — "Maps Platform Terms Of Service". https://cloud.google.com/maps-platform/terms
75. Microsoft — "Bing Maps Platform APIs Terms Of Use". https://www.bingmapsportal.com/terms
76. Esri Community — "World imagery licensing" (World Imagery for Export). https://community.esri.com/t5/arcgis-aviation-questions/world-imagery-licensing/td-p/1547259
77. Mapbox Docs — "Mapbox Satellite | Tilesets". https://docs.mapbox.com/data/tilesets/reference/mapbox-satellite/
78. EOX — "The global and cloudless Sentinel-2 map by EOX" (s2maps.eox.at). https://s2maps.eox.at/
79. Euro Data Cube — "EOxCloudless Sentinel-2 10m Global Cloudless Mosaic". https://collections.eurodatacube.com/eoxcloudless-s2-global-mosaic-10/
80. OpenAerialMap — home page. https://openaerialmap.org/
81. HOT (Humanitarian OpenStreetMap Team) — "OpenAerialMap (OAM)". https://www.hotosm.org/en/tools-resources/tech-product-suite/open-aerial-map/
82. EuroGeographics — "State Service of Ukraine for Geodesy, Cartography and Cadastre (StateGeoCadastre)". https://eurogeographics.org/member/state-service-of-ukraine-for-geodesy-cartography-and-cadastre-stategeocadastre/
83. MAVLink Guide — "File Transfer Protocol (FTP)". https://mavlink.io/en/services/ftp.html
84. ArduPilot Dev Docs — "MAVFTP". https://ardupilot.org/dev/docs/mavlink-mavftp.html
85. MAVLink Guide — "MAVLink Common Message Set (common.xml)". https://mavlink.io/en/messages/common.html
86. Rpanion Electronics — "Configuring Telemetry Rates in Ardupilot" (2022). https://www.rpanion.com/blog/2022/03/11/configuring-telemetry-rates-in-ardupilot/

**Internal cross-references (not public URLs, cited throughout §§1–6):**
`docs/conclusions/visual-geo-research/00-existing-state.md` (this branch's own measured retrieval/
verification/homography/sequence-localizer numbers, §§12–13); `docs/VISUAL-GEO-PLAN.md` (the plan
those measurements were taken against); `drone-link/mavlink/MODULE.md` and
`drone-link/mavlink-core/MODULE.md` (our shipped telemetry-rate and FTP-settings facts, §§5).

---

## Open questions

1. **No confirmed accuracy number exists for any fielded Ukrainian system** (Fourth Law, Bavovna,
   OSCAR/Twist Robotics, FirePoint, Shark-M) — every claim in §1 rows 5, 6, 17, 18, 19 is a
   press-release adjective, not a test result. Getting even one real number (via a partner
   introduction, a captured-drone teardown report, or a defense-procurement test summary) would be
   the single highest-value follow-up.
2. **Does PX4's `VISION_POSITION_ESTIMATE` path tolerate a low, irregular update rate** (i.e., a fix
   only every few seconds, as candidate 1 in §6 would produce), or does EKF2's fusion genuinely
   require the 30–50 Hz continuous stream the docs describe? This determines whether candidate 1 is
   PX4-portable at all or ArduPilot-only via `GPS_INPUT`.
3. **What does MAVLink FTP's real achievable throughput look like over a degraded/jammed-adjacent
   link** — the spec supports chunked transfer, but no benchmark of sustained MB/s over a lossy
   telemetry radio was found. This directly sizes the "in-flight corridor-extension patch" fallback
   named in §5.
4. **Is Ukraine's StateGeoCadastre/NSDI orthophoto and DEM data actually licensed for reuse in a
   fielded map pack**, or only for viewing via their portal? §4d flagged this as unconfirmed and it
   is the most locally relevant open licensing question given our platform's likely deployment
   geography.
5. **No embedded (Jetson/RK3588) benchmark for OrienterNet itself was found** — only for the sibling
   VecMapLocNet. Before investing in candidate 3, a direct OrienterNet latency/accuracy measurement
   on aerial (not ground-level) imagery is needed; the existing literature validates it only on
   car/bike/pedestrian viewpoints.
6. **DINOv2-small has no public embedded FPS number** — every benchmark found in this pass measured
   the base/large/giant variants. Given DINOv2 features are the backbone of AnyLoc/FoundLoc/SatLoc
   (candidate 2), a direct small-variant TensorRT/ONNX benchmark on Orin Nano-class hardware is a
   gap worth closing before committing to that architecture's compute budget.
7. **RK3588 NPU numbers vary by 5–6× across sources for the same model class** (§3: 55 FPS vs.
   8–10 FPS for YOLOv5s-class models) — this needs an in-house measurement on our own target board
   rather than trusting any single cited benchmark.
8. **Neither SatLoc nor FoundLoc publishes a false-fix rate** — both report average/median error
   under normal operation but not the rate at which their fusion layer would confidently commit to a
   wrong tile, which is precisely the metric our own project treats as the safety-critical one
   (§12.1's false-fix columns, §12.13's found gaps). Any adoption of candidate 2 should demand this
   number before trusting the architecture at operational scale.
9. **How would a LIGHT pack be versioned/invalidated for multi-date drift** (seasonal foliage
   change, new construction) once loaded pre-flight, given the pack is by design not re-fetched
   in-flight? Kinnari's season-invariant descriptor approach (§46/§47) is the only precedent found
   that addresses this directly, and it was not evaluated for embedded feasibility here.
