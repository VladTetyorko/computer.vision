import type { AssetStatus, AssetSummary, AssetUsage, GeoPosition, Membership, Role } from '../../core/api/models';
import { hasFix } from '../../core/geo/geo-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * Re-exported from `core/telemetry/telemetry-logic.ts`, which is now its canonical home
 * (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c follow-up — see that function's own doc comment for why:
 * `features/asset-detail/asset-detail.ts` needed the identical guard). Kept here too so this page's
 * own existing `trackingIdChanged` import site keeps working verbatim.
 */
export { trackingIdChanged } from '../../core/telemetry/telemetry-logic';

/**
 * Pure, Angular-free logic behind `FlyPage` (docs/plans/done/MVP3-PLAN.md §C-b) — split out so picker
 * ordering, remembered/requested-asset resolution, the "Replay last flight" link, watch-mode
 * parsing, and the keyboard boxes-cycle are unit-testable without HTTP, the router, or `document`,
 * mirroring every other page's own `*-logic.ts` split (`core/map/map-logic.ts`,
 * `features/replay/replay-logic.ts`, etc.).
 */

/**
 * The asset picker's own order: streaming assets first (an operator most likely wants to jump
 * straight into what's already in the air), then alphabetical by display name — a stable, obvious
 * secondary sort once the streaming/not split is applied. `Array#sort` is stable, so two assets
 * with the same `status` keep their relative order from the second comparison alone.
 */
