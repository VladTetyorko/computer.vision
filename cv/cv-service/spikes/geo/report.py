"""Markdown report generation, shaped to paste directly into
docs/VISUAL-GEO-PLAN.md §12, plus the §5 go/no-go verdict.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from spikes.geo.metrics import ConfigMetrics

GO = "GO"
PARTIAL_GO = "PARTIAL GO"
NO_GO = "NO GO"
INCONCLUSIVE = "INCONCLUSIVE"

REGION_RECALL_GATE = 0.60
PRIOR_RECALL_GATE = 0.85
FALSE_FIX_GO_GATE = 0.02
FALSE_FIX_NOGO_GATE = 0.05
ENCODE_MS_GO_GATE = 150.0
ENCODE_MS_NOGO_GATE = 400.0


@dataclass(frozen=True)
class EncoderVerdict:
    encoder_name: str
    verdict: str
    rationale: str
    region_recall_at_1: Optional[float]
    prior_recall_at_1: Optional[float]
    false_fix_rate: Optional[float]
    encode_ms_per_frame: Optional[float]


def _fmt(value: float, digits: int = 3) -> str:
    if value is None or value != value:  # NaN check without importing math here
        return "n/a"
    return f"{value:.{digits}f}"


def _best_region_config(configs: list[ConfigMetrics]) -> Optional[ConfigMetrics]:
    region = [c for c in configs if not c.with_prior]
    if not region:
        return None
    return max(region, key=lambda c: c.recall_at_1_100m)


def _best_prior_config(configs: list[ConfigMetrics]) -> Optional[ConfigMetrics]:
    prior = [c for c in configs if c.with_prior]
    if not prior:
        return None
    return max(prior, key=lambda c: c.recall_at_1_100m)


def compute_encoder_verdict(encoder_name: str, configs: list[ConfigMetrics]) -> EncoderVerdict:
    """§5's three named outcomes, computed against ONE encoder's best
    region-wide and best with-prior configs (across whichever zooms/
    augmentation settings were run for it). Ties are NOT resolved by the
    plan's wording for every possible number combination -- e.g. a
    false-fix rate of 0.03 is neither <=0.02 nor >0.05. Those cases report
    `INCONCLUSIVE` with the raw numbers rather than silently rounding to a
    named band."""
    region = _best_region_config(configs)
    prior = _best_prior_config(configs)
    if region is None or prior is None:
        return EncoderVerdict(
            encoder_name, INCONCLUSIVE, "missing a with-prior or region-wide run for this encoder", None, None, None, None
        )

    region_recall = region.recall_at_1_100m
    prior_recall = prior.recall_at_1_100m
    # §5 measures false-fix/encode on the SAME config the recall numbers
    # come from; using the with-prior config's own values here since that's
    # the operating point the fusion state machine actually runs at in the field.
    false_fix = prior.false_fix_rate
    encode_ms = prior.encode_ms_per_frame

    if prior_recall < PRIOR_RECALL_GATE or false_fix > FALSE_FIX_NOGO_GATE or encode_ms > ENCODE_MS_NOGO_GATE:
        reasons = []
        if prior_recall < PRIOR_RECALL_GATE:
            reasons.append(f"with-prior recall@1 {_fmt(prior_recall)} < {PRIOR_RECALL_GATE}")
        if false_fix > FALSE_FIX_NOGO_GATE:
            reasons.append(f"false-fix rate {_fmt(false_fix)} > {FALSE_FIX_NOGO_GATE}")
        if encode_ms > ENCODE_MS_NOGO_GATE:
            reasons.append(f"encode {_fmt(encode_ms, 1)}ms/frame > {ENCODE_MS_NOGO_GATE}ms")
        return EncoderVerdict(encoder_name, NO_GO, "; ".join(reasons), region_recall, prior_recall, false_fix, encode_ms)

    if (
        region_recall >= REGION_RECALL_GATE
        and prior_recall >= PRIOR_RECALL_GATE
        and false_fix <= FALSE_FIX_GO_GATE
        and encode_ms <= ENCODE_MS_GO_GATE
    ):
        return EncoderVerdict(
            encoder_name, GO, "region-wide and with-prior recall, false-fix and encode latency all cleared their gates",
            region_recall, prior_recall, false_fix, encode_ms,
        )

    if prior_recall >= PRIOR_RECALL_GATE and region_recall < REGION_RECALL_GATE:
        return EncoderVerdict(
            encoder_name, PARTIAL_GO,
            f"with-prior recall@1 {_fmt(prior_recall)} >= {PRIOR_RECALL_GATE} but region-wide {_fmt(region_recall)} < {REGION_RECALL_GATE}",
            region_recall, prior_recall, false_fix, encode_ms,
        )

    return EncoderVerdict(
        encoder_name, INCONCLUSIVE,
        f"numbers fall between the named bands (region={_fmt(region_recall)}, prior={_fmt(prior_recall)}, "
        f"false_fix={_fmt(false_fix)}, encode={_fmt(encode_ms,1)}ms) -- re-check against §5's exact thresholds by hand",
        region_recall, prior_recall, false_fix, encode_ms,
    )


_VERDICT_RANK = {GO: 3, PARTIAL_GO: 2, INCONCLUSIVE: 1, NO_GO: 0}


def overall_verdict(encoder_verdicts: list[EncoderVerdict]) -> EncoderVerdict:
    if not encoder_verdicts:
        return EncoderVerdict("(none)", INCONCLUSIVE, "no encoder produced a complete with-prior/region-wide pair", None, None, None, None)
    return max(encoder_verdicts, key=lambda v: _VERDICT_RANK[v.verdict])


def render_markdown(
    *,
    run_id: str,
    smoke_test: bool,
    configs: list[ConfigMetrics],
    encoder_verdicts: list[EncoderVerdict],
    verdict: EncoderVerdict,
    notes: list[str],
) -> str:
    lines: list[str] = []
    lines.append(f"## Wave 0 results — run `{run_id}`")
    lines.append("")
    if smoke_test:
        lines.append(
            "> **SMOKE TEST RUN.** Queries are synthetic crops of tiles already in the reference index "
            "(see spikes/geo/synth.py). This proves the fetch -> encode -> index -> search -> metrics -> "
            "report pipeline runs end to end. It is **not evidence of real-world VPR feasibility** -- "
            "a near-identical crop of an already-indexed tile is a far easier match than a real oblique "
            "FPV frame against a nadir satellite tile shot at a different time. Re-run with `--video`/"
            "`--telemetry` or `--manifest` against real footage before trusting the verdict below."
        )
        lines.append("")

    lines.append(
        "| Config | Encoder | Zoom | Augmented | Prior | N | Recall@1≤100m | Recall@5≤100m | "
        "Median err (m) | P90 err (m) | False-fix rate | Accept sim thr | Encode ms/frame | Search ms | Bootstrap frames |"
    )
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for config in configs:
        bootstrap = str(config.bootstrap_frames) if config.bootstrap_frames is not None else "N/A"
        lines.append(
            f"| {config.config_name} | {config.encoder_name} | {config.zoom} | "
            f"{'yes' if config.augmented else 'no'} | {'500m' if config.with_prior else 'none (region-wide)'} | "
            f"{config.n_queries} | {_fmt(config.recall_at_1_100m)} | {_fmt(config.recall_at_5_100m)} | "
            f"{_fmt(config.median_error_m, 1)} | {_fmt(config.p90_error_m, 1)} | {_fmt(config.false_fix_rate)} | "
            f"{_fmt(config.accept_similarity)} | {_fmt(config.encode_ms_per_frame, 1)} | "
            f"{_fmt(config.search_ms, 2)} | {bootstrap} |"
        )
    lines.append("")
    lines.append(
        "*\"Encode ms/frame\" is measured on the machine that ran this spike, plain PyTorch CPU -- "
        "**not** the GB4005 box via OpenVINO §5 asks for. Re-run there before treating the 150/400ms "
        "gates below as decided; OpenVINO is typically 2-3x faster than plain PyTorch CPU (see "
        "cv-service/MODULE.md's OpenVINO export note), so a number here under ~450ms is not "
        "necessarily disqualifying once exported."
    )
    lines.append("")

    lines.append("### Go / no-go")
    lines.append("")
    lines.append("| Encoder | Verdict | Region recall@1 | With-prior recall@1 | False-fix | Encode ms/frame | Rationale |")
    lines.append("|---|---|---|---|---|---|---|")
    for ev in encoder_verdicts:
        lines.append(
            f"| {ev.encoder_name} | **{ev.verdict}** | {_fmt(ev.region_recall_at_1)} | {_fmt(ev.prior_recall_at_1)} | "
            f"{_fmt(ev.false_fix_rate)} | {_fmt(ev.encode_ms_per_frame, 1)} | {ev.rationale} |"
        )
    lines.append("")
    lines.append(f"**Overall verdict: {verdict.verdict}** ({verdict.encoder_name}) — {verdict.rationale}")
    if smoke_test:
        lines.append("")
        lines.append("*(Smoke-test verdict above is a pipeline check only — see banner at the top of this report.)*")
    lines.append("")

    if notes:
        lines.append("### Notes")
        lines.append("")
        for note in notes:
            lines.append(f"- {note}")
        lines.append("")

    return "\n".join(lines)
