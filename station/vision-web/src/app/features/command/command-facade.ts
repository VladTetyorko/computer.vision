import { DestroyRef, Injectable, type Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canAdminister } from '../../core/auth/auth-logic';
import { FleetStore } from '../../core/fleet/fleet-store';
import { buildTestDroneRequest } from '../../core/fleet/simulation-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import { FleetMapStore } from '../../core/map/map-store';
import { resolveLastContact, withLastContact, type LastContact } from '../../core/map/map-logic';
import { readPersistedFlag, readPersistedString, writePersistedFlag, writePersistedString } from '../../core/panel-state';
import { RouteStore } from '../../core/map-data/route-store';
import type { RouteSpan } from '../../core/map-data/route-logic';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { activeGeofenceBreaches, groupBreachesByAsset } from '../../core/geofence/geofence-logic';
import { activePipelineErrorMessagesByStreamId } from '../../core/system-events/system-events-logic';
import { MarksStore } from '../../core/map-data/marks-store';
import { LayersStore } from '../../core/map-data/layers-store';
import { DrawingsStore } from '../../core/map-data/drawings-store';
import { resolveInteractionMode } from '../../core/map-data/drawings-logic';
import { EventsStore } from '../../core/events/events-store';
import { selectEventMarkers } from '../../core/events/events-logic';
import { LiveStore } from '../../core/live/live-store';
import { isLiveAvailable } from '../../core/live/live-fallback-logic';
import {
  SUMMARY_FLOOR_INTERVAL_MS,
  anyNamesListedAsset,
  invalidationDelayMs,
  listedAssetIds,
} from '../../core/fleet/summary-refresh-logic';
import { WeatherStore } from '../../core/weather/weather-store';
import { fleetCentroid } from '../../core/weather/weather-logic';
import { buildEntityRows, buildRailGroups, commandGridColumns, type AttentionReason, type DetailPanelState, type EntityRow, type RailRow } from './command-logic';
import type { PickerGroups } from '../../core/fleet/triage-logic';
import { buildSetupChecklist, isFreshStation, type SetupChecklistRow } from '../../core/command/setup-checklist-logic';
import type { DrawingDraft } from '../../shared/map/tactical-map/tactical-map-logic';
import type { AssetAttention, FleetSummary, GroupSummary, UserSummary } from '../../core/api/models';

/**
 * The fleet-summary read's **not-open fallback** cadence (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md
 * §5 L8a) — unchanged from the unconditional timer this wave retired, but now reached only while
 * `LiveStore` is not `'open'`. While it is, the summary refetches on *invalidation* instead: a
 * `fleet`/`devices` arrival, or a `detection-events` arrival naming an asset this summary lists,
 * debounced to at most one request per `SUMMARY_INVALIDATION_DEBOUNCE_MS`, with
 * `SUMMARY_FLOOR_INTERVAL_MS` as the floor for the telemetry-derived fields that ride no topic.
 */
const SUMMARY_POLL_INTERVAL_MS = 5_000;

/**
 * The rail's age text and the pipeline-error decay window tick on this, independent of any fetch.
 * It matches the cadence those readouts already had when they piggybacked `refreshSummary()`'s own
 * 5s poll — which wave L8a turned event-driven, so the clock had to stop inheriting the transport's
 * cadence or the rail's "n seconds ago" would have quietly slowed to the 10s floor. Local, no requests.
 */
const CLOCK_TICK_MS = 5_000;

const RAIL_OPEN_KEY = 'vision.command.railOpen';
const PANEL_OPEN_KEY = 'vision.command.panelOpen';

/** `AssetPanel`'s Telemetry-tab Route control's own persisted preference (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md
 *  §3.4) — per-browser, not per-asset: the operator's "show me the last flight" preference should
 *  survive picking a different asset, the same way `hideSimulated` survives switching pages. */
const ROUTE_SPAN_KEY = 'vision.command.routeSpan';

/** Guards a stored preference that predates this key, or was hand-edited — falls back to `'last'` (§3.4's own default) rather than trusting an arbitrary string as a `RouteSpan`. */
function loadRouteSpan(): RouteSpan {
  const raw = readPersistedString(ROUTE_SPAN_KEY, 'last');
  return raw === 'off' || raw === 'last' || raw === 'last3' ? raw : 'last';
}

/**
 * `hideSimulated`'s persisted key (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3 — "the same
 * `vision.fly.hideSimulated` flag (one preference, both pages)") — deliberately the *same* key
 * `features/fly/drone-picker-facade.ts` already reads/writes, namespaced `vision.fly.*` rather than
 * `vision.command.*` like this file's own two keys above: an operator's "I don't want to see
 * simulated aircraft" preference is one fact about them, not a per-page one, so hiding them on `/fly`
 * and reopening `/command` (or vice versa) should not re-show what they just hid.
 */
const HIDE_SIMULATED_KEY = 'vision.fly.hideSimulated';

