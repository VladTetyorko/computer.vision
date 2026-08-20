"""Shared fixtures for `cv_service.geo.*` tests (VISUAL-GEO-V2-PLAN.md H4).

Two tiers of test in this package:

- Pure-logic tests (`test_sequence.py`, `test_calibrate.py`, `test_index.py`, `test_pack.py`,
  `test_rerank.py`, `test_localize.py`) need only `numpy`/`cv2` (the `cv` extra, already required
  by the rest of this test suite) -- no network, no real encoder/matcher weights. `localize_frame`
  itself is exercised with an injected fake `Encoder`/matcher, same "explicit injection beats a
  real backend" seam `InferenceServicer.detector=` already uses.
- The two standing regressions (`test_regression_1213_alias.py`,
  `test_regression_1214_false_convergence.py`) need the REAL `geo` extra (torch/kornia, for a real
  encoder + xfeat matcher) plus network access on first run (to fetch `gmberton/eigenplaces` +
  `verlab/accelerated_features` hub weights, cached under `CV_GEO_MODEL_CACHE` after that) -- both
  skip cleanly, not fail, when torch/kornia aren't importable or a build attempt fails offline.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
from typing import Optional

import pytest

GEO_EXTRA_AVAILABLE = (
    importlib.util.find_spec("torch") is not None and importlib.util.find_spec("kornia") is not None
)

# `spikes/geo/regions/**` -- committed, real, already-built regions this wave's H0/H0c produced
# (VISUAL-GEO-V2-PLAN.md §5's own harvest requirement: "Every result committed"). Read-only
# fixtures for H4's tests; this package never writes into `spikes/**`.
_SPIKES_GEO_DIR = Path(__file__).resolve().parents[2] / "spikes" / "geo"
KYIV_MAIDAN_REGION_DIR = _SPIKES_GEO_DIR / "regions" / "kyiv-maidan"
KYIV_POZNIAKY_REGION_DIR = _SPIKES_GEO_DIR / "regions" / "kyiv-pozniaky"
MAIDAN_FRAMES_DIR = _SPIKES_GEO_DIR / "fixtures" / "maidan-video-frames"


@pytest.fixture(scope="session")
def geo_model_cache(tmp_path_factory) -> Path:
    """A scratch `CV_GEO_MODEL_CACHE` shared by every test in one session -- so a first real
    encoder/matcher build in this run downloads hub weights once, not once per test."""
    return tmp_path_factory.mktemp("geo-model-cache")


@pytest.fixture(scope="session")
def real_geo_settings(geo_model_cache: Path):
    from cv_service.config import Settings

    return Settings(geo_model_cache=geo_model_cache)


@pytest.fixture(scope="session")
def real_encoder(real_geo_settings):
    """The real default `Encoder` (`CV_GEO_ENCODER`, eigenplaces). Skips (not fails) when the
    `geo` extra is absent or the first-run weight fetch can't reach the network."""
    if not GEO_EXTRA_AVAILABLE:
        pytest.skip("torch/kornia not installed ('geo' extra absent)")
    from cv_service.grpc.servicers import _apply_geo_model_cache_dir
    from cv_service.geo.encoder import build_encoder, EncoderUnavailableError

    _apply_geo_model_cache_dir(real_geo_settings)
    try:
        return build_encoder(real_geo_settings.geo_encoder, device=real_geo_settings.geo_device)
    except (EncoderUnavailableError, OSError, RuntimeError) as exc:
        pytest.skip(f"geo encoder unavailable (offline, no hub cache?): {exc}")


@pytest.fixture(scope="session")
def real_matcher(real_geo_settings):
    """The real default matcher backend (`CV_GEO_MATCHER`, xfeat) + its `match_keypoints_fn`."""
    if not GEO_EXTRA_AVAILABLE:
        pytest.skip("torch/kornia not installed ('geo' extra absent)")
    from cv_service.grpc.servicers import _apply_geo_model_cache_dir
    from cv_service.geo import matchers as matchers_mod

    _apply_geo_model_cache_dir(real_geo_settings)
    try:
        handle = matchers_mod.build(real_geo_settings.geo_matcher)
    except matchers_mod.MatcherUnavailableError as exc:
        pytest.skip(f"geo matcher unavailable (offline, no hub cache?): {exc}")
    import functools

    return handle, functools.partial(matchers_mod.match_keypoints, real_geo_settings.geo_matcher)


def require_region_dir(region_dir: Path) -> None:
    if not (region_dir / "index.json").is_file():
        pytest.skip(f"fixture region not present/built: {region_dir}")


def require_maidan_frames() -> None:
    if not (MAIDAN_FRAMES_DIR / "manifest.jsonl").is_file():
        pytest.skip(f"fixture frames not present: {MAIDAN_FRAMES_DIR}")
