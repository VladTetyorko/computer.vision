import type { AssetAttention, AssetStatus } from '../../core/api/models';
import type { GeofenceBreach } from '../../core/geofence/geofence-logic';
import { REASON_RANK, attentionReasons, type AttentionReason, type AttentionSeverity } from '../../core/fleet/attention-logic';
import { groupAndSort, offlineLabel, type PickerGroups } from '../../core/fleet/triage-logic';
import type { LastContact } from '../../core/map/map-logic';
import { freshness } from '../../core/telemetry/telemetry-logic';

/**
 * Pure, Angular-free logic behind `CommandPage` (docs/plans/done/UX-REWORK-PLAN.md §U-c — the map-first
 * manager dashboard, superseding docs/plans/done/MVP3-PLAN.md §C-c's stacked-cards layout) — the entity
 * rail's attention-sort order and the collapsible-rail/collapsible-panel grid layout arithmetic,
 * split out so every rule is unit-testable without HTTP, the router, or a component, mirroring
 * every other page's own `*-logic.ts` split.
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
 *
 * **The "attention rules" section (severity thresholds, `attentionReasons`, `attentionAgeLabel`)
 * moved to `core/fleet/attention-logic.ts` in docs/plans/done/UI-REDESIGN-PLAN.md Wave 4** — the Inventory
 * reports page (`features/reports/**`) needed the identical rules for its own read-only attention
 * list, and this codebase has no precedent for one page importing another page's module (see
 * `core/fleet/device-logic.ts`'s doc comment). Every name below is re-exported so this file's own
 * pre-existing import sites (`asset-panel.ts`, `command-facade.ts`, `command-logic.spec.ts`) keep
 * working verbatim — see that module's own doc comment for the full rule set.
 *
 * **`BATTERY_ATTENTION_PERCENT`/`BATTERY_CRITICAL_PERCENT` are gone (S3, docs/plans/active/
 * ASSET-FLOWS-PLAN.md §2 D6)** — the hardcoded 20/10 pair they held is replaced by the served `GET
 * /api/ops/thresholds` value (`core/ops/thresholds-store.ts`), threaded as `batteryAttentionSeverity`/
 * `attentionReasons`'s own new optional `thresholds` parameter (defaulting to
 * `core/ops/thresholds-logic.ts#DEFAULT_BATTERY_THRESHOLDS`, 25/10) instead of two fixed constants.
 * Grep-verified zero importers of either name outside this re-export before deleting.
 */
export {
  attentionReasons,
  attentionAgeLabel,
  batteryAttentionSeverity,
  TELEMETRY_STALE_MS,
  type AttentionReason,
  type AttentionReasonKind,
  type AttentionSeverity,
  type BatteryAttentionSeverity,
} from '../../core/fleet/attention-logic';

// --- Entity rail (docs/plans/done/UX-REWORK-PLAN.md §U-c bullet 1) -----------------------------------------

export interface EntityRow {
  readonly asset: AssetAttention;
  /** Most severe first — same order `attentionReasons` already returns; empty when quiet. */
  readonly reasons: readonly AttentionReason[];
  /** The most severe triggered reason's own severity, or `'ok'` for a quiet asset. */
  readonly severity: AttentionSeverity | 'ok';
}

/**
 * Higher first — the max rank of a row's own triggered reasons, or `0` for a quiet row. Exported
 * (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3) so it can double as `buildRailGroups`'
 * own `attentionRank` callback below, in addition to `buildEntityRows`' own plain-array sort — one
 * ranking rule, two different orderings built from it.
 */
