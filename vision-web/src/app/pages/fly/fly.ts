import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { ToastService } from '../../core/toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry-store';
import { DetectionsStore } from '../../core/detections-store';
import { EventsStore } from '../../core/events-store';
import { videoDevices } from '../../core/device-logic';
import { telemetryDevices } from '../../core/telemetry-logic';
import { capitalizeLabel, filterEvents, formatConfidence } from '../../core/events-logic';
import { Player, type BoxesMode, type Transport } from '../../ui/player';
import { LiveMap } from '../../ui/live-map';
import { DetectionsStrip } from '../../ui/detections-strip';
import { FlyOsd } from '../../ui/fly-osd';
import {
  TICKER_MAX_EVENTS,
  cycleBoxesMode,
  isWatchMode,
  latestFinishedUsage,
  resolveActiveAssetId,
  sortAssetsForPicker,
} from './fly-logic';
import type { AssetDetails, AssetSummary, DetectionEvent } from '../../core/api/models';

/** Asset characteristics/usages + the picker's own asset list are re-read at this cadence. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/**
 * `/fly` — the operator cockpit and the app's default landing page (docs/MVP3-PLAN.md §C-b, the
 * "one job, one page" persona: *flies ONE drone at a time; everything else is noise*).
 *
 * First visit shows an asset picker (streaming assets first); the choice is remembered
 * (`SettingsStore.flyAssetId`), so every later visit — and every deep link carrying `?asset=` —
 * lands straight in the cockpit. The cockpit itself is thin composition over pieces this app
 * already had: `<vision-player>` (WHEP-first, self-recovering, the `stopped` terminal state),
 * `<vision-live-map>`, `<vision-detections-strip>` (moved to `ui/` for this reuse — see its own doc
 * comment), `TelemetryStore`/`DetectionsStore`/`EventsStore` (identical DI-sharing/activate-release
 * idioms as `LivePage`/`AssetDetailPage`/`WallPage`). The one new piece is `<vision-fly-osd>` — see
 * its own doc comment for why the shape genuinely differs from `TelemetryOsd`.
 *
 * **Multi-device asset**: `primaryDevice` is whichever `VIDEO`-capable device is currently
 * selected (`primaryDeviceId`, defaulting to the asset's first one) — the big player always shows
 * this one; every *other* `VIDEO`-capable device on the asset renders as a small clickable tile
 * that swaps which one is primary. Start/Stop and the deliberately-stopped state (mirrors
 * `LivePage`/`AssetDetailPage`'s own `explicitlyStopped`/`hasBeenLive` pair exactly) both act on
 * the primary device's own stream only — switching primary resets both, since it's effectively a
 * fresh device to watch.
 */
@Component({
  selector: 'vision-fly',
  imports: [RouterLink, Player, LiveMap, DetectionsStrip, FlyOsd],
  templateUrl: './fly.html',
  styleUrl: './fly.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `LivePage`/`AssetDetailPage`.
  providers: [TelemetryStore, DetectionsStore],
})
export class FlyPage {
  /**
   * `?asset=` — a future drill-down target (e.g. Command's own "watch this one" links, C-c) that
   * overrides the remembered choice for this visit and becomes the new remembered choice too, same
   * as picking one from the switcher. Query params bind to inputs by name exactly like path params
   * do (`withComponentInputBinding()`), so no route-table change is needed for this to work.
   */
  readonly requestedAssetId = input<string | undefined>(undefined, { alias: 'asset' });

