"""Integration tests for `GeolocationServicer` (`Geolocation.LocalizeStream`/`BuildReferenceIndex`/
`ListRegions`/`DeleteRegion`, VISUAL-GEO-V2-PLAN.md §3.1/H4).

Same posture as `tests/pull/test_detect_pulled.py` (`LocalizeStream` is pull-only, exactly like
`DetectPulled`) and `tests/grpc/test_training_servicer.py` (`BuildReferenceIndex` mirrors
`UploadDataset`'s chunk-to-temp-file + job-streaming shape): a `FakePullSource`/`FakeContext`
drive the real servicer end to end, with a FAKE `Encoder`/matcher injected via the constructor
(`encoder=`/`matcher_handle=`/`match_keypoints_fn=`) -- no real torch/kornia/network needed for
any test in this file. `ListRegions`/`DeleteRegion` need no backend at all (their own documented
contract).
"""

from __future__ import annotations

import io
import json
import time
import zipfile
from pathlib import Path
from typing import Optional

import grpc
import pytest
from google.protobuf import empty_pb2

np = pytest.importorskip("numpy")
cv2 = pytest.importorskip("cv2")

from cv_service.geo import index as geo_index
from cv_service.geo import rerank as rerank_mod
from cv_service.geo.matchers import MatchKeypoints
from cv_service.grpc.servicers import GeolocationServicer, cv_pb2
from cv_service.pull.source import PulledFrame


# --- shared fakes (mirrors tests/pull/test_detect_pulled.py) ------------------------------------


class _AbortError(Exception):
    def __init__(self, code, details):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details


class FakeContext:
    def __init__(self) -> None:
        self._active = True
        self.aborted: Optional[tuple] = None

    def abort(self, code, details):
        self.aborted = (code, details)
        self._active = False
        raise _AbortError(code, details)

    def is_active(self) -> bool:
        return self._active

    def cancel(self) -> None:
        self._active = False

    def add_callback(self, callback) -> None:
        pass


class FakePullSource:
    def __init__(self, *, width: int = 64, height: int = 48, interval_seconds: float = 0.005) -> None:
        self._width = width
        self._height = height
        self._interval = interval_seconds
        self.n = 0
        self.closed = False

    def read(self) -> Optional[PulledFrame]:
        if self._interval:
            time.sleep(self._interval)
        self.n += 1
        rng = np.random.default_rng(self.n)
        image = (
            np.full((self._height, self._width, 3), 100, dtype=np.int16)
            + rng.integers(-40, 40, size=(self._height, self._width, 3))
        ).clip(0, 255).astype(np.uint8)
        return PulledFrame(image=image, width=self._width, height=self._height, pts_millis=self.n * 5.0, decode_millis=1.0)

    def close(self) -> None:
        self.closed = True


class FakeEncoder:
    dim = 8
    name = "fake"

    def __init__(self, true_row: int = 0) -> None:
        self._true_row = true_row

    def encode(self, image):
        v = np.zeros(8, dtype=np.float32)
        v[self._true_row % 8] = 1.0
        return v


class FakeMatcherHandle:
    pass


def _good_match_keypoints(handle, query_bgr, tile_bgr):
    tile_marker = int(tile_bgr[0, 0, 0])
    n = 30 if tile_marker == 200 else 3
    rng = np.random.default_rng(tile_marker)
    kp_query = rng.uniform(10, 55, size=(n, 2))
    kp_tile = kp_query.copy()
    return MatchKeypoints(kp_query=kp_query, kp_tile=kp_tile, confidence=np.ones(n))


# --- fixture region on real disk ------------------------------------------------------------------

TILE_IDS = [f"17/{76640 + i}/44190" for i in range(4)]
TRUE_ROW = 0


