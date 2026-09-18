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
import { FormsModule } from '@angular/forms';
import type * as Leaflet from 'leaflet';
import type { GeoPosition, ZoneKind } from '../../core/api/models';
import { SettingsStore, type MapLayerId } from '../../core/settings/settings-store';
import { ThemeFacade } from '../../core/shell/theme-facade';
import {
  MAP_LAYERS,
  effectiveMapLayerId,
  ensureLeafletStylesheet,
  importLeaflet,
  isMapLayerExplicit,
  markMapLayerExplicit,
  mapLayerTileLayer,
} from '../../shared/map/tile-cache/leaflet-loader';
import {
  assetsOutsideZoneCount,
  canSaveZone,
  zoneKindLabel,
  zoneLayerStyle,
  zoneVertexCountReason,
  type ZoneVertex,
} from '../../core/geofence/geofence-logic';

/** San Francisco — the same generic fallback `shared/map/flight-plan-logic.ts#FALLBACK_HOME_POINT` uses when no better center is known. */
const FALLBACK_CENTER: GeoPosition = { latitude: 37.7749, longitude: -122.4194 };
const DEFAULT_ZOOM = 13;

export interface ZoneDraft {
  readonly name: string;
  readonly polygon: readonly ZoneVertex[];
}

/**
 * The zone-boundary draw dialog (docs/plans/done/OPS-CORE-PLAN.md §G-c) — a modal, reusing
 * `shared/map/fleet-plan-dialog/flight-plan-dialog.ts`'s own click-to-add-vertices/drag-to-move
 * technique verbatim (same "click the mini-map to append a numbered marker + dashed polyline
 * preview, drag a marker to reposition, remove from a list below" shape), adapted from an ordered
 * *route* (≥2 waypoints, speed, route mode) to a *closed polygon* (≥3 vertices, no speed/altitude
 * concept here — a zone's own `maxAltitudeMeters` isn't authored by this dialog either, mirroring
 * `core/geofence/geofence-store.ts#redraw`'s own "not currently reachable from any UI surface"
 * scoping note). Single-consumer today (`features/command/zones-panel.ts`), so this lives in
 * `features/command/` rather than `shared/map/` — the "shared home once a second page needs it"
 * rule (`core/fleet/device-logic.ts`'s own doc comment) hasn't been triggered yet.
 *
 * `kind` is fixed for the dialog's whole lifetime (the caller opens either "New keep-in zone" or
 * "New keep-out zone" — there is no in-dialog toggle): the polygon preview's own color/fill
 * (`core/geofence/geofence-logic.ts#zoneLayerStyle`) matches exactly what the zone will look like
 * once saved, so the operator is drawing *with* the real visual language, not a generic shape.
 *
 * **KEEP_IN save-time advisory** (docs/plans/done/OPS-CORE-PLAN.md §G-c: "3 assets currently outside this
 * zone" — poka-yoke, informational, never blocking): `assetPositions` (every asset with a
 * currently-known position, from `CommandPage`'s own `FleetMapStore.markers()`) is compared against
 * the draft polygon via `assetsOutsideZoneCount`; shown only for `KEEP_IN` (a KEEP_OUT zone
 * "how many assets are outside" isn't the risk it's drawn to catch) and only once the polygon is
 * actually save-able (a 1-vertex polygon has no "inside" to be outside of yet).
 */
