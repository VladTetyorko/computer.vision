import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { WeatherFacade } from '../../core/weather/weather-facade';
import { GeoFacade } from '../../core/geo/geo-facade';
import { SeatFacade } from '../../core/seat/seat-facade';
import { UiStore } from '../../core/ui/ui-store';
import { Player } from '../../shared/player/player';
import { FollowHud } from '../../shared/player/follow-hud/follow-hud';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { SidePanel } from '../../shared/ui/side-panel';
import { EmptyState } from '../../shared/ui/empty-state';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { FlyOsd } from './fly-osd';
import { FailsafeBanner } from './failsafe-banner';
import { GroundedBanner } from './grounded-banner';
import { GroundingStore } from './grounding-store';
import { DiagnosticsCard } from './diagnostics-card';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { CvControlPanel } from './cv-control-panel';
import { CvSetupModal } from './cv-setup-modal';
import { FlyHud } from './fly-hud';
import { MapTools, type MapToolsCapabilities } from '../../shared/map/map-controls/map-tools/map-tools';
import { CockpitFacade } from './cockpit-facade';
import { migratedPanelId, nextCollapseAction, showDetectionOffChip, type ToolRailPanelId } from './fly-logic';

/** `UiStore`'s own storage key for this page's tool-rail (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-D) —
 * one key for all seven drawers (`flight`/`rc`/`cv`/`detections`/`marks`/`map`/`help`; the former
 * detection-`layers` drawer was folded into `cv` per direct user request — see `fly-logic.ts`'s own
 * `ToolRailPanelId` doc comment; the new `map` drawer, docs/plans/done/MAP-REWORK-PLAN.md §5.2, is the map's
 * layers + drawing tools and is unrelated to that old one). Unchanged key — `UiStore` round-trips
 * the same `localStorage` shape, so an already-open drawer survives across a reload. */
const ACTIVE_PANEL_KEY = 'vision.fly.activePanel';

/** This page's one mutually-exclusive **dialog** group (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — the
 * Stop-stream confirm and, as of docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1, the CV setup modal.
 * Typed as a union (not a bare string), mirroring `flight-command-panel.ts#CommandDialog`/
 * `command.ts#CommandOverlay`'s identical precedent. Both members share the one `UiStore` group
 * (transient, no `storageKey`, same as `arm-confirm-dialog.ts`'s own group) so opening one always
 * closes the other — never observed in practice today (nothing opens the setup modal while the Stop
 * confirm is up, or vice versa), but "at most one dialog" is the invariant this group exists to
 * hold regardless. */
type CockpitDialog = 'stop' | 'cv-setup';

