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
import { TelemetryStore } from '../../core/telemetry-store';
import type { GeoPosition, TelemetrySample } from '../../core/api/models';

/** Injected once per document, only when a map is actually created — see class doc. */
const LEAFLET_STYLESHEET_ID = 'vision-leaflet-css';
const LEAFLET_STYLESHEET_HREF = '/leaflet/leaflet.css';

const DEFAULT_ZOOM = 17;

/** Long enough to outlast the `.map-shell`/`.expanded` CSS transition (see live-map.css). */
const EXPAND_TRANSITION_MS = 260;

const OSM_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

/**
 * Live map inset for `/live/:deviceId` (docs/CYCLES-PLAN.md §2, UX-DESIGN §5.2): drone marker
 * rotated to heading, breadcrumb trail of the current usage, start-point flag, auto-follow
 * toggle, and an expand-to-full-pane control.
 *
 * **Leaflet loads only here.** The live route is already its own lazy chunk; `import('leaflet')`
 * inside `initMap()` — a *dynamic* import, not a static one — additionally keeps Leaflet out of
 * that chunk's own parse cost until a telemetry-capable device is actually being viewed.
 * Mirrors `ui/player.ts`'s `import('hls.js')` idiom: dynamic import inside an async method, a
 * `generation` counter to ignore a load superseded by teardown, cleanup on destroy.
 *
 * **Zoneless gotcha (the risk CYCLES-PLAN.md §2 called out):** Leaflet is purely imperative and
 * is never bound in the template. `map`/`marker`/`trailLine`/`startFlag` are plain fields,
 * created once in `initMap()` and mutated from `effect()`s that read the `TelemetryStore`
 * injected from `LivePage`'s DI — Angular never re-renders Leaflet's own DOM.
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

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly autoFollow = signal(true);
  protected readonly expanded = signal(false);
  protected readonly tilesOk = signal(true);

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
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

  private async initMap(): Promise<void> {
    const generation = ++this.generation;
    const imported = await import('leaflet');
    if (generation !== this.generation) {
      return; // destroyed before the chunk finished loading
    }

    // Leaflet ships as UMD; depending on the bundler's CJS interop this dynamic import may
    // resolve either the namespace itself or a `default` wrapping it.
    const namespace = imported as unknown as { default?: typeof Leaflet } & typeof Leaflet;
    const L = namespace.default ?? namespace;
    this.leaflet = L;
    this.ensureStylesheet();

    const map = L.map(this.mapHost().nativeElement, { center: [0, 0], zoom: 2 });
    this.map = map;

    const tiles = L.tileLayer(OSM_TILE_URL, {
      maxZoom: 19,
      className: 'vision-tiles',
      attribution: OSM_ATTRIBUTION,
    });
    tiles.on('tileerror', () => this.tilesOk.set(false));
    tiles.on('load', () => this.tilesOk.set(true));
    tiles.addTo(map);

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

  private droneIcon(headingDegrees: number): Leaflet.DivIcon {
    return this.leaflet!.divIcon({
      className: 'drone-marker',
      html: `<div class="drone-arrow" style="transform: rotate(${headingDegrees}deg)"></div>`,
      iconSize: [22, 22],
      iconAnchor: [11, 11],
    });
  }

  private flagIcon(): Leaflet.DivIcon {
    return this.leaflet!.divIcon({
      className: 'start-flag',
      html: '<div class="flag-glyph">⚑</div>',
      iconSize: [18, 18],
      iconAnchor: [2, 16],
    });
  }

  private ensureStylesheet(): void {
    if (document.getElementById(LEAFLET_STYLESHEET_ID)) {
      return;
    }
    const link = document.createElement('link');
    link.id = LEAFLET_STYLESHEET_ID;
    link.rel = 'stylesheet';
    link.href = LEAFLET_STYLESHEET_HREF;
    document.head.appendChild(link);
  }

  private teardown(): void {
    this.generation++;
    this.map?.remove();
    this.map = null;
    this.marker = null;
    this.trailLine = null;
    this.startFlag = null;
  }
}
