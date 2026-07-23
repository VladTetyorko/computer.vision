import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player } from '../../ui/player';
import { FleetStore } from '../../core/fleet-store';
import type { FleetMarker } from './map-logic';

/**
 * The docked live preview panel (docs/CYCLES-PLAN.md §9, CU-b item 5): clicking a streaming
 * asset's marker or rail row docks this beside the map instead of leaving the tab — inline
 * player + an OSD-style summary + an "Open full cockpit" link for the full `/live/:deviceId`
 * experience. `MapPage` guarantees at most one is ever rendered at a time (bandwidth); closing it
 * emits `close` and `MapPage` un-docks.
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
