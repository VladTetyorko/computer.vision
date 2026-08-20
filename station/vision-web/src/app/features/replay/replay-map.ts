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
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import type { GeoPosition } from '../../core/api/models';
import { SettingsStore, type MapLayerId } from '../../core/settings/settings-store';
import { ThemeStore } from '../../core/shell/theme-store';
import {
  MAP_LAYERS,
  droneDivIcon,
  effectiveMapLayerId,
  ensureLeafletStylesheet,
  importLeaflet,
  isMapLayerExplicit,
  markMapLayerExplicit,
  mapLayerTileLayer,
} from '../../shared/map/tile-cache/leaflet-loader';
import { FALLBACK_MAP_COLORS, resolveMapColors, type MapColors } from '../../shared/map/tactical-map/tactical-map-logic';

const DEFAULT_ZOOM = 17;

/**
 * The flight-replay cockpit's map (docs/plans/done/MVP2-PLAN.md §R, R-b) — a breadcrumb trail drawn up to the
 * scrub position plus a heading-rotated marker at that position. Purely presentational: driven by
 * `[trail]`/`[markerPosition]`/`[markerHeadingDegrees]` inputs from `ReplayPage`'s own scrub-time
 * derivations (`replay-logic.ts#trailPrefix`/`nearestSample`), not a store — replay has no live
 * poller to DI-share the way `shared/map/live-map.ts` shares `TelemetryStore`.
 *
 * Reuses the shared Leaflet bootstrap (`shared/map/leaflet-loader.ts`: dynamic `import('leaflet')`, the
 * runtime stylesheet injection, the four-layer `MAP_LAYERS` switcher, the rotated `droneDivIcon`)
 * exactly like `shared/map/live-map.ts`/`shared/map/fleet-map.ts`/`shared/map/flight-plan-dialog.ts` — a fourth home
 * for the same shared module, per its own "intra-app DRY" doc comment. Lives in `features/replay/`
 * rather than `shared/map/` since only this page uses it, mirroring `shared/map/live-map.ts`'s own
 * original home (`pages/live/live-map.ts`) before a second page needed it — see that module's doc
 * comment for the "move to a shared home only once a second page actually needs it" precedent this
 * follows.
 *
 * Unlike `LiveMap`'s always-on auto-follow, this map fits the view to the *whole* route once
 * (`[fullTrail]`, independent of the scrub-bounded `[trail]`) so a referee sees the entire flight's
 * shape immediately — replay is reviewed, not chased. `autoFollow` (default off, a toggle mirroring
 * `LiveMap`'s own) optionally recenters on the marker as the scrub position moves; "Fit route"
 * re-runs the initial fit on demand after the user has panned/zoomed away.
 *
 * **Basemap follows the same theme-aware/explicit-pick mechanism every other map in this app uses**
 * (docs/plans/done/VISUAL-REFRESH-PLAN.md F7 — `activeLayerId`/`setLayer`, mirroring `TacticalMap`/
 * `GeofenceZoneDialog`/`FlightPlanDialog`'s identical trio). This page used to bypass it entirely —
 * rendering `settings.mapLayer()` raw and never calling `markMapLayerExplicit()` on a pick — which
 * meant a fresh light-theme profile showed `/command` basemap-highlighted Standard while `/replay`
 * showed Night highlighted from the very same persisted `mapLayer` value, and picking Satellite here
 * was silently discarded the moment `/command` (or any other host) recomputed its own theme-aware
 * default over that same raw signal. Fixed so this is one shared basemap contract, not five slightly
 * different ones.
 *
 * **Corrected track (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6)** — `[correctedTrail]`
 * draws a second, dashed polyline over the raw one whenever `GET /api/geo/corrections?usageId=` has
 * anything to show; empty (the default) simply draws nothing, the honest "absent, not empty" degrade
 * §3.8's own Off-state row requires. Colored from the live theme (`resolveMapColors`, reused from
 * `TacticalMap`'s own module) rather than a hardcoded literal, unlike the pre-existing raw
 * `trailLine` beside it — out of this wave's scope to also migrate.
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

  /**
   * The visual-geolocation corrected track, up to the scrub position (docs/plans/active/VISUAL-GEO-V2-PLAN.md
   * §3.8, wave H6) — `ReplayFacade#correctedTrail`, itself `core/geo/geo-logic.ts#correctedTrailPoints`
   * over `GET /api/geo/corrections?usageId=`. Empty on a flight with no corrections (disabled flag,
   * or none computed) — `applyScrub` below then simply clears the second polyline to nothing, the
   * same "absent, not empty" degrade every other geo surface in this wave follows.
   */
  readonly correctedTrail = input<readonly GeoPosition[]>([]);

  protected readonly settings = inject(SettingsStore);
  protected readonly theme = inject(ThemeStore);
  protected readonly layers = MAP_LAYERS;
  protected readonly tilesOk = signal(true);
  protected readonly autoFollow = signal(false);

  /** The layer actually rendered (docs/plans/done/VISUAL-REFRESH-PLAN.md F7) — see `TacticalMap`'s
   * identical `activeBasemapId`/`GeofenceZoneDialog`'s `activeLayerId` for the shared contract this
   * mirrors: an explicit picker click always wins over the theme-implied default. */
  protected readonly activeLayerId = computed<MapLayerId>(() =>
    effectiveMapLayerId(this.theme.theme(), this.settings.mapLayer(), isMapLayerExplicit()),
  );

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private marker: Leaflet.Marker | null = null;
  private trailLine: Leaflet.Polyline | null = null;
  /** The corrected-track polyline (§3.8) — dashed, `colors.trail`, visually secondary to the raw
   * `trailLine` above, same "hollow ring, not a filled dot" secondary treatment `TacticalMap`'s own
   * correction marker uses, translated to a line. */
  private correctedTrailLine: Leaflet.Polyline | null = null;
  private hasFitRoute = false;
  private generation = 0;

  /** Read from the live theme, never a frozen import-time snapshot — same reasoning as
   * `TacticalMap#mapColors`'s own doc comment. Only the corrected-track line reads this; the raw
   * trail/marker keep their pre-existing literal styling unchanged (out of this wave's scope). */
  protected readonly mapColors = signal<MapColors>(FALLBACK_MAP_COLORS);

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

    effect(() => this.applyLayer(this.activeLayerId()));

    // The corrected track (§3.8) is its own independent overlay — scrubs alongside the raw trail
    // but never gates on it (a flight with a raw trail and no corrections, or vice versa, still
    // draws whichever it actually has).
    effect(() => this.applyCorrectedTrail(this.correctedTrail()));

    // Theme flips recolor the corrected line — mirrors `TacticalMap`'s own dedicated theme effect
    // (its own doc comment has the full "why a separate effect, not folded into applyLayer" case).
    effect(() => {
      this.theme.theme();
      if (this.leaflet && this.map) {
        this.refreshMapColors();
      }
    });

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /** A layer-picker click — an explicit pick always wins over the theme default from here on
   * (docs/plans/done/VISUAL-REFRESH-PLAN.md F7); see `TacticalMap#setBasemap`'s identical doc comment. */
  protected setLayer(id: MapLayerId): void {
    markMapLayerExplicit();
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
    this.applyLayer(this.activeLayerId());
    this.refreshMapColors();

    this.trailLine = L.polyline([], { color: '#4f8cff', weight: 3, opacity: 0.85 }).addTo(map);
    this.correctedTrailLine = L.polyline([], {
      color: this.mapColors().trail,
      weight: 2,
      opacity: 0.75,
      dashArray: '6 5',
    }).addTo(map);
    this.marker = L.marker([0, 0], { icon: droneDivIcon(L, 0), opacity: 0, keyboard: false }).addTo(map);

    this.fitRoute(this.fullTrail());
    this.applyScrub(this.trail(), this.markerPosition(), this.markerHeadingDegrees(), this.autoFollow());
    this.applyCorrectedTrail(this.correctedTrail());
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

  /** The corrected track (§3.8) — a second, dashed polyline; no marker/heading of its own (the raw
   * marker above already shows "where the drone is now" — this line only ever needs to show the
   * corrected *path*, not a second aircraft icon). */
  private applyCorrectedTrail(correctedTrail: readonly GeoPosition[]): void {
    const L = this.leaflet;
    if (!L || !this.correctedTrailLine) {
      return; // map chunk/instance not ready yet — initMap() re-applies once it is
    }
    this.correctedTrailLine.setLatLngs(correctedTrail.map((position) => L.latLng(position.latitude, position.longitude)));
  }

  /** Re-resolves {@link mapColors} from the live theme and repaints the corrected line with it — mirrors `TacticalMap#refreshMapColors`. */
  private refreshMapColors(): void {
    const el = this.mapHost().nativeElement;
    const readVar = (name: string): string => getComputedStyle(el).getPropertyValue(name);
    const colors = resolveMapColors(readVar);
    this.mapColors.set(colors);
    this.correctedTrailLine?.setStyle({ color: colors.trail });
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
    this.correctedTrailLine = null;
  }
}
