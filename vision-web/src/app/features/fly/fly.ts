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
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { EventsStore } from '../../core/events/events-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { videoDevices } from '../../core/fleet/device-logic';
import { ageSeconds, telemetryDevices } from '../../core/telemetry/telemetry-logic';
import { canCommandReturnHome, deriveDiagnostics, derivePreflight, flightBanner } from '../../core/telemetry/flight-state-logic';
import { capitalizeLabel, filterEvents, formatConfidence } from '../../core/events/events-logic';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { parseWindLimitMps } from '../../core/weather/weather-logic';
import { Player, type BoxesMode, type Transport } from '../../shared/player/player';
import { LiveMap } from '../../shared/map/live-map/live-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { FlyOsd } from './fly-osd';
import { FailsafeBanner } from './failsafe-banner';
import { PreflightChecklist } from './preflight-checklist';
import { DiagnosticsCard } from './diagnostics-card';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { FlightCommandPanel } from './flight-command-panel';
import { canShowCommandPanel } from './flight-command-panel-logic';
import {
  ALL_DRONES_OPTION_VALUE,
  TICKER_MAX_EVENTS,
  cycleBoxesMode,
  isAllDronesOption,
  isSwitcherOptionSelected,
  isWatchMode,
  lastSeenLabel,
  latestFinishedUsage,
  positionLabel,
  resolveActiveAssetId,
  sortAssetsForPicker,
  streamStateLabel,
  trackingIdChanged,
} from './fly-logic';
import type { AssetDetails, AssetSummary, DetectionEvent, FlightCapability } from '../../core/api/models';

/** Asset characteristics/usages + the picker's own asset list are re-read at this cadence. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** Panel-state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — the two toggles below already
 * existed; only the localStorage key names are new. See `core/panel-state.ts`'s own doc comment
 * for why this isn't routed through `SettingsStore`. */
const MAP_VISIBLE_KEY = 'vision.fly.mapVisible';
const DETECTIONS_STRIP_OPEN_KEY = 'vision.fly.detectionsStripOpen';

/**
 * Console prefix for this page's diagnostic logging (the "Fly shows only the asset name and an
 * empty box" investigation) — this codebase has no logging service/convention (grep-verified), so
 * plain `console.*` with a stable prefix, mirroring `shared/player/player.ts`'s own `[player]`.
 */
const LOG_PREFIX = '[fly]';

