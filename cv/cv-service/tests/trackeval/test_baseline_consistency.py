"""`tools/trackeval/BASELINE.md` -- self-verification against a fresh harness run.

`BASELINE.md` is maintained BY HAND (see its own opening paragraph) and has
drifted from what the code actually produces twice: a stale "Eight
scenarios" heading that survived long after a third named group of
scenarios existed, and worse, a `crossing_similar` row that once recorded
`IDSW=2, FM=0` when the code has always produced `IDSW=0, FM=2` -- a wrong
number that made a real wave's own acceptance criterion ("keeps both ids
where V0 shows a swap") unmeetable, caught only because a human happened to
re-run the harness by hand. `docs/plans/active/TRACKING-V2-PLAN.md` §5b
already records five earlier defects in this harness's OWN measurement
apparatus (never the tracker) before that sixth one; this file exists so a
SEVENTH cannot ship into a wave unnoticed.

**Which columns are asserted, and why not `trk_ms_*`.** `metrics.py`'s own
module docstring draws the line already: "`trk_ms_avg`/`trk_ms_p95` are
rounded to `~N` here on purpose -- they are real `time.perf_counter()`
wall-clock noise ... Every other column is exact and deterministic given
the seed." This file follows that line exactly -- `_SKIPPED_COLUMNS` below
is only those two. Everything else is asserted, including `det/s`, which
LOOKS timing-related but is actually `detector_passes / (frames / fps)`, a
ratio of two exact integers (`metrics.compute`), not a wall-clock
measurement, and `recov%`, which is skipped for a different reason: it is
`recovered_count / gap_count` restated as a percentage, so it can never
disagree with the baseline unless `recov` or `gaps` already does -- both of
which ARE asserted directly, as exact integers.

**Float tolerance: reuse `render_table`'s own rounding, not a hand-picked
epsilon.** `BASELINE.md`'s table is a `render_table()` printout, so every
float cell it records already lost precision to `_row_cells`'s `:.1f`/
`:.3f` formatting before it was ever pasted into the file -- there is no
"exact" baseline value to diff a fresh float against, only a rounded one.
The first version of this comparison used a hand-computed tolerance
(`0.5 * 10 ** -decimals`, "half the display quantum") and it produced a
FALSE failure the moment it ran: `pan`/ASSOCIATE's `life_mean` is exactly
`24.75`, `f"{24.75:.1f}"` is `"24.8"` (Python's round-half-to-even), but
`24.8 - 24.75` as floats is `0.05000000000000071`, a hair over the
hand-computed `0.05` tolerance purely from binary float representation
error -- a real value, correctly rounded, flagged as drifted. Formatting
the fresh float with the SAME `f"...:.{decimals}f"` spec `_row_cells` used
to write the recorded digit and comparing the resulting strings sidesteps
that class of bug entirely: it asks "would this fresh value have printed
as what's on the page", the only question a hand-maintained, pre-rounded
table can actually answer, rather than reimplementing its own rounding
rule via subtraction and re-discovering its edge cases.

**Runtime.** Computing all 15 scenarios x 2 modes in-process (`_run_one`
directly -- no subprocess, no CLI stdout parsing) measured at ~6.8s on the
machine this was written on, three runs, `time.perf_counter()` around the
loop only (imports excluded). See `test_capability_benchmark.py`'s own
module docstring for this project's precedent on stating a measured cost
instead of guessing one. ~6.8s is comfortably inside the ~30s budget this
guard was given, so unlike that file there is no manual-only/fast-subset
split here -- the full comparison runs by default, every `pytest -q`.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import pytest

pytest.importorskip("numpy")  # `_run_one` builds real sequences -- see test_replay.py's own reasoning

from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW

from tools.trackeval import metrics as metrics_module
from tools.trackeval.__main__ import _run_one
from tools.trackeval.metrics import Metrics
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS

# cv-service/ checkout directory: this file sits at tests/trackeval/, so two
# `.parent`s -- same convention `tests/inference/test_real_model.py` uses.
_CV_SERVICE_DIR = Path(__file__).resolve().parent.parent.parent
_BASELINE_PATH = _CV_SERVICE_DIR / "tools" / "trackeval" / "BASELINE.md"

# The heading that starts the CURRENT scoreboard's fenced table -- BASELINE.md
# §5 also has an old-format table (fewer columns, historical, explicitly not
# a target any wave is judged against per its own text), so the search has
# to anchor on THIS heading rather than "the first fenced block in the file".
_TABLE_HEADING = "## 1. The V0 table"

# `trk_ms_avg`/`trk_ms_p95` -- see the module docstring's "which columns"
# section. Nothing else in `metrics.render_table`'s column set is skipped.
_SKIPPED_COLUMNS = ("trk_ms_avg", "trk_ms_p95")

# `recov%` is also skipped, but not via this set -- see the module docstring;
# it is simply never looked up because it is entirely absent from both
# `_INT_COLUMN_FIELD` and `_FLOAT_COLUMN_FIELD` below.

# Column name (as it appears in `metrics._COLUMN_HEADERS`) -> `Metrics`
# field it is asserted exactly against. Every one of these is an integer
# count, deterministic given the seed (`metrics.py`'s own module docstring).
_INT_COLUMN_FIELD: dict[str, str] = {
    "frames": "total_frames",
    "gt": "gt_object_count",
    "IDSW": "idsw",
    "FM": "fragmentations",
    "MT": "mostly_tracked",
    "PT": "partially_tracked",
    "ML": "mostly_lost",
    "gaps": "gap_count",
    "recov": "recovered_count",
    "coast_n": "coast_sample_count",
    "implaus_n": "implausible_velocity_count",
}

# Column name -> `(Metrics field, decimal places `_row_cells` formats it
# with)`. Compared by re-applying that SAME format spec to the fresh value
# and diffing strings -- see the module docstring's tolerance section for
# why not a numeric epsilon.
_FLOAT_COLUMN_FIELD: dict[str, tuple[str, int]] = {
    "life_mean": ("mean_track_lifetime_frames", 1),
    "life_med": ("median_track_lifetime_frames", 1),
    "det/s": ("detector_passes_per_sec", 2),
    "cADE": ("coast_ade_norm", 3),
    "cFDE": ("coast_fde_norm", 3),
    "cADE_px": ("coast_ade_px", 1),
    "cFDE_px": ("coast_fde_px", 1),
}


def _load_baseline_table() -> tuple[tuple[str, ...], list[dict[str, str]]]:
    """Parses `BASELINE.md` §1's fenced table into `(header, rows)`.

    `render_table`'s own format (`" | ".join(cell.ljust(width) for cell,
    width in ...)`) is what generated this block in the first place
    (`BASELINE.md`'s own reproduction command pastes its stdout verbatim),
    so splitting each line on the literal `" | "` separator and stripping
    the padding recovers exactly the cells `render_table` wrote, regardless
    of what the column widths happened to be in this particular table.
    """
    text = _BASELINE_PATH.read_text(encoding="utf-8")
    heading_index = text.index(_TABLE_HEADING)
    fence_start = text.index("```", heading_index) + 3
    fence_end = text.index("```", fence_start)
    block = text[fence_start:fence_end].strip("\n")

    lines = [line for line in block.splitlines() if line.strip()]
    header = tuple(cell.strip() for cell in lines[0].split(" | "))
    data_lines = lines[2:]  # lines[1] is the "----+----" separator row

    rows: list[dict[str, str]] = []
    for line in data_lines:
        cells = [cell.strip() for cell in line.split(" | ")]
        assert len(cells) == len(header), (
            f"BASELINE.md table row has {len(cells)} cells but the header has "
            f"{len(header)} -- the table is malformed: {line!r}"
        )
        rows.append(dict(zip(header, cells)))
    return header, rows


