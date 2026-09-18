import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsFacade } from '../../core/settings/settings-facade';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { pluralize } from '../../shared/ui/text-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { EventsStore } from '../../core/events/events-store';
import { LiveFacade } from '../../core/live/live-facade';
import { LinksStore } from '../../core/pairing/links-store';
import {
  failoverRowsForAsset,
  hasActiveBenchWarning,
  linkHealthLabel,
  linkKindLabel,
  linkQualitySummary,
  sortedLinks,
  type LinkFailoverRow,
} from '../../core/pairing/pairing-logic';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { MarksStore } from '../../core/map-data/marks-store';
import { LayersStore } from '../../core/map-data/layers-store';
import { DrawingsStore } from '../../core/map-data/drawings-store';
import { TracksStore } from '../../core/map-data/tracks-store';
import { followMarkers } from '../../shared/map/tactical-map/tactical-map-logic';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canManageOrg } from '../../core/org/org-logic';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { ageSeconds, isStale, trackingIdChanged } from '../../core/telemetry/telemetry-logic';
import { hasFix } from '../../core/geo/geo-logic';
import { filterEvents } from '../../core/events/events-logic';
import {
  RESTORE_TARGET_STATE,
  buildAssetEdit,
  buildDeviceRenameEdit,
  operatorAssetActions,
} from '../../core/fleet/warehouse-logic';
import {
  flightBars as buildFlightBars,
  kpiTiles as buildKpiTiles,
  type FlightBar,
  type KpiTile,
} from '../../core/fleet/asset-stats-logic';
import {
  buildIdentityEdit,
  effectiveRegistration as computeEffectiveRegistration,
  freshestSample,
  groupTelemetryByDevice,
  sinceServiceTile,
  telemetryDevices,
  telemetryFactRows,
  withFixOnlyPosition,
  type TelemetryFactRow,
} from './asset-detail-logic';
import { roleStatus } from '../../core/onboarding/fit-out-logic';
import type {
  AssetDetails,
  AssetIdentity,
  AssetStats,
  Device,
  DetectionEvent,
  FleetSummary,
  LinkView,
  MaintenanceRecord,
  SettableLifecycleState,
  TelemetrySample,
} from '../../core/api/models';

/** Asset characteristics/usages are re-read at this cadence — matches `FleetStore`'s own poll. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** How often per-device sample-age readouts tick, independent of the telemetry poll cadence. */
const CLOCK_TICK_MS = 1_000;

/** How often the per-stream events feed is re-read while this asset is actively streaming. */
const STREAM_EVENTS_POLL_INTERVAL_MS = 5_000;

/** Matches `EventController.DEFAULT_LIMIT`. */
const STREAM_EVENTS_LIMIT = 50;

/**
 * `AssetDetailPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — owns every store/service injection,
 * derived read-model, and command for the asset manager page, so the page component itself only
 * injects this class (+ its own local `editors`/`panels`/`subView` overlay/view state — see
 * `asset-detail.ts`'s own doc comment for why those stay on the component rather than here).
 * `@Injectable()`, **provided in `AssetDetailPage`'s own `providers` array alongside `TelemetryStore`**
 * (unchanged — still one poller-set per route activation, same idiom as `LivePage`) so this facade's
 * lifetime — and every `effect()`/`PollScheduler` registration below — exactly matches the page's own.
 *
 * **Activation**: the page calls {@link load} from its own single constructor `effect()` (the only
 * one left in the component — reading the route-bound `assetId` input, which only a component can
 * receive, then forwarding it here plus resetting its own local overlay state). Every mutation
 * command below re-reads {@link refresh} against whichever `assetId` `load` was last called with
 * (`currentAssetId`) — mirrors the original page's own `refresh()` exactly.
 *
 * No behavior change from the pre-facade page: every HTTP call, toast, silent-degrade path, and poll
 * cadence below is carried over verbatim, just relocated. See the original class's own (more
 * extensive) doc comments in git history for the full narrative on the KPI/chart Wave B design,
 * per-device telemetry grouping, and the R-a/R-c telemetry re-entry-guard incident — the guard itself
 * is preserved unchanged just below.
 */
