import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetFacade } from '../../core/fleet/fleet-facade';
import { SettingsFacade } from '../../core/settings/settings-facade';
import { PollScheduler } from '../../core/poll-scheduler';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { SystemStatusFacade } from '../../core/system-status/system-status-facade';
import { SeatFacade } from '../../core/seat/seat-facade';
import { EventsFacade } from '../../core/events/events-facade';
import { GeofenceFacade } from '../../core/geofence/geofence-facade';
import { GeoFacade } from '../../core/geo/geo-facade';
import { hasFix } from '../../core/geo/geo-logic';
import { GroundingStore } from './grounding-store';
import { LiveFacade } from '../../core/live/live-facade';
import { isLiveAvailable } from '../../core/live/live-fallback-logic';
import { MarksFacade } from '../../core/map-data/marks-facade';
import { LayersFacade } from '../../core/map-data/layers-facade';
import { DrawingsFacade } from '../../core/map-data/drawings-facade';
import { resolveInteractionMode } from '../../core/map-data/drawings-logic';
import { WeatherFacade } from '../../core/weather/weather-facade';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { videoDevices } from '../../core/fleet/device-logic';
import { ageSeconds, humanAge, selectOpenUsage, telemetryDevices } from '../../core/telemetry/telemetry-logic';
import { canCommandReturnHome, deriveDiagnostics, derivePreflight, flightBanner, preflightSummary } from '../../core/telemetry/flight-state-logic';
import { capitalizeLabel, filterEvents, formatConfidence } from '../../core/events/events-logic';
import { parseWindLimitMps } from '../../core/weather/weather-logic';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canManageOrg } from '../../core/org/org-logic';
import type { Transport } from '../../shared/player/player';
import { cycleBoxesMode } from '../../shared/player/detection-overlay-logic';
import { followMarkers, type DrawingDraft } from '../../shared/map/tactical-map/tactical-map-logic';
import { canShowCommandPanel } from './flight-command-panel-logic';
import { buildFollowLockPatch, buildHotKnobPatch, buildPointLockPatch, buildReleaseLockPatch, resolveCvConfig, type ResolvedCvConfig } from './cv-control-panel-logic';
import { resolveDetectionEnabled, videoNotice } from './stream-state-logic';
import {
  ALL_DRONES_OPTION_VALUE,
  TICKER_MAX_EVENTS,
  cameraHeldByOther,
  cameraHolderLabel,
  commandSurfaceVisible,
  crewCameraDockLine,
  dockPreflightSummaryLabel,
  earlierReplayableUsages,
  flyHeroStatus,
  flyStage,
  isAllDronesOption,
  isAutostart,
  isWatchMode,
  latestFinishedUsage,
  positionLabel,
  sortAssetsForPicker,
  trackingIdChanged,
} from './fly-logic';
import type {
  AssetDetails,
  AssetSummary,
  BoundingBox,
  CvProfileIntent,
  CvProfileSources,
  DetectionEvent,
  EffectiveCvProfile,
  FlightCapability,
  FollowStatus,
  StreamConfigResponse,
} from '../../core/api/models';

/** Asset characteristics/usages + the header switcher's own asset list are re-read at this cadence. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** Panel-state memory (docs/plans/done/UX-REWORK-PLAN.md §U-b item 7) — the map inset toggle predates
 * `PanelState`/`UiStore` and stays exactly as it was (docs/plans/done/UI-REDESIGN-PLAN.md D-D: the map inset is
 * a glanceable, separately-persisted toggle, not a tool-rail drawer, and per docs/plans/done/UI-ARCHITECTURE-PLAN.md
 * a non-mutually-exclusive toggle like this one lives in the feature facade, not `UiStore`). See
 * `core/panel-state.ts`'s own doc comment for why this isn't routed through `SettingsFacade`. */
const MAP_VISIBLE_KEY = 'vision.fly.mapVisible';

/**
 * Console prefix for this page's diagnostic logging (the "Fly shows only the asset name and an
 * empty box" investigation) — this codebase has no logging service/convention (grep-verified), so
 * plain `console.*` with a stable prefix, mirroring `shared/player/player.ts`'s own `[player]`.
 */
const LOG_PREFIX = '[cockpit]';

/**
 * `CockpitPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/fly/:assetId`, the operator cockpit
 * (docs/plans/done/MVP3-PLAN.md §C-b, the "one job, one page" persona: *flies ONE drone at a time; everything
 * else is noise*). Split out of the old combined `FlyFacade` when the cockpit gained its own
 * addressable route (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12) — `drone-picker-facade.ts` is the
 * picker's own half; **almost everything below is that same class, unchanged**, just no longer
 * sharing a component with the picker. See that file's own doc comment for what stayed picker-side.
 *
 * **What's actually different from the pre-split `FlyFacade`, concretely**:
 *   - `activeAssetId` is now driven by the route's own `:assetId` param (via {@link selectAsset},
 *     called from `CockpitPage`'s constructor `effect()`) instead of the picker's internal
 *     resolve-once-then-flip-a-signal dance — there is no more "no asset selected" state for this
 *     page to represent; every mount of this component already has a concrete id to load.
 *   - **`loadError`** is new — the honest empty state docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12 requires for
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
 * cadence, same O(N)-amplification guards (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2), just relocated.
 */
@Injectable()
export class CockpitFacade {
  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  /** Only for the autostart effect's own `router.navigate([], { relativeTo: this.route, ... })` call
   * below — mirrors `inventory-facade.ts`/`devices-facade.ts`/`command-facade.ts`'s identical
   * one-shot-query-param-strip idiom. */
  private readonly route = inject(ActivatedRoute);
  private readonly scheduler = inject(PollScheduler);
  /** Named `liveStore`, not `live` — this class already has a public `live` computed (below,
   * "stream() !== undefined"), unrelated to `LiveFacade`'s own connection state. */
  private readonly liveStore = inject(LiveFacade);
  private readonly auth = inject(AuthFacade);

  readonly fleet = inject(FleetFacade);
  readonly settings = inject(SettingsFacade);
  readonly telemetry = inject(TelemetryFacade);
  readonly detections = inject(DetectionsFacade);
  /** `providedIn: 'root'` singleton, injected here rather than read in `cockpit.ts` per
   * `architecture.spec.ts`'s own rule (a routed page injects only its facade) — {@link heroStatus}
   * below is its one reader in this cockpit. */
  private readonly systemStatus = inject(SystemStatusFacade);
  /** The asset's two seats (docs/plans/active/CREW-CONTROL-PLAN.md §3.1/§3.6, wave W4) — page-provided
   * like every other store here (`CockpitPage`'s own `providers` array), mirroring `features/crew/
   * crew-facade.ts`'s identical injection. The pilot's cockpit only ever reads the *camera* seat
   * (`cameraSeatHeldByOther`/`cameraSeatHolderLabel` below); the flight seat is this pilot's own by
   * construction of being on this page at all and has no reader here. */
  readonly seats = inject(SeatFacade);
  readonly events = inject(EventsFacade);
  readonly geofence = inject(GeofenceFacade);
  /** Visual-geolocation corrections (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3/§3.4/§3.8, wave H6) — the
   * divergence chip/detail popover (`fly-osd.ts`) and `mapCorrections` below both read this directly. */
  readonly geo = inject(GeoFacade);
  /**
   * Custody grounding (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics", wave WB1) — its
   * own class, not folded into this facade's own state, so `cockpit-facade.ts`'s source stays free
   * of every readiness-API literal token `core/telemetry/preflight-readiness-independence.spec.ts`
   * scans for (`GroundingStore`/`groundedReason` match none of them): the live-telemetry preflight
   * checklist must never be able to observe that the readiness API exists, even transitively through
   * this facade. {@link groundedReason} below is a plain pass-through — see that store's own doc
   * comment for the actual fetch/parse.
   */
  private readonly grounding = inject(GroundingStore);
  /**
   * The three halves of the Common Operational Picture (docs/plans/done/MAP-REWORK-PLAN.md §5.2) — exposed as
   * whole stores (not thin passthroughs), mirroring `geofence` above: `cockpit.html` wires
   * `<vision-tactical-map>`'s `[marks]`/`[layers]`/`[drawings]`/`[selectedMarkId]`/`(markSelected)`/
   * `(mapClicked)`/`(drawingCompleted)`/`(drawingSelected)` straight to them, and
   * `<vision-marks-panel>` plus the shared `shared/map/map-controls/**` components inject the same
   * `providedIn: 'root'` singletons directly (non-routed presentational children, per
   * `architecture.spec.ts`'s own carve-out).
   */
  readonly marks = inject(MarksFacade);
  readonly layers = inject(LayersFacade);
  readonly drawings = inject(DrawingsFacade);

