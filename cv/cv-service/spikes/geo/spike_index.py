"""Reference descriptor index: plain numpy, no FAISS (docs/VISUAL-GEO-PLAN.md
§9 open choice 1 -- exhaustive matmul over ~tens of thousands of 512-D rows
is single-digit milliseconds, no extra dependency justified for a spike).
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np

from spikes.geo.encoders import Encoder, load_image
from spikes.geo.geomath import haversine_m

LOGGER = logging.getLogger("spikes.geo.index")


@dataclass(frozen=True)
class ReferenceItem:
    """One reference descriptor's geo-tagged source. `image_path` for a real
    fetched tile; `image` (in-memory) for an augment.py half-offset crop that
    was never written to disk."""

    ref_id: str
    lat: float
    lon: float
    image_path: Optional[Path] = None
    image: Optional[np.ndarray] = None

    def load(self) -> np.ndarray:
        if self.image is not None:
            return self.image
        if self.image_path is None:
            raise ValueError(f"reference item {self.ref_id} has neither image nor image_path")
        image = load_image(self.image_path)
        if image is None:
            raise ValueError(f"could not decode reference image {self.image_path}")
        return image


@dataclass(frozen=True)
class EncodeStats:
    count: int
    total_seconds: float
    per_item_ms: list[float]

    @property
    def mean_ms(self) -> float:
        return (self.total_seconds * 1000.0 / self.count) if self.count else 0.0


def encode_items(items: list[ReferenceItem], encoder: Encoder, batch_size: int = 16) -> tuple[np.ndarray, EncodeStats]:
    """Encode every item's image, L2-normalized rows in `items` order.
    Returns descriptors[N, dim] plus per-item timing (used for both the
    reference-pack build and the query-side `encode ms/frame` metric)."""
    descriptors = np.zeros((len(items), encoder.dim), dtype=np.float32)
    per_item_ms: list[float] = []
    start = time.perf_counter()
    for batch_start in range(0, len(items), max(1, batch_size)):
        batch_items = items[batch_start : batch_start + batch_size]
        images = [item.load() for item in batch_items]
        batch_t0 = time.perf_counter()
        batch_vectors = encoder.encode_batch(images)
        batch_elapsed_ms = (time.perf_counter() - batch_t0) * 1000.0
        per_item_ms.extend([batch_elapsed_ms / len(batch_items)] * len(batch_items))
        descriptors[batch_start : batch_start + len(batch_items)] = batch_vectors
    total = time.perf_counter() - start
    return descriptors, EncodeStats(count=len(items), total_seconds=total, per_item_ms=per_item_ms)


@dataclass(frozen=True)
class SearchResult:
    ref: ReferenceItem
    similarity: float


class ReferenceIndex:
    """Descriptors are assumed already L2-normalized (every Encoder
    guarantees this) so cosine similarity is a plain dot product."""

    def __init__(self, items: list[ReferenceItem], descriptors: np.ndarray):
        if len(items) != descriptors.shape[0]:
            raise ValueError(f"{len(items)} items but {descriptors.shape[0]} descriptor rows")
        self.items = items
        self.descriptors = descriptors
        self._lats = np.array([item.lat for item in items], dtype=np.float64)
        self._lons = np.array([item.lon for item in items], dtype=np.float64)

    def __len__(self) -> int:
        return len(self.items)

    def _candidate_mask(self, prior_lat: float, prior_lon: float, prior_radius_m: float) -> np.ndarray:
        # Vectorized haversine against every reference item -- fine at index
        # sizes this spike targets (tens of thousands of rows); switch to a
        # coarse lat/lon bounding-box pre-filter first if this ever becomes
        # the bottleneck at a much larger pack size.
        phi1 = np.radians(prior_lat)
        phi2 = np.radians(self._lats)
        dphi = np.radians(self._lats - prior_lat)
        dlambda = np.radians(self._lons - prior_lon)
        a = np.sin(dphi / 2) ** 2 + np.cos(phi1) * np.cos(phi2) * np.sin(dlambda / 2) ** 2
        distances = 2 * 6371000.0 * np.arcsin(np.minimum(1.0, np.sqrt(a)))
        return distances <= prior_radius_m

    def search(
        self,
        query_descriptor: np.ndarray,
        top_k: int = 5,
        prior: Optional[tuple[float, float, float]] = None,
    ) -> tuple[list[SearchResult], float]:
        """`prior`: (lat, lon, radius_meters) restricts candidates to that
        geographic disc before ranking -- the "with prior" condition from
        §5. Returns (ranked results best-first, search_ms)."""
        start = time.perf_counter()
        if prior is not None:
            plat, plon, pradius = prior
            mask = self._candidate_mask(plat, plon, pradius)
            candidate_idx = np.nonzero(mask)[0]
        else:
            candidate_idx = np.arange(len(self.items))
        if candidate_idx.size == 0:
            return [], (time.perf_counter() - start) * 1000.0
        sims = self.descriptors[candidate_idx] @ query_descriptor
        k = min(top_k, sims.shape[0])
        top_local = np.argpartition(-sims, k - 1)[:k]
        top_local = top_local[np.argsort(-sims[top_local])]
        top_global = candidate_idx[top_local]
        results = [
            SearchResult(ref=self.items[g], similarity=float(s)) for g, s in zip(top_global, sims[top_local])
        ]
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return results, elapsed_ms


def haversine_to_truth(result: SearchResult, true_lat: float, true_lon: float) -> float:
    return haversine_m(true_lat, true_lon, result.ref.lat, result.ref.lon)
