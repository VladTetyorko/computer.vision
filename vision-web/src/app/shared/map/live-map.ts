import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import type { GeoPosition, TelemetrySample } from '../../core/api/models';
import { SettingsStore, type MapLayerId } from '../../core/settings/settings-store';
import { MAP_LAYERS, droneDivIcon, ensureLeafletStylesheet, importLeaflet, mapLayerTileLayer } from './leaflet-loader';

const DEFAULT_ZOOM = 17;

/** Long enough to outlast the `.map-shell`/`.expanded` CSS transition (see live-map.css). */
const EXPAND_TRANSITION_MS = 260;

/**
 * Live single-asset map inset: drone marker rotated to heading, breadcrumb trail of the current
 * usage, start-point flag, auto-follow toggle, and an expand-to-full-pane control. DI-shares
 * whichever `TelemetryStore` instance its host page provides (docs/CYCLES-PLAN.md §2, UX-DESIGN
 * §5.2) — originally built for `/live/:deviceId`, reused as-is by the asset detail page
 * (docs/CYCLES-PLAN.md §11, CD-b item 2) once that page needed the identical map. Lives in
 * `shared/map/` (moved from `pages/live/`, CD-b) rather than `pages/live/` because of that second
 * host — this codebase has no precedent for one page importing another page's module (see
 * `core/fleet/device-logic.ts`'s doc comment for the original precedent that established this).
 *
 * **Leaflet loads only here** (well, here and `shared/map/fleet-map.ts`, docs/CYCLES-PLAN.md §6 —
 * the shared bootstrap lives in `shared/map/leaflet-loader.ts`). Each host route is already its own lazy
 * chunk; `initMap()`'s call to `importLeaflet()` — a *dynamic* `import('leaflet')` under the
 * hood, not a static one — additionally keeps Leaflet out of that chunk's own parse cost until a
 * telemetry-capable device is actually being viewed. Mirrors `shared/player/player.ts`'s `import('hls.js')`
 * idiom: dynamic import inside an async method, a `generation` counter to ignore a load
 * superseded by teardown, cleanup on destroy.
 *
 * **Zoneless gotcha (the risk CYCLES-PLAN.md §2 called out):** Leaflet is purely imperative and
 * is never bound in the template. `map`/`marker`/`trailLine`/`startFlag` are plain fields,
 * created once in `initMap()` and mutated from `effect()`s that read the `TelemetryStore`
 * injected from the host page's DI — Angular never re-renders Leaflet's own DOM.
 *
 * **Offline fallback.** `.map-shell` has a dark background by default (live-map.css), and
 * Leaflet's marker/overlay panes are independent of the tile pane, so a tile fetch failure (no
 * network at a flying field) just leaves that dark background showing through missing tiles
 * while the trail and marker — vector overlays — keep rendering with no special-case code.
 * `tilesOk` additionally drives a small badge so the operator knows *why* the basemap looks
 * empty, rather than assuming the app itself is broken.
 */
@Component({
  selector: 'vision-live-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './live-map.html',
  styleUrl: './live-map.css',
})
export class LiveMap {
  protected readonly store = inject(TelemetryStore);
  protected readonly settings = inject(SettingsStore);

  /** The four switchable base layers (docs/CYCLES-PLAN.md §9, CU-b item 6), for the template's `@for`. */
  protected readonly layers = MAP_LAYERS;

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly autoFollow = signal(true);
  protected readonly expanded = signal(false);
  protected readonly tilesOk = signal(true);

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private marker: Leaflet.Marker | null = null;
  private startFlag: Leaflet.Marker | null = null;
  private trailLine: Leaflet.Polyline | null = null;
  private hasCentered = false;
  private generation = 0;

