import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { UiStore } from '../../core/ui/ui-store';
import { Player } from '../../shared/player/player';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { SidePanel } from '../../shared/ui/side-panel';
import { EmptyState } from '../../shared/ui/empty-state';
import { FlyOsd } from './fly-osd';
import { FailsafeBanner } from './failsafe-banner';
import { PreflightChecklist } from './preflight-checklist';
import { DiagnosticsCard } from './diagnostics-card';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { FlightCommandPanel } from './flight-command-panel';
import { CvControlPanel } from './cv-control-panel';
import { RcMonitor } from './rc-monitor';
import { DrawingToolbar } from '../../shared/map/map-controls/drawing-toolbar';
import { LayerManager } from '../../shared/map/map-controls/layer-manager';
import { MarksPanel } from './marks-panel';
import { CockpitFacade } from './cockpit-facade';
import { nextCollapseAction, type ToolRailPanelId } from './fly-logic';

/** `UiStore`'s own storage key for this page's tool-rail (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-D) —
 * one key for all seven drawers (`flight`/`rc`/`cv`/`detections`/`marks`/`map`/`help`; the former
 * detection-`layers` drawer was folded into `cv` per direct user request — see `fly-logic.ts`'s own
 * `ToolRailPanelId` doc comment; the new `map` drawer, docs/plans/done/MAP-REWORK-PLAN.md §5.2, is the map's
 * layers + drawing tools and is unrelated to that old one). Unchanged key — `UiStore` round-trips
 * the same `localStorage` shape, so an already-open drawer survives across a reload. */
const ACTIVE_PANEL_KEY = 'vision.fly.activePanel';

/** This page's one mutually-exclusive **confirm-dialog** group (docs/plans/done/UI-ARCHITECTURE-PLAN.md) —
 * today just the Stop-stream confirm. Typed as a union (not a bare string), mirroring
 * `flight-command-panel.ts#CommandDialog`/`command.ts#CommandOverlay`'s identical precedent, even
 * with one member today. */
type CockpitDialog = 'stop';

/**
 * `/fly/:assetId` — the operator cockpit, addressable on its own now (docs/plans/done/NAV-IA-REDESIGN-PLAN.md
 * §2.5 F12, docs/plans/done/MVP3-PLAN.md §C-b's "one job, one page" persona: *flies ONE drone at a time;
 * everything else is noise*). Split out of the old combined `FlyPage`, which switched between this
 * and `DronePickerPage` internally with no URL change — see `drone-picker.ts`'s own doc comment for
 * the picker's half, and `cockpit-facade.ts`'s for exactly what moved/changed in the split.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md (wave W1)**: every store/service injection, derived
 * read-model, and HTTP-backed command lives in {@link CockpitFacade} (provided below, alongside
 * `TelemetryStore`/`DetectionsStore`/`WeatherStore` — one poller-set per route activation). This
 * component is left holding only:
 *   - the route-bound `assetId`/`watch` inputs (only a component can receive one) and the
 *     constructor wiring that forwards them into the facade — `assetId` is now the route's own
 *     `:assetId` param (matched by name via `withComponentInputBinding()`, mirrors `LivePage`'s
 *     identical `deviceId`/`LiveFacade#setDeviceId` shape) and stays reactive across a same-route
 *     drone switch (the header switcher/a picker-card pick both navigate to a new `/fly/:assetId`,
 *     which Angular resolves as a param change on the *same* routed component instance, not a
 *     remount — this component's own constructor `effect()` is what notices and re-selects);
 *   - the overlay state a `UiStore` group is explicitly meant to be **host-owned** (per that class's
 *     own doc comment "a host owns one instance directly", mirrored by `asset-detail.ts`'s
 *     `editors`/`panels` and `command.ts`'s `overlay`): `panels` (the six tool-rail drawers) and
 *     `dialog` (the Stop-stream confirm);
 *   - DOM-only concerns no facade could hold anyway: the fullscreen `viewChild`/`toggleFullscreen`,
 *     and the page-scoped `document` `keydown` listener (`handleKeydown`) that maps physical keys to
 *     facade commands / `UiStore` calls.
 *
 * Every HTTP call, toast, silent-degrade path, poll cadence, and keyboard shortcut is unchanged from
 * the pre-split page — see {@link CockpitFacade}'s own doc comment for the full "what moved/changed" account.
 *
 * **`.surface-dark` enclave root** (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/W4): `cockpit.html`'s `.cockpit`
 * div (every branch — loaded, empty, loading) carries `.surface-dark` — this whole route is the
 * video surface, with no separate light chrome around it (`fullBleed: true`, no page-bar), so the
 * enclave is the page's own root. See `cockpit.html`'s own comment at that class for the reasoning.
 */
