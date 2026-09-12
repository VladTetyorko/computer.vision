"""Contributors, an orchestrator, a budget, an aggregator and a ledger.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W0. This package replaces
the hand-written per-frame chain that had grown inside
`cv_service/tracking/session.py` (2 362 lines, R3 deviation D1) with the
shape the plan's §4 describes:

    FrameContext (blackboard)
      |- FrameBudget        who may run this frame, and why not
      |- Orchestrator       order from the declarations, one ledger entry
      |                     per contributor, a raise is FAILED not a dead
      |                     stream
      |- contributors       detect.full - egomotion.* - predict.cv -
      |                     appearance.* - assoc.* - detect.roi -
      |                     memory.gallery - follow.*
      `- Aggregator         the fold: the ONE writer of Track, at most one
                            TrackBook.apply per frame

**Nothing here changes behaviour.** Wave W0 ships with `BASELINE.md`
unchanged and `tests/trackeval/golden/**` byte-identical (plan P7/E15). The
contributors wrap today's code at the boundaries the plan's mapping table
names; where a boundary could not move without changing an outcome, the
logic stayed put and the deviation is recorded in `cv/cv-service/MODULE.md`.

Pure stdlib, like `cv_service/tracking/` -- no `cv2`, no `numpy`, no
`cv_pb2`. `grpc/servicers.py` remains the sole translator to the wire,
including for the ledger.
"""

from __future__ import annotations
