# Classical / photogrammetric visual geolocation — literature survey

Scope: the non-deep-learning and hybrid tradition of matching a UAV camera frame against
georeferenced satellite/aerial orthoimagery and DEMs to recover absolute position, as a
complement to `docs/conclusions/visual-geo-research/00-existing-state.md` (which digests the
deep-learning-first system already built on `feat/visual-geo`, EigenPlaces/CosPlace + LoFTR).
This document is pure literature research — no code, no design recommendations beyond the
feasibility verdicts asked for in §5. Every accuracy/compute/method claim below is tagged with a
reference number resolved in the bibliography (§3).

---

## 1. Seed papers — deep dive

### 1.1 [1] Shukla, Goel, Singh & Lohani (2014) — "Automatic geolocation of targets tracked by
aerial imaging platforms using satellite imagery"

**Venue.** *International Archives of Photogrammetry, Remote Sensing and Spatial Information
Sciences*, XL-1, 381–388, ISPRS 2014. Geoinformatics Laboratory, Dept. of Civil Engineering,
IIT Kanpur. DOI `10.5194/isprsarchives-XL-1-381-2014`.
PDF: https://isprs-archives.copernicus.org/articles/XL-1/381/2014/isprsarchives-XL-1-381-2014.pdf

**Problem.** Geolocate a target tracked by a surveillance/search-and-rescue aerial platform when
the platform itself lacks (or cannot trust) a positioning-and-orientation system (POS) — i.e. the
platform's own GPS/IMU is not assumed accurate enough, and the goal is to derive the target's
world coordinates purely from image registration against a georeferenced satellite reference.

**Method (5 steps, all classical).**
1. *Image enhancement* — both the stored aerial frame and the satellite reference are
   preprocessed for cross-sensor compatibility (illumination/scale/resolution differences).
2. *Feature-based registration with SURF* — blob/interest-point detection and 64-D orientation
   descriptors extracted independently in both images.
3. *Descriptor matching* — nearest-neighbour matching in descriptor space.
4. *Outlier removal* — RANSAC filters false correspondences and estimates a transformation
   (affine/homography) between aerial and satellite frames.
5. *Sequential frame registration* — once the first aerial frame is registered to the satellite
   image, subsequent aerial frames are registered to that *first frame* rather than re-running
   satellite matching every frame, reusing cached integral images/blob detections for efficiency.

**Inputs needed.** A georeferenced satellite reference image (their experiment used Google Earth
imagery of the IIT Kanpur campus), an aerial/UAV image stream, and (for validation) a scaled
1:250 physical building model. Two capture devices were compared: a Nikon D5100 SLR
(4928×3264, 16.2 MP, 27–83 mm) and an iPhone 4S (2592×1944, 8 MP, 35 mm); reference imagery was
captured near-nadir at ~20 m altitude, sensed imagery obliquely at 0.5–1 m.

**Accuracy.** Positional deviation of 4–6 px along X/Y; RMSE across validation runs 4.5–6.4 px;
the authors characterize this as accurate "up to a few meters in real time" [1].

**Compute.** No quantified runtime/complexity is reported; the paper's only efficiency claim is
qualitative — the sequential-registration trick (step 5) avoids recomputing satellite-side
features per frame [1].

**Limitations (stated).** SURF/RANSAC only performs well where the scene has "significant
features of varying geometry" — textureless or repetitive terrain breaks the match. Large scale
differences between sensed and reference imagery risk false matches once descriptor
orientation vectors exceed roughly six times the detected scale radius. Oblique viewing
introduces a positional shift relative to the nadir reference because of the altitude
difference between the two capture geometries. Validation used a miniature scale model rather
than a full-scale outdoor flight, so the reported pixel accuracy is not proven at operational
scale [1].

**What it needs to operate.** A pre-existing georeferenced satellite/aerial image of the AOI (no
DEM is used — the method is purely 2-D image-to-image, not terrain-aware) and, once registered,
only the aerial platform's own video stream (no external POS needed after the first fix).

**Forward citation trail (from its own reference list, which is the seed for §3 below).** SURF
[28], RANSAC [32], Eroglu & Yilmaz's TRN binary-search UAV localization [17], Fan et al.'s
entropy+edge UAV-to-satellite registration [15], Karel et al.'s archaeological UAV
georeferencing [18], Kawai & Saji's oblique-image-to-digital-map registration [19], Hong &
Zhang's wavelet-based remote-sensing registration [20], Reji & Vidya's satellite registration
survey [22], Tian & Kamata's diffusion-geodesic map registration [21], and the standard
tracking-systems reference (Blackman & Popoli, *Design and Analysis of Modern Tracking
Systems*, Artech House 1999) — cited for the target-tracking side, not registration.

### 1.2 [2] Saoud & Larabi (2024) — "Visual Geo-Localization from Images"

**Venue.** arXiv:2407.14910v1, 20 Jul 2024. USTHB University, Algiers. License CC BY-NC-ND 4.0.
https://arxiv.org/html/2407.14910v1

**Important scoping note.** This paper is *not* about UAV-camera-to-satellite-orthoimage
geolocation. It targets smartphone-camera place recognition in a pedestrian/ground context —
"where am I on campus" from a photo of a building or road junction — using panoramic-image
retrieval plus road-graph reasoning. It is included here as instructed (it is the user's second
seed), and it *is* directly relevant to one bullet of the classical tradition this survey covers
— road/junction-topology matching against an OpenStreetMap graph — but it should not be read as
a UAV-to-ortho registration paper. Its DFS-over-OSM-graph idea generalizes to the "edge/line/road
network matching" family in §2.

**Method — hybrid, three components chained.**
1. *SIFT-based place recognition.* Video frames (30–50% overlap) are stitched into panoramas;
   Detectron2 panoptic segmentation filters out frames dominated by road/pavement pixels
   (>40% discarded); SIFT descriptors are matched via FLANN k-NN against a reference panorama
   set, with a voting scheme choosing the best-matching place.
2. *VGG16 road-junction classifier.* Frames are resized to 224×224, fed to a VGG16 fine-tuned on
   its last 8 layers (class-weighted for imbalance, augmented with flips/brightness, 50 epochs +
   30 fine-tune epochs, batch 32, Adam/cross-entropy) to classify junction type (T, X, Y,
   roundabout).
