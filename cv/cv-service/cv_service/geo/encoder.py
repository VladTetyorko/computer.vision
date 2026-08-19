"""Pluggable image -> descriptor encoder for visual geolocation (docs/VISUAL-GEO-PLAN.md §2 D2,
Wave 2a). Production port of `spikes/geo/encoders.py`'s `VprHubEncoder` -- the same
EigenPlaces/CosPlace-via-`torch.hub` approach the Wave 0 spike validated (§12), CPU-only (this
service's inference box is Intel-only, no CUDA -- `cv-service/DEPLOY-GPU.md`). Same pluggable
`Encoder` interface the spike used, so a future cross-view drone<->satellite model (§12.4's
Sample4Geo lead, not yet tried) can be dropped in without touching `index.py`/`calibrate.py`/
`orchestrator.py`.

Deliberately narrower than the spike: `DeterministicEncoder` (an offline, non-learned stand-in
that let the spike run with zero network access) and `CrossViewEncoder` (deliberately
unimplemented, §5 configuration 'b') are spike-only concerns and are NOT ported here -- tests
that need an encoder without a real weights download inject their own tiny `Encoder` subclass,
the same dependency-injection idiom `cv_service/inference/detector.py#YoloDetector(model=...)`
already uses, rather than this module providing a "fake" implementation of its own.

Needs `cv2` + (lazily) `torch`/`torchvision` -- the `geo` extra. Not imported at
`cv_service/grpc/servicers.py` module scope; only lazily inside `BuildReferenceIndex`, same
discipline `cv_service.inference.registry` already follows for the `cv` extra.
"""

from __future__ import annotations

import logging
import re
from abc import ABC, abstractmethod
from typing import Optional

import cv2
import numpy as np

LOGGER = logging.getLogger("cv_service.geo.encoder")

DEFAULT_DESCRIPTOR_DIM = 512

_IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
_IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
# "eigenplaces_r18_512" -> ("eigenplaces", "18", "512") -- `CV_GEO_ENCODER`'s frozen shape (§3.5).
_ENCODER_ID_RE = re.compile(r"^(eigenplaces|cosplace)_r(\d+)_(\d+)$")
_HUB_REPOS = {"eigenplaces": "gmberton/eigenplaces", "cosplace": "gmberton/cosplace"}


def l2_normalize(vector: "np.ndarray") -> "np.ndarray":
    norm = float(np.linalg.norm(vector))
    if norm < 1e-12:
        return vector
    return vector / norm


class Encoder(ABC):
    """image -> L2-normalized float32[dim] descriptor. `image`: BGR uint8 HxWx3, the same
    convention `cv2.imread`/`cv2.VideoCapture`/`cv_service.inference.detector.decode_frame`
    already use elsewhere in this service."""

    name: str
    dim: int = DEFAULT_DESCRIPTOR_DIM

    @abstractmethod
    def encode(self, image: "np.ndarray") -> "np.ndarray": ...

    def encode_batch(self, images: list) -> "np.ndarray":
        """Default: one at a time. `VprHubEncoder` overrides with a real batched forward pass."""
        return np.stack([self.encode(image) for image in images], axis=0)


class EncoderUnavailableError(RuntimeError):
    """`torch` isn't installed, or `torch.hub.load` failed (no cached weights + no internet on
    first run) -- mirrors `cv_service.inference.detector.ModelUnavailableError`'s contract:
    raised from construction, never a silent fallback, so the caller
    (`GeolocationServicer.BuildReferenceIndex`) reports a normal `FAILED` build event instead of
    crash-looping."""


