"""Wave C5c acceptance: `small_target` -- the scenario built specifically to
isolate REVIEW §4.6 ("detection recall -- the other half of the complaint").
An object at 0.035 of the frame against `SMALL_TARGET_RELIABLE_SIZE=0.10`
means a full-frame pass misses it most of the time; ROI re-detection crops
around the predicted box, magnifying the same object comfortably above the
reliable threshold.

Uses the REAL harness (`tools/trackeval`) directly, exactly like
`test_cost_appearance_integration.py`'s own wave C3 acceptance test --
`run_replay` is called with an explicit `settings=` (not through the CLI,
which reads `Settings.from_env()` and would need the env var actually set)
so this test genuinely exercises `CV_TRACK_ROI_ENABLED`/
`CV_TRACK_ROI_CROP_FACTOR` without depending on the process environment.

Needs `cv2`/`numpy` -- the harness's own frame rendering
(`tools/trackeval/sequences.py`) needs them; skips rather than fails when the
`cv` extra is absent, same contract this package's other harness-integration
test already uses.
"""

from __future__ import annotations

import dataclasses

import pytest

pytest.importorskip("cv2")
pytest.importorskip("numpy")

from cv_service.config import Settings
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval import metrics as metrics_module
from tools.trackeval import replay as replay_module
from tools.trackeval.__main__ import DEFAULT_NOISE_BY_SCENARIO
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS


def _run(scenario: str, mode: str, *, roi_enabled: bool) -> metrics_module.Metrics:
    sequence = SCENARIOS[scenario](DEFAULT_SEED)
    settings = dataclasses.replace(Settings(), track_roi_enabled=roi_enabled)
    result = replay_module.run_replay(
        sequence,
        mode=mode,
        engine_id="",  # deployment default for the mode -- `cost` for ASSOCIATE
        detector_config=DEFAULT_NOISE_BY_SCENARIO.get(scenario, replay_module.DetectorNoiseConfig()),
        settings=settings,
    )
    return metrics_module.compute(result)


# `mean_tracker_millis`/`p95_tracker_millis` are real `time.perf_counter()`
# wall-clock measurements (`metrics.py`'s own docstring: "a COST number...
# real wall-clock noise on it is expected"), never claimed reproducible run
# to run -- excluded here so an "unchanged" assertion pins the ACCURACY
# columns (the only ones a `roi_enabled` flip could legitimately move for a
# scenario that never triggers a rescue) without becoming a timing flake.
_NOISY_TIMING_FIELDS = ("mean_tracker_millis", "p95_tracker_millis")


def _accuracy_fields(metrics: metrics_module.Metrics) -> dict:
    return {
        field.name: getattr(metrics, field.name)
        for field in dataclasses.fields(metrics)
        if field.name not in _NOISY_TIMING_FIELDS
    }


def test_roi_disabled_reproduces_the_baseline_small_target_fragmentation():
    # The pre-wave baseline, reproduced (`cv-service/MODULE.md`'s own
    # BASELINE table): `small_target | ASSOCIATE | cost` fragments badly
    # with no ROI pass -- confirms this test is actually measuring the
    # defect, and confirms the shipped default (`CV_TRACK_ROI_ENABLED`
    # unset) leaves it exactly as bad as before this wave existed.
    metrics = _run("small_target", MODE_ASSOCIATE, roi_enabled=False)

    assert metrics.idsw == 0  # never an identity defect -- this is recall
    assert metrics.fragmentations >= 15
    assert metrics.mostly_tracked == 0


def test_roi_enabled_recovers_small_target_coverage_without_new_id_switches():
    # THE acceptance test: a crop around the predicted box lifts the SAME
    # 0.035-of-frame object comfortably above `reliable_size=0.10` most of
    # the time it would otherwise be missed -- fragmentation drops
    # materially and the object becomes MOSTLY_TRACKED instead of merely
    # PARTIALLY_TRACKED, with no identity cost (still zero id switches: a
    # rescue only ever re-anchors the SAME candidate under its own key).
    metrics = _run("small_target", MODE_ASSOCIATE, roi_enabled=True)

    assert metrics.idsw == 0
    assert metrics.fragmentations <= 5
    assert metrics.mostly_tracked == 1


def test_roi_enabled_leaves_small_target_follow_unchanged():
    # FOLLOW's own single-object tracker already interpolates through
    # `small_target`'s detector misses (MODULE.md's own baseline: FOLLOW is
    # `mostly_tracked=1` even with ROI OFF) -- ROI re-detection is wired
    # into `cost`-associate ASSOCIATE only (`session.py`'s own scope
    # decision), so FOLLOW's row must be byte-for-byte identical whether or
    # not the deployment opts in.
    off = _run("small_target", MODE_FOLLOW, roi_enabled=False)
    on = _run("small_target", MODE_FOLLOW, roi_enabled=True)

    assert _accuracy_fields(off) == _accuracy_fields(on)


