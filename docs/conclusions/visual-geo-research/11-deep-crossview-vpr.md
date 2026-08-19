# Deep-Learning UAV↔Satellite Cross-View Geo-Localization and VPR — Literature Review

Scope: what the field does when **recall@k is fine but top-1 rank fails** for oblique low-altitude
drone frames matched against a discrete satellite-tile gallery. Written against our concrete failure
mode: Esri World Imagery z17 tiles (~40 candidates/region), AnyLoc/DINOv2-class global descriptors,
correct tile top-1 in 0/12 evaluated frames, median rank 14, recall@5 in [0.87, 1.0]. Abstention
(knowing when *not* to commit) already works; ranking does not.

All references verified against arXiv/OpenReview/IEEE/GitHub at fetch time (2026-08-19); URLs are
in §6. No code is reproduced here — only citations, numbers and structural conclusions.

### TL;DR

| Question | Short answer | Detail |
|---|---|---|
| Why does rank fail while recall@5 works? | The global descriptor (AnyLoc/DINOv2) was never trained on the oblique-drone↔nadir-satellite viewpoint gap; it narrows the search well but cannot do the last-mile fine discrimination between visually similar neighboring tiles. | §1.2, §4 |
| What fixes it, per the literature? | Re-rank the existing top-k with a geometric/local-feature matcher, fuse ranks across the video sequence, and rectify oblique→nadir before matching. Fine-tuning a cross-view-specific descriptor is the fallback, not the first move. | §4.2 |
| What runs onboard vs. server? | Sequence fusion and classical RANSAC/homography verification are edge-cheap; the DINOv2 retrieval backbone and any dense matcher (RoMa/LoFTR/MASt3R) belong on the OpenVINO server; LightGlue+SuperPoint is the one borderline case worth prototyping on Jetson Orin Nano. | §5 |
| Is this recipe proven end-to-end anywhere? | No — no single benchmark combines oblique 50–150 m altitude, homogeneous field terrain, and a discrete z17 tile gallery. The recipe is inferred from how adjacent sub-fields solve the same *shape* of problem. | §7 Q1, Q3 |

---

## 1. Problem framing

### 1.1 What "recall@5 high, top-1 fails" means structurally

Recall@5 ∈ [0.87, 1.0] on a ~40-candidate gallery means the descriptor space is already doing most
of the work: it reliably places the correct tile in a small semantically-plausible neighborhood
(roughly the top 5–14 of 40, matching the observed median rank of 14). What it is *not* doing is the
last mile — discriminating the correct tile from its immediate visual neighbors (adjacent tiles,
similar field/road/building patterns) once the search has narrowed. That is a **fine-grained,
geometry-sensitive discrimination problem**, not a coarse "which part of the map" problem. The
literature draws exactly this line and solves the two problems with different machinery (§3, §4).

### 1.2 Why AnyLoc/DINOv2-class descriptors plateau here

| Cause (literature-attested) | Evidence | Consequence for our pipeline |
|---|---|---|
| AnyLoc [27] is a **zero-shot, unsupervised** VLAD/GeM aggregation over frozen DINOv2 features. Its aerial evaluation used same-viewpoint aerial revisit sets, not oblique-drone-vs-nadir-satellite cross-view pairs. | AnyLoc paper + repo (BSD-3, `dinov2_vitg14`) evaluate "urban, indoors, aerial, underwater, subterranean" but the aerial case is drone-to-drone revisit, not cross-view-to-satellite-ortho [27]. | The descriptor was never trained or benchmarked on the specific viewpoint gap (oblique 50–150 m drone vs. nadir ortho tile) that dominates our error. |
| The entire **cross-view geo-localization (CVGL)** sub-field exists precisely because generic VPR descriptors fail to bridge drone-oblique↔satellite-nadir appearance gaps — occluded facades, shadow direction, scale mismatch, rotation ambiguity. | University-1652 [1], LPN [13], FSRA [14], Sample4Geo [16], GeoDTR(+) [17,18] all motivate their architecture explicitly around this gap. | A descriptor with no cross-view-specific training (part alignment, hard-negative mining, polar/BEV rectification) will rank plausible-looking neighbors close to the true tile — exactly the "correct tile in top 14 of 40" symptom. |
| In-plane/viewpoint **rotation** sharply degrades aerial VPR recall even before cross-view is considered. | VPAIR study: "aerial VPR is significantly impacted by in-plane rotations; performance drops sharply" [5]. UltraVPR (RAL 2025) built specifically to fix rotation-sensitivity in aerial VPR. | Drone heading is arbitrary relative to tile north; without orientation normalization or rotation-invariant training, rank noise is expected. |
| Oblique perspective vs. orthographic satellite tiles is a **projective, not just appearance, mismatch** — literature treats it as requiring explicit rectification (IPM/homography) or 3D-aware alignment, not appearance-only embedding. | Coarse-to-fine oblique-view UAV method (ISPRS 2024) [via search]; "Unifying UAV Cross-View Geo-Localization via 3D Geometric Perception" [63]; pseudo-orthophoto synthesis works [62]. | A single global descriptor computed on the raw oblique frame is asking the embedding to implicitly learn a projective transform it was never trained for. |
| Recall@5 near 1.0 with median rank 14 out of 40 is consistent with the descriptor doing **coarse localization well** and needing a **re-ranking / verification stage** to do fine localization — this two-stage split is the default architecture in modern VPR (Patch-NetVLAD [37], TransVPR [38], R2Former [39], hloc [53]). | "Conventional methods generally adopt aggregated CNN features for global retrieval and RANSAC-based geometric verification for reranking" [39]. | This is the single most load-bearing structural fact for §4: the field's answer to exactly our symptom is *add a re-ranking stage*, not *replace the retrieval descriptor*. |

### 1.3 Where our numbers sit relative to what CVGL literature reports

| Metric | Our system (oblique, 50–150 m, ~40-tile z17 gallery) | Typical reported by CVGL-specific methods (§3.1) | Typical reported by generic VPR (§3.2) |
|---|---|---|---|
| Recall@1 | 0/12 frames (0%) | Sample4Geo, GeoDTR+, MCCG: commonly 60–90%+ R@1 on University-1652/CVUSA/CVACT (same-area splits) [16,18,20] | Not directly comparable — cross-area/cross-view R@1 is typically not reported for generic VPR descriptors, since they are not evaluated on cross-view pairs at all |
| Recall@5 | 0.87–1.0 | Rarely the binding metric once R@1 is already 60–90%+ | AnyLoc-class descriptors report strong recall on same-domain VPR revisit sets, but §1.2 notes this transfers weakly to severe cross-view |
| Localization error (meters) | Not yet computed downstream of rank | VIGOR [11] is the first to report meter-level error instead of only R@k; OS-FPI [23] reports 3/5/10 m accuracy directly | Not applicable — generic VPR reports place-level R@k, not georeferenced meter error |
| Gallery size | ~40 tiles/region | University-1652 gallery: 951 satellite images (test); CVUSA/CVACT test sets: tens of thousands | MSLS/Pitts30k: tens of thousands of database images |

The comparison is a governance flag, not a like-for-like benchmark: our gallery (~40 candidates) is
*much smaller* than any of the retrieval galleries above, which should make top-1 easier, not harder,
if the descriptor were cross-view-trained — reinforcing that the gap is architectural (§1.2), not a
scale/gallery-size problem.

### 1.4 Framing for the rest of this document

