import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UiStore } from '../../core/ui/ui-store';
import { FleetMapStore } from '../../core/map/map-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { FleetMap } from '../../shared/map/fleet-map/fleet-map';
import { WeatherChip } from '../../shared/ui/weather-chip';
import { Notice } from '../../shared/ui/notice';
import { AssetPanel } from './asset-panel';
import { ZonesPanel } from './zones-panel';
import { CommandFacade } from './command-facade';

/** Command's one mutually-exclusive overlay group (docs/UI-ARCHITECTURE-PLAN.md) — today just the
 * Zones panel, but typed as a union (not a bare string) so a future second overlay can't typo its id
 * past the compiler, mirroring `flight-command-panel.ts#CommandDialog`'s identical precedent. */
type CommandOverlay = 'zones';

/**
 * `/command` — the manager dashboard (docs/UX-REWORK-PLAN.md §U-c, superseding docs/MVP3-PLAN.md
 * §C-c's stacked-cards layout with the plan's own map-first, three-panel model: FlytBase Fleet View
 * 2.0's "full-bleed map as canvas, slim entity rail docked left, detail panel docked right").
 *
 * **Dumb by design** (docs/UI-ARCHITECTURE-PLAN.md wave W2): every read-model/command the template
 * uses lives on `CommandFacade` (`command-facade.ts`) — this component injects only that facade plus
 * its own `UiStore` for the Zones panel's open/closed state (the one mutually-exclusive overlay this
 * page has). See `CommandFacade`'s own class doc comment for the full "what moved and why" account
 * of the pre-refactor page this class used to be.
 *
 * **Layout**: `<vision-fleet-map>` fills the entire stage between two independently collapsible
 * docked panels (`command-logic.ts#commandGridColumns` computes the grid — see its own doc comment
 * for why these are real grid-track siblings, not `position: absolute` overlays, and how that
 * avoids the map's own zoom/layer controls entirely by construction rather than z-index
 * coordination). Both panels persist their collapsed state per user (`core/panel-state.ts`,
 * docs/UX-REWORK-PLAN.md §U-b item 7's "explicit collapse, reopen via toggle chip" — mirrors
 * `features/live/live.ts`'s `railOpen`/`features/fly/fly.ts`'s `mapVisible` exactly) — now owned by
 * the facade, not this component.
 *
 * **Selecting an asset** (a rail row, or `<vision-fleet-map>`'s own `(preview)` output — a direct
 * marker click, unchanged component behavior) opens the right `<vision-asset-panel>`. Everything
 * that panel shows is data the facade already has from its own existing pollers — **zero new
 * recurring requests** — see `CommandFacade`'s own doc comment for the full accounting.
 *
 * **`<vision-live-dock>` is no longer used here** — the old "docked preview beside the map" role is
 * the asset panel's own Video tab. `<vision-fleet-map>`'s `(preview)` handler is retargeted from
 * "resolve a device and dock `LiveDock`" to "select this asset" (`CommandFacade.selectAsset`), which
 * is the only change to how `<vision-fleet-map>` is composed here — the component itself, its
 * inputs, and its `(watch)`/`(preview)`/`(openEventAsset)` outputs are all unchanged.
 */
@Component({
  selector: 'vision-command',
  imports: [FleetMap, AssetPanel, ZonesPanel, WeatherChip, RouterLink, Notice],
  templateUrl: './command.html',
  styleUrl: './command.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // `FleetMapStore`/`WeatherStore`: own instance per route activation (page-provided, not
  // `providedIn: 'root'` — see their own class doc comments), shared by `CommandFacade` and by the
  // template's own `<vision-fleet-map>`/`<vision-weather-chip>` children, all resolving the same
  // instances through this one component-level injector. `CommandFacade` is provided alongside them
  // so it can `inject()` both.
  providers: [FleetMapStore, WeatherStore, CommandFacade],
})
export class CommandPage {
  protected readonly facade = inject(CommandFacade);

  /**
   * `?asset=<id>` deep link (docs/UX-REWORK-PLAN.md §U-c's "preserve ?asset deep links if map
   * supported any"). Query params bind to inputs by name/alias automatically
   * (`withComponentInputBinding()`, `app.config.ts`) — no route-table change needed. An `input()`
   * can only be declared on the component itself, so `CommandFacade.trackRequestedAsset` is handed
   * this signal-accessor once below rather than owning the input itself.
   */
  readonly requestedAssetId = input<string | undefined>(undefined, { alias: 'asset' });

  /**
   * The Zones panel's own mutually-exclusive overlay group (docs/UI-ARCHITECTURE-PLAN.md) —
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

  constructor() {
    this.facade.trackRequestedAsset(this.requestedAssetId);
  }
}
