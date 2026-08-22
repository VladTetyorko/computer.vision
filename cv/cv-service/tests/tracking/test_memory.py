"""The dormant gallery: the four gates, the bounds, and the honesty of the score.

A false recovery is worse than a missed one -- it attaches the operator's
attention to the wrong object while telling them it is the right one -- so
every gate gets a test that proves it REJECTS, not merely that it accepts.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import METRIC_HELLINGER, Box, Descriptor
from cv_service.tracking.memory import MemoryParams, ObjectMemory

RED = Descriptor("hist", (0.8, 0.1, 0.1), METRIC_HELLINGER)
BLUE = Descriptor("hist", (0.1, 0.1, 0.8), METRIC_HELLINGER)
PINK = Descriptor("hist", (0.7, 0.2, 0.1), METRIC_HELLINGER)


def memory(**overrides) -> ObjectMemory:
    return ObjectMemory(MemoryParams(**overrides))


def remember(store: ObjectMemory, *, track_id=7, label="car", x=0.4, y=0.5,
             descriptor=RED, now=1000.0) -> None:
    store.remember(
        track_id=track_id,
        label=label,
        box=Box(x, y, 0.1, 0.1),
        velocity=(0.0, 0.0),
        descriptor=descriptor,
        now_millis=now,
    )


def test_a_track_that_went_lost_is_remembered_rather_than_deleted():
    store = memory()
    remember(store)
    assert store.size() == 1
    assert store.identities()[0].track_id == 7


# -- the four gates, each proving a rejection ------------------------------


def test_the_same_object_is_recovered_with_its_own_id():
    store = memory()
    remember(store)
    recovery = store.match(box=Box(0.55, 0.5, 0.1, 0.1), label="car",
                           descriptor=RED, now_millis=5000.0)
    assert recovery is not None
    assert recovery.track_id == 7
    assert recovery.dormant_millis == 4000


def test_appearance_gate_rejects_a_different_looking_object():
    store = memory()
    remember(store)
    assert store.match(box=Box(0.55, 0.5, 0.1, 0.1), label="car",
                       descriptor=BLUE, now_millis=5000.0) is None


def test_label_gate_rejects_a_different_class():
    store = memory()
    remember(store)
    assert store.match(box=Box(0.55, 0.5, 0.1, 0.1), label="person",
                       descriptor=RED, now_millis=5000.0) is None


def test_motion_gate_rejects_an_object_that_could_not_have_got_there():
    # Across the frame in 100ms. "That is on the other side of the frame" is
    # a fact, not a preference, so it is a rejection rather than a low score.
    store = memory()
    remember(store)
    assert store.match(box=Box(0.95, 0.05, 0.1, 0.1), label="car",
                       descriptor=RED, now_millis=1100.0) is None


def test_time_gate_rejects_an_identity_past_the_ttl():
    store = memory(ttl_millis=5_000)
    remember(store)
    assert store.match(box=Box(0.4, 0.5, 0.1, 0.1), label="car",
                       descriptor=RED, now_millis=20_000.0) is None


def test_a_longer_gap_permits_a_longer_journey():
    # The same displacement that the motion gate refuses after 100ms is
    # entirely plausible after four seconds.
    store = memory()
    remember(store)
    assert store.match(box=Box(0.9, 0.5, 0.1, 0.1), label="car",
                       descriptor=RED, now_millis=1100.0) is None
    remember(store)
    assert store.match(box=Box(0.9, 0.5, 0.1, 0.1), label="car",
                       descriptor=RED, now_millis=5000.0) is not None


# -- the score -------------------------------------------------------------


def test_confidence_is_reported_and_falls_as_evidence_weakens():
    store = memory()
    remember(store)
    exact = store.match(box=Box(0.41, 0.5, 0.1, 0.1), label="car",
                        descriptor=RED, now_millis=2000.0)
    approximate = store.match(box=Box(0.41, 0.5, 0.1, 0.1), label="car",
                              descriptor=PINK, now_millis=2000.0)
    assert exact is not None and approximate is not None
    assert 0.0 < approximate.confidence < exact.confidence <= 1.0


def test_a_candidate_with_no_descriptor_is_judged_on_motion_alone():
    # A stream with no appearance engine must still be able to recover an id,
    # just less confidently -- not be locked out of recovery entirely.
    store = memory()
    remember(store)
    recovery = store.match(box=Box(0.42, 0.5, 0.1, 0.1), label="car",
                           descriptor=None, now_millis=2000.0)
    assert recovery is not None
    assert recovery.confidence < 1.0


def test_a_match_below_the_confidence_floor_is_refused():
    store = memory(min_confidence=0.99)
    remember(store)
    assert store.match(box=Box(0.55, 0.5, 0.1, 0.1), label="car",
                       descriptor=PINK, now_millis=5000.0) is None


def test_the_best_of_several_candidates_wins():
    store = memory()
    remember(store, track_id=3, x=0.1, descriptor=BLUE)
    remember(store, track_id=9, x=0.5, descriptor=RED)
    recovery = store.match(box=Box(0.52, 0.5, 0.1, 0.1), label="car",
                           descriptor=RED, now_millis=2000.0)
    assert recovery is not None and recovery.track_id == 9


# -- match_identity: the SAME gates, scoped to ONE requested id (TRACK-
# IDENTITY-PLAN wave L4) -----------------------------------------------------
#
# FOLLOW's re-acquire is a different question from ASSOCIATE's: the operator
# asked for a SPECIFIC track id back, so `match`'s whole-gallery "best
# candidate wins" answer is the wrong one -- a different, better-scoring
# dormant identity winning would silently redirect the lock to an object
# nobody asked to follow. `match_identity` answers "is THIS the one" instead.


def test_match_identity_never_gets_redirected_to_a_better_scoring_identity():
    store = memory()
    remember(store, track_id=3, x=0.5, descriptor=RED)  # the requested one
    remember(store, track_id=9, x=0.52, descriptor=RED)  # a closer, better match
    recovery = store.match_identity(
        3, box=Box(0.52, 0.5, 0.1, 0.1), label="car", descriptor=RED, now_millis=2000.0
    )
    # `match` would answer 9 here (closer centre, same descriptor) -- proven
    # by the sibling test above using the same geometry. `match_identity`
    # must answer only for id 3, or refuse.
    assert recovery is not None
    assert recovery.track_id == 3


def test_match_identity_refuses_an_id_never_remembered():
    store = memory()
    remember(store, track_id=3)
    assert store.match_identity(
        999, box=Box(0.4, 0.5, 0.1, 0.1), label="car", descriptor=RED, now_millis=2000.0
    ) is None


def test_match_identity_applies_the_label_gate():
    store = memory()
    remember(store, track_id=3, label="car")
    assert store.match_identity(
        3, box=Box(0.4, 0.5, 0.1, 0.1), label="bus", descriptor=RED, now_millis=2000.0
    ) is None


def test_match_identity_applies_the_appearance_gate():
    store = memory()
    remember(store, track_id=3, descriptor=RED)
    assert store.match_identity(
        3, box=Box(0.41, 0.5, 0.1, 0.1), label="car", descriptor=BLUE, now_millis=2000.0
    ) is None


def test_match_identity_applies_the_motion_gate():
    store = memory()
    remember(store, track_id=3)
    assert store.match_identity(
        3, box=Box(0.95, 0.05, 0.1, 0.1), label="car", descriptor=RED, now_millis=1100.0
    ) is None


def test_match_identity_applies_the_ttl_gate():
    store = memory(ttl_millis=5_000)
    remember(store, track_id=3)
    assert store.match_identity(
        3, box=Box(0.4, 0.5, 0.1, 0.1), label="car", descriptor=RED, now_millis=20_000.0
    ) is None


def test_match_identity_with_no_descriptor_is_judged_on_motion_alone():
    # FOLLOW never resolves an appearance extractor (`_resolve_appearance_
    # extractor` is reached only from ASSOCIATE's `cost` associator), so
    # `_attempt_follow_recovery` always calls this with `descriptor=None`.
    # `memory.py`'s own neutral-appearance reading (0.5) applies -- never a
    # rejection on that account alone.
    store = memory()
    remember(store, track_id=3)
    recovery = store.match_identity(
        3, box=Box(0.42, 0.5, 0.1, 0.1), label="car", descriptor=None, now_millis=2000.0
    )
    assert recovery is not None
    assert recovery.confidence < 1.0


def test_match_identity_does_not_consume_the_identity():
    store = memory()
    remember(store, track_id=3)
    assert store.match_identity(
        3, box=Box(0.41, 0.5, 0.1, 0.1), label="car", descriptor=RED, now_millis=2000.0
    ) is not None
    assert store.size() == 1  # still there -- only `claim` takes it


# -- lifecycle -------------------------------------------------------------


def test_matching_does_not_consume_the_identity_but_claiming_does():
    store = memory()
    remember(store)
    assert store.match(box=Box(0.45, 0.5, 0.1, 0.1), label="car",
                       descriptor=RED, now_millis=2000.0) is not None
    assert store.size() == 1
    claimed = store.claim(7)
    assert claimed is not None and claimed.recoveries == 1
    assert store.size() == 0


def test_claiming_an_unknown_id_is_not_an_error():
    assert memory().claim(999) is None


def test_re_remembering_refreshes_rather_than_duplicates():
    # A recovered target lost again is the common case behind intermittent
    # cover; it must not accumulate entries.
    store = memory()
    remember(store, now=1000.0)
    remember(store, now=8000.0, x=0.6)
    assert store.size() == 1
    entry = store.identities()[0]
    assert entry.lost_at_millis == 8000.0
    assert entry.box.x == pytest.approx(0.6)


def test_expired_identities_are_dropped():
    store = memory(ttl_millis=5_000)
    remember(store)
    store.forget_expired(20_000.0)
    assert store.size() == 0


def test_a_track_id_of_zero_is_never_remembered():
    # 0 is the wire's untracked sentinel, not an identity.
    store = memory()
    remember(store, track_id=0)
    assert store.size() == 0


# -- bounds ----------------------------------------------------------------


def test_the_gallery_is_capped_and_evicts_the_oldest_loss():
    store = memory(capacity=2)
    remember(store, track_id=1, now=1000.0)
    remember(store, track_id=2, now=2000.0)
    remember(store, track_id=3, now=3000.0)
    assert store.size() == 2
    assert [entry.track_id for entry in store.identities()] == [2, 3]


def test_each_identity_keeps_at_most_gallery_size_descriptors():
    store = memory(gallery_size=2)
    for index, descriptor in enumerate((RED, PINK, BLUE, RED)):
        remember(store, descriptor=descriptor, now=1000.0 * (index + 1))
    assert len(store.identities()[0].gallery) == 2


def test_the_gallery_keeps_the_most_recent_views():
    # Appearance drifts with lighting and aspect, so the newest views are what
    # a re-appearance is most likely to resemble.
    store = memory(gallery_size=1)
    remember(store, descriptor=BLUE, now=1000.0)
    remember(store, descriptor=RED, now=2000.0)
    assert store.identities()[0].gallery == [RED]


def test_retuning_to_a_smaller_capacity_enforces_it_immediately():
    store = memory(capacity=4)
    for track_id in (1, 2, 3, 4):
        remember(store, track_id=track_id, now=1000.0 * track_id)
    store.retune(MemoryParams(capacity=2))
    assert store.size() == 2


def test_the_module_stays_pure_stdlib():
    # The gallery must compare descriptors on a companion computer with no
    # cv extra, and its records must stay serializable for a future
    # per-asset tier.
    import cv_service.tracking.memory as module

    assert not hasattr(module, "np")
    assert not hasattr(module, "cv2")
