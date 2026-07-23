import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import { darkTileLayer, droneDivIcon, ensureLeafletStylesheet, importLeaflet } from '../../ui/leaflet-loader';
import { FleetMapStore } from './map-store';
import { fingerprintMarkers, nextAutoFitEnabled, type FleetMarker } from './map-logic';

/** Padding so the outermost markers aren't flush against the map's edge after a fit. */
const FIT_PADDING: Leaflet.PointTuple = [48, 48];

/** A single marker never gets zoomed in tighter than this, even though its own bounds has zero area. */
const FIT_MAX_ZOOM = 16;

const TRAIL_COLOR = '#4f8cff';

interface MarkerHandle {
  marker: Leaflet.Marker;
  trailLine: Leaflet.Polyline | null;
}

const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** Popup content is raw HTML handed to Leaflet, not an Angular template — escape user-controlled text. */
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ESCAPE_MAP[char]);
}

/**
 * The Leaflet map for the `/map` fleet overview tab (docs/CYCLES-PLAN.md §6): every asset
 * `FleetMapStore.markers()` plots, a breadcrumb trail per streaming asset, popups with a Watch
 * action, and auto-fit-to-bounds that a manual pan/zoom disables until "Recenter" is clicked.
 *
 * **Leaflet loads only here and in `pages/live/live-map.ts`** — both dynamically `import`
 * (via `ui/leaflet-loader.ts#importLeaflet`) inside `initMap()`, called from `afterNextRender`,
 * so the ~38 kB gz Leaflet chunk is fetched only once this page is actually visited, same as the
 * live cockpit's map inset.
 *
 * **Zoneless gotcha (as in `LiveMap`):** Leaflet is purely imperative and never bound in the
 * template. `map` and every marker/polyline live in plain fields, created once and mutated from
 * `effect()`s reading `FleetMapStore`'s signals — Angular never re-renders Leaflet's own DOM.
 *
 * **Distinguishing user interaction from our own `fitBounds()` calls:** every programmatic
 * `map.fitBounds()` (from the auto-fit effect or the Recenter button) is wrapped in
 * `suppressAutoFitDisable`; the `movestart`/`zoomstart` listener registered once in `initMap()`
 * only disables auto-fit when that flag is *not* set, so it reliably fires only for actual
 * drag/scroll/zoom-button/keyboard interaction, never for our own recentering.
 *
 * **Watch action:** popups are raw HTML (Leaflet popups aren't Angular templates), so the Watch
 * button inside one is wired via a single delegated click listener on the map container rather
 * than one Angular event binding per popup — matches the imperative-Leaflet approach used
 * throughout this component. Resolving *which* device to navigate to is `FleetMapStore`'s job
 * (`resolveWatchDevice`); this component only emits the chosen `assetId` via `watch` and leaves
 * navigation to `MapPage`, which already injects the `Router` for its own "no position" rail.
 */
@Component({
  selector: 'vision-fleet-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fleet-map.html',
  styleUrl: './fleet-map.css',
})
export class FleetMap {
  protected readonly store = inject(FleetMapStore);

  /** Emits the assetId behind a popup's Watch button; `MapPage` resolves the device and navigates. */
  readonly watch = output<string>();

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly autoFit = signal(true);
  protected readonly tilesOk = signal(true);

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private readonly markerHandles = new Map<string, MarkerHandle>();
  private suppressAutoFitDisable = false;
  private lastFitFingerprint: string | null = null;
  private generation = 0;

  constructor() {
    afterNextRender(() => void this.initMap());

    // Redraws every marker (position/icon/popup/trail) on every store update — including the 1s
    // clock tick that only changes `sampleAgeSeconds` — cheap DOM mutation either way. Whether
    // to *re-fit bounds* is a separate, coarser decision (see `fingerprintMarkers`'s doc comment):
    // only when the plotted set actually moved, not on every tick.
    effect(() => {
      const markers = this.store.markers();
      this.applyMarkers(markers);
      if (this.autoFit()) {
        const fingerprint = fingerprintMarkers(markers);
        if (fingerprint !== this.lastFitFingerprint) {
          this.lastFitFingerprint = fingerprint;
          this.fitToMarkers(markers);
        }
      }
    });

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /** The map's only path back to auto-fit once a manual pan/zoom has disabled it. */
  protected recenter(): void {
    this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'recenterClicked'));
    const markers = this.store.markers();
    this.lastFitFingerprint = fingerprintMarkers(markers);
    this.fitToMarkers(markers);
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

    darkTileLayer(L, (ok) => this.tilesOk.set(ok)).addTo(map);