@Injectable()
export class AssetDetailFacade {
  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly auth = inject(AuthFacade);
  private readonly telemetry = inject(TelemetryFacade);
  private readonly events = inject(EventsStore);
  /** Named `liveStore`, not `live` — this class already has a `live` computed (whether *this asset*
   *  is currently streaming, see below); this is the generic SSE connection singleton. */
  private readonly liveStore = inject(LiveFacade);

  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsFacade);

  /** Gates the Pilots drill-in trigger itself — a non-manager should never see the affordance, not
   *  just find an empty drawer behind it (`PilotsCard`'s own internal gate stays as a second layer). */
  readonly canManagePilots = computed(() => canManageOrg(this.auth.capabilities()));

  readonly asset = signal<AssetDetails | undefined>(undefined);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly busy = signal(false);

  private readonly nowSignal = signal(Date.now());
  readonly now = this.nowSignal.asReadonly();

  readonly videoDevice = computed(() => findVideoDevice(this.asset()?.devices ?? []));
  readonly stream = computed(() => {
    const device = this.videoDevice();
    return device ? this.fleet.streamFor(device.id) : undefined;
  });
  /** Read-only: whether *someone* is currently streaming this asset. Piloting is the cockpit's job. */
  readonly live = computed(() => this.stream() !== undefined);

  // --- Sense/Sight role status (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md P3) -----------------
  //
  // `GET /api/fleet/summary` is the only source of a per-asset `telemetryAgeMs` (`FleetSummary`/
  // `AssetAttention`, `core/api/models.ts`) — no shared store fetches it (`InventoryFacade`/
  // `CommandFacade` each fetch it independently too; see those facades' own `fleetSummaryData`).
  // Fetched with `includeArchived: true` unconditionally: this page has no "show archived" toggle of
  // its own but can still be reached for an archived asset (its own `assetLifecycleAction`/`archived`
  // above already handle that case) — a `false` fetch would silently starve `telemetryAgeMs` there.

  private readonly fleetSummaryData = signal<FleetSummary | undefined>(undefined);

  /** `undefined` until the fleet summary loads, or for an asset the summary doesn't (yet) know about
   *  — {@link roleStatus} already treats an unknown age as honestly `never-seen`/`stale` rather than
   *  fabricating a reading, so this never needs its own separate fallback. */
  private readonly telemetryAgeMs = computed(
    () => this.fleetSummaryData()?.assets.find((a) => a.assetId === this.currentAssetIdSignal())?.telemetryAgeMs,
  );

  readonly senseStatus = computed(() =>
    roleStatus('sense', this.asset()?.devices ?? [], this.fleet.streams(), this.telemetryAgeMs()),
  );
  readonly sightStatus = computed(() =>
    roleStatus('sight', this.asset()?.devices ?? [], this.fleet.streams(), this.telemetryAgeMs()),
  );

  // --- Events (docs/plans/done/MVP2-PLAN.md §E, E-b bullet 2) ---------------------------------------------

  private readonly streamEventsSignal = signal<readonly DetectionEvent[]>([]);

  readonly displayedEvents = computed(() =>
    this.live() ? this.streamEventsSignal() : filterEvents(this.events.events(), { assetId: this.currentAssetIdSignal() ?? '' }),
  );

  readonly assetTelemetryDevices = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  readonly telemetryByDevice = computed(() => groupTelemetryByDevice(this.telemetry.samples()));

  /** Which device the map marker's current position most likely came from — item 3's transparency ask. */
  readonly freshestSourceName = computed(() => {
    const freshest = freshestSample(this.telemetryByDevice());
    if (!freshest) {
      return undefined;
    }
    const device = this.assetTelemetryDevices().find((d) => d.id === freshest.deviceId);
    return device?.name ?? freshest.deviceId;
  });

  readonly freshestOverall = computed(() => freshestSample(this.telemetryByDevice()));
  readonly freshestFacts = computed<readonly TelemetryFactRow[]>(() => telemetryFactRows(this.freshestOverall()));
  readonly freshestAgeSeconds = computed(() => ageSeconds(this.freshestOverall()?.at, this.nowSignal()));
  readonly freshestStale = computed(() => isStale(this.freshestAgeSeconds()));

  // --- Position card map (docs/plans/done/MAP-REWORK-PLAN.md §5.1 Wave D) -----------------------------------
  // `<vision-tactical-map>` replaced the deleted `<vision-live-map>`, which read this facade's own
  // `TelemetryStore` through DI; the new component is dumb, so the followed marker is built here from
  // the same telemetry. This page also finally passes zones + marks (the plan's own bug fix — the old
  // inset dropped both silently), through the two root stores below.

  readonly geofence = inject(GeofenceStore);
  readonly marks = inject(MarksStore);
  /** Layers name the map's data-layer rows and colour COP marks; drawings are the same shared picture every other host shows (docs/plans/done/MAP-REWORK-PLAN.md §5.2). */
  readonly layers = inject(LayersStore);
  readonly drawings = inject(DrawingsStore);
  /** Projected fixed-camera tracks (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md wave G5) — the same
   *  org-wide, already-scoped picture `marks`/`drawings`/`geofence` show on this card's map, not
   *  filtered to this one asset (this card's map has never been per-asset-scoped for its overlays). */
  readonly tracks = inject(TracksStore);

  /** Switches the map into follow mode; `null` until the asset has loaded. */
  readonly mapFollowAssetId = computed(() => this.asset()?.assetId ?? null);

  /**
   * `<vision-tactical-map>`'s `[assets]` — 0 or 1 markers, built from the freshest sample + trail.
   *
   * `trail`/`latest` are both filtered through {@link withFixOnlyPosition}/`hasFix`
   * (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1) before reaching `followMarkers` — a `(0, 0)`
   * point is a no-fix report, not a place, and `shared/map/tactical-map/tactical-map-logic.ts#followMarker`'s
   * own fix check (unrelated to this wave, not repatched here) would otherwise treat it as real and
   * recentre the map on Null Island. Stripping it here makes that helper's existing "no fix → last
   * real trail point, or nothing plotted" fallback fire instead — the position card's map keeps its
   * previous/default view rather than jumping to open ocean.
   */
  readonly mapAssets = computed(() => {
    const asset = this.asset();
    if (!asset) {
      return [];
    }
    return followMarkers({
      assetId: asset.assetId,
      displayName: asset.displayName,
      categoryName: asset.categoryName,
      trail: this.telemetry.trail().filter((point) => hasFix(point)),
      latest: withFixOnlyPosition(this.freshestOverall()),
    });
  });

  readonly lifecycle = computed(() => this.asset()?.lifecycle ?? 'ACTIVE');
  readonly archived = computed(() => this.lifecycle() === 'DELETED');

  /**
   * The header's one lifecycle button (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 2). Exactly one of the pair
   * `operatorAssetActions` returns is ever `available` — see that function's own doc comment.
   */
  readonly assetLifecycleAction = computed(() => operatorAssetActions(this.lifecycle()).find((entry) => entry.available));

  // --- KPI tile row + "Recent flights" chart (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave B items 3–4) -

  readonly stats = signal<AssetStats | undefined>(undefined);
  /** The utilization row's flight-stat tiles, plus one more (docs/plans/active/WAREHOUSE-UX-PLAN.md
   *  §3.4, wave W4) — "Since service", read from this asset's own maintenance history, not `AssetStats`. */
  readonly kpiTiles = computed<readonly KpiTile[]>(() => [
    ...buildKpiTiles(this.stats(), this.nowSignal()),
    sinceServiceTile(this.maintenanceRecords(), this.nowSignal()),
  ]);
  readonly flightBars = computed<readonly FlightBar[]>(() => buildFlightBars(this.asset()?.recentUsages ?? [], this.nowSignal()));

  // --- Identity + maintenance (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave W4 — replaces the
  // old single-field `attributes.registrationNumber` editor with the full `AssetIdentity` group) ---

  readonly identity = computed(() => this.asset()?.identity);
  /** `identity.registration`, falling back to the legacy attribute key — see `asset-detail-logic.ts
   *  #effectiveRegistration`'s own doc comment for the migration gap this covers. */
  readonly effectiveRegistration = computed(() => {
    const asset = this.asset();
    return asset ? computeEffectiveRegistration(asset) : undefined;
  });

  /** This asset's own maintenance history — fetched alongside `fetchAsset`/`refresh`
   *  (`GET /api/assets/{id}/maintenance`, the same per-asset endpoint `InventoryFacade`'s drawer
   *  uses — deliberately not the fleet-wide `GET /api/maintenance` `/fleet/maintenance` itself uses
   *  since wave W9, which would fetch every other asset's records just to discard them here). Feeds
   *  both {@link kpiTiles}' "Since service" tile and (later waves) a maintenance drawer on this page. */
  readonly maintenanceRecords = signal<readonly MaintenanceRecord[]>([]);

  // --- Hardware section: attach-a-device picker ---------------------------------------------------

  readonly busyDeviceId = signal<string | null>(null);
  readonly assignableDevices = signal<readonly Device[]>([]);
  readonly loadingAssignable = signal(false);

  /** The `assetId` {@link load} was last called with — every mutation command below refreshes
   *  against this, mirroring the original page's own `refresh()` exactly. A signal (not a plain
   *  field) because `displayedEvents` above reads it inside a `computed()` — a plain field read
   *  there would silently never register as a dependency, so a same-instance navigation to a
   *  *different* asset (Angular reuses this page's component/facade across same-route navigations)
   *  would keep filtering the global events feed by the previous asset's id until some unrelated
   *  dependency happened to change too. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  private get currentAssetId(): string | undefined {
    return this.currentAssetIdSignal();
  }

  /** The last deviceId the telemetry-tracking effect below actually acted on — see that effect's own
   *  doc comment (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2, R-c follow-up incident). */
  private lastTelemetryDeviceId: string | undefined = undefined;

  constructor() {
    // Any device on the asset resolves the same owning-asset/open-usage pair (see `TelemetryStore`'s
    // own doc comment) — passing `assetId` lets it resolve the open usage with one `getAsset()`
    // instead of listing the whole fleet.
    //
    // **Guarded on the derived deviceId primitive** (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2, R-c
    // follow-up): re-entering `track()` with an unchanged id every ~5s poll tick used to be a
    // self-sustaining 50-90/sec loop (`TelemetryStore.track()`'s own teardown wrote back into a
    // signal this effect was still the active reactive consumer of) — `trackingIdChanged` is what
    // stops that from ever happening again.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      const deviceId = this.assetTelemetryDevices().length > 0 ? devices[0].id : undefined;
      if (!trackingIdChanged(deviceId, this.lastTelemetryDeviceId)) {
        return;
      }
      this.lastTelemetryDeviceId = deviceId;
      if (deviceId) {
        this.telemetry.track(deviceId, this.currentAssetId);
      } else {
        this.telemetry.reset();
      }
    });

    // The per-stream events feed only makes sense while there is a streamId to ask about — an
    // immediate fetch on transition, then the scheduled poll below keeps it fresh.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        void this.pollStreamEvents(streamId);
      } else {
        this.streamEventsSignal.set([]);
      }
    });

    // "O(visible) discipline" (docs/plans/done/MVP2-PLAN.md §E, E-b bullet 5) — one of exactly three pages that
    // keeps the shared global events poll alive.
    this.events.activate();

    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: this is the one facade that injects all five map-data
    // stores (it is also the only routed page that renders the projected-track layer), so it holds
    // demand for every one of them for its own lifetime.
    this.geofence.activate();
    this.marks.activate();
    this.layers.activate();
    this.drawings.activate();
    this.tracks.activate();

    // Every poll registration below returns its own promise so `PollScheduler`'s in-flight guard can
    // skip a tick while the previous one is still pending (docs/plans/done/MVP2-PLAN.md §S, S-b).
    const scheduler = inject(PollScheduler);
    const stopAssetPoll = scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    const stopStreamEventsPoll = scheduler.schedule(STREAM_EVENTS_POLL_INTERVAL_MS, () => {
      const streamId = this.stream()?.streamId;
      return streamId ? this.pollStreamEvents(streamId) : undefined;
    });
    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      this.geofence.release();
      this.marks.release();
      this.layers.release();
      this.drawings.release();
      this.tracks.release();
      stopAssetPoll();
      stopClock();
      stopStreamEventsPoll();
    });
  }

  /** Called once by the page's own constructor `effect()` on every `assetId` route-input change. */
  load(assetId: string): void {
    this.currentAssetIdSignal.set(assetId);
    this.links.track(assetId); // no-op for an unchanged id — see `LinksStore#track`'s own doc comment
    void this.fetchAsset(assetId);
    void this.loadStats(assetId);
    void this.loadMaintenanceRecords(assetId);
    void this.loadFleetSummary();
  }

  /** Feeds {@link senseStatus}/{@link sightStatus} — same "enrichment, not a user-initiated action,
   *  silent-degrade" convention as {@link loadStats}: a failed read leaves the previous summary in
   *  place (or `undefined`, before the first success) rather than blocking or fabricating a reading. */
  private async loadFleetSummary(): Promise<void> {
    try {
      this.fleetSummaryData.set(await this.api.fleetSummary(true));
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  /** Silent-degrade to an empty list on failure — same "enrichment, not a user-initiated action"
   *  convention as {@link loadStats}/{@link pollStreamEvents} on this page. */
  private async loadMaintenanceRecords(assetId: string): Promise<void> {
    try {
      this.maintenanceRecords.set(await this.api.listAssetMaintenance(assetId));
    } catch {
      this.maintenanceRecords.set([]);
    }
  }

  private async pollStreamEvents(streamId: string): Promise<void> {
    try {
      this.streamEventsSignal.set(await this.api.streamEvents(streamId, STREAM_EVENTS_LIMIT));
    } catch {
      // Silent-degrade — same convention as every other poll on this page (enrichment, not a
      // user-initiated action).
    }
  }

  private async fetchAsset(assetId: string): Promise<void> {
    this.loading.set(true);
    try {
      const details = await this.api.getAsset(assetId);
      this.asset.set(details);
      this.notFound.set(false);
    } catch {
      this.notFound.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * The KPI row's own fetch (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 3), independent of
   * `fetchAsset` by design: a `/stats` failure/404 must never flip `notFound` or block the rest of the
   * page — it only ever affects `stats` (rendered as an all-`'—'` row by `kpiTiles` when `undefined`),
   * and on failure leaves it at whatever it already was rather than resetting it.
   */
  private async loadStats(assetId: string): Promise<void> {
    try {
      this.stats.set(await this.api.assetStats(assetId));
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  private refresh(): Promise<void> {
    const id = this.currentAssetId;
    if (!id) {
      return Promise.resolve();
    }
    return Promise.all([this.fetchAsset(id), this.loadStats(id), this.loadMaintenanceRecords(id), this.loadFleetSummary()]).then(
      () => undefined,
    );
  }

  /** The cockpit-link band's secondary CTA — the lightweight single-device watch page, read-only. */
  watch(): Promise<boolean> | undefined {
    const device = this.videoDevice();
    return device ? this.router.navigate(['/live', device.id]) : undefined;
  }

  /**
   * The cockpit-link band's primary CTA — remembers this asset as Fly's active pick
   * (`SettingsFacade.flyAssetId`, the same field `FlyPage#selectAsset` itself writes) so `/fly` lands
   * directly in the cockpit for it, then navigates. Works whether or not the asset is streaming.
   */
  openCockpit(): void {
    const id = this.currentAssetId;
    if (!id) {
      return;
    }
    this.settings.flyAssetId.set(id);
    void this.router.navigate(['/fly']);
  }

  /** Unused by the current template (carried over verbatim from the pre-facade page, which never
   *  wired a "Back" button to it either — the header's own "All assets" link uses a plain
   *  `routerLink` instead) — kept so relocating it here is a pure move, not a behavior decision. */
  back(): Promise<boolean> {
    return this.router.navigate(['/assets']);
  }

  // --- Identity + attributes editors — both submit the asset's full replacement `identity`/
  //     `attributes` (`application.AssetEdit`'s own Javadoc), never a merge. --------------------

  /** `true` = ok for the caller to close its editor (saved, or nothing needed saving); `false` = the
   *  update failed (`FleetStore.updateAsset` already toasted) and the editor should stay open.
   *  Replaces the old `saveRegistrationNumber` (single-field, `attributes.registrationNumber`) —
   *  this wave's edit form covers all four `AssetIdentity` fields at once, PATCH'd via `identity`
   *  (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4). */
  async saveIdentity(serialNumber: string, make: string, model: string, registration: string): Promise<boolean> {
    const asset = this.asset();
    if (!asset) {
      return false;
    }
    const identity: AssetIdentity = buildIdentityEdit(serialNumber, make, model, registration);
    this.busy.set(true);
    try {
      return !!(await this.fleet.updateAsset(asset.assetId, { identity }));
    } finally {
      this.busy.set(false);
    }
  }

  async saveAttributes(attributes: Record<string, string>): Promise<boolean> {
    const asset = this.asset();
    if (!asset) {
      return false;
    }
    this.busy.set(true);
    try {
      return !!(await this.fleet.updateAsset(asset.assetId, { attributes }));
    } finally {
      this.busy.set(false);
    }
  }

  /** `true` = ok to close (nothing to change, or saved) — mirrors `saveRegistrationNumber` above. */
  async saveAssetEdit(displayName: string, category: string): Promise<boolean> {
    const asset = this.asset();
    if (!asset) {
      return false;
    }
    const edit = buildAssetEdit({ displayName, category }, asset);
    if (Object.keys(edit).length === 0) {
      return true;
    }
    this.busy.set(true);
    try {
      const updated = await this.fleet.updateAsset(asset.assetId, edit);
      if (updated) {
        await this.refresh();
      }
      return !!updated;
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * The header's one destructive lifecycle action (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 2's
   * Archive/Restore pair). **Callers must confirm first** — `asset-detail.ts#requestArchiveAsset`
   * gates this behind `<vision-confirm-dialog>` (docs/extracts/design/05-asset-detail.md's own acceptance
   * criterion), a departure from this method's original "Undo over confirm, fires immediately"
   * design (item 3b) now that Archive lives behind a kebab rather than a plain header button — the
   * Undo toast below stays too, so a mistaken confirm is still one click from reversed. Bypasses
   * `FleetStore.deleteAsset`/`setAssetState` the same way `features/devices/devices.ts#archiveAssetNow`
   * does — `core/fleet/fleet-store.ts` isn't touched this batch, so this page attaches its own Undo
   * action to its own toast instead of `FleetStore`'s plain one.
   */
  async archiveAssetNow(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await this.api.deleteAsset(asset.assetId);
      this.undoToast.showUndo(
        `Archived "${result.displayName}" — ${pluralize(result.devicesDeleted, 'device')} archived, ` +
          `${pluralize(result.usagesRetained, 'usage')} retained, ${pluralize(result.streamsStopped, 'stream')} stopped.`,
        () => void this.restoreAssetNow(),
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refresh()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busy.set(false);
    }
  }

  /** Both the Undo toast action and the header's explicit "Restore asset" button call this. */
  async restoreAssetNow(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.busy.set(true);
    try {
      await this.api.setAssetState(asset.assetId, RESTORE_TARGET_STATE);
      this.toasts.ok(`Restored "${asset.displayName}".`);
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refresh()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busy.set(false);
    }
  }

  // --- Hardware section (docs/main/CYCLES-PLAN.md §11 item 2; docs/plans/done/UX-REWORK-PLAN.md §U-a item 7, §U-a2) -

  /** Archive executes immediately, no confirm dialog — same bypass-`FleetStore` reasoning as
   *  `archiveAssetNow` above. Undo reuses the explicit Restore path (`setDeviceLifecycle`). */
  async archiveDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.deleteDevice(device.id);
      this.undoToast.showUndo(`Archived "${device.name}".`, () => void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refresh()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** Deactivate also offers an Undo toast (docs/plans/done/OPS-CORE-PLAN.md §Q2) — same bypass-`FleetStore`
   *  reasoning as `archiveDeviceNow`. */
  async deactivateDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.setDeviceState(device.id, 'DEACTIVATED');
      this.undoToast.showUndo(`Deactivated "${device.name}".`, () => void this.setDeviceLifecycle(device, 'ACTIVE'));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refresh()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  async setDeviceLifecycle(device: Device, state: SettableLifecycleState): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.setDeviceState(device.id, state));
  }

  async unassignDevice(device: Device): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    await this.runDeviceAction(device.id, () => this.fleet.unassignDevice(asset.assetId, device.id));
  }

  /** `true` = ok for the caller to close its own inline row — mirrors the editor-save methods above. */
  async renameDevice(device: Device, newName: string): Promise<boolean> {
    const edit = buildDeviceRenameEdit(newName, device);
    if (Object.keys(edit).length === 0) {
      return true;
    }
    let success = false;
    await this.runDeviceAction(device.id, async () => {
      success = !!(await this.fleet.updateDevice(device.id, edit));
    });
    return success;
  }

  private async runDeviceAction(deviceId: string, action: () => Promise<unknown>): Promise<void> {
    this.busyDeviceId.set(deviceId);
    try {
      await action();
      await this.refresh();
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** Attaching an existing, unowned device — resolved on demand, not kept warm on every page load. */
  async loadAssignableDevices(): Promise<void> {
    this.loadingAssignable.set(true);
    try {
      const [allDevices, summaries] = await Promise.all([this.api.listDevices(), this.api.listAssets()]);
      const details = await Promise.all(summaries.map((summary) => this.api.getAsset(summary.assetId).catch(() => undefined)));
      const owned = new Set<string>();
      for (const detail of details) {
        for (const device of detail?.devices ?? []) {
          owned.add(device.id);
        }
      }
      this.assignableDevices.set(allDevices.filter((device) => !owned.has(device.id) && device.state !== 'DELETED'));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.loadingAssignable.set(false);
    }
  }

  /** `true` = ok for the caller to close the assign picker — mirrors the editor-save methods above. */
  async assignDevice(deviceId: string): Promise<boolean> {
    const asset = this.asset();
    if (!asset) {
      return false;
    }
    this.busy.set(true);
    try {
      const updated = await this.fleet.assignDevice(asset.assetId, deviceId);
      if (updated) {
        await this.refresh();
      }
      return !!updated;
    } finally {
      this.busy.set(false);
    }
  }

  // --- Links panel (docs/plans/active/LINK-PAIRING-PLAN.md §3.4/§3.7, wave L4) --------------------
  // One asset can carry several redundant carriers for the same telemetry device (Wi-Fi, ground
  // radio, a bench cable) — `LinksStore` tracks the whole group; every derivation below is a thin
  // read-model over it, mirroring `geofence`/`marks` etc. immediately above. Degrades honestly per
  // `LinksStore`'s own class doc: `links.disabled()` means the backend route isn't mounted at all
  // (not "no links yet"), `links.group()` staying `undefined` past that is the ordinary
  // still-loading/no-data case — the panel renders `vision-empty` either way, with a different
  // reason.

  readonly links = inject(LinksStore);

  readonly linksSorted = computed(() => sortedLinks(this.links.group()?.links ?? []));
  readonly linksPinned = computed(() => this.links.group()?.pinned ?? false);
  readonly linksActiveId = computed(() => this.links.group()?.activeLinkId ?? null);
  readonly linksBenchWarning = computed(() => hasActiveBenchWarning(this.links.group()?.links ?? []));

  /** Failover history off the existing generic events feed (`LiveFacade.liveEvents()` — only ever
   *  populated while the SSE connection has been open; there is no REST history read for this, same
   *  as every other `LiveEvent` consumer in this app), filtered to this asset. */
  readonly linkFailovers = computed<readonly LinkFailoverRow[]>(() =>
    failoverRowsForAsset(this.liveStore.liveEvents(), this.currentAssetIdSignal() ?? ''),
  );

  linkKind(link: LinkView): string {
    return linkKindLabel(link.carrier, link.serialRole);
  }

  linkHealth(link: LinkView): string {
    return linkHealthLabel(link);
  }

  linkQuality(link: LinkView): string | undefined {
    return linkQualitySummary(link.quality);
  }

  /**
   * The device the Links panel's recovery actions (Replace hardware / Forget pairing / Fix address)
   * act on. **Assumed, not verified against a real backend**: `LinkView` (§3.4's own frozen shape)
   * carries no `deviceId` of its own — `PairingService` is keyed on `DeviceId`, `VehicleLinkPort`/
   * `LinkStateService` on `AssetId`, two different id spaces (docs/plans/active/LINK-PAIRING-PLAN.md
   * §3.3 vs §3.4) — so there is no per-link id to resolve a recovery target against. This assumes the
   * 1:1 case every fixture in the plan shows: one asset, one paired telemetry device, whose carriers
   * are what the link group lists. An asset with more than one independently-paired telemetry device
   * would need a real per-link `deviceId` in the DTO to disambiguate — flagged for L2/L3.
   */
  readonly pairingDevice = computed<Device | undefined>(() => this.assetTelemetryDevices()[0]);

  async pinLink(linkId: string): Promise<void> {
    const assetId = this.currentAssetId;
    if (!assetId) {
      return;
    }
    await this.links.pin(assetId, linkId);
  }

  async releaseLinkPin(): Promise<void> {
    const assetId = this.currentAssetId;
    if (!assetId) {
      return;
    }
    await this.links.releasePin(assetId);
  }

  /** `POST /api/devices/{id}/pairing/replace-hardware` — this device keeps its identity/name, the
   *  physical radio/board behind it is swapped. Toasts on failure; re-reads the link group
   *  immediately on success so the panel doesn't wait out the poll interval. */
  async replaceLinkHardware(): Promise<void> {
    const device = this.pairingDevice();
    if (!device) {
      return;
    }
    this.busyDeviceId.set(device.id);
    try {
      await this.api.replaceDevicePairingHardware(device.id);
      this.toasts.ok(`Ready to pair new hardware for "${device.name}".`);
      await this.links.refreshNow();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** `DELETE /api/devices/{id}/pairing` — irreversible (§3.3's own frozen "hard-delete `forget`"
   *  decision); the shared undo/confirm idiom around this call is the caller's job (the panel opens a
   *  `vision-confirm-dialog` before invoking this), not this method's. */
  async forgetLinkPairing(): Promise<void> {
    const device = this.pairingDevice();
    if (!device) {
      return;
    }
    this.busyDeviceId.set(device.id);
    try {
      await this.api.forgetDevicePairing(device.id);
      this.toasts.ok(`Forgot the pairing for "${device.name}".`);
      await this.links.refreshNow();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** `PATCH /api/devices/{id}` with a new `uri` — `protocol` is always sent alongside it (unchanged,
   *  read off the device's own current value) since the backend requires the pair together whenever
   *  either is present (`DeviceEdit`'s own doc comment). `true` = ok for the caller to close its own
   *  dialog, mirroring the editor-save methods above. */
  async fixLinkAddress(uri: string): Promise<boolean> {
    const device = this.pairingDevice();
    const trimmed = uri.trim();
    if (!device || trimmed.length === 0) {
      return false;
    }
    let success = false;
    await this.runDeviceAction(device.id, async () => {
      success = !!(await this.fleet.updateDevice(device.id, { protocol: device.protocol, uri: trimmed }));
    });
    if (success) {
      await this.links.refreshNow();
    }
    return success;
  }

  // --- Per-device telemetry (called from the page's own `@for`-bound template helpers) -----------

  latestSampleFor(deviceId: string): TelemetrySample | undefined {
    const samples = this.telemetryByDevice().get(deviceId);
    return samples && samples.length > 0 ? samples[samples.length - 1] : undefined;
  }

  // --- Asset photo (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — GET image URL as img src, graceful 404) -
  // Pure URL construction (no HTTP call itself — the browser's own `<img src>` fetch is what
  // actually hits the network); exposed so the page doesn't need its own `VisionApi` injection just
  // for this one string-builder. The 404-degrade that used to live in the page as an
  // `imageLoadFailed` signal now lives in `shared/ui/page-bar`'s own `showAvatar`, since the photo
  // renders as the bar's avatar — see that component's `avatarSrc` doc comment.
  imageUrl(assetId: string): string {
    return this.api.assetImageUrl(assetId);
  }
}
