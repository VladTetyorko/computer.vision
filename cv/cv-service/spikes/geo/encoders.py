"""Pluggable image -> 512-D L2-normalized descriptor encoders for the Wave 0
spike (docs/VISUAL-GEO-PLAN.md §5).

Every encoder implements the same tiny interface: `encode(image: np.ndarray)
-> np.ndarray[dim]`, L2-normalized, `image` a BGR `uint8` array as returned
by `cv2.imread`/`cv2.VideoCapture`. That is the seam the real cross-view
drone<->satellite model plugs into later without touching index.py/metrics.py.

Three implementations:
  - `DeterministicEncoder` -- NOT a learned model. A fixed (seeded, never
    trained) random-projection + color/gradient-histogram descriptor. Exists
    only so the harness is exercisable with zero network access (see
    README.md "what's implemented vs. stubbed"). Never report its numbers as
    evidence about real VPR feasibility.
  - `VprHubEncoder` -- EigenPlaces or CosPlace via `torch.hub.load(...)`,
    ResNet backbone, off-the-shelf ground-level-trained weights (§5
    configuration 'a'). Needs internet on first run (downloads weights into
    `~/.cache/torch/hub`); cached after that, runs CPU-only.
  - `CrossViewEncoder` -- §5 configuration 'b' (drone<->satellite trained
    model, e.g. Sample4Geo/TransGeo). Deliberately `NotImplementedError` --
    see README.md for why this isn't a simple `pip install` and what
    building it for real would need.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from typing import Optional

import cv2
import numpy as np

LOGGER = logging.getLogger("spikes.geo.encoders")

DESCRIPTOR_DIM = 512
_IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
_IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm < 1e-12:
        return vector
    return vector / norm


class Encoder(ABC):
    name: str
    dim: int = DESCRIPTOR_DIM

    @abstractmethod
    def encode(self, image: np.ndarray) -> np.ndarray:
        """`image`: BGR uint8 HxWx3. Returns an L2-normalized float32[dim]."""

    def encode_batch(self, images: list[np.ndarray]) -> np.ndarray:
        """Default: encode one at a time. Torch-backed encoders override
        this with a real batched forward pass."""
        return np.stack([self.encode(image) for image in images], axis=0)


class DeterministicEncoder(Encoder):
    """Offline fallback, exercisable with zero dependencies beyond
    numpy/opencv: a fixed seeded random projection of a color-histogram +
    gradient-orientation-histogram + downsampled-grayscale feature vector.

    Not learned, not trained on anything -- it will NOT distinguish
    perceptually similar-but-distinct places the way a real VPR model would.
    Its only job is proving the harness's fetch -> encode -> index -> search
    -> metrics -> report plumbing runs end to end without a model download.
    """

    name = "offline"

    def __init__(self, seed: int = 20260806, dim: int = DESCRIPTOR_DIM):
        self.dim = dim
        color_bins = 16 * 3
        grad_bins = 8
        gray_pixels = 16 * 16
        feature_dim = color_bins + grad_bins + gray_pixels
        rng = np.random.RandomState(seed)
        self._projection = rng.normal(size=(feature_dim, dim)).astype(np.float32)

    def _raw_features(self, image: np.ndarray) -> np.ndarray:
        resized = cv2.resize(image, (128, 128), interpolation=cv2.INTER_AREA)
        color_hist = np.concatenate(
            [
                np.histogram(resized[:, :, c], bins=16, range=(0, 256))[0].astype(np.float32)
                for c in range(3)
            ]
        )
        color_hist /= max(color_hist.sum(), 1.0)

        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        magnitude = np.sqrt(gx**2 + gy**2)
        angle = (np.arctan2(gy, gx) + np.pi) / (2 * np.pi)  # [0, 1)
        grad_hist, _ = np.histogram(angle, bins=8, range=(0, 1), weights=magnitude)
        grad_hist = grad_hist.astype(np.float32)
        grad_hist /= max(grad_hist.sum(), 1e-6)

        small_gray = cv2.resize(gray, (16, 16), interpolation=cv2.INTER_AREA).astype(np.float32).flatten() / 255.0

        return np.concatenate([color_hist, grad_hist, small_gray]).astype(np.float32)

    def encode(self, image: np.ndarray) -> np.ndarray:
        features = self._raw_features(image)
        descriptor = features @ self._projection
        return l2_normalize(descriptor.astype(np.float32))


class VprHubEncoder(Encoder):
    """EigenPlaces or CosPlace, loaded via `torch.hub.load` from the
    upstream `gmberton/eigenplaces` / `gmberton/cosplace` repos -- both
    ground-level-image VPR models, ResNet backbone, this is §5 configuration
    'a'. CPU-only forward pass (no CUDA assumed, see cv-service/DEPLOY-GPU.md).

    Needs internet on the FIRST call per machine (downloads the repo zip +
    ImageNet backbone + trained head, cached under `~/.cache/torch/hub`
    after that -- see README.md). Construction raises `RuntimeError` with a
    clear message (never silently falls back) if the download/torch import
    fails, so the caller can catch it and report "skipped: no internet" in
    the spike report rather than fabricating a result.
    """

    _REPOS = {
        "eigenplaces": "gmberton/eigenplaces",
        "cosplace": "gmberton/cosplace",
    }

    def __init__(self, variant: str, backbone: str = "ResNet18", fc_output_dim: int = 512, device: str = "cpu"):
        if variant not in self._REPOS:
            raise ValueError(f"unknown VPR variant {variant!r}, must be one of {list(self._REPOS)}")
        self.name = variant
        self.dim = fc_output_dim
        self._device = device
        try:
            import torch  # lazy: keeps this module importable without torch installed
        except ImportError as exc:
            raise RuntimeError(
                f"{variant}: torch is not installed -- `pip install -r spikes/geo/requirements.txt` "
                "with the CPU extra-index-url (see README.md)"
            ) from exc

        try:
            # trust_repo=True: both repos are the paper authors' own
            # (gmberton) and this is throwaway spike code run by the
            # operator themselves, not a service accepting untrusted input.
            model = torch.hub.load(
                self._REPOS[variant], "get_trained_model", backbone=backbone, fc_output_dim=fc_output_dim,
                trust_repo=True,
            )
        except Exception as exc:  # noqa: BLE001 - torch.hub raises a mix of urllib/zip/import errors
            raise RuntimeError(
                f"{variant}: torch.hub.load failed ({exc!r}) -- needs internet on first run to fetch "
                f"weights from github.com/{self._REPOS[variant]}; see README.md"
            ) from exc

        model.eval()
        self._torch = torch
        self._model = model.to(device)

    def _preprocess(self, image: np.ndarray):
        torch = self._torch
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (224, 224), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
        normalized = (resized - _IMAGENET_MEAN) / _IMAGENET_STD
        chw = np.transpose(normalized, (2, 0, 1))
        return torch.from_numpy(chw).float()

    def encode(self, image: np.ndarray) -> np.ndarray:
        return self.encode_batch([image])[0]

    def encode_batch(self, images: list[np.ndarray]) -> np.ndarray:
        torch = self._torch
        tensors = torch.stack([self._preprocess(image) for image in images]).to(self._device)
        with torch.no_grad():
            output = self._model(tensors)
        vectors = output.cpu().numpy().astype(np.float32)
        return np.stack([l2_normalize(v) for v in vectors], axis=0)


class CrossViewEncoder(Encoder):
    """§5 configuration 'b' -- a drone(oblique/nadir FPV)<->satellite(nadir)
    cross-view model, e.g. Sample4Geo or TransGeo. Deliberately unimplemented.

    Why this isn't a simple `pip install` (see README.md for the full
    writeup): these are research repos, not packaged libraries -- no PyPI
    package, no `torch.hub` entry, checkpoints distributed via Google Drive
    links that rot, and most public checkpoints are trained on
    University-1652 / CVUSA / CVACT (ground-panorama<->satellite), not
    oblique FPV drone footage, so even a working install would need
    revalidation against this project's actual camera geometry before its
    numbers meant anything. Raising here rather than silently substituting
    the ground-level encoder keeps the spike honest: config 'b' in the
    report is either real cross-view numbers or an explicit gap, never a
    mislabeled config 'a' result.
    """

    name = "crossview"

    def __init__(self, *_args, **_kwargs):
        raise NotImplementedError(
            "cross-view drone<->satellite encoder is not implemented -- see README.md "
            "'Cross-view encoder: what would be needed' for what building this for real requires"
        )

    def encode(self, image: np.ndarray) -> np.ndarray:  # pragma: no cover - unreachable, __init__ always raises
        raise NotImplementedError


def build_encoder(name: str, *, backbone: str = "ResNet18", fc_output_dim: int = DESCRIPTOR_DIM, device: str = "cpu") -> Encoder:
    """Factory used by run_spike.py. Raises (RuntimeError/NotImplementedError/
    ValueError) rather than returning None on failure -- the caller decides
    whether a failed encoder means "skip this config" or "abort the run"."""
    key = name.strip().lower()
    if key == "offline":
        return DeterministicEncoder()
    if key in ("eigenplaces", "cosplace"):
        return VprHubEncoder(key, backbone=backbone, fc_output_dim=fc_output_dim, device=device)
    if key in ("crossview", "cross-view", "cross_view"):
        return CrossViewEncoder()
    if key in ("dinov2-plain", "sample4geo", "anyloc-vits14"):
        # Bake-off candidates (docs/VISUAL-GEO-PLAN.md §12.8 survey, actually run):
        # lazy import so the default offline/eigenplaces/cosplace/crossview path
        # never pays for torch.hub/timm unless one of these is requested, and so
        # spikes/geo/.venv (no timm/einops/fast_pytorch_kmeans) stays unaffected.
        # See bakeoff_encoders.py.
        from spikes.geo.bakeoff_encoders import build_bakeoff_encoder

        return build_bakeoff_encoder(key, device=device)
    raise ValueError(
        f"unknown encoder {name!r}, must be one of: offline, eigenplaces, cosplace, crossview, "
        "dinov2-plain, sample4geo, anyloc-vits14"
    )


def load_image(path) -> Optional[np.ndarray]:
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        LOGGER.warning("failed to decode image %s", path)
    return image
