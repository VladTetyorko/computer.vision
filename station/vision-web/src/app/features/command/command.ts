import { ChangeDetectionStrategy, Component, computed, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UiStore } from '../../core/ui/ui-store';
import { MapFacade } from '../../core/map/map-facade';
import { RouteFacade } from '../../core/map-data/route-facade';
import { WeatherFacade } from '../../core/weather/weather-facade';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { WeatherChip } from '../../shared/ui/weather-chip';
import { Notice } from '../../shared/ui/notice';
import { DrawingToolbar } from '../../shared/map/map-controls/drawing-toolbar';
import { MapTools, type MapToolsCapabilities } from '../../shared/map/map-controls/map-tools/map-tools';
import { AssetPanel } from './asset-panel';
import { SetupChecklist } from './setup-checklist';
import { CommandRailRow } from './rail-row';
import { CommandFacade } from './command-facade';
import { MarksFacade } from '../../core/map-data/marks-facade';
import { LayersFacade } from '../../core/map-data/layers-facade';
import { DrawingsFacade } from '../../core/map-data/drawings-facade';
import { GeofenceFacade } from '../../core/geofence/geofence-facade';
import { OrgFacade } from '../../core/org/org-facade';

/**
 * `/command` — the manager dashboard (docs/plans/done/UX-REWORK-PLAN.md §U-c, superseding docs/plans/done/MVP3-PLAN.md
 * §C-c's stacked-cards layout with the plan's own map-first, three-panel model: FlytBase Fleet View
 * 2.0's "full-bleed map as canvas, slim entity rail docked left, detail panel docked right").
 *
 * **Dumb by design** (docs/plans/done/UI-ARCHITECTURE-PLAN.md wave W2): every read-model/command the template
 * uses lives on `CommandFacade` (`command-facade.ts`) — this component injects only that facade plus
 * its own `UiStore` for the Zones panel's open/closed state (the one mutually-exclusive overlay this
 * page has). See `CommandFacade`'s own class doc comment for the full "what moved and why" account
 * of the pre-refactor page this class used to be.
 *
 * **Layout**: `<vision-tactical-map>` fills the entire stage between two independently collapsible
 * docked panels (`command-logic.ts#commandGridColumns` computes the grid — see its own doc comment
 * for why these are real grid-track siblings, not `position: absolute` overlays, and how that
 * avoids the map's own zoom/layer controls entirely by construction rather than z-index
 * coordination). Both panels persist their collapsed state per user (`core/panel-state.ts`,
 * docs/plans/done/UX-REWORK-PLAN.md §U-b item 7's "explicit collapse, reopen via toggle chip" — mirrors
 * `features/live/live.ts`'s `railOpen`/`features/fly/fly.ts`'s `mapVisible` exactly) — now owned by
 * the facade, not this component.
 *
 * **Selecting an asset** (a rail row, or `<vision-tactical-map>`'s own `(preview)` output — a direct
 * marker click, unchanged component behavior) opens the right `<vision-asset-panel>`. Everything
 * that panel shows is data the facade already has from its own existing pollers — **zero new
 * recurring requests** — see `CommandFacade`'s own doc comment for the full accounting.
 *
 * **`<vision-live-dock>` is no longer used here** — the old "docked preview beside the map" role is
 * the asset panel's own Video tab. `<vision-tactical-map>`'s `(preview)` handler is retargeted from
 * "resolve a device and dock `LiveDock`" to "select this asset" (`CommandFacade.selectAsset`).
 *
 * **The map component is dumb now** (docs/plans/done/MAP-REWORK-PLAN.md §5.1 Wave D): the deleted `FleetMap`
 * injected `MapFacade`/`EventsStore` itself; `<vision-tactical-map>` takes `[assets]`/`[events]`/
 * `[unplottedAssets]` as plain inputs from `CommandFacade` instead. Every other binding — zones,
 * marks, focus, attention, selection, and all three outputs — is unchanged.
 */