/**
 * `/fly/:assetId` — the operator cockpit, addressable on its own now (docs/plans/done/NAV-IA-REDESIGN-PLAN.md
 * §2.5 F12, docs/plans/done/MVP3-PLAN.md §C-b's "one job, one page" persona: *flies ONE drone at a time;
 * everything else is noise*). Split out of the old combined `FlyPage`, which switched between this
 * and `DronePickerPage` internally with no URL change — see `drone-picker.ts`'s own doc comment for
 * the picker's half, and `cockpit-facade.ts`'s for exactly what moved/changed in the split.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md (wave W1)**: every store/service injection, derived
 * read-model, and HTTP-backed command lives in {@link CockpitFacade} (provided below, alongside
 * `TelemetryStore`/`DetectionsStore`/`WeatherFacade` — one poller-set per route activation). This
 * component is left holding only:
 *   - the route-bound `assetId`/`watch`/`autostart` inputs (only a component can receive one) and
 *     the constructor wiring that forwards them into the facade — `assetId` is now the route's own
 *     `:assetId` param (matched by name via `withComponentInputBinding()`, mirrors `LivePage`'s
 *     identical `deviceId`/`LiveFacade#setDeviceId` shape) and stays reactive across a same-route
 *     drone switch (the header switcher/a picker-card pick both navigate to a new `/fly/:assetId`,
 *     which Angular resolves as a param change on the *same* routed component instance, not a
 *     remount — this component's own constructor `effect()` is what notices and re-selects);
 *   - the overlay state a `UiStore` group is explicitly meant to be **host-owned** (per that class's
 *     own doc comment "a host owns one instance directly", mirrored by `asset-detail.ts`'s
 *     `editors`/`panels` and `command.ts`'s `overlay`): `panels` (the six tool-rail drawers) and
 *     `dialog` (the Stop-stream confirm and, as of docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1,
 *     the CV setup modal);
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
    FollowHud,
    TacticalMap,
    DetectionsStrip,
    Icon,
    IconButton,
    SidePanel,
    EmptyState,
    KebabMenu,
    FlyOsd,
    FailsafeBanner,
    GroundedBanner,
    DiagnosticsCard,
    ReturnHomeButton,
    CvControlPanel,
    CvSetupModal,
    FlyHud,
    MapTools,
  ],
  templateUrl: './cockpit.html',
  styleUrl: './cockpit.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `LivePage`/`AssetDetailPage`.
  // `WeatherFacade` (docs/plans/done/OPS-CORE-PLAN.md §W) is page-provided too — see that class's own doc
  // comment for why it can't be a shared root singleton. `SeatFacade` (docs/plans/active/CREW-CONTROL-
  // PLAN.md §3.6, wave W4) is page-provided for the identical reason, mirroring `features/crew/crew.ts`'s
  // own providers array. `CockpitFacade` shares this same injector so its own `inject(TelemetryFacade)`/
  // `inject(DetectionsFacade)`/`inject(WeatherFacade)`/`inject(SeatFacade)` resolve to these exact
  // instances (see `CockpitFacade`'s own doc comment).
  providers: [TelemetryFacade, DetectionsFacade, WeatherFacade, GeoFacade, SeatFacade, CockpitFacade, GroundingStore],
})
export class CockpitPage {
  /** Bound from the route by `withComponentInputBinding()` (`cockpit.routes.ts` names the segment
   * `:assetId` to match this exactly, mirroring `LivePage#deviceId`). */
  readonly assetId = input.required<string>();

  /** `?watch=1` — hides Start/Stop (docs/plans/done/MVP3-PLAN.md §C-b, C-c's own drill-down target). */
  readonly watch = input<string | undefined>(undefined);

  /** `?autostart=1` — the onboarding wizard's Ready screen's `Open cockpit ›` terminal action
   * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.3). Forwarded into {@link CockpitFacade} below,
   * same as {@link watch} — see that facade's own `autostartSignal`/one-shot-effect doc comment for
   * what actually consuming it does. */
  readonly autostart = input<string | undefined>(undefined);

  protected readonly facade = inject(CockpitFacade);

  private readonly stageHost = viewChild<ElementRef<HTMLDivElement>>('stage');

  /**
   * The map inset's own component instance — `undefined` whenever it isn't rendered (`mapVisible()`
   * off, or no telemetry device). A type-based `viewChild` query, not a template `#ref`: the inset
   * and the `map` drawer sit inside two different `@if` blocks in `cockpit.html`, and a template
   * reference variable's scope doesn't cross that boundary the way a view query does. Used to feed
   * the `map` drawer's `<vision-layer-manager>` its "Show on map"/"Basemap" sections
   * (docs/conclusions/MAP-UX-RESEARCH.md M1) — see `cockpit.html`'s own comment on that drawer.
   */
  protected readonly tacticalMap = viewChild(TacticalMap);

  /**
   * The two `<vision-map-tools>` doors' own fixed capability sets (`docs/plans/active/COMMAND-MAP-
   * FLOW-PLAN.md` §3.2's table) — disjoint by design, so `marks` and `map` can never re-create the
   * old "two controls, one meaning" defect (§3.2's "why `/fly` keeps two buttons" note). `marks`
   * carries {@link cockpit} (recomputed as the selected asset or its position changes across a
   * same-route drone switch); `map` needs neither, so it stays a plain object like Command's own.
   */
  protected readonly marksCapabilities = computed<MapToolsCapabilities>(() => ({
    marks: true,
    layers: 'off',
    draw: false,
    zones: false,
    cockpit: { assetId: this.assetId(), dronePosition: this.facade.dronePosition() },
  }));

  protected readonly mapCapabilities: MapToolsCapabilities = {
    marks: false,
    layers: 'view',
    draw: true,
    zones: true,
  };

  /**
   * `cockpit.html`'s own video-surface "Detection is off — video only" chip
   * (docs/plans/done/CV-DEMAND-PLAN.md wave D3) — a thin template-friendly wrapper over the facade's raw
   * signals, same posture as {@link isPanelOpen} below; the actual decision is the pure, unit-tested
   * `fly-logic.ts#showDetectionOffChip`.
   */
  protected readonly detectionOffChipVisible = computed(() =>
    // `facade.detectionOn()`, not the draft — the chip must describe the stream on screen
    // (docs/plans/done/STREAM-STATE-PLAN.md §3.1). It also stands down while the video itself has
    // something to say ({@link videoNotice}): "Detection off — video only" is a misleading thing to
    // read over a stalled feed, since there is no video either.
    showDetectionOffChip(this.facade.live(), this.facade.detectionOn()) && !this.facade.videoNotice(),
  );

  // --- Overlay state — host-owned, see this class's own doc comment above ------------------------

  /**
   * The right-edge icon tool-rail's one-open-at-a-time drawer manager (docs/plans/done/UI-REDESIGN-PLAN.md
   * Wave 2, D-D/F3). Frozen rail ids (`ToolRailPanelId`): `rc`, `cv`, `marks`, `map`, `help` — `cv`
   * is the merged Vision drawer as of wave W5 (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3) and `rc` the
   * merged Controller drawer as of docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C10, the former
   * separate `detections` and `flight` ids having been folded into them — see
   * `fly-logic.ts#ToolRailPanelId`'s own doc comment.
   */
  protected readonly panels = new UiStore(ACTIVE_PANEL_KEY);

  /** The Stop-stream confirm's own `UiStore` group — see this file's own `CockpitDialog` doc comment. */
  private readonly dialog = new UiStore();
  protected isDialogOpen(id: CockpitDialog): boolean {
    return this.dialog.isOpen(id);
  }

  constructor() {
    // A drawer this rail no longer has, restored from a previous session, would silently open
    // nothing — so the retired `flight` id lands on the drawer that absorbed it (C10).
    const restored = migratedPanelId(this.panels.active());
    if (restored !== this.panels.active() && restored !== null) {
      this.panels.open(restored);
    }

    // Route-driven asset selection (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12) — reruns whenever `assetId()`
    // itself changes, including the very first activation; `CockpitFacade#selectAsset` no-ops if the
    // id is unchanged (mirrors `LivePage`'s identical `effect(() => this.facade.setDeviceId(...))`).
    effect(() => this.facade.selectAsset(this.assetId()));

    // `watch` must stay reactive across a same-route navigation — mirrors `LivePage`'s identical
    // `effect(() => this.facade.setDeviceId(...))`.
    effect(() => this.facade.setWatch(this.watch()));

    // `autostart` — forwarded the same way `watch` is; the facade's own effect does the actual
    // one-shot consumption (see `CockpitFacade`'s doc comment on `autostartSignal`).
    effect(() => this.facade.setAutostart(this.autostart()));

    // Tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — a captured map click always produces a
    // `MarksFacade.draft()` regardless of whether the `marks` drawer happens to be open at that
    // moment (the map inset and the drawer are independent siblings — see `MarksFacade`'s own class
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

  // --- CV setup modal (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1) ------------------------------
  // `CvControlPanel#setupRequested` (the "Change…"/"Detection setup…" buttons) calls
  // `requestCvSetup()`; the modal's own `(closed)` output (scrim click, the header "×", or `Esc` via
  // `collapseOverlays()` below) calls `closeCvSetup()`. Opening this dialog does **not** touch
  // `panels` — the `cv` drawer stays open underneath it, per the plan's "opening it must not close
  // the tool-rail drawer".

  protected requestCvSetup(): void {
    this.dialog.open('cv-setup');
  }

  protected closeCvSetup(): void {
    this.dialog.close('cv-setup');
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

  /** Closest-thing-open-first (docs/plans/done/UI-REDESIGN-PLAN.md D-D): the CV setup modal, then any open
   * tool-rail drawer, then the Stop-stream confirm, then the map inset; see
   * `fly-logic.ts#nextCollapseAction`'s own doc comment for the cascade order this delegates to. */
  protected collapseOverlays(): void {
    const action = nextCollapseAction({
      cvSetupOpen: this.dialog.isOpen('cv-setup'),
      panelOpen: this.panels.active() !== null,
      stopConfirmOpen: this.dialog.isOpen('stop'),
      mapVisible: this.facade.mapVisible(),
    });
    switch (action) {
      case 'cv-setup':
        this.closeCvSetup();
        break;
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
