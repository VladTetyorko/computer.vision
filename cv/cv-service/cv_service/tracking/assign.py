"""The identity decision: a gated cost matrix and the assignment that solves it.

`docs/plans/active/TRACKING-V2-PLAN.md` §3.3, `docs/conclusions/TRACKING-REVIEW.md` §4.3.

**The inversion this file exists to perform.** Before it, `ByteTrackEngine`
held the tracks, the motion model and the matching, and `TrackBook` ran
downstream merely renaming what it was handed. Every improvement the operator
asked for -- appearance, ego-motion, memory -- is an *input to the matching
decision*, and there was no seam to inject one. Here the platform owns the
decision and the engines are demoted to evidence: a compensator supplies the
warp, an extractor supplies the descriptors, and this module decides who is
who. ByteTrack stays available and unchanged as the no-appearance,
no-compensation baseline.

**Two stages, because ByteTrack's actual insight is worth keeping.**
High-confidence detections match first; whatever is still unmatched is then
offered the low-confidence ones. A fading detection can therefore *sustain*
a track it could not have *created* -- which is most of what makes ByteTrack
better than a naive IoU tracker, and it is orthogonal to where the tracks
are held.

**Pure stdlib, including the solver.** `lap` and `scipy` would each violate
the package rule that keeps `tracking/` importable with no `cv` extra (and
therefore testable with no frames), so `hungarian` below is written out. At
the tens-of-boxes scale a frame actually carries, an O(n^3) Python solve is
far below the per-frame budget the detector already spends.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Sequence

from cv_service.tracking.engines.base import Box, Descriptor

# Returned by the cost function for a pair that must never be matched. Kept
# as a real infinity in the public vocabulary because that is what it means;
# `hungarian` substitutes a finite stand-in internally, since the assignment
# arithmetic cannot carry an infinity through its potentials.
FORBIDDEN = float("inf")

# Labels that should not block a match despite differing. Composite mode runs
# more than one model per frame, so one physical object can legitimately
# arrive as `orion12l:tank` and `yolo11n:truck` on the same pass -- charging
# that a full label penalty would split it into two tracks, which is exactly
# the outcome TRACKING-PLAN R9 predicted and accepted.
#
# R9's blanket "a flipping label is cosmetic -- do not special-case" is
# OVERTURNED for the open-vocabulary (prompt-free, ~4585-class) path by
# `docs/plans/active/TRACK-IDENTITY-PLAN.md` -- there the flip is semantic
# noise, not a naming variant across composite-mode models, and wave L1
# (`track.py`'s per-track label election) is the special-casing R9 declined,
# now owner-ordered. This composite-prefix tolerance stands unchanged; the
# `Candidate.label` this function now compares is the ELECTED label
# (`session.py`'s `_run_cost_associate`), not the raw per-frame one.
_UNKNOWN_LABEL = ""


@dataclass(frozen=True)
class Candidate:
    """One live track offered to the matcher, ALREADY predicted and warped.

    The box is where the track is *expected to be on this frame* -- constant
    velocity from its last confirmation, then warped by the ego-motion
    transform. Doing that before the matcher rather than inside it is what
    keeps this module free of both a clock and a motion model.
    """

    key: object
    box: Box
    label: str = _UNKNOWN_LABEL
    descriptor: Optional[Descriptor] = None
    confirmed: bool = False


@dataclass(frozen=True)
class Target:
    """One detection on this frame offered to the matcher."""

    box: Box
    label: str = _UNKNOWN_LABEL
    confidence: float = 0.0
    descriptor: Optional[Descriptor] = None
    det_index: int = -1


@dataclass(frozen=True)
class Assignment:
    """Who matched whom, and what was left over.

    The leftovers are as load-bearing as the matches: unmatched candidates
    are what age toward LOST and eventually into memory, and unmatched
    targets are what get tested against memory before being born as new ids.
    """

    matches: tuple[tuple[int, int], ...] = ()
    unmatched_candidates: tuple[int, ...] = ()
    unmatched_targets: tuple[int, ...] = ()


@dataclass(frozen=True)
class AssignWeights:
    """How much each kind of evidence counts toward one identity decision.

    Not literals at a call site: these are resolved configuration, and the
    only reason they carry defaults here is so a caller with no appearance
    engine gets pure-geometry behavior without having to state it.
    """

    iou: float = 1.0
    appearance: float = 0.0
    label: float = 0.0


@dataclass(frozen=True)
class AssignGates:
    """Hard rejections applied before cost is ever compared.

    A gate is not a weight. Cost ranks plausible matches against each other;
    a gate says a pair is *not a candidate at all*, which is what stops the
    solver from producing a globally-cheap assignment made of individually
    absurd pairs -- the failure mode a pure cost matrix with no gating has.
    """

    min_iou: float = 0.0
    max_appearance: float = 1.0
    max_cost: float = FORBIDDEN
    high_confidence: float = 0.0


class CostAssociator:
    """The platform's own matcher. One instance per stream, stateless per call."""

    engine_id = "cost"

    def __init__(self, *, weights: AssignWeights, gates: AssignGates) -> None:
        self._weights = weights
        self._gates = gates

    @property
    def weights(self) -> AssignWeights:
        return self._weights

    @property
    def gates(self) -> AssignGates:
        return self._gates

    def retune(self, *, weights: AssignWeights, gates: AssignGates) -> None:
        """Adopt newly-resolved configuration. On config change only."""
        self._weights = weights
        self._gates = gates

    def cost(self, candidate: Candidate, target: Target) -> float:
        """Cost of calling `target` the same object as `candidate`.

        THE one place the identity cost is defined. Returns `FORBIDDEN` for
        any pair a gate rejects, so a caller can read the matrix directly and
        see the same refusals the solver will.
        """
        overlap = candidate.box.iou(target.box)
        if overlap < self._gates.min_iou:
            return FORBIDDEN

        distance = 1.0
        if self._weights.appearance > 0.0:
            distance = _appearance_distance(candidate.descriptor, target.descriptor)
            if distance > self._gates.max_appearance:
                return FORBIDDEN

        total = self._weights.iou * (1.0 - overlap)
        if self._weights.appearance > 0.0:
            total += self._weights.appearance * distance
        if self._weights.label > 0.0 and not _labels_compatible(candidate.label, target.label):
            total += self._weights.label
        if total > self._gates.max_cost:
            return FORBIDDEN
        return total

    def assign(
        self, candidates: Sequence[Candidate], targets: Sequence[Target]
    ) -> Assignment:
        """Match `candidates` to `targets` in two confidence stages."""
        if not candidates or not targets:
            return Assignment(
                unmatched_candidates=tuple(range(len(candidates))),
                unmatched_targets=tuple(range(len(targets))),
            )

        high = [
            index
            for index, target in enumerate(targets)
            if target.confidence >= self._gates.high_confidence
        ]
        low = [
            index
            for index, target in enumerate(targets)
            if target.confidence < self._gates.high_confidence
        ]

        open_candidates = list(range(len(candidates)))
        matches: list[tuple[int, int]] = []

        for stage in (high, low):
            if not stage or not open_candidates:
                continue
            found = self._solve(candidates, targets, open_candidates, stage)
            matches.extend(found)
            matched = {candidate_index for candidate_index, _ in found}
            open_candidates = [index for index in open_candidates if index not in matched]
            stage_matched = {target_index for _, target_index in found}
            stage[:] = [index for index in stage if index not in stage_matched]

        matched_targets = {target_index for _, target_index in matches}
        return Assignment(
            matches=tuple(sorted(matches)),
            unmatched_candidates=tuple(sorted(open_candidates)),
            unmatched_targets=tuple(
                index for index in range(len(targets)) if index not in matched_targets
            ),
        )

    def _solve(
        self,
        candidates: Sequence[Candidate],
        targets: Sequence[Target],
        candidate_indices: Sequence[int],
        target_indices: Sequence[int],
    ) -> list[tuple[int, int]]:
        matrix = [
            [self.cost(candidates[row], targets[column]) for column in target_indices]
            for row in candidate_indices
        ]
        return [
            (candidate_indices[row], target_indices[column])
            for row, column in hungarian(matrix)
        ]


