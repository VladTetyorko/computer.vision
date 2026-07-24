import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Player, type BoxesMode, type Transport } from '../../shared/player/player';
import { StreamInfoPanel } from '../../shared/player/stream-info-panel';
import { FleetStore } from '../../core/fleet/fleet-store';
import {
  DETECTION_MODEL_OPTIONS,
  SettingsStore,
  type DetectionModelId,
} from '../../core/settings/settings-store';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { TelemetryOsd } from './telemetry-osd';
import { LiveMap } from '../../shared/map/live-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';

/** Panel-state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — the two toggles below already
 * existed; only the localStorage key names are new. See `core/panel-state.ts`'s own doc comment
 * for why this isn't routed through `SettingsStore`. */
const RAIL_OPEN_KEY = 'vision.live.railOpen';
const MAP_INSET_VISIBLE_KEY = 'vision.live.mapInsetVisible';

@Component({
  selector: 'vision-live',
  imports: [Player, RouterLink, TelemetryOsd, LiveMap, DetectionsStrip, StreamInfoPanel],
  templateUrl: './live.html',
  styleUrl: './live.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation: the telemetry/detections polls start/stop with this page
  // (docs/CYCLES-PLAN.md §2, docs/MVP1-PLAN.md §C8) rather than as app-wide singletons like `FleetStore`.
  providers: [TelemetryStore, DetectionsStore],
})
export class LivePage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly deviceId = input.required<string>();

  private readonly router = inject(Router);
  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);

  protected readonly busy = signal(false);

  /**
   * Video-first layout (docs/CYCLES-PLAN.md §9, CU-b item 4): the player is the dominant surface;
   * OSD + detections strip + settings all live in a right rail that collapses to give the player
   * the full width. `mapInsetVisible` is a second, independent toggle — the map inset can be
   * hidden even while the rail stays open — with a keyboard shortcut (`M`, ignored while typing
   * in a form field) alongside its own button, since it is the one panel worth a fast toggle
   * mid-flight.
   */
  protected readonly railOpen = signal(readPersistedFlag(RAIL_OPEN_KEY, true));
  protected readonly mapInsetVisible = signal(readPersistedFlag(MAP_INSET_VISIBLE_KEY, true));

  /** The player's own measured seconds-behind-live, piped into `StreamInfoPanel` — see its doc comment. */
  protected readonly latencySeconds = signal<number | null>(null);

  /** The player's own live transport (docs/MVP2-PLAN.md §L / §U3), piped into `StreamInfoPanel` too. */
  protected readonly transport = signal<Transport>('hls');

  /** Per-tile "boxes: overlay/burned/off" toggle (docs/CYCLES-PLAN.md §11 item 6) — defaults to overlay. */
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  protected readonly device = computed(() => this.fleet.device(this.deviceId()));
  protected readonly stream = computed(() => this.fleet.streamFor(this.deviceId()));
  protected readonly live = computed(() => this.stream() !== undefined);

  // --- Deliberately-stopped state (docs/MVP2-PLAN.md §S, S-b) ---------------------------------
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
  protected readonly stopped = computed(() => this.explicitlyStopped() || (this.hasBeenLive() && !this.live()));

  /**
   * Capability-driven panels: a fixed camera and a drone are not the same viewing
   * experience, and `Device.capabilities` already says which is which
   * (docs/UX-DESIGN.md §5.2).
   */
  protected readonly hasTelemetry = computed(() =>
    (this.device()?.capabilities ?? []).includes('TELEMETRY'),
  );

  protected readonly optionPairs = computed(() =>
    Object.entries(this.device()?.options ?? {}).map(([key, value]) => ({ key, value })),
  );

  /** docs/CV-MODELS-PLAN.md item 4 — see `features/settings/settings.ts`'s file-level comment for
   * the gap between this picker and what actually reaches a running stream today. */
  protected readonly modelOptions = DETECTION_MODEL_OPTIONS;

  /** The hint for whichever model is currently selected — shown under the segmented control,
   * same "one static line under the control" shape as the confidence/fps hints below it. */
  protected readonly currentModelHint = computed(
    () =>
      this.modelOptions.find((option) => option.id === this.settings.effective().model)?.hint ?? '',
  );

  constructor() {
    // Panel state memory (docs/UX-REWORK-PLAN.md §U-b item 7) — persists whenever either toggle
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
    // No `assetId` to pass here (docs/REALTIME-PLAN.md Phase R-a item 3) — this route only carries
    // a bare `deviceId`, no asset context; `TelemetryStore` falls back to its own list-then-find
    // lookup for this call site, unchanged.
    effect(() => {
      const deviceId = this.deviceId();
      if (this.hasTelemetry()) {
        this.telemetry.track(deviceId);
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while a stream is actually running — there is no streamId to
    // poll otherwise (docs/MVP1-PLAN.md §C8 bullet 4).
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        this.detections.track(streamId);
      } else {
        this.detections.reset();
      }
    });

    // `M` toggles the map inset (docs/CYCLES-PLAN.md §9, CU-b item 4) — ignored while a form
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

  protected toggleRail(): void {
    this.railOpen.update((open) => !open);
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

  protected toggleMapInset(): void {
    this.mapInsetVisible.update((visible) => !visible);
  }

  protected async start(): Promise<void> {
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

  protected back(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  protected onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  protected onModel(model: DetectionModelId): void {
    this.settings.adjust({ model });
  }
}
