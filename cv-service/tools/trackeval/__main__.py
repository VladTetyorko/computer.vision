"""CLI: `python -m tools.trackeval --scenario NAME --mode MODE [--all]`.

`docs/plans/active/TRACKING-V2-PLAN.md` §4 (wave C0). Deliberately thin --
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
import sys
from typing import Optional
from typing import Sequence as TypingSequence

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval import metrics as metrics_module
from tools.trackeval import replay as replay_module
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS

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
DEFAULT_NOISE_BY_SCENARIO: dict[str, replay_module.DetectorNoiseConfig] = {
    "dropout": replay_module.DetectorNoiseConfig(seed=_DROPOUT_NOISE_SEED, dropout_probability=_DROPOUT_PROBABILITY),
    "crossing": replay_module.DetectorNoiseConfig(
        seed=_CROSSING_NOISE_SEED, position_jitter=_CROSSING_POSITION_JITTER
    ),
}


def _noise_for(scenario: str) -> replay_module.DetectorNoiseConfig:
    return DEFAULT_NOISE_BY_SCENARIO.get(scenario, replay_module.DetectorNoiseConfig())


def _run_one(scenario: str, mode: str, *, engine_id: str = "", seed: int) -> metrics_module.Metrics:
    sequence = SCENARIOS[scenario](seed)
    result = replay_module.run_replay(sequence, mode=mode, engine_id=engine_id, detector_config=_noise_for(scenario))
    return metrics_module.compute(result)


def _parse_args(argv: TypingSequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="python -m tools.trackeval")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default=None, help="one scenario to run")
    parser.add_argument("--mode", choices=sorted(_MODE_ALIASES), default="ASSOCIATE")
    parser.add_argument("--engine", default="", help="engine_id override; '' = server default for the mode")
    # Shares `sequences.DEFAULT_SEED` so a bare invocation with no `--seed`
    # reproduces `BASELINE.md` exactly.
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--all", action="store_true", help="every scenario x every mode, as a matrix")
    args = parser.parse_args(argv)
    if not args.all and args.scenario is None:
        parser.error("either --scenario NAME or --all is required")
    return args


def main(argv: Optional[TypingSequence[str]] = None) -> None:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    if args.all:
        rows = [
            _run_one(scenario, mode, seed=args.seed)
            for scenario in sorted(SCENARIOS)
            for mode in (MODE_ASSOCIATE, MODE_FOLLOW)
        ]
    else:
        rows = [_run_one(args.scenario, _MODE_ALIASES[args.mode], engine_id=args.engine, seed=args.seed)]
    print(metrics_module.render_table(rows))


if __name__ == "__main__":
    main()
