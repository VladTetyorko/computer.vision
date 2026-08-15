"""The identity decision: cost, gates, the two stages, and the solver.

The solver gets a brute-force property test rather than hand-picked cases.
An assignment algorithm is exactly the kind of code that passes every example
someone thought to write and is still wrong on the shape they did not -- and
a subtly suboptimal matcher does not fail, it just quietly swaps two ids
occasionally, which is the hardest possible defect to notice in the field.
"""

from __future__ import annotations

import itertools
import random

import pytest

from cv_service.tracking.assign import (
    FORBIDDEN,
    AssignGates,
    AssignWeights,
    Candidate,
    CostAssociator,
    Target,
    hungarian,
)
from cv_service.tracking.engines.base import METRIC_HELLINGER, Box, Descriptor


def box(x: float, y: float, size: float = 0.1) -> Box:
    return Box(x, y, size, size)


def associator(**overrides) -> CostAssociator:
    weights = overrides.pop("weights", AssignWeights(iou=1.0))
    gates = overrides.pop("gates", AssignGates())
    return CostAssociator(weights=weights, gates=gates)


# -- the solver ------------------------------------------------------------


def brute_force(cost) -> float:
    """Optimal cost by enumeration. The SMALLER dimension is fully matched,
    so a tall matrix must choose which rows participate -- transposing first
    is the least error-prone way to say that."""
    rows, columns = len(cost), len(cost[0])
    if rows > columns:
        cost = [[cost[row][column] for row in range(rows)] for column in range(columns)]
        rows, columns = columns, rows
    best = float("inf")
    for permutation in itertools.permutations(range(columns), rows):
        pairs = list(zip(range(rows), permutation))
        if any(cost[row][column] == FORBIDDEN for row, column in pairs):
            continue
        best = min(best, sum(cost[row][column] for row, column in pairs))
    return best


@pytest.mark.parametrize("seed", [1, 2, 3])
def test_the_solver_is_optimal_on_random_matrices_of_every_shape(seed):
    random.seed(seed)
    for _ in range(150):
        rows = random.randint(1, 5)
        columns = random.randint(1, 5)
        cost = [
            [round(random.uniform(0.0, 3.0), 3) for _ in range(columns)]
            for _ in range(rows)
        ]
        matched = hungarian(cost)
        assert len(matched) == min(rows, columns)
        total = sum(cost[row][column] for row, column in matched)
        assert total == pytest.approx(brute_force(cost))


@pytest.mark.parametrize("seed", [4, 5])
def test_a_forbidden_pair_is_never_returned_however_cheap_the_rest_would_be(seed):
    random.seed(seed)
    for _ in range(150):
        rows = random.randint(1, 5)
        columns = random.randint(1, 5)
        cost = [
            [
                FORBIDDEN if random.random() < 0.35 else round(random.uniform(0.0, 3.0), 3)
                for _ in range(columns)
            ]
            for _ in range(rows)
        ]
        matched = hungarian(cost)
        assert all(cost[row][column] != FORBIDDEN for row, column in matched)
        assert len({row for row, _ in matched}) == len(matched)
        assert len({column for _, column in matched}) == len(matched)


def test_an_all_forbidden_matrix_matches_nothing():
    assert hungarian([[FORBIDDEN, FORBIDDEN], [FORBIDDEN, FORBIDDEN]]) == []


def test_empty_matrices_are_not_an_error():
    assert hungarian([]) == []
    assert hungarian([[]]) == []


def test_the_solver_prefers_a_globally_cheap_assignment_over_two_greedy_picks():
    # Greedy takes (0,0) at 1.0 and is then forced into (1,1) at 9.0 = 10.0;
    # the optimum is (0,1) + (1,0) = 2.0 + 2.0. This is the whole reason a
    # solver is used instead of a nearest-match loop.
    assert sorted(hungarian([[1.0, 2.0], [2.0, 9.0]])) == [(0, 1), (1, 0)]


# -- the cost model --------------------------------------------------------


def test_cost_falls_as_overlap_rises():
    engine = associator()
    candidate = Candidate(key="a", box=box(0.5, 0.5))
    near = engine.cost(candidate, Target(box=box(0.51, 0.5)))
    far = engine.cost(candidate, Target(box=box(0.56, 0.5)))
    assert near < far


def test_the_iou_gate_forbids_rather_than_merely_penalises():
    engine = associator(gates=AssignGates(min_iou=0.3))
    candidate = Candidate(key="a", box=box(0.5, 0.5))
    assert engine.cost(candidate, Target(box=box(0.9, 0.9))) == FORBIDDEN


def test_appearance_is_ignored_entirely_when_it_is_not_weighted():
    # A stream with no appearance engine must behave exactly as pure geometry,
    # not as geometry plus a constant.
    engine = associator(weights=AssignWeights(iou=1.0, appearance=0.0))
    candidate = Candidate(key="a", box=box(0.5, 0.5), descriptor=None)
    target = Target(box=box(0.5, 0.5), descriptor=None)
    assert engine.cost(candidate, target) == pytest.approx(0.0)