def _labels_compatible(left: str, right: str) -> bool:
    """An unknown label never contradicts anything.

    Composite mode is why: the same object can carry a different label from
    each model on one pass, and a track whose label flips is a cosmetic
    outcome, while a track that splits in two is a real defect.
    """
    if not left or not right:
        return True
    return left == right


def _appearance_distance(
    left: Optional[Descriptor], right: Optional[Descriptor]
) -> float:
    """Distance between two optional descriptors, on [0, 1].

    A missing descriptor scores the neutral midpoint rather than the maximum:
    "no appearance evidence" must not be punished like "appearance says no",
    or a box the extractor could not describe would be unmatchable by
    geometry that is otherwise perfectly good.
    """
    if left is None or right is None:
        return 0.5
    return left.distance(right)


def hungarian(cost: Sequence[Sequence[float]]) -> list[tuple[int, int]]:
    """Minimum-cost assignment over a rectangular matrix. Pure stdlib.

    Returns `(row, column)` pairs. Pairs whose cost is `FORBIDDEN` are never
    returned: the solver works over a finite stand-in so its potentials stay
    arithmetic, and forbidden pairs are dropped from the result afterwards.
    A row or column may therefore go unassigned even when the matrix is
    square, which is the correct outcome -- a forbidden match is worse than
    no match, not merely expensive.

    Shortest-augmenting-path with potentials (the standard O(n^3)
    formulation), transposing when there are more rows than columns because
    the recurrence requires rows <= columns.
    """
    rows = len(cost)
    if rows == 0:
        return []
    columns = len(cost[0])
    if columns == 0:
        return []

    if rows > columns:
        transposed = [[cost[row][column] for row in range(rows)] for column in range(columns)]
        return [(row, column) for column, row in hungarian(transposed)]

    big = _finite_stand_in(cost)
    matrix = [
        [big if value == FORBIDDEN else float(value) for value in row]
        for row in cost
    ]

    row_potential = [0.0] * (rows + 1)
    column_potential = [0.0] * (columns + 1)
    # `assigned[column]` is the 1-based row currently holding `column`.
    assigned = [0] * (columns + 1)
    previous = [0] * (columns + 1)

    for row in range(1, rows + 1):
        assigned[0] = row
        column = 0
        minimum = [float("inf")] * (columns + 1)
        used = [False] * (columns + 1)
        while True:
            used[column] = True
            current_row = assigned[column]
            delta = float("inf")
            next_column = 0
            for candidate in range(1, columns + 1):
                if used[candidate]:
                    continue
                reduced = (
                    matrix[current_row - 1][candidate - 1]
                    - row_potential[current_row]
                    - column_potential[candidate]
                )
                if reduced < minimum[candidate]:
                    minimum[candidate] = reduced
                    previous[candidate] = column
                if minimum[candidate] < delta:
                    delta = minimum[candidate]
                    next_column = candidate
            for candidate in range(columns + 1):
                if used[candidate]:
                    row_potential[assigned[candidate]] += delta
                    column_potential[candidate] -= delta
                else:
                    minimum[candidate] -= delta
            column = next_column
            if assigned[column] == 0:
                break
        while column:
            moved = previous[column]
            assigned[column] = assigned[moved]
            column = moved

    return [
        (assigned[column] - 1, column - 1)
        for column in range(1, columns + 1)
        if assigned[column] > 0 and cost[assigned[column] - 1][column - 1] != FORBIDDEN
    ]


def _finite_stand_in(cost: Sequence[Sequence[float]]) -> float:
    """A cost strictly worse than any real pair, but finite.

    Scaled off the largest real cost present rather than a fixed constant, so
    it stays "worse than everything" for any weighting an operator configures
    without ever being large enough to lose float precision against the small
    reduced costs the potentials carry.
    """
    real = [
        float(value)
        for row in cost
        for value in row
        if value != FORBIDDEN
    ]
    largest = max(real) if real else 1.0
    return (abs(largest) + 1.0) * (len(cost) + 1) * 2.0
