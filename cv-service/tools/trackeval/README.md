# tools/trackeval

The tracking-accuracy evaluation harness (`docs/plans/active/TRACKING-V2-PLAN.md`
§4, wave C0). It closes `docs/conclusions/TRACKING-REVIEW.md` finding E: every
number `cv-service/MODULE.md`'s "Tracking engine" section reports is a **cost**
number (ms, CPU%, duty ratio) — nothing measured whether tracking actually lost
the subject. This package is the first **accuracy** scoreboard, and waves
C1–C5 are judged against [`BASELINE.md`](BASELINE.md).

There is no camera in this repo — hardware tier H1 is unbought — so ground
truth is generated, not recorded, and known exactly by construction rather
than estimated.

## Run it

From `cv-service/`, with the venv's `cv` extra installed (`numpy`/`cv2` render
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

`--scenario` is one of `linear` / `occlusion` / `crossing` / `pan` / `dropout`
(see "Scenarios" below); `--mode` is `ASSOCIATE` or `FOLLOW`. `--all` runs
every scenario in both modes and prints the full matrix — this is what
[`BASELINE.md`](BASELINE.md) is a saved copy of. `--engine` overrides the
engine id (`""`, the default, uses the server's own default for the mode);
`--seed` overrides `sequences.DEFAULT_SEED`.

## Package shape

```
tools/trackeval/
  sequences.py   synthetic ground-truth clips, known by construction
  replay.py      feeds a sequence through the REAL
                 cv_service.tracking.session.StreamTrackingSession, via a
                 seeded synthetic detector -- never a reimplementation
  metrics.py     greedy IoU matching -> IDSW / FM / MT-PT-ML / recovery
                 rate / track lifetime / cost
  __main__.py    the CLI
  BASELINE.md    the pre-C1 scoreboard waves C1-C5 diff against
```

Mirrors `cv_service/tracking/`'s own convention: one file, one job, and every
module is **pure stdlib at module scope** — `numpy` is imported lazily inside
`sequences.py`'s `_render_frame`, and the real tracker engines `replay.py`
builds are constructed lazily by `cv_service.tracking.registry`'s own
factories. Nothing here needs `cv2`/`ultralytics`/`torch` just to be
*imported*.

## Scenarios

Each isolates one failure mode from `docs/conclusions/TRACKING-REVIEW.md` §3,
deterministic given a seed (same seed -> same frames, same boxes, forever):

| Scenario | What it does | REVIEW finding |
|---|---|---|
| `linear` | 2-3 well-separated objects, constant velocity, always visible | the floor — nothing later should regress this |
| `occlusion` | one object passes fully behind an opaque bar for longer than `CV_TRACK_MAX_AGE` (so the track genuinely goes `LOST`, not just coasts) | B3 — a LOST track is unrecoverable by construction |
| `crossing` | two similar-sized objects' paths intersect | B1 — no appearance model exists anywhere, so geometry alone cannot disambiguate them |
| `pan` | the CAMERA moves (ego-motion); objects also move | A1/A2 — nothing compensates for the camera's own motion |
| `dropout` | the object is always visible; the *detector* is configured to intermittently miss it | §4.6 — detection recall, isolated from identity |

`replay.py`'s `FOLLOW` mode is single-target by charter (`docs/plans/done/TRACKING-PLAN.md`
§3.1), so it locks onto and scores only `Sequence.primary_gt_id` — the object
each scenario is actually about (e.g. the occluded object, not the bar).
`ASSOCIATE` scores every ground-truth object the scenario declares.

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
- **recovery rate — the headline number for waves C4/C5.** Of the
  ground-truth objects that had an *internal* coverage gap (bounded by a match
  on both sides — a leading or trailing gap is real lost coverage, folded into
  ML, but there is nothing on one side of it to "recover" toward), the
  fraction that resumed with their **original track id**. `~0` today for
  `occlusion` is the expected, honest baseline: there is no `ObjectMemory` yet
  (that is wave C4).
- **track lifetime (mean/median, frames)** — how many frames each *emitted*
  track id appeared in, counted as an appearance count rather than a
  first-to-last span (matching `cv-service/MODULE.md`'s own T8 reporting
  convention). A fragmented identity shows up as several short-lived ids
  instead of one long one with a hole in it.
- **cost** (`det/s`, `trk_ms_avg`, `trk_ms_p95`) — detector passes per
  simulated second and `session.py`'s own real `time.perf_counter()` cost per
  frame. Included so a wave that buys accuracy with more CPU shows that
  trade-off honestly; `cv-service/MODULE.md` already has the authoritative,
  production-measured cost numbers, this harness adds nothing new there.

## Baseline headline numbers (pre-C1)

See [`BASELINE.md`](BASELINE.md) for the full table and per-scenario reading.
In short, at the commit recorded there: `crossing`/ASSOCIATE shows `IDSW=4`
(a perfect detector alone does NOT trigger this — it takes a small, realistic
amount of detector position noise to fool `bytetrack`'s own motion model);
`occlusion`/ASSOCIATE shows `recovery%=0` (the track is issued a brand new id
after the gap); `pan`/ASSOCIATE shows `mostly_tracked=0` across all four
objects it ever sees.

## What happens with fewer dependencies installed

`run_replay` (`replay.py`) works even without `cv2`/`ultralytics`/`lap`
installed — `cv_service.tracking.registry.TrackerRegistry.probe()` simply
drops whichever engine fails to construct from its roster, and
`StreamTrackingSession` falls back down the `FOLLOW -> ASSOCIATE -> OFF`
ladder exactly as it would in production on a box missing a dependency. The
harness reports whatever the box it runs on actually serves. Building a
`Sequence` at all (`sequences.py`) does hard-require `numpy`, since a frame is
a pixel array — there is no meaningful degraded mode for that.

## Tests

`tests/trackeval/` mirrors this package: `test_sequences.py` (determinism +
every scenario generates without raising, needs `numpy`), `test_replay.py`
(every scenario replays in every mode without raising, determinism, needs
`numpy`), `test_metrics.py` (metric correctness on hand-built inputs — pure
stdlib, no `cv` extra needed at all), `test_main.py` (the CLI). Run from
`cv-service/`:

```bash
PYTHONPATH="$PWD" .venv/bin/python -m pytest -q tests/trackeval/
```
