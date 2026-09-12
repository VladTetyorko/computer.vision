"""`Key` -- the closed set of blackboard slots one frame can carry.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1, rule 1: an enum rather
than string keys, because a typo in a string key is a silent missing input
where a typo here is an `AttributeError` at import. The set is CLOSED: a new
kind of evidence is a new member reviewed against §4.1's table, never an
ad-hoc string a contributor invents.

Rule 2 -- **a key has exactly one writer per configuration** -- is enforced
by `Orchestrator` at build time, which is what makes "one owner per
responsibility" (the plan's A2) structural rather than aspirational.

Three keys are SEEDED by the session at frame start rather than written by a
contributor (`FRAME`, `POSE`, `LOCK`): they are the frame's given facts, not
anybody's contribution, so declaring them as writes would make the
one-writer rule fight the session itself. `TRACKS_PREV` is deliberately NOT
one of them -- the book's tracks are only correct to read AFTER ego-motion
has warped them, so `predict.cv` owns that snapshot and the ordering is
structural instead of a comment.
"""

from __future__ import annotations

from enum import Enum


class Key(Enum):
    """A blackboard slot. Values are the wire/ledger spelling."""

    #: The memoized frame loader the servicer supplies (`FrameFn`). Seeded.
    FRAME = "frame"
    #: This frame's `CameraPose`, `CameraPose()` when the wire carried none. Seeded.
    POSE = "pose"
    #: `LockArbiter` state for this frame (`lock.target`, bound id). Seeded.
    LOCK = "lock"

    #: `list[Track]` -- the book's live tracks, snapshotted post-warp.
    TRACKS_PREV = "tracks_prev"
    #: `list[Box]` -- constant-velocity box per track, positional against
    #: `TRACKS_PREV`; the two are written together and never separately.
    PREDICTIONS = "predictions"
    #: `Transform` -- this frame's ego-motion, `IDENTITY` when uncompensated.
    TRANSFORM = "transform"
    #: `list` of detector detections, or `None` for "no model resolved".
    DETECTIONS = "detections"
    #: The ROI rescue's own second-pass result, or `None`.
    DETECTIONS_ROI = "detections_roi"
    #: `list[Optional[Descriptor]]`, positional against `DETECTIONS`.
    DESCRIPTORS = "descriptors"
    #: The associator's `Assignment` plus the candidates/targets it ranked.
    ASSIGNMENT = "assignment"
    #: `dict[int, tuple[RecoveredIdentity, Recovery]]` keyed by detection index.
    RECOVERIES = "recoveries"
    #: The FOLLOW path's proposal for this frame.
    FOLLOW_OBS = "follow_obs"
    #: Late/ORU box corrections applied this frame (see the package docstring).
    CORRECTIONS = "corrections"
    #: The `Proposal` the aggregator folds -- the one terminal key.
    OBSERVATIONS = "observations"


#: Keys the session puts on the blackboard itself; never declared as a
#: contributor `writes` (see the module docstring).
SEEDED: "frozenset[Key]" = frozenset({Key.FRAME, Key.POSE, Key.LOCK})
