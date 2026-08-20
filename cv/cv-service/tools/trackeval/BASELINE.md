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
that scenario unwinnable by construction, not merely hard -- fixed by wiring the lag through
(`replay.py`, commit `66b5043`). That exposed a second, genuine `cv_service/tracking/` defect
(`ObservationRing` timestamping entries at arrival instead of capture, out of that repair's own
file scope to fix), fixed separately; see §2 below for the full two-defect account. With both
closed, `latency`/ASSOCIATE's own row below is the only one in this table that moved --
`latency`/FOLLOW and every other scenario are produced by the SAME `cv_service/` code every
prior wave measured against, still untouched by this repair.

**2026-08-14 O3 closed -- velocity plausibility.** `docs/plans/active/TRACKING-V3-PLAN.md`
§6b finding O3: every column in this table reads the MATCHED-frame timeline or the box's
distance from the truth, and none of them asks whether a track's own reported velocity is
physically sensible -- a track whose estimate diverged to `1e16` and a track that is merely
lost both scored `ML=1`, which is exactly how the instrument-repair defect above survived a
whole wave undetected. `metrics.py` gains `implausible_velocity_count` (table column
`implaus_n`) -- a DETECTOR, not a clamp, counting `(track, frame)` reports whose
`velocity_x`/`velocity_y` exceeds `MAX_PLAUSIBLE_VELOCITY_PER_SECOND` (5.0 frame-widths or
-heights/sec, derived in `metrics.py`'s own module-level comment from the fastest apparent
motion this project has ever measured or modelled -- `tiny_fast`'s own 0.6/sec synthetic pan
and `CV-RATE-BUDGET.md` §2's 1.5/sec "aggressive" 90 deg/s search yaw, both real numbers, not
guesses). `replay.py` gains `ReplayResult.track_velocities`, captured immediately after each
`session.process()` call the SAME way `coast_track_ids` is (and for the identical reason --
`Track` is mutable and handed out by reference, so reading `track.velocity_x` back out after
the whole replay finishes would report only the LAST frame's value for every frame a track
ever appeared in). **Every scenario x mode below reports `implaus_n=0`** -- the shipped
configuration is clean; the column exists to catch a FUTURE regression like the one above, not
because this run found one. Proven to fire on a synthetic diverging-velocity track by
`tests/trackeval/test_velocity_plausibility.py`. No product code under `cv_service/` touched;
every pre-existing column in the table below is unchanged (verified: the fresh run this task
regenerated it from reproduces all twenty-two prior columns exactly, `trk_ms_*` aside per this
file's own noise convention below).

**2026-08-15 -- ORU's bracket shape check enabled by default.** `CV_TRACK_REUPDATE_MAX_SHAPE_
LOG_RATIO` now defaults to `0.40` instead of `0` (`cv_service/config.py`, swept in
`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4d: -97 IDSW and +0.7pp recovery across 21 real
MOT17 pairs, the first ORU configuration that beats not running ORU at all). **One row below moves as
a direct, accepted consequence: `pan`/FOLLOW `implaus_n` 0 -> 8.** Everything else in that row --
IDSW, FM, MT/PT/ML, lifetime, coast ADE/FDE -- is byte-identical, so what changed is the reported
VELOCITY on eight coasted frames, not the tracked position. This is a DELIBERATE behaviour change
recorded here, not drift: the table is re-recorded because the tracker genuinely changed, which is
the one case this file's own guard exists to allow. Reverting is `CV_TRACK_REUPDATE_MAX_SHAPE_LOG_
RATIO=0`, and finding O5 (`TRACKING-V3-PLAN.md` §6b) tracks resolving the FOLLOW-side cost once
aerial footage makes FOLLOW measurable on real video at all.

**2026-08-20 -- TRACK-IDENTITY-PLAN wave L2, association hardening, measured. Table below
UNCHANGED -- all 30 rows.** `cv_service/config.py`'s cost weight/gate defaults were raised per
the plan, tuned against this exact table, and one of the three knobs was reverted on measured
evidence rather than shipped on the plan's own starting guess. Full trial account, the reverted
knob's root cause, and the re-measured L1 label-flip counter: §6 below.

Reproduce with (see `cv/cv-service/MODULE.md` for the full `PYTHONPATH` explanation):

```bash
cd cv/cv-service
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all
```

Every scenario uses `sequences.DEFAULT_SEED` (`20260811`) and the per-scenario detector-noise
defaults in `tools/trackeval/__main__.py`'s `DEFAULT_NOISE_BY_SCENARIO`. Both engines are the
server's own defaults: `cost` for ASSOCIATE, `lk` for FOLLOW (`CV_TRACK_ASSOCIATE_ENGINE`/
`CV_TRACK_FOLLOW_ENGINE`, `cv_service/config.py`).

## 1. The V0 table -- all fifteen scenarios

```
scenario         | mode                    | engine | frames | gt | IDSW | FM | MT | PT | ML | gaps | recov | recov% | life_mean | life_med | det/s | trk_ms_avg | trk_ms_p95 | coast_n | cADE  | cFDE  | cADE_px | cFDE_px | implaus_n
-----------------+-------------------------+--------+--------+----+------+----+----+----+----+------+-------+--------+-----------+----------+-------+------------+------------+---------+-------+-------+---------+---------+----------
clutter          | TRACKING_MODE_ASSOCIATE | cost   | 60     | 10 | 0    | 0  | 10 | 0  | 0  | 0    | 0     | n/a    | 55.8      | 60.0     | 10.00 | ~4         | ~5         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
clutter          | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 0.83  | ~1         | ~1         | 55      | 0.218 | 0.324 | 69.9    | 103.8   | 0        
crossing         | TRACKING_MODE_ASSOCIATE | cost   | 50     | 2  | 0    | 0  | 2  | 0  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
crossing         | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 0.80  | ~0.4       | ~1         | 48      | 0.194 | 0.228 | 62.1    | 72.9    | 0        
crossing_similar | TRACKING_MODE_ASSOCIATE | cost   | 50     | 2  | 0    | 2  | 2  | 0  | 0  | 2    | 2     | 100%   | 44.0      | 44.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
crossing_similar | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 50.0      | 50.0     | 0.80  | ~0.4       | ~1         | 48      | 0.357 | 0.752 | 95.9    | 196.8   | 0        
crowd_recall     | TRACKING_MODE_ASSOCIATE | cost   | 150    | 6  | 0    | 6  | 6  | 0  | 0  | 6    | 6     | 100%   | 60.0      | 60.0     | 10.00 | ~0.8       | ~2         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
crowd_recall     | TRACKING_MODE_FOLLOW    | lk     | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 101.0     | 101.0    | 1.47  | ~0.4       | ~1         | 98      | 0.016 | 0.022 | 4.9     | 6.8     | 0        
dropout          | TRACKING_MODE_ASSOCIATE | cost   | 50     | 1  | 0    | 4  | 1  | 0  | 0  | 4    | 4     | 100%   | 41.0      | 41.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
dropout          | TRACKING_MODE_FOLLOW    | lk     | 50     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 35.0      | 35.0     | 1.40  | ~0.7       | ~1         | 33      | 0.002 | 0.003 | 0.6     | 0.8     | 0        
latency          | TRACKING_MODE_ASSOCIATE | cost   | 60     | 1  | 0    | 4  | 0  | 1  | 0  | 4    | 4     | 100%   | 52.0      | 52.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
latency          | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 0  | 0  | 1  | 0    | 0     | n/a    | 48.0      | 48.0     | 1.17  | ~0.8       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
linear           | TRACKING_MODE_ASSOCIATE | cost   | 60     | 3  | 0    | 0  | 3  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 10.00 | ~1         | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
linear           | TRACKING_MODE_FOLLOW    | lk     | 60     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 60.0      | 60.0     | 0.50  | ~1         | ~1         | 57      | 0.002 | 0.002 | 0.6     | 0.6     | 0        
long_occlusion   | TRACKING_MODE_ASSOCIATE | cost   | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 60.0      | 60.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
long_occlusion   | TRACKING_MODE_FOLLOW    | lk     | 150    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 101.0     | 101.0    | 1.47  | ~0.4       | ~1         | 98      | 0.006 | 0.007 | 1.4     | 1.8     | 0        
nonlinear        | TRACKING_MODE_ASSOCIATE | cost   | 70     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 50.0      | 50.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
nonlinear        | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 70.0      | 70.0     | 0.71  | ~0.4       | ~1         | 66      | 0.029 | 0.045 | 9.1     | 14.3    | 0        
occlusion        | TRACKING_MODE_ASSOCIATE | cost   | 110    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 70.0      | 70.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
occlusion        | TRACKING_MODE_FOLLOW    | lk     | 110    | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 110.0     | 110.0    | 0.55  | ~0.6       | ~1         | 106     | 0.008 | 0.009 | 1.9     | 2.1     | 0        
pan              | TRACKING_MODE_ASSOCIATE | cost   | 70     | 4  | 0    | 0  | 4  | 0  | 0  | 0    | 0     | n/a    | 24.8      | 26.0     | 10.00 | ~0.3       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
pan              | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 70.0      | 70.0     | 0.86  | ~0.3       | ~1         | 13      | 0.011 | 0.040 | 3.3     | 12.8    | 8        
pan_occlusion    | TRACKING_MODE_ASSOCIATE | cost   | 90     | 1  | 0    | 1  | 1  | 0  | 0  | 1    | 1     | 100%   | 65.0      | 65.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
pan_occlusion    | TRACKING_MODE_FOLLOW    | lk     | 90     | 1  | 0    | 0  | 0  | 1  | 0  | 0    | 0     | n/a    | 87.0      | 87.0     | 0.78  | ~0.3       | ~1         | 84      | 0.260 | 0.328 | 83.3    | 104.8   | 0        
pan_step         | TRACKING_MODE_ASSOCIATE | cost   | 90     | 2  | 0    | 2  | 2  | 0  | 0  | 2    | 2     | 100%   | 60.0      | 60.0     | 10.00 | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
pan_step         | TRACKING_MODE_FOLLOW    | lk     | 90     | 1  | 0    | 3  | 1  | 0  | 0  | 3    | 3     | 100%   | 90.0      | 90.0     | 0.56  | ~1         | ~1         | 87      | 0.013 | 0.016 | 3.8     | 4.9     | 0        
small_target     | TRACKING_MODE_ASSOCIATE | cost   | 80     | 1  | 0    | 2  | 1  | 0  | 0  | 2    | 2     | 100%   | 78.0      | 78.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
small_target     | TRACKING_MODE_FOLLOW    | lk     | 80     | 1  | 0    | 0  | 1  | 0  | 0  | 0    | 0     | n/a    | 80.0      | 80.0     | 0.50  | ~1         | ~1         | 78      | 0.001 | 0.001 | 0.3     | 0.3     | 0        
tiny_fast        | TRACKING_MODE_ASSOCIATE | cost   | 70     | 3  | 2    | 0  | 3  | 0  | 0  | 0    | 0     | n/a    | 19.0      | 19.0     | 10.00 | ~0         | ~0         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
tiny_fast        | TRACKING_MODE_FOLLOW    | lk     | 70     | 1  | 0    | 0  | 0  | 0  | 1  | 0    | 0     | n/a    | 62.0      | 62.0     | 1.00  | ~0.1       | ~1         | 0       | n/a   | n/a   | n/a     | n/a     | 0        
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

