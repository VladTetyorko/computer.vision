"""The tracking-accuracy evaluation harness.

`docs/plans/active/TRACKING-V2-PLAN.md` §4 (wave C0), closing
`docs/conclusions/TRACKING-REVIEW.md` finding E ("nothing in the repo
measures the thing being complained about"). Every number
`cv-service/MODULE.md`'s "Tracking engine" section reported before this
package existed is a COST number (ms, CPU%, duty ratio); this package is the
first ACCURACY scoreboard, and waves C1-C5 are judged against
`tools/trackeval/BASELINE.md`.

===================  ====================================================
`sequences.py`        synthetic ground-truth clips, known by construction
                       (no camera exists yet -- H1 is unbought)
`replay.py`            feeds a sequence through the REAL
                       `cv_service.tracking.session.StreamTrackingSession`,
                       via a seeded synthetic detector -- never a
                       reimplementation
`metrics.py`           greedy IoU matching -> IDSW / FM / MT-PT-ML /
                       recovery rate / track lifetime / cost
`__main__.py`          `python -m tools.trackeval --scenario X --mode Y`,
                       or `--all` for the full matrix
===================  ====================================================

**Needs the `cv` extra to RENDER and to EXERCISE real engines** --
`sequences.py` builds frames with `numpy` and `replay.py`'s
`TrackerRegistry` constructs engines that need `cv2`/`ultralytics`/`lap`.
It does NOT hard-require any of them at import time (every module here is
pure stdlib at module scope, mirroring `cv_service/tracking/`'s own
discipline -- see each module's docstring for where the lazy import lives),
and a missing engine degrades exactly the way it would in production:
`TrackerRegistry.probe()` drops it from the roster and
`StreamTrackingSession` falls back down the `FOLLOW -> ASSOCIATE -> OFF`
ladder. The harness reports whatever the box it runs on actually serves --
the same honesty the rest of this service already has.
"""
