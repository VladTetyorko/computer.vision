# trackeval baseline

**Status: this is the V0 baseline** (`docs/plans/active/TRACKING-V3-PLAN.md`, wave V0) --
the scoreboard waves V1-V8 are judged against. It supersedes the "pre-C1" snapshot below
(kept as §2, historical) now that waves C1-C5 have shipped: `TRACKING-V2-PLAN.md` §5b
already recorded C1-C5's own final ASSOCIATE-only scoreboard (zero IDSW, 100% recovery,
all ten scenarios). That scoreboard is why wave V0 exists at all -- **it is saturated: a
harness with zero switches and perfect recovery everywhere cannot show an improvement or a
regression, so no later wave could prove anything against it.**

Wave V0 changed nothing under `cv_service/` -- every number below for the ten
pre-existing scenarios is produced by the SAME session code C5 shipped. What changed is
the HARNESS: five new scenarios built to isolate exactly the failure modes waves V2-V6
exist to fix, and two new metrics (coast ADE/FDE) that can see drift IDSW/FM/MT cannot.

**2026-08-14 instrument repair.** `latency`'s own harness never told `session.process()`
about the lag it injected (`detection_lag_millis` defaulted to `0`, "unknown"), which made
that scenario unwinnable by construction, not merely hard -- see its own writeup in §2 below
for the fix, why the table row is unchanged regardless, and the genuine `cv_service/tracking/`
defect (out of that repair's own file scope to fix) it exposed once wired through. No other
row changed -- every number below for the other fourteen scenarios is produced by the SAME
`cv_service/` code every prior wave measured against, still untouched by this repair.

Reproduce with (see `cv-service/MODULE.md` for the full `PYTHONPATH` explanation):

```bash
cd cv-service
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all
```

Every scenario uses `sequences.DEFAULT_SEED` (`20260811`) and the per-scenario detector-noise
defaults in `tools/trackeval/__main__.py`'s `DEFAULT_NOISE_BY_SCENARIO`. Both engines are the
server's own defaults: `cost` for ASSOCIATE, `lk` for FOLLOW (`CV_TRACK_ASSOCIATE_ENGINE`/
`CV_TRACK_FOLLOW_ENGINE`, `cv_service/config.py`).

## 1. The V0 table -- all fifteen scenarios