3. *Graph-based map matching.* OpenStreetMap GeoJSON road data is converted into an
   intersection-as-node / segment-as-edge graph; junction types are inferred from node degree;
   Haversine distance and depth-first search trace candidate paths matching the observed
   junction-type sequence, disambiguating location purely from topology.

**Inputs.** Smartphone photos/video of buildings and junctions, OpenStreetMap road data (edited
via JOSM), Google Places API metadata. No GPS/Wi-Fi/external localization is used at inference.

**Accuracy.** Junction-type classification: precision/recall/F1 of 1.00/1.00/1.00 (T), 1.00/1.00/1.00
(X), 0.95/1.00/0.97 (Y), 1.00/0.67/0.80 (roundabout); overall accuracy 0.98. Class-weight
balancing alone moved test accuracy from 0.94 to 0.9767 and test loss from 0.53 to 0.0959 [2].
These numbers are on a small (282 real images, >20,000 augmented via ImageDataGenerator + SMOTE),
single-campus dataset — not evidence of general robustness.

**Compute.** No runtime or hardware figures are reported; the paper describes the target
deployment loosely as an "offline mobile application" [2].

**Limitations (stated).** Heavy dependence on training-data quantity/quality; deep components are
opaque (no explainability); dynamic scene changes (construction, seasonal foliage) degrade visual
cues; poor lighting/camera distortion complicate matching; the underlying dataset is small and
single-site, so generalization beyond that campus is unverified [2].

**Classification.** Hybrid: SIFT/FLANN (classical, 2004-vintage [27]) for panorama retrieval,
VGG16 transfer learning for junction classification, and pure symbolic graph search (DFS over
Haversine-weighted OSM graph) for final disambiguation — no learned component in the map-matching
stage itself.

**Bibliography contribution.** The paper's own references are almost entirely cross-view deep
geo-localization work (TransVLAD, OrienterNet, dominant-sets retrieval, etc.) plus two classical
line-simplification papers relevant to road-graph processing: Douglas & Peucker (1973) and Ramer
(1972), and Setitra & Larabi (2015) on SIFT-based binary shape matching — these are tangential to
the UAV-to-ortho tradition and are not separately numbered in §3 to avoid diluting the
UAV-specific bibliography, but are noted here for completeness.

---

## 2. Taxonomy of classical/hybrid registration approaches

| Approach | Signal used | Invariances | Typical accuracy | Compute | Failure modes | Key refs |
|---|---|---|---|---|---|---|
| NCC / ZNCC template matching | Raw pixel intensity, normalized | None (needs pre-rectified, similar-scale/rotation patch) | Sub-pixel on well-textured planar scenes; degrades sharply off-nadir | Very low — integral-image trick makes NCC near-constant-time per search window | Repetitive texture, multimodal sensor gap (season/illumination), no scale/rotation handling without external pyramid | [34] |
| Multi-resolution / pyramid NCC search | Same, at coarse→fine image pyramid | Adds robustness to small scale drift, still no rotation | Meter-level with GPS-scale search window | Low–moderate (log search space) | Same texture/multimodal limits; pyramid mismatches propagate downward | [1], [15] |
| Phase correlation (FFT, incl. log-polar for scale/rotation) | Fourier-domain phase, log-polar remap for scale/rotation | Translation always; rotation+scale via log-polar extension | 1/10–1/100 px translational sub-pixel accuracy reported for the base technique | O(N log N) via FFT — cheap even on CPU | Sensitive to non-rigid/perspective (needs prior ortho-rectification), noise-robust but breaks under strong multimodal gap | [35] |
| Mutual information (MI) similarity | Joint intensity histogram / statistical dependence, not raw intensity | Robust to nonlinear intensity mapping — the classic answer to season/sensor multimodality | Meter-level in textured urban scenes [4]; degrades in weak-texture terrain [4] | Moderate–high (dense search over MI surface is costly; needs coarse-to-fine or particle-filter search to be real-time) | Poor in weak-texture/homogeneous regions; dense global search is the main cost driver | [4], [6], [33] |
| Edge/line/road-network matching (Hough, LSD, chamfer) | Extracted line/edge/road topology, not pixel intensity | Strong illumination/season invariance (edges and road layout persist) | Meter-level where road network is legible; fails without one | Moderate (edge extraction + hierarchical chamfer search is pyramidal, sub-second per frame is typical) | Featureless terrain (forest, desert, open water), occluded/absent road network, road-network drift vs. real pavement edges | [15], [19], [26], [36], [37], [38], [2] |
| Feature-based (SIFT/RootSIFT/ASIFT/SURF/ORB + RANSAC homography) | Local keypoints + descriptors | SIFT/SURF: scale+rotation; ASIFT: + full affine/viewpoint tilt; ORB: rotation, binary/fast | Seed [1]: 4–6 px / few-meter RMSE with SURF+RANSAC; [14]: decimeter-level with a tuned feature pipeline | SIFT/SURF: moderate (not real-time on embedded CPU at full res); ORB: low (2 orders of magnitude faster than SIFT [29]); ASIFT: high (dozens of synthetic affine views per image [31]) | Weak/repetitive texture, strong seasonal/illumination change (classic SIFT/SURF/ORB descriptors are not season-invariant), wide viewpoint tilt breaks plain SIFT/SURF (motivates ASIFT) | [1], [12], [13], [14], [27], [28], [29], [30], [31], [32] |
| Orthorectify-then-2D-match (IPM from telemetry, then any 2-D method above) | Rectified nadir view + any similarity/feature method | Removes attitude-induced perspective distortion before matching, so the 2-D method's own invariances apply cleanly | Matches whatever the downstream 2-D method achieves, minus attitude-rectification error | Cost of the 2-D method + one warp; rectification itself is cheap (single homography) | Rectification error compounds with attitude/AGL sensor error (propagates directly into match position, see §1.1 error-budget discussion below) | [9], [1] |
| DEM ray-casting for ground-point geolocation (no matching, pure geometry) | Camera ray + DEM elevation, intersected | Invariant to all visual appearance — geometry only | Meters, bounded by attitude and AGL error, not by image content — 3 m demonstrated on a real fixed-wing flight test [13] | Very low (ray/DEM intersection is closed-form or a short iterative search) | Attitude and altitude (AGL) sensor error dominate; DEM staleness/resolution caps precision; needs *some* telemetry, no imagery needed on the geolocation side itself | [13], [41], [42], [43], [44] |
| Terrain-referenced navigation (TRN/TERCOM/SITAN, DEM correlation of altimeter profile) | 1-D or scalar ground-clearance/altimeter profile vs. DEM | Invariant to all visual appearance and lighting (no camera needed) | TERCOM: sub-INS-drift accuracy over rugged terrain (qualitative, [23]); SITAN AFTI/F-16 flight test: 75 m median radial error over DTED terrain [25] | Low (1-D/2-D correlation search over a DEM patch, classically real-time on 1980s avionics [23]) | Flat/featureless terrain (ocean, plains, deserts) — the DEM gradient carries no information there; needs an altimeter, not a camera | [23], [24], [25], [26] |
| Particle filter over a similarity/matching field | Any of the above similarity scores, fused across frames via Monte-Carlo localization | Recovers from per-frame ambiguity by accumulating evidence | Converges from ambiguous multi-modal posteriors to tens-of-meters after sufficient travel [9], [10] | Moderate (population size × per-particle likelihood evaluation, parallelizable) | Slow convergence in weak-texture corridors; particle depletion under long featureless stretches; still bounded by the underlying per-frame similarity method's failure modes | [5], [8], [9], [10], [26] |
| EKF/UKF with vision position updates | Absolute vision fix as a measurement update on an inertial/GPS-denied state estimate | Smooths noisy per-frame fixes into a consistent trajectory | Bounded by fix accuracy and update rate; classic SITAN-style EKF gave 75 m class accuracy [25] | Low (EKF update is O(state²), trivial at UAV state-vector sizes) | Divergence if vision fixes are biased (not just noisy) — EKF trusts a Gaussian error model that DEM/registration outliers violate | [24], [25], [3] |
| VO/SLAM + absolute map anchoring (MSCKF, ORB-SLAM style, corrected periodically) | Visual-inertial relative odometry, drift-corrected by an occasional absolute registration fix | High-rate relative pose (100 Hz class) with periodic absolute correction | Relative-pose accuracy is odometry-class (sub-% of distance traveled) between corrections; absolute accuracy reverts to the correction method's accuracy at each fix | VO/VIO itself is real-time-capable on embedded CPU (MSCKF was explicitly designed for this: O(features) not O(features²) [39]); ORB-SLAM adds mapping/loop-closure overhead [40] | Drift between corrections; registration-based corrections inherit all failure modes of whichever 2-D/DEM method supplies them | [3], [39], [40] |