/**
 * `CommandPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md wave W2) — owns every store/service the page
 * needs (`FleetStore`, `FleetMapStore`, `GeofenceStore`, `LiveStore`, `WeatherStore`, `VisionApi`,
 * `Router`, `PollScheduler`), the fleet-summary poll, and every read-model/command the template binds
 * to. `CommandPage` itself injects only this facade (plus its own `UiStore` for the Zones overlay —
 * see that class's own doc comment for why the overlay stays component-local rather than moving here).
 *
 * **Provided per route activation**, listed alongside `FleetMapStore`/`WeatherStore` in
 * `CommandPage`'s own `providers` array (both page-scoped, not `providedIn: 'root'` — see their own
 * class doc comments) — all three share one injector, so this facade's own `inject(FleetMapStore)`/
 * `inject(WeatherStore)` resolve to the exact same instances `<vision-weather-chip>`
 * (children of `CommandPage`, injecting those stores directly themselves) already get. Moving the
 * *injection* here changes nothing about *which* instance anything sees — same DI subtree as before,
 * just orchestrated from one class instead of the component.
 *
 * **What moved here from `CommandPage` unchanged**: every computed read-model (`entityRows`,
 * `assetPositions`, `weatherPosition`, `selectedAsset`/`selectedMarker`/`selectedStream`,
 * `panelState`/`gridColumns`, `mapIsEmpty`), every command method (`selectAsset`, `watchAsset`,
 * `openAsset`, `addTestDrone`, …), and the two persisted, non-mutually-exclusive toggles
 * (`railOpen`/the panel's own open/collapsed preference — docs/plans/done/UX-REWORK-PLAN.md §U-b item 7) under
 * their original `localStorage` keys. None of this is exclusive-overlay state, so none of it belongs
 * on a `UiStore` — see `docs/plans/done/UI-ARCHITECTURE-PLAN.md`'s own "toggle-style state that is NOT mutually
 * exclusive … stays a plain boolean … but moves into its feature store/facade" rule.
 */
