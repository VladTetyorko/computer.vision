import type { AssetAttention } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';
import { TELEMETRY_AGE_RED_SECONDS } from '../../core/telemetry/telemetry-logic';
import { gpsSeverity } from '../../core/telemetry/flight-state-logic';
import { geofenceBreachReasonText, type GeofenceBreach } from '../../core/geofence/geofence-logic';

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

export type AttentionReasonKind =
  | 'geofence-breach'
  | 'failsafe'
  | 'battery-critical'
  | 'telemetry-stale'
  | 'battery-low'
  | 'gps-degraded'
  | 'open-events';
export type AttentionSeverity = 'critical' | 'warning';

export interface AttentionReason {
  readonly kind: AttentionReasonKind;
  readonly severity: AttentionSeverity;
  /** A complete sentence — the panel's "why" line joins one or more of these with a space. */
  readonly text: string;
}

/**
 * How urgently each reason kind reads, highest first — an asset's overall rank is the max of its
 * own triggered reasons' ranks (see `buildEntityRows`), so an asset with *any* higher-rank reason
 * always outranks one with only lower-rank reasons, regardless of how many of the latter it has.
 *
 * `geofence-breach` (docs/OPS-CORE-PLAN.md §G-c) is the new **very top** rank, above even
 * `failsafe` — an aircraft that has physically crossed a keep-out/keep-in boundary is an active,
 * external, safety-and-legal-exposure event happening *right now* to something a manager doesn't
 * control the way a failsafe (an onboard, self-correcting response) already is; it outranks every
 * other signal precisely because a breach can co-occur with any of them and still needs to be the
 * first thing a manager's eye lands on.
 *
 * `failsafe` (docs/FC-INTEGRATIONS-PLAN.md F-d) is the next rank, above battery-critical — a
 * flight controller reporting an active failsafe is otherwise the single most urgent "this drone
 * needs attention right now" signal this app has, worse than a low/critical battery reading alone
 * (a failsafe can itself be *caused* by one, but the failsafe state is the more actionable fact).
 * Battery-critical and telemetry-stale share the next two ranks deliberately, both above
 * battery-low: a dead battery mid-flight and a lost telemetry link mid-flight are the same kind of
 * "this drone may not come back" risk. `gps-degraded` (new) ranks below battery-low but above
 * open-events — a degraded fix matters, but a battery running low is the more universally urgent of
 * the two. Open detection events rank lowest — informational, not a safety condition.
 */