@Component({
  selector: 'vision-cockpit',
  imports: [
    RouterLink,
    Player,
    TacticalMap,
    DetectionsStrip,
    Icon,
    IconButton,
    SidePanel,
    EmptyState,
    FlyOsd,
    FailsafeBanner,
    PreflightChecklist,
    DiagnosticsCard,
    ReturnHomeButton,
    FlightCommandPanel,
    CvControlPanel,
    RcMonitor,
    MarksPanel,
    DrawingToolbar,
    LayerManager,
  ],
  templateUrl: './cockpit.html',
  styleUrl: './cockpit.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `LivePage`/`AssetDetailPage`.
  // `WeatherStore` (docs/plans/done/OPS-CORE-PLAN.md §W) is page-provided too — see that class's own doc
  // comment for why it can't be a shared root singleton. `CockpitFacade` shares this same injector
  // so its own `inject(TelemetryStore)`/`inject(DetectionsStore)`/`inject(WeatherStore)` resolve to
  // these exact instances (see `CockpitFacade`'s own doc comment).
  providers: [TelemetryStore, DetectionsStore, WeatherStore, CockpitFacade],
})
export class CockpitPage {
  /** Bound from the route by `withComponentInputBinding()` (`cockpit.routes.ts` names the segment
   * `:assetId` to match this exactly, mirroring `LivePage#deviceId`). */
  readonly assetId = input.required<string>();

  /** `?watch=1` — hides Start/Stop (docs/plans/done/MVP3-PLAN.md §C-b, C-c's own drill-down target). */
  readonly watch = input<string | undefined>(undefined);

  protected readonly facade = inject(CockpitFacade);

  private readonly stageHost = viewChild<ElementRef<HTMLDivElement>>('stage');

  // --- Overlay state — host-owned, see this class's own doc comment above ------------------------

  /**
   * The right-edge icon tool-rail's one-open-at-a-time drawer manager (docs/plans/done/UI-REDESIGN-PLAN.md
   * Wave 2, D-D/F3). Frozen rail ids (`ToolRailPanelId`): `flight`, `rc`, `cv`, `detections`,
   * `marks`, `help`.
   */
  protected readonly panels = new UiStore(ACTIVE_PANEL_KEY);

  /** The Stop-stream confirm's own `UiStore` group — see this file's own `CockpitDialog` doc comment. */
  private readonly dialog = new UiStore();
  protected isDialogOpen(id: CockpitDialog): boolean {
    return this.dialog.isOpen(id);
  }

