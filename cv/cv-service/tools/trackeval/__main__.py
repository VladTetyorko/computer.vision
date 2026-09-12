"""CLI: `python -m tools.trackeval --scenario NAME --mode MODE [--all]`.

`docs/plans/done/TRACKING-V2-PLAN.md` §4 (wave C0). Deliberately thin --
argument parsing and printing only. `sequences.py` builds the data,
`replay.py` runs the real session, `metrics.py` scores it; nothing here
reimplements any of the three.

Run from `cv-service/` with the source tree on `PYTHONPATH` (the worktree
venv is symlinked, so `PYTHONPATH` is what makes imports resolve to THIS
checkout rather than another one):

    PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario occlusion --mode ASSOCIATE
    PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --all
"""

from __future__ import annotations

import argparse
import dataclasses
import sys
from pathlib import Path
from typing import Optional
from typing import Sequence as TypingSequence

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval import golden as golden_module
from tools.trackeval import metrics as metrics_module
from tools.trackeval import replay as replay_module
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS, SMALL_TARGET_RELIABLE_SIZE

_MODE_ALIASES: dict[str, str] = {"ASSOCIATE": MODE_ASSOCIATE, "FOLLOW": MODE_FOLLOW}

# Per-scenario detector noise. Every scenario is otherwise a PERFECT
# detector (`DetectorNoiseConfig()`), isolating the one failure mode its
# name describes.
#
# `dropout` is the deliberate exception (`sequences.py`'s module docstring,
# scenario `dropout`): its ground truth is always visible, so the miss has
# to come from the detector configured here, not from the scene.
_DROPOUT_NOISE_SEED = 20260811
_DROPOUT_PROBABILITY = 0.35
#
# `crossing` needs a SMALL amount of realistic detector jitter to actually
# show the id-swap it is named for. Measured while tuning this baseline: a
# PERFECT detector (jitter=0.0) lets `bytetrack`'s own Kalman motion model
# correctly disambiguate even a full head-on, 94%-IoU crossing every time --
# real id swaps come from the SAME measurement noise a real `YoloDetector`
# would have, not from geometry alone. `0.02` (2% of frame width, ~6px on
# these 320px-wide frames) is the smallest jitter that reliably produced a
# switch across five different noise seeds while tuning; it is still an
# order of magnitude smaller than a box's own width.
_CROSSING_NOISE_SEED = 0
_CROSSING_POSITION_JITTER = 0.02
_SMALL_TARGET_NOISE_SEED = 90210
#
# TRACKING-V3-PLAN wave V0 additions.
#
# `latency` needs no position jitter or dropout -- its own failure mode is
# the systematic lag `latency_frames` injects (see `sequences.py`'s module
# comment on this scenario). Measured while tuning this baseline: 8 frames
# at this scenario's own speed (`LATENCY_TRAVEL_END`-`LATENCY_TRAVEL_START`
# over `LATENCY_FRAME_COUNT`-1) is a ~0.11 position bias, comfortably larger
# than half the object's own `OBJECT_WIDTH_FRACTION` -- enough to cost
# coverage every single frame rather than only occasionally.
_LATENCY_FRAMES = 8
# `crossing_similar` needs the SAME small, realistic jitter `crossing`'s own
# baseline does (`_CROSSING_POSITION_JITTER`) -- a perfect detector lets pure
# geometry resolve even a symmetric crossing (see `crossing`'s own comment
# above), and this scenario's whole point is that colour can no longer help
# once geometry alone is ambiguous. A different seed so its own draw is
# independent of `crossing`'s.
_CROSSING_SIMILAR_NOISE_SEED = 1
#
# Instrument-repair (2026-08): `--lag-jitter-millis` re-runs `latency` with
# `replay.DetectorNoiseConfig.detection_lag_jitter_millis` set, for the
# companion reading `BASELINE.md`'s `latency` writeup discusses alongside
# the exact/idealised default -- a fixed seed so that reading is itself
# reproducible, independent of `SyntheticDetector`'s own `seed` (which
# `latency`'s config never uses -- no dropout/position-jitter/false-positive
# on this scenario).
_LATENCY_JITTER_SEED = 20260814