const REASON_RANK: Readonly<Record<AttentionReasonKind, number>> = {
  'geofence-breach': 7,
  failsafe: 6,
  'battery-critical': 5,
  'telemetry-stale': 4,
  'battery-low': 3,
  'gps-degraded': 2,
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
 * `asset.failsafe` (docs/FC-INTEGRATIONS-PLAN.md F-d, `AssetAttention`'s own new field) —
 * `undefined`/`false` never trigger a reason, only an explicit `true` (never fabricated from
 * absent flight-controller data). States what the aircraft is doing, not an instruction — same
 * poka-yoke rule `core/telemetry/flight-state-logic.ts#flightBanner` follows for the cockpit's own
 * banner text.
 */
function failsafeReason(asset: AssetAttention): AttentionReason | undefined {
  if (asset.failsafe !== true) {
    return undefined;
  }
  return { kind: 'failsafe', severity: 'critical', text: 'Failsafe active — returning to home.' };
}

/**
 * `gpsFixType` is deliberately a *parameter*, not read off `AssetAttention` — the fleet-summary DTO
 * doesn't surface GPS quality (only `flightMode`/`armed`/`failsafe` do, see that interface's own doc
 * comment), so a caller with access to this asset's live marker (`core/map/map-logic.ts#FleetMarker`)
 * passes its `gpsFixType` in; a caller with no marker for this asset (not currently plotted/live)
 * simply omits it, and this reason never fires — "unknown" silently means "not evaluated", not "ok".
 * Reuses `flight-state-logic.ts#gpsSeverity` rather than re-deriving the same fix-quality tiers.
 */
function gpsDegradedReason(gpsFixType: number | undefined): AttentionReason | undefined {
  if (gpsFixType === undefined) {
    return undefined;
  }
  const severity = gpsSeverity(gpsFixType);
  if (severity === 'ok') {
    return undefined;
  }
  return {
    kind: 'gps-degraded',
    severity: severity === 'critical' ? 'critical' : 'warning',
    text: `GPS fix degraded (fix type ${gpsFixType}).`,
  };
}

/**
 * `geofenceBreaches` (docs/OPS-CORE-PLAN.md §G-c, optional) is, like `gpsFixType`, not carried by
 * `AssetAttention` at all — it's derived from the generic `LiveEvent` feed
 * (`core/geofence/geofence-logic.ts#activeGeofenceBreaches`, sourced from `LiveStore.liveEvents()`),
 * not the fleet-summary DTO. An empty/absent array never fires this reason — "no breach known", not
 * "definitely not breaching" (the honest-unknown rule every other optional reason input here follows).
 */
function geofenceBreachReason(breaches: readonly GeofenceBreach[] | undefined): AttentionReason | undefined {
  if (!breaches || breaches.length === 0) {
    return undefined;
  }
  return { kind: 'geofence-breach', severity: 'critical', text: geofenceBreachReasonText(breaches) };
}

/**
 * Every reason `asset` triggers, most severe first. An asset with none of these returns an empty
 * array — "all quiet" for that asset (still shown in the rail, just at the bottom, unflagged).
 *
 * `gpsFixType` (docs/FC-INTEGRATIONS-PLAN.md F-d, optional) and `geofenceBreaches`
 * (docs/OPS-CORE-PLAN.md §G-c, optional) are the two reason inputs not carried by `AssetAttention`
 * itself — see `gpsDegradedReason`'s/`geofenceBreachReason`'s own doc comments for where a caller
 * sources each.
 */
export function attentionReasons(
  asset: AssetAttention,
  gpsFixType?: number,
  geofenceBreaches?: readonly GeofenceBreach[],
): readonly AttentionReason[] {
  const reasons = [
    geofenceBreachReason(geofenceBreaches),
    failsafeReason(asset),
    batteryReason(asset.batteryPercent),
    telemetryReason(asset),
    gpsDegradedReason(gpsFixType),
    openEventsReason(asset.openEventCount),
  ].filter((reason): reason is AttentionReason => reason !== undefined);
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
 *
 * `gpsFixTypeByAssetId` (docs/FC-INTEGRATIONS-PLAN.md F-d, optional) feeds each asset's own
 * `gps-degraded` reason (see `gpsDegradedReason`'s own doc comment) — `CommandPage` builds this from
 * `FleetMapStore.markers()`; an asset with no entry (not currently plotted/live) simply never
 * triggers that one reason, exactly like every other "unknown, not fabricated" gap in this app.
 *
 * `geofenceBreachesByAssetId` (docs/OPS-CORE-PLAN.md §G-c, optional) is the identical shape for the
 * new top-rank `geofence-breach` reason — `CommandPage` builds this from
 * `core/geofence/geofence-logic.ts#groupBreachesByAsset(activeGeofenceBreaches(liveStore.liveEvents()))`.
 */
export function buildEntityRows(
  assets: readonly AssetAttention[],
  gpsFixTypeByAssetId?: ReadonlyMap<string, number>,
  geofenceBreachesByAssetId?: ReadonlyMap<string, readonly GeofenceBreach[]>,
): readonly EntityRow[] {
  const rows: EntityRow[] = assets.map((asset) => {
    const reasons = attentionReasons(
      asset,
      gpsFixTypeByAssetId?.get(asset.assetId),
      geofenceBreachesByAssetId?.get(asset.assetId),
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