export function sortAssetsForPicker(assets: readonly AssetSummary[]): readonly AssetSummary[] {
  return [...assets].sort((a, b) => {
    if (a.status !== b.status) {
      return a.status === 'STREAMING' ? -1 : 1;
    }
    return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/**
 * The one auto-redirect `/fly` performs on its own (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12,
 * docs/extracts/design/01-fly.md — "skip the picker when it has nothing to ask"): the operator's remembered
 * drone (`SettingsStore.flyAssetId`) only counts as "nothing to ask" while it is still actually
 * **streaming** — merely still existing (the pre-split `resolveActiveAssetId`'s own bar, back when
 * one component quietly switched between picker/cockpit with no URL change at all) is not enough. A
 * remembered drone that has simply landed is exactly the case the picker should still ask about,
 * not silently re-enter. `undefined` (render the picker) covers "nothing remembered", "remembered
 * id no longer exists" (archived/deleted since) and "remembered id exists but isn't streaming"
 * alike — `fly-redirect-guard.ts`'s own caller doesn't need to tell those apart, only whether it has
 * something to skip straight into.
 *
 * An explicit `?asset=` query param (Command's/Alerts' own drill-down links) is a *stronger* signal
 * than "remembered" and is handled separately, unconditionally, by the guard itself — see that
 * file's own doc comment for why it never calls this function at all.
 */
export function rememberedStreamingAssetId(
  assets: readonly AssetSummary[],
  rememberedAssetId: string | null,
): string | undefined {
  if (!rememberedAssetId) {
    return undefined;
  }
  const asset = assets.find((candidate) => candidate.assetId === rememberedAssetId);
  return asset?.status === 'STREAMING' ? rememberedAssetId : undefined;
}

/**
 * The most recent *finished* usage — "Replay last flight" — backing `assetId`'s
 * `recentUsages`, which is already newest-first (mirrors `features/asset-detail/asset-detail.html`'s
 * identical "Replay only makes sense for a usage that's actually done" rule).
 */
export function latestFinishedUsage(usages: readonly AssetUsage[]): AssetUsage | undefined {
  return usages.find((usage) => usage.endedAt !== undefined);
}

/**
 * `?watch=1` exactly (docs/plans/done/MVP3-PLAN.md §C-b — "Watch mode `?watch=1`: hides Start/Stop"). Only
 * this literal value counts, not any other truthy-looking string — a deliberate, narrow contract
 * for a query param a future cycle (C-c) constructs itself, not one a person is expected to type.
 */
export function isWatchMode(param: string | undefined): boolean {
  return param === '1';
}

/**
 * Whether `cockpit.html`'s video-surface "Detection is off — video only" chip should show
 * (docs/plans/done/CV-DEMAND-PLAN.md wave D3 — the honest affordance that replaces "an operator sees no
 * boxes and has no idea why"). Requires **both** that a stream is actually live and that detection
 * is off — not just the latter, unlike the tool-rail's own `rail-dot` tell (`cockpit.html`), which
 * fires off `detectionEnabled` alone because it previews what a not-yet-started stream *would* send.
 * This chip sits directly on the video, so "this is video only" must be a statement about a stream
 * that actually exists — before Start there is no video for it to describe, only the picture the
 * operator is about to get.
 *
 * `detectionEnabled` is now `CockpitFacade#detectionOn` — the running stream's own server-side value,
 * falling back to the draft only when nothing is running (docs/plans/done/STREAM-STATE-PLAN.md §3.1).
 * The rail dot reads the same signal, so the "previews what Start would send" reading above holds
 * exactly where it always did: before a stream exists, that resolved value *is* the draft.
 */
export function showDetectionOffChip(live: boolean, detectionEnabled: boolean): boolean {
  return live && !detectionEnabled;
}

/** How many rows the events ticker overlay shows at once — glanceable, not a full feed (see the Wall rail for that). */
export const TICKER_MAX_EVENTS = 4;

// --- Tool-rail / drawer wiring (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-D) ---------------------------
// The right-edge icon tool-rail replaces the split `mapVisible`/`detectionsStripOpen`/`cvPanelOpen`/
// `shortcutsOpen` open-flags with a single per-page `PanelState` (`core/panel-state.ts`). The ids
// below are the frozen set (D-D) — typed here (not just inline string literals in `fly.html`/
// `fly.ts`) purely so a typo in a rail button's `panels.toggle(...)`/`panels.isOpen(...)` call is a
// compile error, not a silently-dead button; `PanelState` itself stays a generic `string` id (it has
// no reason to know this page's specific ids) per its own doc comment.

/** The tool-rail's frozen ids (docs/plans/done/UI-REDESIGN-PLAN.md D-D; `rc` added by docs/plans/active/RC-CONTROL-PLAN.md
 * Phase 0 — the read-only RC transmitter monitor; `marks` added by docs/plans/done/TACTICAL-MARKS-PLAN.md M5
 * — the shared tactical-marks operational picture). This union's own declaration order is no longer
 * the rail's visual order: docs/conclusions/UX-SIMPLIFY-REVIEW.md F4 groups the rail by job — Control
 * (rc), Vision (cv), Situational (marks), Help (pinned last, separated) — see fly.html's
 * own comment above `.grid-rail` for the full grouping. Every id/gate/behavior below is unchanged;
 * only where each button sits in the rail moved. **`layers` was removed** (per direct user request)
 * — the detection-boxes rendering-mode control it used to hold its own drawer for now lives inside
 * the `cv` drawer instead (`cv-control-panel.html`'s own "Boxes rendering" section).
 *
 * **`detections` was merged into `cv` in wave W5** (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3): the
 * standalone strip-only drawer and the CV control drawer are now one "Vision" drawer, id kept as
 * `cv` — the least-disruptive choice (an operator's own persisted `localStorage` open-panel id still
 * opens the same drawer; there is no `detections` id left to migrate away from). See `cockpit.html`'s
 * own comment above the merged drawer block for the full reasoning, including why the drawer stays
 * reachable in watch mode (the strip) even though the control body (`<vision-cv-control-panel>`)
 * does not.
 *
 * **`flight` was merged into `rc` by docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C10**: mode and
 * arm/disarm are now the top section of the Controller drawer rather than a drawer of their own,
 * so there is one "how do I command this vehicle" button instead of two adjacent ones. Unlike the
 * `detections`→`cv` merge above, the retired id was the *persisted* one for anyone who last left
 * that drawer open, so it is migrated rather than dropped — see {@link migratedPanelId}. */
export type ToolRailPanelId = 'rc' | 'cv' | 'marks' | 'map' | 'help';

/**
 * Where a persisted open-drawer id should land today, for ids this rail no longer has.
 *
 * `UiStore` restores whatever string `localStorage` holds, and an id no button asks about restores
 * as "nothing open" — silently, which reads to a returning operator as the app forgetting. The one
 * retired id is `flight`, and its contents did not go away: they are inside `rc` (C10).
 *
 * @param stored the persisted id, or `null` when nothing was open
 * @returns the id to open now — `stored` unchanged for anything still on the rail
 */
export function migratedPanelId(stored: string | null): string | null {
  return stored === 'flight' ? 'rc' : stored;
}

/**
 * `Esc`'s own "closest thing open, first" priority (docs/plans/done/UI-REDESIGN-PLAN.md D-D: "Esc calls
 * `panels.close()`") — extracted from `fly.ts#collapseOverlays()` so the cascade order itself (the
 * CV setup modal, then any open tool-rail drawer, then the Stop-stream confirm, then the map inset)
 * is unit-testable without a real `PanelState`/DOM. Mirrors the pre-Wave-2 cascade's own order
 * (shortcuts/CV/detections were already the drawers-in-training even then, just modeled as separate
 * flags) with one change: the map inset moves to *last* rather than sharing the old detections-strip
 * slot, since it's the one overlay this wave deliberately keeps outside `PanelState` (D-D: "the map
 * inset stays a separate persisted toggle … since it is glanceable, not a modal drawer") and is the
 * least "in the way" of the three.
 *
 * **`cvSetupOpen` is checked first** (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1) — the setup
 * modal is the topmost overlay in the stack (it opens *over* the still-open Vision drawer, per the
 * plan's "opening it must not close the tool-rail drawer"), so `Esc` must close it alone on the
 * first press, leaving the drawer beneath it open for a second `Esc` to then close via `'panel'`.
 */
export function nextCollapseAction(state: {
  readonly cvSetupOpen: boolean;
  readonly panelOpen: boolean;
  readonly stopConfirmOpen: boolean;
  readonly mapVisible: boolean;
}): 'cv-setup' | 'panel' | 'stop-confirm' | 'map' | null {
  if (state.cvSetupOpen) {
    return 'cv-setup';
  }
  if (state.panelOpen) {
    return 'panel';
  }
  if (state.stopConfirmOpen) {
    return 'stop-confirm';
  }
  if (state.mapVisible) {
    return 'map';
  }
  return null;
}

/**
 * Whether `candidateAssetId` is the header switcher `<option>` that should carry the native
 * `selected` property (docs/plans/done/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2 — "switcher shows wrong selected
 * drone when the active asset isn't first in option order"). Extracted so the fix is unit-testable
 * without a real `<select>`/`<option>` DOM.
 *
 * **Root cause this replaces**: `fly.html` used to bind `[value]="activeAssetId()"` on the
 * `<select>` itself, with every `<option>` built by the same `@for` that is the select's own
 * content. Angular applies an element's own property bindings before it patches that element's
 * child content, so on first paint the `<select>`'s `value` property was written before any
 * `<option>` existed to match it. Per the HTML spec, setting a `<select>`'s `value` to a string
 * with no matching `<option>` selects nothing, and the browser then falls back to whichever
 * `<option>` is first in *document* order — not the active asset. Because Angular only re-writes a
 * property binding when the bound expression's value actually changes, and `activeAssetId()`
 * hadn't changed since that failed first write, nothing ever corrected it afterwards: the switcher
 * stuck on the first-listed drone (per `sortAssetsForPicker`'s streaming-then-alphabetical order)
 * whenever the active asset wasn't that one.
 *
 * Binding `[selected]` per `<option>` (via this function) instead sidesteps the ordering race
 * entirely: each option applies its own `selected` property the moment *that node* is created or
 * re-checked, never depending on a sibling — least of all the `<select>` itself — existing first.
 */
export function isSwitcherOptionSelected(candidateAssetId: string, activeAssetId: string | undefined): boolean {
  return candidateAssetId === activeAssetId;
}

// --- Picker asset card (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §3 — "the info-less asset card on Fly ...
// becomes an asset card") ------------------------------------------------------------------------
//
// The picker card used to show only a `displayName` and, while streaming, a bare "Streaming" chip
// — nothing else, silently whole-card-clickable (the plan's own screenshot evidence). These three
// pure derivations back the rebuilt card's extra facts. **No new HTTP call backs any of them** —
// every input is a field `AssetSummary` (the picker's existing `listAssets()` response) already
// carries. Battery is deliberately **not** among them: `AssetSummary` has no such field, and the
// per-asset battery reading this app does have (`TelemetryStore`) only exists for the one asset
// currently tracked by the cockpit (component-provided, one instance per route) — showing it on
// every picker card would mean one more `track()`-worth of polling per card, which is exactly the
// new-request fan-out this task was told to avoid. Omitted rather than fabricated, same posture as
// this app's other honest gaps (e.g. `fly-osd.ts`'s missing speed chip).

/** The picker/asset card's stream-state word (U-a2 §1: "stream state with the word", never color-only). */
export function streamStateLabel(status: AssetStatus): 'Streaming' | 'Offline' {
  return status === 'STREAMING' ? 'Streaming' : 'Offline';
}

/**
 * The card's "last seen" fact — elapsed time since `lastUsedAt`, reusing
 * `core/telemetry/telemetry-logic.ts#humanAge` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4,
 * §2 N4 — "one age vocabulary"), e.g. `"4m 07s ago"` for a fresh one, `"3d 18h ago"` for a stale
 * one — **not** `core/stream-info-logic.ts#formatDuration` (the zero-padded, hour-capped session
 * *duration* renderer this card used before this cycle's W5: a raw `"170h 20m ago"` is a number
 * nobody parses, the exact class of bug N4 already fixed everywhere else this age reads). `undefined`
 * when the asset has never been used — the card omits the fact entirely rather than showing a
 * fabricated placeholder. `nowMs` is a parameter (not `Date.now()` read in here) purely so this
 * stays deterministic under test; `FlyPage` supplies the real clock.
 */
export function lastSeenLabel(lastUsedAt: string | undefined, nowMs: number): string | undefined {
  if (!lastUsedAt) {
    return undefined;
  }
  const elapsedSeconds = Math.max(0, (nowMs - Date.parse(lastUsedAt)) / 1000);
  return `${humanAge(elapsedSeconds)} ago`;
}

/**
 * The card's "position" fact — `lat, lon` to 4 decimal places (~11m precision, plenty for a
 * glance), `undefined` when the asset has never reported a fix. Altitude is deliberately left out
 * here — the card states *where*, not *how high*; altitude already has its own home in the cockpit
 * OSD once actually flying (`fly-osd.ts`).
 *
 * **Does not itself gate on {@link hasFix}** — `positionFact` below is the one caller that needs
 * the no-fix distinction (a picker card); `fly-osd.ts#positionText` calls this directly for the
 * cockpit's own live position readout, unchanged by this function's own contract.
 */
export function positionLabel(position: GeoPosition | undefined): string | undefined {
  if (!position) {
    return undefined;
  }
  return `${position.latitude.toFixed(4)}, ${position.longitude.toFixed(4)}`;
}

/** One rendered fact — `dt`/`dd` register a `<dl class="picker-card-facts">` row needs: `mono` for
 *  a real numeric reading, `faint` for a structural "known but not meaningful" label. Mirrors
 *  `features/asset-detail/asset-detail-logic.ts#TelemetryFactRow`'s identical two-register shape. */
export interface PositionFact {
  readonly value: string;
  readonly mono?: boolean;
  readonly faint?: boolean;
}

/**
 * The picker card's own "position" fact (docs/plans/active/OPERATOR-UX-4-PLAN.md finding 1 of this
 * cycle's W5 — reproduced live: two "Your vehicles" cards for an offline rover printed a
 * confident-looking `POSITION 0.0000, 0.0000`, Null Island read as a real fix). `undefined` when
 * the asset has never reported a position at all — the card omits the fact entirely, unchanged.
 * When a position exists but carries no real fix (`core/geo/geo-logic.ts#hasFix` — a MAVLink
 * `GLOBAL_POSITION_INT` with no GPS lock), this renders the faint structural `'No GPS fix yet'`,
 * matching `features/asset-detail/asset-detail-logic.ts#positionFact`'s own wording/register for
 * the identical fact (that function's own private name, unrelated collision — this is the picker
 * card's own copy, not an import, since the two pages' fact-row shapes differ:
 * `TelemetryFactRow`'s `label` field has no picker-card use). Otherwise the real `lat, lon` in the
 * `.mono` numeric register, via `positionLabel` above.
 */
export function positionFact(position: GeoPosition | undefined): PositionFact | undefined {
  if (!position) {
    return undefined;
  }
  if (!hasFix(position)) {
    return { value: 'No GPS fix yet', faint: true };
  }
  return { value: positionLabel(position) as string, mono: true };
}

// --- Header switcher / "All drones" merge (docs/plans/done/UX-REWORK-PLAN.md §U-a bullet 4 — "Merge 'All
// drones' + drone <select> into one switcher control") -------------------------------------------

/**
 * Sentinel `<option>` value for "exit to the full picker", folded into the header's own drone
 * switcher `<select>` as its last entry instead of a separate "All drones" button sitting next to
 * it (the redundancy the plan names). No real asset id can ever equal this string — asset ids are
 * `AssetId.random()`-shaped UUIDs, never this literal — so it can share the same `(change)` handler
 * unambiguously with every genuine asset option.
 */
export const ALL_DRONES_OPTION_VALUE = '__all-drones__';

/** Whether the switcher's `(change)` value is the "All drones" sentinel rather than a real asset id. */
export function isAllDronesOption(value: string): boolean {
  return value === ALL_DRONES_OPTION_VALUE;
}

// --- Truthful empty state (docs/plans/done/OPS-UX-PLAN.md §2 A2, docs/conclusions/OPS-UX-REVIEW.md §U2) -------
//
// `GET /api/assets` is already visibility-scoped (OPS-UX-PLAN.md §1's own table: "Every read …
// unchanged" — this predates Wave A, nothing new here): a PILOT's `VisibilityScope` is
// `ASSIGNED_ASSETS`, so an empty `listAssets()` response for a PILOT already means "nothing is
// assigned to *you*", not "the fleet is empty" — the two cases this empty state has to tell apart
// were conflated only in the picker's own copy, never in the data. A MANAGER/ADMIN's empty response
// (`GROUPS`/`UNBOUNDED` scope) still means the fleet genuinely has nothing in it.

/** One resolved empty-picker view — `drone-picker.html`'s only source for what to render in the empty state. */
export interface PickerEmptyState {
  readonly title: string;
  readonly message: string;
  /** Only ADMIN/MANAGER get the CTA (docs/plans/done/OPS-UX-PLAN.md §2 A4 — the API now refuses `POST
   * /api/assets` from anyone else, so the door must not be dangled for a PILOT either). */
  readonly showAddSource: boolean;
}

/**
 * Comma-joined, de-duplicated group names from a PILOT's own `memberships` — never a person's name
 * (nothing here is asked of a directory the SPA doesn't have), and `undefined` rather than a
 * fabricated placeholder when `memberships` is empty (a real account always carries at least one
 * per `MeResponse`'s own contract, but the picker must still degrade honestly if that ever isn't
 * true — see `pickerEmptyStateCopy`'s own doc comment).
 */
function membershipGroupNames(memberships: readonly Membership[]): string | undefined {
  const names = [...new Set(memberships.map((membership) => membership.groupName))];
  return names.length > 0 ? names.join(', ') : undefined;
}

/**
 * The picker's empty-state copy, resolved by role — the one place `drone-picker.html`'s three-way
 * branch (error / loading / empty) collapses its "empty" leg down to a single view model, mirroring
 * `onboarding-logic.ts`'s "component reads a computed, never branches on `topRole` itself" convention.
 *
 * **PILOT** (docs/plans/done/OPS-UX-PLAN.md §2 A2, verbatim wording): *"No aircraft assigned to you
 * yet"*, naming their group from `memberships` when one resolves — never a fabricated person's name.
 * A PILOT with genuinely no membership at all (an edge the plan doesn't name a copy for) gets an
 * honest "could not determine your group" rather than either blank text or an invented one — the
 * same "degrade honestly, never fabricate" rule every other empty state in this app follows.
 *
 * **ADMIN/MANAGER**: unchanged title/message from before this task ("keeps its current, correct
 * message" — OPS-UX-PLAN.md §2 A2) — only `drone-picker.html`'s CTA target changes, from
 * `/devices?addSource=1` to `/add-source` (A4), which is why that link lives in the template, not
 * in this string.
 *
 * **An unresolved `topRole`** (`undefined`/`null` — every real caller reaches this page behind
 * `authGuard`, which already awaited `AuthStore.ready`, so this is a defensive fallback, not a path
 * any real visit takes) gets the fleet-empty title/message — never the PILOT copy, which would
 * claim a specific relationship ("assigned to you") the app cannot back up for an unknown role —
 * but **not** the CTA: `showAddSource` mirrors `canManageOrg` exactly (`ADMIN`/`MANAGER` only), so an
 * unconfirmed role never gets offered a door `POST /api/assets` (A4) might refuse.
 */
export function pickerEmptyStateCopy(topRole: Role | null | undefined, memberships: readonly Membership[]): PickerEmptyState {
  if (topRole === 'PILOT') {
    const groupNames = membershipGroupNames(memberships);
    return {
      title: 'No aircraft assigned to you yet',
      message: groupNames
        ? `Nobody has assigned you a drone in ${groupNames} yet — ask a manager there to assign one.`
        : 'Could not determine your group — ask a manager to assign you a drone.',
      showAddSource: false,
    };
  }
  return {
    title: 'No drones registered yet',
    message: 'Add a source from the Devices tab — the synthetic test drone flies a route with no hardware at all.',
    showAddSource: topRole === 'ADMIN' || topRole === 'MANAGER',
  };
}
