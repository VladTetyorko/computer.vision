"""`histogram` appearance descriptor: HSV extraction, `None` for the
undescribable, and that two differently-coloured objects are actually
distinguishable -- the whole reason this engine exists (REVIEW finding B1).

Needs `cv2`/`numpy` -- the third named exception to `engines/`'s
pure-stdlib-outside-these rule (P3), alongside `bytetrack.py`/`lk.py`/
`flow_gmc.py`; skips rather than fails when the `cv` extra is absent, same
contract `test_engines.py`/`test_flow_gmc.py` use.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import METRIC_HELLINGER, Box

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from cv_service.tracking.engines.histogram import (  # noqa: E402
    ENGINE_ID,
    HistogramAppearanceExtractor,
    create,
)

WIDTH, HEIGHT = 200, 150
BACKGROUND = (60, 60, 60)  # BGR


def canvas() -> "np.ndarray":
    return np.full((HEIGHT, WIDTH, 3), BACKGROUND, dtype=np.uint8)


def paint(frame: "np.ndarray", box: Box, color: tuple) -> None:
    x0 = int(box.x * WIDTH)
    y0 = int(box.y * HEIGHT)
    x1 = int((box.x + box.width) * WIDTH)
    y1 = int((box.y + box.height) * HEIGHT)
    frame[y0:y1, x0:x1] = color


RED_BOX = Box(0.1, 0.1, 0.2, 0.2)
GREEN_BOX = Box(0.5, 0.5, 0.2, 0.2)
RED = (30, 30, 210)  # BGR, matches tools/trackeval/sequences.py's palette
GREEN = (40, 170, 40)


def test_engine_id_matches_the_registry_name():
    assert create().engine_id == ENGINE_ID == "histogram"


def test_a_missing_frame_describes_nothing():
    extractor = create()

    assert extractor.describe(None, [RED_BOX, GREEN_BOX]) == [None, None]


def test_the_output_is_positional_and_the_same_length_as_the_input():
    frame = canvas()
    paint(frame, RED_BOX, RED)
    extractor = create()

    described = extractor.describe(frame, [RED_BOX, GREEN_BOX, RED_BOX])

    assert len(described) == 3


def test_an_off_frame_box_is_not_describable():
    frame = canvas()
    extractor = create()

    described = extractor.describe(frame, [Box(1.5, 1.5, 0.1, 0.1)])

    assert described == [None]


def test_a_sub_pixel_box_is_not_describable():
    frame = canvas()
    extractor = create()

    described = extractor.describe(frame, [Box(0.5, 0.5, 0.0001, 0.0001)])

    assert described == [None]


def test_a_describable_box_yields_a_hellinger_descriptor_that_sums_to_one():
    frame = canvas()
    paint(frame, RED_BOX, RED)
    extractor = create()

    [descriptor] = extractor.describe(frame, [RED_BOX])

    assert descriptor is not None
    assert descriptor.engine_id == ENGINE_ID
    assert descriptor.metric == METRIC_HELLINGER
    assert sum(descriptor.values) == pytest.approx(1.0, abs=1e-6)


def test_two_differently_coloured_boxes_are_far_apart():
    # THE property this engine exists for: two visually distinct objects
    # must be distinguishable by their descriptor alone, independent of
    # geometry -- REVIEW finding B1.
    frame = canvas()
    paint(frame, RED_BOX, RED)
    paint(frame, GREEN_BOX, GREEN)
    extractor = create()

    red_descriptor, green_descriptor = extractor.describe(frame, [RED_BOX, GREEN_BOX])

    assert red_descriptor.distance(green_descriptor) > 0.5


def test_two_patches_of_the_same_colour_are_close():
    frame = canvas()
    paint(frame, RED_BOX, RED)
    other_red_box = Box(0.6, 0.1, 0.2, 0.2)
    paint(frame, other_red_box, RED)
    extractor = create()

    first, second = extractor.describe(frame, [RED_BOX, other_red_box])

    assert first.distance(second) < 0.1


def test_reset_is_a_no_op_and_never_raises():
    HistogramAppearanceExtractor().reset()
