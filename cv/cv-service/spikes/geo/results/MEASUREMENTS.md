# H0 — eval harness, matcher bake-off, rectify-first re-rank, false-convergence gate

Measured 2026-08-19, on the laptop (`cv/cv-service/.venv`, CPU-only, Python 3.12, kornia 0.8.3,
torch/CosPlace/EigenPlaces via `torch.hub`). GB4005 (`vlad@192.168.0.106`) is reachable over SSH
but has no `torch`/`kornia`/cv-service checkout and only 2 CPU cores — provisioning a full geo
bake-off environment there was judged a new deployment task, disproportionate to this wave's
"optional, try SSH, else not run" framing. Every GB4005 cell in the tables below is **not run** for
that one reason.

Raw per-config JSON (including every per-frame row): `spikes/geo/results/bakeoff.json` and
`spikes/geo/results/false_convergence_gate.json`. This file is the human-readable digest — the
same numbers now also live in `docs/plans/active/VISUAL-GEO-V2-PLAN.md` §9.

## Regions rebuilt (deliverable 2)

Built from LIVE Esri z17 tiles via `spikes/geo/build_regions.py` (bboxes recovered from the parked
`feat/visual-geo` branch's own `demo/build_region.py` / `demo/build_region_pozniaky.py`):

| Region | Tiles fetched | Descriptors indexed | `accept_similarity` | `accept_margin` | holdout recall@1 | holdout median err (m) | never-accept? |
|---|---|---|---|---|---|---|---|
| `kyiv-maidan` | 49/49 | 45 | 0.870 | 0.000 | 0.25 | 194.5 | no |
| `kyiv-pozniaky` | 252/252 | 227 | 0.490 | 0.060 | 0.04 | 194.7 | no |

Both self-calibrate to real (non-never-accept) thresholds but with LOW holdout recall@1 (4–25%) —
honest evidence that raw-embedding retrieval alone is weak on these regions, consistent with why
this wave's geometric re-rank exists at all.

## Datasets

- **`pexels`** — the 12 real Maidan-Nezalezhnosti frames (`fixtures/maidan-video-frames/`, harvested
  verbatim from `feat/visual-geo`), single ground truth 50.4502431°N/30.5240622°E
  (`fixtures/maidan-video-frames/manifest.jsonl`, written this wave). **No telemetry** — the parked
  branch's own finding ("no telemetry existed on that footage at all"); `--rectified` is refused,
  not faked, on this dataset (see `run_bakeoff.REFUSED_RECTIFIED_NO_TELEMETRY`).
- **`sitl-nadir` / `sitl-oblique45`** — 13 frames each, rendered by `spikes/geo/sitl_render.py`
  from a closed-form ArduCopter CIRCLE-mode track (`analytical_circle_telemetry.py` — a real,
  documented ArduCopter default flight mode's own closed-form trace, **not** a live SITL capture,
  centered on `kyiv-maidan`) against the SAME real Esri z17 tiles, at `fov_degrees=84`
  (`condition_query`'s own assumed default — the first render used `sitl_render`'s CLI default of
  60°, a self-inflicted FOV mismatch between rendering and conditioning that was caught and fixed
  before any number below was recorded; see MODULE.md).

## 9.1-equivalent — matcher bake-off, cost (ms/pair)

| Matcher | Host | ms/pair rectified | ms/pair unrectified | Speedup |
|---|---|---|---|---|
| `xfeat` | laptop | 23.5–25.5 | 43.6–64.7 | ~1.8–2.5x |
| `lightglue_aliked` | laptop | not run — **broken in this env** (0 matches/0 inliers on every frame, see below) | 713.5 | — |
| `lightglue_disk` | laptop | 484.2–555.5 | 766.5–1431.7 | ~1.6–2.6x |
| `eloftr` (official ZJU) | laptop | **not run** — see below | **not run** | — |
| `loftr` (kornia baseline) | laptop | 504.4–575.9 | 964.1–1577.9 | ~1.7–2.7x |
| all five | GB4005 | **not run** — reachable, no torch/kornia/cv-service present, 2 cores; out of scope this wave | | |

`loftr`'s laptop numbers closely reproduce §4.7's own literature anchors (≈530ms conditioned /
≈1457ms unconditioned) — a real corroboration, not a coincidence.

**`lightglue_aliked` — broken, not a "slow" result, a wrong one.** Reproducibly 0 matches / 0
inliers on every one of 12 Pexels frames (confirmed again in the full pipeline, not just isolated
debugging): ALIKED's extractor itself works (1900+ real keypoints, correct descriptors, weight
loading 254/253 keys matched), both `aliked-n16` and `aliked-n16rot` variants tried, adaptive
depth/width pruning disabled, `filter_threshold` lowered to 0.01 — LightGlueMatcher's own
`matching_scores0` stays ~0.0013 max even on a trivial self-match sanity check. Isolated
specifically to the ALIKED+LightGlueMatcher pairing: the identical wrapper code path with DISK
features works correctly (1469 real matches, mean_conf 0.963 on the same sanity check). This reads
as a genuine kornia 0.8.3 integration defect, not a config error — **`lightglue_disk` substituted
for `lightglue_aliked` in the priority triple.**

