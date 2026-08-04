import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { EventsStore } from '../../core/events/events-store';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { MarksStore } from '../../core/marks/marks-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { videoDevices } from '../../core/fleet/device-logic';
import { ageSeconds, telemetryDevices } from '../../core/telemetry/telemetry-logic';
import { canCommandReturnHome, deriveDiagnostics, derivePreflight, flightBanner } from '../../core/telemetry/flight-state-logic';
import { capitalizeLabel, filterEvents, formatConfidence } from '../../core/events/events-logic';
import { parseWindLimitMps } from '../../core/weather/weather-logic';
import type { BoxesMode, Transport } from '../../shared/player/player';
import { canShowCommandPanel } from './flight-command-panel-logic';
import {
  ALL_DRONES_OPTION_VALUE,
  TICKER_MAX_EVENTS,
  cycleBoxesMode,
  isAllDronesOption,
  isWatchMode,
  latestFinishedUsage,
  sortAssetsForPicker,
  trackingIdChanged,
} from './fly-logic';
import type { AssetDetails, AssetSummary, DetectionEvent, FlightCapability } from '../../core/api/models';

/** Asset characteristics/usages + the header switcher's own asset list are re-read at this cadence. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** Panel-state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — the map inset toggle predates
 * `PanelState`/`UiStore` and stays exactly as it was (docs/UI-REDESIGN-PLAN.md D-D: the map inset is
 * a glanceable, separately-persisted toggle, not a tool-rail drawer, and per docs/UI-ARCHITECTURE-PLAN.md
 * a non-mutually-exclusive toggle like this one lives in the feature facade, not `UiStore`). See
 * `core/panel-state.ts`'s own doc comment for why this isn't routed through `SettingsStore`. */
const MAP_VISIBLE_KEY = 'vision.fly.mapVisible';

/**
 * Console prefix for this page's diagnostic logging (the "Fly shows only the asset name and an
 * empty box" investigation) — this codebase has no logging service/convention (grep-verified), so
 * plain `console.*` with a stable prefix, mirroring `shared/player/player.ts`'s own `[player]`.
 */
const LOG_PREFIX = '[cockpit]';

/**
 * `CockpitPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — `/fly/:assetId`, the operator cockpit
 * (docs/MVP3-PLAN.md §C-b, the "one job, one page" persona: *flies ONE drone at a time; everything
 * else is noise*). Split out of the old combined `FlyFacade` when the cockpit gained its own
 * addressable route (docs/NAV-IA-REDESIGN-PLAN.md §2.5 F12) — `drone-picker-facade.ts` is the
 * picker's own half; **almost everything below is that same class, unchanged**, just no longer
 * sharing a component with the picker. See that file's own doc comment for what stayed picker-side.
 *
 * **What's actually different from the pre-split `FlyFacade`, concretely**:
 *   - `activeAssetId` is now driven by the route's own `:assetId` param (via {@link selectAsset},
 *     called from `CockpitPage`'s constructor `effect()`) instead of the picker's internal
 *     resolve-once-then-flip-a-signal dance — there is no more "no asset selected" state for this
 *     page to represent; every mount of this component already has a concrete id to load.
 *   - **`loadError`** is new — the honest empty state docs/NAV-IA-REDESIGN-PLAN.md F12 requires for
 *     a `:assetId` that doesn't resolve (a bad bookmark, a since-deleted asset): `CockpitPage`'s own
 *     template renders `<vision-empty>` instead of the cockpit while this is `true`, rather than the
 *     old behavior of silently kicking the operator back to the picker (impossible now anyway — the
 *     picker is a different route, and yanking the URL out from under a page that failed to load
 *     would be its own kind of dishonest surprise; a bookmarked dead link should say so, not vanish).
 *   - `settings.flyAssetId` is written by {@link loadAsset} **on success only**, not by
 *     {@link selectAsset} up front — the old page only ever called `selectAsset` with an id already
 *     known-valid (chosen from the fetched picker list), so remembering it immediately was safe; a
 *     route param can be any string a URL bar or bookmark supplies, so remembering it before
 *     confirming it actually resolves would let a dead link poison `fly-redirect-guard.ts`'s own
 *     "remembered" check for every future `/fly` visit. Cleared the same way the old code did
 *     whenever a load fails and the failed id is the one currently remembered.
 *   - `pickerAssets`/`orderedPickerAssets` are renamed **`switcherAssets`/`orderedSwitcherAssets`** —
 *     same `listAssets()`-backed list, same 5s poll, just renamed to say what it is actually for now
 *     that there is no picker on this page: populating the header `DRONE` switcher's own options.
 *   - the switcher's `(change)` handler now **navigates** (`Router`) rather than flip an internal
 *     signal — switching drones, or choosing "All drones…", is a real route change to
 *     `/fly/:assetId` (or back to `/fly`), addressable/bookmarkable/Back-able like every other
 *     pick, not an invisible internal state change the URL never reflected. "All drones…" also
 *     clears `settings.flyAssetId` — see {@link onSwitcherChange}'s own doc comment for why a plain
 *     navigate alone isn't enough to actually reach the picker while the current drone still flies.
 *
 * Every other read-model and command — the device/stream/live trio, `stopped`, `watchMode`, the
 * FC-derived `preflightItems`/`diagnosticsRows`/`canBringHome`/`canShowCommands`, the weather chip's
 * `windLimitMps`, the ticker, Start/Stop, the telemetry/detections re-entry guards — is byte-for-byte
 * what `FlyFacade` already had: same HTTP calls, same toasts, same silent-degrade paths, same poll
 * cadence, same O(N)-amplification guards (docs/REALTIME-PLAN.md Phase R-a item 2), just relocated.
 */
