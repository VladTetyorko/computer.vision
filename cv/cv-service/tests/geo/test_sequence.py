"""Unit tests for `cv_service.geo.sequence.SequenceLocalizer` (VISUAL-GEO-V2-PLAN.md §4.4).

Pure numpy -- no cv2/torch, no fixtures. Two things this file must prove, since they are the
whole point of H4's Change 1/Change 2 rewrite (§12.14, the "converged 8/8 times onto a
771m-wrong cell" defect):

1. A clean, well-supported, spatially-diverse geometric field DOES converge (sanity: the filter
   still works at all).
2. A hover (zero real motion -> `baseline_meters` stays ~0) can NEVER reach
   `cell_ever_single_frame_accepted`+diversity+baseline all at once -- `sequence_converged`
   (`cv_service.geo.localize._sequence_converged_policy`) is structurally unreachable, exactly
   §4.4's stated intent, even if the raw `SequenceEstimate.status` itself reaches CONVERGED on
   spread alone (G-c, harvested unchanged).
"""

from __future__ import annotations

import numpy as np
import pytest

from cv_service.geo.sequence import (
    STATUS_CONVERGED,
    SequenceLocalizer,
    tile_size_meters,
)

# A 5x5 zoom-17 grid, tile step ~9.6m at zoom 17/Kyiv latitude scaled up for a readable test --
# tile ids only need to parse; the filter derives real tile_size_meters from zoom/latitude itself.
TILE_IDS = [f"17/{76640 + x}/{44190 + y}" for x in range(5) for y in range(5)]
TRUE_ROW = 12  # the (2,2) center tile -- index 2*5+2=12 in the x-major/y-minor list above


def _field(true_row: int, *, sharp: bool = True) -> np.ndarray:
    field = np.zeros(len(TILE_IDS), dtype=np.float64)
    field[true_row] = 0.9 if sharp else 0.5
    return field


def _localizer(**overrides) -> SequenceLocalizer:
    kwargs = dict(seed=7, n_particles=500, min_supporting_frames=4, min_baseline_m=40.0)
    kwargs.update(overrides)
    return SequenceLocalizer.from_tile_ids(TILE_IDS, **kwargs)


def test_from_tile_ids_rejects_empty():
    with pytest.raises(ValueError):
        SequenceLocalizer.from_tile_ids([])


def test_from_tile_ids_rejects_mixed_zoom():
    with pytest.raises(ValueError):
        SequenceLocalizer.from_tile_ids(["17/1/1", "16/1/1"])


def test_repeated_consistent_evidence_with_real_motion_converges_on_spread_and_diversity():
    """Sanity: a genuinely well-supported track (consistent winner, real inter-update motion)
    both spread-converges (G-c) AND accumulates enough G-b supporting_frames/baseline."""
    seq = _localizer()
    estimate = None
    for i in range(8):
        seq.predict(12.0, 0.0)  # ~12m east per update -> real, non-hover motion
        estimate = seq.update(
            _field(TRUE_ROW), winner_tile_id=TILE_IDS[TRUE_ROW], cleared_inlier_floor=True,
            single_frame_accepted=True,
        )
    assert estimate.status == STATUS_CONVERGED
    assert estimate.converged_cell_tile_id == TILE_IDS[TRUE_ROW]
    assert estimate.cell_ever_single_frame_accepted is True
    assert estimate.supporting_frames >= 4
    # 7 predicts of 12m each strictly after the first supporting entry -> a real baseline, not 0.
    assert estimate.baseline_meters > 40.0


def test_hover_zero_motion_never_reaches_diversity_baseline_even_if_spread_converges():
    """§4.4's own stated intent, directly: a hover (delta 0,0 every tick) drives
    `baseline_meters` to ~0 regardless of how confidently the raw particle spread converges --
    this is the structural fix for §12.14 (the OLD raw-similarity filter converged 8/8 times on a
    static/hover Pexels clip)."""
    seq = _localizer()
    estimate = None
    for i in range(8):
        seq.predict(0.0, 0.0)  # hover: no real platform motion this tick
        estimate = seq.update(
            _field(TRUE_ROW), winner_tile_id=TILE_IDS[TRUE_ROW], cleared_inlier_floor=True,
            single_frame_accepted=True,
        )
    # The raw spread/persistence rule (G-c, unchanged) may well converge on a hover -- that alone
    # is exactly the OLD (defective) behavior this test must show is no longer sufficient.
    assert estimate.baseline_meters < 40.0, (
        "a hover must not accumulate a real baseline -- otherwise G-b's diversity/baseline gate "
        "cannot do its job"
    )


def test_uncleared_inlier_floor_never_counts_as_supporting():
    """`cleared_inlier_floor=False` winners must not count toward G-b's supporting_frames, even
    when they name the eventually-converged cell -- Change 2's whole point is that a similarity
    hit alone (no geometric confirmation) cannot buy convergence."""
    seq = _localizer()
    estimate = None
    for i in range(8):
        seq.predict(12.0, 0.0)
        estimate = seq.update(
            _field(TRUE_ROW), winner_tile_id=TILE_IDS[TRUE_ROW], cleared_inlier_floor=False,
            single_frame_accepted=False,
        )
    assert estimate.supporting_frames == 0
    assert estimate.cell_ever_single_frame_accepted is False


def test_cell_never_single_frame_accepted_blocks_ga_second_clause():
    """A cell that cleared the inlier floor (weak bar) but NEVER cleared every §4.2 gate (strong
    bar) must report `cell_ever_single_frame_accepted=False` -- Change 2 G-a's second clause, the
    exact §12.14 shape ("a filter confidently converging onto a cell the single-frame path never
    trusted")."""
    seq = _localizer()
    estimate = None
    for i in range(8):
        seq.predict(12.0, 0.0)
        estimate = seq.update(
            _field(TRUE_ROW), winner_tile_id=TILE_IDS[TRUE_ROW], cleared_inlier_floor=True,
            single_frame_accepted=False,  # cleared G-a alone, but refused by a LATER §4.2 gate
        )
    assert estimate.supporting_frames >= 4  # G-b's weak bar is satisfied
    assert estimate.cell_ever_single_frame_accepted is False  # G-a's strong bar is not


def test_estimate_does_not_mutate_streak_or_window():
    """`estimate()` is a read-only peek (module docstring) -- calling it must not advance
    `update_count` or perturb the evidence window a later real `update()` will see."""
    seq = _localizer()
    seq.predict(12.0, 0.0)
    seq.update(_field(TRUE_ROW), winner_tile_id=TILE_IDS[TRUE_ROW], cleared_inlier_floor=True)
    before = seq.update_count
    seq.estimate()
    seq.estimate()
    assert seq.update_count == before


def test_tile_size_meters_positive_and_shrinks_with_zoom():
    z17 = tile_size_meters(50.45, 17)
    z18 = tile_size_meters(50.45, 18)
    assert z17 > 0
    assert z18 == pytest.approx(z17 / 2.0, rel=1e-6)