@pytest.mark.parametrize(
    "scenario",
    ["linear", "crossing", "occlusion", "pan", "pan_step", "long_occlusion", "crowd_recall"],
)
def test_roi_enabled_leaves_easy_and_unrelated_scenarios_unchanged(scenario):
    # "Every other scenario must be unchanged: none of them opts into the
    # recall model, so a ROI pass should rarely or never trigger there."
    # None of these seven exercise `DetectorNoiseConfig.reliable_size`, so a
    # rescue attempt -- and measured directly, one DOES fire on five of the
    # seven (`occlusion`/`pan`/`pan_step`/`long_occlusion`/`crowd_recall`,
    # every frame their book holds a confirmed-but-genuinely-occluded or
    # still-drifting-prediction candidate) -- never finds a match worth
    # merging: an object that is truly occluded is invisible to a crop
    # around it exactly as it is to the full frame, and the SAME reasoning
    # applies to a prediction that has drifted off the true position. So
    # every accuracy column stays byte-for-byte identical even though real
    # (wasted) detector cost is paid on some of these -- see
    # `test_roi_costs_extra_detector_passes_even_when_nothing_is_recovered`
    # below for that honestly-reported trade-off. (`clutter` and `dropout`
    # are the two documented EXCEPTIONS where a rescue does find something --
    # see the two tests below.)
    off = _run(scenario, MODE_ASSOCIATE, roi_enabled=False)
    on = _run(scenario, MODE_ASSOCIATE, roi_enabled=True)

    assert _accuracy_fields(off) == _accuracy_fields(on)


def test_roi_enabled_improves_dropout_incidentally():
    # `dropout`'s failure mode is a UNIFORM random per-pass detector miss,
    # independent of apparent size (`reliable_size=0` for this scenario,
    # `DEFAULT_NOISE_BY_SCENARIO`) -- ROI re-detection still helps here,
    # incidentally: the roi pass draws its OWN independent miss roll at the
    # same probability, so a frame the full-frame pass missed still has a
    # real chance of being recovered. An honest, reported finding, not
    # silently folded into the "unchanged" parametrization above.
    off = _run("dropout", MODE_ASSOCIATE, roi_enabled=False)
    on = _run("dropout", MODE_ASSOCIATE, roi_enabled=True)

    assert off.idsw == on.idsw == 0
    assert on.fragmentations < off.fragmentations


def test_roi_re_detection_does_not_cost_identities_in_a_dense_scene():
    """The regression this rescue's own IoU gate exists to prevent.

    `clutter`'s ten closely-spaced objects, half sharing a colour, are the
    hard case for a crop several times a candidate's own size: in a crowd it
    can admit a NEIGHBOUR into contention. When the rescue reused the
    primary match's gate -- which C3 left permissive on purpose, because a
    full-frame match has the whole scene competing to explain each box --
    that neighbour won the slot and enabling ROI cost six id switches.

    A rescue is a different question from a match, and it gets a different
    gate: the crop is taken BECAUSE the object was predicted there, so a
    genuine rescue overlaps the prediction well and an interloper does not
    (`CV_TRACK_ROI_MIN_IOU`). Both arms must be clean, and the small-target
    win must survive it -- a gate tightened until nothing is ever rescued
    would also pass the first assertion.
    """
    off = _run("clutter", MODE_ASSOCIATE, roi_enabled=False)
    on = _run("clutter", MODE_ASSOCIATE, roi_enabled=True)

    assert off.idsw == 0
    assert on.idsw == 0
    assert on.mostly_tracked == off.mostly_tracked

    rescued = _run("small_target", MODE_ASSOCIATE, roi_enabled=True)
    unrescued = _run("small_target", MODE_ASSOCIATE, roi_enabled=False)
    assert rescued.fragmentations < unrescued.fragmentations


def test_roi_costs_extra_detector_passes_even_when_nothing_is_recovered():
    # The other half of an honest cost report: `occlusion` never recovers
    # anything via ROI (the object is genuinely invisible, not merely
    # small -- see the parametrized test above), but the pass still runs --
    # `detector_roi` fires on some frames -- and is still a real,
    # `InferenceGate`-bounded detector call the deployment pays for. The
    # harness's own `det/s` column is frame-based and cannot show this (ASSOCIATE
    # already reports 100% of frames as `detector_ran`, roi or not -- a
    # known harness limitation, `tools/` is out of scope for this task and
    # was not modified), so this test counts `detector_roi` directly instead.
    sequence = SCENARIOS["occlusion"](DEFAULT_SEED)
    settings = dataclasses.replace(Settings(), track_roi_enabled=True)
    result = replay_module.run_replay(
        sequence,
        mode=MODE_ASSOCIATE,
        engine_id="",
        detector_config=DEFAULT_NOISE_BY_SCENARIO.get("occlusion", replay_module.DetectorNoiseConfig()),
        settings=settings,
    )

    roi_frames = sum(1 for outcome in result.outcomes if outcome.detector_roi)

    assert roi_frames > 0  # cost WAS paid --
    assert metrics_module.compute(result).idsw == 0  # -- and bought literally nothing here
