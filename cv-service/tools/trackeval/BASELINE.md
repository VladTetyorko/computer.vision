# trackeval baseline — pre-C1

**Commit:** `cdef30ae849d91b0e3d7489b0755cd97ee2da817` — "feat(tracking-v2): the two
cores where a wrong answer is invisible" (adds `engines/pose_gmc.py` and
`assign.py`, explicitly **not yet wired into `session.py` or `registry.py`** per
that commit's own message — `StreamTrackingSession`'s runtime behaviour at this
commit is therefore identical to wave T8's, the shipped pre-TRACKING-V2 service).
**Date recorded:** 2026-08-11.
**Status:** this is the **pre-C1 baseline**. Waves C1–C5 are judged by diffing
their own `--all` output against the table below — an improvement is a wave
moving a number in the direction its own acceptance criterion names; a
regression anywhere else is a bug, not a trade-off, unless the wave's own
writeup says otherwise.

Reproduce with (see `cv-service/MODULE.md` for the full `PYTHONPATH` explanation):

```bash
cd cv-service
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all
```

Every scenario uses `sequences.DEFAULT_SEED` (`20260811`) and the per-scenario
detector-noise defaults in `tools/trackeval/__main__.py`'s
`DEFAULT_NOISE_BY_SCENARIO` — a perfect (noise-free) synthetic detector for
`linear`/`occlusion`/`pan`, a small (2% of frame width) realistic position
jitter for `crossing`, and a 35% per-frame dropout probability for `dropout`.
Both engines are the server's own defaults: `bytetrack` for ASSOCIATE, `lk` for
FOLLOW (`CV_TRACK_ASSOCIATE_ENGINE`/`CV_TRACK_FOLLOW_ENGINE`,
`cv_service/config.py`).

## The table

```
scenario  | mode                    | engine    | frames | gt | IDSW | FM | MT | PT | ML | gaps | recov | recov% | life_mean | life_med | det/s | trk_ms_avg | trk_ms_p95
----------+-------------------------+-----------+--------+----+------+----+----+----+----+------+-------+--------+-----------+----------+-------+------------+-----------
crossing  | TRACKING_MODE_ASSOCIATE | bytetrack | 50     | 2  | 4    | 0  | 2  | 0  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 10.00 | 0.04       | 0.00
crossing  | TRACKING_MODE_FOLLOW    | lk        | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 0.60  | 0.26       | 1.00
dropout   | TRACKING_MODE_ASSOCIATE | bytetrack | 50     | 1  | 0    | 11 | 0  | 1  | 0  | 11   | 11    | 100%   | 29.0      | 29.0     | 10.00 | 0.00       | 0.00
dropout   | TRACKING_MODE_FOLLOW    | lk        | 50     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 45.0      | 45.0     | 1.60  | 0.24       | 1.00
linear    | TRACKING_MODE_ASSOCIATE | bytetrack | 60     | 3  | 0    | 0  | 3  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 10.00 | 0.18       | 1.00
linear    | TRACKING_MODE_FOLLOW    | lk        | 60     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 0.50  | 0.28       | 1.00
occlusion | TRACKING_MODE_ASSOCIATE | bytetrack | 110    | 1  | 1    | 1  | 0  | 1  | 0  | 1    | 0     | 0%     | 34.5      | 34.5     | 10.00 | 0.01       | 0.00
occlusion | TRACKING_MODE_FOLLOW    | lk        | 110    | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 110.0     | 110.0    | 0.55  | 0.08       | 1.00
pan       | TRACKING_MODE_ASSOCIATE | bytetrack | 70     | 4  | 0    | 0  | 0  | 4  | 0  | 0    | 0     | n/a    | 24.0      | 25.0     | 10.00 | 0.20       | 1.00
pan       | TRACKING_MODE_FOLLOW    | lk        | 70     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 70.0      | 70.0     | 0.71  | 0.17       | 1.00
```

## Reading it, scenario by scenario

**`crossing` / ASSOCIATE — `IDSW=4`, the headline confirmation of REVIEW
finding B1.** With a PERFECT detector, `bytetrack`'s Kalman motion model
actually resolves a symmetric, 94%-peak-IoU head-on crossing correctly every
time (measured while tuning this scenario, not assumed) — a pure-geometry
crossing alone is not the failure mode. Add the small (2%), entirely realistic
position jitter a real `YoloDetector` would have, and the same crossing swaps
ids 4 times with zero fragmentation: both objects stay continuously matched to
*something*, they just periodically trade identities. This is exactly REVIEW's
"unresolvable ambiguity for an IoU matcher" — no appearance model exists
anywhere in the pipeline to break the tie (finding B1). Wave C3 (`assign.py` +
`engines/histogram.py`) is judged by whether `IDSW` on this row drops toward 0
without `FM` rising to compensate.