---

## 3. Annotated bibliography

Seed papers are [1]–[2]; the rest were reached via forward/backward citation trails from the
seeds (ISPRS reference list, IEEE/arXiv "cited by", and targeted searches on the named authors
the task specified). Two paper titles surfaced during the search (a 2025 MDPI classical-vs-deep
UAV geolocation survey and a 2019 *Multimedia Tools and Applications* UAV/Google-map registration
paper) could not be resolved to a verified author list before publisher paywalls blocked
retrieval; they are omitted rather than cited with unconfirmed authorship.

1. **Shukla, P. K., Goel, S., Singh, P., Lohani, B. (2014).** "Automatic geolocation of targets
   tracked by aerial imaging platforms using satellite imagery." *ISPRS Archives*, XL-1, 381–388.
   SURF+RANSAC registration of aerial video to a satellite reference, sequential re-registration
   to the first frame. 4–6 px / ~few-meter accuracy. Seed paper 1; see §1.1.
   https://isprs-archives.copernicus.org/articles/XL-1/381/2014/isprsarchives-XL-1-381-2014.pdf

2. **Saoud, R., Larabi, S. (2024).** "Visual Geo-Localization from Images." arXiv:2407.14910.
   SIFT panorama retrieval + VGG16 junction classification + DFS over an OSM road graph for
   pedestrian place recognition. 0.98 overall junction-classification accuracy on a small
   single-campus dataset. Seed paper 2 (ground-level, not UAV-to-ortho); see §1.2.
   https://arxiv.org/html/2407.14910v1

3. **Conte, G., Doherty, P. (2009).** "Vision-Based Unmanned Aerial Vehicle Navigation Using
   Geo-Referenced Information." *EURASIP Journal on Advances in Signal Processing*, 2009,
   article 387308. Combines inertial sensing, visual odometry, and registration of onboard video
   to a geo-referenced aerial image inside a SLAM-style estimator; flight-tested on a helicopter
   UAV. Drift-free but registration accuracy depends on terrain structure.
   https://link.springer.com/article/10.1155/2009/387308

4. **Yol, A., Delabarre, B., Dame, A., Dartois, J.-É., Marchand, E. (2014).** "Vision-based
   Absolute Localization for Unmanned Aerial Vehicles." *IEEE/RSJ IROS 2014*, 3429–3434.
   Mutual-information similarity against a stitched geo-referenced map; robust to local/global
   scene variation but poor in weak-texture terrain.
   https://www.irisa.fr/lagadic/pdf/2014_iros_yol.pdf

5. **Shan, M., Wang, F., Lin, F., Gao, Z., Tang, Y. Z., Chen, B. M. (2015/2017).** "Google Map
   Aided Visual Navigation for UAVs in GPS-denied Environment." *IEEE ROBIO 2015* (also
   arXiv:1703.10125). Correlation-based initialization, optical-flow inter-frame prediction,
   HOG-feature registration against low-resolution Google Map tiles, particle-filter
   coarse-to-fine search — first reported use of low-res Google Map + HOG for UAV navigation.
   https://arxiv.org/abs/1703.10125

6. **Patel, B., Barfoot, T. D., Schoellig, A. P. (2020).** "Visual Localization with Google Earth
   Images for Robust Global Pose Estimation of UAVs." *IEEE ICRA 2020*, 6491–6497. Matches live
   UAV frames against pre-rendered Google Earth views (rendered from known poses) using dense
   mutual information for metric, GPS-denied global pose estimation.
   https://www.researchgate.net/publication/344981713

