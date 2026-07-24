import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player } from '../../shared/player/player';
import { FleetStore } from '../../core/fleet/fleet-store';
import type { FleetMarker } from '../../core/map/map-logic';

/**
 * The docked live preview panel (docs/CYCLES-PLAN.md §9, CU-b item 5): clicking a streaming
 * asset's marker or rail row docks this beside the map instead of leaving the tab — inline
 * player + an OSD-style summary + a "Watch live" link (docs/UX-REWORK-PLAN.md U-a2 item 1 — was
 * "Open full cockpit"; same verb as the dock itself, a fresh `/live/:deviceId` page instead of
 * this inline panel is a presentation detail, not a new verb) for the full-page experience. Each
 * host page (`MapPage`, and now `CommandPage`, docs/MVP3-PLAN.md §C-c) guarantees at most one is
 * ever rendered at a time (bandwidth); closing it emits `close` and the host un-docks.
 *
 * Moved here from `pages/map/live-dock.ts` in docs/MVP3-PLAN.md §C-c alongside `shared/map/fleet-map.ts`
 * (see that file's own doc comment) — Command's own "hover/click preview, one at a time, LiveDock
 * precedent" bullet reuses this unmodified.
 *
 * Reuses the shared `<vision-player>` (the same component `WallTile`/`LivePage` use — "Player
 * component reused cleanly across wall-tile/live/map-panel" per the cycle's done-when list) for
 * the video itself. The telemetry summary is **not** a fresh `TelemetryStore` instance: this
 * panel is only ever shown for an asset `FleetMapStore` already bucketed `streaming`, which means
 * a `FleetMarker` — carrying battery/altitude/heading/age already — exists one level up in
 * `MapPage`. Passing it in as `marker` avoids a second, redundant 2s telemetry poller for the
 * same asset the map is already polling.
 */
@Component({
  selector: 'vision-live-dock',
  imports: [Player, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './live-dock.html',
  styleUrl: './live-dock.css',
})
export class LiveDock {
  readonly deviceId = input.required<string>();
  readonly marker = input<FleetMarker | undefined>();

  readonly close = output<void>();

  protected readonly fleet = inject(FleetStore);

  protected readonly device = computed(() => this.fleet.device(this.deviceId()));
  protected readonly stream = computed(() => this.fleet.streamFor(this.deviceId()));

  protected readonly batteryLabel = computed(() => {
    const percent = this.marker()?.batteryPercent;
    return percent === undefined ? null : `${percent.toFixed(0)}%`;
  });

  protected readonly altitudeLabel = computed(() => {
    const meters = this.marker()?.position.altitudeMeters;
    return meters === undefined ? null : `${meters.toFixed(0)} m`;
  });

  protected readonly headingLabel = computed(() => {
    const heading = this.marker()?.headingDegrees;
    return heading === undefined ? null : `${heading.toFixed(0)}°`;
  });

  protected readonly ageLabel = computed(() => {
    const age = this.marker()?.sampleAgeSeconds;
    return age === undefined ? null : `${age.toFixed(0)}s ago`;
  });
}
