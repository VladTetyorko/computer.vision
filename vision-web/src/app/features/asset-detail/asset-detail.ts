import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { EventsStore } from '../../core/events/events-store';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { ageSeconds, isStale, trackingIdChanged } from '../../core/telemetry/telemetry-logic';
import { filterEvents, relativeTimeLabel } from '../../core/events/events-logic';
import {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  buildAssetEdit,
  buildDeviceRenameEdit,
  operatorAssetActions,
  reasonedDeviceActions,
  type ActionAvailability,
  type DeviceLifecycleAction,
} from '../../core/fleet/warehouse-logic';
import { formatDuration } from '../../core/stream-info-logic';
import { registrationNumberOf, withRegistrationNumber, withoutRegistrationNumber } from '../../core/fleet/asset-attributes';
import {
  flightBars as buildFlightBars,
  kpiTiles as buildKpiTiles,
  usageDurationSeconds,
  type FlightBar,
  type KpiTile,
} from '../../core/fleet/asset-stats-logic';
import { LiveMap } from '../../shared/map/live-map/live-map';
import {
  attributeRowsToRecord,
  attributesToRows,
  freshestSample,
  groupTelemetryByDevice,
  telemetryDevices,
  type AttributeRow,
} from './asset-detail-logic';
import type {
  AssetDetails,
  AssetStats,
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
 * The asset **manager** page (`/assets/:id`, docs/CYCLES-PLAN.md §11, CD-b item 2 — reworked from a
 * hybrid manager/cockpit page into a pure manager view by docs/ASSET-MANAGER-PAGE-PLAN.md Wave B).
 * The "Open" target from the Devices page's asset-first list: everything about one entity except
 * piloting — characteristics, KPI utilization tiles, a "Recent flights" chart, a map with position +
 * trail, telemetry grouped per source device (item 3), usage history, and a "Hardware" section
 * carrying the full warehouse actions the list-level view demoted (item 1).
 *
 * **No video, ever, on this page** (Wave B's own guardrail) — piloting and live video are the
 * cockpit's job (`/fly`) and the lightweight `/live/:deviceId` watch page, both one click away via
 * the cockpit-link band (`openCockpit`/`watch` below), never embedded here. This page used to mount
 * `<vision-player>`/`<vision-stream-info>` directly (a `DetectionsStore`-fed overlay and inline
 * Start/Stop stream controls); all of that piloting surface was deleted in Wave B, not hidden —
 * `live()`/`stream()` survive only because the Events card still shows the live per-stream feed
 * while someone *else* is streaming this asset, which is read-only observation, not piloting.
 *
 * Reuses rather than rebuilds: `<vision-live-map>` (moved to `shared/map/` for exactly this reuse
 * — see its doc comment) and `TelemetryStore` (page-provided, same DI-sharing idiom as `LivePage`).
 * `TelemetryStore.track()` is started against *any* device on this asset (not necessarily a
 * TELEMETRY-capable one specifically) — it resolves the *owning asset*'s open usage regardless of
 * which of the asset's devices it's given, so `latest()`/`trail()` already aggregate every source
 * device's samples for the map exactly like item 3 asks ("the map marker uses the freshest
 * source"); this page's own `groupTelemetryByDevice` (`asset-detail-logic.ts`) reruns that same
 * poll's raw `samples()` through per-device grouping for the labeled per-source panels.
 *
 * The KPI row + chart (Wave B items 3–4) read `AssetStats` (`VisionApi.assetStats`) and
 * `AssetDetails#recentUsages` through `core/fleet/asset-stats-logic.ts` — see `loadStats`'s own doc
 * comment for how that fetch degrades independently of the rest of the page.
 */
@Component({
  selector: 'vision-asset-detail',
  imports: [RouterLink, LiveMap],
  templateUrl: './asset-detail.html',
  styleUrl: './asset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore],
})
export class AssetDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly assetId = input.required<string>();

  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly telemetry = inject(TelemetryStore);
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
  /** Read-only: whether *someone* is currently streaming this asset — the cockpit-link band's status chip and the Events card's live/global feed switch. Piloting (starting/stopping) is the cockpit's job; this page never does it. */
  protected readonly live = computed(() => this.stream() !== undefined);

  // --- Events (docs/MVP2-PLAN.md §E, E-b bullet 2) ---------------------------------------------
  // Per the plan's own scoping: the per-stream feed while this asset is actively streaming (a
  // dedicated small poll below), else recent events matched by `assetId` from the shared global
  // feed — never both, since a per-stream feed is strictly more precise than filtering the global
  // one once a `streamId` is known.

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

  /**
   * The header's one lifecycle button (docs/UX-REWORK-PLAN.md §U-a2 item 2 — operator-facing
   * surfaces show only Archive/Restore; activate/deactivate no longer render anywhere on this
   * page). Exactly one of the pair `operatorAssetActions` returns is ever `available` — see that
   * function's own doc comment — so the header renders exactly that one, never a disabled sibling.
   */
  protected readonly assetLifecycleAction = computed(() =>
    operatorAssetActions(this.lifecycle()).find((entry) => entry.available),
  );

  // --- KPI tile row + "Recent flights" chart (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B items 3–4) -
  // `stats` is fetched independently of `asset` (see `loadStats`'s own doc comment below) — the KPI
  // row degrades to an all-"—" row on failure, it never blocks the rest of the page.

  protected readonly stats = signal<AssetStats | undefined>(undefined);

  protected readonly kpiTiles = computed<readonly KpiTile[]>(() => buildKpiTiles(this.stats(), this.nowSignal()));

  protected readonly flightBars = computed<readonly FlightBar[]>(() =>
    buildFlightBars(this.asset()?.recentUsages ?? [], this.nowSignal()),
  );

  // --- Asset photo (docs/UX-REWORK-PLAN.md §U-d item 3 — GET image URL as img src, graceful 404) -
  // `hasImage` is optional on `AssetSummary`/`AssetDetails` (a backend predating the image endpoint
  // pair simply omits it — see that field's own doc comment in `models.ts`); `imageLoadFailed`
  // additionally covers the narrower case of a `hasImage: true` asset whose photo 404s anyway (the
  // pinned contract's own `GET`'s documented failure mode — e.g. removed out from under a stale
  // page), so the `<img>` degrades to "no photo" instead of a broken-image icon either way.

  private readonly imageLoadFailed = signal(false);

  protected readonly assetImageSrc = computed(() => {
    const asset = this.asset();
    if (!asset?.hasImage || this.imageLoadFailed()) {
      return undefined;
    }
    return this.api.assetImageUrl(asset.assetId);
  });

  protected onImageError(): void {
    this.imageLoadFailed.set(true);
  }

  // --- Registration/tail number (docs/UX-REWORK-PLAN.md §U-d item 3) — the `registrationNumber`
  // attributes-key convention (`core/fleet/asset-attributes.ts`), display + inline edit --------

  protected readonly registrationNumber = computed(() => registrationNumberOf(this.asset()?.attributes ?? {}));
  protected readonly editingRegistrationNumber = signal(false);
  protected readonly registrationNumberDraft = signal('');

  protected openEditRegistrationNumber(): void {
    this.registrationNumberDraft.set(this.registrationNumber() ?? '');
    this.editingRegistrationNumber.set(true);
  }

  protected cancelEditRegistrationNumber(): void {
    this.editingRegistrationNumber.set(false);
  }

  /**
   * `attributes` is a full replacement map, never a merge (`application.AssetEdit`'s own Javadoc) —
   * both branches below start from the asset's *complete* current `attributes`, never just the one
   * key, so no other attribute is silently dropped.
   */
  protected async confirmEditRegistrationNumber(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    const draft = this.registrationNumberDraft().trim();
    const attributes =
      draft.length > 0 ? withRegistrationNumber(asset.attributes, draft) : withoutRegistrationNumber(asset.attributes);
    this.busy.set(true);
    try {
      const updated = await this.fleet.updateAsset(asset.assetId, { attributes });
      if (updated) {
        this.editingRegistrationNumber.set(false);
      }
    } finally {
      this.busy.set(false);
    }
  }

  // --- Attributes editor (docs/UX-REWORK-PLAN.md §U-d item 3 — advanced-mode key/value rows) ---
  // Shows every attribute raw, including `registrationNumber` — this is the "advanced" escape
  // hatch, it doesn't hide anything the simpler inline edit above already covers.

  protected readonly attributesEditorOpen = signal(false);
  protected readonly attributeRows = signal<readonly AttributeRow[]>([]);
  protected readonly attributesSubmitting = signal(false);

  protected openAttributesEditor(): void {
    this.attributeRows.set(attributesToRows(this.asset()?.attributes ?? {}));
    this.attributesEditorOpen.set(true);
  }

  protected cancelAttributesEditor(): void {
    this.attributesEditorOpen.set(false);
  }

  protected addAttributeRow(): void {
    this.attributeRows.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeAttributeRow(index: number): void {
    this.attributeRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected updateAttributeKey(index: number, key: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  protected updateAttributeValue(index: number, value: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }

  /** Submits every current row as the full replacement map — see `confirmEditRegistrationNumber`'s doc comment. */
  protected async confirmAttributesEditor(): Promise<void> {
    const asset = this.asset();
    if (!asset) {
      return;
    }
    this.attributesSubmitting.set(true);
    try {
      const updated = await this.fleet.updateAsset(asset.assetId, {
        attributes: attributeRowsToRecord(this.attributeRows()),
      });
      if (updated) {
        this.attributesEditorOpen.set(false);
      }
    } finally {
      this.attributesSubmitting.set(false);
    }
  }

  // --- Asset header edit (rename/category) + lifecycle -------------------------------------

  protected readonly editingAsset = signal(false);
  protected readonly nameDraft = signal('');
  protected readonly categoryDraft = signal('');

  // --- Hardware section: per-device inline actions + attach-a-device ------------------------
  // Archive (docs/UX-REWORK-PLAN.md §U-a2 item 3b — "Undo over confirm") fires immediately from
  // the kebab menu, no inline confirm step; only rename still needs one (it needs typed input).

  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' } | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly busyDeviceId = signal<string | null>(null);

  protected readonly assignOpen = signal(false);
  protected readonly assignableDevices = signal<readonly Device[]>([]);
  protected readonly assignDraft = signal('');
  protected readonly loadingAssignable = signal(false);

  // --- Telemetry re-entry guard (docs/REALTIME-PLAN.md Phase R-a item 2, R-c follow-up) — mirrors
  // `FlyPage`'s own identical field exactly (see its doc comment): the last deviceId the
  // corresponding constructor effect below actually acted on, compared by value
  // (`core/telemetry/telemetry-logic.ts#trackingIdChanged`), never by the enclosing `asset()`
  // object's own identity, which is a fresh reference every ~5s poll tick regardless of whether the
  // tracked id changed. Without this, re-entering `telemetry.track()` with an unchanged id every
  // tick was worse than a wasted re-fetch: `TelemetryStore`'s own `track()` tears down and rebuilds
  // its `LiveStore` subscription on every call, and doing that from inside an already-executing
  // effect let a write deep inside that teardown (the store's own internal `currentAssetIdSignal`)
  // get attributed back to *this* effect — re-notifying it with `asset()`/`assetTelemetryDevices()`
  // unchanged, and re-entering `track()` again, in a tight loop paced only by how fast the store's
  // own async lookups resolved (confirmed live: ~50-90 track/untrack cycles/sec against a real
  // backend, not the 5s poll cadence at all).
  private lastTelemetryDeviceId: string | undefined = undefined;

  constructor() {
    effect(() => {
      const id = this.assetId();
      this.imageLoadFailed.set(false); // a fresh navigation deserves a fresh attempt at the photo
      void this.load(id);
      void this.loadStats(id);
    });

    // Any device on the asset resolves the same owning-asset/open-usage pair — see class doc.
    // Passes `assetId` — already the route param, no lookup needed (docs/REALTIME-PLAN.md Phase
    // R-a item 3) — so `TelemetryStore` resolves the open usage with one `getAsset()` instead of
    // listing the whole fleet to find which asset owns `devices[0]`.
    //
    // **Guarded on the derived deviceId primitive** (docs/REALTIME-PLAN.md Phase R-a item 2, R-c
    // follow-up) — see the `lastTelemetryDeviceId` field's own doc comment for why this is not
    // optional here the way a bare "avoid a redundant re-fetch" guard would be.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      const deviceId = this.assetTelemetryDevices().length > 0 ? devices[0].id : undefined;
      if (!trackingIdChanged(deviceId, this.lastTelemetryDeviceId)) {
        return;
      }
      this.lastTelemetryDeviceId = deviceId;
      if (deviceId) {
        this.telemetry.track(deviceId, this.assetId());
      } else {
        this.telemetry.reset();
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

  /**
   * The KPI row's own fetch (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 3), independent of
   * `load` above by design: a `/stats` failure/404 must never flip `notFound` or block the rest of
   * the page (the plan's own "same resilience as the existing enrichment reads" wording) — it only
   * ever affects `stats`, which `kpiTiles` (`core/fleet/asset-stats-logic.ts`) renders as an
   * all-`'—'` row when `undefined`. On failure this deliberately leaves `stats` at whatever it
   * already was (the initial `undefined`, or the last successful fetch) rather than resetting it —
   * the same "silent-degrade, keep the last-known value" convention `pollStreamEvents` above uses.
   */
  private async loadStats(assetId: string): Promise<void> {
    try {
      this.stats.set(await this.api.assetStats(assetId));
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  private refresh(): Promise<void> {
    return Promise.all([this.load(this.assetId()), this.loadStats(this.assetId())]).then(() => undefined);
  }

  protected back(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  /** The cockpit-link band's secondary CTA (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 2) — the lightweight single-device watch page, read-only, no start/stop control here. */
  protected watch(): Promise<boolean> | undefined {
    const device = this.videoDevice();
    return device ? this.router.navigate(['/live', device.id]) : undefined;
  }

  /**
   * The cockpit-link band's primary CTA (docs/ASSET-MANAGER-PAGE-PLAN.md, Wave B item 2) — remembers
   * this asset as Fly's active pick (`SettingsStore.flyAssetId`, the same field `FlyPage#selectAsset`
   * itself writes) so `/fly` lands directly in the cockpit for it, then navigates. Works whether or
   * not the asset is currently streaming — the cockpit owns start/stop, this page never does.
   */
  protected openCockpit(): void {
    this.settings.flyAssetId.set(this.assetId());
    void this.router.navigate(['/fly']);
  }

  // --- Per-device telemetry panels (called from the template — signals tracked on read, same
  //     idiom as `features/devices/devices.ts#simulatedInfo`) -----------------------------------

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
    return formatDuration(usageDurationSeconds(usage, this.nowSignal()));
  }

  /** The "Recent flights" chart's per-bar hover/focus label — mouse `title` and screen-reader `aria-label` alike. */
  protected barLabel(bar: FlightBar): string {
    const when = new Date(bar.startedAt).toLocaleString();
    const duration = formatDuration(bar.durationSeconds);
    return bar.open ? `${when} · ${duration} so far · in progress` : `${when} · ${duration}`;
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

  protected renameAsset(): void {
    this.openEditAsset();
  }

  protected assetActionLabel(action: 'archive' | 'restore'): string {
    return action === 'archive' ? 'Archive asset' : 'Restore asset';
  }

  /**
   * The header's one lifecycle action (docs/UX-REWORK-PLAN.md §U-a2 item 2's Archive/Restore pair).
   * Archive executes immediately, no confirm dialog (item 3b — "Undo over confirm"; the previous
   * `archiveConfirmOpen` card is gone). Bypasses `FleetStore.deleteAsset`/`setAssetState` for the
   * same reason `features/devices/devices.ts#archiveAssetNow` does — see that method's own doc
   * comment: `core/fleet/fleet-store.ts` isn't touched this batch, so this page attaches its own
   * Undo action to its own toast instead of `FleetStore`'s plain one.
   */
  protected onAssetLifecycleAction(action: 'archive' | 'restore'): void {
    if (action === 'archive') {
      void this.archiveAssetNow();
    } else {
      void this.restoreAssetNow();
    }
  }

  protected async archiveAssetNow(): Promise<void> {
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
  protected async restoreAssetNow(): Promise<void> {
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

  // --- Hardware section (docs/CYCLES-PLAN.md §11 item 2 — full warehouse actions; docs/UX-REWORK-PLAN.md
  //     §U-a item 7 — collapsed into a per-row kebab; §U-a2 item 3a — reasoned/disabled-with-reason) -

  /**
   * Every device shown here already belongs to this asset — `owned` is always `true`, and `assign`
   * never applies (filtered out below; only `unassign` is meaningful). `a.devices.length` is this
   * asset's own current device count, so Unassign disables itself with a reason exactly when this
   * device is the asset's only one (see `reasonedDeviceActions`'s own doc comment).
   */
  protected deviceActionsFor(device: Device): readonly ActionAvailability<DeviceLifecycleAction>[] {
    const ownerDeviceCount = this.asset()?.devices.length;
    return reasonedDeviceActions(device.state, true, ownerDeviceCount).filter(
      (entry) => entry.action !== 'assign',
    );
  }

  protected deviceActionLabel(action: DeviceLifecycleAction): string {
    return DEVICE_ACTION_LABELS[action];
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
        void this.deactivateDeviceNow(device);
        break;
      case 'archive':
        void this.archiveDeviceNow(device);
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

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b) — same
   * bypass-`FleetStore` reasoning as `archiveAssetNow` above. Undo reuses the existing explicit
   * Restore path (`setDeviceLifecycle`) rather than a bespoke restore method.
   */
  protected async archiveDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.deleteDevice(device.id);
      this.undoToast.showUndo(`Archived "${device.name}".`, () =>
        void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE),
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refresh()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /**
   * Deactivate now offers an Undo toast too (docs/OPS-CORE-PLAN.md §Q2) — mirrors
   * `features/devices/devices.ts#deactivateDeviceNow` exactly (same bypass-`FleetStore` reasoning
   * as `archiveDeviceNow` above: `FleetStore.setDeviceState`'s own success toast has no Undo
   * action). `activate`/the explicit `restore` kebab entry are unchanged.
   */
  protected async deactivateDeviceNow(device: Device): Promise<void> {
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

  protected rowActionMode(deviceId: string): 'rename' | null {
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
