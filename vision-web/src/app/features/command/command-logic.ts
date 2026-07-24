import type { AssetAttention, CategoryCounts } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';
import { TELEMETRY_AGE_RED_SECONDS } from '../../core/telemetry/telemetry-logic';

/**
 * Pure, Angular-free logic behind `CommandPage` (docs/MVP3-PLAN.md §C-c) — the manager dashboard's
 * attention-queue severity rules, queue ordering, live-strip membership, readiness-tile ordering,
 * and the strip tile's own visibility gate, split out so every rule is unit-testable without HTTP,
 * the router, or a component, mirroring every other page's own `*-logic.ts` split
 * (`features/fly/fly-logic.ts`, `core/map/map-logic.ts`, etc.).
 *
 * Every function here operates on an already-fetched `FleetSummary` (one `GET /api/fleet/summary`
 * response) — nothing below issues a request or knows `VisionApi` exists, which is what makes the
 * "one summary poll drives the queue, the strip's membership, and the readiness tiles" scale claim
 * (docs/MVP3-PLAN.md §C-c bullet 6) true by construction: there is no code path here that could
 * fan out a second request per asset even by accident.
 */

// --- Attention queue (docs/MVP3-PLAN.md §C-c bullet 1) -----------------------------------------

/** Battery below this percent is worth a queue row at all. */
export const BATTERY_ATTENTION_PERCENT = 20;

/** Battery below this percent escalates the same reason to the queue's most severe tier. */
export const BATTERY_CRITICAL_PERCENT = 10;

/**
 * Telemetry older than this, on a *currently streaming* asset, is itself a queue reason. Reuses
 * `core/telemetry/telemetry-logic.ts#TELEMETRY_AGE_RED_SECONDS` directly rather than re-deriving the same
 * threshold under a new name (docs/MVP3-PLAN.md's own C-b done note: Command's severity keying
 * "can reuse `telemetryAgeSeverity`'s `TELEMETRY_AGE_RED_SECONDS`=10 constant directly").
 */
export const TELEMETRY_STALE_MS = TELEMETRY_AGE_RED_SECONDS * 1000;

export type AttentionReasonKind = 'battery-critical' | 'telemetry-stale' | 'battery-low' | 'open-events';
export type AttentionSeverity = 'critical' | 'warning';

export interface AttentionReason {
  readonly kind: AttentionReasonKind;
  readonly severity: AttentionSeverity;
  /** A complete sentence — `AttentionRow#why` joins one or more of these with a space. */
  readonly text: string;
}

/**
 * How urgently each reason kind reads, highest first — a row's overall rank is the max of its own
 * triggered reasons' ranks (see `buildAttentionQueue`), so an asset with *any* rank-4/3 reason
 * always outranks one with only rank-2/1 reasons, regardless of how many of the latter it has.
 *
 * Battery-critical and telemetry-stale share the top two ranks deliberately, both above battery-low
 * and open-events: a dead battery mid-flight and a lost telemetry link mid-flight are the same kind
 * of "this drone may not come back" risk (the plan's own persona text ranks "telemetry/link age,
 * staleness = danger" right behind the video feed itself in priority — not a lesser concern than
 * battery). Battery-low is a step down — worth watching, not yet urgent. Open detection events rank
 * lowest — informational (something was seen), not a safety condition.
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
 * The single battery-severity rule the whole Command page uses — the attention queue's own reason
 * (`battery-critical`/`battery-low`) and the live strip tile's chip color both derive from this one
 * function, so "when is a battery reading worth calling out" is answered in exactly one place.
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
 * Telemetry stale *while streaming* only (docs/MVP3-PLAN.md §C-c: "telemetryAgeMs > 10s while
 * streaming") — an asset that isn't currently flying reporting old telemetry is expected (it landed
 * a while ago), not itself an urgent, right-now attention item the way a live drone going quiet is.
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
 * Every reason `asset` triggers, most severe first (docs/MVP3-PLAN.md §C-c's three rules: battery <
 * 20%/red < 10%, telemetryAgeMs > 10s while streaming, openEventCount > 0). An asset with none of
 * these returns an empty array — "all quiet" for that asset, not rendered in the queue at all.
 */
export function attentionReasons(asset: AssetAttention): readonly AttentionReason[] {
  const reasons = [batteryReason(asset.batteryPercent), telemetryReason(asset), openEventsReason(asset.openEventCount)].filter(
    (reason): reason is AttentionReason => reason !== undefined,
  );
  return [...reasons].sort((a, b) => REASON_RANK[b.kind] - REASON_RANK[a.kind]);
}

