import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { WeatherStore } from '../../core/weather/weather-store';
import { UiStore } from '../../core/ui/ui-store';
import { Player } from '../../shared/player/player';
import { LiveMap } from '../../shared/map/live-map/live-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { SidePanel } from '../../shared/ui/side-panel';
import { FlyOsd } from './fly-osd';
import { FailsafeBanner } from './failsafe-banner';
import { PreflightChecklist } from './preflight-checklist';
import { DiagnosticsCard } from './diagnostics-card';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { FlightCommandPanel } from './flight-command-panel';
import { CvControlPanel } from './cv-control-panel';
import { RcMonitor } from './rc-monitor';
import { MarksPanel } from './marks-panel';
import { FlyFacade } from './fly-facade';
import { lastSeenLabel, nextCollapseAction, positionLabel, streamStateLabel, type ToolRailPanelId } from './fly-logic';
import type { AssetSummary } from '../../core/api/models';

/** `UiStore`'s own storage key for this page's tool-rail (docs/UI-REDESIGN-PLAN.md Wave 2, D-D) —
 * one key for all six drawers (`flight`/`rc`/`cv`/`detections`/`layers`/`help`). Unchanged from the
 * pre-`UiStore` `PanelState` key — `UiStore` round-trips the same `localStorage` shape
 * (docs/UI-ARCHITECTURE-PLAN.md: "API-compatible with `PanelState`"), so an already-open drawer
 * survives this refactor across a reload. */
const ACTIVE_PANEL_KEY = 'vision.fly.activePanel';

/** This page's one mutually-exclusive **confirm-dialog** group (docs/UI-ARCHITECTURE-PLAN.md) —
 * today just the Stop-stream confirm, migrated off its own `stopConfirmOpen` boolean `signal(false)`
 * so it can never drift out of sync with a tool-rail drawer or a future second confirm. Typed as a
 * union (not a bare string), mirroring `flight-command-panel.ts#CommandDialog`/`command.ts#CommandOverlay`'s
 * identical precedent, even with one member today. */
type FlyDialog = 'stop';

/**
 * `/fly` — the operator cockpit and the app's default landing page (docs/MVP3-PLAN.md §C-b, the
 * "one job, one page" persona: *flies ONE drone at a time; everything else is noise*).
 *
 * **Layered per docs/UI-ARCHITECTURE-PLAN.md (wave W1)**: every store/service injection, derived
 * read-model, and HTTP-backed command lives in {@link FlyFacade} (provided below, alongside
 * `TelemetryStore`/`DetectionsStore`/`WeatherStore` — unchanged, still one poller-set per route
 * activation). This component is left holding only:
 *   - the route-bound `requestedAssetId`/`watch` inputs (only a component can receive one) and the
 *     constructor wiring that forwards them into the facade (see `FlyFacade`'s own doc comment for
 *     why one is a one-shot handoff and the other stays continuously reactive);
 *   - the overlay state a `UiStore` group is explicitly meant to be **host-owned** (per that class's
 *     own doc comment "a host owns one instance directly", mirrored by `asset-detail.ts`'s
 *     `editors`/`panels` and `command.ts`'s `overlay`): `panels` (the six tool-rail drawers,
 *     unchanged shape/ids, now backed by `UiStore` instead of `PanelState` — see this file's own
 *     `ACTIVE_PANEL_KEY` doc comment) and `dialog` (the Stop-stream confirm, a one-member transient
 *     `UiStore` group replacing the old `stopConfirmOpen` signal);
 *   - DOM-only concerns no facade could hold anyway: the fullscreen `viewChild`/`toggleFullscreen`,
 *     and the page-scoped `document` `keydown` listener (`handleKeydown`) that maps physical keys to
 *     facade commands / `UiStore` calls — kept here rather than in the facade (unlike `LiveFacade`'s
 *     own self-contained keydown listener) purely because fullscreen needs a `viewChild`, which only
 *     a component can declare;
 *   - a handful of pure, stateless template helpers for the picker's `@for` rows (`assetStreamState`/
 *     `assetLastSeen`/`assetPosition`), the same "plain method reading its `@for` argument, called
 *     from the template" idiom `asset-detail.ts`'s own `barLabel`/`detailPairs` already established.
 *
 * Every HTTP call, toast, silent-degrade path, poll cadence, and keyboard shortcut is unchanged from
 * the pre-facade page — see {@link FlyFacade}'s own doc comment for the full "what moved" account.
 */