def _format_column(value: Optional[float], decimals: int) -> str:
    """The exact string `_row_cells` would have written for this value --
    `"n/a"` for `None`, else fixed-point at `decimals` places."""
    return "n/a" if value is None else f"{value:.{decimals}f}"


def _row_diff(baseline_row: dict[str, str], fresh: Metrics) -> list[str]:
    """Every column of one scenario/mode row that disagrees, as a
    human-readable `"column: BASELINE.md=X fresh_run=Y"` line -- empty if
    the row matches. Collects every mismatch in the row rather than
    stopping at the first, so one bad `--all` run reports the whole picture
    at once instead of costing a re-run per column.
    """
    diffs: list[str] = []

    fresh_engine = fresh.engine_id or "-"  # `_row_cells`'s own `engine_id or "-"` convention
    if fresh_engine != baseline_row["engine"]:
        diffs.append(f"engine: BASELINE.md={baseline_row['engine']!r} fresh_run={fresh_engine!r}")

    for column, field in _INT_COLUMN_FIELD.items():
        expected = int(baseline_row[column])
        actual = getattr(fresh, field)
        if expected != actual:
            diffs.append(f"{column}: BASELINE.md={expected} fresh_run={actual}")

    for column, (field, decimals) in _FLOAT_COLUMN_FIELD.items():
        expected_cell = baseline_row[column]
        actual_cell = _format_column(getattr(fresh, field), decimals)
        if actual_cell != expected_cell:
            diffs.append(f"{column}: BASELINE.md={expected_cell} fresh_run={actual_cell}")

    return diffs