**`occlusion` / ASSOCIATE — `recovery%=0`, the headline confirmation of REVIEW
finding B3.** The occlusion window (40 frames) is deliberately longer than
`CV_TRACK_MAX_AGE`'s default (30, `DEFAULT_TRACK_MAX_AGE_FRAMES`) — long enough
to push the track past `LOST`. On re-appearance the object is issued a **new**
track id (`IDSW=1`, `FM=1`, `recovered_count=0` of `gap_count=1`): "a LOST
track is unrecoverable by construction" (REVIEW B3), because there is no
`ObjectMemory` yet to hold a dormant identity across the gap. This is the
number wave C4 exists to move.

**`occlusion` / FOLLOW — a subtler, arguably worse defect (REVIEW finding C1),
visible only because `life_mean` and coverage are reported as separate
axes.** The emitted track id **never changes** (`life_mean=110.0` — the same
id 1 is on every one of the 110 responses) — naively that would read as
"tracking held." But `MT/PT/ML` show only **partially tracked** coverage
(~31%, `mostly_tracked=0`): once the LK engine loses the target behind the bar,
`_coast` freezes the box at its last known position and the session stops
calling the engine at all (`_tracker_stalled`). The next several cadence-driven
verify passes each fail to re-anchor because the frozen box's IoU with the
*true, moved* position has decayed to ~0 — including passes that run **after**
the object has already re-emerged. The freeze is what causes the subsequent
re-anchor failure, exactly as REVIEW C1 names it: "A lost tracker freezes the
box instead of predicting it... The freeze is what *causes* the subsequent
re-anchor failure." Wave C1's constant-velocity prediction (`predict.py`) is
judged by whether this row's coverage rises toward `mostly_tracked` without
`life_mean` becoming misleading in the other direction (a new id minted too
eagerly).

**`pan` / ASSOCIATE — `mostly_tracked=0` across all 4 objects, the headline
confirmation of REVIEW finding A1/A2.** Nothing compensates for the fact that
the CAMERA moved: every one of the four world-landmarks this scenario sweeps
past is only ever "partially tracked," even measured against its own natural
on-screen window, never "mostly tracked." No `IDSW`/`FM` either — the failure
here is coverage (frequent re-birth as the frame-to-frame IoU degrades under
ego-motion), not identity-swapping between two co-visible objects. Wave C2
(`engines/{flow_gmc,pose_gmc}.py`) is judged by whether `MT` on this row rises
off zero.

**`dropout` / ASSOCIATE — `recovery%=100`, and that is the CORRECT,
already-working outcome for this failure mode, not a gap.** With a 35%
per-frame detector miss rate the track fragments constantly (`FM=11` over 50
frames) but recovers its id every single time (`gap_count=11`,
`recovered_count=11`): every gap here is short enough (1-2 frames, well inside
`max_age_frames=30`) for `bytetrack`'s own short-term coasting to bridge it.
This scenario exists to isolate detection recall (REVIEW §4.6) from identity
loss — it shows today's association layer already tolerates *brief*
intermittent misses well; the ROI re-detection / two-threshold policy wave C5
plans is about recovering the *coverage* this scenario's `PT` classification
shows is still lost, not about identity, which is already fine here.

**`linear` / both modes — clean, as designed.** No switches, no
fragmentation, full coverage, at both the 10 fps ASSOCIATE duty cycle and
FOLLOW's ~0.5-0.7 detector passes/sec. This is the floor every later wave must
not regress: nothing here should ever get *worse*.

## Reading `det/s` and `trk_ms_*`

These are the **cost** axis this harness adds nothing new to measure —
`cv-service/MODULE.md`'s "Tracking engine" section already has real,
production numbers for these. They are included per row here only so a wave
that spends more CPU to fix an accuracy number is a visible, honest trade-off
rather than a free lunch. `trk_ms_*` is `time.perf_counter()` wall time inside
`session.py` on whatever machine ran this — real noise, not a synthetic
number, and not comparable across machines the way the accuracy columns are.
