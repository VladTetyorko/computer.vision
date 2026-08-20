"""cv-service/spikes/geo/matchers.py

H0 matcher bake-off backends (VISUAL-GEO-V2-PLAN.md D4/§4.2). Every `*_match_keypoints` function
matches the injection-seam contract `spikes.geo.harvested.verify.loftr_match_keypoints` already
established (`(matcher, query_image_bgr, tile_image_bgr) -> MatchKeypoints`, native-pixel-frame
correspondences) so the SAME `verify()`/`condition_query()` telemetry-conditioning machinery the
production module already carries can drive every candidate without a fork per matcher --
`rerank.py` is the only new orchestration layer this module needs.

Candidates (VISUAL-GEO-V2-PLAN.md D4, never SuperPoint -- Magic Leap non-commercial-research-only):
  - xfeat            verlab/accelerated_features, torch.hub (Apache-2.0), native MNN matcher.
  - lightglue_aliked  kornia KF.ALIKED + KF.LightGlueMatcher('aliked') (Apache-2.0 both).
  - lightglue_disk    kornia KF.DISK + KF.LightGlueMatcher('disk') (Apache-2.0 both).
  - loftr             kornia KF.LoFTR(pretrained="outdoor") -- the shipped baseline, kept keypoint-
                       returning (spikes.geo.harvested.verify.loftr_match_keypoints, reused directly).
  - eloftr            official ZJU EfficientLoFTR (github.com/zju3dv/EfficientLoFTR, Apache-2.0) --
                       NOT the HuggingFace `transformers` community port bakeoff_matchers.py already
                       measured slower than kornia-LoFTR itself (contra its own literature); build
                       raises RuntimeError with a clear reason if the official repo cannot be
                       fetched/imported in this environment, so a caller can record "not run: <reason>"
                       instead of crashing the whole bake-off (VISUAL-GEO-V2-PLAN.md §5 H0 instruction).

All backends load their weights through torch.hub / kornia's own fetch, both of which respect
TORCH_HOME -- `spikes/geo/env.sh` points that at the gitignored `.model-cache/` (symlinked to the
already-populated `~/.cache/torch/hub` so nothing here re-downloads what a prior spike already
fetched).
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
import torch

from spikes.geo.harvested.verify import LOFTR_RESIZE, MatchKeypoints, _empty_keypoints, _no_match

LOGGER = logging.getLogger("spikes.geo.matchers")

CONFIDENCE_THRESHOLD = 0.5  # kornia LoFTR's own suggested cutoff, reused for every backend so
                            # "confident match" means the same thing across the whole bake-off
MAX_KEYPOINTS = 2048        # ALIKED/DISK/XFeat detector budget -- kept identical across the three
                            # sparse candidates so the bake-off measures the MATCHER, not a keypoint
                            # budget difference


@dataclass
class MatcherHandle:
    name: str
    backend: object
    build_ms: float


# --------------------------------------------------------------------------- shared pixel prep


def _resize_longest_side(image_bgr: np.ndarray, target: int) -> tuple[np.ndarray, float]:
    h, w = image_bgr.shape[:2]
    scale = target / max(h, w)
    if scale >= 1.0:
        return image_bgr, 1.0
    resized = cv2.resize(image_bgr, (int(round(w * scale)), int(round(h * scale))), interpolation=cv2.INTER_AREA)
    return resized, scale


def _to_rgb_tensor(image_bgr: np.ndarray) -> torch.Tensor:
    rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    return torch.from_numpy(rgb).float().permute(2, 0, 1)[None] / 255.0


def _to_gray_tensor(image_bgr: np.ndarray) -> torch.Tensor:
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    return torch.from_numpy(gray).float()[None, None] / 255.0


# --------------------------------------------------------------------------- xfeat


def build_xfeat() -> MatcherHandle:
    t0 = time.perf_counter()
    xfeat = torch.hub.load(
        "verlab/accelerated_features", "XFeat", pretrained=True, top_k=MAX_KEYPOINTS, trust_repo=True
    )
    return MatcherHandle("xfeat", xfeat, (time.perf_counter() - t0) * 1000.0)


def xfeat_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    query, qscale = _resize_longest_side(query_image_bgr, LOFTR_RESIZE)
    tile, tscale = _resize_longest_side(tile_image_bgr, LOFTR_RESIZE)
    with torch.no_grad():
        mkpts0, mkpts1 = handle.backend.match_xfeat(query, tile, top_k=MAX_KEYPOINTS)
    if mkpts0 is None or len(mkpts0) == 0:
        return _no_match()
    kp_query = np.asarray(mkpts0, dtype=np.float64) / qscale
    kp_tile = np.asarray(mkpts1, dtype=np.float64) / tscale
    # XFeat's MNN matcher reports no per-match confidence; every returned pair already cleared
    # the mutual-nearest-neighbour + cosine-similarity test, so all matches are treated as
    # equally "confident" -- consistent with how the ratio-test-based candidates below still end
    # up producing a mix of scores. MAGSAC inlier count, not this score, is what §4.2 actually
    # gates on.
    confidence = np.ones(kp_query.shape[0], dtype=np.float64)
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=confidence)


# --------------------------------------------------------------------------- lightglue (aliked / disk)


def _build_lightglue(feature_name: str) -> MatcherHandle:
    import kornia.feature as KF

    t0 = time.perf_counter()
    if feature_name == "aliked":
        extractor = KF.ALIKED(max_num_keypoints=MAX_KEYPOINTS).eval()
    elif feature_name == "disk":
        extractor = KF.DISK.from_pretrained("depth").eval()
    else:
        raise ValueError(feature_name)
    matcher = KF.LightGlueMatcher(feature_name).eval()
    return MatcherHandle(f"lightglue_{feature_name}", (feature_name, extractor, matcher), (time.perf_counter() - t0) * 1000.0)


def build_lightglue_aliked() -> MatcherHandle:
    return _build_lightglue("aliked")


def build_lightglue_disk() -> MatcherHandle:
    return _build_lightglue("disk")


def _lightglue_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    import kornia.feature as KF

    feature_name, extractor, matcher = handle.backend
    query, qscale = _resize_longest_side(query_image_bgr, LOFTR_RESIZE)
    tile, tscale = _resize_longest_side(tile_image_bgr, LOFTR_RESIZE)
    tensor_q, tensor_t = _to_rgb_tensor(query), _to_rgb_tensor(tile)
    with torch.no_grad():
        if feature_name == "aliked":
            feats_q = extractor(tensor_q)[0]
            feats_t = extractor(tensor_t)[0]
        else:
            feats_q = extractor(tensor_q, n=MAX_KEYPOINTS, pad_if_not_divisible=True)[0]
            feats_t = extractor(tensor_t, n=MAX_KEYPOINTS, pad_if_not_divisible=True)[0]
        if feats_q.keypoints.shape[0] < 2 or feats_t.keypoints.shape[0] < 2:
            return _no_match()
        lafs_q = KF.laf_from_center_scale_ori(feats_q.keypoints[None])
        lafs_t = KF.laf_from_center_scale_ori(feats_t.keypoints[None])
        hw_q = (tensor_q.shape[-2], tensor_q.shape[-1])
        hw_t = (tensor_t.shape[-2], tensor_t.shape[-1])
        scores, matches = matcher(feats_q.descriptors, feats_t.descriptors, lafs_q, lafs_t, hw_q, hw_t)
    if matches is None or len(matches) == 0:
        return _no_match()
    scores_np = scores.reshape(-1).detach().cpu().numpy()
    matches_np = matches.detach().cpu().numpy()
    keep = scores_np >= CONFIDENCE_THRESHOLD
    if not keep.any():
        return _no_match()
    kp_q_all = feats_q.keypoints.detach().cpu().numpy()
    kp_t_all = feats_t.keypoints.detach().cpu().numpy()
    idx_q = matches_np[keep, 0].astype(int)
    idx_t = matches_np[keep, 1].astype(int)
    kp_query = kp_q_all[idx_q].astype(np.float64) / qscale
    kp_tile = kp_t_all[idx_t].astype(np.float64) / tscale
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=scores_np[keep].astype(np.float64))


def lightglue_aliked_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    return _lightglue_match_keypoints(handle, query_image_bgr, tile_image_bgr)


def lightglue_disk_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    return _lightglue_match_keypoints(handle, query_image_bgr, tile_image_bgr)


# --------------------------------------------------------------------------- loftr (kornia baseline)


def build_loftr() -> MatcherHandle:
    import kornia.feature as KF

    t0 = time.perf_counter()
    matcher = KF.LoFTR(pretrained="outdoor").eval()
    return MatcherHandle("loftr", matcher, (time.perf_counter() - t0) * 1000.0)


def loftr_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    from spikes.geo.harvested.verify import loftr_match_keypoints as _prod_loftr_match_keypoints

    return _prod_loftr_match_keypoints(handle.backend, query_image_bgr, tile_image_bgr)


# --------------------------------------------------------------------------- eloftr (official ZJU)

_ELOFTR_REPO_DIR = Path(__file__).resolve().parent / ".eloftr-repo"


def build_eloftr() -> MatcherHandle:
    """Official ZJU EfficientLoFTR (github.com/zju3dv/EfficientLoFTR) -- NOT the HuggingFace
    `transformers` AutoModelForKeypointMatching community port `bakeoff_matchers.py` already
    measured at 4453 ms/pair (slower than kornia-LoFTR's own 1457 ms/pair, contradicting the
    paper's 2.5x speedup claim -- VISUAL-GEO-V2-PLAN.md §4.7). Raises RuntimeError with the
    concrete reason on any failure (repo unreachable, weights unreachable, import error) so a
    caller can record "not run: <reason>" in §9 rather than crash the bake-off."""
    import subprocess
    import sys

    if not _ELOFTR_REPO_DIR.exists():
        LOGGER.info("cloning official EfficientLoFTR repo (shallow) into %s", _ELOFTR_REPO_DIR)
        result = subprocess.run(
            ["git", "clone", "--depth", "1", "https://github.com/zju3dv/EfficientLoFTR.git", str(_ELOFTR_REPO_DIR)],
            capture_output=True, text=True, timeout=180,
        )
        if result.returncode != 0:
            raise RuntimeError(f"git clone of zju3dv/EfficientLoFTR failed: {result.stderr[-2000:]}")
    if str(_ELOFTR_REPO_DIR) not in sys.path:
        sys.path.insert(0, str(_ELOFTR_REPO_DIR))

    # Compat shim: the upstream repo (last touched against an older kornia) imports
    # `kornia.utils.grid.create_meshgrid` -- that submodule path no longer exists in kornia 0.8.3
    # installed here (`create_meshgrid` moved up to `kornia.utils` directly, confirmed still
    # present and API-identical). Rather than vendor-patch the freshly-cloned repo, register a
    # tiny alias module under the old dotted path before its `src.loftr` package is imported --
    # real upstream code runs unmodified, only the import location is bridged.
    if "kornia.utils.grid" not in sys.modules:
        import types as _types
        import kornia.utils as _kornia_utils

        _grid_shim = _types.ModuleType("kornia.utils.grid")
        _grid_shim.create_meshgrid = _kornia_utils.create_meshgrid
        _grid_shim.create_meshgrid3d = _kornia_utils.create_meshgrid3d
        sys.modules["kornia.utils.grid"] = _grid_shim

    try:
        from src.loftr import LoFTR as ELoFTR  # noqa: N811 -- upstream's own class name
        from src.loftr import full_default_cfg, opt_default_cfg, reparameter
    except Exception as exc:  # noqa: BLE001 -- deliberately broad, converted to a recorded skip
        raise RuntimeError(f"official EfficientLoFTR import failed ({exc.__class__.__name__}: {exc})") from exc

    ckpt_path = _ELOFTR_REPO_DIR / "weights" / "eloftr_outdoor.ckpt"
    if not ckpt_path.exists():
        raise RuntimeError(
            f"official EfficientLoFTR checkpoint not found at {ckpt_path} -- the upstream repo "
            "distributes weights via a Google Drive link in its README, not a scripted download; "
            "fetch it by hand and re-run, or this candidate stays 'not run'"
        )
    t0 = time.perf_counter()
    cfg = full_default_cfg
    matcher = ELoFTR(config=cfg)
    state = torch.load(str(ckpt_path), map_location="cpu")
    matcher.load_state_dict(state["state_dict"] if "state_dict" in state else state)
    matcher = reparameter(matcher).eval()
    return MatcherHandle("eloftr", matcher, (time.perf_counter() - t0) * 1000.0)


def eloftr_match_keypoints(handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    query, qscale = _resize_longest_side(query_image_bgr, LOFTR_RESIZE)
    tile, tscale = _resize_longest_side(tile_image_bgr, LOFTR_RESIZE)
    tensor_q, tensor_t = _to_gray_tensor(query), _to_gray_tensor(tile)
    with torch.no_grad():
        out = handle.backend({"image0": tensor_q, "image1": tensor_t})
    confidence = out["mconf"].cpu().numpy() if "mconf" in out else np.zeros(0)
    if confidence.size == 0:
        return _no_match()
    keep = confidence >= CONFIDENCE_THRESHOLD
    if not keep.any():
        return _no_match()
    kp_query = out["mkpts0_f"].cpu().numpy()[keep].astype(np.float64) / qscale
    kp_tile = out["mkpts1_f"].cpu().numpy()[keep].astype(np.float64) / tscale
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=confidence[keep].astype(np.float64))


# --------------------------------------------------------------------------- registry


BUILDERS = {
    "xfeat": build_xfeat,
    "lightglue_aliked": build_lightglue_aliked,
    "lightglue_disk": build_lightglue_disk,
    "loftr": build_loftr,
    "eloftr": build_eloftr,
}

MATCH_FNS = {
    "xfeat": xfeat_match_keypoints,
    "lightglue_aliked": lightglue_aliked_match_keypoints,
    "lightglue_disk": lightglue_disk_match_keypoints,
    "loftr": loftr_match_keypoints,
    "eloftr": eloftr_match_keypoints,
}


def build(name: str) -> MatcherHandle:
    if name not in BUILDERS:
        raise ValueError(f"unknown matcher {name!r}, choose from {sorted(BUILDERS)}")
    return BUILDERS[name]()


def match_keypoints(name: str, handle: MatcherHandle, query_image_bgr: np.ndarray, tile_image_bgr: np.ndarray) -> MatchKeypoints:
    return MATCH_FNS[name](handle, query_image_bgr, tile_image_bgr)
