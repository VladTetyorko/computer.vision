# tools/trackeval

The tracking-accuracy evaluation harness (`docs/plans/done/TRACKING-V2-PLAN.md`
§4, wave C0). It closes `docs/conclusions/TRACKING-REVIEW.md` finding E: every
number `cv/cv-service/MODULE.md`'s "Tracking engine" section reports is a **cost**
number (ms, CPU%, duty ratio) — nothing measured whether tracking actually lost
the subject. This package is the first **accuracy** scoreboard; waves C1–C5
were judged against it, and — because C1-C5 closed the original ten scenarios
to a saturated, zero-defect scoreboard — `docs/plans/active/TRACKING-V3-PLAN.md`
wave V0 extended it with five harder scenarios and a drift metric so waves
V1-V8 have something to be judged against too. See [`BASELINE.md`](BASELINE.md).

There is no camera in this repo — hardware tier H1 is unbought — so every
scenario's ground truth is generated, not recorded, and known exactly by
construction rather than estimated. `recording.py` (wave V0) is the forward
path for when that changes: a recorder + offline replay for real footage,
pixel-free so it can be committed and exercised long before a camera exists —
see "Real-footage recording" below.

## Run it

From `cv/cv-service/`, with the venv's `cv` extra installed (`numpy`/`cv2` render
the synthetic frames; `ultralytics`/`lap` are needed only to exercise the real
`bytetrack` engine — see "What happens with fewer dependencies installed"
below):

```bash
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario occlusion --mode ASSOCIATE
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all
```

### Measuring the acquisition/continuation confidence split

`--confidence-floor` and `--detect-threshold` (both default `0`, i.e. off, so a
bare run still reproduces [`BASELINE.md`](BASELINE.md) exactly) exist to A/B the
change in `docs/conclusions/CV-RATE-BUDGET.md` §4. Before them the harness gave
**every** synthetic detection a flat `confidence = 0.9` and never applied a
threshold, so the entire low-confidence regime — the one the split exists for —
was invisible and the feature was unmeasurable here.

```bash
# old behaviour: the operator's 0.4 threshold applied AT the detector
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval \
    --scenario small_target --mode ASSOCIATE --confidence-floor 0.10 --detect-threshold 0.40
# new behaviour: CV_DETECT_FLOOR
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval \
    --scenario small_target --mode ASSOCIATE --confidence-floor 0.10 --detect-threshold 0.15
```

