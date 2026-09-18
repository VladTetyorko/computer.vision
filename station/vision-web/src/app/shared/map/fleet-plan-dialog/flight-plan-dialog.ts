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
import type { RouteMode } from '../../../core/api/models';
import { SettingsFacade, type MapLayerId } from '../../../core/settings/settings-facade';
import { ThemeFacade } from '../../../core/shell/theme-facade';
import {
  MAP_LAYERS,
  effectiveMapLayerId,
  ensureLeafletStylesheet,
  importLeaflet,
  isMapLayerExplicit,
  markMapLayerExplicit,
  mapLayerTileLayer,
} from '../tile-cache/leaflet-loader';
import {
  DEFAULT_ROUTE_MODE,
  DEFAULT_SPEED_MPS,
  FALLBACK_HOME_POINT,
  addWaypoint,
  canSavePlan,
  formatManualWaypoints,
  moveWaypoint,
  parseManualWaypoints,
  removeWaypoint,
  seedTriangle,
  updateWaypointAltitude,
  type EditorWaypoint,
  type FlightPlanForm,
  type HomePoint,
} from '../flight-plan-logic';

const DEFAULT_ZOOM = 14;

/**
 * The flight-plan map editor (docs/main/CYCLES-PLAN.md §7, CT-b) — a modal dialog, not embedded inline
 * in either host's form, so it can be reused verbatim by two unrelated pages: the Devices page's
 * Add-source Simulate step (file/testDrone modes) and the Map tab's "Add a test drone" empty-state
 * action. Living in `shared/map/` rather than either `features/devices/` or `features/map/` follows
 * the same "no feature imports another feature's module" precedent as
 * `shared/map/live-map.ts`/`core/fleet/device-logic.ts` (see their own doc comments) — a shared
 * home instead of a cross-feature import.
 *
 * Click the mini-map to append a waypoint (numbered marker + a dashed polyline preview); drag a
 * marker to reposition it (only `dragend` writes back to the waypoint list — a live `drag` tick
 * would otherwise fight the in-progress drag by rebuilding every marker on each pixel of movement);
 * remove/altitude edits happen in the waypoint list below the map, mirroring CT-b's own spec text
 * ("clicking appends waypoints … remove buttons in a list, drag to adjust if cheap"). A collapsed
 * "Enter coordinates manually" disclosure holds the `lat,lon[,altM]`-per-line text fallback for a
 * user who'd rather type/paste than click. Reuses `shared/map/leaflet-loader.ts`'s dynamic import + the
 * same switchable base-layer catalogue every other map in this app uses.
 *
 * A fresh dialog seeds `seedTriangle(home)` — the demo's own 3-waypoint patrol shape, repositioned
 * near whichever home point the host already has (or a generic fallback) — so "hit Save" alone
 * produces a sensible, immediately-flyable route; `initialPlan` (re-opening via "Edit flight plan")
 * seeds from the caller's already-saved draft instead.
 */
