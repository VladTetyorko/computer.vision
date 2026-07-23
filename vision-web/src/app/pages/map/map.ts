import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FleetMap } from './fleet-map';
import { FleetMapStore } from './map-store';

/**
 * The `/map` fleet overview tab (docs/CYCLES-PLAN.md §6): the referee's tournament overview and
 * the crew's "where is it" view, one Leaflet map for the whole fleet rather than one device at a
 * time like `/live/:deviceId`.
 *
 * Owns a page-scoped `FleetMapStore` (own `providers`, like `LivePage`'s `TelemetryStore`) so its
 * 5s asset poll and every per-asset 2s telemetry poll start and stop with the route. Composes
 * `FleetMap` (the actual Leaflet rendering) plus a "no position yet" rail so a referee can
 * account for every registered asset, even the ones with nothing to plot.
 */
@Component({
  selector: 'vision-map',
  imports: [FleetMap, RouterLink],
  templateUrl: './map.html',
  styleUrl: './map.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [FleetMapStore],
})
export class MapPage {
  private readonly router = inject(Router);
  protected readonly mapStore = inject(FleetMapStore);

  protected readonly isEmpty = computed(() => this.mapStore.assets().length === 0);
  protected readonly noPositionAssets = computed(() => this.mapStore.buckets().noPosition);

  /**
   * Shared by the rail's own Watch buttons and `FleetMap`'s popup Watch action (`(watch)`
   * output) — resolving the device is `FleetMapStore`'s job, navigating is this page's, same
   * split `pages/devices/devices.ts` uses for its own Watch toast action.
   */
  protected async onWatch(assetId: string): Promise<void> {
    const device = await this.mapStore.resolveWatchDevice(assetId);
    if (device) {
      await this.router.navigate(['/live', device.id]);
    }
  }
}