@Injectable()
export class CockpitFacade {
  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);

  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsStore);
  readonly telemetry = inject(TelemetryStore);
  readonly detections = inject(DetectionsStore);
  readonly events = inject(EventsStore);
  readonly geofence = inject(GeofenceStore);
  /**
   * The shared tactical-marks operational picture (docs/TACTICAL-MARKS-PLAN.md M5) — exposed as the
   * whole store (not a thin passthrough), mirroring `geofence` above: `cockpit.html` wires
   * `<vision-live-map>`'s `[marks]`/`[selectedMarkId]`/`(markSelected)`/`(markMoved)`/`(mapClicked)`
   * straight to it, and `<vision-marks-panel>` injects this same `providedIn: 'root'` singleton
   * directly (a non-routed presentational child, per `architecture.spec.ts`'s own carve-out —
   * mirrors `flight-command-panel.ts` injecting `VisionApi` directly).
   */
  readonly marks = inject(MarksStore);
  private readonly weather = inject(WeatherStore);

  // --- Header switcher's own asset list (renamed from the old FlyFacade's `pickerAssets` — see
  // this class's own doc comment) ---------------------------------------------------------------
  readonly switcherAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  readonly orderedSwitcherAssets = computed(() => sortAssetsForPicker(this.switcherAssets() ?? []));

  /** The header switcher's own sentinel `<option>` value (docs/UX-REWORK-PLAN.md §U-a bullet 4). */
  readonly ALL_DRONES_OPTION = ALL_DRONES_OPTION_VALUE;

  readonly activeAssetId = signal<string | undefined>(undefined);
  readonly asset = signal<AssetDetails | undefined>(undefined);
  /** See this class's own doc comment — the honest-empty-state signal `:assetId` degrading needs. */
  readonly loadError = signal(false);

  // --- Telemetry/detections re-entry guards (docs/REALTIME-PLAN.md Phase R-a item 2) ---------
  // The last deviceId/streamId the corresponding constructor effect actually acted on — compared
  // by value (mirrors `core/map/map-store.ts#reconcileTrackers`), not by the enclosing `asset()`/
  // `stream()` object's own identity, which changes every ~5s poll tick regardless.
  private lastTelemetryDeviceId: string | undefined = undefined;
  private lastDetectionsStreamId: string | undefined = undefined;
  /** `${assetId} ${firmware}` — see the capabilities-tracking effect below (constructor). */
  private lastCapabilitiesKey: string | undefined = undefined;

  // --- Video device selection ------------------------------------------------------------------
  readonly videoDevicesList = computed(() => videoDevices(this.asset()?.devices ?? []));
  /** `undefined` = "use the asset's first VIDEO device" — reset on every asset/device switch. */
  private readonly primaryDeviceIdOverride = signal<string | undefined>(undefined);
  readonly primaryDevice = computed(() => {
    const devices = this.videoDevicesList();
    const chosen = devices.find((device) => device.id === this.primaryDeviceIdOverride());
    return chosen ?? devices[0];
  });
  /**
   * Secondary video-device tiles (`cockpit.html`'s `@for (device of secondaryDevices(); track
   * device.id)`). **Verified against docs/REALTIME-PLAN.md Phase R-a item 4**: this computed
   * returns a brand-new array (and, on every ~5s `refreshPoll`, brand-new `Device` objects too)
   * regardless of whether anything actually changed, but `@for`'s own `track device.id` already
   * keeps the same `<vision-player>` component instance alive across that — Angular reuses/moves
   * the DOM node rather than destroying it, as long as the tracked id is stable — and that
   * instance's own reattach guard (`shared/player/player.ts`'s `lastAttachKey`/`player-recovery.ts#attachKey`)
   * only tears down/rebuilds when the fed `src`/`whepUrl` values themselves change, never on mere
   * reorder/resize. Neither `suspended` nor `stopped` is bound on a secondary tile's player (see
   * `cockpit.html`), so its attach key reduces to exactly `(src, whepUrl)` — i.e. tears down only
   * when the underlying stream id actually changes, per that item's exit criterion.
   */
  readonly secondaryDevices = computed(() => {
    const primaryId = this.primaryDevice()?.id;
    return this.videoDevicesList().filter((device) => device.id !== primaryId);
  });

  readonly stream = computed(() => {
    const device = this.primaryDevice();
    return device ? this.fleet.streamFor(device.id) : undefined;
  });
  readonly live = computed(() => this.stream() !== undefined);

  // --- Deliberately-stopped state (docs/MVP2-PLAN.md §S, S-b) — identical pair/rule to
  // `LivePage`/`AssetDetailPage`; reset whenever the primary device changes since that's
  // effectively a fresh device to watch.
  private readonly explicitlyStopped = signal(false);
  private readonly hasBeenLive = signal(false);
  readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  /** Fed by `CockpitPage`'s own `watch` route input — see this class's own doc comment above. */
  private readonly watchSignal = signal<string | undefined>(undefined);
  readonly watchMode = computed(() => isWatchMode(this.watchSignal()));

  readonly telemetryDevicesList = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  readonly hasTelemetryDevice = computed(() => this.telemetryDevicesList().length > 0);

  // --- Flight-controller state: failsafe banner + pre-flight checklist (docs/FC-INTEGRATIONS-PLAN.md
  // F-d) — both pure derivations over the same `TelemetryStore.latest()` sample every other OSD chip
  // already reads, no second telemetry source.
  readonly failsafeBanner = computed(() => flightBanner(this.telemetry.latest()));

  /** Re-derives whenever the tracked sample/primary-device/live state changes — a ground-check
   * glance, not a live-ticking instrument (the OSD's own age chip is that); see
   * `flight-state-logic.ts#derivePreflight`'s own doc comment for why `Date.now()` is read here,
   * at the call site, rather than inside that pure function. */
  readonly preflightItems = computed(() =>
    derivePreflight(this.telemetry.latest(), this.primaryDevice() !== undefined, this.live(), Date.now()),
  );

  /** Pre-arm ground check — hidden once watch-mode drops the controls entirely, or once the FC
   * itself confirms armed (the OSD chip bar is the live instrument from that point on). */
  readonly showPreflightChecklist = computed(() => {
    if (this.watchMode()) {
      return false;
    }
    const sample = this.telemetry.latest();
    return sample === undefined || sample.flightState?.armed !== true;
  });

  /** docs/FC-INTEGRATIONS-PLAN.md F-e — same `TelemetryStore.latest()` sample every OSD chip
   * already reads; `deriveDiagnostics` itself omits every row whose keys aren't in `extra`. */
  readonly diagnosticsRows = computed(() => deriveDiagnostics(this.telemetry.latest()?.extra));

  /**
   * docs/DRONE-INFRA-PLAN.md I-e Stage 1 — gates `<vision-return-home-button>` (`cockpit.html`'s
   * `.hud-header`). Same "re-derive whenever the tracked sample changes, not a continuously-ticking
   * clock" convention as `preflightItems` above: `telemetry.latest()` itself already re-emits
   * roughly every poll/live-update tick while the vehicle is transmitting, so this tracks freshness
   * closely enough without a dedicated 1s timer.
   */
  readonly canBringHome = computed(() => {
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
  readonly capabilities = signal<FlightCapability | undefined>(undefined);

  /** Gates `<vision-flight-command-panel>` (`cockpit.html`'s `.hud-header`) — `capabilities` itself
   * must have loaded *and* say `commandable`, on top of the identical firmware+freshness bar
   * `canBringHome` already clears (`flight-command-panel-logic.ts#canShowCommandPanel`). */
  readonly canShowCommands = computed(() => {
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
  readonly windLimitMps = computed(() => parseWindLimitMps(this.asset()?.attributes));

  /**
   * The selected mark's "from drone" bearing/distance readout (docs/TACTICAL-MARKS-PLAN.md §3)
   * reuses `weatherPosition` verbatim rather than re-deriving "best position we have right now" a
   * second time — same live-fix-else-last-known fallback, same honest degrade to `undefined` (the
   * readout shows "—") when neither exists. No "from home" counterpart: surveyed, no home/launch
   * position is modeled anywhere in this app's telemetry/asset data (docs/TACTICAL-MARKS-PLAN.md
   * Open Q3's own documented default — "show from-drone always; from-home only when a home position
   * exists" — so `<vision-marks-panel>` renders drone-only and says so, never a fabricated distance).
   */
  readonly dronePosition = this.weatherPosition;

  readonly latencySeconds = signal<number | null>(null);
  readonly transport = signal<Transport>('hls');
  /** Defaults to `'burned'`, not `'overlay'` (per direct user request — `shared/player/player.ts`'s
   * own `boxesMode` input default matches for the same reason). */
  readonly boxesMode = signal<BoxesMode>('burned');

  /** Persisted, non-mutually-exclusive toggle (docs/UI-ARCHITECTURE-PLAN.md) — see this class's own
   * doc comment above `MAP_VISIBLE_KEY`. */
  readonly mapVisible = signal(readPersistedFlag(MAP_VISIBLE_KEY, true));

  readonly busy = signal(false);

  readonly latestFinishedUsageEntry = computed(() => latestFinishedUsage(this.asset()?.recentUsages ?? []));

  // --- Events ticker overlay (docs/MVP3-PLAN.md §C-b: "this stream's events via events-store,
  // newest, auto-fading") — filters the shared global feed by this asset's id, same derivation
  // `AssetDetailPage`'s own offline-branch already uses (`filterEvents(events.events(), {assetId})`);
  // "auto-fading" is a pure CSS animation per row (`cockpit.css`), not a JS timer.
  readonly tickerEvents = computed(() => {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return [] as readonly DetectionEvent[];
    }
    return filterEvents(this.events.events(), { assetId }).slice(0, TICKER_MAX_EVENTS);
  });

  constructor() {
    // Panel state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — persists whenever the map toggle
    // actually changes (the `M` shortcut, or `CockpitPage#collapseOverlays`'s `Esc` handling); the
    // initial `signal()` value above already restored whatever was last saved.
    effect(() => writePersistedFlag(MAP_VISIBLE_KEY, this.mapVisible()));

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

    // `PollScheduler.schedule`'s own contract is "starting one `periodMs` from now" — it never
    // fires immediately itself, by design (every consumer is expected to do its own first fetch,
    // see that method's own doc comment). The pre-split `FlyFacade` got this for free: `FlyPage`'s
    // constructor called `initPicker()` immediately, which populated the one shared `pickerAssets`
    // signal both the picker grid *and* this switcher read from. Now that `switcherAssets` has no
    // picker-side reader forcing an immediate fetch, skipping this call would leave the header
    // switcher showing only its "All drones…" sentinel for up to `ASSET_POLL_INTERVAL_MS` (5s) on
    // every fresh cockpit mount — confirmed live (not just reasoned about) before this line was
    // added. `activeAssetId()` is still `undefined` at this point in construction (the component's
    // own `effect(() => this.facade.selectAsset(this.assetId()))` hasn't run its first turn yet —
    // Angular effects schedule, they don't run inline at declaration), so `refreshPoll`'s own
    // `loadAsset` half correctly no-ops here; only the switcher's list gets the early fetch.
    void this.refreshPoll();

    const scheduler = inject(PollScheduler);
    const stopPoll = scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refreshPoll());

    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopPoll();
    });
  }

  // --- Asset selection (route-driven — see this class's own doc comment above) -----------------

  /** Called from `CockpitPage`'s own constructor `effect()` whenever the route's `:assetId` changes
   * (including the very first activation). */
  selectAsset(assetId: string): void {
    if (assetId === this.activeAssetId()) {
      return;
    }
    console.info(`${LOG_PREFIX} selecting asset ${assetId}`);
    this.activeAssetId.set(assetId);
    this.asset.set(undefined);
    this.loadError.set(false);
    this.primaryDeviceIdOverride.set(undefined);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
    this.capabilities.set(undefined);
    void this.loadAsset(assetId);
  }

  private async loadAsset(assetId: string): Promise<void> {
    try {
      const details = await this.api.getAsset(assetId);
      // A switcher pick / picker-card click / `?asset=` drill-down already only ever names a real
      // id, but a bare `:assetId` route param can be anything a URL bar or bookmark supplies —
      // remembering it as "last flown" only once it has actually resolved is what keeps a dead
      // bookmark from poisoning `fly-redirect-guard.ts`'s own "remembered" check for every future
      // `/fly` visit (see this class's own doc comment).
      this.settings.flyAssetId.set(assetId);
      this.asset.set(details);
    } catch (error) {
      if (this.asset() === undefined) {
        // The very first load for this pick failed — a genuine dead end (docs/NAV-IA-REDESIGN-PLAN.md
        // F12's own "must degrade to an honest empty state" requirement), not a background hiccup on
        // top of an already-working cockpit (that case silently keeps the stale data instead, below).
        console.warn(`${LOG_PREFIX} could not load asset ${assetId}`, { error });
        this.loadError.set(true);
        if (this.settings.flyAssetId() === assetId) {
          this.settings.flyAssetId.set(null);
        }
      }
    }
  }

  /** Refreshes the header switcher's own asset list and the active asset. */
  private async refreshPoll(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.switcherAssets.set(assets);
    } catch {
      // Silent-degrade — background enrichment, not a user-initiated action, matches every other
      // poller in this app.
    }
    const id = this.activeAssetId();
    if (id) {
      await this.loadAsset(id);
    }
  }

  /**
   * `cockpit.html`'s header switcher binds this per-`<option>` (`[selected]`) rather than `[value]`
   * on the `<select>` itself — see `fly-logic.ts#isSwitcherOptionSelected`'s doc comment for the
   * `<select>`/`@for` ordering race this sidesteps (docs/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2).
   */
  switcherOptionSelected(candidateAssetId: string): boolean {
    return candidateAssetId === this.activeAssetId();
  }

  /**
   * The header switcher's single `(change)` handler (docs/UX-REWORK-PLAN.md §U-a bullet 4: fold
   * the old standalone "All drones" button into this one control) — the sentinel option navigates
   * to the picker (`/fly`), any other value is a real asset id and navigates straight to its own
   * cockpit route. A real route change either way (docs/NAV-IA-REDESIGN-PLAN.md F12) — unlike the
   * pre-split page's internal signal flip, both picks are now addressable/Back-able on their own.
   *
   * **"All drones…" also forgets the remembered drone** — not just navigate. Without this,
   * `fly-redirect-guard.ts` would immediately bounce a plain `/fly` right back into *this exact*
   * cockpit whenever the current drone is still streaming (its own "skip the picker when it has
   * nothing to ask" job, working as designed) — making the picker practically unreachable from
   * here for as long as the drone keeps flying, breaking docs/design/01-fly.md's own explicit
   * promise that "the cockpit needs a way back to it". Choosing "All drones…" is itself an explicit
   * signal that this visit is *not* "nothing to ask" — clearing `flyAssetId` is what makes the
   * guard agree. Picking a drone again (from the picker, or this same switcher) re-establishes the
   * memory exactly as before; nothing else about "remembered" changes.
   */
  onSwitcherChange(value: string): void {
    if (isAllDronesOption(value)) {
      this.settings.flyAssetId.set(null);
      void this.router.navigate(['/fly']);
      return;
    }
    void this.router.navigate(['/fly', value]);
  }

  // --- Video device switching -----------------------------------------------------------------

  setPrimaryDevice(deviceId: string): void {
    if (deviceId === this.primaryDevice()?.id) {
      return;
    }
    this.primaryDeviceIdOverride.set(deviceId);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
  }

  // --- Player wiring -----------------------------------------------------------------------

  onLatency(seconds: number | null): void {
    this.latencySeconds.set(seconds);
  }

  onTransport(transport: Transport): void {
    console.info(`${LOG_PREFIX} player transport changed to ${transport}`);
    this.transport.set(transport);
  }

  // --- Start / Stop ------------------------------------------------------------------------
  // No confirm step here — `CockpitPage`'s own host-owned `UiStore` gates `stop()` behind the
  // Stop-stream confirm dialog; this facade only ever executes the actual command.

  async start(): Promise<void> {
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

  async stop(): Promise<void> {
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

  // --- Events ticker ---------------------------------------------------------------------------

  tickerLabel(event: DetectionEvent): string {
    return `${capitalizeLabel(event.label)} · ${formatConfidence(event.peakConfidence)}`;
  }

  // --- Map inset / boxes-mode toggles (docs/UI-ARCHITECTURE-PLAN.md — persisted, non-exclusive,
  // so plain facade commands rather than `UiStore`) -------------------------------------------

  toggleMapVisible(): void {
    this.mapVisible.update((visible) => !visible);
  }

  hideMap(): void {
    this.mapVisible.set(false);
  }

  cycleBoxes(): void {
    this.boxesMode.update(cycleBoxesMode);
  }

  /** Fed from `CockpitPage`'s own route-bound `watch` input — see this class's own doc comment. */
  setWatch(watch: string | undefined): void {
    this.watchSignal.set(watch);
  }

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
}