export function rowRank(row: EntityRow): number {
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
 *
 * `gpsFixTypeByAssetId` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d, optional) feeds each asset's own
 * `gps-degraded` reason (see `gpsDegradedReason`'s own doc comment) — `CommandPage` builds this from
 * `FleetMapStore.markers()`; an asset with no entry (not currently plotted/live) simply never
 * triggers that one reason, exactly like every other "unknown, not fabricated" gap in this app.
 *
 * `geofenceBreachesByAssetId` (docs/plans/done/OPS-CORE-PLAN.md §G-c, optional) is the identical shape for the
 * new top-rank `geofence-breach` reason — `CommandPage` builds this from
 * `core/geofence/geofence-logic.ts#groupBreachesByAsset(activeGeofenceBreaches(liveStore.liveEvents()))`.
 *
 * `pipelineErrorMessagesByStreamId` (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4, optional) is keyed by
 * `streamId`, not `assetId` — a `PIPELINE_ERROR` event carries only the stream it happened on (see
 * `core/system-events/system-events-logic.ts#activePipelineErrorMessagesByStreamId`'s own doc
 * comment) — so each row resolves its own entry via `asset.streamId`, which is itself only present
 * while `asset.streaming` is `true` (`AssetAttention.streamId`'s own doc comment); an asset that
 * isn't streaming can never carry a stale pipeline-error reason as a result, with no extra code here.
 */
export function buildEntityRows(
  assets: readonly AssetAttention[],
  gpsFixTypeByAssetId?: ReadonlyMap<string, number>,
  geofenceBreachesByAssetId?: ReadonlyMap<string, readonly GeofenceBreach[]>,
  pipelineErrorMessagesByStreamId?: ReadonlyMap<string, string>,
): readonly EntityRow[] {
  const rows: EntityRow[] = assets.map((asset) => {
    const reasons = attentionReasons(
      asset,
      gpsFixTypeByAssetId?.get(asset.assetId),
      geofenceBreachesByAssetId?.get(asset.assetId),
      asset.streamId ? pipelineErrorMessagesByStreamId?.get(asset.streamId) : undefined,
    );
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

// --- Rail triage (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3 — "the rail triages like
// /fly") ------------------------------------------------------------------------------------------

/**
 * One `EntityRow` widened with the four fields `core/fleet/triage-logic.ts#TriageCandidate` needs
 * (`AssetAttention`, this row's own `.asset`, carries `categoryId`/`streaming`/`telemetryAgeMs`, not
 * `category`/`status`/`lastUsedAt` — see that DTO's own doc comment) plus the rail row's own honest
 * offline-age text. Every field here is a straight rename or arithmetic derivation of a value
 * `buildEntityRows` already fetched — never a fabricated one.
 */
export interface RailRow extends EntityRow {
  /** `asset.displayName`, hoisted to the top level — `TriageCandidate` (`core/fleet/triage-logic.ts`)
   *  reads `displayName` directly, matching `AssetSummary`'s own shape; `EntityRow.asset` nests it one
   *  level deeper than that DTO does. */
  readonly displayName: string;
  readonly category: string;
  readonly status: AssetStatus;
  readonly lastUsedAt?: string;
  /**
   * The row's own right-hand text for a non-streaming asset — `Offline · 3d` (never the bare word
   * "Offline"), or `undefined` while streaming (the row's live dot already says so). Reuses
   * `core/fleet/triage-logic.ts#offlineLabel` against `lastUsedAt` above rather than a bespoke
   * format string, so this rail's offline age reads identically to `/fly`'s own picker card.
   */
  readonly offlineAge?: string;
}

function toRailRow(row: EntityRow, nowMs: number): RailRow {
  const asset = row.asset;
  // `telemetryAgeMs` is a duration, not a timestamp — recovering an absolute `lastUsedAt` from it
  // (rather than inventing a fresh sort/label input) is what lets this row satisfy `TriageCandidate`
  // and reuse `offlineLabel` unchanged; `undefined` in, `undefined` out (no sample ever arrived).
  const lastUsedAt = asset.telemetryAgeMs === undefined ? undefined : new Date(nowMs - asset.telemetryAgeMs).toISOString();
  return {
    ...row,
    displayName: asset.displayName,
    category: asset.categoryId,
    status: asset.streaming ? 'STREAMING' : 'OFFLINE',
    lastUsedAt,
    offlineAge: asset.streaming ? undefined : offlineLabel({ lastUsedAt }, nowMs),
  };
}

/**
 * The Command rail's own two triage groups — same `core/fleet/triage-logic.ts#groupAndSort` `/fly`'s
 * picker uses, extended with this wave's own `rowRank` as the `attentionRank` argument so "needs
 * attention (severity desc)" sits between "streaming first" and "last seen desc" (that function's
 * own doc comment has the exact tier order). `rows` is `CommandFacade#entityRows` — the identical,
 * already-attention-ranked rows the flat rail used before this wave — so grouping/re-sorting them
 * here is never a second attention derivation, only a different arrangement of the same one.
 */
export function buildRailGroups(rows: readonly EntityRow[], nowMs: number): PickerGroups<RailRow> {
  return groupAndSort(
    rows.map((row) => toRailRow(row, nowMs)),
    nowMs,
    rowRank,
  );
}

// --- Layout grid (docs/plans/done/UX-REWORK-PLAN.md §U-c bullet 5 — geometric separation, not z-index) -----

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
 * the map inset" incident (docs/plans/done/UX-QUICKWINS-PLAN.md QF-1, `features/fly/fly.css`'s own `.hud-map`
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

// --- "All quiet" earns its words (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.6 D5, frozen) ----------

export type QuietVerdict = 'quiet' | 'no-basis';

/**
 * Whether `AssetPanel`'s Status tab has actually earned an "All quiet" reading, or is standing on no
 * basis at all (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.6 D5). Before this, zero triggered
 * `reasons` alone rendered "All quiet" — true even for an asset that was never plotted, or one whose
 * last sample is days old; that isn't quiet, it's silence with nothing behind it.
 *
 * `'quiet'` only when **all three** (frozen) conjuncts hold:
 * 1. `reasons.length === 0` — nothing is actively flagged.
 * 2. `contact?.source === 'telemetry'` — the freshest fact on hand is a real sample, not merely a
 *    flight having once started (`'flight'`) or nothing at all (`'unknown'`) — see
 *    `core/map/map-logic.ts#LastContact`'s own three-tier account.
 * 3. `freshness(contact.ageSeconds) !== 'stale'` — and that sample is not itself stale
 *    (`core/telemetry/telemetry-logic.ts#freshness`, this app's one live/aging/stale rule, reused
 *    rather than a second staleness threshold living here).
 *
 * Any other combination is `'no-basis'`, which the caller renders as *"Nothing to report — no recent
 * telemetry from this asset ({last-contact label})."* — naming the actual fact instead of implying a
 * confidence the data doesn't back up.
 */
export function quietVerdict(reasons: readonly AttentionReason[], contact: LastContact | undefined): QuietVerdict {
  return reasons.length === 0 && contact?.source === 'telemetry' && freshness(contact.ageSeconds) !== 'stale'
    ? 'quiet'
    : 'no-basis';
}