/**
 * `/fly` — the operator cockpit and the app's default landing page (docs/MVP3-PLAN.md §C-b, the
 * "one job, one page" persona: *flies ONE drone at a time; everything else is noise*).
 *
 * First visit shows an asset picker (streaming assets first); the choice is remembered
 * (`SettingsStore.flyAssetId`), so every later visit — and every deep link carrying `?asset=` —
 * lands straight in the cockpit. The cockpit itself is thin composition over pieces this app
 * already had: `<vision-player>` (WHEP-first, self-recovering, the `stopped` terminal state),
 * `<vision-live-map>`, `<vision-detections-strip>` (moved to `shared/player/` for this reuse — see its own doc
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
  imports: [
    RouterLink,
    Player,
    LiveMap,
    DetectionsStrip,
    FlyOsd,
    FailsafeBanner,
    PreflightChecklist,
    DiagnosticsCard,
    ReturnHomeButton,
    FlightCommandPanel,
  ],
  templateUrl: './fly.html',
  styleUrl: './fly.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `LivePage`/`AssetDetailPage`.
  // `WeatherStore` (docs/OPS-CORE-PLAN.md §W) is page-provided too — see that class's own doc
  // comment for why it can't be a shared root singleton.
  providers: [TelemetryStore, DetectionsStore, WeatherStore],
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
  protected readonly geofence = inject(GeofenceStore);
  private readonly weather = inject(WeatherStore);

  private readonly stageHost = viewChild<ElementRef<HTMLDivElement>>('stage');

  // --- Picker ------------------------------------------------------------------------------
  /** Skeleton card count while the first `listAssets()` call is in flight. */
  protected readonly skeletonRows = [1, 2, 3] as const;
  protected readonly pickerAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  protected readonly pickerError = signal(false);
  protected readonly orderedPickerAssets = computed(() => sortAssetsForPicker(this.pickerAssets() ?? []));

  /** The header switcher's own sentinel `<option>` value (docs/UX-REWORK-PLAN.md §U-a bullet 4). */
  protected readonly ALL_DRONES_OPTION = ALL_DRONES_OPTION_VALUE;

  protected readonly activeAssetId = signal<string | undefined>(undefined);
  protected readonly asset = signal<AssetDetails | undefined>(undefined);
  protected readonly showPicker = computed(() => this.activeAssetId() === undefined);

  // --- Telemetry/detections re-entry guards (docs/REALTIME-PLAN.md Phase R-a item 2) ---------
  // The last deviceId/streamId the corresponding constructor effect actually acted on — compared
  // by value (mirrors `core/map/map-store.ts#reconcileTrackers`), not by the enclosing `asset()`/
  // `stream()` object's own identity, which changes every ~5s poll tick regardless.
  private lastTelemetryDeviceId: string | undefined = undefined;
  private lastDetectionsStreamId: string | undefined = undefined;
  /** `${assetId} ${firmware}` — see the capabilities-tracking effect below (constructor). */
  private lastCapabilitiesKey: string | undefined = undefined;

  // --- Cockpit: video device selection ------------------------------------------------------
  protected readonly videoDevicesList = computed(() => videoDevices(this.asset()?.devices ?? []));
  /** `undefined` = "use the asset's first VIDEO device" — reset on every asset/device switch. */
  private readonly primaryDeviceIdOverride = signal<string | undefined>(undefined);
  protected readonly primaryDevice = computed(() => {
    const devices = this.videoDevicesList();
    const chosen = devices.find((device) => device.id === this.primaryDeviceIdOverride());
    return chosen ?? devices[0];
  });
  /**
   * Secondary video-device tiles (`fly.html`'s `@for (device of secondaryDevices(); track
   * device.id)`). **Verified against docs/REALTIME-PLAN.md Phase R-a item 4**: this computed
   * returns a brand-new array (and, on every ~5s `refreshPoll`, brand-new `Device` objects too)
   * regardless of whether anything actually changed, but `@for`'s own `track device.id` already
   * keeps the same `<vision-player>` component instance alive across that — Angular reuses/moves
   * the DOM node rather than destroying it, as long as the tracked id is stable — and that
   * instance's own reattach guard (`shared/player/player.ts`'s `lastAttachKey`/`player-recovery.ts#attachKey`)
   * only tears down/rebuilds when the fed `src`/`whepUrl` values themselves change, never on mere
   * reorder/resize. Neither `suspended` nor `stopped` is bound on a secondary tile's player (see
   * `fly.html`), so its attach key reduces to exactly `(src, whepUrl)` — i.e. tears down only when
   * the underlying stream id actually changes, per that item's exit criterion.
   */
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

  // --- Flight-controller state: failsafe banner + pre-flight checklist (docs/FC-INTEGRATIONS-PLAN.md
  // F-d) — both pure derivations over the same `TelemetryStore.latest()` sample every other OSD chip
  // already reads, no second telemetry source.
  protected readonly failsafeBanner = computed(() => flightBanner(this.telemetry.latest()));

  /** Re-derives whenever the tracked sample/primary-device/live state changes — a ground-check
   * glance, not a live-ticking instrument (the OSD's own age chip is that); see
   * `flight-state-logic.ts#derivePreflight`'s own doc comment for why `Date.now()` is read here,
   * at the call site, rather than inside that pure function. */
  protected readonly preflightItems = computed(() =>
    derivePreflight(this.telemetry.latest(), this.primaryDevice() !== undefined, this.live(), Date.now()),
  );

  /** Pre-arm ground check — hidden once watch-mode drops the controls entirely, or once the FC
   * itself confirms armed (the OSD chip bar is the live instrument from that point on). */
  protected readonly showPreflightChecklist = computed(() => {
    if (this.watchMode()) {
      return false;
    }
    const sample = this.telemetry.latest();
    return sample === undefined || sample.flightState?.armed !== true;
  });

  /** docs/FC-INTEGRATIONS-PLAN.md F-e — same `TelemetryStore.latest()` sample every OSD chip
   * already reads; `deriveDiagnostics` itself omits every row whose keys aren't in `extra`. */
  protected readonly diagnosticsRows = computed(() => deriveDiagnostics(this.telemetry.latest()?.extra));

  /**
   * docs/DRONE-INFRA-PLAN.md I-e Stage 1 — gates `<vision-return-home-button>` (below,
   * `fly.html`'s `.hud-header`). Same "re-derive whenever the tracked sample changes, not a
   * continuously-ticking clock" convention as `preflightItems` above: `telemetry.latest()` itself
   * already re-emits roughly every poll/live-update tick while the vehicle is transmitting, so this
   * tracks freshness closely enough without a dedicated 1s timer.
   */
  protected readonly canBringHome = computed(() => {
    const sample = this.telemetry.latest();
    return canCommandReturnHome(sample?.flightState?.firmware, ageSeconds(sample?.at, Date.now()));
  });

  /**
   * docs/DRONE-INFRA-PLAN.md I-e Stage 2 — the vehicle's own capability matrix, fetched once per
   * asset selection and re-fetched the first time this vehicle's firmware becomes known (see the
   * capabilities-tracking effect below for why). `undefined` while in flight or on any failure —
   * `<vision-flight-command-panel>` renders nothing at all in that case, the plan's own "degrade to
   * hidden if the capabilities call fails" rule.
   */
  protected readonly capabilities = signal<FlightCapability | undefined>(undefined);

  /** Gates `<vision-flight-command-panel>` (below, `fly.html`'s `.hud-header`) — `capabilities`
   * itself must have loaded *and* say `commandable`, on top of the identical firmware+freshness bar
   * `canBringHome` already clears (`flight-command-panel-logic.ts#canShowCommandPanel`). */
  protected readonly canShowCommands = computed(() => {
    const sample = this.telemetry.latest();
    return canShowCommandPanel(this.capabilities(), sample?.flightState?.firmware, ageSeconds(sample?.at, Date.now()));
  });

  // --- Weather go/no-go chip (docs/OPS-CORE-PLAN.md §W) --------------------------------------
  /** The live telemetry fix when one exists, else the asset's own last-known position — "best position we have right now". */
  private readonly weatherPosition = computed(() => {
    const latest = this.telemetry.latest();
    if (latest?.latitude !== undefined && latest.longitude !== undefined) {
      return { latitude: latest.latitude, longitude: latest.longitude };
    }
    return this.asset()?.lastKnownPosition;
  });
  /** `AssetDetails.attributes['windLimitMps']` when present, else the plan's own 10 m/s default. */
  protected readonly windLimitMps = computed(() => parseWindLimitMps(this.asset()?.attributes));

  protected readonly latencySeconds = signal<number | null>(null);
  protected readonly transport = signal<Transport>('hls');
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  protected readonly mapVisible = signal(readPersistedFlag(MAP_VISIBLE_KEY, true));
  protected readonly detectionsStripOpen = signal(readPersistedFlag(DETECTIONS_STRIP_OPEN_KEY, false));
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

    // Panel state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — persists whenever either toggle
    // actually changes (the `M` shortcut, the detections-strip button, or `collapseOverlays`'s
    // `Esc` handling all just flip the same signals); the initial `signal()` value above already
    // restored whatever was last saved.
    effect(() => writePersistedFlag(MAP_VISIBLE_KEY, this.mapVisible()));
    effect(() => writePersistedFlag(DETECTIONS_STRIP_OPEN_KEY, this.detectionsStripOpen()));

    // Keeps the weather chip fresh as the flown asset's own position changes — `WeatherStore.track`
    // itself no-ops instantly unless the 10-minute cache is actually stale (docs/OPS-CORE-PLAN.md §W).
    effect(() => this.weather.track(this.weatherPosition()));

    // Latches once `live()` is ever observed true for the current primary device — see `stopped`'s
    // own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Logs exactly what `<vision-player>` is being fed (docs/MVP3-PLAN.md follow-up: makes an
    // "empty box, no error" report diagnosable from the console alone) — every time the primary
    // device's stream entry changes, not just once, since the whole point is to catch a stream
    // that flips between present/absent as `FleetStore`'s own poll lands.
    effect(() => {
      const device = this.primaryDevice();
      const stream = this.stream();
      if (!device) {
        return;
      }
      if (!stream) {
        console.info(`${LOG_PREFIX} primary device ${device.id} has no active stream yet`);
        return;
      }
      console.info(`${LOG_PREFIX} primary device ${device.id} stream`, {
        streamId: stream.streamId,
        viewUrl: stream.viewUrl,
        whepUrl: stream.whepUrl,
      });
    });

    // Any device on the asset resolves the same owning-asset/open-usage pair (mirrors
    // `AssetDetailPage`'s identical effect) — start tracking as soon as the asset has *any*
    // TELEMETRY-capable device, regardless of which VIDEO device is currently primary.
    //
    // **Guarded on the derived deviceId primitive** (docs/REALTIME-PLAN.md Phase R-a item 2):
    // `this.asset()` is a fresh `AssetDetails` object every ~5s poll tick (`refreshPoll`) even when
    // nothing about the tracked device actually changed, so this effect re-runs on that cadence
    // regardless. Without this guard, re-entering `telemetry.track()` with the *same* deviceId every
    // ~5s re-ran `findOpenUsageId` from scratch each time — the diagnosed O(N) burst (`GET
    // /api/assets` + `GET /api/assets/{id}` per fleet asset, docs/REALTIME-PLAN.md §0). Mirrors
    // `core/map/map-store.ts#reconcileTrackers`' own reconcile-by-id idiom: compare the id *value*, not
    // object identity, and no-op the store call when it hasn't changed.
    effect(() => {
      const devices = this.asset()?.devices ?? [];
      const deviceId = this.hasTelemetryDevice() && devices.length > 0 ? devices[0].id : undefined;
      if (!trackingIdChanged(deviceId, this.lastTelemetryDeviceId)) {
        return;
      }
      this.lastTelemetryDeviceId = deviceId;
      if (deviceId) {
        // Already has the owning asset id (docs/REALTIME-PLAN.md Phase R-a item 3) — skips
        // `TelemetryStore`'s own O(N) fleet-listing fallback.
        this.telemetry.track(deviceId, this.activeAssetId());
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while the primary device's stream is actually running.
    // Guarded on the derived streamId primitive for the identical reason as telemetry above — a
    // stream object re-arriving unchanged every ~5s poll tick must not re-enter `track()` (which
    // clears results immediately, a visible flicker, docs/REALTIME-PLAN.md §0).
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (!trackingIdChanged(streamId, this.lastDetectionsStreamId)) {
        return;
      }
      this.lastDetectionsStreamId = streamId;
      if (streamId) {
        // Already has the owning asset id (docs/REALTIME-PLAN.md §4, Phase R-c) — lets
        // `DetectionsStore` subscribe to live `detections:<assetId>` instead of only polling.
        this.detections.track(streamId, this.activeAssetId());
      } else {
        this.detections.reset();
      }
    });

    // docs/DRONE-INFRA-PLAN.md I-e Stage 2 — flight-command panel capabilities. Fetched once per
    // asset selection, and again the first time this vehicle's own firmware becomes known (an
    // unheard vehicle reports `commandable=false` until its first heartbeat arrives, per the plan's
    // own capability matrix — a fresh fetch once firmware resolves is what flips a just-connected
    // vehicle's panel from hidden to shown without needing a manual refresh).
    //
    // **Guarded on a composite `(assetId, firmware)` key** (mirrors `trackSessionKey`,
    // `core/live/live-fallback-logic.ts`), not `assetId` alone: `telemetry.latest()` is a fresh
    // object most poll ticks (signals compare with `Object.is`), so without the firmware half of the
    // key this effect would re-fetch every ~poll tick with an unchanged firmware value — the exact
    // O(N)-re-entry class of bug `trackingIdChanged`'s other call sites in this file already guard
    // against (docs/REALTIME-PLAN.md Phase R-a item 2).
    effect(() => {
      const assetId = this.activeAssetId();
      const firmware = this.telemetry.latest()?.flightState?.firmware;
      const key = assetId ? `${assetId} ${firmware ?? ''}` : undefined;
      if (!trackingIdChanged(key, this.lastCapabilitiesKey)) {
        return;
      }
      this.lastCapabilitiesKey = key;
      if (assetId) {
        void this.loadCapabilities(assetId);
      } else {
        this.capabilities.set(undefined);
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
      console.info(`${LOG_PREFIX} picker loaded ${assets.length} asset(s)`, {
        requestedAssetId: this.requestedAssetId(),
        rememberedAssetId: this.settings.flyAssetId(),
        resolved,
      });
      if (resolved) {
        this.selectAsset(resolved);
      }
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not load the asset picker`, { error });
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
    } catch (error) {
      if (this.asset() === undefined) {
        // The very first load for this pick failed — a genuine dead end, not a background hiccup
        // on top of an already-working cockpit (that case silently keeps the stale data instead).
        console.warn(`${LOG_PREFIX} could not load asset ${assetId} — returning to the picker`, { error });
        this.toasts.error('Could not load that drone — it may have been removed.');
        this.activeAssetId.set(undefined);
        this.settings.flyAssetId.set(null);
      }
    }
  }

  /**
   * docs/DRONE-INFRA-PLAN.md I-e Stage 2 — background capability read, not a user-initiated action:
   * silent-degrade on any failure (404 unknown asset, 403 out of scope, network) straight to
   * `undefined`, no toast — the flight-command panel just stays hidden, mirroring
   * `TelemetryStore`/`DetectionsStore`'s own "best-effort context" silent-failure convention rather
   * than `loadAsset`'s own user-facing error toast (that one guards the entire cockpit's own load).
   */
  private async loadCapabilities(assetId: string): Promise<void> {
    try {
      const caps = await this.api.flightCapabilities(assetId);
      this.capabilities.set(caps);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not load flight capabilities for ${assetId} — command panel stays hidden`, {
        error,
      });
      this.capabilities.set(undefined);
    }
  }

  /** Picking from the picker grid, the header switcher, or a resolved `?asset=`/remembered id — one path. */
  protected selectAsset(assetId: string): void {
    if (assetId === this.activeAssetId()) {
      return;
    }
    console.info(`${LOG_PREFIX} selecting asset ${assetId}`);
    this.settings.flyAssetId.set(assetId);
    this.activeAssetId.set(assetId);
    this.asset.set(undefined);
    this.primaryDeviceIdOverride.set(undefined);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
    this.capabilities.set(undefined);
    void this.loadAsset(assetId);
  }

  /** Returns to the full picker without forgetting the remembered choice (re-picking re-sets it anyway). */
  protected openPicker(): void {
    this.activeAssetId.set(undefined);
  }

  /**
   * `fly.html`'s header switcher binds this per-`<option>` (`[selected]`) rather than `[value]` on
   * the `<select>` itself — see `fly-logic.ts#isSwitcherOptionSelected`'s doc comment for the
   * `<select>`/`@for` ordering race this sidesteps (docs/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2).
   */
  protected switcherOptionSelected(candidateAssetId: string): boolean {
    return isSwitcherOptionSelected(candidateAssetId, this.activeAssetId());
  }

  /**
   * The header switcher's single `(change)` handler (docs/UX-REWORK-PLAN.md §U-a bullet 4: fold
   * the old standalone "All drones" button into this one control) — the sentinel option opens the
   * full picker, any other value is a real asset id and switches straight to it, same as before.
   */
  protected onSwitcherChange(value: string): void {
    if (isAllDronesOption(value)) {
      this.openPicker();
      return;
    }
    this.selectAsset(value);
  }

  // --- Picker card facts (docs/UX-REWORK-PLAN.md §U-a2 §3 — the asset card rebuild) -----------

  protected assetStreamState(asset: AssetSummary): 'Streaming' | 'Offline' {
    return streamStateLabel(asset.status);
  }

  protected assetLastSeen(asset: AssetSummary): string | undefined {
    return lastSeenLabel(asset.lastUsedAt, Date.now());
  }

  protected assetPosition(asset: AssetSummary): string | undefined {
    return positionLabel(asset.lastKnownPosition);
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
    console.info(`${LOG_PREFIX} player transport changed to ${transport}`);
    this.transport.set(transport);
  }

  // --- Start / Stop, with a confirm step for Stop -----------------------------------------

  protected async start(): Promise<void> {
    const device = this.primaryDevice();
    if (!device) {
      return;
    }
    console.info(`${LOG_PREFIX} starting stream for device ${device.id}`);
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
