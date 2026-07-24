import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { buildTestDroneRequest } from '../../core/fleet/simulation-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import { FleetMapStore } from '../../core/map/map-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { FleetMap } from '../../shared/map/fleet-map';
import { AssetPanel } from './asset-panel';
import { buildEntityRows, commandGridColumns, type DetailPanelState } from './command-logic';
import type { AssetAttention, FleetSummary } from '../../core/api/models';

/** The one poll driving the entity rail and the selected asset's Status/Telemetry facts alike. */
const SUMMARY_POLL_INTERVAL_MS = 5_000;

const RAIL_OPEN_KEY = 'vision.command.railOpen';
const PANEL_OPEN_KEY = 'vision.command.panelOpen';

/**
 * `/command` — the manager dashboard (docs/UX-REWORK-PLAN.md §U-c, superseding docs/MVP3-PLAN.md
 * §C-c's stacked-cards layout with the plan's own map-first, three-panel model: FlytBase Fleet View
 * 2.0's "full-bleed map as canvas, slim entity rail docked left, detail panel docked right").
 *
 * **Layout**: `<vision-fleet-map>` fills the entire stage between two independently collapsible
 * docked panels (`command-logic.ts#commandGridColumns` computes the grid — see its own doc comment
 * for why these are real grid-track siblings, not `position: absolute` overlays, and how that
 * avoids the map's own zoom/layer controls entirely by construction rather than z-index
 * coordination). Both panels persist their collapsed state per user (`core/panel-state.ts`,
 * docs/UX-REWORK-PLAN.md §U-b item 7's "explicit collapse, reopen via toggle chip" — mirrors
 * `features/live/live.ts`'s `railOpen`/`features/fly/fly.ts`'s `mapVisible` exactly).
 *
 * **Selecting an asset** (a rail row, or `<vision-fleet-map>`'s own `(preview)` output — a direct
 * marker click, unchanged component behavior per the plan's own "keep the existing marker/popup
 * behavior" instruction) opens the right `<vision-asset-panel>`. Everything that panel shows is
 * data this page already has from its own existing pollers — **zero new recurring requests**:
 * - Status/the "why" reasons: the one `GET /api/fleet/summary` poll (`summary`, unchanged from the
 *   pre-§U-c page — still the single aggregated poll behind the whole page).
 * - Telemetry facts (position/altitude/heading/battery/age): the embedded `<vision-fleet-map>`'s own
 *   `FleetMapStore` (`mapStore.markers()`) — the exact reuse `shared/map/live-dock.ts` already
 *   established ("passing marker in avoids a second, redundant telemetry poller").
 * - Video: one **one-shot** `FleetMapStore.resolveWatchDevice(assetId)` call per selection change
 *   (unchanged from the old docked-preview's own `onPreview`, just retargeted — see below) plus the
 *   always-on root `FleetStore.streamFor(deviceId)`.
 *
 * **Removed this cycle** (docs/UX-REWORK-PLAN.md §U-c's own user-amendments blockquote):
 * the Warehouse-readiness tiles (drill-down lives in Warehouse itself now) and the live strip
 * (`LiveStripTile`, deleted — its one consumer was this page). The events rail is gone from this
 * page too — events are notifications now (the app shell's header bell, `shared/ui/notification-bell.ts`),
 * not a docked module; `EventsStore` stays the data source, just activated by the shell instead of
 * by this page (see that component's own doc comment for the resulting always-on cost, superseding
 * this store's old "O(visible) discipline" note).
 *
 * **`<vision-live-dock>` is no longer used here** — the old "docked preview beside the map" role is
 * now the asset panel's own Video tab (a superset: facts + actions, not just a bare player). Its
 * `(preview)` handler is retargeted from "resolve a device and dock `LiveDock`" to "select this
 * asset", which is the *only* change to how `<vision-fleet-map>` is composed here — the component
 * itself, its inputs, and its `(watch)`/`(preview)`/`(openEventAsset)` outputs are all unchanged.
 */
@Component({
  selector: 'vision-command',
  imports: [FleetMap, AssetPanel, RouterLink],
  templateUrl: './command.html',
  styleUrl: './command.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to the pre-§U-c page's own `FleetMapStore`.
  providers: [FleetMapStore],
})
export class CommandPage {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  protected readonly mapStore = inject(FleetMapStore);

  /**
   * `?asset=<id>` deep link (docs/UX-REWORK-PLAN.md §U-c's "preserve ?asset deep links if map
   * supported any" — `/map` never actually had one, grep-verified before writing this; added here
   * anyway for consistency with `features/fly/fly.ts`'s identical `requestedAssetId` precedent, and
   * because `/map` now redirects here, this *is* where such a link would need to land). Query
   * params bind to inputs by name/alias automatically (`withComponentInputBinding()`,
   * `app.config.ts`) — no route-table change needed.
   */
  readonly requestedAssetId = input<string | undefined>(undefined, { alias: 'asset' });

  protected readonly summary = signal<FleetSummary | undefined>(undefined);
  /** Only ever set when the *very first* load fails — a background poll failure silently degrades. */
  protected readonly summaryError = signal(false);
  protected readonly includeArchived = signal(false);

  protected readonly entityRows = computed(() => buildEntityRows(this.summary()?.assets ?? []));

  // --- Panel state memory (docs/UX-REWORK-PLAN.md §U-b item 7 / §U-c bullet 5) -------------------
  protected readonly railOpen = signal(readPersistedFlag(RAIL_OPEN_KEY, true));
  private readonly panelOpenPreference = signal(readPersistedFlag(PANEL_OPEN_KEY, true));

