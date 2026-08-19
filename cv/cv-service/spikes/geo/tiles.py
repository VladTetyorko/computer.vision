"""Satellite reference-tile fetch (Esri World Imagery) with disk caching,
politeness (concurrency + requests/sec) and 429/5xx backoff.

Mirrors the tile source frozen in docs/VISUAL-GEO-PLAN.md §3.5:
`https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`
-- note the `{z}/{y}/{x}` order, NOT `{z}/{x}/{y}`; this repo's own
`shared/map/tile-cache/leaflet-loader.ts#MAP_LAYERS` flags the same trap.

stdlib-only (urllib) so this module has no dependency on the requests/httpx
choice made anywhere else in the repo.
"""

from __future__ import annotations

import logging
import os
import random
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from spikes.geo.geomath import tile_center, tiles_for_bbox

LOGGER = logging.getLogger("spikes.geo.tiles")

DEFAULT_URL_TEMPLATE = (
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
)
DEFAULT_ZOOM = 17
DEFAULT_MAX_TILES = 50_000
DEFAULT_CONCURRENCY = 4
DEFAULT_REQUESTS_PER_SECOND = 20.0
DEFAULT_TIMEOUT_SECONDS = 10.0
DEFAULT_USER_AGENT = "vision-geo-spike/0.0.1"
DEFAULT_MAX_RETRIES = 5
_CACHE_DIRNAME = "tile_cache"


def _parse_float(raw: Optional[str], default: float) -> float:
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        LOGGER.warning("invalid float env value %r; using default %s", raw, default)
        return default


def _parse_int(raw: Optional[str], default: int) -> int:
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        LOGGER.warning("invalid int env value %r; using default %s", raw, default)
        return default


@dataclass(frozen=True)
class TileFetchSettings:
    url_template: str = DEFAULT_URL_TEMPLATE
    cache_dir: Path = field(default_factory=lambda: Path(__file__).resolve().parent / _CACHE_DIRNAME)
    concurrency: int = DEFAULT_CONCURRENCY
    requests_per_second: float = DEFAULT_REQUESTS_PER_SECOND
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    user_agent: str = DEFAULT_USER_AGENT
    max_retries: int = DEFAULT_MAX_RETRIES
    max_tiles: int = DEFAULT_MAX_TILES

    @classmethod
    def from_env(cls) -> "TileFetchSettings":
        """Forgiving env-var overrides (`SPIKE_GEO_TILES_*`), same idiom as
        `cv_service/config.py#Settings.from_env` -- unset/garbage falls back
        to the default, never raises. CLI flags in run_spike.py take
        precedence over these when both are given."""
        return cls(
            url_template=os.environ.get("SPIKE_GEO_TILES_URL_TEMPLATE", DEFAULT_URL_TEMPLATE),
            concurrency=_parse_int(os.environ.get("SPIKE_GEO_TILES_CONCURRENCY"), DEFAULT_CONCURRENCY),
            requests_per_second=_parse_float(
                os.environ.get("SPIKE_GEO_TILES_REQUESTS_PER_SECOND"), DEFAULT_REQUESTS_PER_SECOND
            ),
            timeout_seconds=_parse_float(os.environ.get("SPIKE_GEO_TILES_TIMEOUT"), DEFAULT_TIMEOUT_SECONDS),
            user_agent=os.environ.get("SPIKE_GEO_TILES_USER_AGENT", DEFAULT_USER_AGENT),
            max_retries=_parse_int(os.environ.get("SPIKE_GEO_TILES_MAX_RETRIES"), DEFAULT_MAX_RETRIES),
            max_tiles=_parse_int(os.environ.get("SPIKE_GEO_TILES_MAX_TILES"), DEFAULT_MAX_TILES),
        )


@dataclass(frozen=True)
class Tile:
    zoom: int
    x: int
    y: int
    lat: float
    lon: float
    path: Path

    @property
    def tile_id(self) -> str:
        return f"{self.zoom}/{self.x}/{self.y}"


class _RateLimiter:
    """Simple shared token-interval limiter: at most `rps` requests/sec
    across every worker thread combined."""

    def __init__(self, rps: float):
        self._interval = 1.0 / rps if rps > 0 else 0.0
        self._lock = threading.Lock()
        self._next_slot = 0.0

    def wait(self) -> None:
        if self._interval <= 0:
            return
        with self._lock:
            now = time.monotonic()
            start = max(now, self._next_slot)
            self._next_slot = start + self._interval
        delay = start - now
        if delay > 0:
            time.sleep(delay)