**`eloftr` — not run, two independent real blockers.** (1) `git clone` of the official
`zju3dv/EfficientLoFTR` succeeds; its own code imports `kornia.utils.grid.create_meshgrid`, a
dotted path that no longer exists in kornia 0.8.3 (`create_meshgrid` moved to `kornia.utils`
directly) — fixed with a 10-line compat shim (`matchers.py#build_eloftr`, registers an alias
module before import, upstream code runs unmodified). The import chain then needs `joblib`+`yacs`
(installed, trivial) then `pytorch_lightning` (not installed — stopped there deliberately: this
laptop was at 4.2GB free / 98% full disk for the whole task, and installing a heavy ML framework
just to reach the NEXT blocker was not worth the risk). (2) Even with every import fixed,
`build_eloftr()` would still raise on the checkpoint: upstream distributes weights via a **Google
Drive link in its README**, not a scripted download — genuinely not obtainable without a human
fetching it by hand. Blocker (2) alone makes this "not run" regardless of (1).

## 9.2-equivalent — accuracy on the Pexels clip (12 real frames, out-of-sample)

| Matcher | Rectified | k | top-1 ≤100m (n=12) | median error (m) | false-fix rate | frames passing §4.2 gate |
|---|---|---|---|---|---|---|
| `xfeat` | unrectified | 5 | 0/12 | 628.8 | N/A (0 accepted) | 0/12 |
| `xfeat` | unrectified | 10 | 0/12 | 477.0 | N/A | 0/12 |
| `xfeat` | unrectified | 20 | 0/12 | 585.2 | N/A | 0/12 |
| `lightglue_disk` | unrectified | 5 | 0/12 | 774.6 | N/A | 0/12 |
| `lightglue_disk` | unrectified | 10 | 0/12 | 743.5 | N/A | 0/12 |
| `lightglue_disk` | unrectified | 20 | 0/12 | 608.3 | N/A | 0/12 |
| `loftr` | unrectified | 5 | 0/12 | 649.9 | N/A | 0/12 |
| `loftr` | unrectified | 10 | 0/12 | 657.1 | N/A | 0/12 |
| `lightglue_aliked` | unrectified | 5 | 0/12 (broken — see above) | 774.6 | N/A | 0/12 |
| all matchers | **rectified** | — | **refused** — Pexels carries no heading/altitude telemetry; conditioning needs both (§4.2 "both together, never one"); inventing a prior would misreport a measurement as real | | |

**Headline: every matcher, every k, on the real out-of-sample Pexels clip: 0/12 top-1 within
100m, and — because the single-frame gate (G-a..G-f) never accepted a single frame — zero false
fixes too, but also zero usable fixes.** This closely matches the parked branch's own Wave 10a
finding (correct/near-correct tile never reached top-1 either way, 0/12, even after its blind-angle
mitigation) — H0 reconfirms the same real-world domain-gap ceiling independently, with a different
(geometric, not appearance-ranking) mitigation mechanism.

## 9.3-equivalent — SITL analytic track (13 frames, real closed-form telemetry)

| Matcher | Mode | Rectified | k | top-1 ≤100m | median error (m) | frames accepted (all gates) | false fixes among accepted |
|---|---|---|---|---|---|---|---|
| `xfeat` | nadir | rectified | 10 | 0/13 | 584.3 | 0/13 | — |
| `xfeat` | nadir | unrectified | 10 | 0/13 | 578.2 | 0/13 | — |
| `xfeat` | oblique 45° | rectified | 10 | 0/13 | 455.6 | 0/13 | — |
| `xfeat` | oblique 45° | unrectified | 10 | 0/13 | 335.5 | 0/13 | — |
| `lightglue_disk` | oblique 45° | **rectified** | 10 | 2/13 (15.4%) | **260.8** | **1/13** | **0** |
| `lightglue_disk` | oblique 45° | unrectified | 10 | 2/13 (15.4%) | 272.7 | 0/13 | — |
| `loftr` | oblique 45° | rectified | 10 | 0/13 | 458.8 | 0/13 | — |
| `loftr` | oblique 45° | unrectified | 10 | 0/13 | 531.1 | 0/13 | — |