def _build_fixture_region(data_dir: Path, region_id: str = "test-region") -> None:
    """A real, minimal, on-disk region (`region.json` + `tiles/*.jpg` + `descriptors.npy` +
    `tiles.json` + `index.json`) -- everything `ListRegions`/`resolve_regions` actually reads off
    disk, so those tests need no monkeypatching at all."""
    region_dir = data_dir / region_id
    (region_dir / "tiles").mkdir(parents=True)
    tiles, rows = [], []
    for i, tid in enumerate(TILE_IDS):
        zoom, x, y = (int(p) for p in tid.split("/"))
        lat, lon = geo_index.tile_center(x, y, zoom)
        tiles.append(geo_index.ReferenceTileMeta(tile_id=tid, lat=lat, lon=lon, distinctiveness=0.5))
        v = np.zeros(8, dtype=np.float32)
        v[i % 8] = 1.0
        rows.append(v)
        marker = 200 if i == TRUE_ROW else 50
        image = np.full((16, 16, 3), marker, dtype=np.uint8)
        ok, encoded = cv2.imencode(".jpg", image)
        (region_dir / "tiles" / f"{tid.replace('/', '_')}.jpg").write_bytes(encoded.tobytes())
    index = geo_index.ReferenceIndex(tiles, np.stack(rows))
    index.save(region_dir)
    stats = geo_index.ReferenceIndexStats(
        tile_count=len(tiles), descriptor_count=len(tiles), descriptor_dim=8, encoder_id="fake",
        accept_similarity=0.5, accept_margin=0.05, holdout_recall_at_1=0.9,
        holdout_median_error_meters=50.0, index_bytes=1000, never_accept_cells=0,
    )
    geo_index.write_index_json(region_dir, stats, built_at_millis=1_700_000_000_000)
    (region_dir / "region.json").write_text(json.dumps({"regionId": region_id, "name": "Test Region"}))


@pytest.fixture(autouse=True)
def _patch_tile_loader(monkeypatch):
    def _fake_load_tile(region_dir, tile_id):
        marker = 200 if tile_id == TILE_IDS[TRUE_ROW] else 50
        return np.full((256, 256, 3), marker, dtype=np.uint8)

    monkeypatch.setattr(rerank_mod, "load_region_tile_image", _fake_load_tile)


def _fake_servicer(tmp_path: Path, **overrides) -> GeolocationServicer:
    kwargs = dict(
        data_dir=tmp_path, encoder=FakeEncoder(true_row=TRUE_ROW), matcher_handle=FakeMatcherHandle(),
        match_keypoints_fn=_good_match_keypoints,
    )
    kwargs.update(overrides)
    return GeolocationServicer(**kwargs)


def _first_control(**overrides) -> "cv_pb2.GeoControl":
    fields = dict(stream_id="geo-1", source_url="rtsp://example/geo-1", region_id="test-region", target_fps=100.0)
    fields.update(overrides)
    return cv_pb2.GeoControl(**fields)


def _stop_control(**overrides) -> "cv_pb2.GeoControl":
    fields = dict(stream_id="geo-1", stop=True)
    fields.update(overrides)
    return cv_pb2.GeoControl(**fields)


def _drive_localize(servicer, control_iterable, context) -> list:
    return list(servicer.LocalizeStream(iter(control_iterable), context))


# --- LocalizeStream --------------------------------------------------------------------------------


