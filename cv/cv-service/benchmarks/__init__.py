"""Real-footage benchmark runner for `cv_service/tracking/`.

`docs/conclusions/TRACKING-BENCHMARKS.md` §5 scoped this package: a real,
license-clean dataset (MOT17) driving the REAL `StreamTrackingSession`
(`cv_service.tracking.session`), scored with an ignore-aware wrapper around
`tools/trackeval/metrics.py`. `tools/trackeval/` itself stays untouched --
its own synthetic scenarios (`sequences.py`) are ground truth BY
CONSTRUCTION and remain the fast, deterministic regression suite;
`benchmarks/` is a separate, slower, real-data-only sibling that answers a
question synthetic data structurally cannot: how the tracker behaves
against a real detector's real misses, false positives and localization
error.

**Two blockers this package exists to respect (see each module's own
docstring for the mechanism):**

1. **Circularity.** `tools/trackeval/replay.py`'s `SyntheticDetector` takes
   ground truth as its own input and returns it perturbed -- pointing that
   at a real dataset would score the same closed loop under a benchmark's
   name. `driver.py` feeds `Mot17Detection`s built ONLY from `det.txt`
   (real detector output); its `detect()` closure has no parameter of type
   `GroundTruthObject` anywhere in its signature, which makes the ground
   truth structurally unreachable from the session, not merely
   undocumented-but-possible.
2. **Ignore regions.** MOT17 `gt.txt`'s 7th column flags ~42-56% of rows
   (varies per sequence; ~45% pooled across all seven) as "do not score" --
   `tools/trackeval/metrics.py` has no concept of this at all.
   `ignore_matching.py` enforces MOT's rule on both sides: a flagged-0
   object never opens a scored existence window, and a real detection
   landing on one is dropped before it ever reaches
   `StreamTrackingSession.process()`, never merely down-weighted after the
   fact.

**Scope, stated once, not quietly assumed.** MOT17 ships annotations only
-- no images (`docs/conclusions/TRACKING-BENCHMARKS.md` §5's own stated
constraint, confirmed against the mirror at fetch time). This package can
therefore only exercise ASSOCIATE mode's `cost`/`bytetrack` engines
associating real detections across real frames. It CANNOT exercise FOLLOW
mode (`lk`/`ncc` need image patches to track), pixel-based motion
compensation (`flow`'s GMC), appearance evidence (`histogram`), or detector
throughput -- all of those need decoded pixels this dataset does not carry.
`driver.py` forces `track_motion_engine`/`track_appearance_engine`/
`track_roi_enabled` off structurally (not merely by convention) rather than
risk a pixel-consuming code path running against no real pixels; see that
module's docstring for exactly which knobs and why.

Module map::

    mot17.py            loader: seqinfo.ini / gt.txt / det.txt -> Mot17Sequence,
                         pixel -> normalized [0,1] coordinates, per-sequence fps timestamps
    ignore_matching.py  Blocker 2: ignore-flagged rows excluded from scoring AND from
                         the detection stream, both enforced here
    thresholds.py        Blocker "meaningless single threshold": per-detector confidence
                         defaults (DPM/FRCNN/SDP scores are NOT on the same scale)
    driver.py            Blocker 1: drives the REAL StreamTrackingSession with det.txt
                         detections only, sibling to tools/trackeval/replay.run_replay
    runner.py             CLI: one composition (sequence x detector x engine x level x ORU)
                         -> one CSV/JSON row, via `python -m benchmarks.runner`
    fetch_mot17.sh        reproducible re-download (data/ is already present, gitignored,
                         CC BY-NC-SA -- never commit it)

Run from `cv-service/` with the source tree on `PYTHONPATH` (matches
`tools/trackeval/__main__.py`'s own convention)::

    PYTHONPATH="$PWD" .venv/bin/python -m benchmarks.runner \\
        --sequence 09 --detector FRCNN --engine cost --level 1 --oru off
"""
