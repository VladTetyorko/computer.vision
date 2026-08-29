import type { AssetDetails, LifecycleState } from '../../core/api/models';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { triageOrder } from '../../core/fleet/triage-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * Pure logic behind the Assets page (this cycle's Assets/Devices/Warehouse inventory restructure —
 * no dedicated `docs/*-PLAN.md`, see `vision-web/MODULE.md`'s own changelog entry for the full
 * writeup: Assets and Devices became separate pages, Warehouse became a two-tile launcher): the
 * asset → view-model builder plus every search/filter this page's grid needs. Moved wholesale out of
 * `features/devices/devices-page-logic.ts` (where it lived as the "asset-first primary list"'s own
 * logic, docs/main/CYCLES-PLAN.md §11 CD-b) once the Devices page stopped rendering assets at all — this
 * codebase's own precedent (`core/fleet/device-logic.ts`'s doc comment) is that cross-page logic
 * moves to `core/`, but here there is exactly **one** consumer left (this page), so it stays local,
 * unit-tested, Angular-free, mirroring `features/devices/devices-page-logic.ts`'s own split.
 */

/**
 * One row of the Assets grid: an asset plus exactly what the card-level "Watch · Open · Archive"
 * actions need — `watchDeviceId` is the device `Watch` navigates to (`undefined` when the asset has
 * no VIDEO-capable device at all, in which case the card hides its Watch button), `deviceCount`/
 * `streaming` are the card's status decoration. Built from `AssetDetails` (not just `AssetSummary`)
 * because `deviceCount` needs the resolved device list.
 *
 * `asset` is typed `AssetDetails`, not `AssetSummary` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4 Wave 3) —
 * widened from the pre-two-pane version, which only ever exposed the `AssetSummary` field subset
 * even though `buildAssetListRows` has always been handed full `AssetDetails`. The two-pane detail
 * panel needs `asset.devices` (the per-device chip list, docs/extracts/design/04-assets.md's own mockup) and
 * this row is the panel's only data source, so the field is widened rather than threading a second
 * `AssetDetails` lookup through the facade for the one row currently selected.
 */
export interface AssetListRow {
  readonly asset: AssetDetails;
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
  readonly deviceCount: number;
  readonly streaming: boolean;
  readonly watchDeviceId?: string;
}

export function buildAssetListRows(
  assets: readonly AssetDetails[],
  liveDeviceIds: ReadonlySet<string>,
): readonly AssetListRow[] {
  return assets.map((asset) => {
    const lifecycle = asset.lifecycle ?? 'ACTIVE';
    return {
      asset,
      lifecycle,
      archived: lifecycle === 'DELETED',
      deviceCount: asset.devices.length,
      streaming: asset.devices.some((device) => liveDeviceIds.has(device.id)),
      watchDeviceId: findVideoDevice(asset.devices)?.id,
    };
  });
}

/**
 * The grid's default row order (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U5, §2 U5) — the same
 * triage `core/fleet/triage-logic.ts#triageOrder` gives `/fly`'s picker and Command's rail, applied
 * here as one flat list (this page has no Your-vehicles/Simulated section headers to carry
 * `groupAndSort`'s own two-group split) rather than the grouped form: streaming first, then
 * last-seen descending (never-seen last), real (non-simulated) assets ahead of simulated ones as the
 * outer tier. This page's `AssetListRow` carries no attention concept (that belongs to Command's rail,
 * not this grid), so `triageOrder`'s optional urgency tier is simply omitted — U5's own "then
 * attention if the row has it" clause, applied honestly: there isn't one here. Any future explicit
 * column-header sort this page adds should run *after* this and override it, never replace it — this
 * is only ever the list's opening order, not a competing sort mode.
 */
export function sortAssetListRowsByTriage(rows: readonly AssetListRow[], nowMs: number): readonly AssetListRow[] {
  return [...rows].sort((a, b) => triageOrder(a.asset, b.asset, nowMs));
}

/**
 * The grid's own "Last seen" column/card-fact text (docs/plans/active/OPERATOR-UX-5-PLAN.md finding
 * U5) — `2h 29m ago` / `6d 4h ago`, via the app's one age vocabulary
 * (`core/telemetry/telemetry-logic.ts#humanAge`); `Never seen` for an asset with no `lastUsedAt` at
 * all (never a fabricated age). Deliberately a different register from
 * `core/fleet/triage-logic.ts#offlineLabel`'s "Offline · 2h 29m" — this page's own State column
 * already carries "Offline"/"Live" as its own dot/chip, so repeating the word here would be
 * redundant; mirrors `features/fly/fly-logic.ts#lastSeenLabel`'s `<age> ago` wording (that function
 * returns `undefined` instead of "Never seen" — its own caller wraps that case separately — where
 * this page wants the text ready to render directly).
 */
export function lastSeenLabel(lastUsedAt: string | undefined, nowMs: number): string {
  if (!lastUsedAt) {
    return 'Never seen';
  }
  const ageSeconds = Math.max(0, (nowMs - Date.parse(lastUsedAt)) / 1000);
  return `${humanAge(ageSeconds)} ago`;
}

/** Hides archived rows unless the "show archived" toggle is on — the client-side half of it. */
export function filterAssetListRowsByArchived(
  rows: readonly AssetListRow[],
  showArchived: boolean,
): readonly AssetListRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}

/**
 * `?category=<slug>` filter (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2/QF-3 — the Command dashboard's
 * readiness-tile drill-down target; also the Assets page's own category `<select>`). Blank/absent
 * leaves every row; an unknown slug legitimately narrows to zero rows rather than falling back to
 * "show everything", since a caller linking here already knows the category exists.
 */