- §2 catalogs the datasets that define "how oblique / how low / how homogeneous" the field has actually tested against, so we can judge how far our GNSS-denied field scenario sits from the benchmarks' comfort zone.
- §3 catalogs methods by family: cross-view-specific global retrieval, generic VPR global descriptors, re-ranking, dense matching, map-based, sequence-aware.
- §4 assembles the evidence for what specifically converts recall@k into top-1, since that is the actual ask.
- §5 maps every family to Jetson Orin Nano / RPi5 / RK3588 vs. Intel OpenVINO server feasibility.
- §6 is the annotated bibliography.
- §7 is open questions we could not close from literature alone.

---

## 2. Datasets and benchmarks

### 2.1 Drone-view ↔ satellite-view (primary relevance)

| # | Dataset | Venue/Year | Platform pairing | Altitude / angle | Scale | Closest to our case? |
|---|---|---|---|---|---|---|
| [1] | University-1652 | ACM MM 2020 | drone (synthetic flight) ↔ satellite ↔ ground | descending spiral over campus buildings, near-nadir-to-oblique synthetic sweep | 1,652 buildings / 72 universities, 50,218 train images / 701 buildings | Partial — synthetic drone renders, one-building-per-satellite-image framing (not a tiled region search) |
| [2] | SUES-200 | 2023 (arXiv:2204.10704) | drone ↔ satellite | 4 discrete altitudes: 150/200/250/300 m | 200 sites (Shanghai), 24,120 images | Good altitude range, but altitudes are all higher than our 50–150 m band |
| [3] | DenseUAV | IEEE TIP 2023 | drone ↔ satellite | 80/90/100 m, dense low-altitude urban | 14 campuses, 27K+ images | Closest altitude match; dense sampling stresses fine-grained rank exactly like our problem |
| [4] | UAV-VisLoc | 2024 (arXiv:2405.11936) | drone ↔ satellite map mosaics | varied, real flights, heading/height metadata attached | 6,742 drone images, 11 satellite maps, 11 China locations | Real (non-synthetic) drone imagery with per-image GPS/heading metadata — structurally closest to a production pipeline |
| [5] | VPAir | 2022 (arXiv:2205.11567) | fixed-wing aircraft ↔ rendered reference | 300–400 m AGL, near-nadir | 107 km trajectory, 2,788 query / 12,788 reference | Altitude too high (300m+) but the in-plane-rotation-sensitivity finding is directly relevant |
| [6] | ALTO | 2022 (arXiv:2207.12317) | helicopter ↔ reference imagery | large-scale terrain-oriented flight | ~150 km + ~260 km trajectories (OH/PA) | Altitude/angle not UAV-oblique-typical; useful for VPR-at-scale, not viewpoint gap |
| [7] | AerialVL | IEEE RA-L 2024 | UAV ↔ satellite reference DB | varied altitude/lighting, 11 sequences | ~70 km total, Qingdao, China | Good diversity of terrain but altitude/angle not disclosed as oblique-specific |
| [8] | GTA-UAV (Game4Loc) | AAAI 2025 Oral | synthetic game-rendered UAV ↔ satellite | multiple flight altitudes *and* attitudes (deliberately oblique) | 33,763 images, continuous area, positive **and semi-positive** samples | Best match for "region search with noisy/partial correspondence," the exact shape of our tile-gallery problem |
| [9] | CVUSA | (Zhai subset, ~2017) | ground panorama ↔ satellite | ground-level, ortho satellite | 35,532 train / 8,884 test pairs | Ground↔satellite, not drone; used for transfer/architecture validation only |
| [10] | CVACT | CVPR 2019 | ground panorama ↔ satellite | ground-level, ortho satellite | test set ~92,802 pairs (~10× CVUSA) | Same caveat as CVUSA; larger-scale cross-area generalization testbed |
| [11] | VIGOR | CVPR 2021 (arXiv:2011.12172) | ground ↔ satellite, **non-aligned** | ground-level; breaks one-to-one assumption | 90,618 aerial / 105,214 ground images, 4 US cities | Structurally relevant: first to evaluate *meter-level* localization accuracy, not just retrieval accuracy — same metric shift we need |
| [12] | Boson-nighttime | 2023 (arXiv:2306.02994) | UAV thermal ↔ satellite (paired + unpaired) | night flights 21:00–04:00, ~1 m/px | 33 km², 10K/13K/27K train/val/test | Night/thermal domain gap analog — relevant if we ever fly at night |

### 2.2 Notes on realism gap

