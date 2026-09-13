"""`tools.trackeval.__main__` -- the CLI.

Needs `numpy` to render sequences (see `test_sequences.py`). `--trace`
(CV-ORCHESTRATION wave W5b) does not itself need `numpy` (`test_trace.py`'s
own module docstring), but stays gated by this file's module-level skip too,
for the same reason every other CLI test here lives in one file rather than
being split by dependency.
"""

from __future__ import annotations

from pathlib import Path

import pytest

pytest.importorskip("numpy")

from tools.trackeval.__main__ import main

_TRACE_FIXTURE = Path(__file__).parent / "fixtures" / "min-trace.json"


def test_single_scenario_prints_a_table(capsys: pytest.CaptureFixture[str]) -> None:
    main(["--scenario", "linear", "--mode", "ASSOCIATE"])
    out = capsys.readouterr().out
    assert "linear" in out
    assert "IDSW" in out


def test_all_prints_every_scenario_and_mode(capsys: pytest.CaptureFixture[str]) -> None:
    main(["--all"])
    out = capsys.readouterr().out
    for name in (
        "linear",
        "occlusion",
        "crossing",
        "pan",
        "dropout",
        # TRACKING-V3-PLAN wave V0 additions.
        "nonlinear",
        "tiny_fast",
        "pan_occlusion",
        "latency",
        "crossing_similar",
    ):
        assert name in out
    assert "ASSOCIATE" in out
    assert "FOLLOW" in out
    assert "coast_n" in out


def test_requires_scenario_or_all() -> None:
    with pytest.raises(SystemExit):
        main([])


def test_unknown_scenario_is_rejected() -> None:
    with pytest.raises(SystemExit):
        main(["--scenario", "not-a-real-scenario"])


def test_trace_replays_a_saved_cv_trace_and_prints_a_structural_summary(capsys: pytest.CaptureFixture[str]) -> None:
    main(["--trace", str(_TRACE_FIXTURE)])
    out = capsys.readouterr().out
    assert "min-trace" in out
    assert "total_frames:" in out
    assert "distinct_track_ids:" in out
    # No ground-truth accuracy table for a trace replay (no GateReason/IDSW
    # column exists for it, same as recording.py's real-footage path).
    assert "IDSW" not in out