@Component({
  selector: 'vision-flight-plan-dialog',
  imports: [FormsModule],
  templateUrl: './flight-plan-dialog.html',
  styleUrl: './flight-plan-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlightPlanDialog {
  /** The wizard's own home-point fields, when set — seeds the demo triangle near them. */
  readonly homeLatitude = input<number | null>(null);
  readonly homeLongitude = input<number | null>(null);
  /** Re-opening an already-drawn plan ("Edit flight plan") seeds from this instead of a fresh seed. */
  readonly initialPlan = input<FlightPlanForm | null>(null);

  readonly saved = output<FlightPlanForm>();
  readonly cancelled = output<void>();

  protected readonly layers = MAP_LAYERS;
  protected readonly settings = inject(SettingsFacade);
  protected readonly theme = inject(ThemeFacade);

  /** The layer actually rendered (docs/plans/done/VISUAL-REFRESH-PLAN.md F7) — see `FleetMap`'s identical
   * field's own doc comment for the full "explicit pick always wins" contract. */
  protected readonly activeLayerId = computed<MapLayerId>(() =>
    effectiveMapLayerId(this.theme.theme(), this.settings.mapLayer(), isMapLayerExplicit()),
  );

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly waypoints = signal<readonly EditorWaypoint[]>([]);
  protected readonly speedMps = signal<number | null>(DEFAULT_SPEED_MPS);
  protected readonly routeMode = signal<RouteMode>(DEFAULT_ROUTE_MODE);
  protected readonly manualText = signal('');
  protected readonly tilesOk = signal(true);

  protected readonly canSave = computed(() => canSavePlan(this.waypoints()));

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private markers: Leaflet.Marker[] = [];
  private polyline: Leaflet.Polyline | null = null;

  constructor() {
    const initial = this.initialPlan();
    if (initial) {
      this.waypoints.set([...initial.waypoints]);
      this.speedMps.set(initial.speedMps);
      this.routeMode.set(initial.routeMode);
    } else {
      this.waypoints.set(seedTriangle(this.home()));
    }
    this.manualText.set(formatManualWaypoints(this.waypoints()));

    afterNextRender(() => void this.initMap());

    effect(() => this.drawWaypoints(this.waypoints()));
    // `activeLayerId()` tracks both `settings.mapLayer()` and `theme.theme()` (docs/plans/done/VISUAL-REFRESH-PLAN.md F7).
    effect(() => this.applyLayer());

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  private home(): HomePoint | null {
    const latitude = this.homeLatitude();
    const longitude = this.homeLongitude();
    return latitude !== null && longitude !== null ? { latitude, longitude } : null;
  }

  private async initMap(): Promise<void> {
    const L = await importLeaflet();
    this.leaflet = L;
    ensureLeafletStylesheet();

    const first = this.waypoints()[0];
    const center = this.home() ?? (first ? { latitude: first.latitude, longitude: first.longitude } : FALLBACK_HOME_POINT);

    const map = L.map(this.mapHost().nativeElement, { center: [center.latitude, center.longitude], zoom: DEFAULT_ZOOM });
    this.map = map;
    this.applyLayer();

    this.polyline = L.polyline([], { color: '#4f8cff', weight: 3, opacity: 0.85, dashArray: '6 8' }).addTo(map);

    map.on('click', (event: Leaflet.LeafletMouseEvent) => {
      this.waypoints.update((current) =>
        addWaypoint(current, { latitude: event.latlng.lat, longitude: event.latlng.lng }),
      );
    });

    this.drawWaypoints(this.waypoints());
  }

  private drawWaypoints(waypoints: readonly EditorWaypoint[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — initMap() re-draws once it is
    }

    for (const marker of this.markers) {
      marker.remove();
    }
    this.markers = waypoints.map((waypoint, index) => {
      const marker = L.marker([waypoint.latitude, waypoint.longitude], {
        icon: this.waypointIcon(L, index + 1),
        draggable: true,
      }).addTo(map);
      // Only `dragend` writes back — see class doc comment.
      marker.on('dragend', () => {
        const latlng = marker.getLatLng();
        this.waypoints.update((current) =>
          moveWaypoint(current, index, { latitude: latlng.lat, longitude: latlng.lng }),
        );
      });
      return marker;
    });

    this.polyline?.setLatLngs(waypoints.map((waypoint) => L.latLng(waypoint.latitude, waypoint.longitude)));
  }

  private waypointIcon(L: typeof Leaflet, order: number): Leaflet.DivIcon {
    return L.divIcon({
      className: 'waypoint-marker',
      html: `<div class="waypoint-dot">${order}</div>`,
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
    this.waypoints.update((current) => removeWaypoint(current, index));
  }

  protected setAltitude(index: number, raw: string): void {
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      this.waypoints.update((current) => updateWaypointAltitude(current, index, null));
      return;
    }
    const parsed = Number(trimmed);
    if (Number.isFinite(parsed)) {
      this.waypoints.update((current) => updateWaypointAltitude(current, index, parsed));
    }
  }

  /** Re-syncs the manual textarea to whatever's currently on the map — a "start from here" reset. */
  protected loadCurrentIntoManualText(): void {
    this.manualText.set(formatManualWaypoints(this.waypoints()));
  }

  /** Replaces the whole waypoint list from the manual text — the "manual lat,lon fallback"'s Apply action. */
  protected applyManualText(): void {
    const parsed = parseManualWaypoints(this.manualText());
    if (parsed.length > 0) {
      this.waypoints.set(parsed);
    }
  }

  protected save(): void {
    if (!this.canSave()) {
      return;
    }
    this.saved.emit({ waypoints: this.waypoints(), speedMps: this.speedMps(), routeMode: this.routeMode() });
  }

  protected cancel(): void {
    this.cancelled.emit();
  }

  private teardown(): void {
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
    this.markers = [];
    this.polyline = null;
  }
}