@Component({
  selector: 'vision-fly',
  imports: [
    RouterLink,
    Player,
    LiveMap,
    DetectionsStrip,
    Icon,
    IconButton,
    SidePanel,
    FlyOsd,
    FailsafeBanner,
    PreflightChecklist,
    DiagnosticsCard,
    ReturnHomeButton,
    FlightCommandPanel,
    CvControlPanel,
    RcMonitor,
    MarksPanel,
  ],
  templateUrl: './fly.html',
  styleUrl: './fly.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `LivePage`/`AssetDetailPage`.
  // `WeatherStore` (docs/OPS-CORE-PLAN.md §W) is page-provided too — see that class's own doc
  // comment for why it can't be a shared root singleton. `FlyFacade` shares this same injector so
  // its own `inject(TelemetryStore)`/`inject(DetectionsStore)`/`inject(WeatherStore)` resolve to
  // these exact instances (see `FlyFacade`'s own doc comment).
  providers: [TelemetryStore, DetectionsStore, WeatherStore, FlyFacade],
})
export class FlyPage {
  /**
   * `?asset=` — a future drill-down target (e.g. Command's own "watch this one" links, C-c) that
   * overrides the remembered choice for this visit and becomes the new remembered choice too, same
   * as picking one from the switcher. Query params bind to inputs by name exactly like path params
   * do (`withComponentInputBinding()`), so no route-table change is needed for this to work.
   */
  readonly requestedAssetId = input<string | undefined>(undefined, { alias: 'asset' });

  /** `?watch=1` — hides Start/Stop (docs/MVP3-PLAN.md §C-b, C-c's own drill-down target). */
  readonly watch = input<string | undefined>(undefined);

  protected readonly facade = inject(FlyFacade);

  private readonly stageHost = viewChild<ElementRef<HTMLDivElement>>('stage');

  // --- Overlay state — host-owned, see this class's own doc comment above ------------------------

  /**
   * The right-edge icon tool-rail's one-open-at-a-time drawer manager (docs/UI-REDESIGN-PLAN.md
   * Wave 2, D-D/F3; migrated from `PanelState` to `UiStore` by docs/UI-ARCHITECTURE-PLAN.md wave
   * W1 — API-compatible, same persisted `ACTIVE_PANEL_KEY` shape). Frozen rail ids
   * (`ToolRailPanelId`): `flight`, `rc`, `cv`, `detections`, `layers`, `help`.
   */
  protected readonly panels = new UiStore(ACTIVE_PANEL_KEY);

  /** The Stop-stream confirm's own `UiStore` group — see this file's own `FlyDialog` doc comment. */
  private readonly dialog = new UiStore();
  protected isDialogOpen(id: FlyDialog): boolean {
    return this.dialog.isOpen(id);
  }

  constructor() {
    // One-shot `?asset=`/remembered-asset resolution — see `FlyFacade#initPicker`'s own doc comment.
    void this.facade.initPicker(this.requestedAssetId());

    // `watch` must stay reactive across a same-route navigation — mirrors `LivePage`'s identical
    // `effect(() => this.facade.setDeviceId(...))`.
    effect(() => this.facade.setWatch(this.watch()));

    // Tactical marks (docs/TACTICAL-MARKS-PLAN.md M5) — a captured map click always produces a
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

  // --- Picker card facts (docs/UX-REWORK-PLAN.md §U-a2 §3 — the asset card rebuild) -----------
  // Pure, stateless, called from the picker's own `@for` — no facade state needed beyond the loop
  // argument itself, same idiom as `asset-detail.ts`'s own `barLabel`/`detailPairs`.

  protected assetStreamState(asset: AssetSummary): 'Streaming' | 'Offline' {
    return streamStateLabel(asset.status);
  }

  protected assetLastSeen(asset: AssetSummary): string | undefined {
    return lastSeenLabel(asset.lastUsedAt, Date.now());
  }

  protected assetPosition(asset: AssetSummary): string | undefined {
    return positionLabel(asset.lastKnownPosition);
  }

  // --- Stop, with a confirm step (docs/UX-REWORK-PLAN.md §U-a2 §2 poka-yoke rule 2) -------------
  // The facade owns the actual command (`FlyFacade#stop`); this page only owns the confirm gate.

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

  // --- Keyboard shortcuts (docs/MVP3-PLAN.md §C-b) ------------------------------------------
  // Mirrors `LivePage`'s own `M`-only listener (page-scoped `document` `keydown`, ignored while a
  // form field has focus or a modifier is held, added/removed with the route), extended to the
  // cockpit's fuller shortcut set. Kept on this component (not the facade) purely because
  // fullscreen needs `stageHost`, a `viewChild` only a component can declare.

  private handleKeydown(event: KeyboardEvent): void {
    if (this.facade.showPicker()) {
      return; // shortcuts are cockpit-only — the picker has no map/boxes/fullscreen to toggle
    }
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

  /** Closest-thing-open-first (docs/UI-REDESIGN-PLAN.md D-D): any open tool-rail drawer, then the
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

  // --- Tool-rail (docs/UI-REDESIGN-PLAN.md Wave 2, D-D) --------------------------------------
  // Thin wrappers around `this.panels` typed to the frozen `ToolRailPanelId` set (`fly-logic.ts`) so
  // `fly.html`'s rail buttons/drawers can't typo an id past the compiler — `UiStore` itself stays a
  // generic `string` id (see that class's own doc comment).

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
