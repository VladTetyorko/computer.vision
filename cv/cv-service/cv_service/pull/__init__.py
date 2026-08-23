"""MEDIA-SOT-PLAN wave M3 -- the cv-service pull worker.

Three seams (docs/plans/done/MEDIA-SOT-PLAN.md §8 M3, D7):

* ``source.py`` -- the ``PullSource`` protocol + the M0-chosen ``opencv``
  backend (``cv2.VideoCapture(url, cv2.CAP_FFMPEG)``,
  docs/conclusions/CV-PULL-SPIKE.md §2/§7). A Pi's ``v4l2m2m`` hardware
  decoder or the GB4005's VAAPI path is a later swap behind this same
  protocol, not a rewrite of the decode loop.
* ``clock.py`` -- anchored capture time (§6): ``capturedAt = anchorWallclock
  + (pts - anchorPts)``, re-anchored when measured skew exceeds a threshold.
* ``loop.py`` -- the decode loop: a background reader thread pulling frames
  as fast as the source produces them into a latest-wins mailbox (D8), paired
  with the deadline sampler ported from ``spikes/pull/sampler.py`` (itself a
  port of ``StreamPipeline.sampleDue``/``armScheduleAt``,
  feat/cv-rate-control) that decides which of those frames actually get
  inferred.

Pure stdlib except where a module says otherwise (``source.py`` needs
``cv2``/``numpy`` -- lazily imported, same "importable without the `cv`
extra" discipline every other cv-service module keeps). None of these three
modules import ``cv_pb2`` -- ``cv_service/grpc/servicers.py``'s
``DetectPulled`` handler is the sole translation point, same rule
``inference/``/``tracking/`` already follow.
"""

from __future__ import annotations