def _cache_path(cache_dir: Path, zoom: int, x: int, y: int) -> Path:
    return cache_dir / str(zoom) / str(x) / f"{y}.jpg"


def _fetch_one(zoom: int, x: int, y: int, settings: TileFetchSettings, limiter: _RateLimiter) -> Optional[Path]:
    path = _cache_path(settings.cache_dir, zoom, x, y)
    if path.exists() and path.stat().st_size > 0:
        return path

    url = settings.url_template.format(z=zoom, x=x, y=y)
    path.parent.mkdir(parents=True, exist_ok=True)
    backoff = 1.0
    for attempt in range(1, settings.max_retries + 1):
        limiter.wait()
        request = urllib.request.Request(url, headers={"User-Agent": settings.user_agent})
        try:
            with urllib.request.urlopen(request, timeout=settings.timeout_seconds) as response:
                data = response.read()
            tmp_path = path.with_suffix(".tmp")
            tmp_path.write_bytes(data)
            tmp_path.replace(path)
            return path
        except urllib.error.HTTPError as exc:
            if exc.code == 429 or 500 <= exc.code < 600:
                LOGGER.warning(
                    "tile %d/%d/%d HTTP %d (attempt %d/%d), backing off %.1fs",
                    zoom, x, y, exc.code, attempt, settings.max_retries, backoff,
                )
                time.sleep(backoff + random.uniform(0, backoff * 0.5))
                backoff = min(backoff * 2, 30.0)
                continue
            LOGGER.warning("tile %d/%d/%d HTTP %d, giving up", zoom, x, y, exc.code)
            return None
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            LOGGER.warning(
                "tile %d/%d/%d fetch failed (attempt %d/%d): %s", zoom, x, y, attempt, settings.max_retries, exc
            )
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)
    LOGGER.warning("tile %d/%d/%d exhausted %d retries, dropping", zoom, x, y, settings.max_retries)
    return None


def fetch_tiles(xy_list: list[tuple[int, int]], zoom: int, settings: TileFetchSettings) -> list[Tile]:
    """Fetch (or serve from cache) every (x, y) tile at `zoom`. Tiles that
    fail after retries are silently dropped from the result (never raises) --
    a spike over a real region should tolerate a handful of missing tiles
    rather than aborting the whole run."""
    if len(xy_list) > settings.max_tiles:
        raise ValueError(
            f"{len(xy_list)} tiles requested exceeds max_tiles={settings.max_tiles} "
            "-- shrink the bbox or raise --max-tiles"
        )
    limiter = _RateLimiter(settings.requests_per_second)
    tiles: list[Tile] = []
    with ThreadPoolExecutor(max_workers=max(1, settings.concurrency)) as pool:
        futures = {pool.submit(_fetch_one, zoom, x, y, settings, limiter): (x, y) for x, y in xy_list}
        done = 0
        for future in as_completed(futures):
            x, y = futures[future]
            done += 1
            path = future.result()
            if path is None:
                continue
            lat, lon = tile_center(x, y, zoom)
            tiles.append(Tile(zoom=zoom, x=x, y=y, lat=lat, lon=lon, path=path))
            if done % 200 == 0 or done == len(futures):
                LOGGER.info("fetched %d/%d tiles", done, len(futures))
    tiles.sort(key=lambda t: (t.y, t.x))
    return tiles


def fetch_bbox(
    lat1: float, lon1: float, lat2: float, lon2: float, zoom: int, settings: TileFetchSettings, halo: int = 0
) -> list[Tile]:
    """Fetch every tile covering the bbox, optionally expanded by `halo`
    extra tiles on every side (used by augment.py's half-offset crops, which
    need a tile's right/down/down-right neighbor even at the region edge)."""
    xy_list = tiles_for_bbox(lat1, lon1, lat2, lon2, zoom)
    if halo > 0:
        xs = [x for x, _ in xy_list]
        ys = [y for _, y in xy_list]
        x_lo, x_hi = min(xs) - halo, max(xs) + halo
        y_lo, y_hi = min(ys) - halo, max(ys) + halo
        xy_list = [(x, y) for y in range(y_lo, y_hi + 1) for x in range(x_lo, x_hi + 1)]
    return fetch_tiles(xy_list, zoom, settings)