```
scenario          | mode                    | engine | frames | gt | IDSW | FM | MT | PT | ML | gaps | recov | recov% | life_mean | life_med | det/s | trk_ms_avg | trk_ms_p95 | coast_n | cADE  | cFDE  | cADE_px | cFDE_px
------------------+-------------------------+--------+--------+----+------+----+----+----+----+------+-------+--------+-----------+----------+-------+------------+------------+---------+-------+-------+---------+--------
clutter           | TRACKING_MODE_ASSOCIATE | cost   | 60     | 10 | 0    | 0  | 10 | 0  | 0  | 0    | 0     | n/a    | 55.8      | 60.0     | 10.00 | ~4         | ~5         | 0       | n/a   | n/a   | n/a     | n/a
clutter           | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 0.83  | ~1         | ~1         | 55      | 0.218 | 0.324 | 69.9    | 103.8
crossing          | TRACKING_MODE_ASSOCIATE | cost   | 50     | 2  | 0    | 0  | 2  | 0  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
crossing          | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 0.80  | ~0.4       | ~1         | 48      | 0.194 | 0.228 | 62.1    | 72.9
crossing_similar  | TRACKING_MODE_ASSOCIATE | cost   | 50     | 2  | 0    | 2  | 2  | 0  | 0  | 2    | 2     | 100%   | 44.0      | 44.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
crossing_similar  | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 0.80  | ~0.4       | ~1         | 48      | 0.357 | 0.752 | 95.9    | 196.8
crowd_recall      | TRACKING_MODE_ASSOCIATE | cost   | 150    | 6  | 0    | 6  | 6  | 0  | 0  | 6    | 6     | 100%   | 60.0      | 60.0     | 10.00 | ~0.8       | ~2         | 0       | n/a   | n/a   | n/a     | n/a
crowd_recall      | TRACKING_MODE_FOLLOW    | lk     | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 101.0     | 101.0    | 1.47  | ~0.4       | ~1         | 98      | 0.016 | 0.022 | 4.9     | 6.8
dropout           | TRACKING_MODE_ASSOCIATE | cost   | 50     | 1  | 0    | 4  | 1  | 0  | 0  | 4    | 4     | 100%   | 41.0      | 41.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
dropout           | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 35.0      | 35.0     | 1.40  | ~0.7       | ~1         | 33      | 0.002 | 0.003 | 0.6     | 0.8
latency           | TRACKING_MODE_ASSOCIATE | cost   | 60     | 1  | 0    | 0  | 0  | 0  | 1  | 0    | 0     | n/a    | 52.0      | 52.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
latency           | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 0  | 0  | 1  | 0    | 0     | n/a    | 48.0      | 48.0     | 1.17  | ~0.8       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
linear            | TRACKING_MODE_ASSOCIATE | cost   | 60     | 3  | 0    | 0  | 3  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 10.00 | ~1         | ~1         | 0       | n/a   | n/a   | n/a     | n/a
linear            | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 0.50  | ~1         | ~1         | 57      | 0.002 | 0.002 | 0.6     | 0.6
long_occlusion    | TRACKING_MODE_ASSOCIATE | cost   | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 60.0      | 60.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
long_occlusion    | TRACKING_MODE_FOLLOW    | lk     | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 101.0     | 101.0    | 1.47  | ~0.4       | ~1         | 98      | 0.006 | 0.007 | 1.4     | 1.8
nonlinear         | TRACKING_MODE_ASSOCIATE | cost   | 70     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 50.0      | 50.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
nonlinear         | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 70.0      | 70.0     | 0.71  | ~0.4       | ~1         | 66      | 0.029 | 0.045 | 9.1     | 14.3
occlusion         | TRACKING_MODE_ASSOCIATE | cost   | 110    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 70.0      | 70.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
occlusion         | TRACKING_MODE_FOLLOW    | lk     | 110    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 110.0     | 110.0    | 0.55  | ~0.6       | ~1         | 106     | 0.008 | 0.009 | 1.9     | 2.1
pan               | TRACKING_MODE_ASSOCIATE | cost   | 70     | 4  | 0    | 0  | 4  | 0  | 0  | 0    | 0     | n/a    | 24.8      | 26.0     | 10.00 | ~0.3       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
pan               | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 70.0      | 70.0     | 0.86  | ~0.3       | ~1         | 13      | 0.011 | 0.040 | 3.3     | 12.8
pan_occlusion     | TRACKING_MODE_ASSOCIATE | cost   | 90     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 65.0      | 65.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
pan_occlusion     | TRACKING_MODE_FOLLOW    | lk     | 90     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 87.0      | 87.0     | 0.78  | ~0.3       | ~1         | 84      | 0.260 | 0.328 | 83.3    | 104.8
pan_step          | TRACKING_MODE_ASSOCIATE | cost   | 90     | 2  | 0    | 2  | 2  | 0  | 0  | 2    | 2     | 100%   | 60.0      | 60.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
pan_step          | TRACKING_MODE_FOLLOW    | lk     | 90     | 1  | 0    | 3  | 1  | 0  | 0  | 3    | 3     | 100%   | 90.0      | 90.0     | 0.56  | ~1         | ~1         | 87      | 0.013 | 0.016 | 3.8     | 4.9
small_target      | TRACKING_MODE_ASSOCIATE | cost   | 80     | 1  | 0    | 2  | 1  | 0  | 0  | 2    | 2     | 100%   | 78.0      | 78.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
small_target      | TRACKING_MODE_FOLLOW    | lk     | 80     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 80.0      | 80.0     | 0.50  | ~1         | ~1         | 78      | 0.001 | 0.001 | 0.3     | 0.3
tiny_fast         | TRACKING_MODE_ASSOCIATE | cost   | 70     | 3  | 2    | 0  | 3  | 0  | 0  | 0    | 0     | n/a    | 19.0      | 19.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a
tiny_fast         | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 0  | 0  | 0  | 1  | 0    | 0     | n/a    | 62.0      | 62.0     | 1.00  | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a
```

`trk_ms_avg`/`trk_ms_p95` are rounded to `~N` here on purpose -- they are real
`time.perf_counter()` wall-clock noise from whatever machine ran this (see §3 below), and
have fluctuated between consecutive `--all` runs on the SAME unmodified code throughout
this wave's own work. Every other column is exact and deterministic given the seed.

