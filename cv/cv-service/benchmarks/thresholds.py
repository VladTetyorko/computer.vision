"""Per-detector confidence thresholds -- MOT17's three `det.txt` variants
are not on the same scale, so one threshold across all of them is
meaningless.

Measured this benchmark's own data (`cv-service/benchmarks/data/mot17/`,
all seven scenes pooled) before picking anything below::

    detector  n       min     max     mean
    DPM       79790   -0.500  4.773   0.520
    FRCNN     67639    0.050  1.000   0.940
    SDP       82787    0.400  1.000   0.967

(MOT17-13 alone -- the scene named in `TRACKING-BENCHMARKS.md`'s own
figures -- reproduces exactly: DPM [-0.500, 3.392] mean 0.135, FRCNN
[0.050, 1.000] mean 0.861, SDP [0.400, 1.000] mean 0.931.)

**Why the defaults below are not simply "some percentile of the above",
and are each derived from what the score IS, not fit to what this run's
numbers happened to be:**

* **DPM emits a raw, unbounded SVM decision-function margin, not a
  probability.** Its own natural decision boundary is the classifier's
  ZERO -- the same way a threshold-free logistic classifier's natural
  cutoff is `p=0.5` -- not a value picked to land at some fraction of this
  particular run's observed range (which would silently re-tune itself
  every time the pooled data changed).
* **FRCNN and SDP emit calibrated, `[0, 1]`-bounded confidence scores.**
  `0.5` -- "the detector itself thinks this is more likely a real object
  than not" -- is the same principled, scale-appropriate default a
  probability-emitting classifier gets absent a reason to pick something
  else; it is not tuned to either detector's own observed mean (0.94/0.97),
  which would keep nearly everything and make the threshold decorative.

Every default is overridable per run (`runner.py`'s `--conf-threshold`) --
these are the documented STARTING points a reader can reproduce, not a
hidden magic number buried in a function body (this project's own
"Overall rules" §1).
"""

from __future__ import annotations

from typing import TypeVar

from benchmarks.mot17 import DETECTORS, DetRow

# DPM: raw SVM margin, unbounded -- 0.0 is the classifier's own decision
# boundary (see module docstring).
DEFAULT_CONF_THRESHOLD_DPM = 0.0
# FRCNN / SDP: calibrated [0, 1] confidence -- 0.5 is "more likely real than
# not" (see module docstring). Same default for both: both detectors emit
# the same kind of score, just with different observed distributions: a
# per-scale, not per-detector-name, choice.
DEFAULT_CONF_THRESHOLD_FRCNN = 0.5
DEFAULT_CONF_THRESHOLD_SDP = 0.5

DEFAULT_CONF_THRESHOLDS: "dict[str, float]" = {
    "DPM": DEFAULT_CONF_THRESHOLD_DPM,
    "FRCNN": DEFAULT_CONF_THRESHOLD_FRCNN,
    "SDP": DEFAULT_CONF_THRESHOLD_SDP,
}

assert set(DEFAULT_CONF_THRESHOLDS) == set(DETECTORS), (
    "DEFAULT_CONF_THRESHOLDS must name exactly mot17.DETECTORS -- a new detector "
    "variant added to one and not the other is a silent gap, not a valid state."
)


def default_threshold(detector: str) -> float:
    """This detector's documented default confidence threshold. Raises
    `ValueError` for an unknown detector name rather than silently
    returning some other detector's number."""
    try:
        return DEFAULT_CONF_THRESHOLDS[detector]
    except KeyError as exc:
        raise ValueError(f"unknown MOT17 detector {detector!r}; expected one of {DETECTORS}") from exc


_DetRowT = TypeVar("_DetRowT", bound=DetRow)


def apply_threshold(det_rows: "list[_DetRowT]", threshold: float) -> "list[_DetRowT]":
    """Every row whose `confidence >= threshold`, order preserved. A plain
    `>=` filter, deliberately: the threshold IS the policy, there is no
    second rule hiding inside this function for a caller to discover later.
    """
    return [row for row in det_rows if row.confidence >= threshold]
