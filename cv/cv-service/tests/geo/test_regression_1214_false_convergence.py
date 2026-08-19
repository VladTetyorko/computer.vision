"""Standing regression, §4.4 Change 3 / VISUAL-GEO-V2-PLAN.md line 843:
`test_regression_pexels_false_convergence` -- must never go green by accident.

The measured defect this guards against (§4.4's own account, `00-existing-state.md` §2, §12.14):
replaying the 12-frame Maidan Pexels clip (a STATIC/HOVER camera -- `manifest.jsonl`'s own 12
entries all share one identical lat/lon) through the OLD raw-embedding-similarity sequence filter
converged at update 7 and STAYED converged on `KNOWN_WRONG_CELL` (771-777m from the camera's true
position) on every one of 8/12 measured updates
(`spikes/geo/results/false_convergence_gate.json`'s own `control_raw_similarity` summary:
`n_converged_on_known_wrong_cell: 8` out of `n_converged: 8` -- 100%), even though the
single-frame path correctly refused every single frame.

§4.4 Change 1 (the likelihood field is the re-ranked GEOMETRIC score, not raw similarity) + Change
2 (G-a per-cell calibration + G-b evidence diversity/baseline, `cv_service.geo.sequence`'s own
`_windowed_evidence`) are the structural fix under test here, exercised through the REAL
production `localize_frame` entry point end to end (not `SequenceLocalizer` in isolation --
`tests/geo/test_sequence.py` already covers that with synthetic fields) against the real,
committed `kyiv-maidan` region + the real 12-frame fixture.

A camera that never moves feeds `motion_delta_e_m=motion_delta_n_m=0.0` every frame (the honest
"no telemetry" degradation `cv_service.geo.localize.telemetry_motion_delta` already produces for a
hover/absent-telemetry stream) -- exactly the fixture's own real condition, no synthetic motion
injected.

Needs the real `geo` extra (torch/kornia) + a real encoder/matcher build (network on first run,
cached under `CV_GEO_MODEL_CACHE` after) -- skips cleanly, not fails, when either is unavailable.
"""

from __future__ import annotations

import json

import pytest

cv2 = pytest.importorskip("cv2")

from cv_service.geo import localize as loc
from cv_service.geo.sequence import STATUS_CONVERGED, SequenceLocalizer

from .conftest import KYIV_MAIDAN_REGION_DIR, MAIDAN_FRAMES_DIR, require_maidan_frames, require_region_dir

KNOWN_WRONG_CELL = "17/76646/44193"


def _load_frames() -> list:
    manifest = [json.loads(line) for line in (MAIDAN_FRAMES_DIR / "manifest.jsonl").read_text().splitlines() if line.strip()]
    frames = []
    for entry in manifest:
        image = cv2.imread(str(MAIDAN_FRAMES_DIR / entry["path"]), cv2.IMREAD_COLOR)
        if image is not None:
            frames.append((entry, image))
    return frames


def test_sequence_never_confidently_converges_on_the_known_wrong_cell(real_encoder, real_matcher):
    require_region_dir(KYIV_MAIDAN_REGION_DIR)
    require_maidan_frames()
    matcher_handle, match_keypoints_fn = real_matcher

    regions = loc.resolve_regions(KYIV_MAIDAN_REGION_DIR.parent, "kyiv-maidan")
    if not regions:
        pytest.skip(f"kyiv-maidan region at {KYIV_MAIDAN_REGION_DIR} did not resolve as READY")
    region = regions[0]
    region_dir_by_id = {region.region_id: KYIV_MAIDAN_REGION_DIR}

    frames = _load_frames()
    if not frames:
        pytest.skip(f"no readable frames under {MAIDAN_FRAMES_DIR}")

    sequence = SequenceLocalizer.from_tile_ids(
        [t.tile_id for t in region.index.tiles], seed=1,
    )
    params = loc.LocalizeParams(max_candidates=10, min_laplacian_variance=0.0, min_entropy=0.0)

    confirmed_wrong_cell_updates = []
    any_converged_on_true_cell = False
    for entry, image in frames:
        result = loc.localize_frame(
            image, encoder=real_encoder, regions=[region], region_dir_by_id=region_dir_by_id,
            matcher_handle=matcher_handle, match_keypoints_fn=match_keypoints_fn,
            sequence=sequence, motion_delta_e_m=0.0, motion_delta_n_m=0.0, params=params,
        )
        estimate = sequence.estimate()  # read-only peek at the state `localize_frame` just advanced
        if result.evidence.sequence_converged:
            assert estimate.status == STATUS_CONVERGED
            if estimate.converged_cell_tile_id == KNOWN_WRONG_CELL:
                confirmed_wrong_cell_updates.append(entry["path"])
            else:
                any_converged_on_true_cell = True

    assert not confirmed_wrong_cell_updates, (
        f"sequence_converged=true landed on the known 771m-wrong cell {KNOWN_WRONG_CELL} on "
        f"frames {confirmed_wrong_cell_updates} -- the exact §12.14 false-convergence shape"
    )
    # Not asserted (silence is an explicit pass per §4.4 Change 3's own wording) -- recorded only
    # for a human reading test output, since a correct convergence would be "a pass and a
    # headline": whether this fixture also converges truthfully this run.
    if any_converged_on_true_cell:
        print("this run additionally converged confidently on a cell other than the known-wrong one")