def test_a_matching_appearance_beats_a_clashing_one_at_equal_geometry():
    engine = associator(weights=AssignWeights(iou=1.0, appearance=1.0))
    red = Descriptor("hist", (1.0, 0.0, 0.0), METRIC_HELLINGER)
    blue = Descriptor("hist", (0.0, 0.0, 1.0), METRIC_HELLINGER)
    candidate = Candidate(key="a", box=box(0.5, 0.5), descriptor=red)
    same = engine.cost(candidate, Target(box=box(0.5, 0.5), descriptor=red))
    other = engine.cost(candidate, Target(box=box(0.5, 0.5), descriptor=blue))
    assert same < other


def test_a_missing_descriptor_is_neutral_and_not_a_rejection():
    # "No appearance evidence" must not be punished like "appearance says no",
    # or a box the extractor could not describe becomes unmatchable by
    # geometry that is otherwise perfectly good.
    engine = associator(weights=AssignWeights(iou=1.0, appearance=1.0))
    red = Descriptor("hist", (1.0, 0.0, 0.0), METRIC_HELLINGER)
    blue = Descriptor("hist", (0.0, 0.0, 1.0), METRIC_HELLINGER)
    candidate = Candidate(key="a", box=box(0.5, 0.5), descriptor=red)
    absent = engine.cost(candidate, Target(box=box(0.5, 0.5), descriptor=None))
    clashing = engine.cost(candidate, Target(box=box(0.5, 0.5), descriptor=blue))
    matching = engine.cost(candidate, Target(box=box(0.5, 0.5), descriptor=red))
    assert matching < absent < clashing


def test_an_unknown_label_never_contradicts_anything():
    # Composite mode: one object can carry a different label from each model
    # on the same pass. A flipping label is cosmetic; a split track is not.
    engine = associator(weights=AssignWeights(iou=1.0, label=5.0))
    candidate = Candidate(key="a", box=box(0.5, 0.5), label="truck")
    assert engine.cost(candidate, Target(box=box(0.5, 0.5), label="")) == pytest.approx(0.0)
    assert engine.cost(candidate, Target(box=box(0.5, 0.5), label="tank")) > 1.0


# -- the two stages --------------------------------------------------------


def test_a_low_confidence_detection_sustains_a_track_it_could_not_create():
    # ByteTrack's actual insight, preserved: high-confidence targets match
    # first, and what is left is then offered the weak ones.
    engine = associator(gates=AssignGates(high_confidence=0.5))
    candidates = [Candidate(key="strong", box=box(0.2, 0.2)), Candidate(key="fading", box=box(0.7, 0.7))]
    targets = [
        Target(box=box(0.2, 0.2), confidence=0.9, det_index=0),
        Target(box=box(0.7, 0.7), confidence=0.2, det_index=1),
    ]
    assignment = engine.assign(candidates, targets)
    assert assignment.matches == ((0, 0), (1, 1))
    assert assignment.unmatched_candidates == ()
    assert assignment.unmatched_targets == ()


def test_a_high_confidence_target_wins_a_contested_track_over_a_weak_one():
    engine = associator(gates=AssignGates(high_confidence=0.5))
    candidates = [Candidate(key="held", box=box(0.5, 0.5))]
    targets = [
        Target(box=box(0.505, 0.5), confidence=0.1, det_index=0),
        Target(box=box(0.51, 0.5), confidence=0.9, det_index=1),
    ]
    assignment = engine.assign(candidates, targets)
    assert assignment.matches == ((0, 1),)
    assert assignment.unmatched_targets == (0,)


def test_leftovers_are_reported_because_they_are_what_ages_and_what_is_born():
    engine = associator(gates=AssignGates(min_iou=0.3))
    candidates = [Candidate(key="gone", box=box(0.1, 0.1))]
    targets = [Target(box=box(0.8, 0.8), confidence=0.9, det_index=0)]
    assignment = engine.assign(candidates, targets)
    assert assignment.matches == ()
    assert assignment.unmatched_candidates == (0,)
    assert assignment.unmatched_targets == (0,)


def test_no_candidates_or_no_targets_is_not_an_error():
    engine = associator()
    assert engine.assign([], []) == engine.assign([], [])
    empty_targets = engine.assign([Candidate(key="a", box=box(0.5, 0.5))], [])
    assert empty_targets.unmatched_candidates == (0,)
    empty_candidates = engine.assign([], [Target(box=box(0.5, 0.5))])
    assert empty_candidates.unmatched_targets == (0,)


def test_the_module_stays_pure_stdlib():
    # `lap`/`scipy` would each break the rule that keeps the identity core
    # importable, and therefore testable, with no `cv` extra at all.
    import cv_service.tracking.assign as module

    assert not hasattr(module, "np")
    assert not hasattr(module, "cv2")
