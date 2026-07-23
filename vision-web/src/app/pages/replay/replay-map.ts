import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import type { GeoPosition } from '../../core/api/models';
import { SettingsStore, type MapLayerId } from '../../core/settings-store';
import { MAP_LAYERS, droneDivIcon, ensureLeafletStylesheet, importLeaflet, mapLayerTileLayer } from '../../ui/leaflet-loader';

const DEFAULT_ZOOM = 17;

/**
 * The flight-replay cockpit's map (docs/MVP2-PLAN.md §R, R-b) — a breadcrumb trail drawn up to the
 * scrub position plus a heading-rotated marker at that position. Purely presentational: driven by
 * `[trail]`/`[markerPosition]`/`[markerHeadingDegrees]` inputs from `ReplayPage`'s own scrub-time
 * derivations (`replay-logic.ts#trailPrefix`/`nearestSample`), not a store — replay has no live
 * poller to DI-share the way `ui/live-map.ts` shares `TelemetryStore`.
 *
 * Reuses the shared Leaflet bootstrap (`ui/leaflet-loader.ts`: dynamic `import('leaflet')`, the
 * runtime stylesheet injection, the four-layer `MAP_LAYERS` switcher, the rotated `droneDivIcon`)
 * exactly like `ui/live-map.ts`/`pages/map/fleet-map.ts`/`ui/flight-plan-dialog.ts` — a fourth home
 * for the same shared module, per its own "intra-app DRY" doc comment. Lives in `pages/replay/`
 * rather than `ui/` since only this page uses it, mirroring `ui/live-map.ts`'s own original home
 * (`pages/live/live-map.ts`) before a second page needed it — see that module's doc comment for
 * the "move to `ui/` only once a second page actually needs it" precedent this follows.
 *
 * Unlike `LiveMap`'s always-on auto-follow, this map fits the view to the *whole* route once
 * (`[fullTrail]`, independent of the scrub-bounded `[trail]`) so a referee sees the entire flight's
 * shape immediately — replay is reviewed, not chased. `autoFollow` (default off, a toggle mirroring
 * `LiveMap`'s own) optionally recenters on the marker as the scrub position moves; "Fit route"
 * re-runs the initial fit on demand after the user has panned/zoomed away.
 */
@Component({
  selector: 'vision-replay-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './replay-map.html',
  styleUrl: './replay-map.css',
})
export class ReplayMap {
  /** The whole usage's positioned trail, independent of the scrub position — fitted once on load. */
  readonly fullTrail = input<readonly GeoPosition[]>([]);
  /** The trail drawn up to the scrub position — replaces on every scrub tick. */
  readonly trail = input<readonly GeoPosition[]>([]);
  /** The marker's position at the scrub time, or `undefined` before any positioned sample exists. */
  readonly markerPosition = input<GeoPosition | undefined>(undefined);
  readonly markerHeadingDegrees = input<number | undefined>(undefined);

  protected readonly settings = inject(SettingsStore);
  protected readonly layers = MAP_LAYERS;
  protected readonly tilesOk = signal(true);
  protected readonly autoFollow = signal(false);

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private marker: Leaflet.Marker | null = null;
  private trailLine: Leaflet.Polyline | null = null;
  private hasFitRoute = false;
  private generation = 0;

  constructor() {
    afterNextRender(() => void this.initMap());

    effect(() => {
      const trail = this.trail();
      const marker = this.markerPosition();
      const heading = this.markerHeadingDegrees();
      const follow = this.autoFollow();
      this.applyScrub(trail, marker, heading, follow);
    });

    effect(() => this.fitRoute(this.fullTrail()));

    effect(() => this.applyLayer(this.settings.mapLayer()));

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  protected setLayer(id: MapLayerId): void {
    this.settings.mapLayer.set(id);
  }

  protected toggleFollow(): void {
    this.autoFollow.update((value) => !value);
  }

  /** Re-runs the initial route fit — useful after the user has panned/zoomed away from it. */
  protected refit(): void {
    this.hasFitRoute = false;
    this.fitRoute(this.fullTrail());
  }

  private async initMap(): Promise<void> {
    const generation = ++this.generation;
    const L = await importLeaflet();
    if (generation !== this.generation) {
      return; // destroyed before the chunk finished loading
    }
    this.leaflet = L;
    ensureLeafletStylesheet();

    const map = L.map(this.mapHost().nativeElement, { center: [0, 0], zoom: 2 });
    this.map = map;
    this.applyLayer(this.settings.mapLayer());

    this.trailLine = L.polyline([], { color: '#4f8cff', weight: 3, opacity: 0.85 }).addTo(map);
    this.marker = L.marker([0, 0], { icon: droneDivIcon(L, 0), opacity: 0, keyboard: false }).addTo(map);

    this.fitRoute(this.fullTrail());
    this.applyScrub(this.trail(), this.markerPosition(), this.markerHeadingDegrees(), this.autoFollow());
  }

  private applyScrub(
    trail: readonly GeoPosition[],
    marker: GeoPosition | undefined,
    headingDegrees: number | undefined,
    follow: boolean,
  ): void {
    const L = this.leaflet;
    if (!L || !this.map) {
      return; // map chunk/instance not ready yet — initMap() re-applies once it is
    }

    this.trailLine?.setLatLngs(trail.map((position) => L.latLng(position.latitude, position.longitude)));

    if (!marker || !this.marker) {
      this.marker?.setOpacity(0);
      return;
    }
    const point = L.latLng(marker.latitude, marker.longitude);
    this.marker.setLatLng(point);
    this.marker.setIcon(droneDivIcon(L, headingDegrees ?? 0));
    this.marker.setOpacity(1);
    if (follow) {
      this.map.panTo(point, { animate: false });
    }
  }

  /** Fits the view to the whole route once it first has ≥1 point — a no-op on later, smaller changes. */
  private fitRoute(fullTrail: readonly GeoPosition[]): void {
    const L = this.leaflet;
    if (!L || !this.map || this.hasFitRoute || fullTrail.length === 0) {
      return;
    }
    this.hasFitRoute = true;
    if (fullTrail.length === 1) {
      this.map.setView(L.latLng(fullTrail[0].latitude, fullTrail[0].longitude), DEFAULT_ZOOM);
      return;
    }
    const bounds = L.latLngBounds(fullTrail.map((position) => L.latLng(position.latitude, position.longitude)));
    this.map.fitBounds(bounds, { padding: [24, 24] });
  }

  /** Swaps the active base layer — a no-op until the map exists (`initMap()` re-applies once it does). */
  private applyLayer(layerId: MapLayerId): void {
    const L = this.leaflet;
    if (!L || !this.map) {
      return;
    }
    this.tileLayer?.remove();
    this.tileLayer = mapLayerTileLayer(L, layerId, (ok) => this.tilesOk.set(ok));
    this.tileLayer.addTo(this.map);
  }

  private teardown(): void {
    this.generation++;
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
    this.marker = null;
    this.trailLine = null;
  }
}