7. **Goforth, H., Lucey, S. (2019).** "GPS-Denied UAV Localization using Pre-existing Satellite
   Imagery." *IEEE ICRA 2019*, 2974–2980. Downward monocular camera matched to satellite imagery
   via CNN feature representations trained to bridge seasonal/perspective gaps, with a joint
   optimization over adjacent-frame and satellite-map error; open-source code released.
   https://publications.ri.cmu.edu/gps-denied-uav-localization-using-pre-existing-satellite-imagery

8. **Mantelli, M., Pittol, D., Neuland, R., Ribacki, A., Maffei, R., Jorge, V., Prestes, E.,
   Kolberg, M. (2019).** "A novel measurement model based on abBRIEF for global localization of a
   UAV over satellite images." *Robotics and Autonomous Systems*, 112, 304–319. abBRIEF-based
   measurement model feeding a particle filter (Monte-Carlo localization) for downward-camera
   global localization over a satellite-image map.
   https://www.sciencedirect.com/science/article/abs/pii/S092188901830438X

9. **Kinnari, J., Verdoja, F., Kyrki, V. (2021).** "GNSS-denied geolocalization of UAVs by visual
   matching of onboard camera images with orthophotos." *IEEE ICAR 2021*, 555–562 (also
   arXiv:2103.14381). Monte-Carlo localization using only IMU, an arbitrarily-pointed camera, and
   an orthoimage map; orthorectifies the UAV image under a local-planarity assumption, relaxing
   the downward-camera requirement.
   https://arxiv.org/abs/2103.14381

10. **Kinnari, J., Verdoja, F., Kyrki, V. (2022).** "Season-invariant GNSS-denied visual
    localization for UAVs." arXiv:2110.01967. Matches UAV camera images to georeferenced
    orthophotos with a CNN trained to be invariant to winter/summer appearance change, fused via
    Monte-Carlo localization to handle the UAV-vs-map perspective discrepancy.
    https://arxiv.org/abs/2110.01967

11. **Nassar, A., Amer, K., ElHakim, R., ElHelw, M. (2018).** "A Deep CNN-Based Framework For
    Enhanced Aerial Imagery Registration with Applications to UAV Geolocalization." *CVPR
    Workshops 2018*, 1513–1523. Deep-CNN-enhanced registration of onboard UAV camera imagery
    against satellite imagery, adding contextual scene information to raise geolocalization
    accuracy without GPS.
    https://openaccess.thecvf.com/content_cvpr_2018_workshops/papers/w30/Nassar_A_Deep_CNN-Based_CVPR_2018_paper.pdf

12. **Viswanathan, A., Pires, B. R., Huber, D. (2014).** "Vision Based Robot Localization by
    Ground to Satellite Matching in GPS-denied Situations." *IEEE/RSJ IROS 2014*. Ground vehicle
    (not UAV) case: warps ground images to a bird's-eye view, then compares against a grid of
    satellite locations using whole-image descriptors; benchmarks SIFT, SURF, FREAK, PHOW — SIFT
    wins overall even as satellite-map complexity grows.
    https://www.ri.cmu.edu/pub_files/2015/9/anirudh_viswanathan_iros.pdf

13. **Barber, D. B., Redding, J. D., McLain, T. W., Beard, R. W., Taylor, C. N. (2006).**
    "Vision-based Target Geo-location using a Fixed-wing Miniature Air Vehicle." *Journal of
    Intelligent & Robotic Systems*, 47(4), 361–382. Pure sensor-model geolocation (pixel + MAV
    position/attitude + camera pose angles → world coordinates), with four explicit
    error-reduction techniques (RLS filtering, bias estimation, flight-path selection, wind
    estimation); flight-tested to 3 m accuracy against known GPS ground truth. The clearest
    published error-budget treatment found in this survey for the sensor-model/ray-cast family.
    https://link.springer.com/content/pdf/10.1007/s10846-006-9088-7.pdf

14. **Zhuo, X., Koch, T., Kurz, F., Fraundorfer, F., Reinartz, P. (2017).** "Automatic UAV Image
    Geo-Registration by Matching UAV Images to Georeferenced Image Data." *Remote Sensing*, 9(4),
    376. Feature-matching method for nadir/near-nadir UAV vs. aerial imagery reaching
    decimeter-level registration accuracy.
    https://www.mdpi.com/2072-4292/9/4/376

15. **Fan, B., Du, Y., Zhu, L., Tang, Y. (2010).** "The Registration of UAV Down-Looking Aerial
    Images to Satellite Images with Image Entropy and Edges." *Intelligent Robotics and
    Applications (ICIRA 2010)*, Springer, 609–617. Composite deformable template matching fusing
    edge and entropy features; uses UAV altitude to scale the search and constrain the matching
    region in the satellite image.
    https://link.springer.com/chapter/10.1007/978-3-642-16584-9_59

16. **Son, K. et al. (2009).** "UAV Global Pose Estimation by Matching Forward-Looking Aerial
    Images with Satellite Images." *IEEE/RSJ IROS 2009*. Extracts buildings from forward-looking
    aerial imagery, reconstructs a 3-D building structure via the fundamental matrix, and matches
    that structure against a satellite image with a particle filter for global pose. (Full author
    list beyond the first-listed author could not be confirmed from open sources; existence and
    method confirmed via IEEE Xplore document 5354165 and a University of Missouri conference-CD
    mirror.)
    http://vigir.missouri.edu/~gdesouza/Research/Conference_CDs/IEEE_IROS_2009/papers/1273.pdf

17. **Eroglu, O., Yilmaz, G. (2014).** "A terrain referenced UAV localization algorithm using
    binary search method." *Journal of Intelligent & Robotic Systems*, 73(1–4), 309–323.
    Terrain-referenced UAV localization (DEM correlation) using a binary-search strategy over the
    candidate-position space to speed convergence. Cited by seed [1].

18. **Karel, W. et al. (2014).** "Investigation on the Automatic Geo-Referencing of Archaeological
    UAV Photographs by Correlation with Pre-Existing Ortho-Photos." *ISPRS Archives*, XL-5,
    307–313. Correlation-based automatic georeferencing of UAV photos against pre-existing
    ortho-photos, in an archaeological-survey (feature-sparse, non-urban) setting. Cited by seed
    [1].
    https://isprs-archives.copernicus.org/articles/XL-5/307/2014/

