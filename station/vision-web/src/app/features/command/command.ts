import { ChangeDetectionStrategy, Component, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UiStore } from '../../core/ui/ui-store';
import { FleetMapStore } from '../../core/map/map-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { WeatherChip } from '../../shared/ui/weather-chip';
import { Notice } from '../../shared/ui/notice';
import { SidePanel } from '../../shared/ui/side-panel';
import { DrawingToolbar } from '../../shared/map/map-controls/drawing-toolbar';
import { LayerManager } from '../../shared/map/map-controls/layer-manager';
import { AssetPanel } from './asset-panel';
import { ZonesPanel } from './zones-panel';
import { MarksPanel } from './marks-panel';
import { SetupChecklist } from './setup-checklist';
import { CommandRailRow } from './rail-row';
import { CommandFacade } from './command-facade';

/** Command's mutually-exclusive overlay group (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — Zones, Marks (since
 * docs/plans/done/TACTICAL-MARKS-PLAN.md M5), and, since docs/plans/done/MAP-REWORK-PLAN.md §5.2, **Layers** (the layer
 * manager) and **Draw** (the drawing toolbar card floating over the map); typed as a union (not a
 * bare string) so a typo'd id can't compile, mirroring `flight-command-panel.ts#CommandDialog`'s
 * identical precedent. **Deliberately different shells**: Zones stays its pre-existing full backdrop
 * modal (`<vision-zones-panel>`'s own `role="dialog" aria-modal="true"`), while Marks and Layers use
 * the non-blocking `<vision-side-panel>` drawer shell (no backdrop) and Draw is a small card pinned
 * over the map — a true modal would swallow every click on the map underneath, which
 * create-by-map-click and drawing-by-map-click both need to still reach.
 *
 * All four share one group, so opening Draw closes Marks and vice versa. That is deliberate rather
 * than incidental: both arm the map's single `[interactionMode]`, and two open panels each claiming
 * the next click is exactly the drift `UiStore` exists to prevent. */
type CommandOverlay = 'zones' | 'marks' | 'layers' | 'draw';

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
 * injected `FleetMapStore`/`EventsStore` itself; `<vision-tactical-map>` takes `[assets]`/`[events]`/
 * `[unplottedAssets]` as plain inputs from `CommandFacade` instead. Every other binding — zones,
 * marks, focus, attention, selection, and all three outputs — is unchanged.
 */
@Component({
  selector: 'vision-command',
  imports: [
    TacticalMap,
    AssetPanel,
    ZonesPanel,
    MarksPanel,
    SetupChecklist,
    CommandRailRow,
    SidePanel,
    LayerManager,
    DrawingToolbar,
    WeatherChip,
    RouterLink,
    Notice,
  ],
  templateUrl: './command.html',
  styleUrl: './command.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `FleetMapStore`/`WeatherStore`: own instance per route activation (page-provided, not
  // `providedIn: 'root'` — see their own class doc comments). `CommandFacade` is provided alongside
  // them so it can `inject()` both; `<vision-weather-chip>` still resolves `WeatherStore` through
  // this same component-level injector, while the map now receives its markers as inputs instead.
  providers: [FleetMapStore, WeatherStore, CommandFacade],
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
   * The Zones panel's own mutually-exclusive overlay group (docs/plans/done/UI-ARCHITECTURE-PLAN.md) —
   * generalizes the old `zonesPanelOpen` boolean `signal(false)` into a `UiStore`, transient (no
   * `storageKey`, matching the prior behavior: the panel never survived a reload either). Kept as a
   * plain field on the component, not the facade — mirrors `flight-command-panel.ts`'s own `dialog`
   * field precedent: overlay open/closed-ness is template-rendering state the facade's read-models
   * don't otherwise need, not domain state.
   */
  private readonly overlay = new UiStore();

  protected isOverlayOpen(id: CommandOverlay): boolean {
    return this.overlay.isOpen(id);
  }

  protected toggleZonesPanel(): void {
    this.overlay.toggle('zones' satisfies CommandOverlay);
  }

  protected toggleMarksPanel(): void {
    this.overlay.toggle('marks' satisfies CommandOverlay);
  }

  protected toggleLayersPanel(): void {
    this.overlay.toggle('layers' satisfies CommandOverlay);
  }

  /** Shows/hides the drawing toolbar card. Closing it also stops any drawing in progress, so the map
   * never stays armed behind a toolbar the manager can no longer see. */
  protected toggleDrawToolbar(): void {
    this.overlay.toggle('draw' satisfies CommandOverlay);
    if (!this.overlay.isOpen('draw')) {
      this.facade.drawings.stopDrawing();
    }
  }

  constructor() {
    this.facade.trackRequestedAsset(this.requestedAssetId);

    // Tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — see `fly.ts`'s identical effect's own doc
    // comment: a map click always produces a `MarksStore.draft()` regardless of whether the Marks
    // panel happens to be open; this is what keeps a draft from landing out of sight.
    effect(() => {
      if (this.facade.marks.draft()) {
        this.overlay.open('marks' satisfies CommandOverlay);
      }
    });
  }
}
