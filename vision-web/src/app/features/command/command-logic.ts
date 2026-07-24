import type { AssetAttention } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';
import { TELEMETRY_AGE_RED_SECONDS } from '../../core/telemetry/telemetry-logic';

/**
 * Pure, Angular-free logic behind `CommandPage` (docs/UX-REWORK-PLAN.md §U-c — the map-first
 * manager dashboard, superseding docs/MVP3-PLAN.md §C-c's stacked-cards layout) — the entity
 * rail's attention-sort order and severity rules, and the collapsible-rail/collapsible-panel grid
 * layout arithmetic, split out so every rule is unit-testable without HTTP, the router, or a
 * component, mirroring every other page's own `*-logic.ts` split.
 *
 * **What moved out of this file in the §U-c rework, and why**: `buildAttentionQueue`/`AttentionRow`
 * (the old, separate "attention queue" card) is replaced by `buildEntityRows` below — the rail is
 * now the *one* asset list (attention-sorted, but showing every asset, not only flagged ones), so
 * there is no longer a second, narrower "queue" projection to maintain alongside it.
 * `sortReadinessTiles`/`totalStreaming` (the warehouse-readiness tiles) and `streamingAssets`/
 * `shouldPollSnapshot` (the live-strip snapshot tiles) are deleted outright, not moved — both
 * sections they backed are gone from Command per the plan's own user-amendments blockquote
 * ("Warehouse-readiness section: removed"; "live strip: removed"), and neither rule had any other
 * consumer (grep-verified before deleting).
 */

// --- Attention rules (unchanged from the pre-§U-c queue — still exactly what colors a rail row) --

/** Battery below this percent is worth flagging at all. */
export const BATTERY_ATTENTION_PERCENT = 20;

/** Battery below this percent escalates the same reason to the most severe tier. */
export const BATTERY_CRITICAL_PERCENT = 10;

/**
 * Telemetry older than this, on a *currently streaming* asset, is itself an attention reason.
 * Reuses `core/telemetry/telemetry-logic.ts#TELEMETRY_AGE_RED_SECONDS` directly rather than
 * re-deriving the same threshold under a new name.
 */
export const TELEMETRY_STALE_MS = TELEMETRY_AGE_RED_SECONDS * 1000;

export type AttentionReasonKind = 'battery-critical' | 'telemetry-stale' | 'battery-low' | 'open-events';
export type AttentionSeverity = 'critical' | 'warning';

export interface AttentionReason {
  readonly kind: AttentionReasonKind;
  readonly severity: AttentionSeverity;
  /** A complete sentence — the panel's "why" line joins one or more of these with a space. */
  readonly text: string;
}

/**
 * How urgently each reason kind reads, highest first — an asset's overall rank is the max of its
 * own triggered reasons' ranks (see `buildEntityRows`), so an asset with *any* rank-4/3 reason
 * always outranks one with only rank-2/1 reasons, regardless of how many of the latter it has.
 *
 * Battery-critical and telemetry-stale share the top two ranks deliberately, both above battery-low
 * and open-events: a dead battery mid-flight and a lost telemetry link mid-flight are the same kind
 * of "this drone may not come back" risk. Battery-low is a step down — worth watching, not yet
 * urgent. Open detection events rank lowest — informational, not a safety condition.
 */
const REASON_RANK: Readonly<Record<AttentionReasonKind, number>> = {
  'battery-critical': 4,
  'telemetry-stale': 3,
  'battery-low': 2,
  'open-events': 1,
};

/** `'unknown'` (no reading yet) never triggers a reason — never a fabricated tier from no data. */
export type BatteryAttentionSeverity = 'critical' | 'warning' | 'ok' | 'unknown';

/**
 * The single battery-severity rule the whole Command page uses — a rail row's chip color and the
 * detail panel's own battery fact both derive from this one function, so "when is a battery
 * reading worth calling out" is answered in exactly one place.
 */
export function batteryAttentionSeverity(percent: number | undefined): BatteryAttentionSeverity {
  if (percent === undefined) {
    return 'unknown';
  }
  if (percent < BATTERY_CRITICAL_PERCENT) {
    return 'critical';
  }
  return percent < BATTERY_ATTENTION_PERCENT ? 'warning' : 'ok';
}

function batteryReason(percent: number | undefined): AttentionReason | undefined {
  const severity = batteryAttentionSeverity(percent);
  if (severity === 'critical') {
    return { kind: 'battery-critical', severity: 'critical', text: `Battery critical at ${Math.round(percent!)}%.` };
  }
  if (severity === 'warning') {
    return { kind: 'battery-low', severity: 'warning', text: `Battery low at ${Math.round(percent!)}%.` };
  }
  return undefined;
}

/**
 * Telemetry stale *while streaming* only — an asset that isn't currently flying reporting old
 * telemetry is expected (it landed a while ago), not itself an urgent, right-now attention item the
 * way a live drone going quiet is.
 */
function telemetryReason(asset: AssetAttention): AttentionReason | undefined {
  if (!asset.streaming || asset.telemetryAgeMs === undefined || asset.telemetryAgeMs <= TELEMETRY_STALE_MS) {
    return undefined;
  }
  const ageSeconds = Math.round(asset.telemetryAgeMs / 1000);
  return {
    kind: 'telemetry-stale',
    severity: 'critical',
    text: `Telemetry stale for ${ageSeconds}s while streaming.`,
  };
}