  /** `?watch=1` — hides Start/Stop (docs/MVP3-PLAN.md §C-b, C-c's own drill-down target). */
  readonly watch = input<string | undefined>(undefined);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);
  protected readonly events = inject(EventsStore);

  private readonly stageHost = viewChild<ElementRef<HTMLDivElement>>('stage');

  // --- Picker ------------------------------------------------------------------------------
  /** Skeleton card count while the first `listAssets()` call is in flight. */
  protected readonly skeletonRows = [1, 2, 3] as const;
  protected readonly pickerAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  protected readonly pickerError = signal(false);
  protected readonly orderedPickerAssets = computed(() => sortAssetsForPicker(this.pickerAssets() ?? []));

  protected readonly activeAssetId = signal<string | undefined>(undefined);
  protected readonly asset = signal<AssetDetails | undefined>(undefined);
  protected readonly showPicker = computed(() => this.activeAssetId() === undefined);

  // --- Cockpit: video device selection ------------------------------------------------------
  protected readonly videoDevicesList = computed(() => videoDevices(this.asset()?.devices ?? []));
  /** `undefined` = "use the asset's first VIDEO device" — reset on every asset/device switch. */
  private readonly primaryDeviceIdOverride = signal<string | undefined>(undefined);
  protected readonly primaryDevice = computed(() => {
    const devices = this.videoDevicesList();
    const chosen = devices.find((device) => device.id === this.primaryDeviceIdOverride());
    return chosen ?? devices[0];
  });
  protected readonly secondaryDevices = computed(() => {
    const primaryId = this.primaryDevice()?.id;
    return this.videoDevicesList().filter((device) => device.id !== primaryId);
  });

  protected readonly stream = computed(() => {
    const device = this.primaryDevice();
    return device ? this.fleet.streamFor(device.id) : undefined;
  });
  protected readonly live = computed(() => this.stream() !== undefined);

  // --- Deliberately-stopped state (docs/MVP2-PLAN.md §S, S-b) — identical pair/rule to
  // `LivePage`/`AssetDetailPage`; reset whenever the primary device changes since that's
  // effectively a fresh device to watch.
  private readonly explicitlyStopped = signal(false);
  private readonly hasBeenLive = signal(false);
  protected readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  protected readonly watchMode = computed(() => isWatchMode(this.watch()));

  protected readonly telemetryDevicesList = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  protected readonly hasTelemetryDevice = computed(() => this.telemetryDevicesList().length > 0);

  protected readonly latencySeconds = signal<number | null>(null);
  protected readonly transport = signal<Transport>('hls');
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  protected readonly mapVisible = signal(true);
  protected readonly detectionsStripOpen = signal(false);
  protected readonly shortcutsOpen = signal(false);
  protected readonly stopConfirmOpen = signal(false);
  protected readonly busy = signal(false);

  protected readonly latestFinishedUsageEntry = computed(() =>
    latestFinishedUsage(this.asset()?.recentUsages ?? []),
  );

  // --- Events ticker overlay (docs/MVP3-PLAN.md §C-b: "this stream's events via events-store,
  // newest, auto-fading") — filters the shared global feed by this asset's id, same derivation
  // `AssetDetailPage`'s own offline-branch already uses (`filterEvents(events.events(), {assetId})`);
  // "auto-fading" is a pure CSS animation per row (`fly.css`), not a JS timer.
  protected readonly tickerEvents = computed(() => {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return [] as readonly DetectionEvent[];
    }
    return filterEvents(this.events.events(), { assetId }).slice(0, TICKER_MAX_EVENTS);
  });

  constructor() {
    void this.initPicker();

    // Latches once `live()` is ever observed true for the current primary device — see `stopped`'s
    // own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Any device on the asset resolves the same owning-asset/open-usage pair (mirrors
    // `AssetDetailPage`'s identical effect) — start tracking as soon as the asset has *any*
    // TELEMETRY-capable device, regardless of which VIDEO device is currently primary.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      if (this.hasTelemetryDevice() && devices.length > 0) {
        this.telemetry.track(devices[0].id);
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while the primary device's stream is actually running.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        this.detections.track(streamId);
      } else {
        this.detections.reset();
      }
    });

    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — one more of the handful of
    // pages that keeps the shared global events poll alive while mounted.
    this.events.activate();

    const scheduler = inject(PollScheduler);
    const stopPoll = scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refreshPoll());

    const onKeydown = (event: KeyboardEvent): void => this.handleKeydown(event);
    document.addEventListener('keydown', onKeydown);

    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopPoll();
      document.removeEventListener('keydown', onKeydown);
    });
  }

  // --- Picker / asset selection ---------------------------------------------------------------

  private async initPicker(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.pickerAssets.set(assets);
      this.pickerError.set(false);
      const resolved = resolveActiveAssetId(assets, this.requestedAssetId(), this.settings.flyAssetId());
      if (resolved) {
        this.selectAsset(resolved);
      }
    } catch {
      this.pickerError.set(true);
    }
  }

  protected retryPicker(): void {
    void this.initPicker();
  }

  /** Refreshes the picker's asset list (feeds the header switcher too) and the active asset, if any. */
  private async refreshPoll(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.pickerAssets.set(assets);
    } catch {
      // Silent-degrade — background enrichment, not a user-initiated action, matches every other
      // poller in this app.
    }
    const id = this.activeAssetId();
    if (id) {
      await this.loadAsset(id);
    }
  }

  private async loadAsset(assetId: string): Promise<void> {
    try {
      const details = await this.api.getAsset(assetId);
      this.asset.set(details);
    } catch {
      if (this.asset() === undefined) {
        // The very first load for this pick failed — a genuine dead end, not a background hiccup
        // on top of an already-working cockpit (that case silently keeps the stale data instead).
        this.toasts.error('Could not load that drone — it may have been removed.');
        this.activeAssetId.set(undefined);
        this.settings.flyAssetId.set(null);
      }
    }
  }

  /** Picking from the picker grid, the header switcher, or a resolved `?asset=`/remembered id — one path. */
  protected selectAsset(assetId: string): void {
    if (assetId === this.activeAssetId()) {
      return;
    }
    this.settings.flyAssetId.set(assetId);
    this.activeAssetId.set(assetId);
    this.asset.set(undefined);
    this.primaryDeviceIdOverride.set(undefined);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
    void this.loadAsset(assetId);
  }

  /** Returns to the full picker without forgetting the remembered choice (re-picking re-sets it anyway). */
  protected openPicker(): void {
    this.activeAssetId.set(undefined);
  }

  // --- Video device switching -----------------------------------------------------------------

  protected setPrimaryDevice(deviceId: string): void {
    if (deviceId === this.primaryDevice()?.id) {
      return;
    }
    this.primaryDeviceIdOverride.set(deviceId);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
  }

  // --- Player wiring -----------------------------------------------------------------------

  protected onLatency(seconds: number | null): void {
    this.latencySeconds.set(seconds);
  }

  protected onTransport(transport: Transport): void {
    this.transport.set(transport);
  }

  // --- Start / Stop, with a confirm step for Stop -----------------------------------------

  protected async start(): Promise<void> {
    const device = this.primaryDevice();
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

  protected requestStop(): void {
    this.stopConfirmOpen.set(true);
  }

  protected cancelStop(): void {
    this.stopConfirmOpen.set(false);
  }

  protected async confirmStop(): Promise<void> {
    const stream = this.stream();
    if (!stream) {
      this.stopConfirmOpen.set(false);
      return;
    }
    this.busy.set(true);
    try {
      await this.fleet.stop(stream.streamId);
      this.explicitlyStopped.set(true);
    } finally {
      this.busy.set(false);
      this.stopConfirmOpen.set(false);
    }
  }

  // --- Events ticker ---------------------------------------------------------------------------

  protected tickerLabel(event: DetectionEvent): string {
    return `${capitalizeLabel(event.label)} · ${formatConfidence(event.peakConfidence)}`;
  }

  // --- Keyboard shortcuts (docs/MVP3-PLAN.md §C-b) ------------------------------------------
  // Mirrors `LivePage`'s own `M`-only listener exactly (page-scoped `document` `keydown`,
  // ignored while a form field has focus or a modifier is held, added/removed with the route),
  // extended to the cockpit's fuller shortcut set.

  private handleKeydown(event: KeyboardEvent): void {
    if (this.showPicker()) {
      return; // shortcuts are cockpit-only — the picker has no map/boxes/fullscreen to toggle
    }
    if (event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
      return;
    }
    switch (event.key) {
      case 'm':
      case 'M':
        if (this.hasTelemetryDevice()) {
          this.mapVisible.update((visible) => !visible);
        }
        break;
      case 'b':
      case 'B':
        this.boxesMode.update(cycleBoxesMode);
        break;
      case 'f':
      case 'F':
        void this.toggleFullscreen();
        break;
      case 'Escape':
        this.collapseOverlays();
        break;
      case '?':
        this.shortcutsOpen.update((open) => !open);
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  /** Closest-thing-open-first: the shortcuts help, then the stop confirm, then the strip, then the map. */
  protected collapseOverlays(): void {
    if (this.shortcutsOpen()) {
      this.shortcutsOpen.set(false);
      return;
    }
    if (this.stopConfirmOpen()) {
      this.stopConfirmOpen.set(false);
      return;
    }
    if (this.detectionsStripOpen()) {
      this.detectionsStripOpen.set(false);
      return;
    }
    if (this.mapVisible()) {
      this.mapVisible.set(false);
    }
  }

  protected async toggleFullscreen(): Promise<void> {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
        return;
      }
      const host = this.stageHost()?.nativeElement;
      if (host) {
        await host.requestFullscreen();
      }
    } catch {
      // Fullscreen can be refused (permissions-policy, an embedding iframe, an unsupported
      // browser) — never let that break the cockpit; the shortcut simply has no visible effect.
    }
  }
}
