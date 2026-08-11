"""`tools.trackeval.__main__` -- the CLI.

Needs `numpy` to render sequences (see `test_sequences.py`).
"""

from __future__ import annotations

import pytest

pytest.importorskip("numpy")

from tools.trackeval.__main__ import main


def test_single_scenario_prints_a_table(capsys: pytest.CaptureFixture[str]) -> None:
    main(["--scenario", "linear", "--mode", "ASSOCIATE"])
    out = capsys.readouterr().out
    assert "linear" in out
    assert "IDSW" in out


def test_all_prints_every_scenario_and_mode(capsys: pytest.CaptureFixture[str]) -> None:
    main(["--all"])
    out = capsys.readouterr().out
    for name in ("linear", "occlusion", "crossing", "pan", "dropout"):
        assert name in out
    assert "ASSOCIATE" in out
    assert "FOLLOW" in out


def test_requires_scenario_or_all() -> None:
    with pytest.raises(SystemExit):
        main([])


def test_unknown_scenario_is_rejected() -> None:
    with pytest.raises(SystemExit):
        main(["--scenario", "not-a-real-scenario"])