19. **Kawai, S., Saji, H. (2007).** "Automatic registration of aerial oblique images and digital
    maps." *SICE Annual Conference 2007*. Registers oblique aerial imagery directly to a digital
    map (not a raster ortho-image), an early instance of edge/vector-map matching rather than
    pixel-to-pixel image matching. Cited by seed [1].

20. **Hong, G., Zhang, Y. (2008).** "Wavelet-based image registration technique for
    high-resolution remote sensing images." *Computers & Geosciences*, 34(12), 1708–1720.
    Multi-resolution wavelet-domain registration for high-res remote-sensing imagery — the
    multi-resolution/pyramid-search idea applied via wavelets rather than a raw Gaussian
    pyramid. Cited by seed [1].

21. **Tian, L., Kamata, S. I. (2006).** "Diffusion geodesic path for automatic image-map
    registration." *IEEE ISSPIT 2006*, 944–949. Registers imagery to a map using geodesic paths
    computed via anisotropic diffusion, a precursor to modern edge-preserving registration
    approaches. Cited by seed [1].

22. **Reji, R., Vidya, R. (2012).** "Comparative analysis in satellite image registration." *IEEE
    Intl. Conf. on Computational Intelligence and Computing Research 2012*. Survey/comparison of
    satellite-image registration techniques. Cited by seed [1].

23. **Golden, J. P. (1980).** "Terrain Contour Matching (TERCOM): A Cruise Missile Guidance Aid."
    *Proc. SPIE* 238, 10. The foundational TERCOM paper: an inertial guidance system aided by
    comparing a stored terrain contour map against in-flight radar-altimeter measurements,
    developed from a late-1950s Chance-Vought concept; substantially more accurate than pure
    inertial navigation and permits lower, harder-to-detect flight profiles.
    https://www.spiedigitallibrary.org/conference-proceedings-of-spie/0238/0000/Terrain-Contour-Matching-TERCOM-A-Cruise-Missile-Guidance-Aid/10.1117/12.959127.short

24. **Hostetler, L. D., Andreas, R. D. (1983).** "Nonlinear Kalman filtering techniques for
    terrain-aided navigation." *IEEE Transactions on Automatic Control*, 28(3). The SITAN
    (Sandia Inertial Terrain-Aided Navigation) formulation: an Extended Kalman Filter fuses
    scalar radar-altimeter ground-clearance measurements against a local DEM, continuously
    correcting an inertial navigation solution rather than batch-correlating a contour segment
    (as TERCOM does).

25. **AFTI/SITAN Final Report (1988/89).** "Advanced Fighter Technology Integration / Sandia
    Inertial Terrain-Aided Navigation." NTIS DE89004000. Real-world SITAN flight test on the
    AFTI/F-16 at Edwards AFB (Sept 1986–Apr 1987), tracked against ground radar for validation:
    75 m median radial error using DTED terrain data supplied by the Defense Mapping Agency — the
    clearest quantitative operational-accuracy figure found in this survey for pure DEM-correlation
    TRN.
    https://ntrl.ntis.gov/NTRL/dashboard/searchResults/titleDetail/DE89004000.xhtml

26. **Dumble, S. J., Gibbens, P. W.** "Airborne vision-aided navigation using road intersection
    features" and "Particle filter studies on terrain referenced navigation" (IEEE). Two related
    lines of work: (a) using road-intersection features (an edge/graph-matching approach, akin to
    [2]/[19]) as vision-aided navigation landmarks, and (b) a Bayesian particle-filter
    formulation of terrain-referenced navigation/localization for UAVs using radar-altimeter
    measurements as an implicit position representation.
    https://www.researchgate.net/publication/303688226_Particle_filter_studies_on_terrain_referenced_navigation