@Component({
  selector: 'vision-command',
  imports: [TacticalMap, AssetPanel, SetupChecklist, CommandRailRow, MapTools, DrawingToolbar, WeatherChip, RouterLink, Notice],
  templateUrl: './command.html',
  styleUrl: './command.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `MapFacade`/`WeatherFacade`/`RouteFacade`: own instance per route activation (page-provided,
  // not `providedIn: 'root'` — see their own class doc comments). `CommandFacade` is provided
  // alongside them so it can `inject()` all three; `<vision-weather-chip>` still resolves
  // `WeatherFacade` through this same component-level injector, while the map now receives its
  // markers/routes as inputs instead.
  // The map-data facades joined this list in wave N4 (NGRX-MIGRATION-PLAN.md §9): they are
  // page-provided now, so their slices ride this page's route instead of the root injector.
  // Needed by this page's own facade *and* by every control inside `<vision-map-tools>`.
  providers: [MapFacade, WeatherFacade, RouteFacade, CommandFacade, MarksFacade, LayersFacade, DrawingsFacade, GeofenceFacade, OrgFacade],
})
export class CommandPage {
  protected readonly facade = inject(CommandFacade);

  /**
   * `?asset=<id>` deep link (docs/plans/done/UX-REWORK-PLAN.md §U-c's "preserve ?asset deep links if map
   * supported any"). Query params bind to inputs by name/alias automatically
   * (`withComponentInputBinding()`, `app.config.ts`) — no route-table change needed. An `input()`
   * can only be declared on the component itself, so `CommandFacade.trackRequestedAsset` is handed
   * this signal-accessor once below rather than owning the input itself.
   */
  readonly requestedAssetId = input<string | undefined>(undefined, { alias: 'asset' });

  /**
   * The stage map's own component instance — `undefined` while `facade.mapIsEmpty()` (no assets
   * registered yet, `command.html`'s own empty state renders instead). A type-based `viewChild`
   * query rather than a template `#ref`, mirroring `cockpit.ts#tacticalMap`'s identical doc comment
   * — used to feed the Layers panel's "Show on map"/"Basemap" sections (docs/conclusions/MAP-UX-RESEARCH.md
   * M1).
   */
  protected readonly tacticalMap = viewChild(TacticalMap);

  /**
   * The one Map tools drawer (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2) — Command's four
   * former overlays (Zones/Marks/Layers/Draw, each its own topbar button and its own shell: a
   * backdrop modal, two side-panel drawers, and a floating card) are now one door onto one drawer
   * with four fixed-order sections. `CommandOverlay`'s union collapses to this single `'map-tools'`
   * id (§3.2's own wording); still a `UiStore` group, not a bare `signal<boolean>`, per
   * `architecture.spec.ts`'s own rule that every overlay flag routes through one — a future second
   * overlay on this page (there is none today) then costs nothing to add correctly. Kept as a plain
   * field on the component, not the facade, mirroring the retired `overlay` field's own precedent:
   * this is template-rendering state, not domain state.
   */
  private readonly overlay = new UiStore();

  protected mapToolsOpen(): boolean {
    return this.overlay.isOpen('map-tools');
  }

  /** `/command`'s own fixed capability set (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2's table)
   * — every section on, `layers: 'manage'` since this is the one surface layer administration
   * (create/rename/delete/grants) renders on. No `cockpit`: there is no one drone this page is flying. */
  protected readonly mapToolsCapabilities: MapToolsCapabilities = {
    marks: true,
    layers: 'manage',
    draw: true,
    zones: true,
  };

  /** The HUD button's own badge — marks + drawings + zones, so a manager glancing at the map's corner
   * sees how much is already on the picture before opening the drawer. */
  protected readonly mapToolsBadge = computed(
    () => this.facade.marks.marks().length + this.facade.drawings.displayDrawings().length + this.facade.zones().length,
  );

  protected toggleMapTools(): void {
    if (this.overlay.isOpen('map-tools')) {
      this.overlay.close();
    } else {
      this.overlay.open('map-tools');
    }
  }

  protected closeMapTools(): void {
    this.overlay.close('map-tools');
  }

  constructor() {
    this.facade.trackRequestedAsset(this.requestedAssetId);

    // Tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — see `fly.ts`'s identical effect's own doc
    // comment: a map click always produces a `MarksFacade.draft()` regardless of whether the drawer
    // happens to be open; this is what keeps a draft from landing out of sight.
    effect(() => {
      if (this.facade.marks.draft()) {
        this.overlay.open('map-tools');
      }
    });
  }
}