Yaw error: **not measured** — `pose.py#fit_homography_pose` extracts `yaw_deg` per fit, but no
driver in this wave compared it against the synthesized track's own `heading_deg`; flagged as
incomplete, not silently assumed correct.

**xfeat's retrieval-quality ceiling, isolated separately (not a matcher artefact): even against
its OWN synthetic-nadir render of the indexed imagery, the true tile lands rank 7/45 by raw
cosine similarity** (`17/76650/44196`, sim 0.544 vs the wrong top-1's 0.575) — retrieval itself,
not re-ranking, is the bottleneck for this case; re-rank cannot recover a candidate retrieval never
puts in the top-k. Traced with `xfeat` on `sitl-nadir` frame 0: all 10 top-k candidates scored
200+ raw keypoint matches but only 8–14 MAGSAC inliers (a ~4–5% inlier ratio) — xfeat produces many
matches on satellite-tile urban texture, but most are geometrically spurious, so G-b (inlier ratio
≥0.35) refuses everything and the winner is barely distinguishable from the runner-up (G-d margin
gate also fails). This is real, measured evidence that xfeat, while by far the fastest matcher, has
materially weaker geometric discriminative power than LightGlue/LoFTR on this specific domain.

**`lightglue_disk` + rectification is the one config that worked as designed**: conditioning cut
its cost 2.6x (1431.7ms → 555.5ms/pair) **and** the one frame that cleared every §4.2 gate (G-a
through G-f) was a true, non-false fix (260.8m median error across matched frames, 0 false
fixes). This is the wave's one clean positive result — on synthetic oblique telemetry-conditioned
frames, not on the real Pexels clip.

## Latency vs §4.7's ≤800ms/keyframe budget

§4.7: `decode + rectify + encode + k × matcher_pair + PF update + pose ≤ 800ms`, and separately
notes production's own existing mitigation caps verification at the top **2** candidates
(`harvested/verify.py#DEFAULT_VERIFY_TOP_N`), not the full `k`.

| Matcher | ms/pair (rectified) | k=2 total (top-2 mitigation) | k=10 total | Clears 800ms at k=2? | Clears 800ms at k=10? |
|---|---|---|---|---|---|
| `xfeat` | ~25 | ~50ms | ~250ms | yes | yes |
| `lightglue_disk` | ~520 | ~1040ms | ~5200ms | **no** | no |
| `loftr` | ~540 | ~1080ms | ~5400ms | **no** | no |

**Only `xfeat` clears the latency budget at any tested `k`.** Neither heavy matcher clears it even
at production's own already-mitigated top-2 subset, conditioned. The k×fps×resolution frontier
this wave can report: `xfeat` has headroom to spare (k=20 unrectified was still 40.6ms/pair, i.e.
~800ms total for k=20 with ZERO margin for decode/rectify/encode/PF/pose — realistically k≤10 at
1Hz, k≤5 with real headroom); `lightglue_disk`/`loftr` need k≤1 at 1Hz to have any chance of
fitting, and even then leave no headroom for the rest of the pipeline — effectively **incompatible
with §4.7's budget as specified**, on this CPU-only laptop class, regardless of conditioning.

## Gate question, answered directly

> Does a matcher clear §4.7's ≤800ms/keyframe budget with top-1 materially above 0/12 on the
> Pexels clip at zero false fixes?

**No.** `xfeat` clears the latency budget comfortably at every tested `k`, but its top-1 accuracy
on the real Pexels clip is 0/12 at every `k` (5, 10, 20) — not "materially above zero", flatly
zero, and its own geometric discriminative power (measured separately on SITL) is weak (4–5%
inlier ratios, gate refusals on essentially every candidate). `lightglue_disk` and `loftr` do NOT
clear the latency budget at any k that would leave headroom for the rest of the per-keyframe
pipeline, on this laptop class — and even where they were allowed to run to completion (k=10,
unbudgeted), their Pexels top-1 is also 0/12. The one genuinely positive number this wave
produced — `lightglue_disk` rectified reaching 15.4% top-1 / 0 false fixes on the SYNTHETIC SITL
oblique track — does not clear the latency budget either, and does not transfer to the real
out-of-sample clip.

**No candidate matcher, at any measured configuration, simultaneously clears the latency budget
and delivers materially-above-zero real-world accuracy at zero false fixes.** H0's honest
recommendation to H4: ship with `xfeat` as the only matcher that fits the latency envelope, but do
NOT expect it to deliver working single-frame `CONFIRMED` fixes on real out-of-sample footage —
lean structurally on the §4.4 sequence filter + false-convergence gate (measured working, next
section) and the honest `NO_FIX`/`PROBABLE` ceiling rather than a promise that re-ranking alone
solves the retrieval domain gap. This is a genuine, load-bearing finding for whoever runs H4, not
a soft caveat.

## 9.5-equivalent — sequence filter and the false-convergence gate (deliverable 5)

`spikes/geo/false_convergence_gate.py`, real unmodified `harvested/sequence.py#SequenceLocalizer`,
driven twice over the same 12 real Pexels frames with two different per-update measurement fields
— only the field changes, filter mechanics identical:

| Pass | Field | Updates | Converged | Converged on the known 771m-wrong cell (`17/76646/44193`) | Min distance from truth at convergence |
|---|---|---|---|---|---|
| **Control** (today's production behavior) | raw embedding cosine similarity, full region | 12 | **8/12** | **8/8 (100%)** | 776.9 m |
| **Geometric** (§4.4 Change 1, this wave's prototype) | re-ranked MAGSAC inlier ratio (xfeat), top-10 candidates only, `0.0` elsewhere | 12 | **0/12** | — | — (never converged) |

**This is the deliverable-5 headline, measured, not simulated:** fed the raw-similarity field the
production filter uses today, the SAME `SequenceLocalizer` code reproduces the branch's own
§12.14 finding almost exactly — confident convergence, every single time it converges, on the
771m-wrong cell (776.9m here; the branch's own number was 771–773m, the small difference is tile-
center-vs-fitted-position rounding). Fed the re-ranked geometric field instead (§4.2's MAGSAC
inlier ratio in place of raw cosine similarity — §4.4 Change 1, exactly as specced), the filter
**never converges once** across the same 12 frames — it correctly stays `SEARCHING` rather than
confidently committing to evidence that was never geometrically consistent. Raw JSON (all 24
per-frame rows, both passes): `spikes/geo/results/false_convergence_gate.json`.

Not measured this wave (out of H0's scope, correctly deferred): Change 2's G-b diversity/baseline
gate and the two standing regressions (`test_regression_alias_1213`,
`test_regression_pexels_false_convergence`) — those are explicitly H4 deliverables (§5 H0 table),
not H0's; H0's own job was proving Change 1's mechanism works, which the table above does.

## What ran vs what didn't, in one place

| Item | Status |
|---|---|
| Pozniaky + Maidan regions, live Esri z17 | **built** — both self-calibrate real (non-never-accept), low holdout recall (see above) |
| `xfeat` bake-off | **run** — laptop only, k∈{5,10,20}, pexels + both SITL modes |
| `lightglue_disk` bake-off | **run** — laptop only, k∈{5,10,20} pexels, k=10 SITL oblique |
| `lightglue_aliked` bake-off | **run, but the matcher is broken** in this kornia 0.8.3 environment — 0 matches every frame, confirmed in-pipeline; substituted `lightglue_disk` |
| `loftr` bake-off | **run** — laptop only, k∈{5,10} pexels, k=10 SITL oblique |
| `eloftr` (official ZJU) | **not run** — checkpoint is Google-Drive-hosted, not scriptable (the decisive blocker); a separate kornia API-move import bug was found and fixed along the way |
| GB4005 run | **not run** — SSH reachable, confirmed; no torch/kornia/cv-service present, 2 cores; provisioning judged out of this wave's scope |
| Rectify-first re-rank, Pexels clip | **not run (rectified)** — no telemetry exists on this footage; refused, not faked. Unrectified numbers stand as the real measurement |
| Rectify-first re-rank, SITL track | **run** — nadir + oblique45°, all three non-broken matchers, rectified vs unrectified |
| §4.4 false-convergence gate prototype | **run** — control (raw similarity) reproduces the 771m false convergence 8/8 times it converges; geometric field (this wave's fix) never converges, 0/12 |
| Harness self-tests | **14 passed** — `cd cv-service && .venv/bin/python -m pytest spikes -q`, zero network/model deps |
