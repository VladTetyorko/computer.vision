import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { ToastService } from '../../core/toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry-store';
import { DetectionsStore } from '../../core/detections-store';
import { EventsStore } from '../../core/events-store';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/device-logic';
import { ageSeconds, isStale } from '../../core/telemetry-logic';
import { filterEvents, relativeTimeLabel } from '../../core/events-logic';
import {
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildDeviceRenameEdit,
  type AssetLifecycleAction,
  type DeviceLifecycleAction,
} from '../../core/warehouse-logic';
import { formatDuration } from '../../core/stream-info-logic';
import { Player, type BoxesMode, type Transport } from '../../ui/player';
import { StreamInfoPanel } from '../../ui/stream-info-panel';
import { LiveMap } from '../../ui/live-map';
import { freshestSample, groupTelemetryByDevice, telemetryDevices } from './asset-detail-logic';
import type {
  AssetDetails,
  AssetUsage,
  DetectionEvent,
  Device,
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
 * The asset detail page (`/assets/:id`, docs/CYCLES-PLAN.md §11, CD-b item 2) — the "Open" target
 * from the Devices page's asset-first list. One entity, everything about it: characteristics, live
 * video when streaming, a map with position + trail, telemetry grouped per source device (item 3),
 * usage history, and a "Hardware" section carrying the full warehouse actions the list-level view
 * demoted (item 1).
 *
 * Reuses rather than rebuilds: `<vision-player>` (self-recovering, shared with Wall/Live/the map
 * dock), `<vision-stream-info>` (docs/MVP2-PLAN.md §U-info's user-meaning-first info panel),
 * `<vision-live-map>` (moved to `ui/` for exactly this reuse — see its doc comment), and
 * `TelemetryStore`/`DetectionsStore` (page-provided, same DI-sharing idiom as `LivePage`).
 * `TelemetryStore.track()` is started against *any* device on this asset (not necessarily a
 * TELEMETRY-capable one specifically) — it resolves the *owning asset*'s open usage regardless of
 * which of the asset's devices it's given, so `latest()`/`trail()` already aggregate every source
 * device's samples for the map exactly like item 3 asks ("the map marker uses the freshest
 * source"); this page's own `groupTelemetryByDevice` (`asset-detail-logic.ts`) reruns that same
 * poll's raw `samples()` through per-device grouping for the labeled per-source panels.
 */
@Component({
  selector: 'vision-asset-detail',
  imports: [RouterLink, Player, StreamInfoPanel, LiveMap],
  templateUrl: './asset-detail.html',
  styleUrl: './asset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, DetectionsStore],
})
export class AssetDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly assetId = input.required<string>();

  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);
  protected readonly events = inject(EventsStore);

  protected readonly asset = signal<AssetDetails | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly busy = signal(false);

  private readonly nowSignal = signal(Date.now());

  protected readonly videoDevice = computed(() => findVideoDevice(this.asset()?.devices ?? []));
  protected readonly stream = computed(() => {
    const device = this.videoDevice();
    return device ? this.fleet.streamFor(device.id) : undefined;
  });
  protected readonly live = computed(() => this.stream() !== undefined);

  // --- Deliberately-stopped state (docs/MVP2-PLAN.md §S, S-b) — mirrors `LivePage`'s own pair
  // exactly (see its doc comment for the full reasoning): this page's own Stop action, plus
  // "this page watched it go live, then it disappeared from the streams list" (someone else's
  // stop, or this page's own — both read the same way once it's gone).
  private readonly explicitlyStopped = signal(false);
  private readonly hasBeenLive = signal(false);
  protected readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  // --- Events (docs/MVP2-PLAN.md §E, E-b bullet 2) ---------------------------------------------
  // Per the plan's own scoping: the per-stream feed while this asset is actively streaming (a
  // dedicated small poll below, mirroring `DetectionsStore`'s own per-stream cadence), else recent
  // events matched by `assetId` from the shared global feed — never both, since a per-stream feed
  // is strictly more precise than filtering the global one once a `streamId` is known.

  private readonly streamEventsSignal = signal<readonly DetectionEvent[]>([]);

  protected readonly displayedEvents = computed(() =>
    this.live()
      ? this.streamEventsSignal()
      : filterEvents(this.events.events(), { assetId: this.assetId() }),
  );

  protected readonly assetTelemetryDevices = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  protected readonly telemetryByDevice = computed(() => groupTelemetryByDevice(this.telemetry.samples()));

  /** Which device the map marker's current position most likely came from — item 3's transparency ask. */
  protected readonly freshestSourceName = computed(() => {
    const freshest = freshestSample(this.telemetryByDevice());
    if (!freshest) {
      return undefined;
    }
    const device = this.assetTelemetryDevices().find((d) => d.id === freshest.deviceId);
    return device?.name ?? freshest.deviceId;
  });

  protected readonly lifecycle = computed(() => this.asset()?.lifecycle ?? 'ACTIVE');
  protected readonly archived = computed(() => this.lifecycle() === 'DELETED');
  protected readonly assetActions = computed(() => availableAssetActions(this.lifecycle()));

  protected readonly latencySeconds = signal<number | null>(null);
  /** The player's own live transport (docs/MVP2-PLAN.md §L / §U3), piped into `StreamInfoPanel` too. */
  protected readonly transport = signal<Transport>('hls');
  /** Per-tile "boxes: overlay/burned/off" toggle (docs/CYCLES-PLAN.md §11 item 6) — defaults to overlay. */
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  // --- Asset header edit (rename/category) + lifecycle -------------------------------------

  protected readonly editingAsset = signal(false);
  protected readonly nameDraft = signal('');
  protected readonly categoryDraft = signal('');
  protected readonly archiveConfirmOpen = signal(false);

  // --- Hardware section: per-device inline actions + attach-a-device ------------------------

  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'archive' } | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly busyDeviceId = signal<string | null>(null);

  protected readonly assignOpen = signal(false);
  protected readonly assignableDevices = signal<readonly Device[]>([]);
  protected readonly assignDraft = signal('');
  protected readonly loadingAssignable = signal(false);

  constructor() {
    effect(() => {
      const id = this.assetId();
      void this.load(id);
    });

    // Latches once `live()` is ever observed true — see `stopped`'s own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Any device on the asset resolves the same owning-asset/open-usage pair — see class doc.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      if (this.assetTelemetryDevices().length > 0) {
        this.telemetry.track(devices[0].id);
      } else {
        this.telemetry.reset();
      }
    });

    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        this.detections.track(streamId);
      } else {
        this.detections.reset();
      }
    });

    // The per-stream events feed only makes sense while there is a streamId to ask about — an
    // immediate fetch on transition, then `stopStreamEventsPoll` below keeps it fresh.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        void this.pollStreamEvents(streamId);
      } else {
        this.streamEventsSignal.set([]);
      }
    });

    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — see `EventsStore`'s own doc
    // comment: this is one of exactly three pages that keeps the shared global events poll alive.
    this.events.activate();

    // Every poll registration below returns its own promise (not `void`-discarded) so
    // `PollScheduler`'s in-flight guard can skip a tick while the previous one is still pending,
    // rather than piling another request on top of a slow/hung backend (docs/MVP2-PLAN.md §S, S-b).
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

  private async pollStreamEvents(streamId: string): Promise<void> {
    try {
      const results = await this.api.streamEvents(streamId, STREAM_EVENTS_LIMIT);
      this.streamEventsSignal.set(results);
    } catch {
      // Silent-degrade — same convention as every other poll on this page (enrichment, not a
      // user-initiated action).
    }
  }

  protected relativeTime(event: DetectionEvent): string {
    return relativeTimeLabel(event.lastSeen, this.nowSignal());
  }

  private async load(assetId: string): Promise<void> {
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

  private refresh(): Promise<void> {
    return this.load(this.assetId());
  }

  protected back(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  protected watch(): Promise<boolean> | undefined {
    const device = this.videoDevice();
    return device ? this.router.navigate(['/live', device.id]) : undefined;
  }

  protected onLatency(seconds: number | null): void {
    this.latencySeconds.set(seconds);
  }

  protected onTransport(transport: Transport): void {
    this.transport.set(transport);
  }

  protected setBoxesMode(mode: BoxesMode): void {
    this.boxesMode.set(mode);
  }

  protected async start(): Promise<void> {
    const device = this.videoDevice();
    if (!device) {
      return;
    }
    this.busy.set(true);
    try {
      await this.fleet.start(device.id, this.settings.effective());
      this.explicitlyStopped.set(false); // a fresh attach — see `stopped`'s own doc comment
    } finally {
      this.busy.set(false);
    }
  }

  protected async stop(): Promise<void> {
    const stream = this.stream();
    if (!stream) {
      return;
    }
    this.busy.set(true);
    try {
      await this.fleet.stop(stream.streamId);
      this.explicitlyStopped.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  // --- Per-device telemetry panels (called from the template — signals tracked on read, same
  //     idiom as `pages/devices/devices.ts#simulatedInfo`) -----------------------------------

  protected latestSampleFor(deviceId: string): TelemetrySample | undefined {
    const samples = this.telemetryByDevice().get(deviceId);
    return samples && samples.length > 0 ? samples[samples.length - 1] : undefined;
  }

  protected deviceSampleAgeSeconds(deviceId: string): number | undefined {
    return ageSeconds(this.latestSampleFor(deviceId)?.at, this.nowSignal());
  }

  protected deviceStale(deviceId: string): boolean {
    return isStale(this.deviceSampleAgeSeconds(deviceId));
  }

  protected usageDuration(usage: AssetUsage): string {
    const endMs = usage.endedAt ? Date.parse(usage.endedAt) : this.nowSignal();
    return formatDuration((endMs - Date.parse(usage.startedAt)) / 1000);
  }

  protected detailPairs(attributes: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(attributes).map(([key, value]) => ({ key, value }));
  }

  // --- Asset header: rename/category edit + lifecycle (docs/CYCLES-PLAN.md §11 item 2) -------

  protected openEditAsset(): void {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.nameDraft.set(asset.displayName);
    this.categoryDraft.set(asset.category);
    this.editingAsset.set(true);
  }

  protected cancelEditAsset(): void {
    this.editingAsset.set(false);
  }

  protected async confirmEditAsset(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    const edit = buildAssetEdit({ displayName: this.nameDraft(), category: this.categoryDraft() }, asset);
    if (Object.keys(edit).length === 0) {
      this.editingAsset.set(false);
      return;
    }
    this.busy.set(true);
    try {
      const updated = await this.fleet.updateAsset(asset.assetId, edit);
      if (updated) {
        this.editingAsset.set(false);
        await this.refresh();
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected onAssetAction(action: AssetLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.openEditAsset();
        break;
      case 'activate':
        void this.setAssetLifecycle('ACTIVE');
        break;
      case 'deactivate':
        void this.setAssetLifecycle('DEACTIVATED');
        break;
      case 'archive':
        this.archiveConfirmOpen.set(true);
        break;
      case 'restore':
        void this.setAssetLifecycle(RESTORE_TARGET_STATE);
        break;
    }
  }

  protected cancelArchive(): void {
    this.archiveConfirmOpen.set(false);
  }

  protected async confirmArchiveAsset(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await this.fleet.deleteAsset(asset.assetId);
      if (result) {
        await this.router.navigate(['/devices']);
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async setAssetLifecycle(state: SettableLifecycleState): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.busy.set(true);
    try {
      const updated = await this.fleet.setAssetState(asset.assetId, state);
      if (updated) {
        await this.refresh();
      }
    } finally {
      this.busy.set(false);
    }
  }

  // --- Hardware section (docs/CYCLES-PLAN.md §11 item 2 — full warehouse actions) -------------

  /** Every device shown here already belongs to this asset — `owned` is always `true`. */
  protected deviceActionsFor(device: Device): readonly DeviceLifecycleAction[] {
    return availableDeviceActions(device.state, true).filter((action) => action !== 'assign');
  }

  protected onDeviceAction(device: Device, action: DeviceLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.rowAction.set({ deviceId: device.id, mode: 'rename' });
        this.renameDraft.set(device.name);
        break;
      case 'activate':
        void this.setDeviceLifecycle(device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.setDeviceLifecycle(device, 'DEACTIVATED');
        break;
      case 'archive':
        this.rowAction.set({ deviceId: device.id, mode: 'archive' });
        break;
      case 'restore':
        void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE);
        break;
      case 'unassign':
        void this.unassignDevice(device);
        break;
      case 'assign':
        break; // never offered — see deviceActionsFor
    }
  }

  protected rowActionMode(deviceId: string): 'rename' | 'archive' | null {
    const active = this.rowAction();
    return active && active.deviceId === deviceId ? active.mode : null;
  }

  protected cancelRowAction(): void {
    this.rowAction.set(null);
  }

  protected async confirmRename(device: Device): Promise<void> {
    const edit = buildDeviceRenameEdit(this.renameDraft(), device);
    if (Object.keys(edit).length === 0) {
      this.rowAction.set(null);
      return;
    }
    await this.runDeviceAction(device.id, async () => {
      const updated = await this.fleet.updateDevice(device.id, edit);
      if (updated) {
        this.rowAction.set(null);
      }
    });
  }

  protected async confirmArchiveDevice(device: Device): Promise<void> {
    await this.runDeviceAction(device.id, async () => {
      const archived = await this.fleet.deleteDevice(device.id);
      if (archived) {
        this.rowAction.set(null);
      }
    });
  }

  protected async unassignDevice(device: Device): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    await this.runDeviceAction(device.id, () => this.fleet.unassignDevice(asset.assetId, device.id));
  }

  private async setDeviceLifecycle(device: Device, state: SettableLifecycleState): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.setDeviceState(device.id, state));
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
  protected async openAssign(): Promise<void> {
    this.assignOpen.set(true);
    this.loadingAssignable.set(true);
    try {
      const [allDevices, summaries] = await Promise.all([this.api.listDevices(), this.api.listAssets()]);
      const details = await Promise.all(
        summaries.map((summary) => this.api.getAsset(summary.assetId).catch(() => undefined)),
      );
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

  protected cancelAssign(): void {
    this.assignOpen.set(false);
    this.assignDraft.set('');
  }

  protected async confirmAssign(): Promise<void> {
    const asset = this.asset();
    const deviceId = this.assignDraft();
    if (!asset || !deviceId) {
      return;
    }
    this.busy.set(true);
    try {
      const updated = await this.fleet.assignDevice(asset.assetId, deviceId);
      if (updated) {
        this.cancelAssign();
        await this.refresh();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