- None of the primary drone↔satellite datasets combine **low altitude (50–150 m) + strongly oblique + open/homogeneous field terrain** simultaneously — this is the intersection our system is actually operating in. DenseUAV (low altitude, urban) and GTA-UAV (oblique, but game-synthetic) are the two closest approximations from opposite directions.
- UAV-VisLoc [4] is the only dataset in this list built explicitly as "UAV loses GNSS, matches against pre-existing satellite maps," i.e., the same operational framing as our system, and ships per-image drone heading/altitude — a strong candidate for a fine-tuning or evaluation set if usable under its licence.
- GTA-UAV/Game4Loc [8] is the only dataset offering **semi-positive labels for partial tile overlap**, which is the honest ground truth shape of a tiled-gallery search (a drone frame's field of view rarely maps to exactly one 40-tile-gallery entry) — directly useful for training a re-ranker that must handle "near miss but adjacent tile" cases, which is plausibly what's producing rank-14 medians.

---

## 3. Methods by family

Six families, ordered roughly by where they sit in a production pipeline: cross-view-specific
retrieval and generic VPR descriptors both compete for the *first-stage candidate generation* role
our AnyLoc pipeline currently fills; re-ranking and dense matching operate on the *short candidate
list* a first stage produces; map-based methods are an alternative reference source (OSM instead of
satellite tiles); sequence-aware methods operate *across* whatever single-frame pipeline is chosen.

### 3.1 Cross-view-specific global retrieval (drone/ground ↔ satellite, supervised)

This family exists because generic image retrieval (§3.2) does not close the appearance gap between
an oblique drone frame and a nadir satellite tile on its own. Every method below is trained on
*paired* cross-view data and bakes in some explicit mechanism for the viewpoint gap: part-based
alignment (LPN, FSRA), hard-negative contrastive mining (Sample4Geo), or geometric disentanglement
(GeoDTR/GeoDTR+). This is the family lever **D** (§4.1) would draw from.

| # | Method | Venue/Year | One-liner | Dataset | Key metric | Code |
|---|---|---|---|---|---|---|
| [13] | LPN | IEEE TCSVT 2021 (arXiv:2008.11646) | Square-ring feature partition weighted by distance-to-center; scalable to rotation | University-1652, CVUSA | competitive SOTA at publication | github.com/wtyhub/LPN (unofficial mirrors) |
| [14] | FSRA | IEEE TCSVT 2021 (arXiv:2201.09206) | Transformer heat-map-driven region segmentation + region-to-region alignment, no manual partition | University-1652 | SOTA at publication, both drone-target-localization and drone-navigation tasks | github.com/Dmmm1997/FSRA |
| [15] | TransGeo | CVPR 2022 (arXiv:2204.00097) | Pure-transformer, attention-guided non-uniform cropping to reallocate resolution to informative patches | CVUSA, CVACT | SOTA with lower compute than CNN+polar-transform baselines | github.com/Jeff-Zilence/TransGeo2022 |
| [16] | Sample4Geo | ICCV 2023 (arXiv:2303.11851) | Symmetric InfoNCE + two hard-negative mining strategies (geo-neighbor, embedding-similarity) | CVUSA, CVACT, University-1652, VIGOR | SOTA cross-area and same-area | github.com/Skyy93/Sample4Geo |
| [17] | GeoDTR | AAAI 2023 (arXiv:2212.04074) | Explicit geometric-layout disentanglement from appearance; layout-simulation + semantic augmentation; counterfactual training | CVUSA, CVACT, VIGOR | strong cross-area generalization | linked from GeoDTR+ repo |
| [18] | GeoDTR+ | IEEE TPAMI 2024 (arXiv:2308.09624) | Extends GLE module + Contrastive Hard Samples Generation (CHSG) | CVUSA, CVACT, VIGOR | +16.4%/+22.7%/+13.7% cross-area over prior SOTA (no polar transform) | github.com/zxh009123/GeoDTR_plus |
| [19] | SAIG | 2023 (arXiv:2302.01572) | "Narrow-deep" self-attention backbone, no aggregation/alignment modules | multiple CVGL datasets | SOTA with 15.9% of parameters of prior SOTA | linked in paper |
| [20] | MCCG | IEEE TCSVT 2024 | ConvNeXt-based multi-classifier, cross-dimension interaction for multiple feature representations | University-1652, SUES-200 | +3%+ over prior SOTA | github.com/mode-str/crossview |
| [21] | CCR | IEEE 2024 | Counterfactual causal reasoning to balance contextual vs. discriminative cues | cross-view drone/satellite benchmarks | high recall, cited as efficiency-vs-accuracy tradeoff case | — |
| [22] | MBF | Sensors 2023 (doi:10.3390/s23020720) | Two-branch fusion + hierarchical bilinear pooling; injects UAV altitude/status as word-embedding tokens | University-1652-style | improves robustness to flight-height variation | — |
| [23] | OS-FPI | 2024 (arXiv:2403.06148) | **Coarse-to-fine, single-stream** UAV↔satellite; early cross-feature exchange + offset-regression head, not pure retrieval | UL14 | RDS 76.25 (+10.92 pts); +182.6%/+164.2%/+137.4% at 3 m/5 m/10 m accuracy vs. prior | — |
| [24] | MCFA | 2025 (PMC12299452) | Multi-scale cascade + feature-adaptive alignment module for dynamic cross-view feature alignment | cross-view drone/satellite | reported gains over fixed-alignment baselines | — |
| [25] | GeoCLIP | NeurIPS 2023 (arXiv:2309.16020) | CLIP-style image↔GPS contrastive alignment; continuous Earth encoding via random Fourier features | Im2GPS3k, GWS15k | +1.31%@1km, +8.67%@2500km over SOTA; competitive with 20% training data | linked in paper |
| [26] | PIGEON | CVPR 2024 Highlight (arXiv:2307.05845) | Semantic geocells + multi-task contrastive pretraining + retrieval-over-clusters refinement | 100K GeoGuessr locations (own set) | >40% guesses within 25 km; >5% within 1 km on holdout | github.com/LukasHaas/PIGEON |

*Coarse geolocation methods (GeoCLIP, PIGEON) are included for completeness but operate at km-scale — not directly applicable to a 40-tile z17 regional gallery; they are cited because their "location encoding + retrieval" pattern is architecturally reusable for coarse pre-filtering upstream of a tile gallery.*

### 3.2 Generic VPR global descriptors (transfer/backbone candidates)

This is the family our current pipeline draws from (AnyLoc is row [27]). These descriptors are
trained/evaluated on ground-level or same-viewpoint revisit VPR — condition change (day/night,
season, weather) and moderate viewpoint change, not the severe oblique-to-nadir gap of §3.1. They
are architecturally attractive (single forward pass, no pairwise matching cost) but none of them
claim cross-view-specific robustness; several (SALAD, BoQ, MegaLoc) share AnyLoc's DINOv2 lineage
and would inherit the same domain-gap ceiling unless fine-tuned on cross-view pairs.

| # | Method | Venue/Year | One-liner | Dataset | Key metric | Code / licence |
|---|---|---|---|---|---|---|
| [27] | AnyLoc | IEEE RA-L 2023 (arXiv:2308.00688) | Zero-shot: frozen DINOv2 per-pixel features + unsupervised VLAD/GeM aggregation, no training | urban/indoor/aerial/underwater/subterranean | strong universal baseline, no cross-view-specific tuning | github.com/AnyLoc/AnyLoc, BSD-3-Clause |
| [28] | CosPlace | CVPR 2022 (arXiv:2204.02287) | Classification-based training (avoids contrastive mining), SF-XL dataset (30× prior largest) | SF-XL, standard VPR sets | 80% less train-time GPU memory, 8× smaller descriptors, better results | github.com/gmberton/CosPlace |
| [29] | EigenPlaces | ICCV 2023 | Trains on synthesized eigen-viewpoints for explicit viewpoint robustness | SF-XL-derived | improves viewpoint-shift robustness over CosPlace | github.com/gmberton/EigenPlaces |
| [30] | MixVPR | WACV 2023 | All-MLP global feature mixing, no local/pyramidal aggregation, trained on GSV-Cities | Pitts250k, MSLS, Nordland | R@1 94.6% (Pitts250k), fewer params than CosPlace/NetVLAD | github.com/amaralibey/MixVPR |
| [31] | SALAD | CVPR 2024 (arXiv:2311.15937) | Sinkhorn optimal-transport reformulation of NetVLAD soft-assignment + "dustbin" cluster; finetuned DINOv2 | standard VPR benchmarks | SOTA at publication | github.com/serizba/salad, GPL-3.0 |
| [32] | BoQ | CVPR 2024 | Learnable global "Bag of Queries" cross-attend to local features for aggregation | standard VPR benchmarks | beats Patch-NetVLAD/TransVPR/R2Former, much faster | github.com/amaralibey/Bag-of-Queries |
| [33] | MegaLoc | 2025 (arXiv:2502.17237) | Large-scale multi-dataset joint training, one retrieval model for many tasks | many VPR + landmark + LaMAR sets | SOTA generalist, new SOTA on LaMAR visual localization | linked in paper |
| [34] | CliqueMining | 2025 | Graph/clique-based batch sampling across MSLS+GSV-Cities for short-range sensitivity | MSLS val, Tokyo 24/7 | R@10 95.9% MSLS val (slightly > MegaLoc), R@1 96.8% Tokyo24/7 | linked from SALAD authors |
| [35] | SelaVPR / SelaVPR++ | ICLR 2024 (arXiv:2402.14505) / 2025 (arXiv:2502.16601) | Lightweight adapters on frozen foundation model for joint global+local features | MSLS | #1 MSLS challenge leaderboard; SelaVPR++ 6000× faster retrieval than TransVPR | linked in paper |
| [36] | CricaVPR | CVPR 2024 (arXiv:2402.19231) | Cross-image correlation via in-batch attention across viewpoint/condition variants | GSV-Cities-trained, standard VPR sets | SOTA robustness to viewpoint/illumination | github.com/Lu-Feng/CricaVPR |

### 3.3 Re-ranking (candidate-list geometric/local verification)

This is the family that directly targets our symptom (§1.1, §4). All three methods share the same
shape: run global retrieval first (cheap, coarse, high recall@k), then re-score only the top-k
candidates with something that looks at *local structure*, not just the global embedding distance.
The re-ranker never needs to be trained on cross-view pairs specifically — it just needs to tell
"this candidate's local geometry is consistent with the query" from "it merely looks similar."

| # | Method | Venue/Year | One-liner | Key metric | Code |
|---|---|---|---|---|---|
| [37] | Patch-NetVLAD | CVPR 2021 (arXiv:2103.01486) | Two-stage: global NetVLAD top-100 retrieval, then patch-level residual matching re-rank | won Facebook Mapillary VPR Challenge (ECCV 2020) | github.com/QVPR/Patch-NetVLAD |
| [38] | TransVPR | CVPR 2022 | Multi-level Transformer attention → global feature for retrieval + attention-masked key-patch descriptors for re-rank | SOTA at publication among two-stage methods | linked in paper |
| [39] | R2Former | CVPR 2023 Highlight | Unified retrieval+re-rank transformer; re-rank module uses feature correlation, attention value, xy coords (not just RANSAC) | SOTA + much lower inference time/memory than prior two-stage methods; SOTA MSLS challenge (at publication) | github.com/bytedance/R2Former |

### 3.4 Dense matching / local features (geometric verification engines)

The re-rankers in §3.3 are typically *built on top of* one of these matchers, or a classical
detector (SIFT/ORB) scored with RANSAC. This table separates out the matching engines themselves so
their standalone speed/licence/hardware profile is visible for the feasibility judgment in §5 —
this is the layer where "onboard vs. server" actually gets decided for lever A (§4.1).

| # | Method | Venue/Year | One-liner | Speed / size | Licence | Code |
|---|---|---|---|---|---|---|
| [40] | LightGlue + SuperPoint | ICCV 2023 | Adaptive sparse deep matcher, revisits SuperGlue design for speed | 150 FPS@1024kp (RTX3080 GPU) / 20 FPS@512kp (i7 CPU) | Apache-2.0 (SuperPoint weights: restrictive, non-commercial-leaning) | github.com/cvg/LightGlue |
| [41] | LoFTR | CVPR 2021 | Detector-free coarse-to-fine dense matching via self/cross Transformer attention, strong in low-texture regions | ranked #1 on public visual-localization benchmarks at publication | github.com/zju3dv/LoFTR |
| [42] | RoMa | CVPR 2024 | DINOv2 coarse + ConvNet fine features + Transformer match decoder; regression-by-classification | +36% on WxBS benchmark over prior SOTA | MIT (DINOv2 backbone: Apache-2.0) | github.com/Parskatt/RoMa |
| [43] | MASt3R (built on DUSt3R) | ECCV 2024 (arXiv:2406.09756) | Adds dense local-feature head + InfoNCE matching loss to DUSt3R; fast reciprocal matching; joint matching+3D | outperforms LoFTR/SuperGlue on matching+reconstruction benchmarks | CC BY-NC-SA 4.0 (non-commercial) | github.com/naver/mast3r |
| [44] | Kornia | WACV 2020 (arXiv:1910.02190) | Differentiable CV library: homography DLT/IRWLS solvers, RANSAC module, local-feature-matching ops, full autograd/GPU | library, not a benchmark result | Apache-2.0 | github.com/kornia/kornia |

### 3.5 Map-based localization

An orthogonal reference source to satellite tiles: match against a rasterized OpenStreetMap or a
learned neural map instead of pixel imagery. Relevant to us only as a *fallback* reference when
satellite tiles are stale/unavailable for a corridor, not as a replacement for the current pipeline
— both methods below report accuracy on urban ground-level or crowd-sourced datasets, not aerial.

| # | Method | Venue/Year | One-liner | Key metric | Code / licence |
|---|---|---|---|---|---|
| [45] | OrienterNet | CVPR 2023 (arXiv:2304.02009) | Neural BEV built from query image, matched against 2D OpenStreetMap semantic rasters (no satellite imagery needed) | sub-meter accuracy on crowd-sourced 12-city dataset | github.com/facebookresearch/OrienterNet; weights CC-BY-NC, ~11 GB GPU for eval |
| [46] | SNAP | 2023 (arXiv:2306.05407) | Self-supervised neural maps; camera-pose-only supervision, joint map+query embedding for visual positioning + semantic understanding | competitive with map-based baselines, no dense-3D supervision needed | not released as of search date |

### 3.6 Sequence-aware retrieval

This family is the direct evidence base for lever C (§4.1, §4.2). Both methods assume the query is
one frame in a temporally-ordered trajectory rather than an isolated still — exactly the shape of
our 12-frame evaluation set — and both are cheap to bolt onto an existing single-frame pipeline
since they consume already-computed per-frame descriptors/scores rather than raw pixels.

| # | Method | Venue/Year | One-liner | Key metric | Code |
|---|---|---|---|---|---|
| [47] | SeqSLAM | 2012 (classic; reviewed in 2505.14068, reimplemented in event-camera form arXiv:1505.04548) | Sequence-matching via temporal alignment of low-res image sequences, robust to severe appearance change; assumes ~constant velocity | seminal baseline for all sequence-based VPR | multiple open reimplementations |
| [48] | SeqNet | RA-L/ICRA 2021 (arXiv:2102.11603) | Small 1D-CNN over per-frame NetVLAD descriptors → compact learned sequence descriptor; two-stage hierarchical matching | outperforms SeqSLAM-style methods at equal sequence length | github.com/oravus/seqNet |

---

## 4. "Fixing top-1" — evidence section

### 4.1 The four levers the field actually uses, ranked by direct evidence strength for our exact symptom

| Lever | Mechanism | Direct evidence | Why it targets rank-14→rank-1, not just recall |
|---|---|---|---|
| **A. Geometric re-ranking of the top-k with a dense/sparse matcher** | Take the top-k (k=5..40) candidates from the existing global-descriptor search; score each by homography-inlier count / match confidence from LightGlue, LoFTR, or RoMa; re-order by that score | This is the *design purpose* of Patch-NetVLAD [37], TransVPR [38], R2Former [39], and the entire hloc pipeline [53]: "conventional methods adopt aggregated CNN features for global retrieval and RANSAC-based geometric verification for reranking" [39]. hloc's stated purpose is exactly "retrieval narrows candidates, then feature matching resolves the winner." | Recall@5 near 1.0 means the true tile is *already in the candidate set* the re-ranker would see — this lever spends compute only where the descriptor already succeeded coarsely, which is precisely our regime |
| **B. Oblique→nadir rectification before descriptor/matching (IPM/homography using known attitude)** | Use gimbal/attitude telemetry (already in our system per drone-infra work) to warp the oblique frame toward a pseudo-nadir view before feature extraction or matching, closing the projective gap with the ortho satellite tile | Coarse-to-fine oblique-UAV method (ISPRS J. Photogramm. 2024); pseudo-orthophoto generation via 3D reconstruction + PCA-guided orthographic projection in satellite-free training work [62]; unified geometry-aware 3D-perception framework performs retrieval+alignment+pose jointly [63] | Removes the systematic viewpoint bias (not just noise) that a same-training-domain descriptor was never asked to invert; directly attacks the "false near-neighbors look plausible from an oblique angle" failure mode |
| **C. Sequence/temporal fusion across the 12 correlated video frames** | Treat 12 frames as a trajectory, not 12 independent trials; fuse per-frame retrieval scores with SeqSLAM/SeqNet-style sequence descriptors [47,48], a particle filter over retrieval-score likelihoods [particle-filter UAV literature], or plain Bayesian score accumulation across frames | BEV-Patch-PF frames particle filtering explicitly as more suitable than "retrieval-based approaches [that] assign similarity scores over a discretized set … insensitive to fine-grained pose changes"; CPFL (2025) is "resilient continuous UAV localization via cross-view perception and particle filtering" | A single frame's median rank of 14 is exactly the noise level that temporal consistency filtering is built to remove — 12 frames of a moving platform constrain the true tile far more than any one frame does alone |
| **D. Domain-specific fine-tuning / contrastive hard-negative training on paired oblique-drone/satellite-tile data** | Replace or adapt the AnyLoc/DINOv2 descriptor with a CVGL-trained one (LPN/FSRA/Sample4Geo/GeoDTR+-style), even from a modest paired set, so the embedding is explicitly trained to separate visually-similar neighboring tiles under oblique viewing | Sample4Geo's entire contribution is that **hard-negative mining** (geo-neighbor + embedding-similarity) is what separates SOTA from near-SOTA cross-view descriptors [16]; GeoDTR+ shows +13–23% cross-area gains purely from better disentanglement/hard-sample generation [18] | Attacks the root cause in §1.2 directly, but is the most expensive lever (requires labeled paired data + training/eval infra) |

### 4.2 Direct answer to Q1 — best-supported recipe for recall@5≈0.9 → top-1 ≥0.85

The literature's consistent answer is **A + C together, with B as a force-multiplier, not D as a first step**:

1. Keep the existing AnyLoc/DINOv2 retrieval stage as-is for coarse candidate generation — recall@5 0.87–1.0 shows it is already doing its job.
2. Add lever **A**: re-rank the top-k (k≈10–20, generous relative to the ~40-tile gallery) with a dense/sparse local matcher (RoMa or LightGlue+SuperPoint) scored by inlier count / homography residual. This is the single most evidence-backed, lowest-integration-cost fix — it is the standard architecture in R2Former/TransVPR/Patch-NetVLAD/hloc, all built to solve exactly "global descriptor gets you close, local matching gets you exact."
3. Add lever **C**: since the evaluation set is video (12 frames, not 12 unrelated stills), accumulate per-frame re-ranked scores across the sequence (SeqNet-style descriptor or a simple running-likelihood/particle filter over tile hypotheses) before committing to a single top-1. This is the standard fix in the UAV-specific literature (CPFL, BEV-Patch-PF) for turning noisy single-frame ranks into a stable trajectory-level estimate.
4. Treat lever **B** (oblique→nadir rectification using attitude telemetry) as a pre-processing improvement to *both* A and C — it reduces the domain gap the matcher/descriptor has to bridge, and is comparatively cheap since attitude data is already produced onboard.
5. Reserve lever **D** (fine-tuning a CVGL-specific descriptor) as the fallback if A+C+B do not close the gap — it requires a paired oblique-drone/satellite-tile dataset (UAV-VisLoc [4] or a custom-collected one) and training infrastructure, and is the slowest to iterate on.

No single paper claims this exact recipe for this exact operating envelope (oblique 50–150 m, homogeneous field terrain, z17 Esri tiles) — that combination is not represented in any benchmark in §2 — so this is a **structural inference from how the field solves the same symptom in adjacent settings** (VIGOR's "beyond one-to-one" meter-level re-ranking, hloc's coarse-then-fine architecture, UAV-specific particle filtering), not a single-paper-cited result. This is flagged explicitly as an open question in §7.

### 4.3 Reported real-drone-video accuracy vs. the realistic gap for our regime

| Source | Setting | Reported accuracy | Gap to our regime |
|---|---|---|---|
| FoundLoc [49] | Nadir-facing camera + IMU + satellite imagery, VIO+VPR foundation model | ~20 m average error, <1 m best-case; 1.934 Hz on Jetson Xavier NX at >90% GPU | Nadir-only (not oblique); our oblique geometry is explicitly the harder case FoundLoc avoids |
| Vision-based GNSS-Free Localization for UAVs in the Wild [50] | SuperPoint+SuperGlue matching drone RGB vs. georeferenced satellite sections, long-distance high-altitude flights | "comparable accuracy with traditional GNSS-based methods" (qualitative; high-altitude regime) | High-altitude flights reduce oblique distortion relative to our 50–150 m band |
| LSVL [51] | Season-invariant descriptor matching, orthoprojected UAV images vs. satellite, up to 100 km² scale | 12.6–18.7 m lateral error from uninformed initialization within 23.2–44.4 sequential updates | Requires orthoprojection (our lever B) and sequential updates (our lever C) to reach meter-level — consistent with §4.2 |
| OS-FPI [23] | Coarse-to-fine single-stream with offset regression, UL14 dataset | RDS 76.25, large relative gains at 3/5/10 m thresholds | Demonstrates offset-regression (fine stage) is where the large accuracy jumps happen — reinforces that retrieval alone under-delivers on meter-level correctness |
| VIGOR [11] | Ground↔satellite, meter-level eval (not just retrieval accuracy) | first benchmark to report localization error in meters rather than only R@k | Methodological precedent for reporting the metric that actually matters to us (positional error, not just rank) |

**Realistic gap:** every accuracy number above that approaches meter-level or high recall assumes at least one of: (a) near-nadir viewpoint, (b) orthorectification, or (c) sequential/multi-frame fusion. None of the cited real-drone-video results report strong top-1/meter-level accuracy from a *single oblique low-altitude frame against raw appearance matching alone* — which is consistent with our own empirical finding that the raw global-descriptor pipeline plateaus around recall@5 rather than top-1.

---

## 5. Onboard vs. server feasibility

Target platforms: **Jetson Orin Nano** (GPU, CUDA, ~8–40 TOPS depending on SKU), **Raspberry Pi 5** (CPU-only, no NPU/GPU acceleration path for these models), **RK3588 NPU** (6 TOPS INT8, CNN-oriented rknn-toolkit2, documented attention/ViT compilation problems), and the **Intel OpenVINO server host** (no CUDA — our GB4005 inference box).

| # | Model / stage | Params / notes | Reported latency | Best-fit tier | Rationale |
|---|---|---|---|---|---|
| [27] | AnyLoc (DINOv2 ViT-g/14 + VLAD) | ViT-g/14 ≈ 1.1B params; paper uses 4×RTX3090 but notes "16 GB GPU should also work" for single-image inference | not benchmarked for edge in paper | **Server (OpenVINO)** for ViT-g; a distilled ViT-S/B DINOv2 variant could be attempted on Jetson Orin Nano | ViT-g is too large for Jetson Orin Nano's memory/compute envelope at usable FPS; RK3588's rknn-toolkit2 is documented as struggling with large attention matrices (community report: "SigLIP ViT... driver encountered issues... massive Attention matrices triggered compilation errors") |
| [40] | LightGlue + SuperPoint | lightweight sparse matcher | 150 FPS@1024kp (RTX3080 GPU), 20 FPS@512kp (i7 CPU) | **Jetson Orin Nano** for re-ranking a handful of top-k candidates; CPU fallback feasible on the OpenVINO host too | Sparse, low keypoint count (≈512–1024) makes this the most edge-plausible re-ranker in §3.4; SuperPoint's non-commercial-leaning licence is a deployment gate to check before shipping |
| [41] | LoFTR | detector-free, semi-dense, Transformer-based | not edge-benchmarked in sources found | **Server (OpenVINO)** | Dense attention over full feature maps is a poor fit for RK3588's documented ViT limitations and Pi5's CPU-only budget |
| [42] | RoMa | DINOv2 coarse + ConvNet fine + Transformer decoder | not edge-benchmarked; heavier than LoFTR (adds a second backbone) | **Server (OpenVINO)**, and even there expect CPU-bound slowdown vs. published CUDA numbers since RoMa's stack is PyTorch/CUDA-oriented | Two-backbone architecture (DINOv2 + ConvNet) is the heaviest matcher in this survey; least OpenVINO-friendly of the dense matchers without a dedicated export/quantization effort |
| [43] | MASt3R / DUSt3R | ViT-Large encoder (24 layers) + ViT-Base decoder (12 layers) | not edge-benchmarked; GPU-class model | **Server (OpenVINO)**, CUDA strongly preferred by upstream tooling — flag licence (CC BY-NC-SA 4.0, non-commercial) before any production use | Largest model in this survey; also the only one with a non-commercial licence, which independently rules it out for a shipped product regardless of hardware |
| [28]–[36] | CosPlace / EigenPlaces / MixVPR / SALAD / BoQ / MegaLoc / CliqueMining / SelaVPR(++) / CricaVPR | ResNet/ConvNeXt or ViT backbones, single forward pass, no cross-attention over image pairs | MixVPR emphasizes latency/param efficiency explicitly; SelaVPR++ reports "6000× faster... than TransVPR" for retrieval | **Jetson Orin Nano** plausible for ResNet/ConvNeXt-backbone variants (CosPlace, MixVPR); ViT-backbone variants (SALAD, BoQ, MegaLoc) better on **server** | Single-pass global descriptors are cheaper than pairwise matchers by construction; CNN-backbone versions are the more RK3588/Jetson-friendly of this family per the documented CNN-vs-ViT NPU gap |
| [45] | OrienterNet | neural BEV + OSM raster matching | needs ~11 GB GPU at published eval settings (rotation search reducible) | **Server (OpenVINO)** at full settings; reduced-rotation variant conceivable on Jetson Orin Nano | Rotation-hypothesis search is the memory driver; not RK3588/Pi5-class regardless of rotation count reduction |
| [47]/[48] | SeqSLAM / SeqNet | SeqNet: small 1D-CNN over precomputed descriptors | SeqNet explicitly designed as a lightweight add-on ("small 1D CNN") | **Jetson Orin Nano or even RPi5** | Operates on already-extracted per-frame descriptors, not raw images — the cheapest lever in this entire survey to run onboard |
| [44] | Kornia (RANSAC/homography utilities) | library, not a model | — | **Any tier** — pure geometry, no learned weights required for the RANSAC/homography path | Homography-inlier scoring (lever A's cheapest variant) needs no GPU at all if implemented via classical RANSAC rather than a learned matcher |
| [53] | hloc | orchestration framework, not itself a model | — | **Server (OpenVINO)** as the orchestrator; individual plugged-in components follow their own row above | hloc is a pipeline shell around SuperPoint/SuperGlue/LightGlue/NetVLAD-class components — feasibility is inherited from whichever matcher/descriptor is plugged in |

**Summary judgment for our stack:**
- **Onboard-feasible today** (Jetson Orin Nano / RPi5, preloaded corridor tiles): SeqNet-style sequence fusion over existing descriptors, classical RANSAC/homography verification via Kornia, and CNN-backbone global descriptors (CosPlace/MixVPR-class) if a lighter retrieval model is ever substituted for AnyLoc's ViT-g.
- **RK3588 is the weakest of the three edge tiers** for anything in this survey beyond CNN-backbone descriptors and classical geometry — every source touching RK3588 deployment flags ViT/attention-op compilation problems in the standard toolchain (rknn-toolkit2).
- **Belongs server-side on the OpenVINO host**: the AnyLoc DINOv2 ViT-g descriptor itself, any dense matcher (LoFTR/RoMa/MASt3R), and OrienterNet-class map matching — all either too large, too attention-heavy, or (for MASt3R) licence-blocked for edge/production use as-is. LightGlue+SuperPoint is the one dense-ish matcher genuinely borderline enough to prototype on Jetson Orin Nano before conceding it to the server tier.

---

## 6. Annotated bibliography

Numbered references cited throughout §1–5. Format: **[n] Title — Authors/venue, year. One-liner. Dataset. Key metric. URL.**

1. University-1652: A Multi-view Multi-source Benchmark for Drone-based Geo-localization — Zheng, Wei, Yang; ACM Multimedia 2020. Drone/satellite/ground triplet dataset, 1,652 buildings/72 universities, MIT-licensed baseline code. https://arxiv.org/abs/2002.12186 · https://github.com/layumi/University1652-Baseline
2. SUES-200: A Multi-height Multi-scene Cross-view Image Benchmark Across Drone and Satellite — 2023. Drone imagery at 150/200/250/300 m across 200 real-world sites. https://arxiv.org/abs/2204.10704
3. Vision-Based UAV Self-Positioning in Low-Altitude Urban Environments (DenseUAV) — Dai, Zheng et al.; IEEE TIP 2023. Dense low-altitude (80–100 m) self-positioning dataset, 14 campuses. https://github.com/Dmmm1997/DenseUAV
4. UAV-VisLoc: A Large-scale Dataset for UAV Visual Localization — 2024. 6,742 drone images + 11 satellite maps, per-image GPS/heading/altitude metadata. https://arxiv.org/abs/2405.11936 · https://github.com/IntelliSensing/UAV-VisLoc
5. VPAIR — Aerial Visual Place Recognition and Localization in Large-scale Outdoor Environments — 2022. Fixed-wing aircraft, 300–400 m AGL, 107 km, 6-DoF ground truth; documents in-plane-rotation sensitivity. https://arxiv.org/abs/2205.11567 · https://github.com/AerVisLoc/vpair
6. ALTO: A Large-Scale Dataset for UAV Visual Place Recognition and Localization — 2022. Helicopter flights, ~150 km + ~260 km, GPS-INS ground truth. https://arxiv.org/abs/2207.12317 · https://github.com/MetaSLAM/ALTO
7. AerialVL: A Dataset, Baseline and Algorithm Framework for Aerial-Based Visual Localization With Reference Map — IEEE RA-L 2024. 11 sequences, ~70 km, Qingdao, China. https://ieeexplore.ieee.org/document/10632587
8. Game4Loc: A UAV Geo-Localization Benchmark from Game Data (GTA-UAV) — AAAI 2025 Oral. Synthetic game-rendered UAV imagery, oblique + multi-altitude, positive/semi-positive labels. https://arxiv.org/abs/2409.16925 · https://github.com/Yux1angJi/GTA-UAV
9. Predicting Ground-Level Scene Layout from Aerial Imagery (CVUSA construction) — Workman/Zhai et al. Ground-satellite pair dataset used across CVGL literature.
10. CVACT (Lending Orientation to Neural Networks for Cross-view Geo-localization) — Liu & Li; CVPR 2019. Larger-scale ground-satellite pairing, ~92,802 test pairs.
11. VIGOR: Cross-View Image Geo-localization beyond One-to-one Retrieval — Zhu, Yang, Chen; CVPR 2021. First to break one-to-one retrieval assumption and report meter-level localization error. https://arxiv.org/abs/2011.12172 · https://github.com/Jeff-Zilence/VIGOR
12. Long-range UAV Thermal Geo-localization with Satellite Imagery (Boson-nighttime) — 2023. Night thermal-vs-satellite paired dataset, 33 km². https://arxiv.org/abs/2306.02994
13. Each Part Matters: Local Patterns Facilitate Cross-view Geo-localization (LPN) — IEEE TCSVT 2021. Square-ring feature partition for rotation-scalable part matching. https://arxiv.org/abs/2008.11646
14. A Transformer-Based Feature Segmentation and Region Alignment Method For UAV-View Geo-Localization (FSRA) — IEEE TCSVT 2021. Heat-map-driven automatic region alignment. https://arxiv.org/abs/2201.09206 · https://github.com/Dmmm1997/FSRA
15. TransGeo: Transformer Is All You Need for Cross-view Image Geo-localization — CVPR 2022. Attention-guided non-uniform cropping transformer. https://arxiv.org/abs/2204.00097 · https://github.com/Jeff-Zilence/TransGeo2022
16. Sample4Geo: Hard Negative Sampling For Cross-View Geo-Localisation — ICCV 2023. Geo-neighbor + embedding-similarity hard-negative mining with symmetric InfoNCE. https://arxiv.org/abs/2303.11851 · https://github.com/Skyy93/Sample4Geo
17. Cross-view Geo-localization via Learning Disentangled Geometric Layout Correspondence (GeoDTR) — AAAI 2023. https://arxiv.org/abs/2212.04074
18. GeoDTR+: Toward Generic Cross-View Geolocalization via Geometric Disentanglement — IEEE TPAMI 2024. +13–23% cross-area gains via CHSG. https://arxiv.org/abs/2308.09624 · https://github.com/zxh009123/GeoDTR_plus
19. Simple, Effective and General: A New Backbone for Cross-view Image Geo-localization (SAIG) — 2023. https://arxiv.org/abs/2302.01572
20. MCCG: A ConvNeXt-Based Multiple-Classifier Method for Cross-View Geo-Localization — IEEE TCSVT 2024. https://ieeexplore.ieee.org/document/10185134 · https://github.com/mode-str/crossview
21. CCR: A Counterfactual Causal Reasoning-Based Method for Cross-View Geo-Localization — IEEE 2024. https://ieeexplore.ieee.org/document/10589694
22. UAV's Status Is Worth Considering: A Fusion Representations Matching Method for Geo-Localization (MBF) — Sensors 2023. https://doi.org/10.3390/s23020720
23. OS-FPI: A Coarse-to-Fine One-Stream Network for UAV Geo-Localization — 2024. Offset-regression fine stage, RDS 76.25 on UL14. https://arxiv.org/abs/2403.06148
24. MCFA: Multi-Scale Cascade and Feature Adaptive Alignment Network for Cross-View Geo-Localization — 2025. https://pmc.ncbi.nlm.nih.gov/articles/PMC12299452/
25. GeoCLIP: CLIP-Inspired Alignment between Locations and Images for Effective Worldwide Geo-localization — NeurIPS 2023. https://arxiv.org/abs/2309.16020
26. PIGEON: Predicting Image Geolocations — CVPR 2024 Highlight. Semantic geocells + multi-task contrastive pretraining. https://arxiv.org/abs/2307.05845 · https://github.com/LukasHaas/PIGEON
27. AnyLoc: Towards Universal Visual Place Recognition — IEEE RA-L 2023. Zero-shot DINOv2+VLAD/GeM, no cross-view-specific training. https://arxiv.org/abs/2308.00688 · https://github.com/AnyLoc/AnyLoc (BSD-3-Clause)
28. Rethinking Visual Geo-localization for Large-Scale Applications (CosPlace) — CVPR 2022. https://arxiv.org/abs/2204.02287 · https://github.com/gmberton/CosPlace
29. EigenPlaces: Training Viewpoint Robust Models for Visual Place Recognition — ICCV 2023. https://github.com/gmberton/EigenPlaces
30. MixVPR: Feature Mixing for Visual Place Recognition — WACV 2023. All-MLP aggregation, R@1 94.6% Pitts250k. https://github.com/amaralibey/MixVPR
31. Optimal Transport Aggregation for Visual Place Recognition (SALAD) — CVPR 2024. https://arxiv.org/abs/2311.15937 · https://github.com/serizba/salad (GPL-3.0)
32. BoQ: A Place is Worth a Bag of Learnable Queries — CVPR 2024. https://github.com/amaralibey/Bag-of-Queries
33. MegaLoc: One Retrieval to Place Them All — 2025. https://arxiv.org/abs/2502.17237
34. CliqueMining — Izquierdo & Civera, 2025. Graph-based batch sampling atop SALAD-style backbone.
35. Towards Seamless Adaptation of Pre-trained Models for Visual Place Recognition (SelaVPR) / SelaVPR++ — ICLR 2024 / 2025. https://arxiv.org/abs/2402.14505 · https://arxiv.org/abs/2502.16601
36. CricaVPR: Cross-image Correlation-aware Representation Learning for Visual Place Recognition — CVPR 2024. https://github.com/Lu-Feng/CricaVPR
37. Patch-NetVLAD: Multi-Scale Fusion of Locally-Global Descriptors for Place Recognition — CVPR 2021. https://arxiv.org/abs/2103.01486 · https://github.com/QVPR/Patch-NetVLAD
38. TransVPR: Transformer-based place recognition with multi-level attention aggregation and re-ranking — CVPR 2022.
39. R2Former: Unified Retrieval and Reranking Transformer for Place Recognition — CVPR 2023 Highlight. https://github.com/bytedance/R2Former
40. LightGlue: Local Feature Matching at Light Speed — ICCV 2023. https://github.com/cvg/LightGlue (Apache-2.0; SuperPoint weights restrictive)
41. LoFTR: Detector-Free Local Feature Matching with Transformers — CVPR 2021. https://github.com/zju3dv/LoFTR
42. RoMa: Robust Dense Feature Matching — CVPR 2024. https://github.com/Parskatt/RoMa (MIT; DINOv2 backbone Apache-2.0)
43. Grounding Image Matching in 3D with MASt3R — ECCV 2024. https://arxiv.org/abs/2406.09756 · https://github.com/naver/mast3r (CC BY-NC-SA 4.0, non-commercial)
44. Kornia: an Open Source Differentiable Computer Vision Library for PyTorch — WACV 2020. https://arxiv.org/abs/1910.02190 · https://github.com/kornia/kornia (Apache-2.0)
45. OrienterNet: Visual Localization in 2D Public Maps with Neural Matching — CVPR 2023. https://arxiv.org/abs/2304.02009 · https://github.com/facebookresearch/OrienterNet (weights CC-BY-NC)
46. SNAP: Self-Supervised Neural Maps for Visual Positioning and Semantic Understanding — 2023. https://arxiv.org/abs/2306.05407
47. SeqSLAM (classic 2012 method; reviewed in comprehensive multi-modal place-recognition survey arXiv:2505.14068; event-camera reimplementation arXiv:1505.04548). Sequence-matching under severe appearance change.
48. SeqNet: Learning Descriptors for Sequence-based Hierarchical Place Recognition — RA-L/ICRA 2021. https://arxiv.org/abs/2102.11603 · https://github.com/oravus/seqNet
49. FoundLoc: Vision-based Onboard Aerial Localization in the Wild — 2023. VIO+VPR foundation model, ~20 m accuracy, 1.934 Hz on Jetson Xavier NX. https://arxiv.org/abs/2310.16299
50. Vision-Based GNSS-Free Localization for UAVs in the Wild — 2022. SuperPoint+SuperGlue drone-vs-satellite matching. https://arxiv.org/abs/2210.09727 · https://github.com/TerboucheHacene/visual_localization
51. LSVL: Large-scale season-invariant visual localization for UAVs — Robotics and Autonomous Systems 2023. 12.6–18.7 m lateral error, orthoprojection + sequential updates. https://arxiv.org/abs/2212.03581
52. Season-invariant GNSS-denied visual localization for UAVs — 2021/2022. https://arxiv.org/abs/2110.01967
53. Hierarchical Localization (hloc) — Sarlin et al. Modular retrieval+matching visual-localization toolbox. https://github.com/cvg/Hierarchical-Localization (Apache-2.0)
54. VPR-Bench: An Open-Source Visual Place Recognition Evaluation Framework with Quantifiable Viewpoint and Appearance Change — IJCV 2021. https://arxiv.org/abs/2005.08135
55. OpenVPRLab — Ali-bey, 2024. Open-source modular VPR training framework. https://github.com/amaralibey/OpenVPRLab
56. Deep Visual Geo-localization Benchmark — CVPR 2022 Oral. Systematic backbone/aggregation/mining ablation framework. https://arxiv.org/abs/2204.03444 · https://github.com/gmberton/deep-visual-geo-localization-benchmark
57. Visual place recognition for aerial imagery: A survey — 2024/2025 (Robotics and Autonomous Systems). https://arxiv.org/abs/2406.00885
58. Cross-view Geo-localization: A Survey — 2024. https://arxiv.org/abs/2406.09722
59. Visual and Object Geo-localization: A Comprehensive Survey — 2021/2022. https://arxiv.org/abs/2112.15202
60. Image Matching for UAV Geolocation: Classical and Deep Learning Approaches — MDPI Journal of Imaging, 2025, 11(11):409. https://www.mdpi.com/2313-433X/11/11/409
61. A survey of cross-view geo-localization methods based on deep learning — Journal of Image and Graphics (CJIG). https://www.cjig.cn/en/article/doi/10.11834/jig.230858/
62. Satellite-Free Training for Drone-View Geo-Localization — 2026. Pseudo-orthophoto generation via 3D Gaussian splatting + PCA-guided orthographic projection. https://arxiv.org/abs/2604.01581
63. Unifying UAV Cross-View Geo-Localization via 3D Geometric Perception — 2026. Joint retrieval + view alignment + pose estimation, BEV rendering from reconstructed 3D scene. https://arxiv.org/abs/2604.01747
64. Sky2Ground: A Benchmark for Site Modeling under Varying Altitude — 2026. Google Earth Studio synthetic mesh rendering, controlled pose/altitude/incidence for synthetic oblique satellite-style training data. https://arxiv.org/abs/2603.13740

---

## 7. Open questions

| # | Question | Why literature doesn't close it |
|---|---|---|
| 1 | Does any published method report top-1 accuracy specifically for **oblique, 50–150 m altitude, homogeneous-field terrain** (as opposed to urban/campus scenes with distinctive buildings)? | Every drone↔satellite dataset in §2 is either urban/campus (buildings give strong, unambiguous local texture) or high-altitude (VPAir, ALTO). Homogeneous agricultural/field terrain — where neighboring tiles are genuinely close in appearance — is not represented as a distinct benchmark condition anywhere found. Our own recall@5 numbers (0.87–1.0) with rank-14 medians may be *harder* than any published cross-view benchmark because of this terrain homogeneity, not despite it. |
| 2 | What is the actual compute/accuracy tradeoff of re-ranking with classical RANSAC-homography-on-handcrafted-features (cheap, any tier) vs. a learned dense matcher (RoMa/LoFTR, server-only) for *this specific* oblique/nadir pair, at our tile resolution (Esri z17)? | No source directly compares classical vs. learned re-ranking specifically for drone-oblique-vs-satellite-ortho tiles at z17 resolution; the comparisons in §3.3/3.4 are all same-domain VPR (urban ground-level revisit), not cross-view. |
| 3 | Does sequence fusion (lever C) actually help when the 12 frames may straddle a tile boundary (i.e., the "true" answer legitimately changes across the sequence, per GTA-UAV's semi-positive framing [8]) rather than being 12 noisy observations of one fixed answer? | GTA-UAV/Game4Loc is the only dataset modeling this partial-overlap case, and it evaluates single-frame retrieval, not sequence fusion over drifting ground truth. SeqNet/SeqSLAM assume a largely static or slowly-drifting true location, which may not hold if 40-tile z17 gallery cells are small relative to a 12-frame trajectory at flight speed. |
| 4 | Is DINOv2 ViT-g (used by AnyLoc) replaceable by a smaller DINOv2 variant (ViT-S/B) fine-tuned or adapted (SelaVPR-style lightweight adapters [35]) without losing the recall@5 we already have, to make retrieval itself edge-feasible? | No source benchmarks AnyLoc-style zero-shot aggregation across DINOv2 model sizes for the aerial/cross-view case specifically; SelaVPR's adapter approach is validated on ground-level MSLS, not cross-view drone/satellite. |
| 5 | What licence-clean dense matcher is production-viable given MASt3R is CC BY-NC-SA and SuperPoint weights are "restrictive"? | LightGlue itself is Apache-2.0, but its best-performing paired detector (SuperPoint) is not cleanly licensed for commercial use; RoMa is MIT but built on a DINOv2 (Apache-2.0, fine) plus its own weights whose training-data licence provenance was not verified from the sources gathered here. This needs a dedicated licence audit before any production commitment, not literature review. |
| 6 | Can OpenVINO realistically host RoMa or LoFTR at usable latency, given both are PyTorch/CUDA-optimized in upstream tooling and our server (GB4005) is Intel-only, no CUDA? | No source in this survey reports OpenVINO or any non-CUDA inference numbers for RoMa, LoFTR, or MASt3R — every latency figure found (§5) is CUDA-GPU-measured. This is a first-party benchmarking gap that only our own OpenVINO export/quantization experiment can close. |
| 7 | Would oblique→nadir rectification (lever B) using our own telemetry-derived attitude actually reduce rank noise, or would residual DEM/terrain-relief error on flat homogeneous fields make a naive flat-ground homography assumption good enough — i.e., is a full 3D reconstruction pipeline (à la [62], [63]) overkill for our terrain? | The 3D-reconstruction-based rectification papers ([62], [63]) target scenes with real elevation relief (buildings, terrain); no source evaluates whether a much cheaper flat-ground IPM assumption is sufficient specifically for homogeneous low-relief field terrain, which is closer to our operating envelope than any cited benchmark's scenes. |
| 8 | How much of our rank-14 median is attributable to Esri World Imagery z17's own temporal staleness/mosaicking seams (adjacent tiles stitched from different capture dates) rather than the model's viewpoint-gap problem at all? | No cross-view geo-localization paper found here evaluates against a live commercial tile service (Esri/Google/Bing) with its documented mosaic-seam and multi-date stitching behavior — all datasets in §2 use a single controlled capture per reference area. This is a candidate confound the literature cannot rule in or out for us. |
| 9 | Does a rotation-invariant aerial VPR head (UltraVPR-style, RA-L 2025) close the rank gap on its own, without needing full cross-view retraining — i.e., is heading-normalization alone (lever B's cheapest variant) enough, or does it require the fuller oblique-to-nadir rectification? | UltraVPR was surfaced only as a search snippet (not independently fetched/verified to the same depth as the numbered bibliography) and is therefore *not* included as a numbered reference above; it is flagged here as a promising lead worth a dedicated follow-up read rather than a cited claim. |
