"""Pluggable keypoint matcher backends for geometric re-rank (docs/plans/active/
VISUAL-GEO-V2-PLAN.md D4/§4.2, §9.9 amendment 1).

H0's bake-off (`spikes/geo/matchers.py`, five candidates) measured only two as production
candidates: **`xfeat`** (verlab/accelerated_features via `torch.hub`, Apache-2.0, CPU-native by
design -- the only bake-off matcher clearing §4.7's latency budget at any tested k, §9.4) is the
default; **`loftr`** (kornia `KF.LoFTR(pretrained="outdoor")`, the shipped baseline) stays
available as the slow, higher-recall option. `lightglue_aliked`/`lightglue_disk` and `eloftr` are
**not ported** -- see `cv/cv-service/MODULE.md`'s geo section for why (kornia-version breakage /
an unfetchable hand-download weight respectively); this is a deliberate H4 scope cut, not an
oversight.

`MatchKeypoints` correspondences are in each image's NATIVE pixel frame (each side's own resize
scale is undone before returning) -- what `cv_service/geo/pose.py#fit_homography_pose` fits its
MAGSAC homography on.

`cv2`/`numpy` are needed to import this module; `torch`/`kornia` are needed only to actually BUILD
a backend (`build()`) or run a match, both lazily imported inside the relevant function -- this
module (like `cv_service/geo/rerank.py`, which drives it) stays importable without the `geo`
extra, unlike the harvested `verify.py` this replaces (which imported `torch` at module scope).
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np

LOGGER = logging.getLogger("cv_service.geo.matchers")

LOFTR_RESIZE = 480  # longest side, px -- LoFTR's own recommended working resolution range
CONFIDENCE_THRESHOLD = 0.5  # kornia LoFTR's own suggested cutoff for "confident" matches
MAX_KEYPOINTS = 2048  # XFeat detector budget


@dataclass
class MatcherHandle:
    name: str
    backend: object
    build_ms: float


def _empty_keypoints() -> "np.ndarray":
    return np.zeros((0, 2), dtype=np.float64)


@dataclass(frozen=True)
class MatchKeypoints:
    """Confident correspondences between one query/tile pair, in each image's NATIVE pixel
    frame."""

    kp_query: "np.ndarray"  # [N, 2]
    kp_tile: "np.ndarray"  # [N, 2] (256px tile frame)
    confidence: "np.ndarray"  # [N]

    @property
    def count(self) -> int:
        return int(self.kp_query.shape[0])

    @property
    def mean_confidence(self) -> float:
        return float(self.confidence.mean()) if self.confidence.size else 0.0


_NO_MATCH: Optional[MatchKeypoints] = None


def _no_match() -> MatchKeypoints:
    global _NO_MATCH
    if _NO_MATCH is None:
        _NO_MATCH = MatchKeypoints(_empty_keypoints(), _empty_keypoints(), np.zeros(0))
    return _NO_MATCH


def _resize_longest_side(image_bgr: "np.ndarray", target: int) -> tuple["np.ndarray", float]:
    h, w = image_bgr.shape[:2]
    scale = target / max(h, w)
    if scale >= 1.0:
        return image_bgr, 1.0
    resized = cv2.resize(
        image_bgr, (int(round(w * scale)), int(round(h * scale))), interpolation=cv2.INTER_AREA
    )
    return resized, scale


class MatcherUnavailableError(RuntimeError):
    """`torch`/`kornia` isn't installed, or the backend failed to build (no cached weights + no
    internet on first run) -- mirrors `cv_service.geo.encoder.EncoderUnavailableError`'s contract:
    raised from `build()`, never a silent fallback, so the caller reports a normal failure instead
    of crash-looping."""


# --- xfeat (default, §9.9 amendment 1) -----------------------------------------------------------


def build_xfeat() -> MatcherHandle:
    import torch  # lazy: keeps this module importable without torch installed

    t0 = time.perf_counter()
    xfeat = torch.hub.load(
        "verlab/accelerated_features", "XFeat", pretrained=True, top_k=MAX_KEYPOINTS, trust_repo=True
    )
    return MatcherHandle("xfeat", xfeat, (time.perf_counter() - t0) * 1000.0)


def xfeat_match_keypoints(
    handle: MatcherHandle, query_image_bgr: "np.ndarray", tile_image_bgr: "np.ndarray"
) -> MatchKeypoints:
    import torch

    query, qscale = _resize_longest_side(query_image_bgr, LOFTR_RESIZE)
    tile, tscale = _resize_longest_side(tile_image_bgr, LOFTR_RESIZE)
    with torch.no_grad():
        mkpts0, mkpts1 = handle.backend.match_xfeat(query, tile, top_k=MAX_KEYPOINTS)
    if mkpts0 is None or len(mkpts0) == 0:
        return _no_match()
    kp_query = np.asarray(mkpts0, dtype=np.float64) / qscale
    kp_tile = np.asarray(mkpts1, dtype=np.float64) / tscale
    # XFeat's MNN matcher reports no per-match confidence; every returned pair already cleared
    # the mutual-nearest-neighbour + cosine-similarity test. §4.2's gates key on MAGSAC inlier
    # count/ratio, not this score.
    confidence = np.ones(kp_query.shape[0], dtype=np.float64)
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=confidence)


# --- loftr (available, slow high-recall option) ---------------------------------------------------


def build_loftr() -> MatcherHandle:
    import kornia.feature as KF  # lazy

    t0 = time.perf_counter()
    matcher = KF.LoFTR(pretrained="outdoor").eval()
    return MatcherHandle("loftr", matcher, (time.perf_counter() - t0) * 1000.0)


def prep_gray_tensor(image_bgr: "np.ndarray") -> tuple:
    """Grayscale + resize-to-`LOFTR_RESIZE` tensor prep, returning the applied scale factor
    (`resized_px = native_px * scale`) so keypoints can be mapped back to native pixels."""
    import torch

    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    scale = LOFTR_RESIZE / max(h, w)
    if scale < 1.0:
        gray = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    else:
        scale = 1.0
    tensor = torch.from_numpy(gray).float()[None, None] / 255.0
    return tensor, scale


def loftr_match_keypoints(
    handle: MatcherHandle, query_image_bgr: "np.ndarray", tile_image_bgr: "np.ndarray"
) -> MatchKeypoints:
    import torch

    query_tensor, query_scale = prep_gray_tensor(query_image_bgr)
    tile_tensor, tile_scale = prep_gray_tensor(tile_image_bgr)
    with torch.no_grad():
        out = handle.backend({"image0": query_tensor, "image1": tile_tensor})
    confidence = out["confidence"].cpu().numpy() if len(out["confidence"]) else np.zeros(0)
    if confidence.size == 0:
        return _no_match()
    keep = confidence >= CONFIDENCE_THRESHOLD
    kp_query = out["keypoints0"].cpu().numpy()[keep].astype(np.float64) / query_scale
    kp_tile = out["keypoints1"].cpu().numpy()[keep].astype(np.float64) / tile_scale
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=confidence[keep])


# --- registry --------------------------------------------------------------------------------

BUILDERS = {"xfeat": build_xfeat, "loftr": build_loftr}
MATCH_FNS = {"xfeat": xfeat_match_keypoints, "loftr": loftr_match_keypoints}


def build(name: str) -> MatcherHandle:
    """Factory used by `cv_service.geo.localize`/`GeolocationServicer`: builds the named backend.
    Raises `MatcherUnavailableError` (never returns `None`) so the caller decides how to report a
    failed build, mirroring `cv_service.geo.encoder.build_encoder`'s own contract. An unknown
    `name` (e.g. a stale `CV_GEO_MATCHER` naming a bake-off-only backend) raises `ValueError`
    directly -- `Settings.from_env`'s own `_parse_choice` already guards this at config-read time,
    so reaching here with an unknown name is a programming error, not an operator typo."""
    builder = BUILDERS.get(name)
    if builder is None:
        raise ValueError(f"unknown matcher {name!r}, choose from {sorted(BUILDERS)}")
    try:
        return builder()
    except ImportError as exc:
        raise MatcherUnavailableError(
            f"{name}: torch/kornia is not installed -- install the 'geo' extra "
            "(pip install -e '.[geo]' --extra-index-url https://download.pytorch.org/whl/cpu)"
        ) from exc
    except MatcherUnavailableError:
        raise
    except Exception as exc:  # noqa: BLE001 - torch.hub/kornia raise a mix of urllib/zip/import errors
        raise MatcherUnavailableError(f"{name}: failed to build ({exc!r})") from exc


def match_keypoints(
    name: str, handle: MatcherHandle, query_image_bgr: "np.ndarray", tile_image_bgr: "np.ndarray"
) -> MatchKeypoints:
    return MATCH_FNS[name](handle, query_image_bgr, tile_image_bgr)
