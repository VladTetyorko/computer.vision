"""Capture-time anchoring for pulled streams (MEDIA-SOT-PLAN §6).

``DetectionResult.capturedAt``/``DetectionResponse.timestamp_millis`` is
load-bearing on the Java side (``DetectionExtrapolator`` server-side,
``selectDetectionResult`` client-side) -- under pull, this worker mints it,
because there is no JVM in the frame path anymore to stamp arrival time
against a request it just sent.

**Why anchoring, not raw PTS.** The pulled stream gives presentation
timestamps (``cv2.CAP_PROP_POS_MSEC``, stream-relative elapsed time since
open), not an absolute wallclock -- RTCP sender reports carry that mapping
when the camera sends them, but ``cv2.VideoCapture`` exposes no RTCP
sender-report mapping (confirmed, docs/conclusions/CV-PULL-SPIKE.md §4). So::

    capturedAt = anchorWallclock + (pts - anchorPts)

taking the anchor at open, and re-anchoring whenever measured skew exceeds a
threshold.

**Why re-anchoring is keyed on skew, not elapsed time.** At the anchor frame,
by construction, ``capturedAt`` estimate == ``anchorWallclock`` == the
wallclock reading taken for that same frame, so skew (arrival − estimate) is
exactly 0 there. For every later frame, assuming the PTS clock advances at
the same rate as wallclock, skew converges to (and stays near) the
transit+decode latency *at the time of anchoring* -- so skew's own drift away
from that steady value is a direct measurement of "how much has jitter/clock
drift moved since the last anchor", not of raw network latency (which would
false-trigger on every anchor, since real transit latency is never exactly
zero). Re-anchoring on that quantity crossing a threshold recalibrates
against the CURRENT latency baseline rather than the one that held minutes
ago.

M0 measured ``anchor`` (never re-anchored, so the raw drift could be
observed) at ±15 ms typical spread over 10 minutes, worst spike ~80 ms --
inside the plan's 100 ms/10-min gate (docs/conclusions/CV-PULL-SPIKE.md §4,
§7). That measurement is a floor, not a ceiling: the synthetic source shares
a host clock with the puller, so it could not exercise a genuine
camera-oscillator drift. ``CV_PULL_CLOCK_MODE=anchor`` is nonetheless the
chosen default (M0's GO decision); ``arrival`` remains selectable via the
same knob for a deployment that would rather report *receipt* wallclock
(biased by jitter-buffer latency, but with a trivially-zero, well-understood
error term) than a PTS-derived estimate.
"""

from __future__ import annotations

from typing import Optional

# M0's chosen default (docs/conclusions/CV-PULL-SPIKE.md §7) and its named
# fallback -- the only two modes this worker implements. `ffmpeg_wallclock`,
# named in MEDIA-SOT-PLAN §5.5's original table, was DROPPED by M0's own
# contract correction (§4/§6 of the spike write-up): `-use_wallclock_as_
# timestamps 1` is a measured no-op against an RTSP source (the demuxer
# already supplies real RTP-derived timestamps), so a decoder built "under
# that label" would silently just be `arrival` again, via a different
# decoder. Implementing a third mode that collapses onto the second would be
# dead code with a misleading name, so it is not implemented here.
ANCHOR = "anchor"
ARRIVAL = "arrival"
_MODES = (ANCHOR, ARRIVAL)

# Re-anchor threshold: no number is pinned by MEDIA-SOT-PLAN §5.1/§5.5 (only
# "re-anchoring when measured skew exceeds A THRESHOLD" -- §6). 100ms is
# chosen here because it is the same number the plan's own drift GATE is
# expressed in (§6's "100 ms/10 min gate") -- reusing it as the per-event
# trigger means a worker never lets its own estimate wander further from
# the current baseline than the budget the whole feature is judged against,
# without inventing an unrelated second constant. Configurable
# (CV_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS, cv_service/config.py) per rule 1
# (no un-configurable magic numbers) -- this is not a frozen wire value.
DEFAULT_REANCHOR_THRESHOLD_MILLIS = 100.0


class CaptureClock:
    """One instance per pulled stream (``DetectPulled`` call). Not thread-safe
    -- ``loop.py``'s consumer thread is the only caller, exactly one call to
    :meth:`capture_time` per served frame.
    """

    def __init__(self, *, mode: str = ANCHOR, reanchor_threshold_millis: float = DEFAULT_REANCHOR_THRESHOLD_MILLIS) -> None:
        if mode not in _MODES:
            raise ValueError(f"unknown capture clock mode {mode!r}; expected one of {_MODES}")
        self._mode = mode
        self._reanchor_threshold_millis = reanchor_threshold_millis
        self._anchor_wall_millis: Optional[float] = None
        self._anchor_pts_millis: Optional[float] = None

    def capture_time(self, *, pts_millis: float, now_wall_millis: float) -> tuple[int, int]:
        """One call per frame. ``now_wall_millis`` must be read by the CALLER
        exactly once (``time.time() * 1000.0``, read alongside -- never
        instead of -- the monotonic clock read the deadline sampler already
        takes for that same frame; the "one clock read per frame" discipline
        is about never reading the SAME clock twice while deciding one
        frame's fate, not about touching only one clock in the whole loop).

        Returns ``(captured_at_millis, capture_skew_millis)``. In ``arrival``
        mode, or on the very first frame / a re-anchor, skew is reported as
        ``0`` -- by construction there is nothing to estimate yet (§6's
        "0 = unknown"), not a measurement claiming zero drift.
        """
        if self._mode == ARRIVAL:
            return round(now_wall_millis), 0

        if self._anchor_wall_millis is not None:
            captured_at = self._anchor_wall_millis + (pts_millis - self._anchor_pts_millis)
            skew = now_wall_millis - captured_at
            if abs(skew) <= self._reanchor_threshold_millis:
                return round(captured_at), round(skew)
            # Falls through to re-anchor below -- skew has drifted past the
            # threshold since the last anchor, so this frame becomes the new
            # reference instead of reporting a stale, over-budget estimate.

        self._anchor_wall_millis = now_wall_millis
        self._anchor_pts_millis = pts_millis
        return round(now_wall_millis), 0

    @property
    def mode(self) -> str:
        return self._mode
