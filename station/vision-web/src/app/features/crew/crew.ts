import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { SeatFacade } from '../../core/seat/seat-facade';
import { UiStore } from '../../core/ui/ui-store';
import { Player } from '../../shared/player/player';
import { FollowHud } from '../../shared/player/follow-hud/follow-hud';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { Icon } from '../../shared/ui/icon';
import { IconButton } from '../../shared/ui/icon-button';
import { SidePanel } from '../../shared/ui/side-panel';
import { EmptyState } from '../../shared/ui/empty-state';
import { TelemetryOsd } from '../live/telemetry-osd';
import { CvControlPanel } from '../fly/cv-control-panel';
import { CvSetupModal } from '../fly/cv-setup-modal';
import { MapTools, type MapToolsCapabilities } from '../../shared/map/map-controls/map-tools/map-tools';
import { CrewFacade } from './crew-facade';

/** `UiStore`'s own storage key for this page's tool-rail — mirrors `cockpit.ts#ACTIVE_PANEL_KEY`'s
 * identical convention, its own localStorage key. */
const ACTIVE_PANEL_KEY = 'vision.crew.activePanel';

/** This page's tool-rail drawer ids — deliberately just two (§3.4's frozen composition table has no
 * flight/rc/marks-alone/help door here): `vision` (the CV control body) and `map` (the combined
 * marks+layers+draw+zones capability set, §3.4's single `<vision-map-tools>` mount — unlike the
 * cockpit's disjoint two-door split, this page has exactly one map-tools door). */
type CrewPanelId = 'vision' | 'map';

/**
 * `/crew/:assetId` — the crew seat (docs/plans/active/CREW-CONTROL-PLAN.md §3.4, wave W3): a second,
 * camera-only station on an asset a pilot may or may not be flying at the same time from
 * `/fly/:assetId`. Structurally `LiveFacade`/`CockpitFacade`'s own layered shape — every store
 * injection, derived read-model, and HTTP-backed command lives in {@link CrewFacade}, injected
 * exclusively (`core/ui/architecture.spec.ts`'s per-routed-page rule); this component holds only the
 * route-bound `assetId` input, the constructor wiring that forwards it into the facade, and the
 * two host-owned `UiStore` overlay groups (`panels`, `dialog`) that same guard requires live outside
 * a bare `signal()`.
 *
 * **Zero flight verbs** (§3.4): no `<vision-fly-hud>`, no arm/disarm/mode/RTH/e-stop/aux, no RC
 * engage, no session engage/disengage — every one of those stays `/fly`-only. The one action this
 * page ever offers is C0's Start video (`facade.start()`); there is no Stop anywhere on this route.
 *
 * **`.surface-dark` enclave root** — mirrors `cockpit.ts`'s identical doc comment: `crew.routes.ts`'s
 * `fullBleed: true` means no page-bar wraps this route, so the enclave boundary is this component's
 * own root (`crew.html`'s `.crew` div, every branch).
 */
@Component({
  selector: 'vision-crew',
  imports: [
    RouterLink,
    Player,
    FollowHud,
    DetectionsStrip,
    Icon,
    IconButton,
    SidePanel,
    EmptyState,
    TelemetryOsd,
    CvControlPanel,
    CvSetupModal,
    MapTools,
  ],
  templateUrl: './crew.html',
  styleUrl: './crew.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation, identical convention to `CockpitPage`/`LivePage`.
  providers: [TelemetryFacade, DetectionsFacade, SeatFacade, CrewFacade],
})
export class CrewSeatPage {
  /** Bound from the route by `withComponentInputBinding()` (`crew.routes.ts` names the segment
   * `:assetId` to match this exactly). */
  readonly assetId = input.required<string>();

  protected readonly facade = inject(CrewFacade);

  /** `<vision-map-tools>`'s one capability set (§3.4: "marks / layers / draw / zones — capabilities
   * `{marks:true, layers:'manage', draw:true, zones:true, cockpit:{assetId, dronePosition}}`" —
   * "Mark target" geolocation is a crew job). Component-owned, not facade-owned — mirrors
   * `cockpit.ts#marksCapabilities`'s identical placement, reading the route's own `assetId()` input
   * directly rather than the facade's internal copy of it. */
  protected readonly mapToolsCapabilities = computed<MapToolsCapabilities>(() => ({
    marks: true,
    layers: 'manage',
    draw: true,
    zones: true,
    cockpit: { assetId: this.assetId(), dronePosition: this.facade.dronePosition() },
  }));

  // --- Overlay state — host-owned, see this class's own doc comment above ------------------------

  /** The right-edge tool-rail's one-open-at-a-time drawer manager — persisted, mirrors
   * `cockpit.ts#panels`. */
  protected readonly panels = new UiStore(ACTIVE_PANEL_KEY);

  /** The CV setup modal's own group — mirrors `cockpit.ts#dialog`. Transient, no persistence: a
   * reopened modal starting closed is the same posture every dialog in this app already has. */
  private readonly dialog = new UiStore();

  constructor() {
    // Route-driven asset selection — reruns whenever `assetId()` changes, including the very first
    // activation; `CrewFacade#selectAsset` no-ops if the id is unchanged.
    effect(() => this.facade.selectAsset(this.assetId()));
  }

  protected togglePanel(id: CrewPanelId): void {
    this.panels.toggle(id);
  }

  protected isPanelOpen(id: CrewPanelId): boolean {
    return this.panels.isOpen(id);
  }

  // --- CV setup modal — mirrors `cockpit.ts`'s identical request/close pair. Opening it does not
  // touch `panels`: the `vision` drawer stays open underneath it. ---------------------------------

  protected requestCvSetup(): void {
    this.dialog.open('cv-setup');
  }

  protected closeCvSetup(): void {
    this.dialog.close('cv-setup');
  }

  protected isCvSetupOpen(): boolean {
    return this.dialog.isOpen('cv-setup');
  }
}
