import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import { SettingsStore, type MapLayerId } from '../../../core/settings/settings-store';
import { EventsStore } from '../../../core/events/events-store';
import { capitalizeLabel, formatConfidence, relativeTimeLabel, selectEventMarkers } from '../../../core/events/events-logic';
import type { DetectionEvent, GeofenceZone } from '../../../core/api/models';
import { MAP_LAYERS, droneDivIcon, ensureLeafletStylesheet, importLeaflet, mapLayerTileLayer } from '../tile-cache/leaflet-loader';
import { FleetMapStore } from '../../../core/map/map-store';
import { fingerprintMarkers, nextAutoFitEnabled, type FleetMarker } from '../../../core/map/map-logic';
import { zoneKindLabel, zoneLayerStyle } from '../../../core/geofence/geofence-logic';

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
 * The Leaflet fleet map (docs/CYCLES-PLAN.md §6's `/map` tab, now also docs/MVP3-PLAN.md §C-c's
 * Command dashboard): every asset `FleetMapStore.markers()` plots, a breadcrumb trail per
 * streaming asset, popups with a "Watch live" action, and auto-fit-to-bounds that a manual pan/zoom
 * disables until "Recenter" is clicked.
 *
 * **Moved here from `pages/map/fleet-map.ts`** (docs/MVP3-PLAN.md §C-c) when the (now-deleted)
 * `MapPage` and the new Command dashboard both needed to embed this component — the same "no page
 * imports another page's module" precedent every other shared piece in this app follows (see
 * `core/fleet/device-logic.ts`'s doc comment). `MapPage` itself is gone now (`/map` redirects to
 * `/command`, docs/UX-REWORK-PLAN.md §U-c — see `features/map/map.routes.ts`'s own doc comment),
 * so `CommandPage` is this component's only host today: it routes a clicked marker's `watch` output
 * straight to `/fly?asset=<id>&watch=1` (no device lookup needed — see `CommandPage`'s own doc
 * comment) — which host does the navigating is exactly the same split described below, just there
 * is now exactly one host to resolve it.
 *
 * **Leaflet loads only here and in `shared/map/live-map.ts`** — both dynamically `import`
 * (via `shared/map/leaflet-loader.ts#importLeaflet`) inside `initMap()`, called from `afterNextRender`,
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
 * **"Watch live" actions (docs/UX-REWORK-PLAN.md U-a2 item 1):** popups are raw HTML (Leaflet
 * popups aren't Angular templates), so both buttons inside one are wired via a single delegated
 * click listener on the map container rather than one Angular event binding per popup — matches
 * the imperative-Leaflet approach used throughout this component. Resolving *which* device to
 * navigate to is `FleetMapStore`'s job (`resolveWatchDevice`); this component only emits the
 * chosen `assetId` via `watch`/`preview` and leaves both navigation and docking to the host page
 * (`CommandPage` today — see its own doc comment for what each output now drives, since the old
 * docked `LiveDock` panel `preview` used to open is gone). Both
 * buttons read "Watch live" — the docked-inline-preview vs. a fresh full page is a presentation
 * detail, not a separate verb (see each output's own doc comment below for which is which); a
 * `title` attribute on each still spells out the difference for a pointer-hovering user. The dock
 * (`preview` output, docs/CYCLES-PLAN.md §9, CU-b item 5) additionally fires straight from a
 * marker click for `live` markers (bypassing the popup) — the popup's own "Watch live" button
 * exists for discoverability, not as the only way in.
 *
 * **Event markers** (docs/MVP2-PLAN.md §E, E-b bullet 3): a second, independent marker layer for
 * every position-carrying `DetectionEvent` (`EventsStore.events()`, injected directly rather than
 * activated/released here — nobody needs to: the app-shell header bell
 * (`shared/ui/notification-bell.ts`) now keeps `EventsStore` activated for the entire session, so
 * this component simply reads a feed that is always warm rather than owning any part of its
 * activate/release lifecycle itself, same as before, just a different always-on reason), capped to the
 * most recent `MAX_EVENT_MARKERS` (`selectEventMarkers`). These never participate in auto-fit
 * (`fitToMarkers` only ever looks at `store.markers()`, unchanged) — a stray old event elsewhere on
 * the map must never yank the fleet view away from where the assets actually are. A popup shows
 * label/confidence/first-and-last-seen and, per the plan's own honestly-scoped fallback, a
 * **"Details"** button (docs/UX-REWORK-PLAN.md U-a2 item 1 — was "Open asset") when `assetId`
 * resolved — still just the asset detail page, not a replay deep link, even though
 * docs/OPS-CORE-PLAN.md §Q1 later gave `core/events/events-logic.ts#resolveReplayDeepLink` enough
 * to resolve one (`AssetDetails.recentUsages` now carries the usage window needed — the original
 * "no current API exposes that link" gap this doc comment used to record is closed). Deliberately
 * **not** wired in here regardless: that resolution needs an async `VisionApi.getAsset` lookup at
 * click time, and this component's event popups are raw HTML driven by one delegated click
 * listener on the map container (see class doc), not a place that comfortably hosts an async
 * navigation decision — `shared/ui/notification-bell.ts`/`features/wall/wall.ts` (both already
 * Angular components with router/HTTP access) are where that lookup actually lives instead.
 */
@Component({
  selector: 'vision-fleet-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fleet-map.html',
  styleUrl: './fleet-map.css',
})
export class FleetMap {
  protected readonly store = inject(FleetMapStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly events = inject(EventsStore);

  /** The four switchable base layers (docs/CYCLES-PLAN.md §9, CU-b item 6), for the template's `@for`. */
  protected readonly layers = MAP_LAYERS;

  /**
   * The full-page "Watch live" (docs/UX-REWORK-PLAN.md U-a2 item 1): emits the assetId behind a
   * popup's own button; the host page resolves the device and navigates.
   */
  readonly watch = output<string>();

  /**
   * The inline "Watch live" (docs/UX-REWORK-PLAN.md U-a2 item 1 — same label as `watch` above, a
   * docked mini-player instead of a fresh page is a presentation detail, not a new verb): emits
   * the assetId behind a streaming marker click or its popup's own button (docs/CYCLES-PLAN.md §9,
   * CU-b item 5); the host page docks a live preview panel beside the map. Only ever emitted for
   * `live` markers — an offline asset has nothing to preview.
   */
  readonly preview = output<string>();

  /** Emits the assetId behind an event popup's "Details" button (docs/MVP2-PLAN.md §E, E-b bullet 3). */
  readonly openEventAsset = output<string>();

  /**
   * The geofence zones layer (docs/OPS-CORE-PLAN.md §G-c) — `CommandPage` passes `GeofenceStore.zones()`
   * straight through; empty by default so every other host (none exist yet, but the input costs
   * nothing to leave generally available) sees no change. Rendered as a third, independent Leaflet
   * layer alongside the asset markers and event markers — a polygon per zone, styled by
   * `core/geofence/geofence-logic.ts#zoneLayerStyle`, with a permanent center label naming the zone
   * (`bindTooltip(..., {permanent: true})`) since a dashed outline alone doesn't say *which* zone it
   * is. Purely informational here — no click handling, no popup; renaming/enabling/deleting a zone
   * is the Zones panel's own job (`features/command/zones-panel.ts`), not something this read-only
   * map layer offers a way into.
   */
  readonly zones = input<readonly GeofenceZone[]>([]);

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly autoFit = signal(true);
  protected readonly tilesOk = signal(true);

  /** Position-carrying events worth plotting, most recent first, capped — see class doc. */
  protected readonly eventMarkers = computed(() => selectEventMarkers(this.events.events()));

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private readonly markerHandles = new Map<string, MarkerHandle>();
  private readonly eventMarkerHandles = new Map<string, Leaflet.Marker>();
  private readonly zoneLayerHandles = new Map<string, Leaflet.Polygon>();
  private suppressAutoFitDisable = false;
  private lastFitFingerprint: string | null = null;
  private generation = 0;

  constructor() {
    afterNextRender(() => void this.initMap());

    // Swaps the tile layer whenever the persisted choice changes (docs/CYCLES-PLAN.md §9, CU-b
    // item 6) — a no-op until `initMap()` has created `this.map` (it applies the initial layer
    // itself once the Leaflet chunk lands).
    effect(() => this.applyLayer(this.settings.mapLayer()));

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

    // Event markers are their own independent layer — deliberately excluded from the auto-fit
    // fingerprint/bounds above (see class doc): a stray old event elsewhere must never yank the
    // fleet view away from where the assets actually are.
    effect(() => this.applyEventMarkers(this.eventMarkers()));

    // Zones are a fourth, independent layer (docs/OPS-CORE-PLAN.md §G-c) — also excluded from
    // auto-fit for the identical reason: a zone drawn far from the fleet's current position must
    // never yank the map away from the assets themselves.
    effect(() => this.applyZones(this.zones()));

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

    // `zoomControl: false` + re-added at `bottomright`: Leaflet's default zoom control lands at
    // `topleft`, the same corner `.controls.layers` (the layer-switcher segmented control, see
    // this component's own `fleet-map.css`) occupies — the two would render stacked on top of each
    // other. `bottomright` is the one corner nothing else in this template claims (Recenter sits
    // `topright`, the offline badge sits `bottomleft`).
    const map = L.map(this.mapHost().nativeElement, { center: [0, 0], zoom: 2, zoomControl: false });
    this.map = map;
    L.control.zoom({ position: 'bottomright' }).addTo(map);

    this.applyLayer(this.settings.mapLayer());

    map.on('movestart zoomstart', () => {
      if (!this.suppressAutoFitDisable) {
        this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'userInteraction'));
      }
    });

    map.getContainer().addEventListener('click', (clickEvent) => {
      const target = clickEvent.target as HTMLElement | null;
      const watchBtn = target?.closest<HTMLElement>('.watch-btn');
      if (watchBtn?.dataset['assetId']) {
        this.watch.emit(watchBtn.dataset['assetId']);
        return;
      }
      const previewBtn = target?.closest<HTMLElement>('.preview-btn');
      if (previewBtn?.dataset['assetId']) {
        this.preview.emit(previewBtn.dataset['assetId']);
        return;
      }
      const eventAssetBtn = target?.closest<HTMLElement>('.event-asset-btn');
      if (eventAssetBtn?.dataset['assetId']) {
        this.openEventAsset.emit(eventAssetBtn.dataset['assetId']);
      }
    });

    // The signals may already carry data by the time the chunk finishes loading.
    const markers = this.store.markers();
    this.applyMarkers(markers);
    if (this.autoFit()) {
      this.lastFitFingerprint = fingerprintMarkers(markers);
      this.fitToMarkers(markers);
    }
    this.applyEventMarkers(this.eventMarkers());
    this.applyZones(this.zones());
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
      // Clicking a *streaming* marker directly docks the preview panel (docs/CYCLES-PLAN.md §9,
      // CU-b item 5) — in addition to (not instead of) the popup Leaflet opens on the same click,
      // which still carries its own two "Watch live" buttons for discoverability. `live` is
      // re-read from the store at click time rather than captured from this closure's `marker`,
      // since a marker can transition offline long after this listener was attached.
      leafletMarker.on('click', () => {
        const current = this.store.markers().find((candidate) => candidate.assetId === marker.assetId);
        if (current?.live) {
          this.preview.emit(marker.assetId);
        }
      });
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

  // --- Event markers (docs/MVP2-PLAN.md §E, E-b bullet 3) — see class doc ---------------------

  private applyEventMarkers(events: readonly DetectionEvent[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — `initMap()` re-applies once it is
    }

    const seen = new Set<string>();
    for (const event of events) {
      seen.add(event.id);
      this.upsertEventMarker(L, map, event);
    }
    for (const id of [...this.eventMarkerHandles.keys()]) {
      if (!seen.has(id)) {
        this.eventMarkerHandles.get(id)?.remove();
        this.eventMarkerHandles.delete(id);
      }
    }
  }

  private upsertEventMarker(L: typeof Leaflet, map: Leaflet.Map, event: DetectionEvent): void {
    if (!event.position) {
      return; // `selectEventMarkers` already filters these out — defensive, never expected here
    }
    const point = L.latLng(event.position.latitude, event.position.longitude);
    let marker = this.eventMarkerHandles.get(event.id);
    const html = this.eventPopupHtml(event);

    if (!marker) {
      marker = L.marker(point, { icon: this.eventMarkerIcon(L, event), keyboard: false, zIndexOffset: -100 })
        .addTo(map)
        .bindPopup(html);
      this.eventMarkerHandles.set(event.id, marker);
    } else {
      marker.setLatLng(point);
      marker.setIcon(this.eventMarkerIcon(L, event));
      const popup = marker.getPopup();
      if (popup) {
        popup.setContent(html); // updates in place without closing an already-open popup
      } else {
        marker.bindPopup(html);
      }
    }
  }

  private eventMarkerIcon(L: typeof Leaflet, event: DetectionEvent): Leaflet.DivIcon {
    return L.divIcon({
      className: `event-marker ${event.state === 'OPEN' ? 'event-marker-open' : 'event-marker-closed'}`,
      html: '<div class="event-marker-dot"></div>',
      iconSize: [14, 14],
      iconAnchor: [7, 7],
    });
  }

  private eventPopupHtml(event: DetectionEvent): string {
    const nowMs = Date.now();
    const rows: string[] = [
      `<div class="popup-title">${escapeHtml(capitalizeLabel(event.label))} detected</div>`,
      `<span class="chip ${event.state === 'OPEN' ? 'ok' : ''}">${event.state === 'OPEN' ? 'Open' : 'Closed'}</span>`,
      `<div class="popup-row">Peak confidence ${escapeHtml(formatConfidence(event.peakConfidence))}</div>`,
      `<div class="popup-row faint">First seen ${escapeHtml(relativeTimeLabel(event.firstSeen, nowMs))}</div>`,
      `<div class="popup-row faint">Last seen ${escapeHtml(relativeTimeLabel(event.lastSeen, nowMs))}</div>`,
    ];
    if (event.assetId) {
      rows.push(
        `<button type="button" class="btn small event-asset-btn" data-asset-id="${escapeHtml(event.assetId)}">Details</button>`,
      );
    } else {
      // Honest gap (docs/MVP2-PLAN.md §E, E-b bullet 3): no assetId resolved, and no current API
      // can turn a firstSeen instant back into a usageId to link a replay moment either — see the
      // class doc comment.
      rows.push('<div class="popup-row faint">No asset resolved for this event.</div>');
    }
    return `<div class="fleet-popup event-popup">${rows.join('')}</div>`;
  }

  // --- Geofence zones (docs/OPS-CORE-PLAN.md §G-c) — read-only, see the `zones` input's own doc comment --

  private applyZones(zones: readonly GeofenceZone[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — `initMap()` re-applies once it is
    }

    const seen = new Set<string>();
    for (const zone of zones) {
      seen.add(zone.id);
      this.upsertZoneLayer(L, map, zone);
    }
    for (const id of [...this.zoneLayerHandles.keys()]) {
      if (!seen.has(id)) {
        this.zoneLayerHandles.get(id)?.remove();
        this.zoneLayerHandles.delete(id);
      }
    }
  }

  private upsertZoneLayer(L: typeof Leaflet, map: Leaflet.Map, zone: GeofenceZone): void {
    const style = zoneLayerStyle(zone.kind, zone.enabled);
    const points = zone.polygon.map((vertex) => L.latLng(vertex.latitude, vertex.longitude));
    const label = `${zoneKindLabel(zone.kind)}: ${zone.name}${zone.enabled ? '' : ' (disabled)'}`;
    let polygon = this.zoneLayerHandles.get(zone.id);
    if (!polygon) {
      polygon = L.polygon(points, { ...style, interactive: false }).addTo(map);
      polygon.bindTooltip(escapeHtml(label), { permanent: true, direction: 'center', className: 'zone-label' });
      this.zoneLayerHandles.set(zone.id, polygon);
    } else {
      polygon.setLatLngs(points);
      polygon.setStyle(style);
      polygon.setTooltipContent(escapeHtml(label));
    }
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

  protected setLayer(id: MapLayerId): void {
    this.settings.mapLayer.set(id);
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
    if (marker.flightMode !== undefined) {
      // Mode line (docs/FC-INTEGRATIONS-PLAN.md F-d) — red text when failsafe, the one severity
      // color `--live` is reserved for; otherwise the popup's own plain text color.
      const failsafeStyle = marker.failsafe === true ? ' style="color: var(--live); font-weight: 600;"' : '';
      rows.push(`<div class="popup-row"${failsafeStyle}>Mode ${escapeHtml(marker.flightMode)}</div>`);
    }
    if (marker.batteryPercent !== undefined) {
      rows.push(`<div class="popup-row">Battery ${marker.batteryPercent.toFixed(0)}%</div>`);
    }
    if (marker.position.altitudeMeters !== undefined) {
      rows.push(`<div class="popup-row">Altitude ${marker.position.altitudeMeters.toFixed(0)} m</div>`);
    }
    if (marker.sampleAgeSeconds !== undefined) {
      rows.push(`<div class="popup-row faint">Updated ${marker.sampleAgeSeconds.toFixed(0)}s ago</div>`);
    }
    if (marker.live) {
      rows.push(
        `<button type="button" class="btn small secondary preview-btn" data-asset-id="${escapeHtml(marker.assetId)}" title="Watch live, inline beside the map">Watch live</button>`,
      );
    }
    rows.push(
      `<button type="button" class="btn small watch-btn" data-asset-id="${escapeHtml(marker.assetId)}" title="Watch live on its own page">Open asset</button>`,
    );
    return `<div class="fleet-popup">${rows.join('')}</div>`;
  }

  private teardown(): void {
    this.generation++;
    for (const assetId of [...this.markerHandles.keys()]) {
      this.removeMarker(assetId);
    }
    for (const marker of this.eventMarkerHandles.values()) {
      marker.remove();
    }
    this.eventMarkerHandles.clear();
    for (const polygon of this.zoneLayerHandles.values()) {
      polygon.remove();
    }
    this.zoneLayerHandles.clear();
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
  }
}
