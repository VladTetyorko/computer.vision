"""Shared pytest fixtures/helpers for cv-service tests.

Generates a tiny synthetic BGR frame (as raw bytes and as JPEG bytes) so
decode-path tests don't need any fixture image files on disk.
"""

from __future__ import annotations

import numpy as np
import pytest


@pytest.fixture
def bgr_frame() -> np.ndarray:
    """A small deterministic BGR frame: 4x3 pixels (width=4, height=3)."""
    frame = np.zeros((3, 4, 3), dtype=np.uint8)
    # Fill with distinct per-pixel values so a decode round-trip is checkable.
    for y in range(3):
        for x in range(4):
            frame[y, x] = (x * 10, y * 20, 255)
    return frame


@pytest.fixture
def jpeg_bytes(bgr_frame: np.ndarray) -> bytes:
    import cv2

    ok, encoded = cv2.imencode(".jpg", bgr_frame)
    assert ok, "test setup: failed to JPEG-encode the fixture frame"
    return encoded.tobytes()