DEFAULT_NOISE_BY_SCENARIO: dict[str, replay_module.DetectorNoiseConfig] = {
    "dropout": replay_module.DetectorNoiseConfig(seed=_DROPOUT_NOISE_SEED, dropout_probability=_DROPOUT_PROBABILITY),
    "crossing": replay_module.DetectorNoiseConfig(
        seed=_CROSSING_NOISE_SEED, position_jitter=_CROSSING_POSITION_JITTER
    ),
    # The only scenario that opts into the apparent-size recall model: its
    # object is far below `reliable_size`, so a full-frame pass misses it most
    # of the time and a crop around a prediction does not.
    "small_target": replay_module.DetectorNoiseConfig(
        seed=_SMALL_TARGET_NOISE_SEED,
        reliable_size=SMALL_TARGET_RELIABLE_SIZE,
    ),
    "latency": replay_module.DetectorNoiseConfig(latency_frames=_LATENCY_FRAMES),
    "crossing_similar": replay_module.DetectorNoiseConfig(
        seed=_CROSSING_SIMILAR_NOISE_SEED, position_jitter=_CROSSING_POSITION_JITTER
    ),
}


def _noise_for(
    scenario: str,
    *,
    confidence_floor: float = 0.0,
    detect_threshold: float = 0.0,
    lag_jitter_millis: float = 0.0,
) -> replay_module.DetectorNoiseConfig:
    """This scenario's noise, with the confidence-split knobs and the
    `latency` lag-jitter knob folded on.

    All three default to 0 (disabled), so a bare invocation reproduces
    `BASELINE.md` exactly -- these exist to be A/B-ed explicitly, never to
    drift into the default scoreboard. `lag_jitter_millis` only has any
    effect on a scenario whose own `DetectorNoiseConfig.latency_frames` is
    already positive (`_detection_lag_millis_for`'s own guard) -- passing it
    for any other scenario is a harmless no-op, not a second knob to gate.
    """
    base = DEFAULT_NOISE_BY_SCENARIO.get(scenario, replay_module.DetectorNoiseConfig())
    if confidence_floor <= 0.0 and detect_threshold <= 0.0 and lag_jitter_millis <= 0.0:
        return base
    replacements: dict[str, object] = {}
    if confidence_floor > 0.0 or detect_threshold > 0.0:
        replacements["confidence_floor"] = confidence_floor
        replacements["detect_threshold"] = detect_threshold
    if lag_jitter_millis > 0.0:
        replacements["detection_lag_jitter_millis"] = lag_jitter_millis
        replacements["lag_jitter_seed"] = _LATENCY_JITTER_SEED
    return dataclasses.replace(base, **replacements)


def _run_one(
    scenario: str,
    mode: str,
    *,
    engine_id: str = "",
    seed: int,
    confidence_floor: float = 0.0,
    detect_threshold: float = 0.0,
    lag_jitter_millis: float = 0.0,
) -> metrics_module.Metrics:
    sequence = SCENARIOS[scenario](seed)
    result = replay_module.run_replay(
        sequence,
        mode=mode,
        engine_id=engine_id,
        detector_config=_noise_for(
            scenario,
            confidence_floor=confidence_floor,
            detect_threshold=detect_threshold,
            lag_jitter_millis=lag_jitter_millis,
        ),
    )
    return metrics_module.compute(result)


def golden_text(scenario: str, mode: str, *, seed: int = DEFAULT_SEED) -> str:
    """This scenario x mode's per-frame `FrameOutcome` dump (CV-ORCHESTRATION
    wave W0).

    Deliberately shares `_noise_for`/`SCENARIOS` with `_run_one` above rather
    than re-declaring the run: a golden dump recorded against a DIFFERENT
    detector configuration than the scoreboard's would gate the refactor on
    a run nobody else ever reproduces. Only the `--all` defaults are
    supported for the same reason -- the A/B knobs exist to be swept, never
    to be frozen.
    """
    sequence = SCENARIOS[scenario](seed)
    recorder = golden_module.GoldenRecorder()
    replay_module.run_replay(
        sequence,
        mode=mode,
        detector_config=_noise_for(scenario),
        observer=recorder,
    )
    return recorder.text()


