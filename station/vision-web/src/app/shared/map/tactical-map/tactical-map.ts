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
  linkedSignal,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import { SettingsStore, type MapLayerId } from '../../../core/settings/settings-store';
import { ThemeStore } from '../../../core/shell/theme-store';
import { capitalizeLabel, formatConfidence, relativeTimeLabel } from '../../../core/events/events-logic';
import type { CorrectionResponse, GeoPosition, GeofenceZone } from '../../../core/api/models';
import { fingerprintMarkers, nextAutoFitEnabled, type FleetMarker } from '../../../core/map/map-logic';
import { resolveZoneColors, zoneLayerStyle, FALLBACK_ZONE_COLORS, type ZoneColors } from '../../../core/geofence/geofence-logic';
import {
  trackChipLabel,
  trackErrorRadiusMeters,
  trackKey,
  trackTrailPoints,
} from '../../../core/camera-geo/camera-geo-logic';
import {
  correctionRadiusMeters,
  correctionToneKey,
  geoChipLabel,
  hasCorrectionFix,
} from '../../../core/geo/geo-logic';
import {
  MAP_LAYERS,
  correctionDivIcon,
  droneDivIcon,
  effectiveMapLayerId,
  ensureLeafletStylesheet,
  importLeaflet,
  isMapLayerExplicit,
  markMapLayerExplicit,
  mapLayerTileLayer,
} from '../tile-cache/leaflet-loader';
import { Icon } from '../../ui/icon';
import { ICONS } from '../../ui/icon-registry';
import {
  AFFILIATIONS,
  BUILTIN_ASSETS_LAYER,
  BUILTIN_EVENTS_LAYER,
  BUILTIN_ZONES_LAYER,
  FALLBACK_MAP_COLORS,
  TACTICAL_MARK_KINDS,
  affiliationClass,
  affiliationCounts,
  affiliationLabel,
  appendVertex,
  arrowRotationDegrees,
  assetLegendCounts,
  builtinRows,
  completedDraft,
  copLayerIds,
  draftKindForMode,
  drawingColor,
  escapeHtml,
  isLayerHidden,
  layerRows,
  markKindCounts,
  markKindIcon,
  markKindLabel,
  markSymbolClasses,
  readHiddenLayers,
  resolveMapColors,
  toggleLayerHidden,
  visibleDrawings,
  visibleMarks,
  visibleTracks,
  writeHiddenLayers,
  zoneTooltipLabel,
  type DrawingDraft,
  type EventMarker,
  type InteractionMode,
  type LayerView,
  type MapColors,
  type MapDrawing,
  type TacticalMark,
  type TacticalTrack,
} from './tactical-map-logic';

/** Padding so the outermost markers aren't flush against the map's edge after a fit. */
const FIT_PADDING: Leaflet.PointTuple = [48, 48];

/** A single marker never gets zoomed in tighter than this, even though its own bounds has zero area. */
const FIT_MAX_ZOOM = 16;

/** Minimum zoom when centring on one asset — below this "centred" doesn't answer "where is it". */
const FOCUS_MIN_ZOOM = 15;

/** The zoom follow mode snaps to on the first fix (the old `LiveMap`'s own default). */
const FOLLOW_ZOOM = 17;

/** Long enough to outlast the `.map-shell.expanded` CSS transition (see tactical-map.css). */
const EXPAND_TRANSITION_MS = 260;

interface AssetHandle {
  marker: Leaflet.Marker;
  trailLine: Leaflet.Polyline | null;
  startFlag: Leaflet.Marker | null;
}

interface DrawingHandle {
  /** `Leaflet.Path`, not `Polyline`, because a POLYGON and a LINE share this one field. */
  shape: Leaflet.Path | null;
  decoration: Leaflet.Marker | null;
}

/** One projected track's three Leaflet objects (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5 D6, wave G5) — the dot itself, its trail, and its error-radius circle, which is drawn **always** (D6), never conditionally. */
interface TrackHandle {
  dot: Leaflet.CircleMarker;
  trail: Leaflet.Polyline;
  errorCircle: Leaflet.Circle;
}

/**
 * One visual-geolocation correction's two Leaflet objects (docs/plans/active/VISUAL-GEO-V2-PLAN.md
 * §3.8, wave H6) — a marker (hollow ring + tick, never a filled dot, so it never reads as a second
 * "real" aircraft) and its `radiusMeters` error circle, drawn **always**, the same D6 discipline
 * `TrackHandle`'s own doc comment describes ("An 18 m estimate must never render like a 2 m one").
 * No trail: a correction is "where the frame says you are right now", not a track with history —
 * the corrected-track *history* is `features/replay`'s own polyline, read from a completely
 * different endpoint (`VisionApi#geoCorrections(usageId)`), never this live layer.
 */
interface CorrectionHandle {
  marker: Leaflet.Marker;
  errorCircle: Leaflet.Circle;
}

/**
 * `<vision-tactical-map>` — the one Leaflet map in this app (docs/plans/done/MAP-REWORK-PLAN.md §5.1),
 * replacing the deleted `FleetMap` (774 ln) and `LiveMap` (437 ln), which duplicated
 * zones/marks/`escapeHtml`/layer-switch logic byte-for-byte. Every host — Command, the Fly cockpit,
 * `/live/:deviceId`, and asset detail — now embeds this component; `/live` and asset detail gained
 * the zones + marks overlays they used to silently drop (the plan's own bug fix).
 *
 * **Two modes, one component.** `[followAssetId] === null` is *fleet* mode (the old `FleetMap`):
 * plot every marker in `[assets]`, auto-fit the bounds whenever the plotted set actually moves
 * (`core/map/map-logic.ts#nextAutoFitEnabled`'s reducer), popups with Watch-live actions, detection
 * event markers. A non-null `[followAssetId]` is *follow* mode (the old `LiveMap`): one asset, its
 * breadcrumb trail, a start flag at the trail's first point, auto-follow centring, and an
 * expand-to-full-pane control. Everything else — zones, marks, drawings, the legend, the data-layer
 * panel, the basemap picker, the IndexedDB tile cache — is identical in both.
 *
 * **Self-explaining chrome** (what §5.1 means by "standalone"): a collapsible **legend** naming
 * every symbol currently on the map (affiliation frames, kind glyphs, asset states, zone kinds, all
 * with counts) and a collapsible **basemap picker** in the map's own corner. The per-layer eye
 * toggles — persisted per browser under `vision.map.hiddenLayers`, purely client-side decluttering
 * that is *orthogonal* to the server-side visibility scope (docs/plans/done/MAP-REWORK-PLAN.md §3
 * decides what the viewer may see at all; these toggles only decide what they're currently looking
 * at) — used to live in a second floating "Layers" panel here too, a few centimeters from the
 * tool-rail/topbar button that opens the *actual* Layers drawer (`<vision-layer-manager>`) under the
 * same label (`docs/conclusions/MAP-UX-RESEARCH.md` §1.1/§5 M1). That panel is gone: `builtinLayerRows`,
 * `dataRows` and `toggleLayer` below are now `public` (not `protected`) precisely so a host can wire
 * them into that drawer instead, via `viewChild(TacticalMap)` — see `LayerManager`'s own "Show on
 * map" section and `cockpit.ts`/`command.ts`'s `tacticalMap` view query. `basemaps`/
 * `activeBasemapId`/`setBasemap` are `public` for the same reason: the drawer renders its own
 * "Basemap" section from them, in addition to this component's own corner picker (relabeled
 * "Basemap", `map` icon — it no longer says "Layers" anywhere, so the two controls can no longer be
 * confused). Nothing here is a second *source* of truth — `hiddenLayers` stays this component's own
 * signal (still the "single source both the Leaflet effects and the legend counts read" the class
 * doc below describes); the drawer just calls this component's own `toggleLayer`/`setBasemap`
 * rather than owning a duplicate copy of the state.
 *
 * **Dumb by construction.** Unlike both components it replaces (which injected `FleetMapStore` /
 * `TelemetryStore` / `EventsStore` directly and therefore only worked on a page that provided
 * them), every overlay arrives as an input. The only injected state is the two root stores that
 * decide how the *basemap* renders (`SettingsStore.mapLayer` + `ThemeStore.theme`), so this
 * component drops into any page — see `tactical-map-logic.ts#followMarkers` for the one helper a
 * follow-mode host needs to turn its `TelemetryStore` into the single `[assets]` entry.
 *
 * **Zoneless gotcha (unchanged from both predecessors, and the reason this file looks imperative):**
 * Leaflet is never bound in the template. `map` and every marker/polyline/polygon live in plain
 * fields — created once, mutated from `effect()`s that read the inputs — so Angular never re-renders
 * Leaflet's own DOM and change detection is never involved in a marker moving. Consequences worth
 * knowing before editing: (1) every `apply*` method must no-op until `initMap()` has resolved the
 * dynamically-imported Leaflet chunk, and `initMap()` re-applies all of them once it has, because
 * the inputs usually carry data before the chunk lands; (2) styling anything Leaflet renders needs
 * `::ng-deep` (view encapsulation can't reach it); (3) removing a layer means calling `.remove()` on
 * the handle, never letting a template `@if` do it.
 *
 * **Popup buttons are raw HTML**, so all three (`watch`/`preview`/`openEventAsset`) are wired
 * through one delegated click listener on the map container rather than per-popup bindings — the
 * same approach `FleetMap` used, kept verbatim.
 */