function openEventsReason(openEventCount: number): AttentionReason | undefined {
  if (openEventCount <= 0) {
    return undefined;
  }
  return {
    kind: 'open-events',
    severity: 'warning',
    text: `${openEventCount} open detection ${openEventCount === 1 ? 'event' : 'events'}.`,
  };
}

/**
 * Every reason `asset` triggers, most severe first. An asset with none of these returns an empty
 * array — "all quiet" for that asset (still shown in the rail, just at the bottom, unflagged).
 */
export function attentionReasons(asset: AssetAttention): readonly AttentionReason[] {
  const reasons = [batteryReason(asset.batteryPercent), telemetryReason(asset), openEventsReason(asset.openEventCount)].filter(
    (reason): reason is AttentionReason => reason !== undefined,
  );
  return [...reasons].sort((a, b) => REASON_RANK[b.kind] - REASON_RANK[a.kind]);
}

/** The rail/panel's own "age" column — the freshest telemetry sample's age, or `'—'` when none exists yet. */
export function attentionAgeLabel(asset: AssetAttention): string {
  return asset.telemetryAgeMs === undefined ? '—' : `${formatDuration(asset.telemetryAgeMs / 1000)} ago`;
}

// --- Entity rail (docs/UX-REWORK-PLAN.md §U-c bullet 1) -----------------------------------------

export interface EntityRow {
  readonly asset: AssetAttention;
  /** Most severe first — same order `attentionReasons` already returns; empty when quiet. */
  readonly reasons: readonly AttentionReason[];
  /** The most severe triggered reason's own severity, or `'ok'` for a quiet asset. */
  readonly severity: AttentionSeverity | 'ok';
}

function rowRank(row: EntityRow): number {
  return row.reasons[0] ? REASON_RANK[row.reasons[0].kind] : 0;
}

/**
 * The left entity rail's own rows: **every** asset (not just flagged ones — the rail replaces both
 * the old separate "attention queue" card and the old map rail's "every asset" list), sorted
 * attention-first:
 *
 * 1. Higher reason rank first (a quiet asset's rank is 0 — always last).
 * 2. Within a rank tie, more simultaneous reasons first.
 * 3. Alphabetical by display name (case-insensitive) as the final, deterministic tie-break —
 *    this is also what orders the quiet assets among themselves, once every flagged one sorts
 *    ahead of them.
 */
export function buildEntityRows(assets: readonly AssetAttention[]): readonly EntityRow[] {
  const rows: EntityRow[] = assets.map((asset) => {
    const reasons = attentionReasons(asset);
    return { asset, reasons, severity: reasons[0]?.severity ?? 'ok' };
  });
  return [...rows].sort((a, b) => {
    const rankDiff = rowRank(b) - rowRank(a);
    if (rankDiff !== 0) {
      return rankDiff;
    }
    if (a.reasons.length !== b.reasons.length) {
      return b.reasons.length - a.reasons.length;
    }
    return a.asset.displayName.localeCompare(b.asset.displayName, undefined, { sensitivity: 'base' });
  });
}

// --- Layout grid (docs/UX-REWORK-PLAN.md §U-c bullet 5 — geometric separation, not z-index) -----

/** `'hidden'`: nothing selected, no reopen chip. `'collapsed'`: selected, but shrunk to a chip. */
export type DetailPanelState = 'hidden' | 'open' | 'collapsed';

export const COMMAND_RAIL_WIDTH = '300px';
export const COMMAND_PANEL_WIDTH = '380px';

/**
 * The full-bleed map stage's own `grid-template-columns`, as plain CSS text — computed here rather
 * than assembled from several `[class.x]` toggles in the template, so the collapsible-rail ×
 * collapsible-panel arithmetic (6 combinations: 2 rail states × 3 panel states) is unit-tested
 * directly instead of eyeballed across CSS class combinations. Each side's own "reopen" toggle
 * button is always its own grid track (`auto`) once that side is in play — mirrors
 * `features/live/live.ts`'s `.rail-toggle` idiom (a toggle tab that's always present once its side
 * exists, only the content column collapses) — which is also why a `'hidden'` panel contributes
 * *no* extra track at all: there is nothing to reopen until an asset is actually selected.
 *
 * This is deliberately a **flex-docked** layout (rail/map/panel are side-by-side grid tracks, never
 * `position: absolute` overlays on top of the map), not because the plan's own "chrome floating
 * over" language forbids overlays, but because `shared/map/fleet-map.ts`'s own zoom/layer controls
 * are fixed at `top:0.5rem` in its *own* corners (its own CSS, out of this task's reach — the plan
 * explicitly rules out reworking `fleet-map`/`live-map` themselves) — an absolutely-positioned rail
 * sharing that same corner would occlude them, reproducing exactly the "switcher unreachable under
 * the map inset" incident (docs/UX-QUICKWINS-PLAN.md QF-1, `features/fly/fly.css`'s own `.hud-map`
 * doc comment) this task was told to learn from. Docking the panels as real layout siblings instead
 * means the map's own corner controls and this app's own chrome never share a pixel — geometric
 * separation by construction, zero z-index coordination needed with a component this task cannot
 * modify.
 */
export function commandGridColumns(railOpen: boolean, panel: DetailPanelState): string {
  const rail = railOpen ? `${COMMAND_RAIL_WIDTH} auto` : 'auto';
  const stage = 'minmax(0, 1fr)';
  const panelTrack = panel === 'hidden' ? '' : panel === 'open' ? ` auto ${COMMAND_PANEL_WIDTH}` : ' auto';
  return `${rail} ${stage}${panelTrack}`;
}