export function filterAssetListRowsByCategory(
  rows: readonly AssetListRow[],
  category: string | undefined,
): readonly AssetListRow[] {
  const slug = category?.trim();
  return slug ? rows.filter((row) => row.asset.category === slug) : rows;
}

/** The Assets page's lifecycle filter — deliberately only `active`/`deactivated`: `DELETED` rows
 *  are gated by the separate "show archived" toggle, not this control, so `'all'` here still means
 *  "every non-archived asset currently visible", not "every lifecycle state including archived". */
export type AssetStatusFilter = 'all' | 'active' | 'deactivated';

export function filterAssetListRowsByStatus(
  rows: readonly AssetListRow[],
  status: AssetStatusFilter,
): readonly AssetListRow[] {
  if (status === 'all') {
    return rows;
  }
  const target: LifecycleState = status === 'active' ? 'ACTIVE' : 'DEACTIVATED';
  return rows.filter((row) => row.lifecycle === target);
}

/** The Assets page's streaming filter — reads the same `row.streaming` the card's Live/Offline chip does. */
export type AssetStreamingFilter = 'all' | 'streaming' | 'offline';

export function filterAssetListRowsByStreaming(
  rows: readonly AssetListRow[],
  streaming: AssetStreamingFilter,
): readonly AssetListRow[] {
  if (streaming === 'all') {
    return rows;
  }
  return rows.filter((row) => (streaming === 'streaming' ? row.streaming : !row.streaming));
}

/** Case-insensitive substring match on the asset's display name — the Assets page's search box. */
export function searchAssetListRowsByName(
  rows: readonly AssetListRow[],
  query: string,
): readonly AssetListRow[] {
  const q = query.trim().toLowerCase();
  return q ? rows.filter((row) => row.asset.displayName.toLowerCase().includes(q)) : rows;
}

/**
 * Resolves the two-pane detail panel's row from `?sel=<assetId>` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4,
 * docs/extracts/design/04-assets.md) — looked up against **every** loaded row, not the search/filter-narrowed
 * `assetRows()`, so narrowing the filters never silently evicts an already-open selection out from
 * under the user. Returns `undefined` for a missing/blank id and equally for one that matches no
 * currently-loaded row (an asset archived or deleted by someone else since the link was shared) — the
 * caller (`AssetsFacade#selectedRow`) treats both identically: the pane just doesn't open, never a
 * crash or a blank panel (F6's own "degrade honestly" rule, restated for a stale deep link).
 */
export function findAssetRowById(rows: readonly AssetListRow[], id: string | undefined): AssetListRow | undefined {
  if (!id) {
    return undefined;
  }
  return rows.find((row) => row.asset.assetId === id);
}

/**
 * docs/plans/done/VISUAL-REFRESH-PLAN.md F5's "at most one chip per row" — collapses a row's lifecycle +
 * streaming facts into the one state a glance actually needs, priority archived > deactivated >
 * live > the default "offline" (an active, non-streaming asset — this app's own steady state,
 * rendered as a dot + plain text everywhere it's used rather than a fourth chip color, so the
 * merely-normal case doesn't compete for attention with the three exception states that do get a
 * chip). Used by the dense list, the card grid, and the two-pane detail panel's own status row —
 * one source of truth for what used to be two separately-rendered chips per surface.
 *
 * **Deliberately duplicated** in `features/devices/devices-page-logic.ts#describeDeviceState`
 * (byte-similar, `'stopped'`/`'Stopped'` instead of `'offline'`/`'Offline'`) rather than lifted to
 * `core/fleet/warehouse-logic.ts` alongside this codebase's usual "second consumer → `core/`" rule
 * — this task's own file scope is exactly `features/{assets,devices,roster,reports,activity}/**` +
 * `shared/ui/{two-pane,side-panel}.*`, `core/fleet/**` is out of it. Flagged as a follow-up for
 * whichever task next touches `core/fleet/warehouse-logic.ts`, not solved here.
 */
export type AssetStateKind = 'archived' | 'deactivated' | 'live' | 'offline';

export interface AssetStateDescriptor {
  readonly kind: AssetStateKind;
  readonly label: string;
}

export function describeAssetState(row: Pick<AssetListRow, 'lifecycle' | 'archived' | 'streaming'>): AssetStateDescriptor {
  if (row.archived) {
    return { kind: 'archived', label: 'Archived' };
  }
  if (row.lifecycle === 'DEACTIVATED') {
    return { kind: 'deactivated', label: 'Deactivated' };
  }
  if (row.streaming) {
    return { kind: 'live', label: 'Live' };
  }
  return { kind: 'offline', label: 'Offline' };
}

/** The `▤ ▦` list/card view toggle (docs/extracts/design/04-assets.md's suggested design) — persisted per user
 *  via `core/panel-state.ts#readPersistedString`/`writePersistedString`, the same idiom
 *  `features/command/command.ts`'s `railOpen`/`panelOpen` already use for their own collapse state. */
export type AssetViewMode = 'list' | 'card';

/**
 * Defends the persisted view-mode read against a corrupted/hand-edited `localStorage` value —
 * `readPersistedString` returns whatever string was last written (or `null`), with no type-level
 * guarantee it is still `'list' | 'card'`. Anything else falls back to `'list'`, the dense default
 * docs/extracts/design/04-assets.md calls for (F5 — a card grid is the wrong default for record browsing).
 */
export function parseAssetViewMode(raw: string | null): AssetViewMode {
  return raw === 'card' ? 'card' : 'list';
}
