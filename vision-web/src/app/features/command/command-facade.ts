import { DestroyRef, Injectable, type Signal, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { buildTestDroneRequest } from '../../core/fleet/simulation-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import { FleetMapStore } from '../../core/map/map-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { activeGeofenceBreaches, groupBreachesByAsset } from '../../core/geofence/geofence-logic';
import { LiveStore } from '../../core/live/live-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { fleetCentroid } from '../../core/weather/weather-logic';
import { buildEntityRows, commandGridColumns, type DetailPanelState } from './command-logic';
import type { AssetAttention, FleetSummary } from '../../core/api/models';

/** The one poll driving the entity rail and the selected asset's Status/Telemetry facts alike. */
const SUMMARY_POLL_INTERVAL_MS = 5_000;

const RAIL_OPEN_KEY = 'vision.command.railOpen';
const PANEL_OPEN_KEY = 'vision.command.panelOpen';

/**
 * `CommandPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md wave W2) — owns every store/service the page
 * needs (`FleetStore`, `FleetMapStore`, `GeofenceStore`, `LiveStore`, `WeatherStore`, `VisionApi`,
 * `Router`, `PollScheduler`), the fleet-summary poll, and every read-model/command the template binds
 * to. `CommandPage` itself injects only this facade (plus its own `UiStore` for the Zones overlay —
 * see that class's own doc comment for why the overlay stays component-local rather than moving here).
 *
 * **Provided per route activation**, listed alongside `FleetMapStore`/`WeatherStore` in
 * `CommandPage`'s own `providers` array (both page-scoped, not `providedIn: 'root'` — see their own
 * class doc comments) — all three share one injector, so this facade's own `inject(FleetMapStore)`/
 * `inject(WeatherStore)` resolve to the exact same instances `<vision-fleet-map>`/`<vision-weather-chip>`
 * (children of `CommandPage`, injecting those stores directly themselves) already get. Moving the
 * *injection* here changes nothing about *which* instance anything sees — same DI subtree as before,
 * just orchestrated from one class instead of the component.
 *
 * **What moved here from `CommandPage` unchanged**: every computed read-model (`entityRows`,
 * `assetPositions`, `weatherPosition`, `selectedAsset`/`selectedMarker`/`selectedStream`,
 * `panelState`/`gridColumns`, `mapIsEmpty`), every command method (`selectAsset`, `watchAsset`,
 * `openAsset`, `addTestDrone`, …), and the two persisted, non-mutually-exclusive toggles
 * (`railOpen`/the panel's own open/collapsed preference — docs/UX-REWORK-PLAN.md §U-b item 7) under
 * their original `localStorage` keys. None of this is exclusive-overlay state, so none of it belongs
 * on a `UiStore` — see `docs/UI-ARCHITECTURE-PLAN.md`'s own "toggle-style state that is NOT mutually
 * exclusive … stays a plain boolean … but moves into its feature store/facade" rule.
 */
@Injectable()
export class CommandFacade {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly mapStore = inject(FleetMapStore);
  private readonly geofence = inject(GeofenceStore);
  private readonly liveStore = inject(LiveStore);
  private readonly weather = inject(WeatherStore);

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
   * `assetId → gpsFixType`, built from `FleetMapStore` (docs/FC-INTEGRATIONS-PLAN.md F-d) — feeds
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
   * `assetId → active breaches` (docs/OPS-CORE-PLAN.md §G-c), derived from `LiveStore.liveEvents()`
   * — the generic `event` SSE topic GEOFENCE_BREACH rides. Feeds `buildEntityRows`' top-rank
   * `geofence-breach` reason, same "optional map, by assetId" shape as `gpsFixTypeByAssetId` above.
   */
  private readonly geofenceBreachesByAssetId = computed(() =>
    groupBreachesByAsset(activeGeofenceBreaches(this.liveStore.liveEvents())),
  );

  readonly entityRows = computed(() =>
    buildEntityRows(this.summary()?.assets ?? [], this.gpsFixTypeByAssetId(), this.geofenceBreachesByAssetId()),
  );

  /** Every asset's currently-known position — threaded to the Zones panel's own draw-dialog advisory. */
  readonly assetPositions = computed(() => this.mapStore.markers().map((marker) => marker.position));

  // --- Weather go/no-go chip (docs/OPS-CORE-PLAN.md §W) ------------------------------------------
  // Command's chip centers on the fleet centroid, not any one asset — this page's own fleet-summary
  // poll carries no per-asset `attributes`, so the wind limit here is always the plan's own default
  // rather than a specific asset's `windLimitMps` override (see `WeatherChip`'s own doc comment;
  // Fly's chip, scoped to one selected asset, is the one that reads that attribute).
  readonly weatherPosition = computed(() => fleetCentroid(this.assetPositions()));

  // --- Panel state memory (docs/UX-REWORK-PLAN.md §U-b item 7 / §U-c bullet 5) -------------------
  // Persisted, but NOT mutually exclusive with anything else (docs/UI-ARCHITECTURE-PLAN.md), so both
  // stay plain signals here rather than joining the Zones overlay's `UiStore` group.
  private readonly railOpenSignal = signal(readPersistedFlag(RAIL_OPEN_KEY, true));
  readonly railOpen = this.railOpenSignal.asReadonly();
  private readonly panelOpenPreferenceSignal = signal(readPersistedFlag(PANEL_OPEN_KEY, true));

  // --- Selection (docs/UX-REWORK-PLAN.md §U-c bullet 1) -------------------------------------------
  private readonly selectedAssetIdSignal = signal<string | null>(null);
  readonly selectedAssetId = this.selectedAssetIdSignal.asReadonly();
  private readonly selectedVideoDeviceIdSignal = signal<string | undefined>(undefined);
  readonly selectedVideoDeviceId = this.selectedVideoDeviceIdSignal.asReadonly();

  readonly selectedAsset = computed<AssetAttention | undefined>(() =>
    this.summary()?.assets.find((asset) => asset.assetId === this.selectedAssetId()),
  );

  readonly selectedMarker = computed(() => {
    const assetId = this.selectedAssetId();
    return assetId ? this.mapStore.markers().find((marker) => marker.assetId === assetId) : undefined;
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

  private appliedDeepLink = false;

  constructor() {
    void this.refreshSummary();
    const stopPoll = inject(PollScheduler).schedule(SUMMARY_POLL_INTERVAL_MS, () => this.refreshSummary());
    inject(DestroyRef).onDestroy(stopPoll);

    effect(() => writePersistedFlag(RAIL_OPEN_KEY, this.railOpenSignal()));
    effect(() => writePersistedFlag(PANEL_OPEN_KEY, this.panelOpenPreferenceSignal()));

    // Keeps the weather chip fresh as the fleet centroid moves — `WeatherStore.track` itself
    // no-ops instantly unless the 10-minute cache is actually stale (docs/OPS-CORE-PLAN.md §W).
    effect(() => this.weather.track(this.weatherPosition()));
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

  private async refreshSummary(): Promise<void> {
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
   */
  async selectAsset(assetId: string): Promise<void> {
    this.selectedAssetIdSignal.set(assetId);
    this.selectedVideoDeviceIdSignal.set(undefined);
    this.panelOpenPreferenceSignal.set(true);
    const device = await this.mapStore.resolveWatchDevice(assetId);
    // Guard against a stale response landing after the operator already selected someone else.
    if (this.selectedAssetId() === assetId) {
      this.selectedVideoDeviceIdSignal.set(device?.id);
    }
  }

  closePanel(): void {
    this.selectedAssetIdSignal.set(null);
    this.selectedVideoDeviceIdSignal.set(undefined);
  }

  // --- Navigation (the verb dictionary's two terms — docs/UX-REWORK-PLAN.md §U-a2 item 1) --------

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

  /** `<vision-fleet-map>`'s event-popup "Details" button — unchanged target, just this facade's own wiring. */
  openEventAsset(assetId: string): void {
    this.openAsset(assetId);
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