@Component({
  selector: 'vision-tactical-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './tactical-map.html',
  styleUrl: './tactical-map.css',
  host: { '[class.follow-mode]': 'followMode()' },
})
export class TacticalMap {
  private readonly settings = inject(SettingsStore);
  private readonly theme = inject(ThemeStore);

  // --- Inputs (docs/plans/done/MAP-REWORK-PLAN.md §5.1's frozen superset) ----------------------------------

  /** Every plottable asset. Fleet mode plots all of them; follow mode expects the one being followed. */
  readonly assets = input<readonly FleetMarker[]>([]);

  /** `null` → fleet auto-fit mode; an assetId → follow that asset (trail, start flag, auto-follow). */
  readonly followAssetId = input<string | null>(null);

  /** Geofence zones, read-only — a styled polygon per zone plus a permanent centre label. */
  readonly zones = input<readonly GeofenceZone[]>([]);

  /**
   * Tactical marks in the display model — hosts pass `core/map-data/marks-store.ts#displayMarks`.
   * Wave D's interim `adaptMarks` input transform (old `/api/marks` shape → this one) is **gone**;
   * the input's public type never changed, only the transform that used to sit in front of it.
   */
  readonly marks = input<readonly TacticalMark[]>([]);

  /** Lines/areas/arrows/text on layers — `core/map-data/drawings-store.ts#displayDrawings`. */
  readonly drawings = input<readonly MapDrawing[]>([]);

  /** The viewer's visible layers (`core/map-data/layers-store.ts#layers`) — names the data-layer panel's rows and marks COP-layer symbols. */
  readonly layers = input<readonly LayerView[]>([]);

  /** Detection events worth plotting (Command passes `selectEventMarkers(...)`; follow-mode hosts don't). */
  readonly events = input<readonly EventMarker[]>([]);

  /**
   * Projected fixed-camera tracks (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5, wave G5) —
   * `core/map-data/tracks-store.ts#tracks`. Empty on every host that hasn't wired it yet (the
   * default), which is exactly D8's own "the feature is inert, not wrong" for an unflagged
   * deployment or a host that simply doesn't bind this input.
   */
  readonly tracks = input<readonly TacticalTrack[]>([]);

  /**
   * Visual-geolocation corrections (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) —
   * hosts pass whatever they have latest-per-asset (the Fly cockpit's `GeoStore.latest()` wrapped
   * in a single-element array; a fleet-wide host could pass one per asset). Empty on every host
   * that hasn't wired it, or whenever `vision.geo.visual.enabled` is off — the whole layer is then
   * silently absent, never an empty state (§3.8's own "Off state" row).
   */
  readonly corrections = input<readonly CorrectionResponse[]>([]);

  readonly selectedMarkId = input<string | undefined>(undefined);

  /** The asset selected elsewhere on the page — gets the app's one `--color-info` selection ring. */
  readonly selectedAssetId = input<string | undefined>(undefined);

  /** Assets with an open attention reason — recoloured `--color-danger` regardless of live/offline. */
  readonly attentionAssetIds = input<ReadonlySet<string>>(new Set());

  /**
   * A request to centre on one asset. Carries a `tick` alongside the id so re-selecting the
   * already-selected asset still re-centres (an operator who panned away must be able to click the
   * same row again). `undefined` leaves the camera alone.
   */
  readonly focusRequest = input<{ assetId: string; tick: number } | undefined>(undefined);

  /** What the next map click means — `drawings-logic.ts#resolveInteractionMode` computes it host-side. */
  readonly interactionMode = input<InteractionMode>('view');

  /**
   * Assets that have no position at all and therefore cannot be plotted — the legend's own
   * "no position" count, which this component cannot derive from `[assets]` (a marker only exists
   * for an asset that has a position). Left 0 by hosts that don't track it; the row is then hidden
   * rather than showing a fabricated zero.
   */
  readonly unplottedAssets = input<number>(0);

  // --- Outputs ----------------------------------------------------------------------------------

  /** A popup's "Open asset" — the host resolves the device and navigates to the full page. */
  readonly watch = output<string>();

  /** A streaming marker click (or its popup's inline "Watch live") — the host docks/selects it. */
  readonly preview = output<string>();

  /** An event popup's "Details" button. */
  readonly openEventAsset = output<string>();

  /** A mark symbol was clicked. */
  readonly markSelected = output<string>();

  /**
   * A click on the map's own background (never one that landed on an interactive marker — Leaflet
   * markers stop propagation). Not emitted while a drawing mode is active: there the click is a
   * vertex. The host decides whether a plain background click means anything.
   */
  readonly mapClicked = output<GeoPosition>();

  /** A drawing draft finished (double-click, Enter, or the single click of a TEXT drawing). */
  readonly drawingCompleted = output<DrawingDraft>();

  /** An existing drawing was clicked. */
  readonly drawingSelected = output<string>();

  /** The full set of layer ids currently hidden by the eye toggles (incl. the `builtin:*` overlays). */
  readonly layerVisibilityChanged = output<readonly string[]>();

