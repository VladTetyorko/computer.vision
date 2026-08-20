"""Standing regression, §4.4 Change 3 / VISUAL-GEO-V2-PLAN.md line 841:
`test_regression_alias_1213` -- must never go green by accident.

**Plan-defect note (found 2026-08-19, H4): the literal "14 recorded along-linear-feature alias
pairs" fixture the plan's own §4.4 prose names does not exist in this repo.** Those pairs came
from `btn-road-x`/`vas-road` linear-terrain regions + `LT_RUN`/`HT_RUN` raw results
(`spikes/geo/regression_1213.py`'s own `REGION_SOURCES`/`BEFORE_CALIBRATION`), which were on the
now-superseded H0 branch that "gitignored [results] and lost every raw artefact" (§1.3's own H0
plan-defect note, VISUAL-GEO-V2-PLAN.md line 176) -- they were never re-created. `regression_1213.py`
itself is kept as read-only spike reference only (it also targets an OLDER, pre-frozen wire shape
-- `GeoFrameRequest`/`encoder_factory=`, not this wave's `GeoControl`/constructor-injection
contract -- so it cannot be run as-is regardless).

The plan's OWN routing for this gap (line 1270: "Pozniaky/danger-region regressions are H4's
`test_regression_alias_1213`") directs H4's alias regression at `kyiv-pozniaky` instead: a real,
committed, 252-tile "danger region" (zero mapped highways, §12's own alias-terrain profile) built
by this wave's H0/H0c. This test samples `kyiv-pozniaky`'s own tiles as queries (`imagery of a
spot as its own drone would see it` stand-in -- there is no real drone footage of this region
either, same honesty note `calibrate.simulate_view_perturbation` already carries) and asserts the
production pipeline never confidently FIXes onto a WRONG neighboring tile -- the exact
along-linear-feature aliasing shape §4.2 G-d (rerank margin) is meant to catch. 14 tiles are
sampled (evenly spaced across the region), matching the plan's own cardinality.

Needs the real `geo` extra (torch/kornia) + a real encoder/matcher build (network on first run,
cached under `CV_GEO_MODEL_CACHE` after) -- skips cleanly, not fails, when either is unavailable.
"""

from __future__ import annotations

from pathlib import Path

import pytest

cv2 = pytest.importorskip("cv2")

from cv_service.geo import calibrate as calibrate_mod
from cv_service.geo import localize as loc
from cv_service.geo.index import haversine_m

from .conftest import KYIV_POZNIAKY_REGION_DIR, require_region_dir

N_SAMPLED_TILES = 14


def test_no_confident_fix_lands_on_a_wrong_neighboring_tile(real_encoder, real_matcher):
    require_region_dir(KYIV_POZNIAKY_REGION_DIR)
    matcher_handle, match_keypoints_fn = real_matcher

    regions = loc.resolve_regions(KYIV_POZNIAKY_REGION_DIR.parent, "kyiv-pozniaky")
    if not regions:
        pytest.skip(f"kyiv-pozniaky region at {KYIV_POZNIAKY_REGION_DIR} did not resolve as READY")
    region = regions[0]
    region_dir_by_id = {region.region_id: KYIV_POZNIAKY_REGION_DIR}

    tiles = region.index.tiles
    step = max(1, len(tiles) // N_SAMPLED_TILES)
    sampled = tiles[::step][:N_SAMPLED_TILES]
    assert sampled, "test setup: kyiv-pozniaky has no indexed tiles to sample"

    false_fixes = []
    for tile in sampled:
        tile_path = KYIV_POZNIAKY_REGION_DIR / "tiles" / (tile.tile_id.replace("/", "_") + ".jpg")
        image = cv2.imread(str(tile_path), cv2.IMREAD_COLOR)
        if image is None:
            continue  # a listed-but-unreadable tile file -- not this test's concern
        result = loc.localize_frame(
            image, encoder=real_encoder, regions=[region], region_dir_by_id=region_dir_by_id,
            matcher_handle=matcher_handle, match_keypoints_fn=match_keypoints_fn,
            params=loc.LocalizeParams(max_candidates=10, min_laplacian_variance=0.0, min_entropy=0.0),
        )
        if result.status != loc.STATUS_FIX:
            continue  # a correct refusal (the common/expected outcome on a danger region) is fine
        radius = calibrate_mod.holdout_correct_radius_m(tile.tile_id)
        error_m = haversine_m(result.latitude, result.longitude, tile.lat, tile.lon)
        if error_m > radius:
            false_fixes.append((tile.tile_id, result.tile_id, round(error_m, 1), round(radius, 1)))

    assert not false_fixes, (
        "along-linear-feature aliasing produced a confident FIX on the WRONG tile "
        f"(query_tile, fixed_tile, error_m, correct_radius_m): {false_fixes}"
    )
