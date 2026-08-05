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
import { TelemetryStore } from '../../../core/telemetry/telemetry-store';
import type { GeoPosition, GeofenceZone, Mark, TelemetrySample } from '../../../core/api/models';
import { SettingsStore, type MapLayerId } from '../../../core/settings/settings-store';
import { ThemeStore } from '../../../core/shell/theme-store';
import {
  MAP_LAYERS,
  droneDivIcon,
  effectiveMapLayerId,
  ensureLeafletStylesheet,
  importLeaflet,
  isMapLayerExplicit,
  markMapLayerExplicit,
  mapLayerTileLayer,
} from '../tile-cache/leaflet-loader';
import { zoneKindLabel, zoneLayerStyle } from '../../../core/geofence/geofence-logic';
import { markKindLabel, markStyle, type MarkMoved } from '../../../core/marks/mark-logic';

const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** Tooltip content is raw HTML handed to Leaflet, not an Angular template — escape the zone's own name. */
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ESCAPE_MAP[char]);
}

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
 * **Offline fallback.** `.map-shell`'s background is `--bg` (live-map.css) — themed, not
 * hardcoded, since docs/VISUAL-REFRESH-PLAN.md's two-theme flip (paper-gray canvas in light, the
 * original dark canvas in dark/`.surface-dark`) — and Leaflet's marker/overlay panes are
 * independent of the tile pane, so a tile fetch failure (no network at a flying field) just
 * leaves that canvas colour showing through missing tiles while the trail and marker — vector
 * overlays — keep rendering with no special-case code. `tilesOk` additionally drives a small
 * badge so the operator knows *why* the basemap looks empty, rather than assuming the app itself
 * is broken.
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
  protected readonly theme = inject(ThemeStore);

  /** The four switchable base layers (docs/CYCLES-PLAN.md §9, CU-b item 6), for the template's `@for`. */
  protected readonly layers = MAP_LAYERS;

  /** The layer actually rendered (docs/VISUAL-REFRESH-PLAN.md F7) — see `FleetMap`'s identical
   * field's own doc comment for the full "explicit pick always wins" contract; both components
   * share the one `effectiveMapLayerId`/`isMapLayerExplicit` mechanism in `leaflet-loader.ts`. */
  protected readonly activeLayerId = computed<MapLayerId>(() =>
    effectiveMapLayerId(this.theme.theme(), this.settings.mapLayer(), isMapLayerExplicit()),
  );

  /**
   * Geofence zones, read-only (docs/OPS-CORE-PLAN.md §G-c: "Fly map shows zones read-only, same
   * styles"). `FlyPage` passes `GeofenceStore.zones()`; empty by default, so `AssetDetailPage`
   * (this component's other host — see class doc) sees no change at all. Same rendering as
   * `shared/map/fleet-map/fleet-map.ts`'s own zones layer — a styled polygon per zone plus a
   * permanent center label — this map has no click-to-manage affordance either way (that lives in
   * Command's own Zones panel).
   */
  readonly zones = input<readonly GeofenceZone[]>([]);

  /**
   * Tactical marks (docs/TACTICAL-MARKS-PLAN.md M5) — `FlyPage` passes `MarksStore.marks()`
   * (via its facade); empty by default. Unlike `zones` above this layer **is** interactive: each
   * mark renders as a coloured (`core/marks/mark-logic.ts#markStyle`), draggable `L.marker` — click
   * selects (`markSelected`), drag-to-correct PATCHes the new position (`markMoved`) — mirroring
   * `upsertZoneLayer`'s own upsert-by-id diffing below but with click/drag handlers attached once,
   * at creation, exactly like `shared/map/fleet-map/fleet-map.ts#upsertMarker`'s own `live`-marker
   * click handler.
   */
  readonly marks = input<readonly Mark[]>([]);
  /** The currently-selected mark id, if any — rendered larger/highlighted (`markStyle`'s own `selected` flag). */
  readonly selectedMarkId = input<string | undefined>(undefined);

  /** A mark marker was clicked — the host's `MarksStore.select(id)` toggles selection. */
  readonly markSelected = output<string>();
  /** A mark marker was dragged to a new position — the host's `MarksStore.moveTo(id, position)` PATCHes it (drag-to-correct). */
  readonly markMoved = output<MarkMoved>();
  /**
   * Any click on the map's own background (never one that lands on an interactive marker — Leaflet
   * markers stop event propagation to the map by default) — the host only acts on this while a mark
   * kind is armed (`MarksStore.pendingKind()`); an ordinary click with nothing pending is ignored by
   * `MarksStore.handleMapClick` itself, so this output always fires unconditionally and stays a
   * dumb passthrough (this component has no opinion about "placement mode").
   */
  readonly mapClicked = output<GeoPosition>();

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
  private readonly zoneLayerHandles = new Map<string, Leaflet.Polygon>();
  private readonly markLayerHandles = new Map<string, Leaflet.Marker>();
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

    // Swaps the tile layer whenever the effective choice changes (docs/CYCLES-PLAN.md §9, CU-b item
    // 6; theme-aware default docs/VISUAL-REFRESH-PLAN.md F7) — `activeLayerId()` tracks both
    // `settings.mapLayer()` and `theme.theme()`, so a theme flip re-tiles this inset too. A no-op
    // until `initMap()` has created `this.map` (it applies the initial layer itself once the
    // Leaflet chunk lands, same pattern as the telemetry effect above).
    effect(() => this.applyLayer());

    // Geofence zones (docs/OPS-CORE-PLAN.md §G-c) — see the `zones` input's own doc comment.
    effect(() => this.applyZones(this.zones()));

    // Tactical marks (docs/TACTICAL-MARKS-PLAN.md M5) — see the `marks` input's own doc comment.
    // Re-runs on `selectedMarkId()` too, so a selection made elsewhere (e.g. the marks panel list)
    // re-styles the right marker here without needing its own separate effect.
    effect(() => this.applyMarks(this.marks(), this.selectedMarkId()));

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

  /** A layer-picker click — an explicit pick always wins over the theme default from here on
   * (docs/VISUAL-REFRESH-PLAN.md F7); see `FleetMap#setLayer`'s identical doc comment. */
  protected setLayer(id: MapLayerId): void {
    markMapLayerExplicit();
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

    // Tactical marks create-by-click (docs/TACTICAL-MARKS-PLAN.md M5) — fires only for a click on
    // the map's own background (a mark marker or a zone's own tooltip stops this from bubbling up
    // for a click that actually landed on one of those); see the `mapClicked` output's own doc
    // comment for why this component stays opinion-free about "placement mode".
    map.on('click', (event: Leaflet.LeafletMouseEvent) => {
      this.mapClicked.emit({ latitude: event.latlng.lat, longitude: event.latlng.lng });
    });

    this.applyLayer();

    this.trailLine = L.polyline([], { color: '#4f8cff', weight: 3, opacity: 0.85 }).addTo(map);
    this.marker = L.marker([0, 0], {
      icon: this.droneIcon(0),
      opacity: 0,
      keyboard: false,
    }).addTo(map);

    // The signals may already carry data by the time the chunk finishes loading.
    this.applyTelemetry(this.store.trail(), this.store.latest(), this.autoFollow());
    this.applyZones(this.zones());
    this.applyMarks(this.marks(), this.selectedMarkId());
  }

  // --- Geofence zones (docs/OPS-CORE-PLAN.md §G-c), read-only — see the `zones` input's own doc comment --

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

  // --- Tactical marks (docs/TACTICAL-MARKS-PLAN.md M5) — interactive, unlike zones above --------

  private applyMarks(marks: readonly Mark[], selectedMarkId: string | undefined): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — `initMap()` re-applies once it is
    }
    const seen = new Set<string>();
    for (const mark of marks) {
      seen.add(mark.id);
      this.upsertMarkLayer(L, map, mark, mark.id === selectedMarkId);
    }
    for (const id of [...this.markLayerHandles.keys()]) {
      if (!seen.has(id)) {
        this.markLayerHandles.get(id)?.remove();
        this.markLayerHandles.delete(id);
      }
    }
  }

  private upsertMarkLayer(L: typeof Leaflet, map: Leaflet.Map, mark: Mark, selected: boolean): void {
    const point = L.latLng(mark.position.latitude, mark.position.longitude);
    const tooltip = escapeHtml(`${markKindLabel(mark.kind)}: ${mark.label}`);
    let marker = this.markLayerHandles.get(mark.id);
    if (!marker) {
      marker = L.marker(point, { icon: this.markIcon(L, mark, selected), draggable: true, keyboard: false }).addTo(map);
      marker.on('click', () => this.markSelected.emit(mark.id));
      // Only `dragend` writes back — mirrors `geofence-zone-dialog.ts`'s identical vertex-marker
      // reasoning: a live `drag` tick would fight the in-progress gesture by re-rendering mid-drag.
      // No local optimistic state here — `MarksStore.moveTo` owns the honest revert-on-failure (see
      // that method's own doc comment); this handler only ever reports what the user did.
      marker.on('dragend', () => {
        const latlng = marker!.getLatLng();
        this.markMoved.emit({ id: mark.id, position: { latitude: latlng.lat, longitude: latlng.lng } });
      });
      marker.bindTooltip(tooltip, { direction: 'top', offset: [0, -8] });
      this.markLayerHandles.set(mark.id, marker);
    } else {
      marker.setLatLng(point);
      marker.setIcon(this.markIcon(L, mark, selected));
      marker.setTooltipContent(tooltip);
    }
  }

  private markIcon(L: typeof Leaflet, mark: Mark, selected: boolean): Leaflet.DivIcon {
    const style = markStyle(mark.kind, selected);
    return L.divIcon({
      className: `mark-marker${selected ? ' mark-selected' : ''}`,
      html: `<div class="mark-dot" style="width:${style.diameterPx}px;height:${style.diameterPx}px;background:${style.fillColor};border-color:${style.color}"></div>`,
      iconSize: [style.diameterPx, style.diameterPx],
      iconAnchor: [style.diameterPx / 2, style.diameterPx / 2],
    });
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

  /** Swaps the active base layer to `activeLayerId()` — a no-op until the map exists (`initMap()`
   * re-applies once it does). */
  private applyLayer(): void {
    const L = this.leaflet;
    if (!L || !this.map) {
      return;
    }
    this.tileLayer?.remove();
    this.tileLayer = mapLayerTileLayer(L, this.activeLayerId(), (ok) => this.tilesOk.set(ok));
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
    for (const polygon of this.zoneLayerHandles.values()) {
      polygon.remove();
    }
    this.zoneLayerHandles.clear();
    for (const marker of this.markLayerHandles.values()) {
      marker.remove();
    }
    this.markLayerHandles.clear();
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
    this.marker = null;
    this.trailLine = null;
    this.startFlag = null;
  }
}