  // --- View state --------------------------------------------------------------------------------

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');

  protected readonly followMode = computed(() => this.followAssetId() !== null);

  protected readonly autoFit = signal(true);
  protected readonly autoFollow = signal(true);
  protected readonly expanded = signal(false);
  protected readonly tilesOk = signal(true);

  /**
   * The legend defaults to open on a full-page fleet map and closed on a follow-mode inset (§5.1's
   * "defaults per host container width", resolved by mode rather than by measuring the DOM — the
   * two follow-mode hosts are the two small insets). A `linkedSignal` rather than a plain one
   * because the inputs aren't readable yet when fields initialize; the operator's own toggle wins
   * from then on.
   */
  protected readonly legendOpen = linkedSignal(() => !this.followMode());
  /** The map's own corner panel — basemap picker only since M1 (see the class doc's "Self-explaining chrome"). */
  protected readonly basemapPanelOpen = signal(false);

  /** Client-side eye toggles, restored from `vision.map.hiddenLayers` (see the class doc). */
  protected readonly hiddenLayers = signal<readonly string[]>(readHiddenLayers());

  /** The four switchable basemaps — `public`: also the `<vision-layer-manager>` drawer's own "Basemap" section (see the class doc's M1 note). */
  readonly basemaps = MAP_LAYERS;

  /**
   * The basemap actually rendered — the operator's explicit pick once they have used the picker,
   * otherwise the current theme's own default (docs/plans/done/VISUAL-REFRESH-PLAN.md F7).
   * `isMapLayerExplicit()` reads a genuine signal now (`leaflet-loader.ts#explicitMapLayer` — see its
   * own doc comment), not a bare `localStorage` read — a `computed()` calling a plain read would be
   * invisible to Angular's dependency graph, so `setBasemap`'s `settings.mapLayer.set(id)` call could
   * no-op (an `Object.is`-equal re-pick of whatever the theme default already was) with nothing left
   * to invalidate this computed at all; see `vision-web/MODULE.md` Gotchas for the incident.
   * `public` for the same reason as {@link basemaps} — see the class doc's M1 note.
   */
  readonly activeBasemapId = computed<MapLayerId>(() =>
    effectiveMapLayerId(this.theme.theme(), this.settings.mapLayer(), isMapLayerExplicit()),
  );

  /**
   * Every Leaflet-paint-layer colour (trail, drawing swatches, zone stroke/fill) this map currently
   * draws with — read from the live theme, never a frozen import-time snapshot (`tactical-map-logic.ts#resolveMapColors`'s
   * own comment has the full "why" and the bug this replaces). Refreshed by {@link refreshMapColors},
   * called once the map exists, again whenever the basemap changes (`applyBasemap`'s own effect), and
   * — independently — on every theme flip via its own dedicated effect below, since {@link
   * activeBasemapId} stops tracking theme at all the moment a basemap becomes explicit (a computed's
   * version only bumps when its *recomputed value* changes, and an explicit pick's own id never does
   * on a theme flip — see that effect's own doc comment for the full mechanism), so a drawn line
   * matches its own token in whichever theme is actually active, live, not just at the next full remount.
   */
  protected readonly mapColors = signal<MapColors>(FALLBACK_MAP_COLORS);
  protected readonly zoneColors = signal<ZoneColors>(FALLBACK_ZONE_COLORS);

  // Overlay collections after the eye toggles have been applied — the single source both the
  // Leaflet effects and the legend counts read, so the panel and the map can never disagree.
  protected readonly shownAssets = computed(() =>
    isLayerHidden(this.hiddenLayers(), BUILTIN_ASSETS_LAYER) ? [] : this.assets(),
  );
  protected readonly shownZones = computed(() =>
    isLayerHidden(this.hiddenLayers(), BUILTIN_ZONES_LAYER) ? [] : this.zones(),
  );
  protected readonly shownEvents = computed(() =>
    isLayerHidden(this.hiddenLayers(), BUILTIN_EVENTS_LAYER) ? [] : this.events(),
  );
  protected readonly shownMarks = computed(() => visibleMarks(this.marks(), this.hiddenLayers()));
  protected readonly shownDrawings = computed(() => visibleDrawings(this.drawings(), this.hiddenLayers()));
  protected readonly shownTracks = computed(() => visibleTracks(this.tracks(), this.hiddenLayers()));

  /** The data-layer eye-toggle rows — `public`, rendered by `<vision-layer-manager>`'s "Show on map" section (M1). */
  readonly dataRows = computed(() =>
    layerRows(this.layers(), this.marks(), this.drawings(), this.hiddenLayers()),
  );
  /** The built-in-overlay eye-toggle rows (Assets/Zones/Events) — `public` for the same reason as {@link dataRows}. */
  readonly builtinLayerRows = computed(() =>
    builtinRows(
      { assets: this.assets().length, zones: this.zones().length, events: this.events().length },
      this.hiddenLayers(),
    ),
  );

  protected readonly assetCounts = computed(() =>
    assetLegendCounts(this.shownAssets(), this.attentionAssetIds(), this.unplottedAssets()),
  );
  protected readonly affiliationRows = computed(() => {
    const counts = affiliationCounts(this.shownMarks());
    return AFFILIATIONS.map((affiliation) => ({
      affiliation,
      label: affiliationLabel(affiliation),
      className: affiliationClass(affiliation),
      count: counts[affiliation],
    }));
  });
  protected readonly kindRows = computed(() => {
    const counts = markKindCounts(this.shownMarks());
    return TACTICAL_MARK_KINDS.map((kind) => ({
      kind,
      label: markKindLabel(kind),
      icon: markKindIcon(kind),
      count: counts[kind],
    })).filter((row) => row.count > 0);
  });
  protected readonly hasMarks = computed(() => this.shownMarks().length > 0);
  protected readonly hasTracks = computed(() => this.shownTracks().length > 0);
  /** No layer-visibility filter — `CorrectionResponse` carries no `layerId` (asset-scoped, not layer-scoped); only rows with an actual fix are plottable (see `applyCorrections`). */
  protected readonly hasCorrections = computed(() => this.corrections().some((c) => hasCorrectionFix(c)));

  // --- Leaflet state (plain fields — see the class doc's zoneless gotcha) -------------------------

  private leaflet: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private tileLayer: Leaflet.TileLayer | null = null;
  private readonly assetHandles = new Map<string, AssetHandle>();
  private readonly eventHandles = new Map<string, Leaflet.Marker>();
  private readonly zoneHandles = new Map<string, Leaflet.Polygon>();
  private readonly markHandles = new Map<string, Leaflet.Marker>();
  private readonly drawingHandles = new Map<string, DrawingHandle>();
  private readonly trackHandles = new Map<string, TrackHandle>();
  private readonly correctionHandles = new Map<string, CorrectionHandle>();
  private draftLine: Leaflet.Polyline | null = null;
  private suppressAutoFitDisable = false;
  private lastFitFingerprint: string | null = null;
  private hasCentered = false;
  private generation = 0;

  /** The drawing being built by successive clicks — plain field for the same reason the Leaflet handles are. */
  private draft = signal<DrawingDraft | null>(null);