  /**
   * The map inset's single `[interactionMode]`, folded from the two independent arming states that
   * can produce one — an armed mark palette and an armed drawing kind
   * (`core/map-data/drawings-logic.ts#resolveInteractionMode`). Neither store knows about the other;
   * this is the one place they meet.
   */
  readonly interactionMode = computed(() => resolveInteractionMode(this.marks.armed(), this.drawings.mode()));
  private readonly weather = inject(WeatherFacade);

  // --- Header switcher's own asset list (renamed from the old FlyFacade's `pickerAssets` — see
  // this class's own doc comment) ---------------------------------------------------------------
  readonly switcherAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  readonly orderedSwitcherAssets = computed(() => sortAssetsForPicker(this.switcherAssets() ?? []));

  /** The header switcher's own sentinel `<option>` value (docs/plans/done/UX-REWORK-PLAN.md §U-a bullet 4). */
  readonly ALL_DRONES_OPTION = ALL_DRONES_OPTION_VALUE;

  readonly activeAssetId = signal<string | undefined>(undefined);
  readonly asset = signal<AssetDetails | undefined>(undefined);
  /** See this class's own doc comment — the honest-empty-state signal `:assetId` degrading needs. */
  readonly loadError = signal(false);

  // --- Telemetry/detections re-entry guards (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2) ---------
  // The last deviceId/streamId the corresponding constructor effect actually acted on — compared
  // by value (mirrors `core/map/map-store.ts#reconcileTrackers`), not by the enclosing `asset()`/
  // `stream()` object's own identity, which changes every ~5s poll tick regardless.
  private lastTelemetryDeviceId: string | undefined = undefined;
  private lastDetectionsStreamId: string | undefined = undefined;
  /** `${assetId} ${firmware}` — see the capabilities-tracking effect below (constructor). */
  private lastCapabilitiesKey: string | undefined = undefined;

