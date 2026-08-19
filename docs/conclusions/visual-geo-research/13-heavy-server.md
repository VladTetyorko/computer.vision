# The HEAVY sub-way — station-side, compute-rich visual geolocation that corrects a drone's telemetry track

Scope: the opposite end of the spectrum from `docs/conclusions/visual-geo-research/12-light-onboard.md`
(preloaded corridor pack, cheap onboard/edge compute, never a live map-server call in flight). HEAVY
assumes the station — not the aircraft — does the work: dense or photogrammetric registration of a
drone frame (or a whole flight's worth of frames) against satellite/aerial orthoimagery and a DEM,
fused with MAVLink telemetry in a smoother, running near-real-time or fully post-flight. Compute
lives on `cv/cv-service`'s host (`§6.1` of `01-master-geo-stack.md`: Intel-only, no CUDA, OpenVINO IR
export path, deployed by rsync — a GPU host is possible later but does not exist today).

This document sits downstream of three siblings in this series and does not re-derive their content:
`00-existing-state.md` digests the parked `feat/visual-geo` branch's own measured numbers, including
the concrete cross-modal-gap evidence cited in §1 below (AnyLoc/DINOv2 retrieval: correct tile top-1
in 0/12 evaluated frames, median rank 14, against Esri World Imagery z17 tiles); `10-classical-registration.md`
and `11-deep-crossview-vpr.md` cover the *onboard/light-tier* framing of many of the same matchers
(re-ranking a discrete satellite-tile gallery); `01-master-geo-stack.md` is the grounding document for
where either an onboard fix or a station correction plugs into the shipped `Telemetry`/`GeoProjection`
model and names the exact extension points reused in §7. Every accuracy/licence/runtime claim below
is tagged with its confidence: **verified** (fetched from the primary source this pass), or flagged
where a source could not be reached and the claim rests on established background knowledge instead.
No code is proposed here — this is a literature and landscape survey with a recommendation, per the
scoping request.

---

## 1. Dense / learned matchers for frame↔ortho registration