`--confidence-floor` makes confidence fall linearly with apparent size (from the
scenario's own `confidence` at/above `reliable_size` down to this at zero),
mirroring `_miss_probability`'s existing curve on purpose: the same physical
fact — a small object is harder to see — shows up first as a weaker score and
only then as a miss. It consumes no randomness, so enabling it cannot re-roll
any scenario's existing draw sequence, and the threshold is applied *after* the
box is built for the same reason.

**What it does and does not model.** This covers the half of the change that
decides *which boxes reach the associator*. It does **not** model the response
filter (`servicers._reportable`), which is what the operator finally sees —
that half is covered by `tests/grpc/test_confidence_split.py` only.

### Measuring late-detection back-correction (`latency`)

`--lag-jitter-millis` (default `0`, i.e. the exact/idealised case, so a bare run
still reproduces [`BASELINE.md`](BASELINE.md) exactly) re-runs `latency` with a
realistic companion reading alongside it. Before a 2026-08-14 instrument repair,
`replay.py` injected the scenario's own lag by shifting *which* ground truth
`SyntheticDetector` returned but never told `session.process()` about it —
`detection_lag_millis` defaulted to `0` ("unknown"), so wave V6's correction
(`docs/plans/active/TRACKING-V3-PLAN.md` §4.5) had no way to fire and the
scenario was unwinnable by construction, not merely hard (`L` is unidentifiable
from position/arrival-time pairs alone under constant velocity and constant lag).

```bash
# exact/idealised: the harness's own injected lag, reported perfectly
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE
# realistic companion: +/-15ms spread, sourced from pull/clock.py's own M0 measurement
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE --lag-jitter-millis 15
```

Both readings score identically (`ML=1`, unchanged from before the repair) — not
because nothing improved, but because wiring the signal through exposed a SECOND,
previously-invisible defect, this time a genuine one in `cv_service/tracking/`
(`ObservationRing`'s arrival-time timestamps vs. `late_correction`'s implicit
capture-time assumption, causing the corrected box to diverge rather than
converge once a bracket exists). See [`BASELINE.md`](BASELINE.md)'s `latency`
writeup for the full diagnosis, and `tests/trackeval/test_replay.py::
test_persistent_per_frame_lag_correction_diverges_past_the_dead_zone` for the
regression that locks it down until a future wave fixes it.

`--scenario` is any key of `sequences.SCENARIOS` (see "Scenarios" below);
`--mode` is `ASSOCIATE` or `FOLLOW`. `--all` runs every scenario in both modes
and prints the full matrix — this is what [`BASELINE.md`](BASELINE.md) §1 is a
saved copy of. `--engine` overrides the engine id (`""`, the default, uses the
server's own default for the mode); `--seed` overrides `sequences.DEFAULT_SEED`.

## Package shape

```
tools/trackeval/
  sequences.py   synthetic ground-truth clips, known by construction
  replay.py      feeds a sequence through the REAL
                 cv_service.tracking.session.StreamTrackingSession, via a
                 seeded synthetic detector -- never a reimplementation
  metrics.py     greedy IoU matching -> IDSW / FM / MT-PT-ML / recovery
                 rate / coast ADE-FDE / track lifetime / cost
  recording.py   real-footage recorder + pixel-free offline replay
                 (TRACKING-V3-PLAN wave V0)
  __main__.py    the CLI
  BASELINE.md    the V0 scoreboard waves V1-V8 diff against
```

Mirrors `cv_service/tracking/`'s own convention: one file, one job, and every
module is **pure stdlib at module scope** — `numpy` is imported lazily inside
`sequences.py`'s `_render_frame`, and the real tracker engines `replay.py`
builds are constructed lazily by `cv_service.tracking.registry`'s own
factories. Nothing here needs `cv2`/`ultralytics`/`torch` just to be
*imported*.

## Scenarios

Ten scenarios from `docs/conclusions/TRACKING-REVIEW.md` §3 and waves C1-C5's own
tuning, plus five from `docs/plans/active/TRACKING-V3-PLAN.md` wave V0 (marked
below) built to isolate what waves V2-V6 exist to fix, since C1-C5 closed the
original ten to a perfect scoreboard (`BASELINE.md` §1). Deterministic given a
seed (same seed -> same frames, same boxes, forever):

| Scenario | What it does | Traces to |
|---|---|---|
| `linear` | 2-3 well-separated objects, constant velocity, always visible | the floor — nothing later should regress this |
| `occlusion` | one object passes fully behind an opaque bar for longer than `CV_TRACK_MAX_AGE` (so the track genuinely goes `LOST`, not just coasts) | REVIEW B3 — a LOST track is unrecoverable by construction |
| `crossing` | two DIFFERENT-coloured, similar-sized objects' paths intersect | REVIEW B1 — id-swap under pure geometry, closed by wave C3's appearance |
| `pan` | the CAMERA moves (ego-motion); objects also move | REVIEW A1/A2 — nothing compensates for the camera's own motion |
| `dropout` | the object is always visible; the *detector* is configured to intermittently miss it | REVIEW §4.6 — detection recall, isolated from identity |
| `pan_step` | camera static while visible, pans exactly as the target and a distractor go behind a bar | proves ego-motion compensation, not merely coincidence, recovers the right object |
| `clutter` | ten close-spaced, colour-sharing objects | decides whether `cost` may be the ASSOCIATE default at typical drone crowd sizes |
| `long_occlusion` | a gap longer than `TrackBook`'s own retention — the track is actually deleted | tests the dormant GALLERY (wave C4), not mere coasting |
| `crowd_recall` | three same-coloured PAIRS, all vanish long enough to be deleted, all continue moving | can the gallery be fooled into a WRONG recovery, not just a missed one |
| `small_target` | one object far below the detector's `reliable_size` | REVIEW §4.6 — recall, not identity, is what fails |
| `nonlinear` **(V0)** | the target REVERSES heading the instant it is hidden by an occlusion bar | breaks constant-velocity extrapolation — wave V3's ORU target |
| `tiny_fast` **(V0)** | three "handful of pixels" landmarks under a fast, steady pan | `CV-RATE-BUDGET.md` §2 — a tiny box has almost no IoU budget left for residual compensation error |
| `pan_occlusion` **(V0)** | continuous ego-motion AND a genuine occlusion gap, at once | `pan` and `occlusion` are each survivable alone; superposed, neither correction mechanism is available during the gap |
| `latency` **(V0)** | the detector reports a position from `latency_frames` frames ago | wave V6's target — the normal case for an offboard detector (§5.2, "L1 RELAY") |
| `crossing_similar` **(V0)** | two IDENTICALLY-coloured objects cross, both coasting through the crossing point | sharpens `crossing`: appearance (wave C3's own fix) is uninformative when both objects look alike |

`replay.py`'s `FOLLOW` mode is single-target by charter (`docs/plans/done/TRACKING-PLAN.md`
§3.1), so it locks onto and scores only `Sequence.primary_gt_id` — the object
each scenario is actually about (e.g. the occluded object, not the bar).
`ASSOCIATE` scores every ground-truth object the scenario declares.

**A structural fact worth knowing before reading any single-object ASSOCIATE
row** (`nonlinear`, `pan_occlusion`, `tiny_fast` with one landmark, etc.): with
one live candidate and one target, `cost`'s matcher always force-matches them
(no hard IoU/cost gate by default), so a single-object ASSOCIATE scenario can
show a COVERAGE defect (a gap, `FM`) but never an IDENTITY one (`IDSW`) no
matter how far the predicted box has drifted. `tiny_fast` has three competing
landmarks specifically because of this; `nonlinear`/`pan_occlusion`'s real
claim is in their FOLLOW row's coast ADE/FDE, not their ASSOCIATE row's `IDSW`.
See `BASELINE.md` §3 for the full writeup.

## What each metric means

Greedy per-frame IoU matching of ground truth to emitted tracks at
IoU >= 0.5 (the standard CLEAR-MOT approximation — see `metrics.py`'s module
docstring for why greedy instead of Hungarian). From that:

- **IDSW** — id switches, summed across every ground-truth object's
  trajectory. Counted by walking each object's MATCHED-frame timeline in
  order: every place the assigned track id changes counts, whether that's
  between two immediately-adjacent frames (the `crossing` case) or across an
  occlusion gap (a failed `occlusion`-style recovery).
- **FM** — fragmentations: how many times a ground-truth object's coverage was
  interrupted and then resumed before the clip ended.
- **MT / PT / ML** — mostly tracked (>=80% of the object's own on-screen
  window was matched to *some* id) / partially tracked / mostly lost (<20%).
  This is a coverage axis, deliberately separate from identity — a track that
  never changes id can still be mostly-lost if it stops matching the true
  position (see `BASELINE.md`'s `occlusion`/FOLLOW row for exactly that case).
- **recovery rate — the headline number for waves C4/C5, now saturated at
  100% wherever a gap exists.** Of the ground-truth objects that had an
  *internal* coverage gap (bounded by a match on both sides — a leading or
  trailing gap is real lost coverage, folded into ML, but there is nothing on
  one side of it to "recover" toward), the fraction that resumed with their
  **original track id**. `occlusion` was `~0` at the pre-C1 baseline (no
  `ObjectMemory` yet); wave C4's dormant gallery closed it to `100%`, which is
  exactly why this number alone cannot judge waves V1 onward — see the next
  bullet.
- **coast ADE / FDE (TRACKING-V3-PLAN wave V0) — average / final displacement
  error, in normalized units AND pixels at the sequence's own frame size,
  measured ONLY over frames where the reported box came from the TRACKER, not
  a fresh detector observation (`Track.source != SOURCE_DETECTOR`).** IDSW/
  FM/MT/recovery are all blind to this: a track can hold its id, recover
  perfectly, and still be dozens of pixels off the whole time it is coasting.
  ADE is the mean drift over every such frame; FDE is the mean of each
  maximal coasting RUN's *final* frame, i.e. how far off the box was right
  before it should have re-anchored — the number that matters most for
  waves V3-V5's reduction of it. Reads `n/a` when nothing ever coasted
  (`coast_n=0`), which is the honest, correct answer for `latency` (its own
  failure lives in DETECTOR-confirmed frames that are simply wrong) and for
  every ASSOCIATE row today (`_run_cost_associate` never emits a box for an
  unmatched candidate at all — see `metrics.py`'s own module docstring). Both
  facts are argued in full in `BASELINE.md` §2-3.
- **track lifetime (mean/median, frames)** — how many frames each *emitted*
  track id appeared in, counted as an appearance count rather than a
  first-to-last span (matching `cv/cv-service/MODULE.md`'s own T8 reporting
  convention). A fragmented identity shows up as several short-lived ids
  instead of one long one with a hole in it.
- **cost** (`det/s`, `trk_ms_avg`, `trk_ms_p95`) — detector passes per
  simulated second and `session.py`'s own real `time.perf_counter()` cost per
  frame. Included so a wave that buys accuracy with more CPU shows that
  trade-off honestly; `cv/cv-service/MODULE.md` already has the authoritative,
  production-measured cost numbers, this harness adds nothing new there.

## Baseline headline numbers (V0)

See [`BASELINE.md`](BASELINE.md) for the full table and per-scenario reading.
In short: the ten pre-existing scenarios score exactly as `TRACKING-V2-PLAN.md`
§5b's own final scoreboard records — zero IDSW everywhere, 100% recovery
wherever there is a gap, which is precisely why they cannot judge waves V1
onward and why wave V0 exists. Of the five new ones, `crossing_similar`/
ASSOCIATE shows `IDSW=2` (appearance made uninformative by an identical
colour reopens the pre-C3 `crossing` defect); `tiny_fast`/ASSOCIATE shows
`IDSW=2` (three "handful of pixels" landmarks under a fast pan); `latency`
shows `ML=1` in both modes from a purely systematic 8-frame lag, unmoved by
a 2026-08-14 instrument repair that finally wires `detection_lag_millis`
through and, in the process, found a genuine `cv_service/tracking/` defect
(see [`BASELINE.md`](BASELINE.md)'s `latency` writeup, and "Measuring
late-detection back-correction" above); and `nonlinear`/`pan_occlusion`'s
real claim is their FOLLOW row's coast ADE/FDE (up to `~105 px` on a
320x240 frame) — a defect the ten old scenarios' own metrics literally
cannot express.

## What happens with fewer dependencies installed

`run_replay` (`replay.py`) works even without `cv2`/`ultralytics`/`lap`
installed — `cv_service.tracking.registry.TrackerRegistry.probe()` simply
drops whichever engine fails to construct from its roster, and
`StreamTrackingSession` falls back down the `FOLLOW -> ASSOCIATE -> OFF`
ladder exactly as it would in production on a box missing a dependency. The
harness reports whatever the box it runs on actually serves. Building a
`Sequence` at all (`sequences.py`) does hard-require `numpy`, since a frame is
a pixel array — there is no meaningful degraded mode for that.
`recording.py` (below) needs neither — a recorded replay runs the same
`cost`/`predict`/`assign`/`memory`/`pose_gmc` identity core the L1 RELAY
capability level (`docs/plans/active/TRACKING-V3-PLAN.md` §5.2) will, with no
pixels involved anywhere on that path.

## Real-footage recording

`tools/trackeval/recording.py` (TRACKING-V3-PLAN wave V0) is a recorder and an
offline replay for a REAL detector stream — the other half of "there is no
camera in this repo": every scenario above is synthetic, known by
construction, and cannot prove anything about a real detector's real
confidence distribution or a real gimbal's `CameraPose`.

**Pixel-free, deliberately the only mode, not merely an option.** A
`Recording` stores detections + `CameraPose` per frame, never pixels — small
enough to commit (a few hundred bytes/frame of JSON, not a video file), and
honest about its own limit: FOLLOW's SOT engines and ASSOCIATE's `flow`/
`histogram` compensators all need real pixels, so `replay_recording` has no
`mode` parameter at all (ASSOCIATE is the only thing it can honestly serve)
and forces `motion_engine_id`/`appearance_engine_id` to `"off"` rather than
feeding either a `None` frame. See the module's own docstring for the full
reasoning.

```python
from tools.trackeval.recording import LiveFrame, record_stream, write_recording, read_recording, replay_recording

# `frames` -- one `LiveFrame(index, timestamp_millis, detections, pose)` per
# frame a real capture loop received, built from whatever live session a
# caller has (a PulledDetectionSession, a DetectionFrameCodec, ...).
recording = record_stream("my-clip", fps=10.0, width=640, height=360, frames=frames)
write_recording(recording, "my-clip.json")

loaded = read_recording("my-clip.json")
result = replay_recording(loaded)  # ASSOCIATE only -- see the module docstring
```

`metrics.summarize_recording(result.outcomes, fps=result.fps,
coast_track_ids=result.coast_track_ids)` is the no-ground-truth counterpart
of `metrics.compute` — track count, lifetimes, cost, and the coasting
fraction, explicitly NOT an accuracy number (there is no known true position
to score against unless the recording is separately hand-labelled later).

## Tests

`tests/trackeval/` mirrors this package: `test_sequences.py` (determinism +
every scenario generates without raising, needs `numpy`), `test_replay.py`
(every scenario replays in every mode without raising, determinism, the
`latency` lag mechanism including `detection_lag_millis` wiring/jitter and
the divergence regression the 2026-08-14 instrument repair added, needs
`numpy`), `test_metrics.py` (metric correctness on hand-built inputs,
including coast ADE/FDE — pure stdlib, no `cv` extra needed at all),
`test_recording.py` (record/write/read/replay round-trip against a
synthetic "live" source, needs `numpy` to build that source), `test_main.py`
(the CLI), `test_baseline_consistency.py` (`BASELINE.md`'s own table,
diffed against a fresh `--all` run every `pytest -q`). Run from `cv/cv-service/`:

```bash
PYTHONPATH="$PWD" .venv/bin/python -m pytest -q tests/trackeval/
```