  /** `null` until the asset poll is actually paused/resumed for the first time — see
   * `applyAssetPollTransport` (docs/plans/done/SCALE-100-PLAN.md §5 S6, item 1). */
  private assetPollStopFn: (() => void) | null = null;

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
   * device.id)`). **Verified against docs/plans/done/REALTIME-PLAN.md Phase R-a item 4**: this computed
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

  /** `stream()#state` projected to a primitive — `stream()` itself is a fresh object every ~5s poll
   * tick even when nothing changed (this file's own recurring "guarded on a derived primitive"
   * convention, e.g. `lastTelemetryDeviceId` below); whether this stream's *video* is actually
   * flowing, measured server-side (docs/plans/done/STREAM-STATE-PLAN.md §2.3). `undefined` with
   * nothing running, or against a backend that predates the field; `stream-state-logic.ts#videoNotice`
   * degrades both to silence. */
  readonly streamState = computed(() => this.stream()?.state);

  /** What the operator is told about the video right now — `null` for "say nothing", which covers
   * both "it is fine" and "we could not measure it". Never speaks about detection. */
  readonly videoNotice = computed(() => videoNotice(this.live(), this.streamState()));

  // --- CV profile hierarchy / live config read-back (docs/plans/active/CV-SETTINGS-PLAN.md §3, wave
  // W7) — the one honest replacement for the deleted `SettingsFacade` CV-defaults draft (H2). Two
  // independent reads, merged by `resolveCvConfig`: the asset's own effective profile
  // ({@link effectiveProfile}, §3.1's hierarchy — what the *next* Start will apply) and, once a
  // stream exists, that stream's own live config ({@link streamConfig}, `GET .../config` — H6's real
  // readback instead of assuming from what this browser last sent). {@link resolvedCvConfig} is the
  // one merge point every fly-time CV control (`CvControlPanel`, `CvSetupModal`, `DetectionsStrip`)
  // renders from.

  /** The asset's own effective CV profile — fetched whenever {@link activeAssetId} changes,
   *  independent of whether a stream is running (an asset always has *some* effective profile, per
   *  §3.1's "behavior-preserving with zero bindings" default). `undefined` before the first fetch
   *  resolves, on any failure, or with no asset selected — every reader degrades honestly (§3.5 rule
   *  3), never fabricating a profile name. */
  readonly effectiveProfile = signal<EffectiveCvProfile | undefined>(undefined);

  /** The running stream's own live config (`GET /api/streams/{id}/config`) — fetched whenever
   *  {@link stream}'s own `streamId` changes, `undefined` while nothing is running, before the first
   *  fetch resolves, or on failure. This is what makes the fly-time controls a real readback (H6)
   *  rather than an assumption carried forward from whatever this browser last PATCHed. */
  readonly streamConfig = signal<StreamConfigResponse | undefined>(undefined);

  /** The one value every fly-time CV control renders — see this section's own doc comment. */
  readonly resolvedCvConfig = computed<ResolvedCvConfig | undefined>(() =>
    resolveCvConfig(this.streamConfig(), this.effectiveProfile()),
  );

  /** `canManageOrg` (`core/org/org-logic.ts`), the same predicate `/vision/profiles`
   *  (`VisionProfilesFacade.canManage`) gates on — the Vision drawer's "Save to this asset's
   *  profile" action is hidden for anyone who couldn't reach that page to see the result anyway.
   *  Dev parity: `vision.auth.enabled=false`'s dev principal resolves to `ADMIN`/unbounded, so this
   *  is always `true` in dev, unchanged behavior. */
  readonly canManage = computed(() => canManageOrg(this.auth.capabilities()));

  private lastEffectiveProfileAssetId: string | undefined = undefined;
  private lastStreamConfigStreamId: string | undefined = undefined;

  /**
   * **The one place this cockpit decides where a detection control's position comes from**
   * (docs/plans/done/STREAM-STATE-PLAN.md §3.1) — the running stream's own server-side intent while
   * something is running, the asset's own resolved CV config otherwise (wave W7 — previously this
   * browser's `SettingsFacade` draft; see {@link resolvedCvConfig}'s own doc comment for why that
   * changed). The rail's off-dot, the video-surface "Turn on" chip and the drawer's Detect switch
   * all read this one value, so they cannot disagree with each other or with the backend.
   */
  readonly detectionOn = computed(() =>
    resolveDetectionEnabled(this.stream()?.detectionEnabled, this.resolvedCvConfig()?.detectionEnabled ?? false),
  );

  /** True while a Detect on/off request is in flight — the switch is bound to server truth, so
   * without this there is a round-trip during which a click appears to have done nothing. */
  readonly detectionPending = signal(false);

  /** Staleness honesty (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.4) — "Detections paused —
   * last seen Ns ago" once the newest batch is older than `DETECTION_STALE_CUTOFF_SECONDS`, or `null`
   * to say nothing (mirrors {@link videoNotice}'s own shape). Gated on {@link detectionOn}: a stream
   * the operator has turned detection off for already has its own honest "off" chip
   * (`detectionOffChipVisible` in `cockpit.ts`) — this notice is only for "detection is meant to be
   * on but nothing has arrived recently", never a second, contradictory read on the same fact. */
  readonly detectionsPausedNotice = computed(() =>
    this.detectionOn() ? this.detections.pausedNotice() : null,
  );

  // --- Intent chips + hero status line (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§4.8, wave
  // W3.3) -----------------------------------------------------------------------------------------
  /** The most recent `PatchStreamConfigResponse#sources` this session has actually seen — i.e. the
   *  provenance of a config PATCH this browser itself just sent, not a durable server fact (§6's own
   *  "As built" note: `sources` reports provenance only on the create/update response itself, never
   *  on a later `GET`). `undefined` before any PATCH this session, on a failed PATCH ({@link setIntent}
   *  below degrades the same way {@link followTrack} already does), or once {@link selectAsset} moves
   *  to a different asset — a PATCH response about the *previous* asset's stream has nothing honest
   *  to say about this one. Read by a sibling wave (W3.4)'s Tuning modal; exposed plainly here with
   *  no reader of its own yet in this file beyond that.
   */
  private readonly lastConfigSourcesSignal = signal<CvProfileSources | undefined>(undefined);
  readonly lastConfigSources = this.lastConfigSourcesSignal.asReadonly();

  /** The Fly hero's own one status line — see {@link flyHeroStatus}'s own doc comment for the exact
   *  4-state priority order. `cv-service`'s row may be absent from `subsystems` entirely against a
   *  server that predates it, or before the first `/api/system/status` read resolves; either way
   *  `flyHeroStatus` treats an absent row as "no fault known", never a guessed one. */
  readonly heroStatus = computed(() =>
    flyHeroStatus(
      this.detectionOn(),
      this.systemStatus.status()?.subsystems.find((subsystem) => subsystem.id === 'cv-service'),
      this.detections.tracks()?.detectionState,
      this.detections.worldObjects(),
    ),
  );

  // --- Deliberately-stopped state (docs/plans/done/MVP2-PLAN.md §S, S-b) — identical pair/rule to
  // `LivePage`/`AssetDetailPage`; reset whenever the primary device changes since that's
  // effectively a fresh device to watch.
  private readonly explicitlyStopped = signal(false);
  private readonly hasBeenLive = signal(false);
  readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  /** Fed by `CockpitPage`'s own `watch` route input — see this class's own doc comment above. */
  private readonly watchSignal = signal<string | undefined>(undefined);
  readonly watchMode = computed(() => isWatchMode(this.watchSignal()));

  /** Fed by `CockpitPage`'s own `autostart` route input (`?autostart=1` —
   * docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.3 "the terminal action"). Consumed exactly once by
   * the constructor effect near the end of this class ({@link autostartHandled}), never re-read
   * after — see that effect's own doc comment for the full one-shot mechanics. */
  private readonly autostartSignal = signal<string | undefined>(undefined);
  /** One-shot guard for the autostart effect — a plain field, not a signal: writing it must not
   * itself be a tracked dependency (that would defeat the guard), and it must simply persist for the
   * life of this facade instance, matching `?autostart=1`'s pinned "consumed once per component
   * instance" contract. */
  private autostartHandled = false;

  readonly telemetryDevicesList = computed(() => telemetryDevices(this.asset()?.devices ?? []));
  readonly hasTelemetryDevice = computed(() => this.telemetryDevicesList().length > 0);

  /**
   * Whether the asset's own open usage — if any — was opened by the operator's `engage` rather than
   * a video stream starting (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4). Read
   * honestly off the same `AssetDetails#recentUsages` this class's 5s asset poll already refreshes
   * (`selectOpenUsage`, `core/telemetry/telemetry-logic.ts`), never off a local "I clicked it" flag
   * — there is no `GET .../session` read endpoint (`VisionApi#engageAssetSession`'s own doc
   * comment), so this poll is the only honest source. `features/fly/rc-monitor.ts` feeds this and
   * `hasTelemetryDevice`/`live` into `resolveSessionAffordance` to decide what its session-link
   * block shows.
   */
  readonly operatorEngaged = computed(() => selectOpenUsage(this.asset()?.recentUsages ?? [])?.origin === 'OPERATOR');

  /**
   * The one bottom-center dock's own stage (docs/plans/active/FLY-FLOW-PLAN.md §2/§4 W1) —
   * `fly-logic.ts#flyStage`'s pure state machine over exactly the signals above; see that function's
   * own doc comment for the priority order and for why its `'engaged'` is a different fact from
   * `fly-hud.ts`'s own `ManualControlClient.state() === 'engaged'`. `cockpit.html` reads this to
   * choose between the idle/starting dock card and nothing (the live/engaged row is `<vision-fly-
   * hud>`'s own zone, gated on this same value).
   */
  readonly stage = computed(() =>
    flyStage({
      live: this.live(),
      stopped: this.stopped(),
      busy: this.busy(),
      streamState: this.streamState(),
      operatorEngaged: this.operatorEngaged(),
    }),
  );

  // --- Flight-controller state: failsafe banner + pre-flight checklist (docs/plans/done/FC-INTEGRATIONS-PLAN.md
  // F-d) — both pure derivations over the same `TelemetryStore.latest()` sample every other OSD chip
  // already reads, no second telemetry source.
  readonly failsafeBanner = computed(() => flightBanner(this.telemetry.latest()));

  /** `GroundingStore#groundedReason` for the currently tracked asset — `undefined` unless it carries
   * an open `MAINTENANCE_GROUNDED:` blocker. Renders `<vision-grounded-banner>` in the same
   * `.grid-banner` area as {@link failsafeBanner} above, and disables Arm via
   * `flight-command-panel.ts#armDisabled` (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave WB1). */
  readonly groundedReason = computed(() => this.grounding.groundedReason());

  /** Re-derives whenever the tracked sample/primary-device/live state changes — a ground-check
   * glance, not a live-ticking instrument (the OSD's own age chip is that); see
   * `flight-state-logic.ts#derivePreflight`'s own doc comment for why `Date.now()` is read here,
   * at the call site, rather than inside that pure function. `this.capabilities()?.vehicleKind` is
   * the same read `rc-monitor.ts#activeProfile` already makes — legitimately `undefined` before the
   * capability fetch resolves or when it fails (`capabilities`'s own doc comment below), which
   * `derivePreflight`'s GPS/Battery rows treat as "don't soften", not as "assume rover". */
  readonly preflightItems = computed(() =>
    derivePreflight(
      this.telemetry.latest(),
      this.capabilities()?.vehicleKind,
      this.primaryDevice() !== undefined,
      this.live(),
      Date.now(),
    ),
  );

  /** The worst-state-wins rollup of {@link preflightItems} — same call
   * `<vision-preflight-checklist>`'s own collapsed head already makes, promoted here (docs/plans/active/
   * FLY-FLOW-PLAN.md §4 W4) so {@link preflightDockSummary} below and the connect-ritual modal's own
   * checklist read one shared value rather than two independent calls that could drift. */
  readonly preflightSummary = computed(() => preflightSummary(this.preflightItems()));

  /** The idle/starting dock card's own one-line pre-flight fact (docs/plans/active/FLY-FLOW-PLAN.md §4
   * W4 item 3b) — see `fly-logic.ts#dockPreflightSummaryLabel`'s own doc comment for the exact
   * wording rule. */
  readonly preflightDockSummary = computed(() => dockPreflightSummaryLabel(this.preflightSummary()));

  /** docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e — same `TelemetryStore.latest()` sample every OSD chip
   * already reads; `deriveDiagnostics` itself omits every row whose keys aren't in `extra`. */
  readonly diagnosticsRows = computed(() => deriveDiagnostics(this.telemetry.latest()?.extra));

  /**
   * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 — gates `<vision-return-home-button>` (`cockpit.html`'s
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
   * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2 — the vehicle's own capability matrix, fetched once per
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

  /** D1 fix (docs/plans/active/CREW-CONTROL-PLAN.md §2.3 D1, wave W4) — the single `[canCommand]`
   * input `cockpit.html` feeds `<vision-fly-hud>`. See `fly-logic.ts#commandSurfaceVisible`'s own doc
   * comment for why ANDing `!watchMode` in here (rather than gating the whole HUD mount) is the fix:
   * it closes the Arm/Disarm zone and the mode picker, which both already hide on `canCommand()`
   * alone, without touching this HUD's informational content. */
  readonly flyHudCanCommand = computed(() => commandSurfaceVisible(this.canShowCommands(), this.watchMode()));

  // --- Camera seat (docs/plans/active/CREW-CONTROL-PLAN.md §3.4/§3.5/§3.6, wave W4) ---------------
  // The pilot never faces a refusal on their own aircraft (§0.2/§3.2 rule 3) — a camera write from
  // this page always succeeds and preempts whoever held it, so {@link takeCameraSeat} below is
  // legibility, not a requirement. These two computeds are the only seat-derived facts the cockpit
  // renders: the dock's one crew-presence line (`cockpit.html`'s own S3/S4 dock template) and the
  // Vision drawer's read-state reason.

  /** §3.2 rule 2's honest discriminator — see `fly-logic.ts#cameraHeldByOther`'s own doc comment for
   * why this is not simply `!camera.mine`. */
  readonly cameraSeatHeldByOther = computed(() => cameraHeldByOther(this.seats.camera()));

  /** The dock's one crew-presence line (`fly-logic.ts#crewCameraDockLine`, §3.5) — `null` at rest, so
   * `cockpit.html` renders zero pixels for it exactly as that section requires. */
  readonly crewCameraLine = computed(() => crewCameraDockLine(this.seats.camera()));

  /** The Vision drawer's own read-state reason — see `fly-logic.ts#cameraHolderLabel`'s own doc
   * comment for the honest fallback. */
  readonly cameraSeatHolderLabel = computed(() => cameraHolderLabel(this.seats.camera()));

  /** `cockpit.html`'s own "Take camera" button — disables itself and swaps its label while the
   * request is in flight, mirroring every other one-shot command button on this page
   * (`busy`/`sessionBusy`/`detectionPending`). */
  readonly cameraSeatPending = signal(false);

  // --- Weather go/no-go chip (docs/plans/done/OPS-CORE-PLAN.md §W) --------------------------------------
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
   * The selected mark's "from drone" bearing/distance readout (docs/plans/done/TACTICAL-MARKS-PLAN.md §3)
   * reuses `weatherPosition` verbatim rather than re-deriving "best position we have right now" a
   * second time — same live-fix-else-last-known fallback, same honest degrade to `undefined` (the
   * readout shows "—") when neither exists. No "from home" counterpart: surveyed, no home/launch
   * position is modeled anywhere in this app's telemetry/asset data (docs/plans/done/TACTICAL-MARKS-PLAN.md
   * Open Q3's own documented default — "show from-drone always; from-home only when a home position
   * exists" — so `<vision-marks-panel>` renders drone-only and says so, never a fabricated distance).
   */
  readonly dronePosition = this.weatherPosition;

  /**
   * Whether {@link dronePosition} is a real fix, not merely a defined-but-fake `(0, 0)` MAVLink
   * no-fix report (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1's own trap — the same one
   * {@link notStreamingPosition} below already guards against). Gates `cockpit.html`'s map inset
   * (docs/plans/active/FLY-FLOW-PLAN.md §4 W2, D5): a `!== undefined` check alone would still let a
   * `(0, 0)` reading through and paint a world-zoomed map centered on Null Island — "pure noise,
   * answers nothing" is exactly as true of a fake fix as of no fix at all.
   */
  readonly hasKnownPosition = computed(() => hasFix(this.dronePosition()));

  // --- Not-streaming card (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1) --------------------------
  // `cockpit.html` renders one honest card in place of the video hero's bare "Not streaming" caption
  // whenever `live()` is false — CLAUDE.md's "degrade honestly": say what's actually known (last
  // seen, last position) rather than nothing.

  /** `'Last seen 4d 2h ago'`, or `'Never seen'` once no sample has ever arrived for this asset —
   * `TelemetryStore.sampleAgeSeconds()` is the same age every OSD/Controller-drawer chip already
   * reads (H1: a stale-but-present sample still has a real answer here, even while nothing is
   * live). */
  readonly notStreamingLastSeen = computed(() => {
    const age = this.telemetry.sampleAgeSeconds();
    return age === undefined ? 'Never seen' : `Last seen ${humanAge(age)} ago`;
  });

  /** The card's "last position" line — reuses {@link dronePosition} verbatim (live fix else the
   * asset's own last-known position) and `fly-logic.ts#positionLabel`'s identical `lat, lon`
   * format, rather than a third rendering of "best position we have right now". `undefined` omits
   * the line entirely — no fix has ever been reported, **or** the only position on record is
   * exactly `(0, 0)` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1: a MAVLink no-fix report,
   * never a real vehicle position) — same poka-yoke rule as every other chip on this page. Gated on
   * `core/geo/geo-logic.ts#hasFix` rather than re-deriving the `(0, 0)` check here. */
  readonly notStreamingPosition = computed(() => {
    const position = this.dronePosition();
    return hasFix(position) ? positionLabel(position) : undefined;
  });

  // --- Map inset (docs/plans/done/MAP-REWORK-PLAN.md §5.1 Wave D) ------------------------------------------
  // `<vision-tactical-map>` replaced the deleted `<vision-live-map>`, which read this facade's own
  // `TelemetryStore` through DI. The new component is dumb — every overlay is an input — so the
  // cockpit's single followed drone is built here from the exact same trail/latest signals, via the
  // shared pure builder. An empty array (no fix and no trail yet) plots nothing, which is precisely
  // what the old component's empty-trail branch did.

  /** The one asset the map follows — also what `[followAssetId]` switches the map into follow mode with. */
  readonly mapFollowAssetId = computed(() => this.activeAssetId() ?? null);

  /** `<vision-tactical-map>`'s `[assets]` — 0 or 1 markers, per §5.1's "1 in follow mode". */
  readonly mapAssets = computed(() => {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return [];
    }
    return followMarkers({
      assetId,
      displayName: this.asset()?.displayName ?? '',
      categoryName: this.asset()?.categoryName,
      trail: this.telemetry.trail(),
      latest: this.telemetry.latest(),
    });
  });

  /**
   * `<vision-tactical-map>`'s `[corrections]` (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) — 0
   * or 1 rows, the followed asset's own latest visual-geolocation correction. `GeoFacade.latest()`
   * reads `undefined` on a `NO_FIX`/not-yet-computed row (and always while `vision.geo.visual.enabled`
   * is off, since the poll then either 404s silently or never returns this asset) — either way an
   * empty array here, so the map layer is simply absent, never a fabricated marker.
   */
  readonly mapCorrections = computed(() => {
    const latest = this.geo.latest();
    return latest ? [latest] : [];
  });

  readonly latencySeconds = signal<number | null>(null);
  readonly transport = signal<Transport>('hls');
  /** The shared, persisted declutter level (docs/plans/active/CV-SETTINGS-PLAN.md wave W7, H12) —
   * aliases `SettingsFacade.declutterLevel` directly (the exact same `WritableSignal` instance, not a
   * copy), so this facade, `LiveFacade` and `WallTile` all read/write one preference instead of each
   * keeping its own unshared in-memory signal. `cockpit.html` still binds `[boxesMode]="facade.
   * boxesMode()"` / `(boxesModeChange)="facade.boxesMode.set($event)"` unchanged — only what's behind
   * the name changed. See {@link cycleBoxes}. */
  readonly boxesMode = this.settings.declutterLevel;

  /**
   * The FOLLOW-locked track id, read from the **per-frame** detections feed
   * (`FrameTracking#lockedTrackId` on `detections.results()[0]`), not from the 2s tracks poll
   * (docs/plans/active/TRACK-FOLLOW-PLAN.md §2.2 D1, wave W4 — this fixes the defect the wave is named
   * for: closing the Vision drawer used to stop `CvControlPanel`'s poll entirely, and with it the
   * only signal that ever wrote this field, silently un-following the target the instant the drawer
   * closed even though the server-side lock was untouched). The detections feed runs for the whole
   * stream session regardless of any drawer (`DetectionsStore.track`'s own doc comment), so this now
   * survives a closed drawer exactly like the lock itself does. `0` is the wire's own "no lock"
   * sentinel (mirrors `StreamTracksResponse#lockedTrackId`), never `null`/`undefined` — with no
   * detections yet, `results()[0]` is `undefined` and this reads `0`, the same "no lock" idle value
   * as before.
   */
  readonly lockedTrackId = computed(() => this.detections.results()[0]?.tracking?.lockedTrackId ?? 0);

  /**
   * The follow lock's own lifecycle — mirrors `GET .../tracks`' trailing `follow` object 1:1
   * (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1, wave W4). `null` before any lock has ever been
   * issued this session, after a `RELEASED` read, or on any tracks-poll transport failure — the same
   * "hidden, not fabricated" degrade every other tracks-poll-derived read in this class already
   * follows. Feeds `<vision-follow-hud>` directly (`cockpit.html`); see {@link wantsTracksPoll} for
   * what keeps the underlying poll itself alive long enough to observe a `LOST` transition.
   */
  readonly follow = computed<FollowStatus | null>(() => this.detections.tracks()?.follow ?? null);

  /**
   * The lost target's last-confirmed box, fed to `<vision-player>`'s `lostBox` input only while
   * {@link follow} reports `LOST` (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1 wave W4, D3) — `null`
   * the instant the state moves on (recovered to `HOLDING`, or released), so the dashed placeholder
   * never lingers a frame past being true. `player.ts` draws this tier-independently of the normal
   * detection-box pipeline; see that component's own `drawLostBox` doc comment.
   */
  readonly lostBox = computed<BoundingBox | null>(() => {
    const follow = this.follow();
    return follow?.state === 'LOST' ? follow.lastBox : null;
  });

  /**
   * Whether a tracks session should be running right now (docs/plans/active/TRACK-FOLLOW-PLAN.md
   * §3.5 "two feeds, two jobs") — driven into `DetectionsStore.followTracks` by the constructor
   * effect below. Before wave W9 this gated the `GET .../tracks` poll directly, the only mechanism
   * there was; now (decision E25) `DetectionsStore.tracks` is transport-aware like `results`, so this
   * gates the tracks session's "wanted" state instead — `false` tears down both the poll and any live
   * read, `true` lets `tracks()` resolve to live `tracks:<assetId>` data whenever it can, falling back
   * to the same poll only without an asset id or while `LiveFacade` isn't open (that class's own doc
   * comment). The plan's own formula is "the Vision drawer is open OR the
   * per-frame lock is non-zero OR the last follow read was LOST"; the middle and last clauses are
   * exactly {@link lockedTrackId}/{@link follow} below. **The first clause is a deliberate
   * deviation**: `CockpitPage` owns the drawer's own open/closed `UiStore` and is out of this wave's
   * file scope, and `UiStore` is deliberately non-injectable (`core/ui/ui-store.ts`'s own doc
   * comment) — there is no DI path from this facade to that state without either injecting a store
   * into a page component (an architecture.spec.ts invariant this wave must not touch) or adding a
   * new output through `CvControlPanel` (out of scope — "keeps its chip but loses tracks-poll
   * ownership" only). {@link detectionOn} substitutes for it instead: it is the same "operator
   * plausibly cares about Vision right now" signal the drawer's own mount condition already reads
   * from elsewhere in this cockpit, and it fails toward *more* polling (a few extra idle reads),
   * never toward silently dropping a `LOST` recovery window — the one honesty-critical case. A
   * follow-up wave touching `cockpit.ts` should close this gap properly by feeding the drawer's own
   * open boolean into the facade instead.
   */
  private readonly wantsTracksPoll = computed(
    () => this.detectionOn() || this.lockedTrackId() !== 0 || this.follow()?.state === 'LOST',
  );

  /**
   * The class currently hovered in the merged Vision drawer's strip (wave W5,
   * docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3, research §3.5) — `shared/player/detections-strip.ts`'s own
   * `(hoveredClassChange)`, relayed straight into `<vision-player [hoveredClass]>` (`cockpit.html`),
   * which temporarily promotes every box of that class to tier T1
   * (`shared/player/detection-overlay-logic.ts#detectionTiers`). Mirrors {@link lockedTrackId}'s own
   * "echo a child's own signal up through the facade, straight into the player" shape from wave W4,
   * just without a poll behind it — hover is instantaneous DOM state, not a wire-confirmed fact, so
   * there is nothing to reconcile against. `null` = nothing hovered (also this signal's own idle
   * value — never a fabricated "something is hovered" default).
   */
  readonly hoveredDetectionClass = signal<string | null>(null);

  /**
   * Whether `CvSetupModal`'s own "Expert" `<details>` disclosure is open (docs/plans/active/
   * CV-PANEL-SPLIT-PLAN.md P1) — facade-owned rather than a component-local boolean on the modal
   * itself, mirroring {@link lockedTrackId}/{@link hoveredDetectionClass}'s own "plain
   * `CockpitFacade` signal round-tripped through an input/output pair" shape. Needed here
   * specifically (unlike those two) because the modal, unlike the always-mounted-while-the-drawer-
   * is-open panel, is destroyed and recreated on every close/reopen — a component-local field would
   * forget an operator's "I always want Expert open" preference the instant they closed the dialog.
   * Transient, not persisted to `localStorage`: this is a per-session convenience, not a durable
   * setting worth a storage key of its own.
   */
  readonly cvExpertOpen = signal(false);

  /** Persisted, non-mutually-exclusive toggle (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — see this class's own
   * doc comment above `MAP_VISIBLE_KEY`. */
  readonly mapVisible = signal(readPersistedFlag(MAP_VISIBLE_KEY, true));

  readonly busy = signal(false);

  readonly latestFinishedUsageEntry = computed(() => latestFinishedUsage(this.asset()?.recentUsages ?? []));

  /** "Replay an earlier flight" menu (D3r, docs/plans/active/ASSET-FLOWS-PLAN.md §3 WB2) — the small
   *  list of finished usages behind {@link latestFinishedUsageEntry}'s own primary link, sourced from
   *  the exact same already-in-hand `asset()?.recentUsages` (no extra `GET /api/usages` read — see
   *  `fly-logic.ts#earlierReplayableUsages`'s own doc comment for why). Empty whenever there is at
   *  most one finished usage to offer, which `cockpit.html` reads as "don't render the menu at all". */
  readonly olderReplayableUsages = computed(() => earlierReplayableUsages(this.asset()?.recentUsages ?? []));

  // --- Events ticker overlay (docs/plans/done/MVP3-PLAN.md §C-b: "this stream's events via events-store,
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
    // Panel state memory (docs/plans/done/UX-REWORK-PLAN.md §U-b item 7) — persists whenever the map toggle
    // actually changes (the `M` shortcut, or `CockpitPage#collapseOverlays`'s `Esc` handling); the
    // initial `signal()` value above already restored whatever was last saved.
    effect(() => writePersistedFlag(MAP_VISIBLE_KEY, this.mapVisible()));

    // Keeps the weather chip fresh as the flown asset's own position changes — `WeatherFacade.track`
    // itself no-ops instantly unless the 10-minute cache is actually stale (docs/plans/done/OPS-CORE-PLAN.md §W).
    effect(() => this.weather.track(this.weatherPosition()));

    // Latches once `live()` is ever observed true for the current primary device — see `stopped`'s
    // own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Logs exactly what `<vision-player>` is being fed (docs/plans/done/MVP3-PLAN.md follow-up: makes an
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
    // **Guarded on the derived deviceId primitive** (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2):
    // `this.asset()` is a fresh `AssetDetails` object every ~5s poll tick (`refreshPoll`) even when
    // nothing about the tracked device actually changed, so this effect re-runs on that cadence
    // regardless. Without this guard, re-entering `telemetry.track()` with the *same* deviceId every
    // ~5s re-ran `findOpenUsageId` from scratch each time — the diagnosed O(N) burst (`GET
    // /api/assets` + `GET /api/assets/{id}` per fleet asset, docs/plans/done/REALTIME-PLAN.md §0). Mirrors
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
        // Already has the owning asset id (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3) — skips
        // `TelemetryStore`'s own O(N) fleet-listing fallback.
        this.telemetry.track(deviceId, this.activeAssetId());
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while the primary device's stream is actually running.
    // Guarded on the derived streamId primitive for the identical reason as telemetry above — a
    // stream object re-arriving unchanged every ~5s poll tick must not re-enter `track()` (which
    // clears results immediately, a visible flicker, docs/plans/done/REALTIME-PLAN.md §0).
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (!trackingIdChanged(streamId, this.lastDetectionsStreamId)) {
        return;
      }
      this.lastDetectionsStreamId = streamId;
      // A hover carried over from the previous stream's strip would promote a box on a feed it was
      // never hovered on — same "a stream switch clears anything scoped to the old one" rule
      // `explicitlyStopped`/`hasBeenLive`/`capabilities` already follow elsewhere in this file.
      this.hoveredDetectionClass.set(null);
      if (streamId) {
        // Already has the owning asset id (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) — lets
        // `DetectionsStore` subscribe to live `detections:<assetId>` instead of only polling.
        this.detections.track(streamId, this.activeAssetId());
      } else {
        this.detections.reset();
      }
    });

    // Drives the tracks session (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W4; transport-
    // flipped wave W9 decision E25 — see {@link wantsTracksPoll}'s own doc comment for the `wanted`
    // formula, its one documented deviation, and what "wanted" now actually gates). No
    // `trackingIdChanged` guard needed: `DetectionsStore.followTracks` (like
    // `geo.track`/`grounding.track` above) already no-ops internally on an unchanged
    // `(streamId, wanted)` pair, so re-running this effect on every ~5s `stream()` poll tick is
    // harmless, exactly like those two.
    effect(() => {
      const streamId = this.stream()?.streamId;
      this.detections.followTracks(streamId ?? '', streamId !== undefined && this.wantsTracksPoll());
    });

    // Visual-geolocation corrections (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.4, wave H6) — keyed
    // directly on `activeAssetId()`, no device/stream indirection to guard on (unlike telemetry/
    // detections above): `GeoFacade.track()` is already a no-op for an unchanged assetId (its own
    // `lastTrackAssetId` field), so this effect needs no derived-primitive guard of its own.
    effect(() => {
      const assetId = this.activeAssetId();
      if (assetId) {
        this.geo.track(assetId);
      } else {
        this.geo.reset();
      }
    });

    // Custody grounding (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave WB1) — same per-asset
    // track/reset shape as `geo` above; `GroundingStore.track()` is a no-op for an unchanged
    // assetId (its own `lastTrackedAssetId` field), so no derived-primitive guard needed here either.
    effect(() => {
      const assetId = this.activeAssetId();
      if (assetId) {
        this.grounding.track(assetId);
      } else {
        this.grounding.reset();
      }
    });

    // Seats (docs/plans/active/CREW-CONTROL-PLAN.md §3.1/§3.6, wave W4) — keyed on `activeAssetId()`
    // alone, mirroring `geo.track()`/`grounding.track()` immediately above: `SeatFacade.track()` is
    // already a no-op for an unchanged assetId, so no derived-primitive guard needed here either.
    effect(() => {
      const assetId = this.activeAssetId();
      if (assetId) {
        this.seats.track(assetId);
      } else {
        this.seats.reset();
      }
    });

    // The asset's own effective CV profile (docs/plans/active/CV-SETTINGS-PLAN.md §3, wave W7) —
    // keyed on `activeAssetId()` alone, like `geo.track()` above: independent of whether a stream is
    // running, since §3.1's hierarchy always resolves to *some* profile.
    effect(() => {
      const assetId = this.activeAssetId();
      if (!trackingIdChanged(assetId, this.lastEffectiveProfileAssetId)) {
        return;
      }
      this.lastEffectiveProfileAssetId = assetId;
      if (assetId) {
        void this.loadEffectiveProfile(assetId);
      } else {
        this.effectiveProfile.set(undefined);
      }
    });

    // The running stream's own live config (wave W7, H6) — guarded on the derived streamId
    // primitive for the same reason telemetry/detections above are: a stream object re-arriving
    // unchanged every ~5s poll tick must not re-fetch.
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (!trackingIdChanged(streamId, this.lastStreamConfigStreamId)) {
        return;
      }
      this.lastStreamConfigStreamId = streamId;
      if (streamId) {
        void this.loadStreamConfig(streamId);
      } else {
        this.streamConfig.set(undefined);
      }
    });

    // docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2 — flight-command panel capabilities. Fetched once per
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
    // against (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2).
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

    // "O(visible) discipline" (docs/plans/done/MVP2-PLAN.md §E, E-b bullet 5) — one more of the handful of
    // pages that keeps the shared global events poll alive while mounted.
    this.events.activate();

    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: the cockpit renders `<vision-tactical-map>` directly
    // (the map inset) plus the Map/Marks drawers built from the same stores — this facade holds
    // demand for all four for its own lifetime, released alongside `events` below.
    this.geofence.activate();
    this.marks.activate();
    this.layers.activate();
    this.drawings.activate();

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
    this.assetPollStopFn = this.scheduleAssetPoll();

    // Pause/resume the asset poll against `LiveFacade`'s own connection state
    // (docs/plans/done/SCALE-100-PLAN.md §5 S6, item 1) — mirrors
    // `core/fleet/fleet-store.ts#FleetStore`'s identical transport-switch effect: pause while live
    // is open, resume and refetch immediately the moment it drops (the switcher list/active asset
    // may be stale from however long the connection was up).
    effect(() => {
      this.applyAssetPollTransport(isLiveAvailable(this.liveStore.connectionState()));
    });

    // The `fleet` topic (`List<AssetSummaryResponse>`) is exactly `switcherAssets`' own domain —
    // applied the moment one arrives, independent of whether the poll above is currently paused, so
    // a snapshot that lands before the transport-switch effect above has paused polling is never
    // dropped (mirrors `FleetStore`'s own `devices`-snapshot effect). This keeps the header
    // switcher's own list exactly as fresh while live as the 5s poll kept it before — only
    // `loadAsset(id)`'s own richer `AssetDetails` (`devices`/`recentUsages` — no matching live
    // topic) actually goes stale for the length of the live connection, the same accepted
    // trade-off `GeofenceFacade` takes for its own near-static data.
    effect(() => {
      const snapshot = this.liveStore.fleet();
      if (snapshot !== undefined) {
        this.switcherAssets.set(snapshot);
      }
    });

    // `?autostart=1` (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.3 "the terminal action") — the
    // onboarding wizard's Ready screen links `Open cockpit ›` straight to
    // `/fly/:assetId?autostart=1` once both proof halves are shown; this is where that link's
    // promise is kept. Consumed **exactly once** per component/facade instance ({@link
    // autostartHandled}), never re-armed by a later re-render: a bare `effect()` with no guard would
    // re-fire on *any* dependency change it reads, including its own side effects —
    // {@link engageSession}'s success path re-fetches {@link asset}, which would otherwise retrigger
    // this very effect and re-issue the command a second time.
    //
    // Waits for the asset load {@link selectAsset} already kicked off to actually settle
    // ({@link asset} truthy, or {@link loadError} — a genuine dead `:assetId`) before deciding
    // anything: {@link primaryDevice}/{@link hasTelemetryDevice} both read `asset()?.devices`, so
    // deciding before that resolves would misread "not loaded yet" as "not fitted" and silently skip
    // a half that is actually there — a fabricated-by-omission attempt (CLAUDE.md rule 9).
    //
    // Calls {@link start}/{@link engageSession} exactly as a manual click on the same buttons would
    // — no second, competing denial UI here; a grounding denial (docs/plans/active/ASSET-FLOWS-PLAN.md
    // S1) or any other failure surfaces through those methods' own existing toast/silent-degrade
    // paths, untouched. A half with no matching device is never attempted at all — never a
    // fabricated attempt, never a fake tick (§3.3's own "a half that is not-fitted shows —, never a
    // green tick" carried into the command itself, not just the Ready screen's own display).
    effect(() => {
      const autostart = this.autostartSignal();
      if (!isAutostart(autostart) || this.autostartHandled) {
        return;
      }
      const asset = this.asset();
      const failed = this.loadError();
      if (!asset && !failed) {
        return; // still loading — this effect re-runs once `loadAsset` settles either way
      }
      this.autostartHandled = true;
      if (asset) {
        if (this.primaryDevice()) {
          void this.start();
        }
        if (this.hasTelemetryDevice()) {
          void this.engageSession();
        }
      }
      // Strips the param so a reload of the resulting URL never re-fires (§3.3) — `merge` leaves
      // every other query param untouched, mirroring every other one-shot query-param consumer in
      // this app (`inventory-facade.ts#clearSelection`, `devices-facade.ts`, `command-facade.ts`,
      // `roster-facade.ts`, `alerts-facade.ts`).
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { autostart: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    });

    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      this.geofence.release();
      this.marks.release();
      this.layers.release();
      this.drawings.release();
      this.stopAssetPolling();
    });
  }

  /**
   * Switches whether the local 5s asset poll is running — mirrors `FleetStore#applyTransport`
   * exactly (docs/plans/done/SCALE-100-PLAN.md §5 S6, item 1). `liveAvailable` pauses the poll;
   * its absence resumes it, refetching immediately first (mirrors the reconnect-driven branch every
   * other gated poller in this app takes). A no-op when the poll is already in the requested state
   * (`assetPollStopFn`'s own nullness tracks that).
   */
  private applyAssetPollTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      this.stopAssetPolling();
      return;
    }
    if (this.assetPollStopFn !== null) {
      return; // already polling
    }
    void this.refreshPoll();
    this.assetPollStopFn = this.scheduleAssetPoll();
  }

  private scheduleAssetPoll(): () => void {
    return this.scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refreshPoll());
  }

  private stopAssetPolling(): void {
    this.assetPollStopFn?.();
    this.assetPollStopFn = null;
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
    // A prior asset's PATCH provenance has nothing honest to say about this one — see
    // `lastConfigSourcesSignal`'s own doc comment.
    this.lastConfigSourcesSignal.set(undefined);
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
        // The very first load for this pick failed — a genuine dead end (docs/plans/done/NAV-IA-REDESIGN-PLAN.md
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
   * `<select>`/`@for` ordering race this sidesteps (docs/plans/done/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2).
   */
  switcherOptionSelected(candidateAssetId: string): boolean {
    return candidateAssetId === this.activeAssetId();
  }

  /**
   * The header switcher's single `(change)` handler (docs/plans/done/UX-REWORK-PLAN.md §U-a bullet 4: fold
   * the old standalone "All drones" button into this one control) — the sentinel option navigates
   * to the picker (`/fly`), any other value is a real asset id and navigates straight to its own
   * cockpit route. A real route change either way (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12) — unlike the
   * pre-split page's internal signal flip, both picks are now addressable/Back-able on their own.
   *
   * **"All drones…" also forgets the remembered drone** — not just navigate. Without this,
   * `fly-redirect-guard.ts` would immediately bounce a plain `/fly` right back into *this exact*
   * cockpit whenever the current drone is still streaming (its own "skip the picker when it has
   * nothing to ask" job, working as designed) — making the picker practically unreachable from
   * here for as long as the drone keeps flying, breaking docs/extracts/design/01-fly.md's own explicit
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

  /**
   * Click-to-follow (docs/plans/done/TRACKING-PLAN.md §4.D, wave T7) — `<vision-player>`'s own
   * `(trackFollowed)`, the operator clicking a tracked box in the video. A single PATCH, always
   * `mode:'FOLLOW'` + the lock together (`cv-control-panel-logic.ts#buildFollowLockPatch`'s own doc
   * comment); no toast, no optimistic UI update here — the "Following #N" chip's own confirmation
   * comes from `cv-control-panel.ts`'s independent tracks poll, never from this call's return value
   * (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule — a lock is a request until the wire says
   * otherwise). A no-op with nothing running has no stream to patch.
   */
  followTrack(trackId: number): void {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    // A CAMERA-guarded write (§3.3, wave W4) — always succeeds for this pilot (§3.2 rule 3) and
    // takes the seat back if a crew member held it; `seats.refreshNow()` mirrors `features/crew/
    // crew-facade.ts#followTrack`'s identical choke point so the dock's crew-presence line/Vision
    // drawer's read-state catch up on the next tick rather than the ordinary ~3s seat-poll cadence.
    void this.fleet.patchStreamConfig(streamId, buildFollowLockPatch(trackId)).then(() => this.seats.refreshNow());
  }

  /**
   * The Fly hero's own intent chips (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.3) —
   * People/Vehicles/Everything, each a one-shot `{ intent }` PATCH with no other field bundled
   * (never `detectionEnabled: true` — "Turn on" stays the operator's own separate, required act, per
   * §4.7's own decision). No persistent "selected chip" state exists: there is no wire fact for
   * "the stream's current intent," only its resolved *effect* (`model`/`labelFilter`), so unlike
   * {@link followTrack} this never becomes a toggle — every click is independent and none renders
   * pressed. Same no-optimistic-UI, no-extra-error-handling shape as {@link followTrack}:
   * `fleet.patchStreamConfig` already toasts+returns `null` on failure (`FleetStore#patchStreamConfig`'s
   * own doc comment), so `null?.sources` degrades {@link lastConfigSources} to `undefined` — the
   * honest "nothing to report" state — for free, with no `.catch()` of our own to invent. A no-op
   * with nothing running has no stream to patch.
   *
   * **`'CUSTOM'` is deliberately never sent by a hero chip** (a scope judgment call, disclosed in
   * this wave's commit/MODULE.md entry): `IntentPolicyResolver.resolve` (server-side) throws when
   * `intent === CUSTOM` and the same request's `labelFilter` is null/empty, and the hero has no
   * surface to collect custom classes inline — `cockpit.ts#requestCvSetup()` (opening the Tuning
   * modal, where that surface already exists) is what the "Custom" chip actually calls. This method
   * itself stays correct for all four {@link CvProfileIntent} values for whichever future caller
   * does have classes in hand.
   */
  async setIntent(intent: CvProfileIntent): Promise<void> {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    const response = await this.fleet.patchStreamConfig(streamId, { intent });
    this.lastConfigSourcesSignal.set(response?.sources);
  }

  /**
   * `<vision-player>`'s own `(pointFollowed)` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7 D8, wave
   * W3.5) — the sibling {@link followTrack} never had: an operator's click landed on an **untracked**
   * box, or on open video with no box under it, so there is no track id to lock onto, only a
   * normalized `[0,1]` point (see `player.ts#pointFollowed`'s own doc comment for which of those two
   * cases produced it, and `resolveOverlayClickTarget`'s for the exact split). Same choke point as
   * {@link followTrack} in every other respect: a single PATCH always pairing `mode:'FOLLOW'` with the
   * lock (`cv-control-panel-logic.ts#buildPointLockPatch`), no optimistic UI, `seats.refreshNow()`
   * after — a crew member's read state and the dock's presence line catch up on the next tick instead
   * of the ordinary ~3s seat-poll cadence. A no-op with nothing running has no stream to patch.
   */
  followPoint(pointX: number, pointY: number): void {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    void this.fleet.patchStreamConfig(streamId, buildPointLockPatch(pointX, pointY)).then(() => this.seats.refreshNow());
  }

  /**
   * `<vision-follow-hud>`'s own `(release)` output (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.3,
   * wave W4) — the same `buildReleaseLockPatch()` PATCH `CvControlPanel`'s own chip Release button
   * already sends (`releaseLock()` there), so the HUD cannot drift from the drawer's own affordance.
   * No optimistic UI: {@link follow} moves to `RELEASED` (then absent) only once the next tracks
   * poll confirms it, same honesty rule as {@link followTrack}.
   */
  releaseFollow(): void {
    const streamId = this.stream()?.streamId;
    if (!streamId) {
      return;
    }
    // See {@link followTrack}'s own doc comment on the identical seat-refresh choke point.
    void this.fleet.patchStreamConfig(streamId, buildReleaseLockPatch()).then(() => this.seats.refreshNow());
  }

  /**
   * `<vision-follow-hud>`'s own `(reacquire)` output — offered only while {@link follow} reports
   * `LOST` and `reacquirable` (the HUD's own `showReacquire` gate, `follow-logic.ts`). Re-acquiring
   * is the same lock request as the original click-to-follow, just re-issued for the id the operator
   * already had (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.3 decision 6) — a no-op if `follow` is
   * absent (nothing to reacquire).
   */
  reacquireFollow(): void {
    const trackId = this.follow()?.trackId;
    if (trackId === undefined) {
      return;
    }
    this.followTrack(trackId);
  }

  /**
   * The "Turn on" action on `cockpit.html`'s own video-surface affordance (docs/plans/done/CV-DEMAND-PLAN.md
   * wave D3) — the honest chip shown over the video whenever {@link detectionOn} is `false` and a
   * stream is actually live (`fly-logic.ts#showDetectionOffChip`). A thin alias for
   * {@link setDetection}, which is the one write path the drawer's own switch also emits into, so
   * this quick action cannot drift from it.
   */
  enableDetection(): void {
    void this.setDetection(true);
  }

  /**
   * The single write path behind every Detect on/off affordance in this cockpit — the chip above and
   * `CvControlPanel#onDetectionEnabledToggle` alike (docs/plans/done/STREAM-STATE-PLAN.md §3.1), so the
   * two can never apply the same operator intent under two different rules.
   *
   * **No-op before a stream exists** (wave W7, H2 — the old draft this used to write is gone): there
   * is nothing to PATCH and nothing left to remember client-side for "the next Start" — the profile
   * hierarchy decides that now (§3.1). The running stream is PATCHed and then **re-read**
   * ({@link FleetStore.refresh} + {@link refreshStreamConfig}) rather than assumed: {@link
   * detectionOn}/{@link resolvedCvConfig} render the wire, so the switch moves when the backend says
   * it moved and not a moment sooner. A failed PATCH therefore leaves the control exactly where the
   * stream really is — nothing on screen claims a live change that did not happen.
   */
  async setDetection(enabled: boolean): Promise<void> {
    const streamId = this.stream()?.streamId;
    const current = this.resolvedCvConfig();
    if (!streamId || !current) {
      return;
    }
    this.detectionPending.set(true);
    try {
      await this.fleet.patchStreamConfig(streamId, buildHotKnobPatch({ ...current, detectionEnabled: enabled }));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.loadStreamConfig(streamId)]);
      // A CAMERA-guarded write (§3.3, wave W4) — see {@link followTrack}'s own doc comment on the
      // identical seat-refresh choke point.
      this.seats.refreshNow();
    } finally {
      this.detectionPending.set(false);
    }
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
      // No settings argument (wave W7, H2) — the server resolves the CV config from the profile
      // hierarchy (§3.1: PLATFORM → ORGANIZATION → CATEGORY → ASSET → SESSION), never from a
      // browser-local draft this app no longer keeps.
      await this.fleet.start(device.id);
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

  // --- Asset session (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4) -----------------
  // A separate `sessionBusy` signal, not `busy` above — engaging/disengaging is a distinct verb from
  // Start/Stop and must not disable that button (or read as "stream busy") while it runs.

  readonly sessionBusy = signal(false);

  /**
   * `rc-monitor.ts`'s "Engage link" button. No confirm step — same posture as {@link start}, and
   * for the same reason: this is a calm, reversible act, not a destructive one. Re-runs
   * {@link loadAsset} on success so `operatorEngaged` flips as soon as the write actually lands,
   * rather than leaving the operator staring at a stale "Engage link" for up to the 5s poll period
   * — the fact still only ever comes from that same read, never from this call's own response.
   */
  async engageSession(): Promise<void> {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return;
    }
    this.sessionBusy.set(true);
    try {
      const ok = await this.fleet.engageAsset(assetId);
      if (ok) {
        await this.loadAsset(assetId);
      }
    } finally {
      this.sessionBusy.set(false);
    }
  }

  /** `rc-monitor.ts`'s "End session" button. Same immediate-reload reasoning as {@link engageSession}. */
  async endSession(): Promise<void> {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return;
    }
    this.sessionBusy.set(true);
    try {
      const ok = await this.fleet.disengageAsset(assetId);
      if (ok) {
        await this.loadAsset(assetId);
      }
    } finally {
      this.sessionBusy.set(false);
    }
  }

  // --- Events ticker ---------------------------------------------------------------------------

  tickerLabel(event: DetectionEvent): string {
    return `${capitalizeLabel(event.label)} · ${formatConfidence(event.peakConfidence)}`;
  }

  // --- Map inset / boxes-mode toggles (docs/plans/done/UI-ARCHITECTURE-PLAN.md — persisted, non-exclusive,
  // so plain facade commands rather than `UiStore`) -------------------------------------------

  toggleMapVisible(): void {
    this.mapVisible.update((visible) => !visible);
  }

  hideMap(): void {
    this.mapVisible.set(false);
  }

  cycleBoxes(): void {
    this.boxesMode.update((current) => cycleBoxesMode(current));
  }

  /** Fed from `CockpitPage`'s own route-bound `watch` input — see this class's own doc comment. */
  setWatch(watch: string | undefined): void {
    this.watchSignal.set(watch);
  }

  /** Fed from `CockpitPage`'s own route-bound `autostart` input — see {@link autostartSignal}'s own
   * doc comment. */
  setAutostart(autostart: string | undefined): void {
    this.autostartSignal.set(autostart);
  }

  // --- Drawings (docs/plans/done/MAP-REWORK-PLAN.md §5.2) ---------------------------------------------------

  /** `<vision-tactical-map>`'s `(drawingCompleted)` — the map only ever emits a shape that already passes `Drawing`'s own minimum-point rule, so this is a straight `POST`. */
  async completeDrawing(draft: DrawingDraft): Promise<void> {
    await this.drawings.completeDraft(draft);
  }

  /** `(drawingSelected)` — the same selection the toolbar's editor reads. */
  selectDrawing(drawingId: string): void {
    this.drawings.select(drawingId);
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

  // --- Resolved CV config (docs/plans/active/CV-SETTINGS-PLAN.md §3, wave W7) -------------------
  // Two independent reads merged by `resolveCvConfig` — a failure on either degrades honestly to
  // `undefined` for that half rather than fabricating a value (CLAUDE.md).

  private async loadEffectiveProfile(assetId: string): Promise<void> {
    try {
      const profile = await this.api.getEffectiveCvProfile(assetId);
      this.effectiveProfile.set(profile);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not load the effective CV profile for ${assetId} — "From profile" line stays honest`, {
        error,
      });
      this.effectiveProfile.set(undefined);
    }
  }

  private async loadStreamConfig(streamId: string): Promise<void> {
    try {
      const config = await this.api.getStreamConfig(streamId);
      this.streamConfig.set(config);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read back live config for stream ${streamId} — H6 readback stays honest`, {
        error,
      });
      this.streamConfig.set(undefined);
    }
  }

  /** `<vision-cv-setup-modal>`'s `(profileSaved)` — re-reads the asset's effective profile after an explicit save. */
  refreshEffectiveProfile(): void {
    const assetId = this.activeAssetId();
    if (assetId) {
      void this.loadEffectiveProfile(assetId);
    }
  }

  /** `(configChanged)` from any CV control that just PATCHed the stream — the H6 readback after a
   * write. Also the one choke point every CV write path funnels through (`<vision-cv-control-panel>`/
   * `<vision-detections-strip>`/`<vision-cv-setup-modal>` alike), so `seats.refreshNow()` here
   * (docs/plans/active/CREW-CONTROL-PLAN.md §3.3, wave W4) covers every write those components make
   * themselves without this facade needing its own copy of each one — mirrors `features/crew/
   * crew-facade.ts#refreshStreamConfig`'s identical reasoning. A write that 409'd because the camera
   * seat was preempted between two polls (should never happen to this pilot per §3.2 rule 3, but a
   * 409 on *any other* cause still deserves a fresh seat read rather than a stale "mine") flips the
   * dock/drawer to the correct posture on the very next tick rather than the ordinary ~3s cadence. */
  refreshStreamConfig(): void {
    const streamId = this.stream()?.streamId;
    if (streamId) {
      void this.loadStreamConfig(streamId);
    }
    this.seats.refreshNow();
  }

  /**
   * `cockpit.html`'s own "Take camera" button (docs/plans/active/CREW-CONTROL-PLAN.md §3.5, wave W4)
   * — legibility, not a requirement: any CV write this pilot makes already preempts a crew member's
   * camera seat server-side (§3.2 rule 3), so this button exists purely so the state is visible and
   * actionable without first touching an unrelated control. `POST .../seats/camera` by the
   * flight-seat holder never 409s (§3.6), so the `catch` below is defense-in-depth, not an expected
   * path — either way, `seats.refreshNow()` re-reads the truth rather than assuming the request's own
   * outcome, the same posture every other seat-observing call in this file takes.
   */
  async takeCameraSeat(): Promise<void> {
    const assetId = this.activeAssetId();
    if (!assetId) {
      return;
    }
    this.cameraSeatPending.set(true);
    try {
      await this.api.takeAssetSeat(assetId, 'CAMERA');
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not take the camera seat for ${assetId}`, { error });
    } finally {
      this.seats.refreshNow();
      this.cameraSeatPending.set(false);
    }
  }
}
