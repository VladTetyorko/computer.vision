import { DestroyRef, Injectable, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { Router } from '@angular/router';
import type { BoxesMode, Transport } from '../../shared/player/player';
import { defaultBoxesMode, resolveBurnedIn } from '../../shared/player/detection-overlay-logic';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { GeofenceStore } from '../../core/geofence/geofence-store';
import { MarksStore } from '../../core/map-data/marks-store';
import { LayersStore } from '../../core/map-data/layers-store';
import { DrawingsStore } from '../../core/map-data/drawings-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { followMarkers } from '../../shared/map/tactical-map/tactical-map-logic';

/** Panel-state memory (docs/plans/done/UX-REWORK-PLAN.md §U-b item 7) — the two toggles below already
 * existed; only the localStorage key names are new. See `core/panel-state.ts`'s own doc comment
 * for why this isn't routed through `SettingsStore`. */
const RAIL_OPEN_KEY = 'vision.live.railOpen';
const MAP_INSET_VISIBLE_KEY = 'vision.live.mapInsetVisible';

/**
 * `LivePage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — orchestrates `FleetStore`/`SettingsStore`/
 * `TelemetryStore`/`DetectionsStore`/`Router`, exactly what the page injected directly before this
 * refactor. `TelemetryStore`/`DetectionsStore` stay listed in `LivePage`'s own `providers` array
 * (unchanged) alongside this facade, so this facade and the page's child components
 * (`<vision-telemetry-osd>`, `<vision-detections-strip>`, `<vision-tactical-map>`, `<vision-stream-info>`)
 * still DI-share the exact same store instances as before — only *who injects them* moved.
 *
 * `railOpen`/`mapInsetVisible` are non-exclusive toggles (persisted, but not mutually exclusive with
 * anything else on this page), so per docs/plans/done/UI-ARCHITECTURE-PLAN.md they stay plain signals here
 * rather than a `UiStore` group — this page has no two overlays that could ever conflict.
 *
 * `deviceId` is a route-bound input and must stay on the page (Angular requirement); `bindDeviceId`
 * is how the page feeds it in — see `LivePage`'s own constructor.
 */
@Injectable()
export class LiveFacade {
  private readonly router = inject(Router);
  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsStore);
  readonly telemetry = inject(TelemetryStore);
  readonly detections = inject(DetectionsStore);

  /**
   * The two shared operational-picture stores (both `providedIn: 'root'`, started at boot) —
   * **new here** (docs/plans/done/MAP-REWORK-PLAN.md §5.1 Wave D's own bug fix): this page's map inset used to
   * silently drop zones and marks, showing the operator a different picture from the one Command and
   * the Fly cockpit were looking at. `<vision-tactical-map>` now gets both, read-only for zones and
   * click/drag-interactive for marks, exactly as the other two hosts do.
   */
  readonly geofence = inject(GeofenceStore);
  readonly marks = inject(MarksStore);
  /** Layers name the map's data-layer rows and colour COP marks; drawings are the same shared picture every other host shows (docs/plans/done/MAP-REWORK-PLAN.md §5.2). */
  readonly layers = inject(LayersStore);
  readonly drawings = inject(DrawingsStore);

  private readonly deviceIdSignal = signal<string | undefined>(undefined);

  readonly busy = signal(false);

  /**
   * Video-first layout (docs/main/CYCLES-PLAN.md §9, CU-b item 4): the player is the dominant surface;
   * OSD + detections strip + settings all live in a right rail that collapses to give the player
   * the full width. `mapInsetVisible` is a second, independent toggle — the map inset can be
   * hidden even while the rail stays open — with a keyboard shortcut (`M`, ignored while typing
   * in a form field) alongside its own button, since it is the one panel worth a fast toggle
   * mid-flight.
   */
  readonly railOpen = signal(readPersistedFlag(RAIL_OPEN_KEY, true));
  readonly mapInsetVisible = signal(readPersistedFlag(MAP_INSET_VISIBLE_KEY, true));

  /** The player's own measured seconds-behind-live, piped into `StreamInfoPanel` — see its doc comment. */
  readonly latencySeconds = signal<number | null>(null);

  /** The player's own live transport (docs/plans/done/MVP2-PLAN.md §L / §U3), piped into `StreamInfoPanel` too. */
  readonly transport = signal<Transport>('hls');

  readonly device = computed(() => {
    const deviceId = this.deviceIdSignal();
    return deviceId === undefined ? undefined : this.fleet.device(deviceId);
  });
  readonly stream = computed(() => {
    const deviceId = this.deviceIdSignal();
    return deviceId === undefined ? undefined : this.fleet.streamFor(deviceId);
  });
  readonly live = computed(() => this.stream() !== undefined);

  /** `stream()#burnedIn` projected to a primitive (docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8) —
   * see `CockpitFacade#streamBurnedIn`'s identical doc comment for why a primitive, not the whole
   * `stream()` object, gates `boxesMode`'s `linkedSignal` below. */
  private readonly streamBurnedIn = computed(() => this.stream()?.burnedIn);

  /** Per-tile "boxes: overlay/burned/off" toggle (docs/main/CYCLES-PLAN.md §11 item 6) — defaults to
   * `'burned'`, not `'overlay'` (per direct user request — `shared/player/player.ts`'s own
   * `boxesMode` input default matches for the same reason) **unless this stream is confirmed
   * burn-in-free**, in which case `'overlay'` is the only mode that shows anything
   * (docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8) — see `CockpitFacade#boxesMode`'s identical
   * `linkedSignal` doc comment for the full reasoning. */
  readonly boxesMode = linkedSignal<BoxesMode>(() => defaultBoxesMode(this.streamBurnedIn()));

  /** `live.html`'s own "Burned" button — hidden once {@link streamBurnedIn} is confirmed `false`
   * (docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8), same reasoning as
   * `CvControlPanel#showBurnedInOption`. */
  readonly showBurnedInOption = computed(() => resolveBurnedIn(this.streamBurnedIn()));

  // --- Deliberately-stopped state (docs/plans/done/MVP2-PLAN.md §S, S-b) ---------------------------------
  // `explicitlyStopped` is this page's own Stop action; `hasBeenLive` tracks whether *this page
  // instance* ever saw the stream live at all. Combined, `stopped` covers both the reported bug
  // (click Stop → calm terminal state, not a reconnect storm) and "someone else stopped it" —
  // `FleetStore`'s poll flips `live()` false on its own next tick, which this page has no
  // separate way to tell apart from "was deliberately stopped", so it's treated the same: once a
  // stream this page was watching disappears from the streams list, it is gone, not struggling.
  // A device that has simply never been started (`hasBeenLive` still `false`) stays the existing
  // plain `idle`/"Not streaming" state — that is not "stopped", it's "never asked to play".
  private readonly explicitlyStopped = signal(false);
  private readonly hasBeenLive = signal(false);
  readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  /**
   * Capability-driven panels: a fixed camera and a drone are not the same viewing
   * experience, and `Device.capabilities` already says which is which
   * (docs/main/UX-DESIGN.md §5.2).
   */
  readonly hasTelemetry = computed(() => (this.device()?.capabilities ?? []).includes('TELEMETRY'));

  // --- Map inset (docs/plans/done/MAP-REWORK-PLAN.md §5.1 Wave D) ------------------------------------------
  // `<vision-tactical-map>` is dumb — the deleted `<vision-live-map>` read this facade's own
  // `TelemetryStore` through DI, the new one takes inputs — so the single followed marker is built
  // here from the same trail/latest signals. This route carries a bare `deviceId` and no asset
  // context at all, so the device's own id/name is what the marker is keyed and labelled by.

  /** Switches the map into follow mode; `null` (nothing to follow) before the device has loaded. */
  readonly mapFollowAssetId = computed(() => this.device()?.id ?? null);

  /** `<vision-tactical-map>`'s `[assets]` — 0 or 1 markers (empty until a position exists). */
  readonly mapAssets = computed(() => {
    const device = this.device();
    if (!device) {
      return [];
    }
    return followMarkers({
      assetId: device.id,
      displayName: device.name,
      trail: this.telemetry.trail(),
      latest: this.telemetry.latest(),
    });
  });

  readonly optionPairs = computed(() =>
    Object.entries(this.device()?.options ?? {}).map(([key, value]) => ({ key, value })),
  );

  /** docs/plans/done/CV-CONTROL-PLAN.md §4 — the roster is now data-driven (`GET /api/cv/models`, cached by
   * `FleetStore.models`), replacing the old hardcoded `DETECTION_MODEL_OPTIONS` array. */
  readonly modelOptions = computed(() => this.fleet.models());

  constructor() {
    // Panel state memory (docs/plans/done/UX-REWORK-PLAN.md §U-b item 7) — persists whenever either toggle
    // actually changes; the initial `signal()` value above already restored whatever was last
    // saved (or the existing default, on a first visit).
    effect(() => writePersistedFlag(RAIL_OPEN_KEY, this.railOpen()));
    effect(() => writePersistedFlag(MAP_INSET_VISIBLE_KEY, this.mapInsetVisible()));

    // Latches once `live()` is ever observed true — see `stopped`'s own doc comment above.
    effect(() => {
      if (this.live()) {
        this.hasBeenLive.set(true);
      }
    });

    // Only devices that declare TELEMETRY are worth looking up an asset/usage for at all —
    // `hasTelemetry()` flips true once `FleetStore` has loaded the device, so this also covers
    // the brief window before that first fetch resolves.
    // No `assetId` to pass here (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3) — this route only carries
    // a bare `deviceId`, no asset context; `TelemetryStore` falls back to its own list-then-find
    // lookup for this call site, unchanged.
    effect(() => {
      const deviceId = this.deviceIdSignal();
      if (deviceId === undefined) {
        return;
      }
      if (this.hasTelemetry()) {
        this.telemetry.track(deviceId);
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while a stream is actually running — there is no streamId to
    // poll otherwise (docs/plans/done/MVP1-PLAN.md §C8 bullet 4).
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        this.detections.track(streamId);
      } else {
        this.detections.reset();
      }
    });

    // `M` toggles the map inset (docs/main/CYCLES-PLAN.md §9, CU-b item 4) — ignored while a form
    // field has focus (typing "m" into the name/URI fields elsewhere in the app must not fight
    // this) and while the device has no telemetry to show a map for in the first place.
    const onKeydown = (event: KeyboardEvent): void => {
      if (event.key.toLowerCase() !== 'm' || event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
        return;
      }
      if (this.hasTelemetry()) {
        this.toggleMapInset();
      }
    };
    document.addEventListener('keydown', onKeydown);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('keydown', onKeydown));
  }

  /**
   * Fed from `LivePage`'s own route-bound `deviceId` input — see this class's own doc comment.
   *
   * **Resets the deliberately-stopped state on a device switch** (docs/plans/done/UI-STATE-PLAN.md §2 — a stale
   * value surviving into a context where it's wrong). `/live/:deviceId` is reachable from many direct
   * links to a *different* device while already on this route (the notification bell, Wall tiles,
   * Assets/Devices/asset-detail "Watch live", Replay's "Watch live") — Angular reuses this same routed
   * component/facade instance across such a navigation (only the param changes), so without this reset
   * a device the operator explicitly stopped stays latched `stopped()` (`explicitlyStopped`/
   * `hasBeenLive`'s own combined read-model, this class's doc comment above) after switching to a
   * brand-new device that was never touched — the player refuses to attach and shows "Stream stopped"
   * for a device that may be actively live (`shared/player/player.ts`'s own `stopped` input: "a
   * deliberately stopped stream must never attach"). Mirrors `CockpitFacade.selectAsset`'s identical
   * reset on its own equivalent asset-switch path — this facade was the one place that reset had gone
   * missing.
   */
  setDeviceId(deviceId: string): void {
    if (deviceId === this.deviceIdSignal()) {
      return;
    }
    this.deviceIdSignal.set(deviceId);
    this.explicitlyStopped.set(false);
    this.hasBeenLive.set(false);
  }

  toggleRail(): void {
    this.railOpen.update((open) => !open);
  }

  onLatency(seconds: number | null): void {
    this.latencySeconds.set(seconds);
  }

  onTransport(transport: Transport): void {
    this.transport.set(transport);
  }

  setBoxesMode(mode: BoxesMode): void {
    this.boxesMode.set(mode);
  }

  toggleMapInset(): void {
    this.mapInsetVisible.update((visible) => !visible);
  }

  async start(): Promise<void> {
    const device = this.device();
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

  back(): Promise<boolean> {
    return this.router.navigate(['/assets']);
  }

  onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  onModel(model: string): void {
    this.settings.adjust({ model });
  }
}
