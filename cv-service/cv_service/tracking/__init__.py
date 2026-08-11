"""Per-stream tracking engine: the detect-then-track duty cycle.

`docs/TRACKING-PLAN.md` §3/§5.A + `docs/TRACKING-ORCHESTRATION.md` §2.1. One
charter per module, and the charter is the review criterion:

===================  ====================================================
`params.py`          `TrackingParams` + `resolve()` -- the ONLY place a
                     `<=0` wire sentinel becomes a number
`scheduler.py`       `DutyCycleScheduler.decide(now, state) -> Decision`
                     -- pure policy, §3.1's table and nothing else
`track.py`           `Track` + `TrackBook` -- the
                     TENTATIVE->CONFIRMED->COASTING->LOST->expired machine
`lock.py`            `LockArbiter` -- `lock_seq` monotonicity + target
                     selection
`registry.py`        `TrackerRegistry` -- two rosters of factories, startup
                     constructibility probe, roster logged once at INFO
`engines/base.py`    the TWO protocols (`Associator`,
                     `SingleObjectTracker`) and their vocabulary types
`engines/*.py`       pixels/geometry only
`session.py`         `StreamTrackingSession` -- composition only
===================  ====================================================

Two invariants hold across every module here:

* **No module in this package imports `cv_pb2`.** `cv_service/grpc/servicers.py`
  remains the sole wire-translation point, exactly as `cv_service/inference/`
  and `cv_service/training/` already work. Enum-valued fields cross this
  boundary as plain strings matching the proto enum *value names* one-for-one
  (`"TRACK_STATE_CONFIRMED"`, ...), the same convention
  `cv_service/inference/detector.py`'s `ENCODING_JPEG`/`ENCODING_BGR24`
  already uses -- so the servicer maps them with
  `cv_pb2.TrackState.Value(name)` and needs no lookup table.
* **Everything except `engines/{bytetrack,lk,ncc}.py` is pure stdlib** -- no
  `cv2`, no `numpy`, no `ultralytics` at module scope. The scheduler, the
  lifecycle machine, the lock arbiter and the session are therefore unit
  testable against a fake clock with no frames, no OpenCV and no gRPC.
  `engines/base.py` is in `engines/` but is stdlib-only too (its `np.ndarray`
  annotations are `TYPE_CHECKING`-only), which is what lets `track.py` and
  `session.py` share its `Box`/`Observation` vocabulary.
"""
