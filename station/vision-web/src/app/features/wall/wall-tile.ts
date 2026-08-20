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
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player, type BoxesMode } from '../../shared/player/player';
import { cycleBoxesMode } from '../../shared/player/detection-overlay-logic';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import type { ActiveStream, Device } from '../../core/api/models';

/** Start decoding slightly before a tile scrolls into view, so it is ready on arrival. */
const PREROLL_MARGIN = '250px';

/**
 * One live tile.
 *
 * Suspends its player while off-screen: a 30-camera wall that decodes every tile at once
 * saturates the CPU and drops frames on the tiles the user is actually looking at
 * (docs/plans/done/WEB-PLAN.md, W6).
 *
 * Also carries its own `TelemetryStore`/`DetectionsStore` (docs/main/CYCLES-PLAN.md §2,
 * docs/main/CYCLES-PLAN.md §11 item 6): a tile only exists for a device that is currently streaming
 * (`WallPage` builds `tiles()` from `fleet.streams()`), so "only show telemetry/detections while
 * the tile's stream is live" is automatic — what this component adds is gating both polls on
 * on-screen visibility too, the same idea as suspending the player, so a 30-tile wall doesn't run
 * 30 telemetry/detections pollers for tiles nobody is looking at (the O(visible) posture item 4
 * asks for). The per-tile "boxes: overlay/off" toggle (item 6) is a tiny cycling button rather
 * than a labeled toggle group — there is no room for one at wall-tile scale.
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, DetectionsStore],
  templateUrl: './wall-tile.html',
  styleUrl: './wall-tile.css',
})
export class WallTile {
  readonly stream = input.required<ActiveStream>();
  readonly device = input<Device | undefined>();

  protected readonly visible = signal(true);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);

  /** Defaults to `'overlay'` — burn-in no longer exists at all (docs/plans/active/CV-CLEAN-FEED-PLAN.md
   * D-1), so there is nothing left to re-derive against the stream. A plain `signal`, not the old
   * `linkedSignal` over a derived `streamBurnedIn` primitive — see `CockpitFacade#boxesMode`'s
   * identical simplification. */
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  private readonly hasTelemetryCapability = computed(() =>
    (this.device()?.capabilities ?? []).includes('TELEMETRY'),
  );

  protected cycleBoxesMode(): void {
    this.boxesMode.update((current) => cycleBoxesMode(current));
  }

  protected readonly batteryLabel = computed(() => {
    const percent = this.telemetry.latest()?.batteryPercent;
    return percent === undefined ? null : `${percent.toFixed(0)}%`;
  });

  protected readonly altitudeLabel = computed(() => {
    const meters = this.telemetry.latest()?.altitudeMeters;
    return meters === undefined ? null : `${meters.toFixed(0)}m`;
  });

  /** The FC's own human mode name (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) — no badge at all until one is reported. */
  protected readonly modeLabel = computed(() => this.telemetry.latest()?.flightState?.mode ?? null);
  protected readonly failsafe = computed(() => this.telemetry.latest()?.flightState?.failsafe === true);

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());

    // Detections poll the same way — on-screen only (docs/main/CYCLES-PLAN.md §11 item 4/6), no
    // capability gate (any streaming device can have CV running on its stream).
    effect(() => {
      if (this.visible()) {
        this.detections.track(this.stream().streamId);
      } else {
        this.detections.reset();
      }
    });

    // Poll only for a telemetry-capable device that is actually on-screen — off-screen tiles
    // already stop decoding video (above), so they stop polling telemetry too.
    // No `assetId` to pass here (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3) — `stream: ActiveStream`
    // carries no asset id, only a bare `deviceId`; `TelemetryStore` falls back to its own
    // list-then-find lookup for this call site, unchanged.
    effect(() => {
      const deviceId = this.stream().deviceId;
      if (this.hasTelemetryCapability() && this.visible()) {
        this.telemetry.track(deviceId);
      } else {
        this.telemetry.reset();
      }
    });
  }
}