  constructor() {
    afterNextRender(() => void this.initMap());

    effect(() => {
      const trail = this.store.trail();
      const latest = this.store.latest();
      const follow = this.autoFollow();
      this.applyTelemetry(trail, latest, follow);
    });

    // Swaps the tile layer whenever the persisted choice changes (docs/CYCLES-PLAN.md §9, CU-b
    // item 6) — a no-op until `initMap()` has created `this.map` (it applies the initial layer
    // itself once the Leaflet chunk lands, same pattern as the telemetry effect above).
    effect(() => this.applyLayer(this.settings.mapLayer()));

    // Leaflet sizes itself from the DOM at creation time; expanding/collapsing the inset
    // resizes that DOM out from under it, so it must be told to remeasure. `invalidateSize()`
    // is imperative (not signal-driven), hence calling it as an effect side-effect rather than
    // binding anything to it.
    effect((onCleanup) => {
      this.expanded();
      this.map?.invalidateSize();
      const timer = setTimeout(() => this.map?.invalidateSize(), EXPAND_TRANSITION_MS);
      onCleanup(() => clearTimeout(timer));
    });

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  protected toggleFollow(): void {
    this.autoFollow.update((value) => !value);
  }

  protected toggleExpanded(): void {
    this.expanded.update((value) => !value);
  }

  protected setLayer(id: MapLayerId): void {
    this.settings.mapLayer.set(id);
  }

  private async initMap(): Promise<void> {
    const generation = ++this.generation;
    const L = await importLeaflet();
    if (generation !== this.generation) {
      return; // destroyed before the chunk finished loading
    }
    this.leaflet = L;
    ensureLeafletStylesheet();

    // `zoomControl: false` + re-added at `bottomright`: Leaflet's default zoom control lands at
    // `topleft`, the same corner `.controls.layers` (the layer-switcher segmented control, see
    // this component's own `live-map.css`) occupies — the two would render stacked on top of each
    // other (visually confirmed on `/fly`'s cockpit inset — see `shared/map/fleet-map.ts`'s
    // identical fix and comment). `bottomright` is the one corner nothing else in this template
    // claims (Follow/Expand sit `topright`, the offline badge sits `bottomleft`).
    const map = L.map(this.mapHost().nativeElement, { center: [0, 0], zoom: 2, zoomControl: false });
    this.map = map;
    L.control.zoom({ position: 'bottomright' }).addTo(map);

    this.applyLayer(this.settings.mapLayer());

    this.trailLine = L.polyline([], { color: '#4f8cff', weight: 3, opacity: 0.85 }).addTo(map);
    this.marker = L.marker([0, 0], {
      icon: this.droneIcon(0),
      opacity: 0,
      keyboard: false,
    }).addTo(map);

    // The signals may already carry data by the time the chunk finishes loading.
    this.applyTelemetry(this.store.trail(), this.store.latest(), this.autoFollow());
  }

  private applyTelemetry(
    trail: readonly GeoPosition[],
    latest: TelemetrySample | undefined,
    follow: boolean,
  ): void {
    const L = this.leaflet;
    if (!L || !this.map) {
      return; // map chunk/instance not ready yet — initMap() re-applies once it is
    }

    if (trail.length === 0) {
      this.trailLine?.setLatLngs([]);
      this.startFlag?.remove();
      this.startFlag = null;
      this.marker?.setOpacity(0);
      this.hasCentered = false;
      return;
    }

    const points = trail.map((position) => L.latLng(position.latitude, position.longitude));
    this.trailLine?.setLatLngs(points);

    if (!this.startFlag) {
      this.startFlag = L.marker(points[0], {
        icon: this.flagIcon(),
        keyboard: false,
        interactive: false,
      }).addTo(this.map);
    } else {
      this.startFlag.setLatLng(points[0]);
    }

    const hasFix = latest?.latitude !== undefined && latest.longitude !== undefined;
    if (hasFix && this.marker) {
      const point = L.latLng(latest.latitude as number, latest.longitude as number);
      this.marker.setLatLng(point);
      this.marker.setIcon(this.droneIcon(latest.headingDegrees ?? 0));
      this.marker.setOpacity(1);

      // Auto-follow keeps recentering; otherwise only the very first fix centers the view, so
      // a user who has since panned away is not yanked back on every poll.
      if (follow || !this.hasCentered) {
        this.map.setView(point, this.hasCentered ? this.map.getZoom() : DEFAULT_ZOOM, {
          animate: this.hasCentered,
        });
        this.hasCentered = true;
      }
    }
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

  private droneIcon(headingDegrees: number): Leaflet.DivIcon {
    return droneDivIcon(this.leaflet!, headingDegrees);
  }

  private flagIcon(): Leaflet.DivIcon {
    return this.leaflet!.divIcon({
      className: 'start-flag',
      html: '<div class="flag-glyph">⚑</div>',
      iconSize: [18, 18],
      iconAnchor: [2, 16],
    });
  }

  private teardown(): void {
    this.generation++;
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
    this.marker = null;
    this.trailLine = null;
    this.startFlag = null;
  }
}