**`latency` -- two apparatus defects hid the tracker's real behaviour here, both now
closed; ASSOCIATE recovers what it used to lose outright, FOLLOW does not.**
`latency_frames=8` (800 ms at this harness's 10 fps) makes the detector describe a position
the object left 8 frames ago. The first defect: `replay.py` injected that lag but never told
`StreamTrackingSession.process()` about it (`detection_lag_millis` defaulted to `0`,
"unknown"), so wave V6's late-detection back-correction (`docs/plans/active/
TRACKING-V3-PLAN.md` §4.5, commit `1665969`) had no way to fire at all -- fixed by wiring
`replay.py` to compute and pass the lag it already knows (commit `66b5043`), mirroring how
pull mode obtains the SAME signal for real: `cv_service/pull/clock.py`'s `CaptureClock.
capture_time` reports `capture_skew_millis = now_wall - captured_at`, threaded into
`session.process()` by `grpc/servicers.py`'s `_handle_request`. Once wired, the correction
fired -- and diverged: `ObservationRing.record()` (`cv_service/tracking/history.py`)
timestamped every entry with the frame it was PROCESSED at, not the instant its content was
actually true, so a `reupdate()` bracket built from one of those mistimed entries divided a
REAL position delta by an ARTIFICIALLY SMALL elapsed time, inflating the reconstructed
velocity into a positive feedback loop that ran the reported box to `1e14`-`1e32` within a
few dozen frames. That second defect is now also fixed (`ObservationRing`/`reupdate.py`/
`track.py` thread the observation's own CAPTURE instant instead of its arrival instant, plus
a floor on `reupdate()`'s own elapsed-time denominator) -- a separate task from this one, per
this file's own delegation model, so only its effect on this scenario's numbers is recorded
here.

With both defects closed:

- **ASSOCIATE: `ML=1` -> `PT=1, ML=0`.** `FM=4`, all four recovered (`recov=4`, 100%),
  `IDSW=0` throughout. `life_mean`/`life_med` stay `52.0` and `det/s` stays `10.00` -- the
  SAME continuously-confirmed track as before the timestamp fix, now reporting a corrected
  box good enough to clear the coverage-match gate instead of a systematically-stale one that
  never could.
- **FOLLOW: unchanged -- still `ML=1`, `life_mean=48.0`, `det/s=1.17`, `coast_n=0`.**
  Late-detection back-correction does not move this row at all, even though `_follow_verify`
  (`cv_service/tracking/session.py`) calls the SAME `_late_corrected_box` `_run_cost_associate`
  does.

**Diagnosis: ASSOCIATE and FOLLOW re-anchor through different paths, and only one of them
lets the correction stick.** `_run_cost_associate` writes `corrected_box` straight into the
`Observation` it books (`session.py`) -- the SAME value every downstream consumer then sees:
the ring, the next frame's constant-velocity prediction, the reported box, all at once,
because ASSOCIATE has no separate visual state to keep in sync. `_follow_verify` calls
`engine.init(frame(), boxes[index])` -- telling the LK optical-flow engine which PATCH of the
actual frame to visually track from now on -- *before* `_late_corrected_box` ever runs;
the correction it computes afterward is applied only to `locked_observation`'s box, for
booking/reporting, and is never fed back into the engine's own visual anchor. FOLLOW spends
most of its frames coasting on that anchor between rare verify passes (`det/s=1.17` here
against ASSOCIATE's `10.00` -- a detector pass roughly every 8-9 frames, not every one), so a
verify pass that re-anchors LK on the stale box commits the visual tracker to following
whatever is actually AT that patch -- not the object, `latency_frames` frames of real motion
away -- for every coasted frame afterward, regardless of what that same frame's corrected,
reported number says. The correction can fix the SCORE on the one frame it runs; it cannot
fix the PATCH the engine is physically looking at, which is what every frame between verify
passes actually depends on. This is established directly from `_follow_verify`'s own call
order, not inferred from the unchanged row alone; nothing here has instrumented what LK
actually locks onto pixel-for-pixel, so treat the mechanism as diagnosed, the exact drift
magnitude as not separately measured.

**The idealised reading versus the honest one.** The ASSOCIATE row above is the EXACT-lag
case (`detection_lag_jitter_millis=0.0`) -- this harness knows precisely how many frames late
the detector is, because it is the config that shifted the ground truth; a real pull-mode
worker's `capture_skew_millis` estimate never quite does. `--lag-jitter-millis 15` (`replay.
DETECTION_LAG_JITTER_TYPICAL_MILLIS`, sourced from `pull/clock.py`'s own module docstring --
M0 measured `capture_skew_millis` at "+/-15 ms typical spread over 10 minutes, worst spike
~80 ms") is the operator-relevant reading: ASSOCIATE degrades gracefully to `FM=5 | PT=1 |
ML=0 | gaps=5 | recov=5 | 100%` -- one extra fragmentation, still fully recovered, not a
collapse back to `ML=1`. That is the difference between "works in the lab" (perfect lag
knowledge) and "works on a link" (a noisy estimate of it), worth stating outright: the
exact-lag row is an idealisation, and the jittered figure -- one extra fragmentation, not a
regression to total loss -- is the one to trust when reasoning about a real deployment.

```bash
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode FOLLOW
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario latency --mode ASSOCIATE --lag-jitter-millis 15
```

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

---

## 6. TRACK-IDENTITY-PLAN wave L2 (2026-08-20) -- association hardening, measured

`docs/plans/active/TRACK-IDENTITY-PLAN.md`'s L2, after L1's track-level label election
landed (commit `899fb838`, "Track-level label election" above). L1 gave `assign.py`'s
`Candidate.label` a stable, hysteresis-gated operand instead of the raw, noisy per-frame
one; L2's own job is to actually SPEND that stability -- raise the cost weights/gates that
were left at their permissive "nothing is ever forbidden" C3 defaults
(`docs/plans/active/TRACK-IDENTITY-RESEARCH.md` §1 item 3) now that a label disagreement or
a near-zero-overlap pairing means something more than frame-to-frame classifier noise.

**Method.** Every trial below runs the full unmodified `tools/trackeval --all` (all 15
scenarios x 2 modes, `sequences.DEFAULT_SEED`) and diffs the 22 non-timing columns against
§1's own table -- the same comparison `tests/trackeval/test_baseline_consistency.py` makes
automatically against whatever is currently in this file. Each of the three knobs was first
tested ALONE (the other two held at their PRE-L2 default via an env-var override, so
`CV_TRACK_COST_WEIGHT_LABEL`/`_GATE_MIN_IOU`/`_GATE_MAX_COST` isolate one variable's effect
at a time), then the surviving knobs were re-measured together as the final combination.

**Trial 1 -- `DEFAULT_TRACK_COST_WEIGHT_LABEL` 0.0 -> 0.3, isolated.** Command:
`CV_TRACK_COST_GATE_MIN_IOU=0.0 CV_TRACK_COST_GATE_MAX_COST=inf PYTHONPATH="$PWD"
.venv/bin/python -m tools.trackeval --all`. **Result: all 30 rows byte-identical to §1.**
The harness has no scenario where two live candidates compete for one detection under
disagreeing ELECTED labels (every scenario runs with `label_noise_probability=0.0` by
default, per `metrics.py`'s own module docstring on why `label_flip_count` reads `0`
everywhere in §1's table) -- so the penalty is provably inert on every pinned scenario
while still tightening the real multi-object, conflicting-label case it targets. **Kept.**

**Trial 2 -- `DEFAULT_TRACK_COST_GATE_MIN_IOU` 0.0 -> 0.05 (the plan's own proposed value),
isolated.** Command: `CV_TRACK_COST_WEIGHT_LABEL=0.0 CV_TRACK_COST_GATE_MAX_COST=inf
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all`. **Result: 4 of 15 scenarios'
ASSOCIATE row regress:**

```
scenario   | column     | before (S1) | after (0.05)
-----------+------------+-------------+--------------
latency    | IDSW       | 0           | 16
latency    | FM         | 4           | 16
latency    | recov/gaps | 4/4 (100%)  | 0/16 (0%)
latency    | life_mean  | 52.0        | 2.9
nonlinear  | IDSW       | 0           | 1
nonlinear  | recov/gaps | 1/1 (100%)  | 0/1 (0%)
occlusion  | IDSW       | 0           | 1
occlusion  | recov/gaps | 1/1 (100%)  | 0/1 (0%)
tiny_fast  | IDSW       | 2           | 4
```

`latency`'s collapse is the sharpest: a track that used to survive its own 8-frame detection
lag as one continuous identity (recovering all 4 of its own fragmentation gaps) instead
fragments and re-spawns 16 times over 60 frames, never once recovering its own id.

**Trial 2 continued -- tuning sweep.** Per the plan's own "tune the three values, document
every trial" clause, the same 4-row check was re-run at `min_iou = 0.04, 0.03, 0.02, 0.01,
0.005, 0.0001` (six more full-suite runs, `CV_TRACK_COST_GATE_MIN_IOU=<value>` with the other
two knobs still held at their pre-L2 default). **Every value produced the IDENTICAL four
regressed rows above, digit for digit, all the way down to `0.0001`.** That flatness is the
finding: it means the TRUE geometric IoU between the predicted candidate and the
reappearing/lagging/reversing target on the frame each row's `IDSW`/`recov` figure turns on
is exactly `0.0`, not merely small -- so no strictly-positive gate, however gentle, can avoid
excluding it. `nonlinear`'s own §2 write-up above already names the mechanism directly:
"constant-velocity extrapolation ran the WRONG WAY the entire time: the object reverses
heading the instant it goes behind the bar" -- a predicted box built from the wrong-direction
extrapolation can land with zero overlap on the true box, and `occlusion`/`latency`/
`tiny_fast` each have their own equivalent wide-displacement moment (a full occlusion gap, an
8-frame systematic lag, and a ~19px/frame pan respectively). This is precisely the failure
the PRE-L2 comment on this line already named as the reason `min_iou` defaulted to `0.0` in
the first place ("a strict `min_iou` would forbid exactly the wide-displacement case
ego-motion compensation exists to recover") -- Trial 2 turns that reasoning from an argument
into a measurement. **Reverted to `0.0`** (`config.py`'s own comment on this default has the
full account) -- a measured retreat per the plan's own escape hatch, not a partial win shipped
on the strength of the plan's starting guess.

**Trial 3 -- `DEFAULT_TRACK_COST_GATE_MAX_COST` `inf` -> 1.5, isolated.** Command:
`CV_TRACK_COST_WEIGHT_LABEL=0.0 CV_TRACK_COST_GATE_MIN_IOU=0.0 PYTHONPATH="$PWD"
.venv/bin/python -m tools.trackeval --all`. **Result: all 30 rows byte-identical to §1.**
No pinned scenario's total cost (`1.0*(1-iou) + 0.5*appearance + 0.3*label`, capped at `1.8`
under the full L2 weight set) ever climbs anywhere near `1.5` -- the gate is a forward guard
against a worst-of-everything pairing, not a demonstrated fix for one on this suite. **Kept**
(plan's own text: "1.5 starting point ... tune against trackeval" -- measurement found no
reason to move it).

**Final combination -- `weight_label=0.3`, `gate_min_iou=0.0` (reverted), `gate_max_cost=1.5`
(`config.py`'s actual shipped defaults after this wave).** Command: bare `PYTHONPATH="$PWD"
.venv/bin/python -m tools.trackeval --all`, no env overrides. **Result: all 30 rows
byte-identical to §1 -- §1's own table needed no edit, which is why it still reads exactly as
wave V0 left it.** `tests/trackeval/test_baseline_consistency.py` confirms this automatically
on every `pytest` run (it diffs a fresh harness run against whatever this file currently
documents), and passed unchanged through this whole wave.

**L1's flip counter, re-measured under the final L2 config.** The acceptance bar requires the
noisy-label case L1 measured at 7 flips to stay improved. Re-running
`tests/trackeval/test_replay.py::test_label_election_suppresses_most_of_a_maximal_noisy_label_
sequence`'s own scenario (`linear`, seed 0, detector seed 2, `label_noise_probability=1.0`
over a 3-label pool) directly against the shipped L2 config: **`label_flip_count=7`,
`idsw=0`, `fragmentations=0` -- identical to L1's own measurement, unchanged by L2.** Still a
~16x reduction against the ~118 raw-label estimate, comfortably past the plan's "order of
magnitude" bar (`test_replay.py`'s own `raw_flip_estimate // 8` threshold, `~14`).

**Full cv-service pytest, before and after this wave.** `PYTHONPATH="$PWD" .venv/bin/python
-m pytest -q`, foreground, both runs on the same machine: **1227 passed / 1 skipped before
this wave's `config.py` edits, 1227 passed / 1 skipped after** -- a net delta of zero, as
expected for a wave that changes three numeric defaults and comments only, no new code paths
and no new tests (the acceptance bar here is trackeval non-regression, not new unit coverage;
`tests/tracking/test_params.py`'s existing env-override/garbage-fallback tests for these three
knobs pin behaviour around the DEFAULT value, not its literal number, so none needed editing).

**Ride-along per the plan.** `config.py`'s `DEFAULT_TRACK_APPEARANCE_ENGINE` comment
(previously ~114-120, TRACK-IDENTITY-RESEARCH.md §2 finding D-D) claimed `cost` is NOT the
`CV_TRACK_ASSOCIATE_ENGINE` default; `DEFAULT_TRACK_ASSOCIATE_ENGINE = "cost"` (`config.py`
line 73) and this file's own §1 reproduction note ("Both engines are the server's own
defaults: `cost` for ASSOCIATE") already said otherwise -- fixed to state the correct
direction, no behaviour change.