  // --- Selection (docs/UX-REWORK-PLAN.md §U-c bullet 1) -------------------------------------------
  protected readonly selectedAssetId = signal<string | null>(null);
  protected readonly selectedVideoDeviceId = signal<string | undefined>(undefined);

  protected readonly selectedAsset = computed<AssetAttention | undefined>(() =>
    this.summary()?.assets.find((asset) => asset.assetId === this.selectedAssetId()),
  );

  protected readonly selectedMarker = computed(() => {
    const assetId = this.selectedAssetId();
    return assetId ? this.mapStore.markers().find((marker) => marker.assetId === assetId) : undefined;
  });

  protected readonly selectedStream = computed(() => {
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
  protected readonly panelState = computed<DetailPanelState>(() =>
    this.selectedAsset() === undefined ? 'hidden' : this.panelOpenPreference() ? 'open' : 'collapsed',
  );

  protected readonly gridColumns = computed(() => commandGridColumns(this.railOpen(), this.panelState()));

  protected readonly mapIsEmpty = computed(() => this.mapStore.assets().length === 0);
  protected readonly addingTestDrone = signal(false);

  private appliedDeepLink = false;

  constructor() {
    void this.refreshSummary();
    const stopPoll = inject(PollScheduler).schedule(SUMMARY_POLL_INTERVAL_MS, () => this.refreshSummary());
    inject(DestroyRef).onDestroy(stopPoll);

    effect(() => writePersistedFlag(RAIL_OPEN_KEY, this.railOpen()));
    effect(() => writePersistedFlag(PANEL_OPEN_KEY, this.panelOpenPreference()));

    // One-shot `?asset=` resolution, mirroring `fly.ts#initPicker`'s own "runs once, never inside
    // the periodic poll" rule — a later query-param change while already on `/command` should not
    // silently override a manager's own subsequent click elsewhere in the rail/map.
    effect(() => {
      if (this.appliedDeepLink) {
        return;
      }
      const requested = this.requestedAssetId();
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
      const data = await this.api.fleetSummary(this.includeArchived());
      this.summary.set(data);
      this.summaryError.set(false);
    } catch {
      if (this.summary() === undefined) {
        this.summaryError.set(true);
      }
      // else: silent-degrade — a background poll failure keeps showing the last-known summary,
      // matching every other poller in this app.
    }
  }

  protected toggleIncludeArchived(checked: boolean): void {
    this.includeArchived.set(checked);
    void this.refreshSummary(); // don't make the toggle wait up to 5s for the next scheduled poll
  }

  protected toggleRail(): void {
    this.railOpen.update((open) => !open);
  }

  protected togglePanelCollapse(): void {
    this.panelOpenPreference.update((open) => !open);
  }

  // --- Selection (rail row click, or the map's own direct-marker-click `(preview)`) --------------

  /**
   * Selects `assetId` for the right-hand panel and resolves its video device (one-shot, mirrors
   * the pre-§U-c docked-preview's own `onPreview` — see class doc). A fresh selection always shows
   * the panel, even if the operator had previously collapsed it — collapsing is "get this out of my
   * way for now", not "never show me a panel again".
   */
  protected async selectAsset(assetId: string): Promise<void> {
    this.selectedAssetId.set(assetId);
    this.selectedVideoDeviceId.set(undefined);
    this.panelOpenPreference.set(true);
    const device = await this.mapStore.resolveWatchDevice(assetId);
    // Guard against a stale response landing after the operator already selected someone else.
    if (this.selectedAssetId() === assetId) {
      this.selectedVideoDeviceId.set(device?.id);
    }
  }

  protected closePanel(): void {
    this.selectedAssetId.set(null);
    this.selectedVideoDeviceId.set(undefined);
  }

  // --- Navigation (the verb dictionary's two terms — docs/UX-REWORK-PLAN.md §U-a2 item 1) --------

  /** `router.navigate(['/fly'], {queryParams: {asset, watch: 1}})` — the pinned Watch-live contract. */
  protected watchAsset(assetId: string): void {
    void this.router.navigate(['/fly'], { queryParams: { asset: assetId, watch: 1 } });
  }

  protected watchSelected(): void {
    const assetId = this.selectedAssetId();
    if (assetId) {
      this.watchAsset(assetId);
    }
  }

  protected openAsset(assetId: string): void {
    void this.router.navigate(['/assets', assetId]);
  }

  protected openSelectedDetails(): void {
    const assetId = this.selectedAssetId();
    if (assetId) {
      this.openAsset(assetId);
    }
  }

  /** `<vision-fleet-map>`'s event-popup "Details" button — unchanged target, just this page's own wiring. */
  protected openEventAsset(assetId: string): void {
    this.openAsset(assetId);
  }

  /**
   * The map's own empty-state action, ported from the now-deleted `MapPage` (docs/UX-REWORK-PLAN.md
   * §U-c: "their components fold in") — one click places a moving synthetic drone with no video
   * file, the fastest way to see the map plot something with no hardware/file path needed.
   */
  protected async addTestDrone(): Promise<void> {
    this.addingTestDrone.set(true);
    try {
      await this.fleet.simulate(buildTestDroneRequest({ name: '', latitude: null, longitude: null, autoStart: true }));
      await this.mapStore.refresh();
    } finally {
      this.addingTestDrone.set(false);
    }
  }
}