def _parse_args(argv: TypingSequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python -m tools.trackeval")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default=None, help="one scenario to run")
    parser.add_argument("--mode", choices=sorted(_MODE_ALIASES), default="ASSOCIATE")
    parser.add_argument("--engine", default="", help="engine_id override; '' = server default for the mode")
    # Shares `sequences.DEFAULT_SEED` so a bare invocation with no `--seed`
    # reproduces `BASELINE.md` exactly.
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--all", action="store_true", help="every scenario x every mode, as a matrix")
    parser.add_argument(
        "--confidence-floor",
        type=float,
        default=0.0,
        help=(
            "model falling detector confidence for small/occluded targets: confidence "
            "interpolates from the scenario's own value down to this at zero apparent size. "
            "0 = flat confidence (the BASELINE.md behaviour). Needs a scenario with "
            "reliable_size set to have any effect."
        ),
    )
    parser.add_argument(
        "--detect-threshold",
        type=float,
        default=0.0,
        help=(
            "the threshold the DETECTOR runs at -- weaker boxes are never emitted. "
            "A/B the confidence split with this: 0.4 is where the operator threshold used "
            "to land, 0.15 is CV_DETECT_FLOOR. 0 = emit everything."
        ),
    )
    parser.add_argument(
        "--lag-jitter-millis",
        type=float,
        default=0.0,
        help=(
            "spread (+/-, uniform) around latency's own exact injected "
            "detection_lag_millis, modelling a real pull-mode capture_skew_millis "
            "estimate's own measurement error instead of perfect lag knowledge. "
            "0 (default) = exact/idealised. replay.DETECTION_LAG_JITTER_TYPICAL_MILLIS "
            "(15.0) is the value sourced from pull/clock.py's own M0 measurement; see "
            "BASELINE.md's latency writeup for the reading it produces. Only affects a "
            "scenario whose own latency_frames is already positive (today, latency)."
        ),
    )
    parser.add_argument(
        "--golden-dir",
        default="",
        help=(
            "regenerate the per-frame FrameOutcome golden dumps into this directory "
            "(every scenario x mode, --all defaults) instead of printing the scoreboard. "
            "The committed fixtures live in tests/trackeval/golden/; regenerate them ONLY "
            "when a behaviour change is intended and reviewed -- they are wave W0's "
            "zero-delta gate (docs/plans/active/CV-ORCHESTRATION-PLAN.md P7/E15)."
        ),
    )
    args = parser.parse_args(argv)
    if not args.all and args.scenario is None and not args.golden_dir:
        parser.error("either --scenario NAME, --all or --golden-dir is required")
    return args


def main(argv: Optional[TypingSequence[str]] = None) -> None:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    if args.golden_dir:
        directory = Path(args.golden_dir)
        for scenario in sorted(SCENARIOS):
            for mode in (MODE_ASSOCIATE, MODE_FOLLOW):
                path = golden_module.write(
                    directory, scenario, mode, golden_text(scenario, mode, seed=args.seed)
                )
                print(path)
        return
    if args.all:
        rows = [
            _run_one(
                scenario,
                mode,
                seed=args.seed,
                confidence_floor=args.confidence_floor,
                detect_threshold=args.detect_threshold,
                lag_jitter_millis=args.lag_jitter_millis,
            )
            for scenario in sorted(SCENARIOS)
            for mode in (MODE_ASSOCIATE, MODE_FOLLOW)
        ]
    else:
        rows = [
            _run_one(
                args.scenario,
                _MODE_ALIASES[args.mode],
                engine_id=args.engine,
                seed=args.seed,
                confidence_floor=args.confidence_floor,
                detect_threshold=args.detect_threshold,
                lag_jitter_millis=args.lag_jitter_millis,
            )
        ]
    print(metrics_module.render_table(rows))


if __name__ == "__main__":
    main()