27. **Lowe, D. G. (2004).** "Distinctive Image Features from Scale-Invariant Keypoints."
    *International Journal of Computer Vision*, 60(2), 91–110. The original SIFT paper — scale-
    and rotation-invariant local keypoints and descriptors; the baseline every later
    feature-based UAV registration method (including the seed [1]'s SURF choice) is positioned
    against.

28. **Bay, H., Ess, A., Tuytelaars, T., Van Gool, L. (2008).** "Speeded-Up Robust Features
    (SURF)." *Computer Vision and Image Understanding*, 110(3), 346–359. Faster
    integral-image-based alternative to SIFT; the descriptor used directly by seed paper [1].

29. **Rublee, E., Rabaud, V., Konolige, K., Bradski, G. (2011).** "ORB: An efficient alternative
    to SIFT or SURF." *ICCV 2011*. FAST keypoints + rotation-aware BRIEF binary descriptors;
    roughly two orders of magnitude faster than SIFT at comparable matching performance in many
    scenes, and unencumbered by SIFT/SURF's (now-expired) patents — the practical choice for
    onboard/embedded feature matching.
    https://en.wikipedia.org/wiki/Oriented_FAST_and_rotated_BRIEF

30. **Arandjelović, R., Zisserman, A. (2012).** "Three things everyone should know to improve
    object retrieval." *CVPR 2012*. Introduces RootSIFT — L1-normalize then square-root the SIFT
    descriptor so Euclidean distance approximates the Bhattacharyya coefficient — a drop-in
    accuracy improvement requiring no change to the underlying SIFT extractor.
    https://www.robots.ox.ac.uk/~vgg/publications/2012/Arandjelovic12/arandjelovic12.pdf

31. **Morel, J.-M., Yu, G. (2009).** "ASIFT: A New Framework for Fully Affine Invariant Image
    Comparison." *SIAM Journal on Imaging Sciences*, 2(2), 438–469. Extends SIFT with simulated
    latitude/longitude viewpoint sampling to achieve full affine invariance, handling "transition
    tilt" far beyond plain SIFT (≈2), Harris/Hessian-Affine (≈2.5) or MSER (≈10) — directly
    relevant to the extreme oblique-vs-nadir viewpoint gap between a UAV frame and a nadir
    satellite ortho-image, at the cost of evaluating dozens of synthetic affine views per image.
    https://www.ipol.im/pub/art/2011/my-asift/

32. **Fischler, M. A., Bolles, R. C. (1981).** "Random Sample Consensus: A Paradigm for Model
    Fitting with Applications to Image Analysis and Automated Cartography." *Communications of
    the ACM*, 24(6), 381–395. The RANSAC outlier-rejection step used by essentially every
    feature-based registration paper in this survey, including seed [1].

33. **Viola, P., Wells, W. M. III (1997).** "Alignment by Maximization of Mutual Information."
    *International Journal of Computer Vision*, 24(2), 137–154. The foundational MI-registration
    paper — an information-theoretic pose estimate that needs only object shape, not surface
    photometric properties, and is robust to illumination change; the theoretical basis for [4]
    and [6].
    https://www.ccs.neu.edu/home/jaa/CSG399.05F/Topics/Papers/ViolaWe97.pdf

34. **Lewis, J. P. (1995).** "Fast Normalized Cross-Correlation." *Vision Interface 1995*,
    120–123. Shows unnormalized cross-correlation (efficiently computed via FFT or a running
    sum) can be normalized cheaply using precomputed integral tables of the image and its
    square — the standard efficient-NCC algorithm underlying template-matching registration.
    https://scribblethink.org/Work/nvisionInterface/nip.pdf

35. **Reddy, B. S., Chatterji, B. N. (1996).** "An FFT-Based Technique for Translation, Rotation
    and Scale-Invariant Image Registration." *IEEE Transactions on Image Processing*, 5(8).
    Extends phase correlation with a log-polar Fourier remap to recover rotation and scale in
    addition to translation, entirely in the frequency domain; base phase correlation achieves
    1/10–1/100-pixel sub-pixel accuracy and is highly robust to gain/offset intensity
    differences and noise.

36. **Borgefors, G. (1988).** "Hierarchical Chamfer Matching: A Parametric Edge Matching
    Algorithm." *IEEE Transactions on Pattern Analysis and Machine Intelligence*, 10(6), 849–865.
    Chamfer-distance-transform edge matching over an image pyramid — cheap, no learned model,
    the classical way to match extracted edges (e.g. road/building outlines) between two images
    without dense pixel correlation.

37. **von Gioi, R. G., Jakubowicz, J., Morel, J.-M., Randall, G. (2010).** "LSD: A Fast Line
    Segment Detector with a False Detection Control." *IEEE Transactions on Pattern Analysis and
    Machine Intelligence*, 32(4), 722–732. Linear-time, parameter-free, sub-pixel-accurate line
    detector with a formal (Helmholtz-principle) false-alarm control — the standard modern
    front-end for extracting road/building edges ahead of chamfer or Hough-based map matching.

38. **Duda, R. O., Hart, P. E. (1972).** "Use of the Hough Transformation to Detect Lines and
    Curves in Pictures." *Communications of the ACM*, 15(1), 11–15. The generalized Hough
    transform for line/curve detection — the classical alternative to LSD [37] for extracting the
    linear road/structure features that edge/road-network matching methods correlate against a
    map.

39. **Mourikis, A. I., Roumeliotis, S. I. (2007).** "A Multi-State Constraint Kalman Filter for
    Vision-Aided Inertial Navigation." *IEEE ICRA 2007*. MSCKF: an Error-State EKF that keeps a
    sliding window of camera poses (not 3-D landmark positions) in the state vector, enforcing
    multi-view feature constraints without ever estimating landmark 3-D location — cuts
    complexity from quadratic to linear in the number of tracked features, enabling real-time
    VIO on resource-constrained (embedded) platforms.

40. **Mur-Artal, R., Montiel, J. M. M., Tardós, J. D. (2015).** "ORB-SLAM: A Versatile and
    Accurate Monocular SLAM System." *IEEE Transactions on Robotics*, 31(5), 1147–1163. Full
    tracking/mapping/relocalization/loop-closure SLAM built entirely on ORB [29] features; a
    natural VO backbone to pair with periodic absolute-position corrections from any of the 2-D
    or DEM registration methods above.
    https://arxiv.org/abs/1502.00956

41. **Farr, T. G. et al. (2007).** "The Shuttle Radar Topography Mission." *Reviews of
    Geophysics*, 45, RG2004. The SRTM mission and dataset description: near-global (56°S–60°N)
    C-band/X-band radar-derived elevation, 1 arc-second (~30 m) postings, linear vertical
    absolute-height error under 16 m. See §4 for licensing/coverage detail.

42. **Copernicus DEM (GLO-30) — ESA / Airbus Defence and Space / DLR license and product
    documentation.** Global 1 arc-second (~30 m) digital surface model (not bare-earth — includes
    buildings/vegetation), derived from the WorldDEM™ product with water-body flattening and
    river-flow consistency editing; free worldwide license except for two listed excluded
    countries, with a mandatory attribution notice. See §4.
    https://docs.sentinel-hub.com/api/latest/static/files/data/dem/resources/license/License-COPDEM-30.pdf

43. **JAXA (2021).** "ALOS Global Digital Surface Model 'ALOS World 3D - 30m' (AW3D30)," product
    documentation. 30 m mesh DSM downsampled from a 5 m source (ALOS PRISM stereo, 2006–2011);
    target accuracy 5 m RMSE vertical / 5 m horizontal; validated RMSE ≈ 5.4 m overall, as low as
    ≈ 2.7 m in low-relief regions. See §4.
    https://www.eorc.jaxa.jp/ALOS/en/dataset/aw3d30/

44. **Hawker, L., Uhe, P., Paulo, L., Sosa, J., Savage, J., Sampson, C., Neal, J. (2022).** "A
    30 m global map of elevation with forests and buildings removed." *Environmental Research
    Letters*, 17(2), 024016. FABDEM: machine-learning-estimated vegetation/building heights
    subtracted from Copernicus GLO-30 to produce the first global bare-earth 30 m DEM covering
    60°S–80°N. See §4.
    https://iopscience.iop.org/article/10.1088/1748-9326/ac4d4f

---

## 4. DEM / orthoimagery data sources

| Source | Resolution | Coverage | Vintage / update cadence | Vertical accuracy | Surface type | License | Ref |
|---|---|---|---|---|---|---|---|
| SRTM (NASA/NGA) | 1 arcsec (~30 m); 3 arcsec (~90 m) outside the US in older releases | ~56°S–60°N (near-global, misses high latitudes) | Single Feb-2000 radar mission, no updates | Linear absolute vertical error < 16 m | Digital surface model (first-return radar, includes canopy/buildings) | Public domain (NASA/USGS) | [41] |
| Copernicus DEM GLO-30 | 1 arcsec (~30 m); GLO-90 (~90 m) also available | Global, free tier excludes 2 named countries | Derived from WorldDEM™ (TanDEM-X 2010–2015 acquisition), static release | Not independently re-verified in this survey; product-spec target class is meter-level | Digital surface model (buildings/vegetation included, water bodies flattened) | Free with mandatory attribution notice; a licensed non-excluded-country caveat applies | [42] |
| ALOS World 3D-30m (AW3D30) | 30 m mesh, downsampled from a 5 m stereo-photogrammetric source | Global | ALOS PRISM stereo acquisitions 2006–2011, periodic version releases (v2.1/v2.2/v3.1 etc.) | Target 5 m RMSE vertical/horizontal; validated ≈5.4 m RMSE overall, ≈2.7 m in low-relief terrain | Digital surface model | Free (JAXA) | [43] |
| FABDEM | 30 m (built on Copernicus GLO-30 grid) | 60°S–80°N | 2022 release, built once from GLO-30 + ML height removal | Inherits Copernicus GLO-30 baseline error, improved for canopy/building bias specifically (bare-earth) | Bare-earth DEM (forest + building heights machine-learning-subtracted from GLO-30) | Free for research; check current Fathom/Bristol terms for commercial use | [44] |
| National DEMs (USGS 3DEP, IGN RGE ALTI, Ordnance Survey Terrain, etc.) | Typically 1 m–10 m (photogrammetric/LiDAR-derived, far finer than the global 30 m products) | National footprint only — not usable outside the issuing country | Varies widely, actively updated in many programs | Sub-meter to ~1 m class in LiDAR-derived programs | Bare-earth or DSM depending on program | Varies by country — many are free/open, some restricted; not independently verified per-country in this survey | — |
| Commercial/free-imagery orthophoto basemaps (Esri World Imagery, Google/Bing tiles, Sentinel-2/Planet) | Sub-meter (Esri/Google/Bing in many urban areas) to ~10 m (Sentinel-2) | Global but resolution/recency varies heavily by region | Continuously updated in commercial tile services; satellite revisit cadence for Sentinel-2/Planet | N/A (2-D imagery, not elevation) | — | Commercial tile services generally prohibit offline bulk caching/redistribution under their ToS; Sentinel-2 is open (Copernicus program) | (background context only, not separately numbered) |

**Reading this table against the taxonomy in §2:** the DEM-only methods (ray-casting, TRN/TERCOM/
SITAN) need only elevation, so the 30 m global products [41]–[44] are directly usable worldwide,
with FABDEM [44] the best-matched choice where the flight corridor has forest/urban canopy that
would otherwise bias a first-surface-return DEM. The 2-D image-matching methods (NCC, MI,
feature-based, edge/road) additionally need a reasonably recent, reasonably resolved orthophoto —
global 30 m elevation products carry no visual texture, so those methods depend on whichever tile
service or archival orthophoto is available for the AOI, which is the weaker link in practice for
missions outside well-mapped regions.

---

## 5. Feasibility verdicts

### 5(a) Onboard, CPU-only, Raspberry Pi/Jetson-class companion, pre-loaded corridor tiles

| Method | Feasible onboard? | Why |
|---|---|---|
| NCC/ZNCC template matching, single-scale | Yes | Integral-image NCC [34] is cheap enough for real-time CPU execution; the constraint is having a pre-rectified, roughly co-scaled reference tile cached for the corridor. |
| Multi-resolution/pyramid NCC | Yes | Adds only a log-factor of work over single-scale NCC; still CPU-tractable. |
| Phase correlation (incl. log-polar rotation/scale) | Yes | FFT-based [35], well within a Pi/Jetson CPU budget for tile-sized patches; needs the frame ortho-rectified first if attitude is non-nadir. |
| Orthorectify (IPM from telemetry) then 2-D match | Yes | The rectification itself is a single homography warp; cost is dominated by whichever 2-D method follows it. |
| ORB + RANSAC feature matching | Yes | ORB is explicitly ~100x cheaper than SIFT [29]; real-time on embedded CPU is its design goal, and RANSAC [32] outlier rejection is cheap at typical inlier counts. |
| Edge/line/road matching (LSD/Hough + chamfer) | Yes, where a road network exists in the corridor | LSD [37] and chamfer matching [36] are both linear/near-linear time; the limiting factor is whether the flight corridor actually contains legible road/edge structure, not compute. |
| DEM ray-casting for ground-point geolocation | Yes, trivially | Closed-form or short-iteration geometry against a cached DEM tile [13]; this is the cheapest method in the whole taxonomy and needs no imagery on the geolocation side at all. |
| TRN/TERCOM/SITAN (DEM correlation of an altimeter profile) | Yes, if the platform carries an altimeter (radar/laser) | Originally designed for 1980s-class avionics compute [23][24]; trivially fits a modern embedded CPU. Needs a rangefinder, not a camera — a different sensor requirement than the rest of this table. |
| SIFT/SURF full-descriptor matching | Marginal | Classically not real-time on embedded CPU at full resolution; workable at reduced resolution/frame rate or with a hardware accelerator, but ORB is the better default choice for this tier. |
| ASIFT (full affine invariance) | No, not at flight frame rate | Evaluating dozens of synthetic affine views per frame [31] is designed for offline/near-real-time desktop use, not an onboard loop. |
| Mutual-information dense global search | Marginal | The base MI computation is moderate, but the *dense search* over candidate offsets that [4]/[6] use is expensive; only tractable onboard if constrained to a small search window seeded by GPS/dead-reckoning, or run at reduced rate. |
| Particle filter over any similarity field | Yes, with a modest particle count | Per-particle likelihood evaluation is embarrassingly parallel and each individual evaluation is one of the cheap 2-D methods above; [9][10] were demonstrated in this class of onboard compute budget. |
| VO/VIO (MSCKF-style) + periodic absolute correction | Yes | MSCKF was explicitly designed for resource-constrained real-time operation [39]; ORB-SLAM's fuller mapping/loop-closure stack [40] is heavier but has run on embedded boards in prior UAV work. |
| CNN-descriptor hybrids (Goforth & Lucey, Nassar et al., Kinnari et al.) | Marginal-to-No on CPU-only; Yes with a Jetson-class GPU/NPU | These need a trained CNN forward pass per frame [7][10][11]; infeasible on a bare Raspberry Pi CPU at useful frame rates, but Jetson-class boards with a GPU/NPU can run lightweight versions — this is the boundary case between "classical onboard" and "needs an accelerator." |

**Summary for 5(a).** The purely geometric/DEM family (ray-casting, TRN/TERCOM/SITAN) and the
cheap 2-D signal-processing family (NCC, phase correlation, ORB+RANSAC, edge/chamfer matching)
are all comfortably CPU-feasible on a Pi/Jetson-class companion with pre-loaded corridor tiles,
at accuracies from meters (2-D image matching in good texture, e.g. seed [1]'s few-meter class)
down to the 3 m flight-tested figure for a well-instrumented sensor-model ray-cast [13], or the
75 m class for pure DEM-correlation TRN over rugged terrain with no camera at all [25]. Anything
requiring a CNN forward pass or a full ASIFT/dense-MI search should be treated as needing either
a GPU/NPU accelerator or reduced frame rate, not a bare CPU budget.

### 5(b) Station-side, offline/near-real-time corrector of the telemetry track

| Method | Feasible station-side? | Why |
|---|---|---|
| All of §5(a)'s methods | Yes, trivially | A station has orders of magnitude more compute than an embedded companion; every onboard-feasible method is a fortiori station-feasible. |
| ASIFT (full affine invariance) | Yes | Its own IPOL reference implementation targets exactly this offline/near-real-time regime [31]; well suited to a station reprocessing a saved flight log. |
| Dense mutual-information global search, unconstrained | Yes | Station-side compute removes the need to constrain the search window as tightly as onboard; matches the deployment context of [4] and [6], which were run offline/desktop-class. |
| CNN-descriptor hybrids (season-invariant CNNs, deep aerial-registration nets) | Yes | This is exactly the regime [7][9][10][11] were built and evaluated in — GPU-class compute, not embedded. |
| Particle filter / Monte-Carlo localization over a full flight track | Yes, and better-suited here than onboard | A station can run a much larger particle population and can reprocess forward *and* backward through a saved track (smoothing, not just filtering), which no onboard real-time loop can do. |
| Global bundle-style reconciliation (VO/VIO track + all available absolute fixes jointly optimized) | Yes | This is a natural station-side extension not attempted in real time by any onboard system in this survey — the closest analogue is offline SLAM/structure-from-motion reprocessing on top of an ORB-SLAM-style [40] track. |

**Summary for 5(b).** There is no method surveyed here that is *infeasible* station-side; the
station's role is specifically to absorb the compute-heavy tail (ASIFT, dense MI, CNN-descriptor
hybrids, large-population particle filters, full-track smoothing) that a companion computer
cannot run in real time, and to correct a saved telemetry track after the fact rather than guide
the vehicle live. Expected accuracy station-side is set by the same per-method figures in §2 —
station-side reprocessing buys headroom to run the *more expensive, more accurate* member of a
method family (e.g. ASIFT instead of ORB, dense MI instead of a constrained search window,
season-invariant CNN instead of raw SIFT) rather than a fundamentally different accuracy regime.

---

## 6. Open questions

1. **No single paper in this survey reports a like-for-like classical-vs-classical accuracy
   comparison on the same UAV dataset.** Every accuracy figure above ([1] 4–6 px, [13] 3 m, [14]
   decimeter-level, [25] 75 m) comes from a different platform, sensor, terrain type, and
   validation methodology (some are scale-model experiments, e.g. [1]). A controlled benchmark
   across NCC / phase-correlation / MI / ORB+RANSAC / edge-matching / DEM ray-cast on one shared
   flight-log corpus was not found in this literature pass.

2. **The error-budget question the task asked for a textbook citation on — how attitude error and
   AGL error propagate into ground-point geolocation error — was only found addressed
   empirically** (Barber et al.'s four mitigation techniques and 3 m flight-test result [13]), not
   in a closed-form textbook derivation reachable in this pass. A dedicated photogrammetry
   textbook (e.g. Wolf/DeWitt/Wilkinson, *Elements of Photogrammetry with Applications in GIS*)
   was surfaced as topically relevant by a secondary search result but was not independently
   verified against the primary text, so it is deliberately not numbered as a claim-bearing
   reference above — a follow-up pass with direct textbook access is needed to close this gap
   properly.

3. **Whether TRN/TERCOM/SITAN-class methods are practically relevant to *this* project's terrain**
   is unresolved by the literature alone: [25]'s 75 m figure and [23]'s qualitative "considerably
   more accurate than pure INS" claim both come from rugged/varied terrain; the taxonomy entry in
   §2 already flags that flat terrain (plains, open water) defeats the method, and nothing found
   here quantifies the accuracy degradation curve as a function of terrain roughness.

4. **The two unresolved bibliography entries** (a 2025 MDPI classical-vs-deep UAV geolocation
   survey and a 2019 *Multimedia Tools and Applications* UAV/Google-map registration paper, both
   noted in §3's preamble) likely contain further classical references and possibly a direct
   accuracy comparison relevant to open question 1; they were excluded for lack of confirmed
   authorship, not lack of relevance, and are worth a second retrieval attempt with authenticated
   publisher access.

5. **Season/illumination robustness of the purely classical (non-CNN) methods is thin.** Only the
   edge/road-network family ([15], [19], [26], [2]) and DEM-only family (no visual signal at all)
   have an intrinsic argument for season invariance; NCC, phase correlation, MI, and
   SIFT/SURF/ORB feature matching all rely on photometric or local-gradient signal that changes
   with foliage/snow cover, which is exactly the gap the CNN-hybrid papers ([7][9][10][11]) were
   built to close. Whether a purely classical pipeline (e.g. LSD/chamfer road matching as the
   season-robust fallback when feature/NCC/MI confidence is low) is an adequate substitute for a
   CNN descriptor in a CPU-only onboard budget is not answered by any single paper found here.

6. **DEM staleness and vertical datum consistency** (e.g. WGS84 ellipsoidal vs. EGM96/EGM2008
   geoid heights) across SRTM [41], Copernicus DEM [42], AW3D30 [43], and FABDEM [44] were not
   cross-checked against each other in this pass; mixing DEM sources for different mission
   corridors without reconciling the vertical datum is a plausible silent error source that this
   survey did not verify one way or the other.
