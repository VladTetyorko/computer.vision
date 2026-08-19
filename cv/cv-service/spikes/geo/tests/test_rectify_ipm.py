"""H0c instrument calibration for the IPM rectification path (VISUAL-GEO-V2-PLAN.md §9.8 defect
2, task requirement): a 45deg synthetic oblique render of a real mosaic (`sitl_render.py#
render_oblique`, the SAME construction `calibrate_instrument.py`'s "v_oblique_45deg" variant
uses) must, once run through `harvested/rectify.py#rectify()` (via
`rectify_rerank.compute_rectification`) and matched back against its own source mosaic
(`rectify_rerank.rerank_rectified`, `use_mosaic=True`, the real production matching path -- not a
hand-rolled RANSAC snippet), self-match with inlier ratio >= 0.6.

This is the IPM-path analogue of `calibrate_instrument.py`'s own "self-matching the same pixels
should be near-perfect for any matcher" instrument check -- it answers, independent of any real
domain gap, "does the rectify-then-match pipeline recognise a query built FROM the same indexed
pixels once IPM has undone the 45deg tilt?" A failure here would be a harness/rectification defect,
not a finding about real drone footage.

Matcher choice -- `lightglue_disk`, not `xfeat`: a manual 5-tile probe run while building this test
(kept as a finding, VISUAL-GEO-V2-PLAN.md §9.8) found `lightglue_disk` self-matches every probed
tile at inlier_ratio 0.96-0.99 (inlier_count 171-302) after IPM rectification -- unambiguous
evidence `rectify.py#rectify()` itself IS undoing the 45deg tilt correctly. `xfeat` on the SAME
rectified images stayed at inlier_ratio 0.11-0.19 despite the HIGHEST raw match_count of the three
matchers (296-351) -- high recall, low RANSAC-verified precision, a matcher-specific signature
under IPM's resampling near the horizon-side crop edge, not a rectification defect (`loftr` sat in
between, ~0.47-0.51). Using `xfeat` as this test's gate would make a real rectification success
look like a failure; `lightglue_disk` is the one matcher of the three whose self-match ratio is
unambiguous either way, so it is the instrument-calibration matcher here. §9.8's re-measurement
tables report all three matchers regardless -- this finding is why xfeat's IPM numbers there should
not be read as "IPM didn't help."

Gated (never fails) on:
  - `kyiv-pozniaky` being built on disk (`build_regions.py --region pozniaky`) -- a real region
    with a full 3x3 tile neighbourhood is needed for a realistic mosaic source; this is a runtime
    artefact (gitignored), not something this test can construct standalone.
  - the `lightglue_disk` matcher being buildable (cached torch.hub weights under `.model-cache/`).

Run: `cd cv-service && .venv/bin/python -m pytest spikes/geo/tests/test_rectify_ipm.py -q`
(after `source spikes/geo/env.sh && .venv/bin/python -m spikes.geo.build_regions --region pozniaky`
if the region isn't already built).
"""
from __future__ import annotations

import numpy as np
import pytest

from spikes.geo import matchers as matcher_registry
from spikes.geo import mosaic as mosaic_mod
from spikes.geo import rectify_rerank
from spikes.geo import sitl_render
from spikes.geo.calibrate_instrument import (
    FOV_DEGREES,
    OBLIQUE_ALTITUDE_M,
    OBLIQUE_HEADING_DEG,
    OBLIQUE_PITCH_DEGREES,
    REGION_ID,
    REGIONS_ROOT,
    SEED,
    _pick_tiles,
)
from spikes.geo.harvested.localize import Candidate, resolve_regions
from spikes.geo.harvested.verify import load_region_tile_image

MIN_SELF_MATCH_INLIER_RATIO = 0.6


def _region_or_skip():
    regions = resolve_regions(REGIONS_ROOT, REGION_ID)
    if not regions:
        pytest.skip(f"{REGION_ID} not built on disk -- run: .venv/bin/python -m spikes.geo.build_regions --region pozniaky")
    return regions[0]


def _matcher_or_skip(name: str):
    try:
        return matcher_registry.build(name)
    except Exception as exc:  # noqa: BLE001 -- any build failure (no cached weights, no torch) is a clean skip
        pytest.skip(f"{name} matcher unavailable: {type(exc).__name__}: {exc}")


def test_45deg_oblique_self_match_after_ipm_rectification():
    region = _region_or_skip()
    matcher_handle = _matcher_or_skip("lightglue_disk")
    region_dir = REGIONS_ROOT / REGION_ID

    picked = _pick_tiles(region, region_dir, n=1, seed=SEED)
    tile_meta, x, y, zoom = picked[0]
    mosaic = mosaic_mod.build_tile_mosaic(region_dir, x, y, zoom)
    assert mosaic is not None, f"mosaic unavailable for {tile_meta.tile_id} despite the neighbourhood pre-check in _pick_tiles"

    oblique = sitl_render.render_oblique(
        mosaic, tile_meta.lat, tile_meta.lon, OBLIQUE_HEADING_DEG, OBLIQUE_ALTITUDE_M,
        OBLIQUE_PITCH_DEGREES, FOV_DEGREES, 512,
    )
    assert isinstance(oblique, np.ndarray) and oblique.size > 0

    rect = rectify_rerank.compute_rectification(
        oblique, pitch_deg=OBLIQUE_PITCH_DEGREES, altitude_m=OBLIQUE_ALTITUDE_M,
        heading_deg=OBLIQUE_HEADING_DEG, tile_lat=tile_meta.lat, zoom=zoom, fov_degrees=FOV_DEGREES,
    )
    assert rect is not None, (
        "harvested/rectify.py#rectify() refused a clean, well-formed 45deg synthetic oblique -- "
        "min_depression_deg gate should never trip at a 45deg pitch (45deg depression from "
        "horizon, well above the 15deg floor)"
    )

    candidate = Candidate(tile_id=tile_meta.tile_id, lat=tile_meta.lat, lon=tile_meta.lon, similarity=1.0, region_id=REGION_ID)

    def load_tile(c: Candidate):
        return load_region_tile_image(region_dir, c.tile_id)

    match_fn = matcher_registry.MATCH_FNS["lightglue_disk"]
    result = rectify_rerank.rerank_rectified(
        matcher_handle, rect, [candidate], region_dir, load_tile, match_fn, top_k=1, use_mosaic=True,
    )

    assert result.winner is not None, f"no candidate produced enough matches to fit a pose (refusal={result.refusal})"
    assert result.winner.inlier_ratio is not None
    assert result.winner.inlier_ratio >= MIN_SELF_MATCH_INLIER_RATIO, (
        f"IPM self-match instrument calibration failed: inlier_ratio={result.winner.inlier_ratio:.3f} "
        f"< {MIN_SELF_MATCH_INLIER_RATIO} threshold (inlier_count={result.winner.inlier_count}, "
        f"match_count={result.winner.match_count}) -- IPM rectification is not undoing the 45deg "
        "tilt well enough for the matcher to recognise its own source pixels"
    )