@Injectable()
export class CommandFacade {
  private readonly router = inject(Router);
  /** `selectAsset`/`closePanel`'s own `relativeTo` anchor — the same `router.navigate([], {relativeTo, queryParamsHandling: 'merge'})`
   *  idiom every sibling page's facade uses to keep a selection alive across reload/Back (`assets-facade.ts`,
   *  `devices-facade.ts`, `alerts-facade.ts`, `roster-facade.ts`, `replay-library-facade.ts`). */
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthFacade);
  private readonly fleet = inject(FleetStore);
  private readonly mapStore = inject(FleetMapStore);
  private readonly geofence = inject(GeofenceStore);
  private readonly events = inject(EventsStore);
  private readonly liveStore = inject(LiveStore);
  private readonly scheduler = inject(PollScheduler);
  private readonly weather = inject(WeatherStore);
  /** §3.4's on-demand route fetch — page-provided alongside `FleetMapStore`/`WeatherStore` in
   *  `CommandPage`'s own `providers` (see `RouteStore`'s own class doc comment for why). */
  private readonly routeStore = inject(RouteStore);

  private readonly summarySignal = signal<FleetSummary | undefined>(undefined);
  readonly summary = this.summarySignal.asReadonly();
  /** Only ever set when the *very first* load fails — a background poll failure silently degrades. */
  private readonly summaryErrorSignal = signal(false);
  readonly summaryError = this.summaryErrorSignal.asReadonly();
  private readonly includeArchivedSignal = signal(false);
  readonly includeArchived = this.includeArchivedSignal.asReadonly();

  /** The Zones panel's own zone list — a thin passthrough of `GeofenceStore.zones()` so `CommandPage`
   * never injects that store directly. */
  readonly zones = this.geofence.zones;

  /**
   * The three halves of the Common Operational Picture (docs/plans/done/MAP-REWORK-PLAN.md §5.2) — exposed as
   * whole stores (not thin passthroughs, unlike `zones` above): `command.html` wires
   * `<vision-tactical-map>`'s `[marks]`/`[layers]`/`[drawings]`/`[selectedMarkId]`/`(markSelected)`/
   * `(mapClicked)`/`(drawingCompleted)`/`(drawingSelected)` straight to them, and the
   * shared `shared/map/map-controls/**` components plus `features/command/marks-panel.ts` inject the
   * same `providedIn: 'root'` singletons directly (non-routed presentational children, mirroring
   * `zones-panel.ts` injecting `GeofenceStore` directly).
   */
  readonly marks = inject(MarksStore);
  readonly layers = inject(LayersStore);
  readonly drawings = inject(DrawingsStore);

  // --- Recent-flight routes (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.4, wave W3) ---------------
  // `AssetPanel` stays "deliberately dumb" (its own class doc comment) — every route read-model and
  // the span control's own state live here, threaded to the panel as plain inputs/an output, exactly
  // like every other fact that component shows.

  private readonly routeSpanSignal = signal<RouteSpan>(loadRouteSpan());
  readonly routeSpan = this.routeSpanSignal.asReadonly();
  readonly routes = this.routeStore.routes;
  readonly routesLoading = this.routeStore.loading;
  readonly routesError = this.routeStore.error;
  readonly routesNoUsages = this.routeStore.noUsages;

  setRouteSpan(span: RouteSpan): void {
    this.routeSpanSignal.set(span);
    writePersistedString(ROUTE_SPAN_KEY, span);
  }

  /**
   * The map's single `[interactionMode]`, folded from the two independent arming states that can
   * produce one — an armed mark palette and an armed drawing kind
   * (`core/map-data/drawings-logic.ts#resolveInteractionMode`). Neither store knows about the other;
   * this is the one place they meet.
   */
  readonly interactionMode = computed(() => resolveInteractionMode(this.marks.armed(), this.drawings.mode()));

  /**
   * `assetId → gpsFixType`, built from `FleetMapStore` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) — feeds
   * `command-logic.ts#attentionReasons`' `gps-degraded` reason via `buildEntityRows`'s own optional
   * second argument; an asset with no live marker (not currently plotted) simply has no entry, so that
   * one reason never fires for it — see `gpsDegradedReason`'s own doc comment for why this can't be
   * read off `AssetAttention` directly.
   */
  private readonly gpsFixTypeByAssetId = computed(() => {
    const byAssetId = new Map<string, number>();
    for (const marker of this.mapStore.markers()) {
      if (marker.gpsFixType !== undefined) {
        byAssetId.set(marker.assetId, marker.gpsFixType);
      }
    }
    return byAssetId;
  });

  /**
   * `assetId → active breaches` (docs/plans/done/OPS-CORE-PLAN.md §G-c), derived from `LiveStore.liveEvents()`
   * — the generic `event` SSE topic GEOFENCE_BREACH rides. Feeds `buildEntityRows`' top-rank
   * `geofence-breach` reason, same "optional map, by assetId" shape as `gpsFixTypeByAssetId` above.
   */
  private readonly geofenceBreachesByAssetId = computed(() =>
    groupBreachesByAsset(activeGeofenceBreaches(this.liveStore.liveEvents())),
  );

  /**
   * Wall-clock ms on its own {@link CLOCK_TICK_MS} tick —
   * `activePipelineErrorMessagesByStreamId`'s 15-minute decay window (docs/plans/done/SYSTEM-STATUS-PLAN.md
   * §3.4) needs *some* source of "time is passing" independent of new `LiveEvent`s arriving, or a
   * stream that errored once and then went silent would stay flagged forever until the next
   * unrelated live event happened to re-run this computed.
   *
   * This used to piggyback `refreshSummary()`'s own unconditional 5s poll, which was the cheaper
   * arrangement while that poll existed. Wave L8a made the fetch event-driven with a 10s floor, so
   * the piggyback would have silently halved the rail's age-text refresh rate — a UI regression
   * bought by a request saving nobody asked for. The clock is now its own thing, at the cadence it
   * always effectively had.
   */
  private readonly nowSignal = signal(Date.now());

  /**
   * `streamId → active PIPELINE_ERROR message` (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4), derived from
   * `LiveStore.liveEvents()` exactly like `geofenceBreachesByAssetId` above, but keyed by `streamId`
   * (a pipeline error carries no `assetId` — see that function's own doc comment) rather than
   * `assetId`. Feeds `buildEntityRows`' `pipeline-error` reason.
   */
  private readonly pipelineErrorMessagesByStreamId = computed(() =>
    activePipelineErrorMessagesByStreamId(this.liveStore.liveEvents(), this.nowSignal()),
  );

  readonly entityRows = computed(() =>
    buildEntityRows(
      this.summary()?.assets ?? [],
      this.gpsFixTypeByAssetId(),
      this.geofenceBreachesByAssetId(),
      this.pipelineErrorMessagesByStreamId(),
    ),
  );

  /**
   * Which assets `<vision-tactical-map>` should recolor `--color-danger` (docs/plans/done/VISUAL-REFRESH-PLAN.md
   * F7, task 2) — every `entityRows` row with a non-`'ok'` severity, by id. Reuses the rail's own
   * already-computed sort rather than a second attention derivation, so the rail and the map can
   * never disagree about which assets need attention.
   */
  readonly attentionAssetIds = computed<ReadonlySet<string>>(
    () => new Set(this.entityRows().filter((row) => row.severity !== 'ok').map((row) => row.asset.assetId)),
  );

  /**
   * `assetId → attention row` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N2, §2 N2) — the *same*
   * `EntityRow` objects `entityRows` already sorts, just addressable by id. This is the fix for a
   * rail-vs-panel disagreement reproduced live on the running dev server: the rail's row read CRIT
   * (a `geofence-breach`/`pipeline-error` reason, fed from `LiveStore` and threaded into
   * `buildEntityRows` but never into `AssetPanel`'s own, narrower `attentionReasons()` call) while the
   * panel it opened read "All quiet" for the identical asset — two independent derivations of the
   * same fact, free to drift. `AssetPanel` now takes `[reasons]` as a plain input fed from this map
   * (via `selectedAttentionReasons` below) instead of recomputing its own — one verdict, one source.
   */
  readonly attentionByAssetId = computed<ReadonlyMap<string, EntityRow>>(
    () => new Map(this.entityRows().map((row) => [row.asset.assetId, row])),
  );

  /**
   * The rail's own **Your vehicles** / **Simulated** groups (docs/plans/active/OPERATOR-UX-4-PLAN.md finding
   * N3, §2 N3 — "the rail triages like `/fly`"), replacing the old flat `entityRows`-as-one-list
   * rendering. Built from `entityRows` (never a second attention derivation) via
   * `command-logic.ts#buildRailGroups`; `nowSignal` (already ticked alongside the 5s summary poll —
   * see that signal's own doc comment) is the shared clock so this recomputes on the same cadence the
   * rail's age text already did.
   */
  readonly railGroups = computed<PickerGroups<RailRow>>(() => buildRailGroups(this.entityRows(), this.nowSignal()));

  /** Whether the rail's **Simulated** group is collapsed (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3)
   *  — see `HIDE_SIMULATED_KEY`'s own doc comment for why this is the identical persisted preference
   *  `/fly`'s picker uses, not a second, page-scoped one. The group header (with its own count) stays
   *  visible either way; only the row list beneath it collapses — mirrors `drone-picker.html`'s own
   *  "Hide simulated" behavior exactly. */
  readonly hideSimulated = signal(readPersistedFlag(HIDE_SIMULATED_KEY, false));

  toggleHideSimulated(): void {
    this.hideSimulated.update((hidden) => !hidden);
  }

  /** Every asset's currently-known position — threaded to the Zones panel's own draw-dialog advisory. */
  readonly assetPositions = computed(() => this.mapStore.markers().map((marker) => marker.position));

  /**
   * `assetId → LastContact` (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.3, wave W3 closing the
   * fallback gap wave W1 left open) — resolved per marker from whichever tier actually has an
   * answer: `AssetAttention.telemetryAgeMs` (the fleet-summary poll, tier 1) first, then
   * `AssetSummary.lastUsedAt` (`FleetMapStore.assets()`, tier 2 — the offline bucket gets no live
   * telemetry poll at all, so this is its only honest fact), `'unknown'` otherwise
   * (`resolveLastContact`'s own doc comment has the full three-tier account). `nowSignal` is the
   * same wall clock `pipelineErrorMessagesByStreamId` above already ticks off the 5s summary poll —
   * reused rather than a second clock, per CLAUDE.md rule 9 ("newest data … even if previous is
   * still available").
   */
  private readonly lastContactByAssetId = computed<ReadonlyMap<string, LastContact>>(() => {
    const attentionByAssetId = new Map(this.summary()?.assets.map((asset) => [asset.assetId, asset]) ?? []);
    const lastUsedAtByAssetId = new Map(this.mapStore.assets().map((asset) => [asset.assetId, asset.lastUsedAt]));
    const now = this.nowSignal();
    const result = new Map<string, LastContact>();
    for (const marker of this.mapStore.markers()) {
      result.set(
        marker.assetId,
        resolveLastContact(attentionByAssetId.get(marker.assetId), lastUsedAtByAssetId.get(marker.assetId), now),
      );
    }
    return result;
  });

  /**
   * `<vision-tactical-map>`'s `[assets]` (docs/plans/done/MAP-REWORK-PLAN.md §5.1). The map component is dumb
   * now — unlike the deleted `FleetMap`, which injected `FleetMapStore` itself and therefore only
   * worked on a page that provided it — so the store's markers are handed over as an input from
   * here, the one place already holding that store. Enriched with `lastContact` (§3.3/W3, above) so
   * the map's own tri-state freshness colouring and "Last contact …" popup line are honest for the
   * offline bucket too, not just the ones still getting a live telemetry poll.
   */
  readonly markers = computed(() => withLastContact(this.mapStore.markers(), this.lastContactByAssetId()));

  /**
   * The map's `[unplottedAssets]` legend count — assets with no position at all, which by
   * construction never appear in `markers` above (`core/map/map-logic.ts#buildMarker` returns
   * `undefined` for them). Only this facade knows the number, so the map is told rather than left to
   * guess it.
   */
  readonly unplottedAssets = computed(() => this.mapStore.buckets().noPosition.length);

  /**
   * The map's `[events]` — position-carrying detection events, most recent first, capped
   * (`selectEventMarkers`). Same reason as `markers` above: the deleted `FleetMap` injected
   * `EventsStore` directly; the store is still never activated/released here (the app-shell
   * notification bell keeps it warm for the whole session), this facade only reads it.
   */
  readonly eventMarkers = computed(() => selectEventMarkers(this.events.events()));

  // --- Weather go/no-go chip (docs/plans/done/OPS-CORE-PLAN.md §W) ------------------------------------------
  // Command's chip centers on the fleet centroid, not any one asset — this page's own fleet-summary
  // poll carries no per-asset `attributes`, so the wind limit here is always the plan's own default
  // rather than a specific asset's `windLimitMps` override (see `WeatherChip`'s own doc comment;
  // Fly's chip, scoped to one selected asset, is the one that reads that attribute).
  readonly weatherPosition = computed(() => fleetCentroid(this.assetPositions()));

  // --- Panel state memory (docs/plans/done/UX-REWORK-PLAN.md §U-b item 7 / §U-c bullet 5) -------------------
  // Persisted, but NOT mutually exclusive with anything else (docs/plans/done/UI-ARCHITECTURE-PLAN.md), so both
  // stay plain signals here rather than joining the Zones overlay's `UiStore` group.
  private readonly railOpenSignal = signal(readPersistedFlag(RAIL_OPEN_KEY, true));
  readonly railOpen = this.railOpenSignal.asReadonly();
  private readonly panelOpenPreferenceSignal = signal(readPersistedFlag(PANEL_OPEN_KEY, true));

  // --- Selection (docs/plans/done/UX-REWORK-PLAN.md §U-c bullet 1) -------------------------------------------
  private readonly focusTickSignal = signal(0);
  private readonly selectedAssetIdSignal = signal<string | null>(null);
  readonly selectedAssetId = this.selectedAssetIdSignal.asReadonly();
  private readonly selectedVideoDeviceIdSignal = signal<string | undefined>(undefined);
  readonly selectedVideoDeviceId = this.selectedVideoDeviceIdSignal.asReadonly();

  readonly selectedAsset = computed<AssetAttention | undefined>(() =>
    this.summary()?.assets.find((asset) => asset.assetId === this.selectedAssetId()),
  );

  readonly selectedMarker = computed(() => {
    const assetId = this.selectedAssetId();
    return assetId ? this.markers().find((marker) => marker.assetId === assetId) : undefined;
  });

  /** `AssetPanel`'s own `[reasons]` input — see `attentionByAssetId`'s own doc comment for the
   *  disagreement this replaces. `[]` (not "quiet" fabricated from nothing) whenever nothing is
   *  selected or the selection has no row yet (the very first render, before `entityRows` exists). */
  readonly selectedAttentionReasons = computed<readonly AttentionReason[]>(() => {
    const assetId = this.selectedAssetId();
    return (assetId ? this.attentionByAssetId().get(assetId) : undefined)?.reasons ?? [];
  });

  readonly selectedStream = computed(() => {
    const deviceId = this.selectedVideoDeviceId();
    return deviceId ? this.fleet.streamFor(deviceId) : undefined;
  });

  /**
   * `'hidden'` whenever there is no *resolvable* selection — tied to `selectedAsset()`, not the
   * raw id, so a selection that stops existing in the current summary (archived, or the
   * "include archived" toggle flipped off underneath it) self-heals the layout back to no panel
   * rather than reserving a grid track for content that no longer renders. Otherwise the manager's
   * own open/collapsed preference.
   */
  readonly panelState = computed<DetailPanelState>(() =>
    this.selectedAsset() === undefined ? 'hidden' : this.panelOpenPreferenceSignal() ? 'open' : 'collapsed',
  );

  readonly gridColumns = computed(() => commandGridColumns(this.railOpen(), this.panelState()));

  readonly mapIsEmpty = computed(() => this.mapStore.assets().length === 0);
  private readonly addingTestDroneSignal = signal(false);
  readonly addingTestDrone = this.addingTestDroneSignal.asReadonly();

  // --- "Set up this station" checklist (docs/plans/done/OPS-UX-PLAN.md §3 B2) ------------------------------
  // UNBOUNDED-scope-only (`canAdminister(AuthFacade.scopeKind())`, docs/plans/active/AUTH-ROLES-PLAN.md
  // §3.2, wave W2 — moved off `topRole === 'ADMIN'` for the same reason `canManageOrg`/
  // `canAdministerRegistry` did) — an ADMIN is the one session that can act on every row (create a
  // group, create a user, add a source, assign a pilot), so a MANAGER/PILOT landing on `/command` never
  // sees a checklist pointing at doors they can't open. **Dev parity**: with `vision.auth.enabled=false`
  // the backend's fixed dev principal resolves to `scopeKind: 'UNBOUNDED'` (`AuthFacade`'s own class doc
  // comment, "Dev parity" paragraph) — so this gate is effectively "everyone" on a default install,
  // which is the *right* call here (unlike, say, `core/shell/landing-logic.ts`'s stricter decision for a
  // different question): a fresh unsecured station genuinely needs this setup walked through by whoever
  // is sitting at it, same as a real ADMIN would.
  private readonly setupUsersSignal = signal<readonly UserSummary[]>([]);
  private readonly setupGroupsSignal = signal<readonly GroupSummary[]>([]);
  /** Guards the very first render from a false "fresh!" flash before `listUsers`/`listGroups` land — see `showSetupChecklist`. */
  private readonly setupDataLoadedSignal = signal(false);
  /**
   * Best-effort "does *any* asset anywhere already have a pilot assigned" — mirrors
   * `RosterFacade`'s own per-asset `listAssetPilots` fan-out (`features/roster/roster-facade.ts`),
   * scoped down to "any at all" rather than a full `assetId → pilots` map, since the checklist only
   * ever needs the yes/no. Starts `false` and only ever flips to `true`: an admin unassigning the one
   * pilot they just assigned should not make a completed row reappear and re-nag them.
   */
  private readonly hasAnyPilotAssignmentSignal = signal(false);

  /**
   * Whether to show the checklist at all — ADMIN, plus `isFreshStation` (see that function's own doc
   * comment for the "no users beyond the seeded three, or zero assets" rule) — gated on
   * `setupDataLoadedSignal` so this never flashes `true` off an empty `[]` default before the user/group
   * load resolves.
   */
  readonly showSetupChecklist = computed(
    () => this.setupDataLoadedSignal() && canAdminister(this.auth.scopeKind()) && isFreshStation(this.setupUsersSignal(), this.summary()?.totalAssets ?? 0),
  );

  /** The five rows themselves — only built while `showSetupChecklist` is true (no reason to compute it otherwise). */
  readonly setupChecklist = computed<readonly SetupChecklistRow[]>(() =>
    buildSetupChecklist(
      this.setupUsersSignal(),
      this.setupGroupsSignal(),
      this.summary()?.totalAssets ?? 0,
      this.hasAnyPilotAssignmentSignal(),
      this.auth.authEnabled(),
    ),
  );

  private appliedDeepLink = false;

  constructor() {
    // No `refreshSummary()` here: `applySummaryTransport` below fetches once on its own first run,
    // whichever transport it resolves to. Calling it here as well would double-fetch at construction
    // -- the same reason `MarksStore.activate()` routes through `applyTransport` instead of
    // refreshing directly.
    const stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stopClock);

    // D1 + L8a: while live is open the summary refetches on invalidation plus a floor; while it is
    // not, the original 5s poll is the fallback. Same `liveGated` shape as every store this plan
    // gates — see `core/map-data/marks-store.ts#applyTransport` for the frozen table.
    effect(() => {
      this.applySummaryTransport(isLiveAvailable(this.liveStore.connectionState()));
    });

    // The invalidation itself. `fleet`/`devices` arrivals are structural by definition; a
    // `detection-events` arrival only counts if it names an asset this summary already lists
    // (`openEventCount` is the only summary field it can move). The first run is swallowed — the
    // constructor's own `refreshSummary()` above already covers construction.
    effect(() => {
      const detectionEvents = this.liveStore.detectionEvents();
      const structural = this.liveStore.fleet() !== undefined || this.liveStore.devices() !== undefined;
      untracked(() => {
        const newEvents = detectionEvents.slice(this.processedDetectionEventCount);
        this.processedDetectionEventCount = detectionEvents.length;
        if (!this.summaryBootstrapped) {
          this.summaryBootstrapped = true;
          return;
        }
        if (!isLiveAvailable(this.liveStore.connectionState())) {
          return; // the 5s fallback poll already covers this case
        }
        if (!structural && !anyNamesListedAsset(newEvents, listedAssetIds(this.summary()))) {
          return;
        }
        this.invalidateSummary();
      });
    });

    inject(DestroyRef).onDestroy(() => this.teardownSummaryTransport());

    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: `/command` renders `<vision-tactical-map>`, so this facade
    // is a direct consumer of all four map-data poll stores for its own lifetime — see each store's
    // own "polling is demand-gated" doc section for why this is now required, not optional.
    this.geofence.activate();
    this.marks.activate();
    this.layers.activate();
    this.drawings.activate();
    inject(DestroyRef).onDestroy(() => {
      this.geofence.release();
      this.marks.release();
      this.layers.release();
      this.drawings.release();
    });

    effect(() => writePersistedFlag(RAIL_OPEN_KEY, this.railOpenSignal()));
    effect(() => writePersistedFlag(PANEL_OPEN_KEY, this.panelOpenPreferenceSignal()));
    effect(() => writePersistedFlag(HIDE_SIMULATED_KEY, this.hideSimulated()));

    // Keeps the weather chip fresh as the fleet centroid moves — `WeatherStore.track` itself
    // no-ops instantly unless the 10-minute cache is actually stale (docs/plans/done/OPS-CORE-PLAN.md §W).
    effect(() => this.weather.track(this.weatherPosition()));

    // The route interaction contract, frozen (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.4):
    // selecting an asset auto-shows its route at the current span; changing span while selected
    // re-shows at the new span; deselecting clears it. One effect over both signals covers all
    // three — `RouteStore.show`/`hide` are themselves idempotent/generation-guarded, so there is no
    // "did this already fire" bookkeeping needed here.
    effect(() => {
      const assetId = this.selectedAssetId();
      const span = this.routeSpanSignal();
      if (assetId) {
        void this.routeStore.show(assetId, span);
      } else {
        this.routeStore.hide();
      }
    });

    void this.loadSetupChecklistData();

    // The checklist's own "assign a pilot" row (docs/plans/done/OPS-UX-PLAN.md §3 B2): re-checks pilot
    // coverage on every fleet-summary tick while the checklist is showing and nothing has been found yet
    // — `hasAnyPilotAssignmentSignal` only ever flips false→true (see its own doc comment), so this stops
    // polling for good the moment it finds one, rather than fanning out `listAssetPilots` forever.
    // `untracked()` (mirrors `shared/map/tactical-map/tactical-map.ts`'s identical use) reads `summary()`
    // for the async call without making *that* the thing this effect depends on twice over — `summary`
    // is already the signal driving re-runs here.
    effect(() => {
      const shouldCheck = this.showSetupChecklist() && !this.hasAnyPilotAssignmentSignal();
      const assets = this.summary()?.assets;
      if (!shouldCheck || !assets || assets.length === 0) {
        return;
      }
      untracked(() => void this.refreshPilotCoverage(assets));
    });
  }

  /** Loads the checklist's own two small lists once — best-effort, mirrors every other poller's silent-degrade rule: a failed load just leaves the checklist not-yet-shown rather than surfacing a page-blocking error over the map. */
  private async loadSetupChecklistData(): Promise<void> {
    await this.auth.ready;
    if (!canAdminister(this.auth.scopeKind())) {
      return; // never fetched for a session that could never see the checklist anyway
    }
    try {
      const [users, groups] = await Promise.all([this.api.listUsers(), this.api.listGroups()]);
      this.setupUsersSignal.set(users);
      this.setupGroupsSignal.set(groups);
    } catch {
      // Silent-degrade: the checklist simply never turns on this session; nothing else on
      // `/command` depends on this data, so there is nothing to retry into.
    } finally {
      this.setupDataLoadedSignal.set(true);
    }
  }

  /**
   * One best-effort pass over `assets`, stopping at the first asset found with ≥1 assigned pilot —
   * see the constructor effect's own doc comment for why this only runs while it can still find
   * something new. Sequential (not `RosterFacade.load`'s parallel `Promise.all` fan-out) and with an
   * early exit: this only ever needs a yes/no, not a full `assetId → pilots` map, so stopping at the
   * first hit is strictly less work than always fetching every asset.
   */
  private async refreshPilotCoverage(assets: readonly AssetAttention[]): Promise<void> {
    for (const asset of assets) {
      try {
        const pilots = await this.api.listAssetPilots(asset.assetId);
        if (pilots.length > 0) {
          this.hasAnyPilotAssignmentSignal.set(true);
          return;
        }
      } catch {
        // One asset's pilots failing to resolve never blocks checking the rest — same
        // catch-and-continue idiom `RosterFacade.load` uses per asset.
      }
    }
  }

  /**
   * One-shot `?asset=` deep-link resolution, mirroring `fly.ts#initPicker`'s own "runs once, never
   * inside the periodic poll" rule — a later query-param change while already on `/command` should
   * not silently override a manager's own subsequent click elsewhere in the rail/map.
   *
   * `requestedAssetId` is `CommandPage`'s own routed `input()` accessor (aliased `asset`) — a facade
   * can't declare an Angular component input itself, so `CommandPage`'s constructor passes the
   * signal-accessor in once; reading it (and `summary()`) inside this `effect()` still re-runs on
   * every later change to either, exactly like the effect this replaces.
   *
   * Since BUG 4's fix, `selectAsset` itself writes `?asset=`, which makes `requestedAssetId()` change
   * too — `selectAsset` latches `appliedDeepLink = true` *before* navigating for exactly this reason,
   * so this effect never mistakes its own selection's URL write for a fresh inbound deep link and
   * double-fires `selectAsset`.
   */
  trackRequestedAsset(requestedAssetId: Signal<string | undefined>): void {
    effect(() => {
      if (this.appliedDeepLink) {
        return;
      }
      const requested = requestedAssetId();
      const assets = this.summary()?.assets;
      if (!requested || !assets) {
        return;
      }
      this.appliedDeepLink = true;
      if (assets.some((asset) => asset.assetId === requested)) {
        void this.selectAsset(requested);
      }
    });
  }

  /** Cursor into `LiveStore.detectionEvents()` — the same idiom `MarksStore` uses for `map`. */
  private processedDetectionEventCount = 0;
  /** Swallows the invalidation effect's own first run; the constructor already fetched. */
  private summaryBootstrapped = false;
  /** `true` while the summary is on live+invalidation rather than the fallback poll (D1's `liveGated`). */
  private summaryLiveGated = false;
  /** The fallback poll's unsubscribe, held only while it is actually running. */
  private stopSummaryPollFn: (() => void) | null = null;
  /** The floor timer's unsubscribe, held only while live is open. */
  private stopSummaryFloorFn: (() => void) | null = null;
  /** A queued debounced refetch, or `null` when none is pending. */
  private summaryInvalidationHandle: ReturnType<typeof setTimeout> | null = null;
  /** When the most recent `refreshSummary()` started — the debounce's own reference point. */
  private lastSummaryFetchAtMs = 0;

  /**
   * D1's frozen gate for the fleet-summary read (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3,
   * wave L8a), live axis only — this facade is page-provided, so its lifetime already is its demand
   * signal, exactly like `FleetMapStore`'s.
   *
   * | `liveAvailable` | previous | Action |
   * |---|---|---|
   * | `true` | poll | stop poll; refresh once; start the floor timer |
   * | `true` | live | nothing |
   * | `false` | live | stop floor + any pending invalidation; refresh once; start poll |
   * | `false` | poll | nothing |
   */
  private applySummaryTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      if (this.summaryLiveGated) {
        return;
      }
      this.stopSummaryPoll();
      void this.refreshSummary();
      this.stopSummaryFloorFn = this.scheduler.schedule(SUMMARY_FLOOR_INTERVAL_MS, () => this.refreshSummary());
      this.summaryLiveGated = true;
      return;
    }
    this.summaryLiveGated = false;
    this.stopSummaryFloor();
    this.cancelPendingInvalidation();
    if (this.stopSummaryPollFn !== null) {
      return; // already polling
    }
    void this.refreshSummary();
    this.stopSummaryPollFn = this.scheduler.schedule(SUMMARY_POLL_INTERVAL_MS, () => this.refreshSummary());
  }

  /**
   * Queues one debounced refetch. A second invalidation arriving while one is already queued is
   * folded into it rather than adding a request — the debounce bounds the *rate*, so a burst of
   * arrivals costs exactly one fetch. Raw `setTimeout` rather than `PollScheduler`, matching
   * `LayersStore#scheduleGrantsReconcile`'s own one-shot-debounce precedent (that scheduler is a
   * fixed-cadence heartbeat, not a one-shot timer).
   */
  private invalidateSummary(): void {
    if (this.summaryInvalidationHandle !== null) {
      return;
    }
    const delay = invalidationDelayMs(this.lastSummaryFetchAtMs, Date.now());
    this.summaryInvalidationHandle = setTimeout(() => {
      this.summaryInvalidationHandle = null;
      void this.refreshSummary();
    }, delay);
  }

  private cancelPendingInvalidation(): void {
    if (this.summaryInvalidationHandle !== null) {
      clearTimeout(this.summaryInvalidationHandle);
      this.summaryInvalidationHandle = null;
    }
  }

  private stopSummaryPoll(): void {
    this.stopSummaryPollFn?.();
    this.stopSummaryPollFn = null;
  }

  private stopSummaryFloor(): void {
    this.stopSummaryFloorFn?.();
    this.stopSummaryFloorFn = null;
  }

  private teardownSummaryTransport(): void {
    this.stopSummaryPoll();
    this.stopSummaryFloor();
    this.cancelPendingInvalidation();
  }

  private async refreshSummary(): Promise<void> {
    this.lastSummaryFetchAtMs = Date.now();
    // Also ticks the shared clock, on both success and failure paths. `nowSignal` has its own
    // CLOCK_TICK_MS timer since wave L8a and no longer depends on this, but a fetch is still a
    // genuine "time has passed" moment, and advancing it here keeps every derived age recomputing
    // against the same snapshot the summary itself just produced.
    this.nowSignal.set(Date.now());
    try {
      const data = await this.api.fleetSummary(this.includeArchivedSignal());
      this.summarySignal.set(data);
      this.summaryErrorSignal.set(false);
    } catch {
      if (this.summary() === undefined) {
        this.summaryErrorSignal.set(true);
      }
      // else: silent-degrade — a background poll failure keeps showing the last-known summary,
      // matching every other poller in this app.
    }
  }

  toggleIncludeArchived(checked: boolean): void {
    this.includeArchivedSignal.set(checked);
    void this.refreshSummary(); // don't make the toggle wait up to 5s for the next scheduled poll
  }

  toggleRail(): void {
    this.railOpenSignal.update((open) => !open);
  }

  togglePanelCollapse(): void {
    this.panelOpenPreferenceSignal.update((open) => !open);
  }

  // --- Selection (rail row click, or the map's own direct-marker-click `(preview)`) --------------

  /**
   * Selects `assetId` for the right-hand panel and resolves its video device (one-shot, mirrors
   * the pre-§U-c docked-preview's own `onPreview`). A fresh selection always shows the panel, even
   * if the operator had previously collapsed it — collapsing is "get this out of my way for now",
   * not "never show me a panel again".
   *
   * **BUG 4 fix**: also mirrors the choice into `?asset=` — every sibling page's facade round-trips
   * its selection through the URL (`assets-facade.ts#selectRow`, `devices-facade.ts`, `alerts-facade.ts`,
   * `roster-facade.ts`, `replay-library-facade.ts`) so it survives reload and Back; `CommandFacade` is
   * page-provided (not `providedIn: 'root'`), so without this a selection died the moment the tab
   * reloaded even though `trackRequestedAsset` reads `?asset=` right back in on the next boot.
   * `replaceUrl: true` — same reasoning as every sibling's identical choice: picking a rail row is
   * browsing, not a step Back should have to undo one click at a time.
   */
  async selectAsset(assetId: string): Promise<void> {
    // A manual selection supersedes whatever `?asset=` originally asked for — latching this here (not
    // just inside `trackRequestedAsset`'s own effect) stops that effect from re-firing a redundant
    // second `selectAsset` call the moment this method's own `router.navigate` below lands and the
    // routed `requestedAssetId` input updates to match. Without it, the very first selection in a
    // session double-fires (an extra focus-tick bump + a duplicate `resolveWatchDevice` call).
    this.appliedDeepLink = true;
    // Bumped on *every* selection, including re-selecting the asset already selected. The map
    // centres on `focusRequest` changing; keying that off the asset id alone meant clicking the
    // current asset did nothing, so an operator who had panned away could not click it to bring the
    // camera back — the one gesture they'd naturally reach for (docs/extracts/design/02-command.md).
    this.focusTickSignal.update((tick) => tick + 1);
    this.selectedAssetIdSignal.set(assetId);
    this.selectedVideoDeviceIdSignal.set(undefined);
    this.panelOpenPreferenceSignal.set(true);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { asset: assetId }, queryParamsHandling: 'merge', replaceUrl: true });
    const device = await this.mapStore.resolveWatchDevice(assetId);
    // Guard against a stale response landing after the operator already selected someone else.
    if (this.selectedAssetId() === assetId) {
      this.selectedVideoDeviceIdSignal.set(device?.id);
    }
  }

  /**
   * What `<vision-tactical-map>`'s `focusRequest` reads — the selected asset plus a tick that changes on
   * every `selectAsset` call, so re-selecting the same asset re-centres. `undefined` while nothing is
   * selected, which leaves the camera alone.
   */
  readonly focusRequest = computed(() => {
    const assetId = this.selectedAssetId();
    return assetId ? { assetId, tick: this.focusTickSignal() } : undefined;
  });

  /** Clears the selection and, per the same BUG 4 fix as `selectAsset`, `?asset=` — so a closed panel
   *  stays closed across reload rather than the deep link re-opening it. */
  closePanel(): void {
    this.selectedAssetIdSignal.set(null);
    this.selectedVideoDeviceIdSignal.set(undefined);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { asset: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  // --- Navigation (the verb dictionary's two terms — docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 1) --------

  /** `router.navigate(['/fly'], {queryParams: {asset, watch: 1}})` — the pinned Watch-live contract. */
  watchAsset(assetId: string): void {
    void this.router.navigate(['/fly'], { queryParams: { asset: assetId, watch: 1 } });
  }

  watchSelected(): void {
    const assetId = this.selectedAssetId();
    if (assetId) {
      this.watchAsset(assetId);
    }
  }

  openAsset(assetId: string): void {
    void this.router.navigate(['/assets', assetId]);
  }

  openSelectedDetails(): void {
    const assetId = this.selectedAssetId();
    if (assetId) {
      this.openAsset(assetId);
    }
  }

  /** `<vision-tactical-map>`'s event-popup "Details" button — unchanged target, just this facade's own wiring. */
  openEventAsset(assetId: string): void {
    this.openAsset(assetId);
  }

  // --- Drawings (docs/plans/done/MAP-REWORK-PLAN.md §5.2) ---------------------------------------------------

  /**
   * `<vision-tactical-map>`'s `(drawingCompleted)` — the map owns the in-progress vertex list and
   * only ever emits a shape that already passes `Drawing`'s own minimum-point rule
   * (`tactical-map-logic.ts#completedDraft`), so this is a straight `POST`. The colour comes from
   * `DrawingsStore`'s own picker state, which is why the toolbar doesn't need to be reachable from
   * here.
   */
  async completeDrawing(draft: DrawingDraft): Promise<void> {
    await this.drawings.completeDraft(draft);
  }

  /** `(drawingSelected)` — selecting on the map is the same selection the toolbar's editor reads. */
  selectDrawing(drawingId: string): void {
    this.drawings.select(drawingId);
  }

  /**
   * The map's own empty-state action, ported from the now-deleted `MapPage` — one click places a
   * moving synthetic drone with no video file, the fastest way to see the map plot something with no
   * hardware/file path needed.
   */
  async addTestDrone(): Promise<void> {
    this.addingTestDroneSignal.set(true);
    try {
      await this.fleet.simulate(buildTestDroneRequest({ name: '', latitude: null, longitude: null, autoStart: true }));
      await this.mapStore.refresh();
    } finally {
      this.addingTestDroneSignal.set(false);
    }
  }
}
