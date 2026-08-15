# Tracking on real footage — 91 compositions on MOT17

**Run 2026-08-14, re-run 2026-08-15** on `feat/tracking-v3`. Machine-readable rows:
`cv-service/benchmarks/results.csv` (current, ORU velocity guard active) and
`benchmarks/results-pre-oru-guard.csv` (the first run, kept so the guard's effect stays checkable).
Tool: `cv-service/benchmarks/`. Dataset survey and shortlist reasoning: [TRACKING-BENCHMARKS.md](TRACKING-BENCHMARKS.md).

This is the first measurement of this tracker against data we did not author.

---

## 0. The one-paragraph summary

On real footage, **`bytetrack` beats `cost` on every identity metric, and `cost` beats it on every
throughput metric.** `cost` runs 1.9–5.3× faster; `bytetrack` commits 2–4.5× fewer identity switches
and recovers 8–16 points more of the gaps it opens. Two of this branch's own results did **not**
survive contact with real data: **ORU made identity worse in 15 of 21 scenes**, and the O3
plausibility metric — silent on all thirty synthetic rows — **fired on 37 of 42 `cost` runs**, where
peak reported velocity reached **12.9 frame-widths/second**. `bytetrack` produced none, peaking at
**0.67**. The capability ladder itself verified perfectly end to end.

**Update, after fixing the guard (§4b):** refusing implausible reconstructions removed **64 % of the
non-physical velocities** and recovered **93 IDSW** — but ORU is **still net-negative on real crowded
data (+377 IDSW)**. The velocity bound treated a symptom. The root cause — ORU never checks that its
two bracketing observations are the same object — is untouched, and shows up as a clean split: in
scenes at or above 10 detections/frame, ORU improved **0 of 7**; in sparse, ego-motion-dominated
scenes it produces its only real wins.

---

## 1. What was measured

| axis | values | rows |
|---|---|---|
| scene | MOT17 `02 04 05 09 10 11 13` | 7 |
| detector | `DPM` `FRCNN` `SDP` — three real detectors, same pixels | 3 |
| engine | `cost` (L1, pure stdlib) · `bytetrack` (L3, numpy) | 2 |
| ORU | off · on | 2 |
| ladder check | `bytetrack` requested at L1 | +7 |

**91 compositions.** Scenes span static and moving cameras, 640×480 to 1920×1080, 14/25/30 fps,
4.2 to 24.5 detections per frame, daylight to night-with-motion-blur.

### Why these numbers are not circular

`tools/trackeval`'s `SyntheticDetector` takes ground truth as its input and returns it perturbed — a
closed loop. **This runner never lets ground truth reach the tracker.** Detections come from
`det.txt`: real DPM/FRCNN/SDP output on real pixels, with real misses, real false positives and real
localization error. Ground truth reaches the scorer only.

### Two corrections applied before scoring

- **Ignore regions.** 42 % of MOT17 GT rows carry ignore flag 0. Such a row opens no scored window
  (failing to track it is not a miss) and any detection overlapping it is dropped before the session
  sees it (it is not a false positive). Without this every number below would be wrong, flatteringly.
- **Per-detector thresholds.** The detectors' scores are not comparable — DPM is a raw SVM margin on
  `[-0.5, 3.39]`, FRCNN and SDP are calibrated to `[0,1]`. Thresholds are `0.0` / `0.5` / `0.5`
  respectively, chosen from what each score *is*, and every row reports retained detections/frame.

---

## 2. Throughput — what the ladder actually buys

FRCNN detections, ORU off. `ms` is wall time around `session.process()` only; loading, parsing,
threshold filtering and metric computation are all outside the bracket.

| scene | dets/frame | `cost` ms | `bytetrack` ms | `cost` fps | `bytetrack` fps | `cost` is |
|---|---|---|---|---|---|---|
| MOT17-05 | 4.2 | 0.138 | 0.730 | 7231 | 1371 | **5.3× faster** |
| MOT17-09 | 5.6 | 0.278 | 0.698 | 3600 | 1434 | 2.5× faster |
| MOT17-11 | 6.3 | 0.260 | 0.766 | 3843 | 1306 | 2.9× faster |
| MOT17-13 | 9.8 | 0.415 | 1.176 | 2407 | 850 | 2.8× faster |
| MOT17-02 | 10.6 | 0.352 | 1.120 | 2842 | 893 | 3.2× faster |
| MOT17-10 | 12.5 | 0.485 | 1.333 | 2063 | 750 | 2.8× faster |
| MOT17-04 | 24.5 | 1.160 | 2.161 | 862 | 463 | 1.9× faster |

**The synthetic crossover did not appear.** `CV-RATE-BUDGET`/§5.1 measured `cost` losing to
`bytetrack` above N≈25–30 targets. At 24.5 detections/frame — the top of MOT17's range — `cost` is
still 1.9× ahead. The advantage *narrows* monotonically with density exactly as predicted (5.3× →
1.9×), so the shape of the model is right and the crossing point sits further out on real data than
the synthetic scenarios implied.

Both engines are far faster than any detector: at 30 fps a frame arrives every 33 ms, and the most
expensive association measured here costs **2.2 ms**. On this axis the engine choice is free.

---

## 3. Identity — where that speed is paid for

Totals across all seven scenes, ORU off. `MT`/`ML` are mostly-tracked / mostly-lost object counts.

| detector | engine | dets/frame | IDSW | MT | ML | recovery | implausible velocity |
|---|---|---|---|---|---|---|---|
| DPM | `cost` | 7.9 | 2645 | 53 | 274 | 56.9 % | **204** |
| DPM | `bytetrack` | 7.9 | **591** | 35 | 314 | **73.0 %** | 0 |
| FRCNN | `cost` | 10.5 | 1085 | **145** | **141** | 46.7 % | **20** |
| FRCNN | `bytetrack` | 10.5 | **671** | 131 | 151 | **54.8 %** | 0 |
| SDP | `cost` | 13.6 | 2345 | **252** | **87** | 66.4 % | **96** |
| SDP | `bytetrack` | 13.6 | **1087** | 227 | 99 | **78.0 %** | 0 |

The trade is consistent across all three detectors: **`cost` covers more and churns more.** It holds
slightly more objects mostly-tracked and loses slightly fewer outright, but commits 2.2–4.5× the
identity switches and recovers far fewer of its own gaps. `bytetrack` is the more conservative
tracker — it gives up on more objects and keeps cleaner identities on the ones it keeps.

Which is better depends entirely on what the identity is *for*. For a "how many objects are in
frame" readout, `cost` is defensible. For "follow *that* one", 2–4× the id switches is the whole
problem, and this is the mode a drone operator actually uses.

**Detector quality behaves as expected and is worth having as an axis**: SDP retains 13.6 dets/frame
and drives ML down to 87–99; DPM retains 7.9 and pushes ML to 274–314. More detections buy coverage
and cost identity switches — SDP has both the best MT and more IDSW than FRCNN.

---

## 4. ORU on real data — the result that did not survive

Wave V3's ORU improved the synthetic `nonlinear` scenario's coast drift by 77 %. On MOT17, per-scene
pairs at identical settings, `cost` engine:

| | scenes |
|---|---|
| IDSW **worse** with ORU on | **15** |
| IDSW better | 6 |
| net change across 21 scenes | **+470 IDSW** |

The worst single case is `MOT17-04-DPM`: **1179 → 1456 IDSW (+277)**, recovery 53.3 % → 48.6 %. The
gainers are real but smaller — `MOT17-02-SDP` −98, `MOT17-13-SDP` −26.

The pattern that explains it: **ORU raises implausible-velocity counts in 19 of 21 scenes**, often by
an order of magnitude (`MOT17-04-DPM` 157 → 441; `MOT17-09-DPM` 10 → 46; `MOT17-02-DPM` 0 → 22). ORU
reconstructs a gap from the two real observations bracketing it. In synthetic scenarios those two
observations are always the same object. In a crowd with a weak detector they are frequently *not*,
and the reconstruction then produces a velocity across the gap that is physically impossible — which
then propagates forward as a prediction and costs an identity.

**ORU is not wrong; its guard is missing.** It has no test that the two observations it interpolates
between are plausibly the same object. On clean data that assumption holds and it wins; on crowded
real data it does not, and it loses more than it wins.

### ORU does nothing at all under `bytetrack`

Every one of the 21 `bytetrack` ORU pairs is **byte-identical** — IDSW, recovery, MT/PT/ML,
implausible velocity, all unchanged. This is by design and documented in `session.py`: `bytetrack`
runs inside a third-party engine with its own Kalman filters and mints its own keys, so `TrackBook`'s
`Track` — where history, ORU, ego-motion compensation and velocity live — is not in its path.

**Everything TRACKING-V3 built applies to `cost` alone.** That is a scope fact worth stating plainly:
the default ASSOCIATE engine at L3+ is untouched by waves V2, V3 and V6.

---

## 4b. The guard, measured

`reupdate()` now returns `None` when its own reconstruction implies a velocity beyond
`CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND` (default **5.0** frame-widths/sec; `<= 0` disables,
reproducing the pre-guard behaviour exactly). It refuses rather than clamps: an impossible velocity is
evidence the bracket is wrong, and a wrong bracket has no salvageable answer.

All 91 compositions re-run. **Control first: all 49 ORU-off rows are byte-identical**, and all thirty
synthetic baseline rows are unchanged — the guard is inert everywhere ORU is not running.

| ORU-on, `cost`, 21 pairs | before guard | after guard |
|---|---|---|
| implausible velocities | 951 | **346** (−64 %) |
| IDSW | 6545 | **6452** (−93) |
| scenes improved by the guard | — | 11 better, 6 worse, 4 unchanged |

**But ORU still loses to not running ORU at all**, even guarded:

| `cost`, all 21 pairs | ORU off | ORU on + guard |
|---|---|---|
| IDSW | **6075** | 6452 (**+377**) |
| recovery | **56.7 %** | 56.6 % |
| MT / ML | 450 / 502 | 450 / 502 |

MT and ML are *identical* — ORU changes neither coverage nor outright loss. It only churns identity.

### Where ORU pays, and where it never does

| scene density | pairs | net IDSW | scenes improved |
|---|---|---|---|
| **≥ 10 dets/frame** | 7 | **+320** | **0 of 7** |
| < 10 dets/frame | 14 | +57 | 5 of 14 |

The dense half is unanimous — ORU has never once helped a crowded scene, and `MOT17-04-DPM` alone
(21.3 dets/frame, weakest detector) accounts for **+233** of the total.

The two largest wins are both `MOT17-13` (**−51** and **−38**), the camera mounted on a moving bus:
sparse targets, strong coherent ego-motion. That is the regime ORU was designed for, and it is also
the regime a drone flies in — few targets, constantly moving camera. The guard is worth keeping on
its own merits (605 fewer non-physical velocities), but **ORU's remaining cost is a bracket-identity
problem, and a velocity bound cannot see it.**

---

## 4c. Density gate — swept, and it does not save ORU

§4b's split (0 of 7 crowded scenes improved) suggested gating ORU on scene density.
`CV_TRACK_REUPDATE_MAX_TRACK_COUNT` refuses a reconstruction when more than N tracks are live.

**The signal is a proxy.** `reupdate()` is a pure function of a `Track` and has no `TrackBook`
reference, so detections-per-frame — what §4b actually measured — is not reachable without threading
an argument through five `TrackBook.apply()` call sites, some of which (tracker-only FOLLOW frames)
have no detector pass to count. Live track count is used instead. Measured range: median 5
(`MOT17-09`) to 34 (`MOT17-04-SDP`).

Swept across all 21 scene/detector pairs, `cost`, ORU on:

| configuration | IDSW | vs ORU off | recovery | scenes beating ORU-off |
|---|---|---|---|---|
| **ORU off** | **6075** | — | 56.7 % | — |
| ORU on, no gate | 6452 | +377 | 56.6 % | — |
| gate ≤ 6 | 6064 | **−11** | 56.7 % | 3 / 21 |
| gate ≤ 8 | 6143 | +68 | 56.6 % | 3 / 21 |
| gate ≤ 10 | 6219 | +144 | 56.9 % | 4 / 21 |
| gate ≤ 12 | 6259 | +184 | 56.3 % | 3 / 21 |
| gate ≤ 15 | 6279 | +204 | 56.9 % | 4 / 21 |
| gate ≤ 20 | 6213 | +138 | 56.8 % | 6 / 21 |
| gate ≤ 30 | 6368 | +293 | 56.9 % | 5 / 21 |

**No threshold makes ORU pay.** Every setting lands between "ORU off" and "ORU ungated", and the only
one reaching parity does so by **switching ORU off almost everywhere**: at ≤ 6, sixteen of
twenty-one scenes are byte-identical to ORU-off because the gate never lets a reconstruction through.
The −11 is 0.2 % — noise.

**And the gate discards the wins with the losses.** ORU's two largest ungated gains were
`MOT17-13-FRCNN` (−51) and `MOT17-13-SDP` (−38) — the moving-bus scene, median 9 tracks. At ≤ 6 those
become **+6 and 0**. The gate cannot separate them from `MOT17-04-DPM`'s +233, because both sit in
the middle of the track-count range. Recovery is flat at 56.3–56.9 % across every configuration: ORU
buys no recovery at any density either.

**Conclusion: density is a poor proxy for bracket ambiguity, which is what actually breaks ORU.** Two
observations can bracket different objects in an uncrowded frame and the same object in a busy one.
The gate ships **disabled by default** — the sweep found no value worth enabling — and stays as a
deployment knob. The real fix remains a same-object test on the bracket itself
([TRACKING-RECOVERY-RESEARCH.md](TRACKING-RECOVERY-RESEARCH.md) §2.1), which is the one thing none of
velocity-bounding, density-gating or threshold-tuning can substitute for.

Raw sweep rows: `cv-service/benchmarks/results-density-sweep.csv`.

---

## 4d. Bracket identity — the first thing that makes ORU pay, and why it still ships off

The third attempt, and the first aimed at the cause: test whether the two bracketing observations are
the same object at all. Two independent checks, each separately switchable so the sweep can attribute
the result.

- **A — shape consistency.** Refuse when `|ln(w₂/w₁)|` or `|ln(h₂/h₁)|` exceeds a bound. Log-ratio, so
  growth and shrinkage are symmetric and one bound serves every target size.
  `CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO`.
- **B — motion plausibility.** Forward-predict the earlier observation to the later one's timestamp
  using the track's **pre-gap** velocity, and refuse when the forecast lands more than N box-diagonals
  away. A size-scaled centre distance rather than IoU: **IoU collapses to a hard 0 the moment two
  boxes stop overlapping, which is the ordinary case after a multi-second gap on a fast target**, so
  an IoU gate would refuse nearly every honest long-gap bracket.
  `CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE`.

### Swept independently, 21 real scene/detector pairs each

| configuration | IDSW | vs ORU off | recovery | implausible |
|---|---|---|---|---|
| ORU off | **6075** | — | 56.7 % | 320 |
| ORU on, velocity guard only | 6452 | +377 | 56.6 % | 372 |
| **A — shape ≤ 0.40** | **5978** | **−97** | **57.4 %** | 343 |
| A — shape ≤ 0.50 | 6089 | +14 | 57.2 % | 359 |
| A — shape ≤ 0.70 | 6429 | +354 | 56.5 % | 355 |
| B — motion ≤ 1.0 diag | 6509 | +434 | 56.2 % | 381 |
| B — motion ≤ 2.0 diag | 6625 | +550 | 55.5 % | 366 |
| B — motion ≤ 3.0 diag | 6526 | +451 | 56.0 % | 409 |

**Check A works — it is the first ORU configuration in three attempts that beats not running ORU at
all**, and it is the only change so far that raised *recovery* rather than leaving it flat.

**Check B is harmful at every setting tested** — worse than no check. The tension its own design note
predicted is the likely cause: the pre-gap velocity it forecasts from is exactly the estimate ORU
exists because it distrusts, so it rejects honest brackets more often than dishonest ones. Kept as a
knob, defaulted off, because that verdict is regime-bound — a sparse scene with coherent ego-motion
is precisely where a pre-gap velocity *is* trustworthy.

### Trust the band, not the digit

The IDSW surface is jagged (0.40 → −97, 0.45 → +175, 0.50 → +14) and **one pathological pair,
`MOT17-04-DPM`, swings between +64 and +284 by itself** and dominates every total. Excluding it, the
picture is clean and the break is sharp:

| bound | 0.3 | 0.40 | 0.45 | 0.50 | 0.55 | 0.60 | 0.70 |
|---|---|---|---|---|---|---|---|
| IDSW vs ORU-off, excl. `MOT17-04-DPM` | −126 | **−277** | −109 | −50 | +179 | +252 | +198 |

**≤ 0.5 is consistently better than no ORU; ≥ 0.55 is consistently worse.** That band is the robust
finding; the exact digit is not.

### Shipped ON at 0.40 — by decision, with the cost recorded

Enabling `0.40` moves exactly one synthetic row: **`pan`/FOLLOW's `implaus_n` 0 → 8**. Nothing else in
that row moves — coast ADE/FDE, MT/PT/ML, IDSW, lifetime all identical. So it leaves *position*
untouched and the *velocity* non-physical on eight coasted frames — precisely the defect shape O3
exists to catch and every other column is blind to.

The trade, stated plainly:

| | 21 real ASSOCIATE pairs | synthetic `pan`/FOLLOW |
|---|---|---|
| **shape 0.40 (shipped)** | **−97 IDSW, +0.7 pp recovery** | 8 non-physical velocities |
| off | +377 IDSW | clean |

**The decision was to enable it**: the ASSOCIATE evidence is 21 real scene/detector pairs, the cost is
one synthetic row where position is unaffected, and `CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO=0` reverts
in one env var. `tools/trackeval/BASELINE.md` records the moved row as a deliberate behaviour change
with its reason, and plan finding **O5** tracks resolving it.

**What remains genuinely unknown**: FOLLOW is the mode an operator holds a target with, velocity is
what geolocation consumes, and **MOT17 ships no pixels — so FOLLOW cannot be measured on real footage
at all**. That single synthetic row is the entire evidence base on that side.

### Verification of the shipped configuration

Full matrix re-run with the new default. Three controls that must not move, and did not:

| control | why it must not move | rows moved |
|---|---|---|
| `cost`, ORU off | the check lives inside ORU | **0 / 21** |
| `bytetrack`, ORU on | bytetrack bypasses the book entirely | **0 / 21** |
| `bytetrack`, ORU off | — | **0 / 21** |

| `cost`, 21 pairs | IDSW | vs ORU off | recovery | implausible |
|---|---|---|---|---|
| ORU off | 6075 | — | 56.7 % | 320 |
| previous default (velocity guard) | 6452 | +377 | 56.6 % | 372 |
| **shipped now (+ shape 0.40)** | **5978** | **−97** | **57.4 %** | 343 |

**16 of 21 scenes improved against the previous default; net −474 IDSW.**


---

## 5. O3 fired on first contact

The velocity plausibility metric committed hours earlier scored **0 on all thirty synthetic rows**.
On real footage:

| engine | runs with implausible velocity | peak reported velocity |
|---|---|---|
| `cost` | **37 of 42** | **12.9 frame-widths/sec** |
| `bytetrack` | 0 of 42 | 0.67 frame-widths/sec |

**This is not an artifact of one engine not reporting velocity.** Directly measured on
`MOT17-09-FRCNN`: `cost` emitted 2927 non-zero velocities of 2937, `bytetrack` 2880 of 2909. Both
report. Only one reports possible numbers — 12.9 frame-widths/second is a target crossing the entire
frame thirteen times a second.

Anything downstream that consumes `velocity_x/y` — prediction, geolocation, the visual-geo work — is
being fed values that are sometimes garbage, on the engine we intend to run on the airframe.

O3 was built as insurance against a future regression. It found a present defect in its first hour
against data we did not write. That is the argument for the whole instrument-first ordering.

---

## 6. The capability ladder verified end to end

Requesting `bytetrack` while capped at L1, on all seven scenes: the session served **`cost`**, and
every identity metric was **identical** to requesting `cost` directly. The ceiling holds, the
degradation is silent-but-correct, and the level served is reported.

One gap: `capability_level_reason` is empty in these runs because the *level* was not capped — the
*engine* was substituted within the served level. The reason string explains level capping only, so
an operator sees the substitution through `engineId` alone. Band 1's UI renders that, but not as a
downgrade. **Worth closing**: an engine substitution is exactly as surprising as a level cap.

---

## 7. What cannot be concluded from this

Stated so no one reads more into the tables than is there.

- **No pixels.** MOT17 ships annotations only. FOLLOW (`lk`/`ncc`), optical-flow ego-motion
  compensation, appearance/re-ID evidence, ROI re-detection and detector throughput are **entirely
  unmeasured**. FOLLOW is the mode an operator uses to hold a target, and it has never been tested on
  real footage.
- **Not aerial.** Every scene is ground-level, human-scale, pedestrian-class. Our deployment is a
  drone looking down at small, fast, low-contrast targets. Every licence-clean aerial benchmark found
  was a multi-GB download this machine has no room for.
- **One machine, one run.** Throughput figures are this laptop's, single-threaded, unrepeated.
- **Pedestrians only.** MOT17 scores class 1. Our targets are vehicles and aircraft.

---

## 8. What I would change on the strength of this

1. **~~Give ORU a plausibility guard~~ — done (§4b), and it was necessary but not sufficient.** Keep
   it: 605 fewer non-physical velocities is worth having regardless. But it did not make ORU pay.
2. **~~Gate ORU on scene density~~ — built and swept (§4c); no threshold pays.** Density is a poor
   proxy for bracket ambiguity. The knob ships disabled. **ORU remains on by default (15 s gap) and
   is worth +377 IDSW on this data** — that default deserves a decision, but not from out-of-regime
   evidence: ORU's largest wins came from the one ego-motion-dominated scene here, which is the
   closest analogue to a drone we have.
3. **~~The real ORU fix is a bracket-identity check~~ — built and swept (§4d). Shape consistency
   works** (−97 IDSW, +0.7 pp recovery, the first net win in three attempts); motion-forecast
   plausibility does not (+434 at best). It ships off because it conflicts with the one FOLLOW
   measurement we have. **Resolving that needs aerial footage with pixels.**
4. **Do not ship `cost` as the identity engine for FOLLOW-style work without fixing its velocity.**
   Even guarded, `cost` reports 346 implausible velocities where `bytetrack` reports none.
5. **Decide deliberately whether V2/V3/V6 should reach `bytetrack`.** The L1 engine carries all the
   sophistication and the L3 default carries none, which is the opposite of the intended ladder.
6. **Get aerial footage.** Everything here is a proxy, and §4b is the clearest example of why: the
   recommendation flips depending on target density, and we have measured every density except ours.

## 9. Reproduce

```bash
cd cv-service
bash benchmarks/fetch_mot17.sh                    # 16 MB, annotations only
PYTHONPATH="$PWD" .venv/bin/python -m benchmarks.runner \
    --sequence 09 --detector FRCNN --engine cost --level 1 --oru off \
    --output benchmarks/results.csv
```

`--sequence 02|04|05|09|10|11|13` · `--detector DPM|FRCNN|SDP` · `--engine cost|bytetrack` ·
`--level 1..5` · `--oru on|off` · `--conf-threshold` overrides the per-detector default.

Data is CC BY-NC-SA (non-commercial), gitignored, never committed.