  constructor() {
    afterNextRender(() => void this.initMap());

    // Basemap: `activeBasemapId()` tracks both `settings.mapLayer()` and `theme.theme()` while no
    // explicit pick has been made, so a theme flip re-tiles the map exactly like an explicit pick
    // does. No-op until `initMap()` has run.
    effect(() => this.applyBasemap());

    // Paint-layer colours (trail/drawing/zone strokes) must resync on *every* theme flip, even once
    // an explicit basemap pick makes `activeBasemapId()` stop depending on theme at all — a computed
    // only re-notifies its own consumers when its recomputed value actually changes (`Object.is`),
    // and an explicit pick's id is the same string regardless of theme, so folding this into the
    // `applyBasemap` effect above (keyed only on `activeBasemapId()`) would silently stop re-running
    // the moment a basemap becomes explicit — exactly docs/plans/done/VISUAL-REFRESH-PLAN.md §3's own
    // invariant, broken. Kept as its own effect, tracking `theme.theme()` directly, rather than
    // restoring `theme.theme()` as a tracked read inside `applyBasemap()` itself, so a theme flip
    // never forces a needless tile-layer teardown/re-add (a visible flicker) when the basemap image
    // itself hasn't changed — only the colours that need to.
    effect(() => {
      this.theme.theme();
      if (this.leaflet && this.map) {
        this.refreshMapColors();
      }
    });

    // Centres on each focus request. `untracked` is load-bearing: `centerOnAsset` reads `assets()`,
    // which changes on every telemetry tick, so a tracked read would re-centre the map underneath an
    // operator who had since panned away. Depending on `focusRequest()` alone fires once per request.
    effect(() => {
      const request = this.focusRequest();
      if (request) {
        untracked(() => this.centerOnAsset(request.assetId));
      }
    });

    // Assets: redraw every tick (cheap DOM mutation), but decide *camera* moves separately — a
    // refit only when the plotted set actually moved (`fingerprintMarkers`), and in follow mode a
    // recentre only while auto-follow is on or nothing has been centred yet.
    effect(() => {
      const assets = this.shownAssets();
      // Tracked explicitly so a change to either re-icons every marker even when `assets` is empty.
      this.attentionAssetIds();
      this.selectedAssetId();
      const follow = this.followAssetId();
      const autoFollow = this.autoFollow();
      // Tracked so a theme flip recolors an already-drawn trail immediately, not just on next redraw.
      const trailColor = this.mapColors().trail;
      this.applyAssets(assets, follow, trailColor);
      if (follow !== null) {
        this.applyFollow(assets, follow, autoFollow);
      } else if (this.autoFit()) {
        const fingerprint = fingerprintMarkers(assets);
        if (fingerprint !== this.lastFitFingerprint) {
          this.lastFitFingerprint = fingerprint;
          this.fitToAssets(assets);
        }
      }
    });

    // Every other overlay is its own independent layer, deliberately excluded from auto-fit: a zone,
    // a mark, or a stale event far from the fleet must never yank the camera off the assets.
    effect(() => this.applyEvents(this.shownEvents()));
    effect(() => this.applyZones(this.shownZones(), this.zoneColors()));
    effect(() => this.applyMarks(this.shownMarks(), this.selectedMarkId(), copLayerIds(this.layers())));
    effect(() => this.applyDrawings(this.shownDrawings(), this.mapColors()));
    effect(() => this.applyTracks(this.shownTracks(), this.mapColors()));
    effect(() => this.applyCorrections(this.corrections(), this.mapColors()));
    effect(() => this.applyDraft(this.draft(), this.mapColors().trail));

    // Leaflet sizes itself from the DOM at creation time; expanding/collapsing resizes that DOM out
    // from under it, so it must be told to remeasure — twice, since the transition takes a moment.
    effect((onCleanup) => {
      this.expanded();
      this.map?.invalidateSize();
      const timer = setTimeout(() => this.map?.invalidateSize(), EXPAND_TRANSITION_MS);
      onCleanup(() => clearTimeout(timer));
    });

    // Enter completes a drawing draft, Escape abandons it — registered only while a draft exists.
    effect((onCleanup) => {
      if (!this.draft()) {
        return;
      }
      const onKeydown = (event: KeyboardEvent): void => {
        if (event.key === 'Escape') {
          this.draft.set(null);
        } else if (event.key === 'Enter') {
          this.completeDraft();
        }
      };
      document.addEventListener('keydown', onKeydown);
      onCleanup(() => document.removeEventListener('keydown', onKeydown));
    });

    // A mode switch abandons whatever was half-drawn — a draft belongs to the mode that started it.
    // Drawing modes also take Leaflet's double-click away from zooming, since a double-click is how
    // a line/polygon draft is completed.
    effect(() => {
      const drawing = draftKindForMode(this.interactionMode()) !== undefined;
      untracked(() => {
        this.draft.set(null);
        if (drawing) {
          this.map?.doubleClickZoom.disable();
        } else {
          this.map?.doubleClickZoom.enable();
        }
      });
    });

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  // --- Chrome actions ----------------------------------------------------------------------------

  /** The map's only path back to auto-fit once a manual pan/zoom has disabled it (fleet mode). */
  protected recenter(): void {
    this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'recenterClicked'));
    const assets = this.shownAssets();
    this.lastFitFingerprint = fingerprintMarkers(assets);
    this.fitToAssets(assets);
  }

  protected toggleFollow(): void {
    this.autoFollow.update((value) => !value);
  }

  protected toggleExpanded(): void {
    this.expanded.update((value) => !value);
  }

  protected toggleLegend(): void {
    this.legendOpen.update((value) => !value);
  }

  protected toggleBasemapPanel(): void {
    this.basemapPanelOpen.update((value) => !value);
  }

  /** An eye toggle — client-side only, persisted per browser, announced to the host. `public`: also called by the `<vision-layer-manager>` drawer's "Show on map" section (M1). */
  toggleLayer(layerId: string): void {
    const next = toggleLayerHidden(this.hiddenLayers(), layerId);
    this.hiddenLayers.set(next);
    writeHiddenLayers(next);
    this.layerVisibilityChanged.emit(next);
  }

  /** A basemap pick always wins over the theme default from here on (docs/plans/done/VISUAL-REFRESH-PLAN.md F7). `public` for the same reason as {@link toggleLayer}. */
  setBasemap(id: MapLayerId): void {
    markMapLayerExplicit();
    this.settings.mapLayer.set(id);
  }

  // --- Map bootstrap ------------------------------------------------------------------------------

  private async initMap(): Promise<void> {
    const generation = ++this.generation;
    const L = await importLeaflet();
    if (generation !== this.generation) {
      return; // destroyed before the chunk finished loading
    }
    this.leaflet = L;
    ensureLeafletStylesheet();

    // `zoomControl: false` + re-added at `bottomright`: Leaflet's default corner is `topleft`, where
    // the basemap panel lives (the eye-toggle data-layer panel that used to share that corner moved
    // into the `<vision-layer-manager>` drawer — M1). `bottomright` is the one corner this template
    // never claims (the view controls sit topright, the legend + tiles badge bottomleft).
    const map = L.map(this.mapHost().nativeElement, { center: [0, 0], zoom: 2, zoomControl: false });
    this.map = map;
    L.control.zoom({ position: 'bottomright' }).addTo(map);

    this.applyBasemap();

    map.on('movestart zoomstart', () => {
      if (!this.suppressAutoFitDisable) {
        this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'userInteraction'));
      }
    });

    // Popup buttons are raw HTML — one delegated listener instead of a binding per popup.
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

    // Leaflet's own `click` — fires only for the map's own background (a marker or a zone tooltip
    // stops it), so this component never has to work out what was clicked.
    map.on('click', (event: Leaflet.LeafletMouseEvent) => {
      this.onBackgroundClick({ latitude: event.latlng.lat, longitude: event.latlng.lng });
    });
    map.on('dblclick', () => this.completeDraft());
    if (draftKindForMode(this.interactionMode()) !== undefined) {
      map.doubleClickZoom.disable(); // mounted straight into a drawing mode
    }

    // The inputs usually carry data before the Leaflet chunk lands — re-apply every layer now.
    // `applyBasemap()` above already resolved `mapColors`/`zoneColors` from the live theme.
    const assets = this.shownAssets();
    const follow = this.followAssetId();
    this.applyAssets(assets, follow, this.mapColors().trail);
    if (follow !== null) {
      this.applyFollow(assets, follow, this.autoFollow());
    } else if (this.autoFit()) {
      this.lastFitFingerprint = fingerprintMarkers(assets);
      this.fitToAssets(assets);
    }
    this.applyEvents(this.shownEvents());
    this.applyZones(this.shownZones(), this.zoneColors());
    this.applyMarks(this.shownMarks(), this.selectedMarkId(), copLayerIds(this.layers()));
    this.applyDrawings(this.shownDrawings(), this.mapColors());
    this.applyTracks(this.shownTracks(), this.mapColors());
    this.applyCorrections(this.corrections(), this.mapColors());
  }

  /**
   * Swaps the active basemap — a no-op until the map exists (`initMap()` re-applies once it does).
   * Also re-resolves {@link refreshMapColors} first: this effect re-fires whenever `activeBasemapId()`
   * actually changes (an explicit pick, or a theme flip while nothing has been explicitly picked
   * yet), so the paint-layer colours catch up alongside every basemap swap. **Not** the only place
   * colours are refreshed, though — the constructor's own dedicated theme effect (see its doc
   * comment) covers the case this one can't: a theme flip once a basemap *is* explicit, where
   * `activeBasemapId()` never changes value at all.
   */
  private applyBasemap(): void {
    const L = this.leaflet;
    if (!L || !this.map) {
      return;
    }
    this.refreshMapColors();
    this.tileLayer?.remove();
    this.tileLayer = mapLayerTileLayer(L, this.activeBasemapId(), (ok) => this.tilesOk.set(ok));
    this.tileLayer.addTo(this.map);
  }

  /**
   * Reads every Leaflet-paint-layer colour from the live theme, off the element that actually
   * paints (`this.mapHost()`, not `document.documentElement`/`:root`) — a Fly cockpit inset lives
   * inside a `.surface-dark` enclave that is dark in *both* themes, so resolving against this
   * component's own host is what makes the trail/drawing/zone colours correct on both hosts without
   * this component ever having to know which one it's mounted in. See
   * `tactical-map-logic.ts#resolveMapColors` / `geofence-logic.ts#resolveZoneColors` for the pure
   * resolution rules this only supplies a DOM reader for.
   */
  private refreshMapColors(): void {
    const el = this.mapHost().nativeElement;
    const readVar = (name: string): string => getComputedStyle(el).getPropertyValue(name);
    this.mapColors.set(resolveMapColors(readVar));
    this.zoneColors.set(resolveZoneColors(readVar));
  }

  // --- Assets -------------------------------------------------------------------------------------

  private applyAssets(assets: readonly FleetMarker[], followAssetId: string | null, trailColor: string): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return; // map chunk/instance not ready yet — `initMap()` re-applies once it is
    }
    const seen = new Set<string>();
    for (const asset of assets) {
      seen.add(asset.assetId);
      this.upsertAsset(L, map, asset, asset.assetId === followAssetId, trailColor);
    }
    for (const assetId of [...this.assetHandles.keys()]) {
      if (!seen.has(assetId)) {
        this.removeAsset(assetId);
      }
    }
  }

  private upsertAsset(L: typeof Leaflet, map: Leaflet.Map, asset: FleetMarker, followed: boolean, trailColor: string): void {
    const point = L.latLng(asset.position.latitude, asset.position.longitude);
    let handle = this.assetHandles.get(asset.assetId);

    if (!handle) {
      const marker = L.marker(point, { icon: this.assetIcon(L, asset), keyboard: false }).addTo(map);
      handle = { marker, trailLine: null, startFlag: null };
      this.assetHandles.set(asset.assetId, handle);
      if (!this.followMode()) {
        // Clicking a *streaming* marker docks the preview (in addition to the popup Leaflet opens on
        // the same click). `live` is re-read at click time, since a marker can go offline long after
        // this listener was attached. Follow mode has nothing to preview — one asset, already shown.
        marker.on('click', () => {
          const current = this.assets().find((candidate) => candidate.assetId === asset.assetId);
          if (current?.live) {
            this.preview.emit(asset.assetId);
          }
        });
      }
    } else {
      handle.marker.setLatLng(point);
      handle.marker.setIcon(this.assetIcon(L, asset));
    }

    // Popups belong to fleet mode: they carry the Watch-live actions a fleet overview needs, and the
    // follow-mode inset deliberately stayed popup-free (its host page already shows this asset).
    if (!this.followMode()) {
      const html = this.assetPopupHtml(asset);
      const popup = handle.marker.getPopup();
      if (popup) {
        popup.setContent(html); // updates in place without closing an already-open popup
      } else {
        handle.marker.bindPopup(html);
      }
    }

    // A trail for every streaming asset (fleet mode) and always for the followed one.
    const wantsTrail = (asset.live || followed) && asset.trail.length > 0;
    if (wantsTrail) {
      if (!handle.trailLine) {
        handle.trailLine = L.polyline([], { color: trailColor, weight: followed ? 3 : 2, opacity: 0.85 }).addTo(map);
      } else {
        // Re-stated every call, not just at creation — a theme flip re-resolves `trailColor` and
        // this is what makes an already-drawn trail catch up immediately (`setLatLngs` alone never
        // touches an existing path's own style).
        handle.trailLine.setStyle({ color: trailColor });
      }
      handle.trailLine.setLatLngs(asset.trail.map((p) => L.latLng(p.latitude, p.longitude)));
    } else if (handle.trailLine) {
      handle.trailLine.remove();
      handle.trailLine = null;
    }

    // The start flag marks where this flight began — follow mode only, as before.
    if (followed && asset.trail.length > 0) {
      const start = L.latLng(asset.trail[0].latitude, asset.trail[0].longitude);
      if (!handle.startFlag) {
        handle.startFlag = L.marker(start, { icon: this.flagIcon(L), keyboard: false, interactive: false }).addTo(map);
      } else {
        handle.startFlag.setLatLng(start);
      }
    } else if (handle.startFlag) {
      handle.startFlag.remove();
      handle.startFlag = null;
    }
  }

  private removeAsset(assetId: string): void {
    const handle = this.assetHandles.get(assetId);
    handle?.marker.remove();
    handle?.trailLine?.remove();
    handle?.startFlag?.remove();
    this.assetHandles.delete(assetId);
  }

  /**
   * One asset glyph, state carried by colour only (docs/plans/done/VISUAL-REFRESH-PLAN.md F7): a
   * heading-rotated arrow while streaming, a dot while offline, `--color-danger` when the asset needs
   * attention, a `--color-info` ring when selected. Own assets are always friendly by definition
   * (docs/plans/done/MAP-REWORK-PLAN.md §5.1), which is why they keep this rounded glyph rather than ever
   * taking a hostile/unknown frame — affiliation symbology applies to *marks*.
   */
  private assetIcon(L: typeof Leaflet, asset: FleetMarker): Leaflet.DivIcon {
    const modifiers = `${this.attentionAssetIds().has(asset.assetId) ? ' attention' : ''}${
      asset.assetId === this.selectedAssetId() ? ' selected' : ''
    }`;
    if (asset.live) {
      return droneDivIcon(L, asset.headingDegrees ?? 0, `asset-marker live${modifiers}`);
    }
    return L.divIcon({
      className: `asset-marker offline${modifiers}`,
      html: '<div class="offline-dot"></div>',
      iconSize: [14, 14],
      iconAnchor: [7, 7],
    });
  }

  private flagIcon(L: typeof Leaflet): Leaflet.DivIcon {
    return L.divIcon({
      className: 'start-flag',
      html: '<div class="flag-glyph">⚑</div>',
      iconSize: [18, 18],
      iconAnchor: [2, 16],
    });
  }

  private assetPopupHtml(asset: FleetMarker): string {
    const rows: string[] = [
      `<div class="popup-title">${escapeHtml(asset.displayName)}</div>`,
      `<div class="popup-meta">${escapeHtml(asset.categoryName)}</div>`,
      `<span class="chip ${asset.live ? 'ok' : ''}">${asset.live ? 'Streaming' : 'Offline'}</span>`,
    ];
    if (asset.flightMode !== undefined) {
      const failsafeStyle = asset.failsafe === true ? ' style="color: var(--color-danger); font-weight: 600;"' : '';
      rows.push(`<div class="popup-row"${failsafeStyle}>Mode ${escapeHtml(asset.flightMode)}</div>`);
    }
    if (asset.batteryPercent !== undefined) {
      rows.push(`<div class="popup-row">Battery ${asset.batteryPercent.toFixed(0)}%</div>`);
    }
    if (asset.position.altitudeMeters !== undefined) {
      rows.push(`<div class="popup-row">Altitude ${asset.position.altitudeMeters.toFixed(0)} m</div>`);
    }
    if (asset.sampleAgeSeconds !== undefined) {
      rows.push(`<div class="popup-row faint">Updated ${asset.sampleAgeSeconds.toFixed(0)}s ago</div>`);
    }
    if (asset.live) {
      rows.push(
        `<button type="button" class="btn small secondary preview-btn" data-asset-id="${escapeHtml(asset.assetId)}" title="Watch live, inline beside the map">Watch live</button>`,
      );
    }
    rows.push(
      `<button type="button" class="btn small watch-btn" data-asset-id="${escapeHtml(asset.assetId)}" title="Watch live on its own page">Open asset</button>`,
    );
    return `<div class="map-popup">${rows.join('')}</div>`;
  }

  // --- Camera -------------------------------------------------------------------------------------

  /**
   * Centres on one asset. Turns auto-fit **off** first: its own effect re-fits to every marker
   * whenever the plotted set moves, so leaving it on would pull the camera back on the next
   * telemetry tick. Deliberately *not* wrapped in `suppressAutoFitDisable` — this move genuinely
   * should count as "the camera stopped following the fleet". Silent no-op for an asset with no
   * plotted marker: centring on a position we don't have would mean inventing one.
   */
  private centerOnAsset(assetId: string): void {
    const L = this.leaflet;
    const asset = this.assets().find((candidate) => candidate.assetId === assetId);
    if (!L || !this.map || !asset) {
      return;
    }
    this.autoFit.set(nextAutoFitEnabled(this.autoFit(), 'assetFocused'));
    this.map.setView(L.latLng(asset.position.latitude, asset.position.longitude), Math.max(this.map.getZoom(), FOCUS_MIN_ZOOM), {
      animate: true,
    });
  }

  private fitToAssets(assets: readonly FleetMarker[]): void {
    const L = this.leaflet;
    if (!L || !this.map || assets.length === 0) {
      return;
    }
    const bounds = L.latLngBounds(assets.map((asset) => L.latLng(asset.position.latitude, asset.position.longitude)));
    this.suppressAutoFitDisable = true;
    this.map.fitBounds(bounds, { padding: FIT_PADDING, maxZoom: FIT_MAX_ZOOM, animate: false });
    this.suppressAutoFitDisable = false;
  }

  /**
   * Follow mode's camera: keep recentring while auto-follow is on; otherwise only the very first fix
   * centres the view, so an operator who has since panned away is not yanked back on every poll.
   */
  private applyFollow(assets: readonly FleetMarker[], followAssetId: string, autoFollow: boolean): void {
    const L = this.leaflet;
    const asset = assets.find((candidate) => candidate.assetId === followAssetId);
    if (!L || !this.map) {
      return;
    }
    if (!asset) {
      this.hasCentered = false;
      return;
    }
    if (!autoFollow && this.hasCentered) {
      return;
    }
    const point = L.latLng(asset.position.latitude, asset.position.longitude);
    this.suppressAutoFitDisable = true;
    this.map.setView(point, this.hasCentered ? this.map.getZoom() : FOLLOW_ZOOM, { animate: this.hasCentered });
    this.suppressAutoFitDisable = false;
    this.hasCentered = true;
  }

  // --- Detection events ---------------------------------------------------------------------------

  private applyEvents(events: readonly EventMarker[]): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const event of events) {
      if (!event.position) {
        continue; // `selectEventMarkers` already filters these — defensive only
      }
      seen.add(event.id);
      const point = L.latLng(event.position.latitude, event.position.longitude);
      const html = this.eventPopupHtml(event);
      let marker = this.eventHandles.get(event.id);
      if (!marker) {
        marker = L.marker(point, { icon: this.eventIcon(L, event), keyboard: false, zIndexOffset: -100 })
          .addTo(map)
          .bindPopup(html);
        this.eventHandles.set(event.id, marker);
      } else {
        marker.setLatLng(point);
        marker.setIcon(this.eventIcon(L, event));
        marker.getPopup()?.setContent(html);
      }
    }
    for (const id of [...this.eventHandles.keys()]) {
      if (!seen.has(id)) {
        this.eventHandles.get(id)?.remove();
        this.eventHandles.delete(id);
      }
    }
  }

  private eventIcon(L: typeof Leaflet, event: EventMarker): Leaflet.DivIcon {
    return L.divIcon({
      className: `event-marker ${event.state === 'OPEN' ? 'event-marker-open' : 'event-marker-closed'}`,
      html: '<div class="event-marker-dot"></div>',
      iconSize: [14, 14],
      iconAnchor: [7, 7],
    });
  }

  private eventPopupHtml(event: EventMarker): string {
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
      rows.push('<div class="popup-row faint">No asset resolved for this event.</div>');
    }
    return `<div class="map-popup event-popup">${rows.join('')}</div>`;
  }

  // --- Geofence zones (read-only) ------------------------------------------------------------------

  private applyZones(zones: readonly GeofenceZone[], colors: ZoneColors): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const zone of zones) {
      seen.add(zone.id);
      const style = zoneLayerStyle(zone.kind, zone.enabled, colors);
      const points = zone.polygon.map((vertex) => L.latLng(vertex.latitude, vertex.longitude));
      const label = escapeHtml(zoneTooltipLabel(zone));
      let polygon = this.zoneHandles.get(zone.id);
      if (!polygon) {
        polygon = L.polygon(points, { ...style, interactive: false }).addTo(map);
        polygon.bindTooltip(label, { permanent: true, direction: 'center', className: 'zone-label' });
        this.zoneHandles.set(zone.id, polygon);
      } else {
        polygon.setLatLngs(points);
        polygon.setStyle(style);
        polygon.setTooltipContent(label);
      }
    }
    for (const id of [...this.zoneHandles.keys()]) {
      if (!seen.has(id)) {
        this.zoneHandles.get(id)?.remove();
        this.zoneHandles.delete(id);
      }
    }
  }

  // --- Marks (interactive) --------------------------------------------------------------------------

  private applyMarks(
    marks: readonly TacticalMark[],
    selectedMarkId: string | undefined,
    copLayers: ReadonlySet<string>,
  ): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const mark of marks) {
      seen.add(mark.id);
      const point = L.latLng(mark.position.latitude, mark.position.longitude);
      const icon = this.markIcon(L, mark, mark.id === selectedMarkId, copLayers.has(mark.layerId));
      const tooltip = escapeHtml(`${markKindLabel(mark.kind)} · ${affiliationLabel(mark.affiliation)}: ${mark.label}`);
      let marker = this.markHandles.get(mark.id);
      if (!marker) {
        // Deliberately not `draggable`: a mark records where something *was observed*, so nudging
        // its symbol with a mouse would silently rewrite an observation. Correcting a wrong
        // position is a re-report, not a gesture.
        marker = L.marker(point, { icon, keyboard: false }).addTo(map);
        marker.on('click', () => this.markSelected.emit(mark.id));
        marker.bindTooltip(tooltip, { direction: 'top', offset: [0, -14] });
        this.markHandles.set(mark.id, marker);
      } else {
        marker.setLatLng(point);
        marker.setIcon(icon);
        marker.setTooltipContent(tooltip);
      }
    }
    for (const id of [...this.markHandles.keys()]) {
      if (!seen.has(id)) {
        this.markHandles.get(id)?.remove();
        this.markHandles.delete(id);
      }
    }
  }

  /** The APP-6-inspired symbol: an affiliation-shaped frame (CSS) with the kind's own glyph inside. */
  private markIcon(L: typeof Leaflet, mark: TacticalMark, selected: boolean, cop: boolean): Leaflet.DivIcon {
    const size = selected ? 28 : 24;
    return L.divIcon({
      className: markSymbolClasses(mark, { selected, cop }),
      html: `<span class="sym-frame"></span><span class="sym-glyph">${this.glyphSvg(mark)}</span>`,
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2],
    });
  }

  /**
   * The kind glyph as raw SVG. `ICONS` is the same closed, compile-time registry `<vision-icon>`
   * renders — never user input — but a divIcon is raw HTML, not an Angular template, so the markup
   * is inlined here rather than instantiating a component per marker.
   */
  private glyphSvg(mark: TacticalMark): string {
    return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">${ICONS[markKindIcon(mark.kind)]}</svg>`;
  }

  // --- Projected tracks (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5 D6/D7, wave G5) ----------------

  /**
   * Draws each track as three coupled Leaflet objects: a small dot at its own current position, a
   * polyline through its (decimated) trail, and — **always**, never behind a toggle or a data check
   * — a circle of radius `errorRadiusMeters` under the dot (D6's own "a 200m-error estimate must
   * never render as a 5m-accurate-looking dot"). One shared colour (`colors.trail`, the same token
   * an asset's own breadcrumb trail already uses) rather than a colour per track: D3 tracks are a
   * single honest layer, not a categorical set that needs its own palette — the stable id chip
   * (bound as a permanent tooltip, mirroring `applyZones`'s own centre label) is what tells two
   * tracks apart, not colour.
   */
  private applyTracks(tracks: readonly TacticalTrack[], colors: MapColors): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const track of tracks) {
      const key = trackKey(track.assetId, track.trackId);
      seen.add(key);
      const point = L.latLng(track.latitude, track.longitude);
      const trailPoints = trackTrailPoints(track).map((p) => L.latLng(p.latitude, p.longitude));
      const radius = trackErrorRadiusMeters(track);
      const label = escapeHtml(trackChipLabel(track));

      let handle = this.trackHandles.get(key);
      if (!handle) {
        const dot = L.circleMarker(point, {
          radius: 6,
          color: colors.trail,
          weight: 2,
          fill: true,
          fillColor: colors.trail,
          fillOpacity: 0.9,
          interactive: false,
        }).addTo(map);
        dot.bindTooltip(label, { permanent: true, direction: 'top', offset: [0, -8], className: 'track-label' });
        const trail = L.polyline(trailPoints, { color: colors.trail, weight: 2, opacity: 0.7, interactive: false }).addTo(map);
        const errorCircle = L.circle(point, {
          radius,
          color: colors.trail,
          weight: 1,
          opacity: 0.5,
          fill: true,
          fillColor: colors.trail,
          fillOpacity: 0.12,
          interactive: false,
        }).addTo(map);
        handle = { dot, trail, errorCircle };
        this.trackHandles.set(key, handle);
      } else {
        handle.dot.setLatLng(point);
        handle.dot.setTooltipContent(label);
        handle.trail.setLatLngs(trailPoints);
        handle.errorCircle.setLatLng(point);
        handle.errorCircle.setRadius(radius);
      }
    }
    for (const key of [...this.trackHandles.keys()]) {
      if (!seen.has(key)) {
        const handle = this.trackHandles.get(key);
        handle?.dot.remove();
        handle?.trail.remove();
        handle?.errorCircle.remove();
        this.trackHandles.delete(key);
      }
    }
  }

  // --- Visual-geolocation corrections (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.8, wave H6) --------

  /**
   * Draws each correction that has an actual fix (`hasCorrectionFix` — a `NO_FIX` row carries no
   * `latitude`/`longitude` at all, so there is nothing to plot; the cockpit chip/popover are where a
   * `NO_FIX` shows, verbatim, not here) as a hollow-ring-plus-tick marker (never a filled dot — §3.8's
   * "visually secondary to the raw one") plus its `radiusMeters` error circle, drawn **always**, the
   * same D6 discipline `applyTracks` follows: "An 18 m estimate must never render like a 2 m one".
   * Keyed by `assetId` directly (unlike tracks' composite key) since §3.3 has no per-correction id and
   * a correction is inherently one-per-asset.
   */
  private applyCorrections(corrections: readonly CorrectionResponse[], colors: MapColors): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const correction of corrections) {
      if (!hasCorrectionFix(correction)) {
        continue; // NO_FIX / not yet computed — nothing to plot at
      }
      seen.add(correction.assetId);
      const point = L.latLng(correction.latitude as number, correction.longitude as number);
      const divergent = correctionToneKey(correction) === 'warn';
      const color = divergent ? colors.warn : colors.trail;
      const radius = correctionRadiusMeters(correction);
      const label = escapeHtml(geoChipLabel(correction));

      let handle = this.correctionHandles.get(correction.assetId);
      if (!handle) {
        const marker = L.marker(point, {
          icon: correctionDivIcon(L, correction.yawDegrees, divergent),
          interactive: false,
          keyboard: false,
        }).addTo(map);
        marker.bindTooltip(label, { permanent: true, direction: 'top', offset: [0, -8], className: 'geo-correction-label' });
        const errorCircle = L.circle(point, {
          radius,
          color,
          weight: 1,
          opacity: 0.5,
          fill: true,
          fillColor: color,
          fillOpacity: 0.12,
          interactive: false,
        }).addTo(map);
        handle = { marker, errorCircle };
        this.correctionHandles.set(correction.assetId, handle);
      } else {
        handle.marker.setLatLng(point);
        handle.marker.setIcon(correctionDivIcon(L, correction.yawDegrees, divergent));
        handle.marker.setTooltipContent(label);
        handle.errorCircle.setLatLng(point);
        handle.errorCircle.setRadius(radius);
        handle.errorCircle.setStyle({ color, fillColor: color });
      }
    }
    for (const assetId of [...this.correctionHandles.keys()]) {
      if (!seen.has(assetId)) {
        const handle = this.correctionHandles.get(assetId);
        handle?.marker.remove();
        handle?.errorCircle.remove();
        this.correctionHandles.delete(assetId);
      }
    }
  }

  // --- Drawings -------------------------------------------------------------------------------------

  private applyDrawings(drawings: readonly MapDrawing[], colors: MapColors): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    const seen = new Set<string>();
    for (const drawing of drawings) {
      seen.add(drawing.id);
      this.upsertDrawing(L, map, drawing, colors);
    }
    for (const id of [...this.drawingHandles.keys()]) {
      if (!seen.has(id)) {
        this.removeDrawing(id);
      }
    }
  }

  private upsertDrawing(L: typeof Leaflet, map: Leaflet.Map, drawing: MapDrawing, colors: MapColors): void {
    // Geometry changes are rare and cheap to rebuild; recreating avoids having to reconcile a shape
    // that switched kind (a polyline can't become a polygon in place) — this also means a theme flip
    // (this method's own `colors` re-resolving) recolors every drawing for free on its next run,
    // since nothing here is skipped for an already-existing shape.
    this.removeDrawing(drawing.id);
    const color = drawingColor(drawing.colorToken, colors);
    const points = drawing.points.map((point) => L.latLng(point.latitude, point.longitude));
    const handle: DrawingHandle = { shape: null, decoration: null };

    if (drawing.kind === 'TEXT') {
      handle.decoration = L.marker(points[0], {
        icon: L.divIcon({ className: 'draw-text', html: `<span>${escapeHtml(drawing.label ?? '')}</span>`, iconSize: [0, 0] }),
        keyboard: false,
      }).addTo(map);
      handle.decoration.on('click', () => this.drawingSelected.emit(drawing.id));
    } else {
      const shape: Leaflet.Path =
        drawing.kind === 'POLYGON'
          ? L.polygon(points, { color, weight: 2, fillOpacity: 0.1 }).addTo(map)
          : L.polyline(points, { color, weight: 3 }).addTo(map);
      shape.on('click', () => this.drawingSelected.emit(drawing.id));
      if (drawing.label) {
        shape.bindTooltip(escapeHtml(drawing.label), { direction: 'top' });
      }
      handle.shape = shape;
      if (drawing.kind === 'ARROW' && points.length >= 2) {
        handle.decoration = L.marker(points[points.length - 1], {
          icon: L.divIcon({
            className: 'draw-arrowhead',
            html: `<div class="arrowhead" style="border-bottom-color:${color};transform:rotate(${arrowRotationDegrees(drawing.points)}deg)"></div>`,
            iconSize: [14, 14],
            iconAnchor: [7, 7],
          }),
          keyboard: false,
          interactive: false,
        }).addTo(map);
      }
    }
    this.drawingHandles.set(drawing.id, handle);
  }

  private removeDrawing(id: string): void {
    const handle = this.drawingHandles.get(id);
    handle?.shape?.remove();
    handle?.decoration?.remove();
    this.drawingHandles.delete(id);
  }

  /** The in-progress draft, rendered as a dashed rubber band so the operator sees what they're building. */
  private applyDraft(draft: DrawingDraft | null, trailColor: string): void {
    const L = this.leaflet;
    const map = this.map;
    if (!L || !map) {
      return;
    }
    if (!draft || draft.points.length === 0) {
      this.draftLine?.remove();
      this.draftLine = null;
      return;
    }
    const points = draft.points.map((point) => L.latLng(point.latitude, point.longitude));
    if (!this.draftLine) {
      this.draftLine = L.polyline(points, { color: trailColor, weight: 2, dashArray: '5 5' }).addTo(map);
    } else {
      this.draftLine.setLatLngs(points);
      this.draftLine.setStyle({ color: trailColor });
    }
  }

  /**
   * A background click: a vertex while a drawing mode is active, otherwise the plain `mapClicked`
   * passthrough the host decides what to do with (Wave E's mark palette is one such host).
   */
  private onBackgroundClick(position: GeoPosition): void {
    const kind = draftKindForMode(this.interactionMode());
    if (!kind) {
      this.mapClicked.emit(position);
      return;
    }
    const next = appendVertex(this.draft() ?? { kind, points: [] }, position);
    this.draft.set(next);
    if (kind === 'TEXT') {
      this.completeDraft(); // a text annotation is exactly one point — no second click to wait for
    }
  }

  private completeDraft(): void {
    const draft = this.draft();
    if (!draft) {
      return;
    }
    const completed = completedDraft(draft);
    this.draft.set(null);
    if (completed) {
      this.drawingCompleted.emit(completed);
    }
  }

  private teardown(): void {
    this.generation++;
    for (const assetId of [...this.assetHandles.keys()]) {
      this.removeAsset(assetId);
    }
    for (const marker of this.eventHandles.values()) {
      marker.remove();
    }
    this.eventHandles.clear();
    for (const polygon of this.zoneHandles.values()) {
      polygon.remove();
    }
    this.zoneHandles.clear();
    for (const marker of this.markHandles.values()) {
      marker.remove();
    }
    this.markHandles.clear();
    for (const id of [...this.drawingHandles.keys()]) {
      this.removeDrawing(id);
    }
    for (const handle of this.correctionHandles.values()) {
      handle.marker.remove();
      handle.errorCircle.remove();
    }
    this.correctionHandles.clear();
    this.draftLine?.remove();
    this.draftLine = null;
    this.map?.remove();
    this.map = null;
    this.tileLayer = null;
  }
}