def test_scenario_list_matches_the_harness_registry() -> None:
    """Guards the exact staleness class that produced the old "Eight
    scenarios" heading: a scenario added to (or removed from)
    `sequences.SCENARIOS` with no matching update to `BASELINE.md`'s own
    table. Deliberately cheap -- pure string parsing plus a set diff, no
    replay -- so it fails fast, before the much more expensive full
    comparison below even starts.
    """
    _, baseline_rows = _load_baseline_table()
    documented = {row["scenario"] for row in baseline_rows}
    registered = set(SCENARIOS)

    assert documented == registered, (
        f"BASELINE.md's table (section 1) documents {sorted(documented)} scenarios but "
        f"tools/trackeval/sequences.py's SCENARIOS registry has {sorted(registered)}.\n"
        f"  only in BASELINE.md:  {sorted(documented - registered)}\n"
        f"  only in SCENARIOS:    {sorted(registered - documented)}\n"
        "A scenario was added to (or removed from) sequences.py without updating "
        "BASELINE.md's own table -- this is exactly the stale-heading class of drift this "
        "test exists to catch. Re-run `PYTHONPATH=\"$PWD\" .venv/bin/python -m "
        "tools.trackeval --all` from cv-service/ and paste the result into BASELINE.md."
    )


def test_baseline_matches_a_fresh_harness_run() -> None:
    """Runs every scenario x mode the harness has (`SCENARIOS` x
    `{ASSOCIATE, FOLLOW}`) with `BASELINE.md`'s own documented reproduction
    seed (`sequences.DEFAULT_SEED`, matching a bare `--all` invocation with
    no `--seed` override) and diffs the result against the recorded table,
    column by column, per this module's docstring.

    Needs the real `cv` extra: FOLLOW's default engine (`lk`) is
    `cv2`-backed (`engines/lk.py`), and without it `TrackerRegistry.probe()`
    degrades FOLLOW down its ladder to a DIFFERENT engine
    (`replay.py`'s own docstring) -- comparing that degraded run's numbers
    against a baseline recorded with `lk` would be diffing two different
    algorithms, not detecting drift in one. `BASELINE.md`'s own reproduction
    instructions already assume the `cv` extra is installed.
    """
    pytest.importorskip("cv2", reason="FOLLOW's default 'lk' engine needs cv2 to match BASELINE.md's own run")

    header, baseline_rows = _load_baseline_table()
    assert list(header) == list(metrics_module._COLUMN_HEADERS), (
        "BASELINE.md's table header no longer matches metrics.render_table's own column "
        f"order/names.\n  BASELINE.md header: {list(header)}\n  render_table header:  "
        f"{list(metrics_module._COLUMN_HEADERS)}\n"
        "Either metrics.py's column set changed and BASELINE.md needs regenerating (see its "
        "own reproduction command), or this test's column tables need updating to match."
    )
    baseline_by_key = {(row["scenario"], row["mode"]): row for row in baseline_rows}

    missing_rows: list[str] = []
    row_diffs: dict[tuple[str, str], list[str]] = {}
    for scenario in sorted(SCENARIOS):
        for mode in (MODE_ASSOCIATE, MODE_FOLLOW):
            baseline_row = baseline_by_key.get((scenario, mode))
            if baseline_row is None:
                missing_rows.append(f"{scenario} / {mode}")
                continue
            fresh = _run_one(scenario, mode, seed=DEFAULT_SEED)
            diffs = _row_diff(baseline_row, fresh)
            if diffs:
                row_diffs[(scenario, mode)] = diffs

    if missing_rows:
        pytest.fail(
            "BASELINE.md's scoreboard table (section 1) has no row for:\n  "
            + "\n  ".join(missing_rows)
            + "\nEvery scenario x mode the harness runs needs a row. Re-run "
            "`PYTHONPATH=\"$PWD\" .venv/bin/python -m tools.trackeval --all` from cv-service/ "
            "and paste the result in."
        )

    if row_diffs:
        report_lines = [
            f"  {scenario} / {mode}:\n    " + "\n    ".join(diffs)
            for (scenario, mode), diffs in row_diffs.items()
        ]
        pytest.fail(
            f"BASELINE.md has drifted from what the harness actually produces "
            f"(seed={DEFAULT_SEED}) on {len(row_diffs)} of {len(baseline_rows)} row(s):\n\n"
            + "\n".join(report_lines)
            + "\n\nThis is what TRACKING-V2-PLAN.md §5b's five apparatus defects, and the "
            "crossing_similar IDSW/FM mixup that motivated this test, all looked like: a wave "
            "gets judged against a number BASELINE.md never actually produced. The fix is "
            "USUALLY to re-run the harness (`PYTHONPATH=\"$PWD\" .venv/bin/python -m "
            "tools.trackeval --all` from cv-service/) and paste the corrected numbers into "
            "BASELINE.md -- not to change the tracker to match a stale table."
        )