@Component({
  selector: 'vision-geofence-zone-dialog',
  imports: [FormsModule],
  templateUrl: './geofence-zone-dialog.html',
  styleUrl: './geofence-zone-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeofenceZoneDialog {
  readonly kind = input.required<ZoneKind>();
  /** Every asset's currently-known position — feeds the KEEP_IN save-time advisory only. */
  readonly assetPositions = input<readonly GeoPosition[]>([]);
  /** Seeds the mini-map's initial view near the fleet, when known — purely cosmetic, never validated. */
  readonly centerHint = input<GeoPosition | null>(null);

  readonly saved = output<ZoneDraft>();
  readonly cancelled = output<void>();

  protected readonly layers = MAP_LAYERS;
  protected readonly settings = inject(SettingsStore);
  protected readonly theme = inject(ThemeFacade);

  /** The layer actually rendered (docs/plans/done/VISUAL-REFRESH-PLAN.md F7) — see `FleetMap`'s identical
   * field's own doc comment for the full "explicit pick always wins" contract. */
  protected readonly activeLayerId = computed<MapLayerId>(() =>
    effectiveMapLayerId(this.theme.theme(), this.settings.mapLayer(), isMapLayerExplicit()),
  );

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly name = signal('');
  protected readonly vertices = signal<readonly ZoneVertex[]>([]);
  protected readonly tilesOk = signal(true);

  protected readonly kindLabel = computed(() => zoneKindLabel(this.kind()));
  protected readonly canSave = computed(() => canSaveZone(this.vertices()) && this.name().trim().length > 0);
  protected readonly vertexCountReason = computed(() => zoneVertexCountReason(this.vertices()));
  /** `null` unless the draft is a save-able KEEP_IN polygon with ≥1 asset currently outside it — the template's `@if` gate. */
  protected readonly outsideCount = computed(() => {
    if (this.kind() !== 'KEEP_IN' || !canSaveZone(this.vertices())) {
      return null;
    }
    const count = assetsOutsideZoneCount(this.vertices(), this.assetPositions());
    return count > 0 ? count : null;
  });

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private markers: Leaflet.Marker[] = [];
  private polygon: Leaflet.Polygon | null = null;

  constructor() {
    afterNextRender(() => void this.initMap());

    effect(() => this.drawVertices(this.vertices()));
    // `activeLayerId()` tracks both `settings.mapLayer()` and `theme.theme()` (docs/plans/done/VISUAL-REFRESH-PLAN.md F7).
    effect(() => this.applyLayer());

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  private center(): GeoPosition {
    return this.centerHint() ?? FALLBACK_CENTER;
  }

  private async initMap(): Promise<void> {
    const L = await importLeaflet();
    this.leaflet = L;
    ensureLeafletStylesheet();

    const center = this.center();
    const map = L.map(this.mapHost().nativeElement, { center: [center.latitude, center.longitude], zoom: DEFAULT_ZOOM });
    this.map = map;
    this.applyLayer();

    map.on('click', (event: Leaflet.LeafletMouseEvent) => {
      this.vertices.update((current) => [...current, { latitude: event.latlng.lat, longitude: event.latlng.lng }]);
    });

    this.drawVertices(this.vertices());
  }

  private drawVertices(vertices: readonly ZoneVertex[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — initMap() re-draws once it is
    }

    for (const marker of this.markers) {
      marker.remove();
    }
    this.markers = vertices.map((vertex, index) => {
      const marker = L.marker([vertex.latitude, vertex.longitude], {
        icon: this.vertexIcon(L, index + 1),
        draggable: true,
      }).addTo(map);
      // Only `dragend` writes back — mirrors `flight-plan-dialog.ts`'s identical reasoning: a live
      // `drag` tick would otherwise fight the in-progress drag by rebuilding every marker mid-gesture.
      marker.on('dragend', () => {
        const latlng = marker.getLatLng();
        this.vertices.update((current) =>
          current.map((v, i) => (i === index ? { latitude: latlng.lat, longitude: latlng.lng } : v)),
        );
      });
      return marker;
    });

    const points = vertices.map((vertex) => L.latLng(vertex.latitude, vertex.longitude));
    const style = zoneLayerStyle(this.kind());
    if (!this.polygon) {
      this.polygon = L.polygon(points, { ...style, interactive: false }).addTo(map);
    } else {
      this.polygon.setLatLngs(points);
    }
  }

  private vertexIcon(L: typeof Leaflet, order: number): Leaflet.DivIcon {
    return L.divIcon({
      className: 'zone-vertex-marker',
      html: `<div class="zone-vertex-dot">${order}</div>`,
      iconSize: [24, 24],
      iconAnchor: [12, 12],
    });
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

  /** A layer-picker click — an explicit pick always wins over the theme default from here on
   * (docs/plans/done/VISUAL-REFRESH-PLAN.md F7); see `FleetMap#setLayer`'s identical doc comment. */
  protected setLayer(id: MapLayerId): void {
    markMapLayerExplicit();
    this.settings.mapLayer.set(id);
  }

  protected removeAt(index: number): void {
    this.vertices.update((current) => current.filter((_, i) => i !== index));
  }

  protected clearVertices(): void {
    this.vertices.set([]);
  }

  protected save(): void {
    if (!this.canSave()) {
      return;
    }
    this.saved.emit({ name: this.name().trim(), polygon: this.vertices() });
  }

  protected cancel(): void {
    this.cancelled.emit();
  }

  private teardown(): void {
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
    this.markers = [];
    this.polygon = null;
  }
}