class VprHubEncoder(Encoder):
    """EigenPlaces or CosPlace via `torch.hub.load(..., trust_repo=True)` -- both repos are the
    original papers' own (`gmberton/eigenplaces`/`gmberton/cosplace`), ResNet backbone,
    ground-level-image VPR, CPU-only forward pass.

    Needs internet on the FIRST call per machine (downloads into `~/.cache/torch/hub`, cached
    after that -- confirmed in this task's own dev environment, see MODULE.md).
    """

    def __init__(
        self,
        variant: str,
        *,
        name: Optional[str] = None,
        backbone: str = "ResNet18",
        fc_output_dim: int = DEFAULT_DESCRIPTOR_DIM,
        device: Optional[str] = None,
    ) -> None:
        if variant not in _HUB_REPOS:
            raise ValueError(f"unknown VPR variant {variant!r}, must be one of {list(_HUB_REPOS)}")
        self.name = name or variant
        self.dim = fc_output_dim
        self._device = device or "cpu"
        try:
            import torch  # lazy: keeps this module importable without torch installed
        except ImportError as exc:
            raise EncoderUnavailableError(
                f"{self.name}: torch is not installed -- install the 'geo' extra "
                "(pip install -e '.[geo]' --extra-index-url https://download.pytorch.org/whl/cpu)"
            ) from exc

        try:
            # trust_repo=True: both repos are the paper authors' own (gmberton), loaded by this
            # service's own operator-controlled deployment, not accepting untrusted input.
            model = torch.hub.load(
                _HUB_REPOS[variant],
                "get_trained_model",
                backbone=backbone,
                fc_output_dim=fc_output_dim,
                trust_repo=True,
            )
        except Exception as exc:  # noqa: BLE001 - torch.hub raises a mix of urllib/zip/import errors
            raise EncoderUnavailableError(
                f"{self.name}: torch.hub.load failed ({exc!r}) -- needs internet on first run to "
                f"fetch weights from github.com/{_HUB_REPOS[variant]}"
            ) from exc

        model.eval()
        self._torch = torch
        self._model = model.to(self._device)
        LOGGER.info(
            "cv-service geo encoder loaded: %s (device=%s, dim=%d)", self.name, self._device, self.dim
        )

    def _preprocess(self, image: "np.ndarray"):
        torch = self._torch
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (224, 224), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
        normalized = (resized - _IMAGENET_MEAN) / _IMAGENET_STD
        chw = np.transpose(normalized, (2, 0, 1))
        return torch.from_numpy(chw).float()

    def encode(self, image: "np.ndarray") -> "np.ndarray":
        return self.encode_batch([image])[0]

    def encode_batch(self, images: list) -> "np.ndarray":
        torch = self._torch
        tensors = torch.stack([self._preprocess(image) for image in images]).to(self._device)
        with torch.no_grad():
            output = self._model(tensors)
        vectors = output.cpu().numpy().astype(np.float32)
        return np.stack([l2_normalize(v) for v in vectors], axis=0)


def parse_encoder_id(encoder_id: str) -> tuple[str, str, int]:
    """`"eigenplaces_r18_512"` -> `("eigenplaces", "ResNet18", 512)`. Raises `ValueError` on an
    unrecognized shape -- `build_encoder` lets this propagate; the caller reports it as a normal
    failed-build event, same posture as an unloadable `CV_MODEL`."""
    match = _ENCODER_ID_RE.match(encoder_id.strip())
    if not match:
        raise ValueError(
            f"unrecognized encoder id {encoder_id!r}; expected "
            "'<eigenplaces|cosplace>_r<backbone-number>_<dim>', e.g. 'eigenplaces_r18_512' "
            "(the CV_GEO_ENCODER default)"
        )
    variant, backbone_num, dim = match.groups()
    return variant, f"ResNet{backbone_num}", int(dim)


def build_encoder(encoder_id: str, *, device: Optional[str] = None) -> Encoder:
    """Factory used by `cv_service.geo.orchestrator`/`GeolocationServicer`: parses `encoder_id`
    (`Settings.geo_encoder`, i.e. `CV_GEO_ENCODER`) and constructs the matching `VprHubEncoder`.
    Raises (`ValueError`/`EncoderUnavailableError`) rather than returning `None` -- the caller
    decides how to report a failed build, mirroring `cv_service.inference.registry`'s factories.
    """
    variant, backbone, dim = parse_encoder_id(encoder_id)
    return VprHubEncoder(variant, name=encoder_id, backbone=backbone, fc_output_dim=dim, device=device)


def load_image(path) -> Optional["np.ndarray"]:
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        LOGGER.warning("failed to decode reference tile image %s", path)
    return image