export interface AttentionRow {
  readonly asset: AssetAttention;
  /** Most severe first — same order `attentionReasons` already returns. */
  readonly reasons: readonly AttentionReason[];
  /** The most severe triggered reason's own severity — drives the row's color. */
  readonly severity: AttentionSeverity;
  readonly why: string;
}

function buildRow(asset: AssetAttention): AttentionRow | undefined {
  const reasons = attentionReasons(asset);
  if (reasons.length === 0) {
    return undefined;
  }
  return {
    asset,
    reasons,
    severity: reasons[0].severity,
    why: reasons.map((reason) => reason.text).join(' '),
  };
}

/**
 * The attention queue: every asset with ≥1 triggered reason, most severe first (docs/MVP3-PLAN.md
 * §C-c bullet 1). Ordering, fully spec'd:
 *
 * 1. Higher `REASON_RANK` (the most severe of an asset's own triggered reasons) first.
 * 2. Within a rank tie, more simultaneous reasons first — two things wrong at once reads as more
 *    urgent than one, regardless of which two.
 * 3. Within that tie too, alphabetical by display name (case-insensitive) — deterministic, and
 *    never leans on comparing e.g. a battery percentage against an event count across rows
 *    triggered by unrelated reasons, which would be a meaningless comparison.
 */
export function buildAttentionQueue(assets: readonly AssetAttention[]): readonly AttentionRow[] {
  const rows = assets.map(buildRow).filter((row): row is AttentionRow => row !== undefined);
  return [...rows].sort((a, b) => {
    const rankA = REASON_RANK[a.reasons[0].kind];
    const rankB = REASON_RANK[b.reasons[0].kind];
    if (rankA !== rankB) {
      return rankB - rankA;
    }
    if (a.reasons.length !== b.reasons.length) {
      return b.reasons.length - a.reasons.length;
    }
    return a.asset.displayName.localeCompare(b.asset.displayName, undefined, { sensitivity: 'base' });
  });
}

/** The queue row's own "age" column — the freshest telemetry sample's age, or `'—'` when none exists yet. */
export function attentionAgeLabel(asset: AssetAttention): string {
  return asset.telemetryAgeMs === undefined ? '—' : `${formatDuration(asset.telemetryAgeMs / 1000)} ago`;
}

/** The empty state's own count: "All quiet — N assets, M streaming" (docs/MVP3-PLAN.md §C-c bullet 1). */
export function totalStreaming(categories: readonly CategoryCounts[]): number {
  return categories.reduce((sum, category) => sum + category.streaming, 0);
}

// --- Live strip (docs/MVP3-PLAN.md §C-c bullet 2) -----------------------------------------------

/**
 * Every currently-streaming asset, alphabetical by name — the live strip's own membership. Only
 * ever derived from the one already-fetched summary (never a second request), which is what makes
 * "the strip's membership" one of the three things a single summary poll drives.
 */
export function streamingAssets(assets: readonly AssetAttention[]): readonly AssetAttention[] {
  return assets
    .filter((asset) => asset.streaming && asset.streamId !== undefined)
    .slice()
    .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }));
}

/**
 * Whether a strip tile should keep polling its own snapshot — visible tiles only (docs/MVP3-PLAN.md
 * §C-c bullets 2/6: "refreshed … per VISIBLE tile only", "no O(fleet) request patterns"). A pure
 * wrapper around the same boolean an `IntersectionObserver` callback drives in
 * `features/command/live-strip-tile.ts` (mirroring `features/wall/wall-tile.ts`'s identical visibility
 * gate for its own telemetry/detections polls) — kept here so the *rule* itself, not just the DOM
 * wiring around it, is unit-tested. `hasStreamId` covers the honest gap where a tile is still
 * mounted (its asset was streaming as of the last summary poll) but the stream has since stopped —
 * there is nothing to snapshot until the next summary poll either drops the tile or hands it a
 * fresh `streamId`.
 */
export function shouldPollSnapshot(visible: boolean, hasStreamId: boolean): boolean {
  return visible && hasStreamId;
}

// --- Warehouse readiness tiles (docs/MVP3-PLAN.md §C-c bullet 4) --------------------------------

/**
 * `FleetSummary#categories` in display order — alphabetical by category name. The server already
 * sorts by slug (`FleetSummaryResponse`'s own doc comment); this is purely a friendlier *display*
 * order, not a behavior change (a category's slug and name agree in every seed category today).
 */
export function sortReadinessTiles(categories: readonly CategoryCounts[]): readonly CategoryCounts[] {
  return [...categories].sort((a, b) => a.categoryName.localeCompare(b.categoryName, undefined, { sensitivity: 'base' }));
}