**The ten pre-existing scenarios (`clutter` through `small_target`) score EXACTLY as
`TRACKING-V2-PLAN.md` §5b's own final scoreboard records** -- zero IDSW everywhere, 100%
recovery wherever there is a gap. Verified by running `--all` before touching a single line
of `sequences.py`/`metrics.py`/`replay.py` and diffing after: identical on every column
except `trk_ms_*`. `coast_n`/`cADE`/`cFDE`/`cADE_px`/`cFDE_px` are new columns, additive by
construction (`metrics.compute` never touches `matches_by_frame`/`windows` to produce
them -- see `metrics.py`'s own module docstring) -- confirmed empirically, not merely
argued: the other seventeen columns are untouched to the last digit.

## 2. The five new scenarios, read one at a time

**`crossing_similar` / ASSOCIATE -- `IDSW=0`, `FM=2`, recovery 100%.** Same geometry as
`crossing`, same small realistic jitter, but both objects are the SAME colour and both
coast through a brief window centred on the crossing point. `engines/histogram.py` (wave
C3) is what closed the ORIGINAL `crossing` row to `IDSW=0` -- appearance breaks the
geometric tie. Make appearance uninformative (identical colour) and the tie is back, but
**it costs fragmentation, not identity**: both tracks break at the crossing and both are
recovered by wave C4's dormant gallery, so the ids survive and the continuity does not.

**Read this row carefully, because it is not the defect it looks like.** An earlier draft
of this file recorded `IDSW=2` with zero fragmentation here, and that number was wrong --
re-measured directly, and re-measured again with wave V1's `cv_service/` changes stashed
out to rule them out as the cause, the answer is `IDSW=0 / FM=2` both times. The scenario
does expose a real defect; it is just a *different* one than first recorded, which is
precisely the apparatus-error class `TRACKING-V2-PLAN.md` §5b warned about. **Wave V4's
acceptance is therefore stated against `FM`, not `IDSW`:** the crossing must stop breaking
the track, not stop swapping ids, because it never swapped them.

The FOLLOW row is where this scenario's sharpest signal actually lives -- coast
ADE/FDE **95.9 / 196.8 px** on a 320x240 canvas, the largest final drift of any scenario
here. **This is the harness catching E7's own resolution honestly** -- decision E7 keeps
histogram as the default because the UAV literature (AMOT) says appearance matters more on
real footage than the generic MOT literature does, and this pair of rows is exactly the
case where losing that signal costs continuity and position.

**`tiny_fast` / ASSOCIATE -- `IDSW=2`, MT stays 3/3.** Three world-static, "handful of
pixels" landmarks (`TINY_FAST_SIZE=0.025`, ~8x6 px on this harness's 320x240 canvas) swept
by a camera panning at `TINY_FAST_CAMERA_VELOCITY=0.06` -- ~19 px/frame, nearly double
`pan`'s own steady 0.035 (~11 px/frame), in `CV-RATE-BUDGET.md` §2's ballpark for a fast
yaw. Coverage stays perfect (every landmark is matched to SOME id on every visible frame),
but WHICH id it is matched to swaps -- a pure identity confusion, not a coverage failure,
robust across a `0.055`-`0.07` neighbourhood of the chosen velocity (not a knife-edge
value; see the scenario's own module comment). **`tiny_fast` / FOLLOW -- `ML=1`, a total
loss, and a DIFFERENT failure mode from ASSOCIATE's, both genuine.** `lk`'s own
`_MIN_TRACKED_CORNERS` floor (`engines/lk.py`) cannot reliably hold a box this small: the
lock never stays anchored long enough to accumulate real coverage. A single-object variant
of this scenario was tried first and discarded specifically because a lone object can never
show ASSOCIATE-level identity confusion at all -- with one candidate and one target, `cost`
always force-matches them regardless of geometric quality (see `nonlinear`'s own ASSOCIATE
row below for the same structural fact), so `tiny_fast` needed real competition, which is
why it has three landmarks.

**`nonlinear` / ASSOCIATE -- `FM=1`, recovery 100%, but this row is structurally
uninformative and should not be over-read.** With one ground-truth object, `cost`'s
matcher has exactly one live candidate and one target every frame it runs at all, and
`AssignGates.min_iou`/`max_cost` default to `0.0`/`inf` (no hard geometric floor) --
so the pair is force-matched REGARDLESS of how far off the drifted candidate box is.
This is not a bug in `nonlinear`; it is a genuine, load-bearing fact about single-object
ASSOCIATE scenarios that this wave's own tuning surfaced (see `tiny_fast`'s writeup, which
is a direct response to it): **a lone target can never demonstrate a geometric tracking
defect under `cost`, only a coverage one.** `nonlinear` / FOLLOW is where the real defect
showed: at wave V0 it scored `PT=1` (not MT -- coverage genuinely degraded) and
**`coast_ade_px=39.8`, `coast_fde_px=59.3`** on a 320x240 frame -- the box drifted nearly a
fifth of the frame's own width by the time it should have re-anchored, because
constant-velocity extrapolation ran the WRONG WAY the entire time: the object reverses
heading the instant it goes behind the bar. **This is precisely the number IDSW/FM/MT
cannot see** (the plan's own words for wave V0's charter) -- the id never changed and the
coverage classification was a single bucket (PT), and neither says anything about HOW FAR
OFF the box was. Coast ADE/FDE is the only column in this table that does.

> **Closed by wave V3 (ORU + OCR).** The row above now reads `MT=1`, `FM=1`, recovery 100%,
> **`coast_ade_px=9.1` / `coast_fde_px=14.3`** -- a 77%/76% reduction in drift, and the
> track is promoted from Partially to Mostly Tracked. It is the only one of the thirty rows
> in §1 that wave V3 moved; the other twenty-nine are byte-identical, and setting
> `CV_TRACK_REUPDATE_MAX_GAP_MILLIS=0` reproduces the whole table including this row
> (invariant P7). Both halves of the fix were needed and neither alone would have shown
> here: ORU rebuilds the gap retrospectively, and the observation-centric re-anchor
> fallback is what lets the re-anchor fire at all -- a drifted prediction running the wrong
> way can never clear the IoU test that gates ORU's own invocation.

**`pan_occlusion` / FOLLOW -- `coast_ade_px=83.3`, `coast_fde_px=104.8`, the largest drift
in this table.** `pan` alone (continuous ego-motion, no gap) and `occlusion` alone (a gap,
no ego-motion) are both fully survivable today -- `pan`'s own coast numbers are modest
(`cADE_px=3.3`) because every frame's fresh detection corrects whatever the previous
frame's warp got slightly wrong before it can compound, and `occlusion`'s coasting is pure
constant-velocity with nothing else confusing it. Superposing them removes both correction
mechanisms for the SAME 25-frame window at once: no detection to correct the warp, and the
warp itself running every one of those frames (`TrackBook.warp()`) -- so whatever residual
per-frame compensation error `flow_gmc`'s fit carries gets 25 chances to compound instead
of one. `ASSOCIATE`'s own row (`FM=1`, recovery 100%) is the same single-object
force-match artefact `nonlinear`'s is -- read the FOLLOW row for this scenario's real
claim.

**`latency` / both modes -- `ML=1`, a clean, strong result from a purely SYSTEMATIC
bias, not sporadic noise.** `latency_frames=8` (800 ms at this harness's 10 fps) makes the
detector describe a position the object left 8 frames ago; ASSOCIATE's single track stays
alive and CONTINUOUSLY confirmed (`life_mean=52` of 60 frames, `IDSW=0`, `FM=0`, `gaps=0`)
but its own reported box is *wrong* almost every frame, dragging coverage down to Mostly
Lost without ever registering as an identity or coverage-fragmentation event. **`coast_n=0`
in both modes is itself the finding, not a gap in the harness**: `latency`'s failure lives
entirely in `Track.source == SOURCE_DETECTOR` frames that are simply WRONG, and coast
ADE/FDE, by its own honest scope (see `metrics.py`'s module docstring), only measures
`SOURCE_TRACKER` frames. IDSW/MT catch this defect fine; coast ADE/FDE, correctly, does
not -- the two metrics are covering genuinely different failure shapes, which is the point
of shipping both.

**Instrument repair (2026-08-14): the seventh apparatus defect, and what fixing it found.**
Every number above was produced by a harness that injected an 8-frame detector lag and then
never told `StreamTrackingSession.process()` about it -- `replay.py` called `process(now_millis=
..., detect=..., frame=...)` with no `detection_lag_millis`, which defaults to `0` ("unknown").
Wave V6's late-detection back-correction (`docs/plans/active/TRACKING-V3-PLAN.md` §4.5, commit
`1665969`) reads exactly that argument and had no way to fire. Worse: under constant velocity
and constant lag `L`, a late stream `p(t-L) = (p0 - vL) + vt` is mathematically indistinguishable
from an on-time stream starting `vL` further back -- `L` is unidentifiable from (position,
arrival-time) pairs alone, so no algorithm the tracker could ship would ever have closed this
row. The scenario was unwinnable by construction, not merely hard.

`replay.py` now computes the lag it already knows (it is the config that shifted the ground
truth) and passes it as `detection_lag_millis`, mirroring how pull mode obtains the SAME signal
for real: `cv_service/pull/clock.py`'s `CaptureClock.capture_time` reports `capture_skew_millis =
now_wall - captured_at`, a same-process, same-clock estimate `grpc/servicers.py`'s
`_handle_request` threads straight into `session.process()`. The default reports the exact
injected lag (`DetectorNoiseConfig.detection_lag_jitter_millis=0.0`) -- the IDEALISED case,
perfect lag knowledge a real estimate never quite has. `--lag-jitter-millis 15` (`replay.
DETECTION_LAG_JITTER_TYPICAL_MILLIS`, sourced from `pull/clock.py`'s own module docstring --
M0 measured `capture_skew_millis` at "+/-15 ms typical spread over 10 minutes, worst spike
~80 ms") adds a realistic companion reading:

```bash
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE --lag-jitter-millis 15
```

**The row above is unchanged by either reading -- `ML=1` in both modes, exact and jittered
alike -- and the reason is not "no improvement": it is a SECOND, previously-invisible defect,
this time a genuine one in `cv_service/tracking/` (out of this repair task's file scope,
`tools/trackeval/**` and `tests/trackeval/**` only, to fix).** With `detection_lag_millis`
finally nonzero, wave V6's correction DOES fire -- and diverges. Traced directly (`tests/
trackeval/test_replay.py::test_persistent_per_frame_lag_correction_diverges_past_the_dead_zone`,
and independently reproduced with round numbers straight against `StreamTrackingSession`,
bypassing this package entirely): the reported box stays exactly stale through a `2 * lag`-frame
"dead zone" (`late_correction` correctly refuses to correct a track with no bracketing prior
observation -- `reupdate.py`'s own "brand-new track has nothing to bracket against" contract),
then, the instant a bracket becomes available, the corrected box does not converge toward the
true position -- it runs away to physically meaningless magnitudes (`1e14`-`1e32` within a few
dozen frames, in BOTH the exact and the 15ms-jittered reading; jitter does not soften this).

**Root cause.** `ObservationRing.record()` (`cv_service/tracking/history.py`) timestamps every
entry with the frame it was PROCESSED at (`now`), not the instant its content was actually true.
That is correct exactly when a `late_correction` succeeds (a corrected box legitimately
represents "position AT now") -- and wrong for any entry `late_correction` could not correct,
which is every entry through the dead zone above: each one's stale content gets filed under a
timestamp `lag_seconds` LATER than when it was true, with nothing recording the mislabelling. The
next `reupdate()` call that brackets against one of those entries divides a REAL position delta
(spanning close to a full `lag_seconds` of true motion) by an ARTIFICIALLY SMALL elapsed time
(the bracket's inflated timestamp), inflating the reconstructed velocity by roughly
`lag_seconds / true_elapsed`. The resulting (wrong, usually off-frame) box is then written back
into the SAME ring, poisoning the next bracket the same way -- a positive feedback loop, not a
one-off error, and why this diverges rather than merely staying imprecise. It bites hardest
exactly where wave V6 is supposed to help most: a persistent, near-constant `capture_skew_millis`
(`pull/clock.py`'s own documented steady state between re-anchors) applied every matched-detection
frame (`_run_cost_associate`'s per-candidate call, ASSOCIATE's `det/s=10.00` cadence) -- not a
corner case this scenario invented, but the ordinary shape of a pulled stream's own signal.

**Why the scoreboard row does not move even though the underlying box now behaves far worse.**
`ML=1` already scored the PRE-repair stale case at the metric's own floor: IoU against the true
box was already ~0 every frame (`latency_frames=8` was tuned to guarantee exactly that -- see
`__main__.py`'s own comment on `_LATENCY_FRAMES`), and a box that is even-further wrong cannot
score below that floor. The row is therefore an honest, unchanged report of a defect the metric
was never built to distinguish by DEGREE, only by threshold -- which is also why this repair
adds a dedicated regression test (`test_persistent_per_frame_lag_correction_diverges_past_the_
dead_zone`) rather than relying on `BASELINE.md`'s own numeric table to carry this finding: a
future wave that fixes the `ObservationRing` timestamp mismatch will make THAT test start
failing, which is the correct signal to revisit this paragraph, while `ML=1` here would still
report nothing wrong on its own.

**The scenario is not winnable today, and tuning it further would hide that, not fix it.**
The mathematical-unidentifiability defect (five of seven apparatus defects have now been found
in this harness's OWN measurement code, never the tracker -- `TRACKING-V2-PLAN.md` §5b, this
file's §2 `crossing_similar` entry, and this section) is genuinely closed: the signal production
has is now the signal this harness supplies. What remains is a real defect in the code this
harness measures, discovered only because the instrument finally works. Per this task's own
acceptance criteria, that is reported here rather than papered over by shrinking `latency_frames`
until the correction's own bug stops mattering, which would make the scenario winnable "by
accident" in exactly the sense this repair was told not to allow.

## 3. What this wave found about the HARNESS itself, not the tracker

Per TRACKING-V2-PLAN §5b, measurement infrastructure fails toward false pessimism -- five
defects there each made the system look worse than it was, never better. This wave found
one structural fact in the same spirit, though it is a fact about the SHIPPED matcher, not
a harness bug: **a single-ground-truth-object ASSOCIATE scenario can never demonstrate an
id switch under `cost`.** With one live candidate and one target, and no hard IoU/cost
gate (`AssignGates.min_iou=0.0`, `max_cost=inf` by default), the pair is always
force-matched -- so `nonlinear` and `pan_occlusion`'s ASSOCIATE rows read as "recovered,
100%" regardless of how badly the predicted box drifted. This is exactly why `tiny_fast`
uses three competing landmarks rather than one, and exactly why coast ADE/FDE exists at
all: single-object drift needs a metric that reads the BOX, because the matcher's own
verdict ("matched", "recovered") is uninformative when there was never a second candidate
to lose the match TO.

`Track` being a mutable, per-stream-unique object handed out BY REFERENCE inside
`TrackedBox` (`track.py`'s own docstring) is a second finding, caught while building coast
ADE/FDE rather than while running a scenario: reading `outcome.boxes[i].track.source`
AFTER a full replay has finished reports every past frame's `source` as whatever it was on
the LAST frame that track appeared in, not the frame actually being inspected, because
every `TrackedBox` referencing that track aliases the SAME mutable object. `replay.
ReplayResult.coast_track_ids` is captured immediately after each `session.process()` call
specifically to avoid this -- see its own docstring. Nothing in production reads a `Track`
this way (the book itself is always read forward, never backward), so this was invisible
until a metric tried to do the same thing a REPLAY does.

## 4. Real-footage recording

`tools/trackeval/recording.py` -- a recorder (`record_stream`/`write_recording`) and an
offline replay (`replay_recording`) for a REAL detector stream, pixel-free by design (see
the module's own docstring for the three reasons, not one, this is the only mode rather
than an option). There is still no camera in this repo to record FROM, so this is
infrastructure proven by its own tests (`tests/trackeval/test_recording.py`) against a
synthetic "live" source built from `sequences.py` + the real `SyntheticDetector`, not
against footage that does not yet exist. `metrics.summarize_recording` is the
no-ground-truth counterpart of `compute` -- cost/structural facts only (detector passes,
track lifetimes, coasting fraction), explicitly not an accuracy number, because accuracy
needs a known true position real footage does not carry unless it is separately
hand-labelled.

---

## 5. Historical: the pre-C1 baseline (superseded)

**Commit:** `cdef30ae849d91b0e3d7489b0755cd97ee2da817` -- "feat(tracking-v2): the two
cores where a wrong answer is invisible" (adds `engines/pose_gmc.py` and
`assign.py`, explicitly **not yet wired into `session.py` or `registry.py`** per
that commit's own message -- `StreamTrackingSession`'s runtime behaviour at this
commit is therefore identical to wave T8's, the shipped pre-TRACKING-V2 service).
**Date recorded:** 2026-08-11. Kept for archaeological value: this is what waves C1-C5
were judged against while they shipped, before this wave's own §1 replaced it as the
scoreboard V1-V8 are judged against.

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

Reading it, scenario by scenario, is unchanged from the original write-up: `crossing`
showed `IDSW=4` (REVIEW finding B1, closed by wave C3's `engines/histogram.py` --
and reopened deliberately by this wave's own `crossing_similar`, see §2 above);
`occlusion` showed `recovery%=0` (REVIEW finding B3, closed by wave C4's dormant
gallery); `pan` showed `mostly_tracked=0` across all four objects (REVIEW finding
A1/A2, closed by wave C2's ego-motion compensation). `TRACKING-V2-PLAN.md` §5b has the
full C1-C5 delivery table; this section exists only so the numbers above have their
original context, not as a target any wave is judged against anymore.