| # | Matcher | Year | One-liner | CPU/OpenVINO viability | Licence |
|---|---|---|---|---|---|
| [1] RoMa | 2024 | DINOv2-coarse + ConvNet-fine dense matcher, transformer decoder predicts multimodal anchor probabilities; +36% on WxBS benchmark | GPU-class (DINOv2 backbone); no OpenVINO port found | **MIT** (verified) |
| [2] RoMa v2 | 2025 | DINOv3-based successor, 1.7× faster, new SOTA dense matching ("harder better faster denser") | Still GPU-oriented; no OpenVINO port found | Repository license not independently re-checked this pass (same author/org as [1]) |
| [3] DKM | 2023 | Gaussian-Process global matcher + depthwise-kernel warp refinement; pose AUC@5°/10°/20° = 60.5/74.5/84.2 (beats [6]'s 57.2/72.1/82.9 on the same benchmark) | GPU-class | **MIT** (verified) |
| [4] LoFTR | 2021/2022 | Detector-free coarse-to-fine transformer, semi-dense, the field's baseline | Community ONNX ports exist (historically TensorRT-focused, e.g. `Kolkir/LoFTR_TRT`); no maintained OpenVINO benchmark found | **Apache 2.0** (verified) |
| [5] EfficientLoFTR | 2024 | Semi-dense matching at "sparse-like speed" — LoFTR's near-real-time successor | Same ONNX caveat as [4]; not independently OpenVINO-benchmarked this pass | Not independently re-checked (same org as [4], presumed Apache 2.0) |
| [6] ASpanFormer | 2022 | Flow + pixel-uncertainty-guided adaptive attention span; official repo is Apple Research | GPU-class | Not independently re-checked (repo license shown as "Other") |
| [7] DUSt3R | 2024 | Pointmap regression — uncalibrated stereo/3D reconstruction with no known camera parameters | GPU-class, transformer-heavy | **CC BY-NC-SA 4.0 — non-commercial** (verified) |
| [8] MASt3R | 2024 | DUSt3R + local-feature head + metric pointmaps + scalable global alignment | GPU-class | Same non-commercial license family as [7] |
| [9] MASt3R-SfM | 2024 | Extends MASt3R into a fully-integrated, scalable SfM pipeline (global alignment + foundation-model image retrieval) | GPU-class | Same license family as [7]/[8] |
| [10] VGGT | 2025 | Feed-forward transformer: camera params + depth + pointmaps + 3D tracks from 1–many views, reconstructs in <1 s; CVPR 2025 Best Paper | GPU-class; no OpenVINO port found | Custom **"VGGT License"** — permissive grant, but subject to Meta's Acceptable Use Policy; not a standard OSI license (verified) |
| [11] MapAnything | 2025 | Unified feed-forward metric-3D reconstruction with optional geometric priors (intrinsics/poses/depth); one pass covers uncalibrated SfM, MVS, mono depth, localization | GPU-class | Not independently re-checked this pass |
| [12] LightGlue (+ SuperPoint/DISK/ALIKED) | 2023 | Fast learned sparse matcher, ships pretrained weights for four detector families | **Mature, actively maintained ONNX export with explicit OpenVINO execution-provider support** (`fabio-sim/LightGlue-ONNX`, also `colmap/LightGlue-ONNX`) — best fit for an Intel-only station today | **Apache 2.0** (verified) |
| [13] XFeat | 2024 | Real-time **CPU-native** sparse feature extraction on VGA images — faster than SIFT on CPU, accuracy comparable to SuperPoint; explicitly designed for embedded deployment | CPU by design (the whole point of the paper); community ONNX ports exist (`DavideCatto/XFeat-ONNX`, others) | **Apache 2.0** (verified) |
| [14] OmniGlue | 2024 | DINOv2-guided matcher with keypoint-position-guided attention, explicitly designed for generalization to *unseen* domains | GPU-class (DINOv2 guidance) | **Apache 2.0** (verified) |
| [15] GIM | 2024 | Self-training framework using 50 h of internet video for a single generalizable matcher; +6.9–18.1% zero-shot vs. baselines | Architecture-dependent (wraps an existing matcher backbone) | **MIT** (verified) |
| [16] MatchAnything | 2025 | Universal **cross-modality** matching via large-scale pretraining on synthetic cross-modal pairs; same weights generalize across 8+ unseen cross-modality tasks | Architecture-dependent (fine-tunes RoMa/ELoFTR-class backbones) | Not found in repo (no `LICENSE` file at time of fetch) — verify before use |
| [17] Kornia | ongoing | Differentiable CV library; ships and maintains LoFTR + LightGlue + AdaLAM as an *integration layer*, not itself a matcher | CPU/GPU per wrapped model | **Apache 2.0** (verified) |

**Cross-modal robustness — the load-bearing question for this whole doc.** [16] MatchAnything is
the strongest evidence found that dense matchers *can* be made appearance-insensitive across a large
domain gap: fine-tuned with its synthetic cross-modal pretraining, RoMa gains up to +76.9% on medical
CT–MRI registration and EfficientLoFTR gains over +400% on liver CT–MR, with the *same* network
weights also generalizing to thermal-visible and vector-map matching. None of this was demonstrated
specifically on oblique-drone-to-nadir-satellite pairs, though — that is a gap, not a confirmed
capability. Separately, the UAV↔satellite cross-view *retrieval* literature ([18]–[19], University-1652
benchmark) establishes that the domain gap between oblique low-altitude drone imagery and nadir
satellite tiles is real and severe even for global descriptors, which is exactly the failure mode this
project's own parked branch measured directly: `00-existing-state.md` records AnyLoc/DINOv2-class
retrieval against Esri World Imagery z17 tiles landing correct top-1 in **0 of 12** evaluated frames
(median rank 14, recall@5 in [0.87, 1.0]) — the ranking, not the recall, is what breaks. [1]–[6] and
[12]–[15] are all trained on MegaDepth/ScanNet-style ground-level or oblique-to-oblique pairs, **not**
oblique-to-nadir remote-sensing pairs, so their raw off-the-shelf accuracy on our exact registration
task is unverified and must be benchmarked in-house before being trusted; ortho-rectifying the drone
frame first (using telemetry + a DEM, turning the problem into nadir-vs-nadir) or MatchAnything-style
fine-tuning are the two credible ways to close that gap, not blind deployment of a ground-scene matcher.

**ONNX/OpenVINO viability, concretely, for an Intel-only station.** [12] LightGlue paired with any of
its sparse detectors has the most mature path onto our exact hardware today — `fabio-sim/LightGlue-ONNX`
explicitly documents an OpenVINO execution provider. [13] XFeat is CPU-native by design and has
community ONNX ports; XFeat+LightGlue is the lightest viable pipeline for near-real-time use with zero
GPU dependency. [4]/[5] LoFTR/EfficientLoFTR have community ONNX exports but no OpenVINO benchmark
found in this pass — treat as "needs in-house validation," not "known-good." The dense/heavy tier
([1]–[3], [7]–[11]) is transformer-heavy on DINOv2/v3 backbones, uniformly GPU-oriented, and **no
OpenVINO port was found for any of them** — running these on the current Intel CPU-only host would
almost certainly be too slow for near-real-time work; they belong in a post-flight batch pass, or wait
for a GPU host.

---

## 2. Photogrammetric pipelines for post-flight correction

| # | Pipeline | Year | One-liner | Accuracy | Runtime | Licence |
|---|---|---|---|---|---|---|
| [20] COLMAP | ongoing | General-purpose SfM + MVS toolkit; the de facto open-source reconstruction baseline (12.5k★) | Reference-quality reconstructions, no single published RMSE (task-dependent) | Incremental reconstruction — the traditionally slow step [22] replaces | Custom permissive license ("Other" on GitHub; historically BSD-style with attribution clauses) |
| [21] GLOMAP | 2024 | Global (non-incremental) SfM; **now deprecated as a standalone project and merged into COLMAP as its "global" mapper** | On-par or superior reconstruction quality vs. COLMAP's incremental mapper (self-reported) | **1–2 orders of magnitude faster** than incremental COLMAP (self-reported) | **BSD 3-Clause** (verified, ETH Zürich) |
| [22] OpenMVG | ongoing | "open Multiple View Geometry" library — SfM primitives, camera models, robust estimators (6.5k★) | Library, not a turnkey pipeline — no single accuracy number | Library-speed, not benchmarked as a pipeline here | **MPL 2.0** (verified) |
| [23] OpenSfM | ongoing (Mapillary-originated) | Python SfM pipeline; integrates external GPS/accelerometer measurements for geographic alignment and robustness | Not independently benchmarked this pass | Not independently benchmarked this pass | **BSD-style** (verified) |
| [24] OpenDroneMap (ODM) | ongoing | Command-line toolkit: drone/balloon/kite imagery → point clouds, textured 3D models, **georeferenced orthophoto GeoTIFFs, DEMs**; accepts GCP files; accepts video with GPS-subtitle sidecars (6.4k★) | Not independently benchmarked this pass (task-, GCP-, and overlap-dependent) | CPU or GPU-accelerated | **AGPL-3.0** (verified) |
| [25] WebODM | ongoing | Web UI + orchestration layer on top of ODM, "commercial-grade" processing UX (4.1k★) | Same engine as [24] | Same engine as [24] | **AGPL-3.0** (verified) |
| [26] Pix4Dmapper / Pix4Dmatic | commercial | Proprietary drone photogrammetry suite — automatic aerial triangulation, camera self-calibration, orthomosaic/DEM output; widely used as the *reference* commercial baseline in academic UAV-mapping papers (e.g. [30]) | Vendor-reported, not independently verified this pass | Proprietary desktop/cloud | Proprietary, per-seat/subscription |
| [27] Agisoft Metashape | commercial | Proprietary SfM/MVS suite, direct COLMAP/ODM competitor, common in survey-grade UAV mapping workflows | Vendor-reported, not independently verified this pass | Proprietary desktop | Proprietary, perpetual/subscription tiers |
| [28] hloc (Hierarchical-Localization) | ongoing | Toolbox wrapping COLMAP/`pycolmap` around modern learned features (incl. [4],[12]) for 6-DoF visual localization — SfM map build + query-image localization in one framework | Localization-benchmark-dependent, not a single number | Feature-detector-dependent | **Apache 2.0** (verified) |

**GCP-free / satellite-control georeferencing landscape.** Two concrete, verified research threads
directly attack "accuracy without physical ground control points": [29] "Deep Learning-Based UAV
Aerial Triangulation without Image Control Points" (Zhong, Li, Qin, Zhang, 2023) replaces the
traditionally high-mismatch-rate manual/classical tie-point step with a SuperPoint-class learned
matcher, and [30] DeepAAT (Chen, Li, Li, Yang, Dong, 2024) reports a purpose-built network for
Automated Aerial Triangulation that is "hundreds of times" faster than incremental AAT while
targeting the same control-point-light goal. Neither paper uses *satellite imagery itself* as the
control source, though — they eliminate physical GCPs by making tie-point matching between the
drone's own images more robust, not by tying the reconstruction to an external absolute reference.
**No paper matching the specific phrasing "satellite-aided bundle adjustment" or a method that
explicitly ties a drone bundle adjustment to satellite-imagery-derived absolute control was found in
this research pass** — this is a genuine literature gap (or a terminology mismatch that needs a
differently-worded follow-up search), logged in Open Questions. The practical implication for our
pipeline: getting absolute (not just self-consistent) geolocation out of ODM/OpenSfM/COLMAP without
physical GCPs today means supplying *automatically detected* correspondences between the SfM
reconstruction and a georeferenced ortho/DEM as synthetic GCPs — exactly the role [1]–[17]'s
frame↔ortho matchers would play if fed into ODM's/OpenSfM's existing GCP-file format, rather than a
published end-to-end "satellite-aided BA" method that does this natively.

---

## 3. Sequence estimation / telemetry fusion

| # | Tool / method | Year | One-liner | Licence / status |
|---|---|---|---|---|
| [31] GTSAM | ongoing | Factor-graph / sensor-fusion library (Georgia Tech, Dellaert lab); iSAM2 incremental smoothing, native GNSS/IMU/vision factor support — best structural fit for the sliding-window smoother in §7 | **BSD** (verified) |
| [32] g2o | ongoing | General graph optimizer for SLAM/bundle adjustment; less built-in sensor-fusion tooling than [31], widely used as a lighter baseline | Mixed BSD/GPL depending on module (not independently re-verified this pass) |
| [33] Ceres Solver | ongoing | General nonlinear least-squares solver (Google); the optimization backbone underneath [20] COLMAP's bundle adjustment | New BSD |
| [34] symforce | 2022 | Symbolic computation + code generation for factor graphs (Skydio); GTSAM-compatible factor-graph formulation, autogenerates fast residual code — built by a drone company, a notable precedent | **Apache 2.0** (verified) |
| [35] MASt3R-Fusion | 2025 | Integrates [8] MASt3R's feed-forward pointmap regression with IMU + GNSS via a hierarchical, metric-scale SE(3) factor graph; real-time sliding-window **and** global optimization | No public code repository confirmed this pass — design pattern reference, not yet a drop-in dependency |
| [36]/[37] Kinnari et al. | 2021/2022 | GNSS-denied UAV geolocalization by matching onboard imagery to orthophotos: orthorectifies under a local-planarity assumption, proposes a match-goodness score; follow-on **LSVL** adds season-invariant CNN features for large-scale (100 km²) matching, converging to **12.6–18.7 m** average error within 23–44 particle-filter iterations | Academic, ICAR 2021 / IEEE RA-L 2022 |
| [38] Robust kernels — Huber/Cauchy | — | Standard down-weighting loss functions for outlier residuals, built into [31]/[32]/[33] natively | Built-in, no separate licence |
| [39] Dynamic Covariance Scaling | 2014 | Zach, "Robust Bundle Adjustment Revisited" (ECCV 2014) — a robust-kernel reformulation for bundle adjustment/pose-graph outlier rejection | Academic |
| [40] Switchable Constraints | 2012 | Sünderhauf & Protzel, "Switchable constraints for robust pose graph SLAM" (IROS 2012) — lets the optimizer itself decide whether a constraint/measurement should be trusted | Academic |
| [41] UAV-LiDAR boresight alignment | 2022 | Gopinath, Hijazi, Collins, Lemons, Schultz-Fellenz, Bent, Hijazi, Riemersma — globally-optimal boresight-misalignment estimation formulated as mathematical optimization, open-source multi-threaded/multi-machine implementation. LiDAR-specific, but the methodology transfers directly to camera/gimbal boresight self-calibration | Academic + open-source code |
| [42] Terrain-referenced navigation particle filter | 2026 | Park & Bang — contour-seeking proposal-density particle filter for TRN, addresses particle degeneracy under multimodal terrain likelihoods; directly relevant to gating an AGL/DEM consistency check | Academic |
| [43] DroidCalib | 2023 | Hagemann, Knorr, Stiller — extends a DROID-SLAM-style architecture with a self-calibrating bundle-adjustment layer; estimates intrinsics (pinhole/unified/focal-only) from monocular video with **no calibration target** | **AGPL-3.0**; repo archived Jan 2025, "research prototype, not maintained" (verified) |
| [44] GeoCalib | 2024 | Single-image calibration combining a differentiable Levenberg–Marquardt geometric-optimization layer with learned up-vector/latitude priors ([45]); estimates intrinsics **and** gravity direction from one frame | Academic + open-source (`cvg/GeoCalib`) |
| [45] PerspectiveFields | 2023 | Predicts per-pixel up-vector + latitude maps for robust single-image calibration; the geometric prior [44] refines | Academic |
| [46] WildCamera | 2023 | "Tame a Wild Camera" — estimates intrinsics from monocular images with no checkerboard, via an "incidence field" (rays between 3D points and pixels) | Academic |

**Composing this into a telemetry-correction pipeline.** The shape all of the above converges on is a
sliding-window factor graph ([31] GTSAM, or [34] symforce if code-generated residual speed matters
more than GTSAM's larger ecosystem) with a pose node per keyframe. Factors: (a) MAVLink GNSS/IMU
priors, soft and weighted by the flight controller's own EKF covariance where available; (b) visual
absolute-position "fixes" from §1's matchers, converted to a geodetic 3D point via §4's DEM ray-cast,
robustly down-weighted with [38]/[39]/[40]-class kernels so a single bad match cannot yank the whole
window; (c) an AGL-consistency factor, comparing telemetry AGL against DEM elevation at the matched
ground point (§4); (d) a boresight/gimbal offset modeled as a slowly-time-varying calibration node,
solved jointly in the style of [43]/[44] rather than trusted blindly from the aircraft's own gimbal
telemetry — a wrong boresight biases every fix in a *correlated* way that a naively-designed per-frame
filter would otherwise misread as position noise. [35] MASt3R-Fusion is the closest published
precedent for exactly this shape of system (feed-forward geometry + IMU/GNSS in one hierarchical
graph) and is the strongest signal that this is a proven pattern, not a speculative one — see §7.

---

## 4. Ground-target / per-pixel geolocation done right

| # | Method | Year | One-liner | Reported error / accuracy | Licence (if software) |
|---|---|---|---|---|---|
| [47] Wildes, Hirvonen, Hsu, Kumar, Lehman, Matei, Zhao — "Video Georegistration: Algorithm and Quantitative Evaluation" | 2001 (ICCV) | The field's foundational paper: registers video frames to a georeferenced reference image/terrain model with a quantitative accuracy evaluation | Paper-reported, not independently re-measured this pass | Academic (DOI 10.1109/ICCV.2001.937646) |
| [48] Pritt, Wright, LaTourette — "Error propagation for DEM-based georegistration of motion imagery" | 2011 (AIPR) | Formal error-budget analysis for **DEM-based ray-cast georegistration** — exactly the "DEM ray-casting error budget" ask for this section | Paper-reported error-propagation model, not independently re-measured this pass | Academic (DOI 10.1109/AIPR.2011.6176342) |
| [49] Pritt & LaTourette — "Automated georegistration of motion imagery" | 2011 (AIPR) | Automated (non-manual) pipeline for georegistering aerial motion imagery — the closest verified match to the "fast geo-registration of aerial video" ask | Paper-reported, not independently re-measured this pass | Academic (DOI 10.1109/AIPR.2011.6176343) |
| [50] Pritt & LaTourette — "Stabilization and georegistration of aerial video over mountain terrain by means of lidar" | 2011 (IGARSS) | Extends the same lineage to mountainous/non-flat terrain using LiDAR-derived elevation — directly relevant to the DEM-consistency question over non-trivial terrain | Paper-reported | Academic |
| [51] LaTourette & Pritt — "Dense 3D reconstruction for video stabilization and georegistration" | 2012 (IGARSS) | Uses dense 3D reconstruction (not just a flat-DEM ray-cast) to stabilize and georegister video | Paper-reported | Academic |
| [24]/[23] ODM / OpenSfM georeferencing internals | ongoing | Both consume a plain-text GCP file (`geo.txt`-style: image, pixel coords, geodetic coords) to anchor an otherwise-relative reconstruction to absolute coordinates — the same file format §2's synthetic/automated GCPs would need to target | See §2 | See §2 |

**DEM ray-casting error budget, in the shape our own kernel already has half of.** Per-pixel
geolocation is: project a ray from the camera through a pixel using intrinsics + pose, intersect it
with a DEM (SRTM/Copernicus/FABDEM, §5), return the intersection's geodetic coordinates. [48]'s
error-propagation model is the right reference for quantifying how pointing-angle error, altitude
error, and DEM vertical error each contribute to horizontal ground-position error — critically, this
error grows sharply as depression angle approaches the horizon (this project's own `GeoProjection`
javadoc already documents the identical failure mode for its own boresight-only math: at 3 m AGL and
a 1° depression angle, ground range ≈ 172 m with d(range)/dθ ≈ 9.8 m per 0.1° of angular error — see
`01-master-geo-stack.md` §2.1). Refinement after an initial ray-cast — matching a small patch of local
orthoimagery around the coarse ray-cast estimate to pull the fix onto an actual visible ground feature
— is the natural second stage, and is exactly what §1's matchers are for once a rough search window is
established from the ray-cast. No paper describing "real-time frame-to-map georegistration" newer than
2023 with a directly-transferable published accuracy number was found in this pass beyond the
UAV↔satellite retrieval literature already covered in §1/[18]/[19] — this is flagged as an open
question rather than asserted.

---

## 5. Data and services

### 5a. Satellite / aerial imagery

| # | Source | Resolution / revisit | Licence (server-side use) | Ukraine coverage / notes |
|---|---|---|---|---|
| [52] Esri World Imagery + Wayback | Mixed, commonly ~0.3–1 m in covered urban areas; Wayback = time-versioned archive to 2014 | ArcGIS/Living Atlas basemap terms, tied to an ArcGIS account/subscription — **not** an unrestricted bulk-download-and-retain right (detail page unreachable this pass; hedge accordingly) | Not independently checked |
| [53] Google Maps Platform tiles / Photorealistic 3D Tiles | Global, high-res varies | Standard Maps Platform ToS restricts caching/bulk reuse beyond normal tile-serving; **not suited to a self-hosted automated matching backend** without an enterprise agreement (verified: page describes the tile format but not caching terms — full ToS not fetched) | Not independently checked |
| [54] Bing Maps imagery API | Global, varies | Microsoft's standard terms similarly restrict caching/bulk extraction (background knowledge, not independently fetched this pass) | Not independently checked |
| [55] Mapbox Satellite | Global to z16 (1–2 m), regional to z18 (0.6–0.3 m), select coverage z21+ (7.5 cm+); sourced from NASA MODIS (low z), Maxar + Landsat (mid z), Maxar Vivid (z13–16), **Vexcel aerial** in NA/Europe + open aerial data (z16+) | Attribution required; caching/offline ToS governed by the general Mapbox ToS, not detailed on the tileset reference page (verified: source/resolution breakdown fetched directly) | Not independently checked |
| [56] Maxar Vivid / SecureWatch / MGP | Vivid = curated cloud-free basemap mosaic; SecureWatch = near-daily-refreshed high-res tasking/archive; MGP = API platform bundling both | Commercial, licence-tiered (maxar.com unreachable this pass — description rests on background knowledge, flagged unverified) | **Verified concrete fact**: per Wikipedia's Maxar Technologies article, in early March 2025 the Trump administration temporarily restricted Ukraine's access to a U.S. intelligence program managed by Maxar Intelligence, and Maxar shut down that Ukraine imagery access as a result. Separately, Maxar openly published imagery of the 40-mile Russian convoy near Kyiv in March 2022 for press/OSINT use — both a "helped" and a "revoked" episode in the same conflict |
| [57] Planet SkySat / PlanetScope | PlanetScope ~3–5 m, near-daily global revisit; SkySat ~50 cm, sub-daily tasking (background knowledge, not independently fetched this pass) | Commercial subscription; reduced-terms research/education program exists | Not independently checked |
| [58] Airbus OneAtlas (Pleiades/SPOT) | Pleiades ~30–50 cm, SPOT ~1.5 m (background knowledge, not independently fetched this pass) | Commercial, European alternative to Maxar/Planet | Not independently checked |
| [59] Sentinel-2 (Copernicus Data Space Ecosystem) | 10/20/60 m depending on band, ~5-day revisit (4-satellite constellation, 2C launched Sept 2024) | **Free and open data policy**, explicitly "free-of-charge to all users and the public" (verified) | Free bulk download + caching explicitly permitted — the safest default for an Ukraine-deployed offline-cached station, though 10 m resolution is too coarse for fine pixel-matching against drone footage; useful as a coarse sanity/change-detection layer |
| [60] EOX cloudless (s2maps.eu → cloudless.eox.at) | Sentinel-2-derived global cloudless basemap, ~10 m | Free for non-commercial/education with attribution; commercial licence required for commercial server-side use (background knowledge — redirect confirmed, detail page not independently fetched this pass) | Not independently checked |
| [61] OpenAerialMap | Very-high-res, crowdsourced drone/aerial imagery; per-image licence set by uploader (typically CC-BY family) | Free, but patchy/inconsistent coverage (background knowledge, not independently fetched this pass) | Some crowd-sourced coverage of conflict-affected areas globally exists but Ukraine coverage is patchy/uncoordinated — not a dependable primary layer |
| [62] NAIP | ~0.6–1 m, US-only (background knowledge, not independently fetched this pass) | **Public domain** (US government work) | Not relevant to Ukraine; useful free high-res source for US-based development/testing only |

### 5b. DEM / DSM / vector

| # | Source | Type | Resolution / accuracy | Licence |
|---|---|---|---|---|
| [63] Copernicus DEM GLO-30 | DSM | 30 m (also GLO-90 at 90 m), global | Free under the Copernicus DEM licence (existence verified via `spacedata.copernicus.eu`; exact licence text not re-fetched this pass — commonly cited as broadly permissive incl. commercial use since the 2021 update, **verify before committing**) |
| [64] SRTM | DSM | 30 m (US)/90 m elsewhere, ~16 m absolute vertical accuracy, 2000 acquisition, voids in mountains/forest (background knowledge, not independently fetched this pass) | Free / public domain (NASA/USGS) — largely superseded by [63] for new work but a useful cross-check |
| [65] FABDEM | DEM (forest+building bias removed) | 30 m (1 arcsec); **FABDEM+** extends to 10 m in 30+ countries with LiDAR coverage (10M km²); independent validation found it roughly halves mean vertical error vs. [63] in forested areas and reduces it ~⅓ in built-up areas (verified via `fathom.global`) | Commonly cited as **CC BY-NC-SA 4.0** (non-commercial) — **not independently confirmed this pass** (fetch timeout), verify before commercial use |
| [66] ALOS World 3D (AW3D30) | DSM | 30 m (1 arcsec), JAXA/ALOS | **Free for both commercial and non-commercial use** under JAXA's standard terms, requires registration (verified via `eorc.jaxa.jp`) |
| [67] Google Photorealistic 3D Tiles | DSM + texture mesh | Global | ToS on caching not detailed on the overview page; presumed similarly restrictive to [53]'s other Google Maps Platform terms (verified: format/capability description only, not the binding ToS) |
| [68] OSM Buildings | Vector footprints (+ occasional height/levels tags) | Patchy tag coverage (background knowledge) | ODbL |
| USGS 3DEP / national LiDAR portals | Point cloud | High-res, **US-only** | No Ukraine coverage found or expected — not applicable to our stated area of interest |
| [69] OpenStreetMap (via Overpass API / Geofabrik extracts) | Vector roads/buildings | Global, variable completeness | **ODbL** — share-alike triggers only if a *derivative database* is redistributed; using OSM as an internal reference/matching layer without redistributing it is generally covered by ODbL's "produced work" exception (background knowledge, not independently re-fetched) |
| [70] Overture Maps Foundation | Vector (Places, Buildings, Transportation, Divisions, Addresses) + GERS stable IDs | Aggregates OSM + commercial partner contributions | **Mixed by theme** (verified via `docs.overturemaps.org`): Buildings/Transportation/Divisions/Base = **ODbL**; Places = **CDLA Permissive 2.0 + Apache 2.0**; Addresses = various permissive (CC BY 4.0/CC0/OGL) — notably *not* uniformly cleaner than raw OSM, contrary to common assumption |

**Ukraine-specific availability and offline-caching legality.** [59] Sentinel-2, [63] Copernicus DEM,
and [69] OSM are the safe defaults for an Ukraine-deployed, offline-cached station: their licences
explicitly or generally permit bulk download and caching, and their coverage/currency is unaffected
by the war. Commercial high-resolution sources are the opposite case, and this is not hypothetical:
[56]'s verified Maxar/Ukraine episode — a *specific, documented instance* of a commercial imagery
programme being restricted for this exact theatre following a change in U.S. policy in March 2025 —
is concrete evidence against architecting the heavy-tier reference-imagery layer around a single
commercial vendor as the sole high-resolution source. Cloud cover (especially autumn/winter) also
degrades Sentinel-2/optical revisit reliability generally, independent of the war. [61] OpenAerialMap
has some crowd-sourced coverage of conflict-affected areas globally, but Ukraine coverage is patchy
and uncoordinated, not a dependable base layer. **Practical implication:** the reference-imagery layer
should be pluggable — an open default ([52] Esri Wayback / [59] Sentinel-2 / [60] EOX cloudless) with
a commercial vendor ([56]/[57]/[58]) as an optional paid upgrade — so the pipeline degrades gracefully
if one vendor's access changes, rather than having a single point of geopolitical failure.

---

## 6. Commercial / managed geolocation services

| # | Service | What it actually does | Relevance to telemetry-correction use case |
|---|---|---|---|
| [71] Sentinel Hub | Cloud access/processing for Sentinel/Landsat/commercial imagery — browsing, time-series, custom scripting, WMS/APIs; owned by Sinergise, now part of Planet Insights Platform | Data-access/processing layer, **not** a geolocation-correction service (verified via `sentinel-hub.com`) |
| [72] Up42 | EO data marketplace + processing platform: catalog/discovery, tasking, data management, processing; aggregates 20+ providers (Planet, Airbus, Capella, ICEYE, BlackSky named explicitly) | Marketplace/procurement layer for reference imagery/SAR/elevation, not a geolocation service itself (verified via `up42.com`) |
| [73] SkyFi | "Earth Intelligence Platform" — satellite tasking (~$200+) and archive imagery (~$15+) marketplace, "virtual constellation" of 40+ providers (Vantor, Planet, ICEYE, Umbra named) | Same category as [72] — imagery access, not geolocation-correction (verified via `skyfi.com`) |
| [74] Vexcel Imaging / Vexcel Data Program | Aerial camera manufacturer (UltraCam Merlin/Eagle/Osprey/Condor/Dragon) + UltraMap photogrammetry software; the Vexcel Data Program is one of the largest proprietary aerial-imagery archives, and its imagery already feeds Mapbox's highest-resolution tiles in NA/Europe (per [55]) | Reference-imagery/hardware source, not a service (verified via `vexcel-imaging.com` — note `vexcel.com`/`vexcel-group.com` is an unrelated Microsoft-owned IT consultancy of the same name, confirmed by mistake during this research pass) |
| [75] Picterra | GeoAI analytics platform, currently focused on agriculture/environmental/supply-chain compliance (deforestation, EUDR) via an "Insights Hub"; products: Forge (custom models), Tracer (supply chain), Accelerate | Not aimed at our use case at all (verified via `picterra.ai`) |
| [76] Blackshark.ai | Converts satellite/aerial imagery into structured 3D digital-twin world models; HUNTR™ = AI infrastructure extraction, REPLIKA™ = simulation-ready 3D reconstruction; processes 7M km²/day | Closer to a DSM/3D-data provider than a geolocation-correction service — could theoretically supply a high-fidelity 3D reference model for §4's ray-casting, but that is speculative, not their advertised product (verified via `blackshark.ai`) |
| Maxar Raptor | Named in the original research prompt as an onboard/edge geolocation product | **Could not be independently verified this pass** — `maxar.com` was unreachable (DNS/timeout) on every attempt. Note: `12-light-onboard.md` §1 row 1 already documents "Raptor / Vantor (formerly Maxar Intelligence)" as a live-camera-vs-preloaded-terrain-model onboard correlation product with a vendor-claimed <10 m RMSE — that entry should be treated as the authoritative one, not this document's unconfirmed attempt |

**The gap this table confirms.** No dedicated commercial "match my drone image/video against
satellite imagery and return a corrected absolute geolocation" API service was found anywhere in this
research pass. Every verified service above is either (a) an imagery/data marketplace ([71]/[72]/[73]/[74])
that would supply the reference-imagery layer to a pipeline we still have to build, or (b) a
geospatial-ML platform aimed at a different vertical entirely ([75] agriculture/ESG, [76] synthetic 3D
world generation). This confirms the heavy-tier correction pipeline itself — matching, fusion, gating —
is something to build in-house on top of the open-source stack in §§1–4, using these marketplaces
purely as an imagery/DEM acquisition convenience layer, not as a shortcut around building the pipeline.

---

## 7. Recommendation

### 7a. Three candidate architectures

| | **A — Sparse-robust baseline (ship now)** | **B — Cross-modal dense (post-flight, "the heavy pass")** | **C — Feed-forward geometry (research bet)** |
|---|---|---|---|
| Matching | [13] XFeat + [12] LightGlue, both ONNX/OpenVINO | [2] RoMa v2 (or [5] EfficientLoFTR) fine-tuned in the style of [16] MatchAnything | [8] MASt3R / [10] VGGT / [11] MapAnything feed-forward pose+geometry regression |
| Fusion | [31] GTSAM sliding window, [38]/[39]/[40]-gated | [31]/[34] sliding window shaped like [35] MASt3R-Fusion | Same graph as B, geometry model feeds one strong "fix" factor; [44]/[43]-style self-calibration for boresight drift |
| Ground projection | [63] Copernicus DEM ray-cast (§4) | Ray-cast + local-ortho refinement (§4) | Same as B |
| Accuracy | Moderate — brittle on textureless fields/forest, good on structured features (roads, buildings, intersections) | **Highest of the three** — dense correspondence + demonstrated cross-modal generalization targets the exact oblique↔nadir gap that likely sinks A | Potentially highest, but **unvalidated** — no cross-modal evidence found for VGGT/MapAnything specifically; trained on natural multi-view photo collections, not remote-sensing pairs |
| Robustness (fields/forest/oblique) | Weak–moderate; mitigate by ortho-rectifying the drone frame with telemetry+DEM *before* matching, turning it into nadir-vs-nadir | Best — this is precisely what [16]'s appearance-insensitive, structural-reasoning pretraining targets | Unknown/highest-risk — biggest unknown of the three |
| Latency | **Best** — genuinely near-real-time on Intel CPU, XFeat's whole design point, no GPU dependency | Worst — GPU-class transformers, no verified OpenVINO port; viable only as a batch/post-flight pass today | Comparable to B or worse; VGGT is "<1s" but that's still GPU-class compute |
| Integration effort (Intel/OpenVINO today) | **Lowest** — XFeat is CPU-native, LightGlue has a maintained OpenVINO export | Highest today — needs real OpenVINO porting work, or simply waits for a GPU host and runs post-flight meanwhile | Highest and most research-y — treat as a future upgrade path |
| References | [12], [13], [31], [63] | [2], [5], [16], [31], [34], [35] | [8], [10], [11], [43], [44] |

**Ranking:** ship **Candidate A** first — it runs on hardware we already own (§6.1 of
`01-master-geo-stack.md`: Intel-only GB4005, no CUDA) and is genuinely near-real-time. Run
**Candidate B** as the deep, batched, post-flight correction pass this document's scope is actually
named for — the task explicitly allows "near-real-time or post-flight," and B is the honest heavy-tier
answer once one accepts it cannot run in near-real-time on today's hardware. Treat **Candidate C** as
a 2027-horizon research bet, contingent on a GPU host materializing (per `TWO-TARGETS-PLAN`/
`HARDWARE-BUYLIST.md`) and an in-house cross-modal validation that does not exist in the literature yet.

### 7b. Telemetry-correction design sketch

This grounds directly in what `01-master-geo-stack.md` already documents as shipped: `Telemetry`
(kernel) carries `latitude`/`longitude`, `altitudeMeters` (AMSL — fixed to be un-confused with AGL by
GEO-POSE G1), a separate `aglMeters`, `headingDegrees`, an `Attitude` record (aircraft roll/pitch/yaw +
*earth-frame* gimbal roll/pitch/yaw), a free-form `extra: Map<String,Double>`, and `flightState`.
`GeoProjection` today does boresight-only, flat-ground/spherical-earth ray projection with **no DEM,
no camera intrinsics** — `altitudeMeters` on every result is `null`. This is the shipped substrate a
correction plugs into, not a green field.

**What gets corrected:**
- **Horizontal position** (lat/lon) — from the DEM-ray-cast + ortho-registration fix (§4), the same
  shape of output `GeoProjection.project` already returns, but now DEM-aware instead of flat-ground.
- **Yaw/heading** — from the rotation component of the image-to-map registration. Only yaw is
  well-constrained from a single frame; roll/pitch stay telemetry-trusted since a gimbal usually
  stabilizes those (matches `Attitude`'s existing earth-frame gimbal fields).
- **AGL** — cross-checked against DEM elevation at the matched ground point vs. `Telemetry.aglMeters`.
- **Boresight/gimbal offset** — modeled as a slowly-time-varying calibration node (§3's [43]/[44]
  self-calibration pattern), not trusted blindly from `GIMBAL_DEVICE_ATTITUDE_STATUS`(#285)/
  `MOUNT_ORIENTATION`(#265) as decoded today.

**How corrections are gated:** (a) the matcher's own inlier-ratio/confidence score (RoMa/LightGlue
both emit per-match certainty); (b) a Huber/DCS-robust residual check inside the factor graph ([38]/
[39]/[40]) so one bad match cannot yank the whole window; (c) the AGL/DEM consistency test from §3/§4
— disagreement beyond a terrain-uncertainty-scaled threshold downweights or rejects the fix outright;
(d) a minimum-baseline / consecutive-agreement requirement before trusting a derived heading
correction, since a single frame's rotation estimate alone is too noisy. This mirrors the exact lesson
this project's own `CameraCalibrationSolver` already learned the hard way (`01-master-geo-stack.md`
§3.1): with N=2 correspondences, "the fit is exact and the residual meaningless" was **false** — two
points give four measurements against three unknowns, one redundant degree of freedom, and a
confidently-wrong pose slipped through five review waves and 1591 tests until one live `curl` caught
it. Any pose-correction design that fits parameters from matched points must apply its residual/
quality ceiling at *every* N, never skip it because a case looks "thin but exact."

**How corrections are published back:** as a parallel, versioned corrected-track stream — never
overwriting raw MAVLink telemetry — carrying the correction itself, a confidence/covariance from the
factor graph, and a divergence-alarm flag (fires when raw and corrected disagree by more than Nσ for M
consecutive fixes, signalling GPS spoofing/drift/telemetry fault rather than ordinary filter noise).
Concretely, this is a new *source* for the N-source `PositionFusion` registry `01-master-geo-stack.md`
§7 already names as the extension point (today `PositionFix`/`PositionFusion`/`FixOrigin` exist only
on the unmerged, parked `feat/visual-geo` branch), landing either as a new `Telemetry.extra` key
convention or as a station-side corrected-position publication analogous to S2's `TrackProjectionService`/
`ProjectedTrack` pattern — a new table classified per the V21 audit split (`camera_poses` = audited,
`projected_track_points` = excluded/high-volume — a 1 Hz corrected-position stream should follow the
latter's precedent, not the former's). Raw telemetry stays the trusted source of record until a human
or a gated policy promotes a correction — this is the same "freshest-data, never silently override"
rule (`01-master-geo-stack.md` §8, CLAUDE.md rule 9) already governing every other live-data path in
this codebase, and the same RX-only, operator-gated doctrine (§5.3 of that document) that would apply
if a correction were ever fed back to the aircraft itself via `GPS_INPUT`/`VISION_POSITION_ESTIMATE` —
neither of which is decoded or sent anywhere in this codebase today, and neither should be without the
same explicit operator go every other command-TX capability already requires.

---

## Annotated bibliography

1. Edstedt, Sun, Bökman, Wadenbäck, Felsberg — "RoMa: Robust Dense Feature Matching" (CVPR 2024). https://github.com/Parskatt/RoMa
2. Edstedt et al. — "RoMa v2: Harder Better Faster Denser Feature Matching" (arXiv 2511.15706, 2025). https://arxiv.org/abs/2511.15706
3. Edstedt et al. — "DKM: Dense Kernelized Feature Matching for Geometry Estimation" (CVPR 2023, arXiv 2202.00667). https://arxiv.org/abs/2202.00667
4. Sun et al. — "LoFTR: Detector-Free Local Feature Matching with Transformers" (CVPR 2021 / T-PAMI 2022). https://github.com/zju3dv/LoFTR
5. "Efficient LoFTR: Semi-Dense Local Feature Matching with Sparse-Like Speed" (CVPR 2024). https://github.com/zju3dv/EfficientLoFTR
6. Chen, Luo, Zhou, Tian, Zhen, Fang, Mckinnon, Tsin, Quan — "ASpanFormer: Detector-Free Image Matching with Adaptive Span Transformer" (arXiv 2208.14201, ECCV 2022). https://arxiv.org/abs/2208.14201
7. Wang, Leroy, Cabon, Chidlovskii, Revaud — "DUSt3R: Geometric 3D Vision Made Easy" (CVPR 2024). https://github.com/naver/dust3r
8. Leroy et al. — "Grounding Image Matching in 3D with MASt3R" (2024). https://github.com/naver/mast3r
9. Duisterhof, Zust, Weinzaepfel, Leroy, Cabon, Revaud — "MASt3R-SfM" (arXiv 2409.19152, 2024). https://arxiv.org/abs/2409.19152
10. Wang, Chen, Xie, Novotny — "VGGT: Visual Geometry Grounded Transformer" (CVPR 2025 Best Paper, arXiv 2503.11651). https://github.com/facebookresearch/vggt
11. "MapAnything: Universal Feed-Forward Metric 3D Reconstruction" (arXiv 2509.13414, 2025). https://map-anything.github.io/
12. Lindenberger, Sarlin, Pollefeys — "LightGlue: Local Feature Matching at Light Speed" (ICCV 2023). https://github.com/cvg/LightGlue
13. Potje, Cadar, Araujo, Martins, Nascimento — "XFeat: Accelerated Features for Lightweight Image Matching" (CVPR 2024). https://github.com/verlab/accelerated_features
14. Jiang, Karpur, Cao, Huang, Araujo — "OmniGlue: Generalizable Feature Matching with Foundation Model Guidance" (CVPR 2024). https://github.com/google-research/omniglue
15. Shen et al. — "GIM: Learning Generalizable Image Matcher From Internet Videos" (ICLR 2024 Spotlight, arXiv 2402.11095). https://github.com/xuelunshen/gim
16. "MatchAnything: Universal Cross-Modality Image Matching with Large-Scale Pre-Training" (arXiv 2501.07556, TPAMI 2026). https://github.com/zju3dv/MatchAnything
17. Kornia — differentiable computer vision library. https://github.com/kornia/kornia
18. "A Practical Cross-View Image Matching Method between UAV and Satellite for UAV-Based Geo-Localization" (Ding et al., Remote Sensing 2021). https://www.mdpi.com/2072-4292/13/1/47
19. "A Cross-View Geo-Localization Algorithm Using UAV Image and Satellite Image" (2024). https://pmc.ncbi.nlm.nih.gov/articles/PMC11207219/
20. COLMAP — SfM + MVS toolkit. https://github.com/colmap/colmap
21. "GLOMAP: Global Structured-from-Motion Revisited" (2024, now merged into COLMAP as its global mapper). https://github.com/colmap/glomap
22. OpenMVG — "open Multiple View Geometry" library. https://github.com/openMVG/openMVG
23. OpenSfM — Python structure-from-motion pipeline. https://github.com/mapillary/OpenSfM
24. OpenDroneMap (ODM). https://github.com/OpenDroneMap/ODM
25. WebODM. https://github.com/OpenDroneMap/WebODM
26. Pix4D — Pix4Dmapper / Pix4Dmatic (commercial). https://www.pix4d.com/
27. Agisoft — Metashape (commercial). https://www.agisoft.com/
28. Sarlin, Cadena, Siegwart, Dymczyk (cvg) — hloc (Hierarchical-Localization). https://github.com/cvg/Hierarchical-Localization
29. Zhong, Li, Qin, Zhang — "Deep Learning-Based UAV Aerial Triangulation without Image Control Points" (arXiv 2301.02869, 2023). https://arxiv.org/abs/2301.02869
30. Chen, Li, Li, Yang, Dong — "DeepAAT: Deep Automated Aerial Triangulation for Fast UAV-based Mapping" (arXiv 2402.01134, 2024). https://arxiv.org/abs/2402.01134
31. GTSAM — factor-graph sensor fusion library. https://github.com/borglab/gtsam
32. g2o — general graph optimization for SLAM/BA. https://github.com/RainerKuemmerle/g2o
33. Ceres Solver. http://ceres-solver.org/
34. Hu et al. (Skydio) — "SymForce: Symbolic Computation and Code Generation for Robotics" (RSS 2022). https://www.roboticsproceedings.org/rss18/p041.pdf
35. Zhou, Li, Li, Yan, Xia, Feng — "MASt3R-Fusion: Integrating Feed-Forward Visual Model with IMU, GNSS for High-Functionality SLAM" (arXiv 2509.20757, 2025). https://arxiv.org/abs/2509.20757
36. Kinnari, Verdoja, Kyrki — "GNSS-denied geolocalization of UAVs by visual matching of onboard camera images with orthophotos" (ICAR 2021). https://research.aalto.fi/en/publications/gnss-denied-geolocalization-of-uavs-by-visual-matching-of-onboard/
37. Kinnari, Verdoja, Kyrki — "LSVL: Large-scale season-invariant visual localization for UAVs" (arXiv 2212.03581, IEEE RA-L 2022). https://arxiv.org/abs/2212.03581
38. Huber / Cauchy robust loss kernels — standard SLAM/BA optimization literature, built into [31]/[32]/[33].
39. Zach — "Robust Bundle Adjustment Revisited" (ECCV 2014). https://doi.org/10.1007/978-3-319-10602-1_50
40. Sünderhauf & Protzel — "Switchable constraints for robust pose graph SLAM" (IROS 2012). https://doi.org/10.1109/IROS.2012.6385590
41. Gopinath, Hijazi, Collins, Lemons, Schultz-Fellenz, Bent, Hijazi, Riemersma — "Globally Optimal Boresight Alignment of UAV-LiDAR Systems" (arXiv 2202.13501, 2022). https://arxiv.org/abs/2202.13501
42. Park & Bang — "Contours-Seeking Proposal Density Particle Filter and Resilient Terrain-Referenced Navigation" (arXiv 2608.15489, 2026). https://arxiv.org/abs/2608.15489
43. Hagemann, Knorr, Stiller — "Deep geometry-aware camera self-calibration from video" (ICCV 2023). https://github.com/boschresearch/DroidCalib
44. Veicht, Sarlin, Lindenberger, Pollefeys (cvg) — "GeoCalib: Learning Single-image Calibration with Geometric Optimization" (ECCV 2024, arXiv 2409.06704). https://github.com/cvg/GeoCalib
45. Jin, Zhang, Hold-Geoffroy, Wang, Matzen, Sticha, Fouhey — "Perspective Fields for Single Image Camera Calibration" (CVPR 2023, arXiv 2212.03239). https://arxiv.org/abs/2212.03239
46. Zhu, Kumar, Hu, Liu — "Tame a Wild Camera: In-the-Wild Monocular Camera Calibration" (NeurIPS 2023, arXiv 2306.10988). https://arxiv.org/abs/2306.10988
47. Wildes, Hirvonen, Hsu, Kumar, Lehman, Matei, Zhao — "Video Georegistration: Algorithm and Quantitative Evaluation" (ICCV 2001). https://doi.org/10.1109/ICCV.2001.937646
48. Pritt, Wright, LaTourette — "Error propagation for DEM-based georegistration of motion imagery" (AIPR 2011). https://doi.org/10.1109/AIPR.2011.6176342
49. Pritt & LaTourette — "Automated georegistration of motion imagery" (AIPR 2011). https://doi.org/10.1109/AIPR.2011.6176343
50. Pritt & LaTourette — "Stabilization and georegistration of aerial video over mountain terrain by means of lidar" (IGARSS 2011).
51. LaTourette & Pritt — "Dense 3D reconstruction for video stabilization and georegistration" (IGARSS 2012).
52. Esri — "World Imagery Wayback". https://livingatlas.arcgis.com/wayback/
53. Google — "Photorealistic 3D Tiles overview". https://developers.google.com/maps/documentation/tile/3d-tiles-overview
54. Microsoft — Bing Maps Platform APIs Terms of Use. https://www.bingmapsportal.com/terms
55. Mapbox — "Mapbox Satellite | Tilesets". https://docs.mapbox.com/data/tilesets/reference/mapbox-satellite/
56. Wikipedia — "Maxar Technologies" (2022 Kyiv-convoy imagery; 2025 Ukraine access restriction). https://en.wikipedia.org/wiki/Maxar_Technologies
57. Planet Labs — imagery products. https://www.planet.com/products/planet-imagery/
58. Airbus — OneAtlas (Pleiades/SPOT). https://www.intelligence-airbusds.com/imagery/
59. Copernicus Data Space Ecosystem. https://dataspace.copernicus.eu/
60. EOX — cloudless Sentinel-2 basemap. https://cloudless.eox.at/preview
61. OpenAerialMap. https://openaerialmap.org/
62. USDA/USGS — NAIP (National Agriculture Imagery Program). https://www.usgs.gov/centers/eros/science/usgs-eros-archive-aerial-photography-national-agriculture-imagery-program-naip
63. Copernicus — Digital Elevation Model (GLO-30/GLO-90). https://spacedata.copernicus.eu/collections/copernicus-digital-elevation-model
64. NASA/USGS — SRTM. https://www2.jpl.nasa.gov/srtm/
65. Fathom — FABDEM (Forest And Buildings removed Copernicus DEM). https://www.fathom.global/product/fabdem/
66. JAXA — ALOS World 3D (AW3D30). https://www.eorc.jaxa.jp/ALOS/en/dataset/aw3d30/aw3d30_e.htm
67. Google — Photorealistic 3D Tiles (same as [53]).
68. OpenStreetMap — Buildings (via Overpass/Geofabrik). https://download.geofabrik.de/
69. OpenStreetMap Foundation — ODbL licence. https://www.openstreetmap.org/copyright
70. Overture Maps Foundation — data licences by theme. https://docs.overturemaps.org/attribution/
71. Sentinel Hub (Sinergise / Planet Insights Platform). https://www.sentinel-hub.com/
72. Up42 — EO data marketplace and processing platform. https://up42.com/
73. SkyFi — satellite tasking and imagery marketplace. https://skyfi.com/
74. Vexcel Imaging — UltraCam aerial cameras and imagery program. https://www.vexcel-imaging.com/
75. Picterra — GeoAI analytics platform. https://picterra.ai/
76. Blackshark.ai — 3D digital-twin world models from satellite/aerial imagery. https://blackshark.ai/

**Internal cross-references (not public URLs):** `docs/conclusions/visual-geo-research/00-existing-state.md`
(this branch's own measured AnyLoc/DINOv2 retrieval-failure numbers, cited in §1); `01-master-geo-stack.md`
(the `Telemetry`/`GeoProjection`/`FixedCameraGeo` shipped model and the `PositionFusion` extension point
grounding §7b); `10-classical-registration.md` and `11-deep-crossview-vpr.md` (this series' onboard/light-tier
treatment of the retrieval-and-re-ranking framing for many of the same §1 matchers); `12-light-onboard.md`
(the onboard/edge product survey, including its own entry for Maxar/Vantor Raptor, §6 of this document);
`docs/main/HARDWARE-BUYLIST.md` / `TWO-TARGETS-PLAN` (station compute reality, referenced in §7a's ranking).

---

## Open questions

1. **No literature was found benchmarking dense matchers ([1]–[6]) specifically on oblique-drone-to-
   nadir-satellite pairs** — every dense-matcher accuracy number in §1 comes from ground-level or
   oblique-to-oblique benchmarks (MegaDepth/ScanNet-class). This is the single highest-value gap to
   close before committing to Candidate A or B in §7: an in-house benchmark using our own gimbal
   footage against Sentinel-2/Esri Wayback tiles, with and without pre-rectification and with and
   without MatchAnything-style fine-tuning.
2. **No OpenVINO benchmark numbers (fps/ms) were found for any matcher in §1 on Intel hardware
   specifically** — every latency claim is architectural/qualitative ("CPU-native," "GPU-class"), not
   measured. The first implementation step for Candidate A should be an in-house OpenVINO benchmark
   sweep of XFeat and LightGlue on the actual GB4005 host.
3. **No paper matching "satellite-aided bundle adjustment" or an equivalent explicit satellite-imagery-
   as-control method was found** (§2) — either a genuine literature gap or a terminology mismatch that
   needs a differently-worded follow-up search (e.g. "cross-domain geo-registration for SfM control,"
   "remote sensing image as virtual GCP source").
4. **The task's suggested "Sheikh/Shah" attribution for foundational video-georegistration work was
   not found** under an author-name search (§4) — the actual foundational lineage located instead is
   Wildes et al. (Sarnoff, ICCV 2001) and Pritt/LaTourette/Wright (Lockheed Martin, AIPR/IGARSS
   2011–2012). Worth a direct follow-up with the user on whether a different paper/author was intended,
   since it's possible a real but differently-titled Sheikh/Shah paper exists that this pass's search
   terms simply missed.
5. **FABDEM's exact licence text ([65]) and several imagery-source caching ToS clauses ([52]/[53]/[54]/
   [55]/[56]/[60]) were not independently confirmed this pass** — several source pages either timed out,
   were unreachable (`maxar.com` failed on every attempt), or only exposed marketing/docs content
   rather than the binding legal terms. A dedicated licence-verification pass (reading actual ToS PDFs,
   not doc/marketing pages) is needed before any of these sources are wired into a production caching
   pipeline.
6. **Maxar Raptor (§6) could not be verified this pass** due to `maxar.com` being unreachable — the
   existing, better-sourced entry in `12-light-onboard.md` §1 should be treated as authoritative instead
   of anything asserted here.
7. **MASt3R-Fusion ([35]) has no confirmed public code repository** — it is currently a design-pattern
   reference (the closest published precedent for §7b's fusion architecture), not a verified
   integration option. Confirm licence/availability before treating it as more than that.
8. **§3's boresight/gimbal self-calibration references ([41], [43], [44]) are drawn from LiDAR and
   general-camera literature, not gimbal-specific UAV work** — no paper was found addressing boresight
   drift *specifically* for a MAVLink `GIMBAL_DEVICE_ATTITUDE_STATUS`-reporting gimbal in flight; the
   design sketch's self-calibration node is therefore a methodological transfer, not a directly-proven
   technique for this exact sensor class.
9. **No published false-fix rate exists for any visual-correction method surveyed here** — every
   accuracy number in §§1–4 is an average/median error under normal operating conditions, not the rate
   at which a fusion layer would confidently commit to a *wrong* fix. `11-deep-crossview-vpr.md` flags
   the same gap for the onboard/light tier; it applies equally to the heavy-tier gating logic in §7b
   and should be demanded of any candidate before trusting it at operational scale.
