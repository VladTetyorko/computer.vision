import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FleetStore } from '../../core/fleet-store';
import { EventsStore } from '../../core/events-store';
import { buildTestDroneRequest } from '../devices/simulate-logic';
import { FlightPlanDialog } from '../../ui/flight-plan-dialog';
import { buildTelemetryRequest, type FlightPlanForm } from '../../ui/flight-plan-logic';
import { FleetMap } from './fleet-map';
import { LiveDock } from './live-dock';
import { FleetMapStore } from './map-store';
import { bucketForAsset, type MarkerBucket } from './map-logic';
import type { AssetSummary } from '../../core/api/models';

/**
 * The `/map` fleet overview tab (docs/CYCLES-PLAN.md §6): the referee's tournament overview and
 * the crew's "where is it" view, one Leaflet map for the whole fleet rather than one device at a
 * time like `/live/:deviceId`.
 *
 * Owns a page-scoped `FleetMapStore` (own `providers`, like `LivePage`'s `TelemetryStore`) so its
 * 5s asset poll and every per-asset 2s telemetry poll start and stop with the route. Composes
 * `FleetMap` (the actual Leaflet rendering) plus a right-hand rail listing **every** registered
 * asset (docs/CYCLES-PLAN.md §11 — broadened from CU-b's "no position yet"-only rail so a referee
 * can account for the whole fleet from one list, not just the ones missing a position), with an
 * "Open" link to that asset's `/assets/:id` detail page alongside Watch/Preview.
 *
 * **Docked live preview** (docs/CYCLES-PLAN.md §9, CU-b item 5): `dockedAssetId` tracks at most
 * one asset at a time (bandwidth) — set by `FleetMap`'s `(preview)` output (a streaming marker
 * click, or its popup's Preview button) or the rail's own Preview button, cleared by
 * `LiveDock`'s close button. Resolving *which device* to preview is `FleetMapStore`'s job
 * (`resolveWatchDevice`, the same lookup `onWatch` already used), same split as Watch.
 */
@Component({
  selector: 'vision-map',
  imports: [FleetMap, LiveDock, RouterLink, FlightPlanDialog],
  templateUrl: './map.html',
  styleUrl: './map.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [FleetMapStore],
})
export class MapPage {
  private readonly router = inject(Router);
  private readonly fleet = inject(FleetStore);
  private readonly events = inject(EventsStore);
  protected readonly mapStore = inject(FleetMapStore);

  constructor() {
    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — see `EventsStore`'s own doc
    // comment: this is one of exactly three pages that keeps the shared events poll alive, which
    // is what backs `FleetMap`'s own event-marker layer (it injects `EventsStore` directly, since
    // this page already owns the activate/release lifecycle for it).
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());
  }

  protected readonly isEmpty = computed(() => this.mapStore.assets().length === 0);

  /**
   * The right rail lists **every** asset, not only the unpositioned ones (docs/CYCLES-PLAN.md
   * §11: "CD adds nothing map-side beyond the rail listing all assets, not only unpositioned
   * ones" — the left-side layer control and the rail's existence are already CU-b items 5–6).
   */
  protected readonly railAssets = computed(() => this.mapStore.assets());

  protected readonly dockedAssetId = signal<string | null>(null);
  protected readonly dockedDeviceId = signal<string | null>(null);
  protected readonly addingTestDrone = signal(false);
  protected readonly flightPlanDialogOpen = signal(false);

  /** The docked asset's freshest marker, re-derived every tick — feeds `LiveDock`'s OSD summary. */
  protected readonly dockedMarker = computed(() => {
    const assetId = this.dockedAssetId();
    return assetId ? this.mapStore.markers().find((marker) => marker.assetId === assetId) : undefined;
  });

  protected bucketFor(asset: AssetSummary): MarkerBucket {
    return bucketForAsset(asset);
  }

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

  /** Docks `assetId`'s live preview beside the map — replaces whichever asset was docked before. */
  protected async onPreview(assetId: string): Promise<void> {
    const device = await this.mapStore.resolveWatchDevice(assetId);
    if (device) {
      this.dockedAssetId.set(assetId);
      this.dockedDeviceId.set(device.id);
    }
  }

  protected closeDock(): void {
    this.dockedAssetId.set(null);
    this.dockedDeviceId.set(null);
  }

  protected openAsset(assetId: string): Promise<boolean> {
    return this.router.navigate(['/assets', assetId]);
  }

  /** `FleetMap`'s event-popup "Open asset" button (docs/MVP2-PLAN.md §E, E-b bullet 3). */
  protected openEventAsset(assetId: string): Promise<boolean> {
    return this.openAsset(assetId);
  }

  /**
   * The Map tab's empty-state action (docs/CYCLES-PLAN.md §9, CU-b item 7): one click places a
   * moving synthetic drone with no video file (CU-a) — the fastest possible way to see the map
   * actually plot something with no hardware and no file path to type in. Unchanged by CT-b
   * (docs/CYCLES-PLAN.md §7) on purpose — this stays the true one-click path (a bare circular
   * default track); `openFlightPlanDialog`/`onFlightPlanSaved` below are the *second*,
   * flight-plan-aware door the same empty state now also offers.
   */
  protected async addTestDrone(): Promise<void> {
    this.addingTestDrone.set(true);
    try {
      await this.fleet.simulate(buildTestDroneRequest({ name: '', latitude: null, longitude: null, autoStart: true }));
      await this.mapStore.refresh();
    } finally {
      this.addingTestDrone.set(false);
    }
  }

  /**
   * The flight-plan-aware door (docs/CYCLES-PLAN.md §7, CT-b): opens the exact same
   * `<vision-flight-plan-dialog>` the Devices page's Simulate step uses (`ui/flight-plan-dialog.ts`
   * — "no page imports another page's module", see that file's own doc comment) so a referee/crew
   * member can place a test drone that actually flies a specific route, not just the default
   * circle, without leaving the Map tab.
   */
  protected openFlightPlanDialog(): void {
    this.flightPlanDialogOpen.set(true);
  }

  protected async onFlightPlanSaved(plan: FlightPlanForm): Promise<void> {
    this.flightPlanDialogOpen.set(false);
    this.addingTestDrone.set(true);
    try {
      await this.fleet.simulate(
        buildTestDroneRequest({
          name: '',
          latitude: null,
          longitude: null,
          autoStart: true,
          telemetry: buildTelemetryRequest(plan),
        }),
      );
      await this.mapStore.refresh();
    } finally {
      this.addingTestDrone.set(false);
    }
  }

  protected onFlightPlanCancelled(): void {
    this.flightPlanDialogOpen.set(false);
  }
}