  constructor() {
    // Route-driven asset selection (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12) — reruns whenever `assetId()`
    // itself changes, including the very first activation; `CockpitFacade#selectAsset` no-ops if the
    // id is unchanged (mirrors `LivePage`'s identical `effect(() => this.facade.setDeviceId(...))`).
    effect(() => this.facade.selectAsset(this.assetId()));

    // `watch` must stay reactive across a same-route navigation — mirrors `LivePage`'s identical
    // `effect(() => this.facade.setDeviceId(...))`.
    effect(() => this.facade.setWatch(this.watch()));

    // Tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — a captured map click always produces a
    // `MarksStore.draft()` regardless of whether the `marks` drawer happens to be open at that
    // moment (the map inset and the drawer are independent siblings — see `MarksStore`'s own class
    // doc comment). Auto-reopening the drawer here is what keeps a draft from silently landing
    // out of sight if the operator armed a kind, closed the drawer, then clicked the map.
    effect(() => {
      if (this.facade.marks.draft()) {
        this.panels.open('marks');
      }
    });

    const onKeydown = (event: KeyboardEvent): void => this.handleKeydown(event);
    document.addEventListener('keydown', onKeydown);

    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('keydown', onKeydown);
    });
  }

  // --- Stop, with a confirm step (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §2 poka-yoke rule 2) -------------
  // The facade owns the actual command (`CockpitFacade#stop`); this page only owns the confirm gate.

  protected requestStop(): void {
    this.dialog.open('stop');
  }

  protected cancelStop(): void {
    this.dialog.close('stop');
  }

  protected async confirmStop(): Promise<void> {
    await this.facade.stop();
    this.dialog.close('stop');
  }

  // --- Keyboard shortcuts (docs/plans/done/MVP3-PLAN.md §C-b) ------------------------------------------
  // Mirrors `LivePage`'s own `M`-only listener (page-scoped `document` `keydown`, ignored while a
  // form field has focus or a modifier is held, added/removed with the route), extended to the
  // cockpit's fuller shortcut set. Kept on this component (not the facade) purely because
  // fullscreen needs `stageHost`, a `viewChild` only a component can declare. No `showPicker()`
  // guard needed any more (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12) — this page *is* the cockpit now, the
  // picker is a different route entirely and was never reachable from here.

  private handleKeydown(event: KeyboardEvent): void {
    if (event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
      return;
    }
    switch (event.key) {
      case 'm':
      case 'M':
        if (this.facade.hasTelemetryDevice()) {
          this.facade.toggleMapVisible();
        }
        break;
      case 'b':
      case 'B':
        this.facade.cycleBoxes();
        break;
      case 'f':
      case 'F':
        void this.toggleFullscreen();
        break;
      case 'Escape':
        this.collapseOverlays();
        break;
      case '?':
        this.panels.toggle('help');
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  /** Closest-thing-open-first (docs/plans/done/UI-REDESIGN-PLAN.md D-D): any open tool-rail drawer, then the
   * Stop-stream confirm, then the map inset; see `fly-logic.ts#nextCollapseAction`'s own doc comment
   * for the cascade order this delegates to. */
  protected collapseOverlays(): void {
    const action = nextCollapseAction({
      panelOpen: this.panels.active() !== null,
      stopConfirmOpen: this.dialog.isOpen('stop'),
      mapVisible: this.facade.mapVisible(),
    });
    switch (action) {
      case 'panel':
        this.panels.close();
        break;
      case 'stop-confirm':
        this.dialog.close('stop');
        break;
      case 'map':
        this.facade.hideMap();
        break;
    }
  }

  // --- Tool-rail (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-D) --------------------------------------
  // Thin wrappers around `this.panels` typed to the frozen `ToolRailPanelId` set (`fly-logic.ts`) so
  // `cockpit.html`'s rail buttons/drawers can't typo an id past the compiler — `UiStore` itself stays
  // a generic `string` id (see that class's own doc comment).

  protected togglePanel(id: ToolRailPanelId): void {
    this.panels.toggle(id);
  }

  protected isPanelOpen(id: ToolRailPanelId): boolean {
    return this.panels.isOpen(id);
  }

  protected async toggleFullscreen(): Promise<void> {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
        return;
      }
      const host = this.stageHost()?.nativeElement;
      if (host) {
        await host.requestFullscreen();
      }
    } catch {
      // Fullscreen can be refused (permissions-policy, an embedding iframe, an unsupported
      // browser) — never let that break the cockpit; the shortcut simply has no visible effect.
    }
  }
}