def test_missing_source_url_aborts_invalid_argument(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    with pytest.raises(_AbortError) as exc_info:
        _drive_localize(servicer, [cv_pb2.GeoControl(stream_id="s1", target_fps=10.0)], FakeContext())
    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT
    assert "source_url" in exc_info.value.details


def test_missing_encoder_aborts_unavailable(tmp_path: Path):
    servicer = _fake_servicer(tmp_path, encoder=None, matcher_handle=None, match_keypoints_fn=None)
    with pytest.raises(_AbortError) as exc_info:
        _drive_localize(servicer, [_first_control()], FakeContext())
    assert exc_info.value.code == grpc.StatusCode.UNAVAILABLE


def test_stream_id_mismatch_mid_call_aborts_invalid_argument(tmp_path: Path):
    _build_fixture_region(tmp_path)
    source = FakePullSource()
    servicer = _fake_servicer(tmp_path, pull_source_open=lambda url, **kwargs: source)

    def control_gen():
        yield _first_control(target_fps=200.0)
        time.sleep(0.1)
        yield cv_pb2.GeoControl(stream_id="other", target_fps=200.0)

    with pytest.raises(_AbortError) as exc_info:
        _drive_localize(servicer, control_gen(), FakeContext())
    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT
    assert "stream_id" in exc_info.value.details


def test_happy_path_produces_a_geo_fix_stream_on_the_true_tile(tmp_path: Path):
    _build_fixture_region(tmp_path)
    source = FakePullSource(interval_seconds=0.002)
    servicer = _fake_servicer(tmp_path, pull_source_open=lambda url, **kwargs: source)
    context = FakeContext()

    def control_gen():
        yield _first_control(target_fps=200.0)
        time.sleep(0.15)
        yield _stop_control()

    responses = _drive_localize(servicer, control_gen(), context)

    assert context.aborted is None
    assert len(responses) >= 1
    sequences = [r.sequence for r in responses]
    assert sequences == list(range(1, len(sequences) + 1))
    fixes = [r for r in responses if r.status == cv_pb2.GeoStatus.GEO_STATUS_FIX]
    assert fixes, "at least one frame should have produced a clean GEO_STATUS_FIX"
    assert fixes[0].tile_id == TILE_IDS[TRUE_ROW]
    assert fixes[0].HasField("latitude") and fixes[0].HasField("longitude")
    assert fixes[0].refusal == ""
    for r in responses:
        assert r.stream_id == "geo-1"


def test_unopenable_source_aborts_unavailable(tmp_path: Path):
    _build_fixture_region(tmp_path)

    def _raising_open(url, **kwargs):
        from cv_service.pull.source import PullSourceError

        raise PullSourceError(f"could not open {url!r}")

    servicer = _fake_servicer(tmp_path, pull_source_open=_raising_open)
    with pytest.raises(_AbortError) as exc_info:
        _drive_localize(servicer, [_first_control()], FakeContext())
    assert exc_info.value.code == grpc.StatusCode.UNAVAILABLE


def test_no_ready_region_yields_no_index_status(tmp_path: Path):
    """`region_id` names a region that was never landed/built -- `resolve_regions` returns `[]`,
    the pipeline reports `GEO_STATUS_NO_INDEX` rather than erroring."""
    source = FakePullSource(interval_seconds=0.002)
    servicer = _fake_servicer(tmp_path, pull_source_open=lambda url, **kwargs: source)
    context = FakeContext()

    def control_gen():
        yield _first_control(region_id="does-not-exist", target_fps=200.0)
        time.sleep(0.05)
        yield _stop_control()

    responses = _drive_localize(servicer, control_gen(), context)
    assert responses
    assert all(r.status == cv_pb2.GeoStatus.GEO_STATUS_NO_INDEX for r in responses)


# --- BuildReferenceIndex ----------------------------------------------------------------------------


def _pack_zip_bytes(region_id: str, tile_count: int = 3) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("region.json", json.dumps({"regionId": region_id}))
        for i in range(tile_count):
            image = np.full((16, 16, 3), 100 + i, dtype=np.uint8)
            ok, encoded = cv2.imencode(".jpg", image)
            zf.writestr(f"tiles/17_{76700+i}_44200.jpg", encoded.tobytes())
    return buf.getvalue()


def _chunks(region_id: str, data: bytes, chunk_size: int = 4096) -> list:
    return [
        cv_pb2.ReferencePackChunk(region_id=region_id, content=data[i : i + chunk_size])
        for i in range(0, len(data), chunk_size)
    ] or [cv_pb2.ReferencePackChunk(region_id=region_id, content=b"")]


def test_build_reference_index_happy_path_reaches_succeeded_with_stats(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    zip_bytes = _pack_zip_bytes("built-region", tile_count=4)
    events = list(servicer.BuildReferenceIndex(iter(_chunks("built-region", zip_bytes)), FakeContext()))

    phases = [e.phase for e in events]
    assert "receiving" in phases
    assert "extracting" in phases
    terminal = events[-1]
    assert terminal.state == cv_pb2.JobState.SUCCEEDED
    assert terminal.HasField("stats")
    assert terminal.stats.tile_count == 4
    assert (tmp_path / "built-region" / "index.json").is_file()


def test_build_reference_index_blank_region_id_aborts(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    context = FakeContext()
    with pytest.raises(_AbortError) as exc_info:
        list(servicer.BuildReferenceIndex(iter([cv_pb2.ReferencePackChunk(region_id="", content=b"x")]), context))
    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT


def test_build_reference_index_mid_stream_region_id_change_aborts(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    chunks = [
        cv_pb2.ReferencePackChunk(region_id="r1", content=b"x"),
        cv_pb2.ReferencePackChunk(region_id="r2", content=b"y"),
    ]
    with pytest.raises(_AbortError) as exc_info:
        list(servicer.BuildReferenceIndex(iter(chunks), FakeContext()))
    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT


def test_build_reference_index_zero_chunks_reports_failed_not_abort(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    events = list(servicer.BuildReferenceIndex(iter([]), FakeContext()))
    assert events[-1].state == cv_pb2.JobState.FAILED


def test_build_reference_index_corrupt_zip_reports_failed(tmp_path: Path):
    servicer = _fake_servicer(tmp_path)
    events = list(
        servicer.BuildReferenceIndex(iter(_chunks("bad-region", b"not a zip at all")), FakeContext())
    )
    assert events[-1].state == cv_pb2.JobState.FAILED


def test_build_reference_index_no_encoder_reports_failed(tmp_path: Path):
    servicer = _fake_servicer(tmp_path, encoder=None, matcher_handle=None, match_keypoints_fn=None)
    zip_bytes = _pack_zip_bytes("r1", tile_count=2)
    events = list(servicer.BuildReferenceIndex(iter(_chunks("r1", zip_bytes)), FakeContext()))
    assert events[-1].state == cv_pb2.JobState.FAILED
    assert "geo encoder unavailable" in events[-1].message


# --- ListRegions / DeleteRegion ---------------------------------------------------------------------


def test_list_regions_only_reports_ready_built_regions(tmp_path: Path):
    _build_fixture_region(tmp_path, region_id="ready-region")
    # A landed-but-never-built region: region.json + tiles/, no index.json.
    (tmp_path / "landed-only" / "tiles").mkdir(parents=True)
    (tmp_path / "landed-only" / "region.json").write_text("{}")

    servicer = GeolocationServicer(data_dir=tmp_path, encoder=None, matcher_handle=None)
    result = servicer.ListRegions(empty_pb2.Empty(), FakeContext())

    ids = [r.region_id for r in result.regions]
    assert ids == ["ready-region"]
    info = result.regions[0]
    assert info.name == "Test Region"
    assert info.stats.tile_count == len(TILE_IDS)
    assert info.zoom == 17


def test_list_regions_empty_data_dir(tmp_path: Path):
    servicer = GeolocationServicer(data_dir=tmp_path / "does-not-exist", encoder=None, matcher_handle=None)
    result = servicer.ListRegions(empty_pb2.Empty(), FakeContext())
    assert list(result.regions) == []


def test_delete_region_removes_an_existing_region(tmp_path: Path):
    _build_fixture_region(tmp_path, region_id="to-delete")
    servicer = GeolocationServicer(data_dir=tmp_path, encoder=None, matcher_handle=None)

    ack = servicer.DeleteRegion(cv_pb2.RegionRef(region_id="to-delete"), FakeContext())

    assert ack.ok is True
    assert not (tmp_path / "to-delete").exists()


def test_delete_region_unknown_id_returns_ok_false(tmp_path: Path):
    servicer = GeolocationServicer(data_dir=tmp_path, encoder=None, matcher_handle=None)
    ack = servicer.DeleteRegion(cv_pb2.RegionRef(region_id="nope"), FakeContext())
    assert ack.ok is False


def test_delete_region_unsafe_id_returns_ok_false(tmp_path: Path):
    servicer = GeolocationServicer(data_dir=tmp_path, encoder=None, matcher_handle=None)
    ack = servicer.DeleteRegion(cv_pb2.RegionRef(region_id="../evil"), FakeContext())
    assert ack.ok is False
