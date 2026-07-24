import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { buildTestDroneRequest } from '../../core/fleet/simulation-logic';
import { FleetMap } from '../../shared/map/fleet-map';
import { LiveDock } from '../../shared/map/live-dock';
import { FleetMapStore } from '../../core/map/map-store';
import { bucketForAsset, type MarkerBucket } from '../../core/map/map-logic';
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
 * can account for the whole fleet from one list, not just the ones missing a position), with a
 * "Details" link to that asset's `/assets/:id` detail page alongside "Watch live".
 *
 * **Verb dictionary (docs/UX-REWORK-PLAN.md U-a2 item 1):** the rail's old three-way Watch/
 * Preview/Open button set is now exactly two labels everywhere on this page — "Watch live" (this
 * page's own full-page `onWatch`, `FleetMap`'s inline-docking `onPreview`, and its popup's
 * full-page button all read "Watch live"; which behavior a given "Watch live" button triggers is
 * a presentation detail — inline dock vs. a fresh page — not a separate verb) and "Details"
 * (`openAsset`, the asset's own `/assets/:id` page). See each method's own doc comment below for
 * which of the two "Watch live" behaviors it is.
 *
 * **Docked live preview** (docs/CYCLES-PLAN.md §9, CU-b item 5): `dockedAssetId` tracks at most
 * one asset at a time (bandwidth) — set by `FleetMap`'s `(preview)` output (a streaming marker
 * click, or its popup's own "Watch live" button) or the rail's own "Watch live" button (shown only
 * while streaming), cleared by `LiveDock`'s close button. Resolving *which device* to preview is
 * `FleetMapStore`'s job (`resolveWatchDevice`, the same lookup `onWatch` already used), same split
 * as the full-page "Watch live".
 */
@Component({
  selector: 'vision-map',
  imports: [FleetMap, LiveDock, RouterLink],
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

  /** The docked asset's freshest marker, re-derived every tick — feeds `LiveDock`'s OSD summary. */
  protected readonly dockedMarker = computed(() => {
    const assetId = this.dockedAssetId();
    return assetId ? this.mapStore.markers().find((marker) => marker.assetId === assetId) : undefined;
  });

  protected bucketFor(asset: AssetSummary): MarkerBucket {
    return bucketForAsset(asset);
  }

  /**
   * The full-page "Watch live" (docs/UX-REWORK-PLAN.md U-a2 item 1) — shared by the rail's own
   * button and `FleetMap`'s popup `(watch)` output. Resolving the device is `FleetMapStore`'s job,
   * navigating is this page's, same split `features/devices/devices.ts` uses for its own watch
   * toast action.
   */
  protected async onWatch(assetId: string): Promise<void> {
    const device = await this.mapStore.resolveWatchDevice(assetId);
    if (device) {
      await this.router.navigate(['/live', device.id]);
    }
  }

  /**
   * The inline "Watch live" (docs/UX-REWORK-PLAN.md U-a2 item 1 — same label as `onWatch` above,
   * a docked mini-player instead of a fresh page is a presentation detail, not a new verb): docks
   * `assetId`'s live preview beside the map, replacing whichever asset was docked before.
   */
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

  /** The rail's "Details" button (docs/UX-REWORK-PLAN.md U-a2 item 1 — was "Open"). */
  protected openAsset(assetId: string): Promise<boolean> {
    return this.router.navigate(['/assets', assetId]);
  }

  /** `FleetMap`'s event-popup "Details" button (docs/MVP2-PLAN.md §E, E-b bullet 3). */
  protected openEventAsset(assetId: string): Promise<boolean> {
    return this.openAsset(assetId);
  }

  /**
   * The Map tab's empty-state action (docs/CYCLES-PLAN.md §9, CU-b item 7): one click places a
   * moving synthetic drone with no video file (CU-a) — the fastest possible way to see the map
   * actually plot something with no hardware and no file path to type in. This is now the empty
   * state's only action beyond "Go to Devices" (docs/UX-REWORK-PLAN.md U-a item 3 removed the
   * second "Draw a flight plan…" door — a demo action judged not worth its own button on this
   * referee-facing surface; `shared/map/flight-plan-dialog.ts` is untouched and still reused
   * as-is by the Devices page's own Simulate step).
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
}
