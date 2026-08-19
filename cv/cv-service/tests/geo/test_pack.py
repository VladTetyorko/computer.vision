"""Unit tests for `cv_service.geo.pack` (reference-pack zip landing, VISUAL-GEO-V2-PLAN.md §3.1).
Pure stdlib -- no cv/geo extra needed."""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

import pytest

from cv_service.geo.pack import (
    is_safe_pack_entry,
    is_safe_region_id,
    land_pack,
    read_region_meta,
    sanitize_region_id,
)


# --- region id safety --------------------------------------------------------------------------


def test_sanitize_region_id_keeps_a_clean_id():
    assert sanitize_region_id("kyiv-maidan") == "kyiv-maidan"


@pytest.mark.parametrize("bad", ["", "..", "../etc", "a/b", "a\\b", "   "])
def test_is_safe_region_id_rejects_unsafe(bad):
    assert is_safe_region_id(bad) is False


def test_is_safe_region_id_accepts_clean_id():
    assert is_safe_region_id("kyiv-maidan_2") is True


# --- zip-slip guard ------------------------------------------------------------------------------


def test_is_safe_pack_entry_accepts_region_json_and_tile_entries():
    assert is_safe_pack_entry("region.json") is True
    assert is_safe_pack_entry("tiles/17_76648_44197.jpg") is True


@pytest.mark.parametrize(
    "bad",
    [
        "../region.json",
        "/etc/passwd",
        "tiles/../region.json",
        "tiles/sub/17_1_1.jpg",
        "tiles/not-a-tile.jpg",
        "other.json",
        "",
    ],
)
def test_is_safe_pack_entry_rejects_unsafe(bad):
    assert is_safe_pack_entry(bad) is False


# --- land_pack ------------------------------------------------------------------------------------


def _zip_bytes(entries: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    return buf.getvalue()


def _write_zip(path: Path, entries: dict) -> None:
    path.write_bytes(_zip_bytes(entries))


def test_land_pack_happy_path(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    _write_zip(zip_path, {
        "region.json": '{"regionId": "r1"}',
        "tiles/17_1_1.jpg": b"\xff\xd8fake-jpeg-bytes",
        "tiles/17_1_2.jpg": b"\xff\xd8fake-jpeg-bytes",
    })

    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=100)

    assert outcome.ok is True
    assert outcome.tile_count == 2
    assert (data_dir / "r1" / "region.json").is_file()
    assert (data_dir / "r1" / "tiles" / "17_1_1.jpg").is_file()


def test_land_pack_overwrites_a_previous_landing(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    _write_zip(zip_path, {"region.json": "{}", "tiles/17_1_1.jpg": b"a"})
    land_pack(zip_path, data_dir, "r1", bytes_received=10)

    _write_zip(zip_path, {"region.json": "{}", "tiles/17_2_2.jpg": b"b"})
    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=10)

    assert outcome.ok is True
    assert not (data_dir / "r1" / "tiles" / "17_1_1.jpg").exists()
    assert (data_dir / "r1" / "tiles" / "17_2_2.jpg").is_file()


def test_land_pack_rejects_zip_slip_entry(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    _write_zip(zip_path, {"region.json": "{}", "../evil.jpg": b"a"})

    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=10)

    assert outcome.ok is False
    assert "unsafe" in outcome.message
    assert not (data_dir / "r1").exists()


def test_land_pack_missing_region_json_fails(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    _write_zip(zip_path, {"tiles/17_1_1.jpg": b"a"})

    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=10)

    assert outcome.ok is False
    assert "region.json" in outcome.message


def test_land_pack_missing_tiles_dir_fails(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    _write_zip(zip_path, {"region.json": "{}"})

    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=10)

    assert outcome.ok is False
    assert "tiles/" in outcome.message


def test_land_pack_corrupt_zip_fails(tmp_path: Path):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    zip_path = tmp_path / "pack.zip"
    zip_path.write_bytes(b"not a zip file at all")

    outcome = land_pack(zip_path, data_dir, "r1", bytes_received=10)

    assert outcome.ok is False


def test_read_region_meta_round_trips(tmp_path: Path):
    region_dir = tmp_path / "r1"
    region_dir.mkdir()
    (region_dir / "region.json").write_text('{"regionId": "r1", "name": "Region One"}')
    meta = read_region_meta(region_dir)
    assert meta == {"regionId": "r1", "name": "Region One"}


def test_read_region_meta_missing_returns_none(tmp_path: Path):
    assert read_region_meta(tmp_path / "nope") is None
