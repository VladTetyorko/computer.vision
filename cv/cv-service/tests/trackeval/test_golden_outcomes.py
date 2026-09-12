"""Every scenario x mode replays to a BYTE-IDENTICAL per-frame outcome dump.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` P7/E15, wave W0's acceptance.
`test_baseline_consistency.py` guards fifteen SCORED numbers per row; this
file guards the raw material those numbers are computed from. The
difference matters: a refactor that moved an id from one box to another,
re-ordered the emitted boxes, elected a label one frame earlier, or changed
a coasted box in the third decimal can leave every `BASELINE.md` column
untouched. None of them survive this file.

**Fails on ANY difference, not on a score difference** -- the comparison is
string equality between the committed fixture and a fresh run, reported as
a unified diff so the first differing frame names itself.

**Regenerating is a deliberate act.** `python -m tools.trackeval
--golden-dir tests/trackeval/golden` rewrites every fixture. That command
belongs in a commit whose message explains which behaviour changed and why;
running it to make a red refactor go green is exactly the failure this gate
exists to prevent.

See `tools/trackeval/golden.py` for what is dumped and why `tracker_millis`/
`motion_millis` are not.
"""

from __future__ import annotations

import difflib
from pathlib import Path

import pytest

pytest.importorskip("numpy")  # builds real sequences -- see test_replay.py's own reasoning

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval import golden as golden_module
from tools.trackeval.__main__ import golden_text
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS

# tests/trackeval/ -> the fixtures sit beside this file, not under tools/:
# they are test data, and `tools/trackeval/` is the harness that produces
# them (same split `BASELINE.md` vs this directory already has).
_GOLDEN_DIR = Path(__file__).resolve().parent / "golden"

_REGENERATE = (
    "Regenerate with:\n"
    '  PYTHONPATH="$PWD" python -m tools.trackeval --golden-dir tests/trackeval/golden\n'
    "run from cv-service/ -- but ONLY when the behaviour change is intended and reviewed."
)


def _cases() -> list[tuple[str, str]]:
    return [(scenario, mode) for scenario in sorted(SCENARIOS) for mode in (MODE_ASSOCIATE, MODE_FOLLOW)]


def test_every_scenario_and_mode_has_a_committed_fixture() -> None:
    """The staleness guard: a scenario added to `sequences.SCENARIOS` with no
    fixture would otherwise silently skip the gate for that scenario.

    Cheap on purpose (a directory listing plus a set diff, no replay) so it
    fails fast, before the expensive comparison below starts.
    """
    expected = {golden_module.golden_path(_GOLDEN_DIR, scenario, mode).name for scenario, mode in _cases()}
    present = {path.name for path in _GOLDEN_DIR.glob("*.txt")}
    assert expected == present, (
        "tests/trackeval/golden/ does not cover exactly the harness's scenario x mode matrix.\n"
        f"  missing fixtures: {sorted(expected - present)}\n"
        f"  orphan fixtures:  {sorted(present - expected)}\n" + _REGENERATE
    )


@pytest.mark.parametrize(("scenario", "mode"), _cases())
def test_frame_outcomes_are_byte_identical_to_the_fixture(scenario: str, mode: str) -> None:
    """One scenario x mode, every frame, every field (see `golden.py`).

    Needs the real `cv` extra for the same reason `test_baseline_consistency`
    does: FOLLOW's default `lk` engine is `cv2`-backed, and without it the
    session degrades down a DIFFERENT ladder -- comparing that run against a
    fixture recorded with `lk` would be diffing two algorithms, not
    detecting drift in one.
    """
    pytest.importorskip("cv2", reason="FOLLOW's default 'lk' engine needs cv2 to match the fixtures")

    recorded = golden_module.read(_GOLDEN_DIR, scenario, mode)
    assert recorded is not None, (
        f"no golden fixture for {scenario} / {mode}.\n" + _REGENERATE
    )

    fresh = golden_text(scenario, mode, seed=DEFAULT_SEED)
    if fresh == recorded:
        return

    diff = "\n".join(
        difflib.unified_diff(
            recorded.splitlines(),
            fresh.splitlines(),
            fromfile=f"golden/{scenario}.{mode}.txt",
            tofile="fresh run",
            lineterm="",
            n=1,
        )
    )
    pytest.fail(
        f"{scenario} / {mode}: the per-frame outcome sequence changed.\n\n"
        f"{diff}\n\n"
        "This is a BEHAVIOURAL DELTA. CV-ORCHESTRATION wave W0 ships only if this file is "
        "green without regenerating the fixtures (plan P7/E15).\n" + _REGENERATE
    )
