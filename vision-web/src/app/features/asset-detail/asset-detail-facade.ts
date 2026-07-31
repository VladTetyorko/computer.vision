import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { EventsStore } from '../../core/events/events-store';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { ageSeconds, isStale, trackingIdChanged } from '../../core/telemetry/telemetry-logic';
import { filterEvents } from '../../core/events/events-logic';
import {
  RESTORE_TARGET_STATE,
  buildAssetEdit,
  buildDeviceRenameEdit,
  operatorAssetActions,
} from '../../core/fleet/warehouse-logic';
import { registrationNumberOf, withRegistrationNumber, withoutRegistrationNumber } from '../../core/fleet/asset-attributes';
import {
  flightBars as buildFlightBars,
  kpiTiles as buildKpiTiles,
  type FlightBar,
  type KpiTile,
} from '../../core/fleet/asset-stats-logic';
import { freshestSample, groupTelemetryByDevice, telemetryDevices, telemetryFactRows, type TelemetryFactRow } from './asset-detail-logic';
import type {
  AssetDetails,
  AssetStats,
  Device,
  DetectionEvent,
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
 * `AssetDetailPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — owns every store/service injection,
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
  private readonly auth = inject(AuthStore);
  private readonly telemetry = inject(TelemetryStore);
  private readonly events = inject(EventsStore);

  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsStore);

  /** Gates the Pilots drill-in trigger itself — a non-manager should never see the affordance, not
   *  just find an empty drawer behind it (`PilotsCard`'s own internal gate stays as a second layer). */
  readonly canManagePilots = computed(() => canManageOrg(this.auth.user()?.topRole));

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

  // --- Events (docs/MVP2-PLAN.md §E, E-b bullet 2) ---------------------------------------------

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

  readonly lifecycle = computed(() => this.asset()?.lifecycle ?? 'ACTIVE');
  readonly archived = computed(() => this.lifecycle() === 'DELETED');

  /**
   * The header's one lifecycle button (docs/UX-REWORK-PLAN.md §U-a2 item 2). Exactly one of the pair
   * `operatorAssetActions` returns is ever `available` — see that function's own doc comment.
   */
  readonly assetLifecycleAction = computed(() => operatorAssetActions(this.lifecycle()).find((entry) => entry.available));

  // --- KPI tile row + "Recent flights" chart (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B items 3–4) -

  readonly stats = signal<AssetStats | undefined>(undefined);
  readonly kpiTiles = computed<readonly KpiTile[]>(() => buildKpiTiles(this.stats(), this.nowSignal()));
  readonly flightBars = computed<readonly FlightBar[]>(() => buildFlightBars(this.asset()?.recentUsages ?? [], this.nowSignal()));

  // --- Registration/tail number + attributes (docs/UX-REWORK-PLAN.md §U-d item 3) ---------------

  readonly registrationNumber = computed(() => registrationNumberOf(this.asset()?.attributes ?? {}));

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
   *  doc comment (docs/REALTIME-PLAN.md Phase R-a item 2, R-c follow-up incident). */
  private lastTelemetryDeviceId: string | undefined = undefined;

  constructor() {
    // Any device on the asset resolves the same owning-asset/open-usage pair (see `TelemetryStore`'s
    // own doc comment) — passing `assetId` lets it resolve the open usage with one `getAsset()`
    // instead of listing the whole fleet.
    //
    // **Guarded on the derived deviceId primitive** (docs/REALTIME-PLAN.md Phase R-a item 2, R-c
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

    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — one of exactly three pages that
    // keeps the shared global events poll alive.
    this.events.activate();

    // Every poll registration below returns its own promise so `PollScheduler`'s in-flight guard can
    // skip a tick while the previous one is still pending (docs/MVP2-PLAN.md §S, S-b).
    const scheduler = inject(PollScheduler);
    const stopAssetPoll = scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    const stopStreamEventsPoll = scheduler.schedule(STREAM_EVENTS_POLL_INTERVAL_MS, () => {
      const streamId = this.stream()?.streamId;
      return streamId ? this.pollStreamEvents(streamId) : undefined;
    });
    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopAssetPoll();
      stopClock();
      stopStreamEventsPoll();
    });
  }

  /** Called once by the page's own constructor `effect()` on every `assetId` route-input change. */
  load(assetId: string): void {
    this.currentAssetIdSignal.set(assetId);
    void this.fetchAsset(assetId);
    void this.loadStats(assetId);
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
   * The KPI row's own fetch (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 3), independent of
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
    return Promise.all([this.fetchAsset(id), this.loadStats(id)]).then(() => undefined);
  }

  /** The cockpit-link band's secondary CTA — the lightweight single-device watch page, read-only. */
  watch(): Promise<boolean> | undefined {
    const device = this.videoDevice();
    return device ? this.router.navigate(['/live', device.id]) : undefined;
  }

  /**
   * The cockpit-link band's primary CTA — remembers this asset as Fly's active pick
   * (`SettingsStore.flyAssetId`, the same field `FlyPage#selectAsset` itself writes) so `/fly` lands
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
   *  wired a "Back" button to it either — the header's own "All devices" link uses a plain
   *  `routerLink` instead) — kept so relocating it here is a pure move, not a behavior decision. */
  back(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  // --- Registration/tail number + attributes editors — both submit the asset's full replacement
  //     `attributes` map (`application.AssetEdit`'s own Javadoc), never a merge. -----------------

  /** `true` = ok for the caller to close its editor (saved, or nothing needed saving); `false` = the
   *  update failed (`FleetStore.updateAsset` already toasted) and the editor should stay open. */
  async saveRegistrationNumber(draft: string): Promise<boolean> {
    const asset = this.asset();
    if (!asset) {
      return false;
    }
    const trimmed = draft.trim();
    const attributes = trimmed.length > 0 ? withRegistrationNumber(asset.attributes, trimmed) : withoutRegistrationNumber(asset.attributes);
    this.busy.set(true);
    try {
      return !!(await this.fleet.updateAsset(asset.assetId, { attributes }));
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
   * The header's one lifecycle action (docs/UX-REWORK-PLAN.md §U-a2 item 2's Archive/Restore pair).
   * Archive executes immediately, no confirm dialog (item 3b — "Undo over confirm"). Bypasses
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
        `Archived "${result.displayName}" — ${result.devicesDeleted} device(s) archived, ` +
          `${result.usagesRetained} usage(s) retained, ${result.streamsStopped} stream(s) stopped.`,
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

  // --- Hardware section (docs/CYCLES-PLAN.md §11 item 2; docs/UX-REWORK-PLAN.md §U-a item 7, §U-a2) -

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

  /** Deactivate also offers an Undo toast (docs/OPS-CORE-PLAN.md §Q2) — same bypass-`FleetStore`
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

  // --- Per-device telemetry (called from the page's own `@for`-bound template helpers) -----------

  latestSampleFor(deviceId: string): TelemetrySample | undefined {
    const samples = this.telemetryByDevice().get(deviceId);
    return samples && samples.length > 0 ? samples[samples.length - 1] : undefined;
  }

  // --- Asset photo (docs/UX-REWORK-PLAN.md §U-d item 3 — GET image URL as img src, graceful 404) -
  // Pure URL construction (no HTTP call itself — the browser's own `<img src>` fetch is what
  // actually hits the network); exposed so the component's `imageLoadFailed`/`assetImageSrc`
  // (local view state around the `<img>` tag's error event) don't need their own `VisionApi`
  // injection just for this one string-builder.

  imageUrl(assetId: string): string {
    return this.api.assetImageUrl(assetId);
  }
}