    map.on('movestart zoomstart', () => {
      if (!this.suppressAutoFitDisable) {
        this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'userInteraction'));
      }
    });

    map.getContainer().addEventListener('click', (event) => {
      const target = event.target as HTMLElement | null;
      const button = target?.closest<HTMLElement>('.watch-btn');
      const assetId = button?.dataset['assetId'];
      if (assetId) {
        this.watch.emit(assetId);
      }
    });

    // The signals may already carry data by the time the chunk finishes loading.
    const markers = this.store.markers();
    this.applyMarkers(markers);
    if (this.autoFit()) {
      this.lastFitFingerprint = fingerprintMarkers(markers);
      this.fitToMarkers(markers);
    }
  }

  private applyMarkers(markers: readonly FleetMarker[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — initMap() re-applies once it is
    }

    const seen = new Set<string>();
    for (const marker of markers) {
      seen.add(marker.assetId);
      this.upsertMarker(L, map, marker);
    }
    for (const assetId of [...this.markerHandles.keys()]) {
      if (!seen.has(assetId)) {
        this.removeMarker(assetId);
      }
    }
  }

  private upsertMarker(L: typeof Leaflet, map: Leaflet.Map, marker: FleetMarker): void {
    const point = L.latLng(marker.position.latitude, marker.position.longitude);
    let handle = this.markerHandles.get(marker.assetId);

    if (!handle) {
      const leafletMarker = L.marker(point, { icon: this.iconFor(L, marker), keyboard: false }).addTo(map);
      handle = { marker: leafletMarker, trailLine: null };
      this.markerHandles.set(marker.assetId, handle);
    } else {
      handle.marker.setLatLng(point);
      handle.marker.setIcon(this.iconFor(L, marker));
    }

    const html = this.popupHtml(marker);
    const popup = handle.marker.getPopup();
    if (popup) {
      popup.setContent(html); // updates in place without closing an already-open popup
    } else {
      handle.marker.bindPopup(html);
    }

    if (marker.live) {
      if (!handle.trailLine) {
        handle.trailLine = L.polyline([], { color: TRAIL_COLOR, weight: 2, opacity: 0.75 }).addTo(map);
      }
      handle.trailLine.setLatLngs(marker.trail.map((position) => L.latLng(position.latitude, position.longitude)));
    } else if (handle.trailLine) {
      handle.trailLine.remove();
      handle.trailLine = null;
    }
  }

  private removeMarker(assetId: string): void {
    const handle = this.markerHandles.get(assetId);
    handle?.marker.remove();
    handle?.trailLine?.remove();
    this.markerHandles.delete(assetId);
  }

  private fitToMarkers(markers: readonly FleetMarker[]): void {
    const L = this.leaflet;
    if (!L || !this.map || markers.length === 0) {
      return;
    }
    const bounds = L.latLngBounds(markers.map((marker) => L.latLng(marker.position.latitude, marker.position.longitude)));
    this.suppressAutoFitDisable = true;
    this.map.fitBounds(bounds, { padding: FIT_PADDING, maxZoom: FIT_MAX_ZOOM, animate: false });
    this.suppressAutoFitDisable = false;
  }

  private iconFor(L: typeof Leaflet, marker: FleetMarker): Leaflet.DivIcon {
    if (marker.live) {
      return droneDivIcon(L, marker.headingDegrees ?? 0, 'fleet-drone-marker');
    }
    return L.divIcon({
      className: 'fleet-offline-marker',
      html: '<div class="offline-dot"></div>',
      iconSize: [14, 14],
      iconAnchor: [7, 7],
    });
  }

  private popupHtml(marker: FleetMarker): string {
    const rows: string[] = [
      `<div class="popup-title">${escapeHtml(marker.displayName)}</div>`,
      `<div class="popup-meta">${escapeHtml(marker.categoryName)}</div>`,
      `<span class="chip ${marker.live ? 'ok' : ''}">${marker.live ? 'Streaming' : 'Offline'}</span>`,
    ];
    if (marker.batteryPercent !== undefined) {
      rows.push(`<div class="popup-row">Battery ${marker.batteryPercent.toFixed(0)}%</div>`);
    }
    if (marker.position.altitudeMeters !== undefined) {
      rows.push(`<div class="popup-row">Altitude ${marker.position.altitudeMeters.toFixed(0)} m</div>`);
    }
    if (marker.sampleAgeSeconds !== undefined) {
      rows.push(`<div class="popup-row faint">Updated ${marker.sampleAgeSeconds.toFixed(0)}s ago</div>`);
    }
    rows.push(
      `<button type="button" class="btn small watch-btn" data-asset-id="${escapeHtml(marker.assetId)}">Watch</button>`,
    );
    return `<div class="fleet-popup">${rows.join('')}</div>`;
  }

  private teardown(): void {
    this.generation++;
    for (const assetId of [...this.markerHandles.keys()]) {
      this.removeMarker(assetId);
    }
    this.map?.remove();
    this.map = null;
  }
}
