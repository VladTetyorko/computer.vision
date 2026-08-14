"""`benchmarks.thresholds` -- per-detector confidence defaults + filtering."""

from __future__ import annotations

import pytest

from benchmarks.mot17 import DETECTORS, DetRow
from benchmarks.thresholds import apply_threshold, default_threshold


def _det_row(confidence: float) -> DetRow:
    return DetRow(frame=1, x=0.0, y=0.0, width=10.0, height=10.0, confidence=confidence)


def test_every_known_detector_has_a_documented_default() -> None:
    for detector in DETECTORS:
        # Must not raise, and must return a real float.
        assert isinstance(default_threshold(detector), float)


def test_default_threshold_unknown_detector_raises() -> None:
    with pytest.raises(ValueError, match="unknown MOT17 detector"):
        default_threshold("NOT-A-REAL-DETECTOR")


def test_dpm_and_probability_detectors_get_different_defaults() -> None:
    # DPM's raw SVM margin and FRCNN/SDP's calibrated [0,1] score are not on
    # the same scale -- a single shared default would be meaningless (see
    # thresholds.py's own docstring).
    assert default_threshold("DPM") != default_threshold("FRCNN")


def test_apply_threshold_keeps_only_at_or_above() -> None:
    rows = [_det_row(0.1), _det_row(0.5), _det_row(0.9)]
    kept = apply_threshold(rows, 0.5)
    assert [row.confidence for row in kept] == [0.5, 0.9]


def test_apply_threshold_zero_drops_only_negative_scores() -> None:
    # `>= threshold`: at threshold 0.0 (DPM's own default -- see
    # thresholds.py), a negative DPM margin is dropped, zero and positive
    # ones are kept.
    rows = [_det_row(-0.5), _det_row(0.0), _det_row(3.4)]
    assert apply_threshold(rows, 0.0) == [rows[1], rows[2]]


def test_apply_threshold_very_low_keeps_everything() -> None:
    rows = [_det_row(-0.5), _det_row(0.0), _det_row(3.4)]
    assert apply_threshold(rows, -999.0) == rows
