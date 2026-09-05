import { ChangeDetectionStrategy, Component, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { UiStore } from '../../core/ui/ui-store';
import { Player, type BoxesMode } from '../../shared/player/player';
import { StreamInfoPanel } from '../../shared/player/stream-info-panel';
import { DECLUTTER_LEVELS, declutterLevelLabel } from '../../shared/player/detection-overlay-logic';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { TelemetryOsd } from './telemetry-osd';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { FollowHud } from '../../shared/player/follow-hud/follow-hud';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { MapTools, type MapToolsCapabilities } from '../../shared/map/map-controls/map-tools/map-tools';
import { LiveFacade } from './live-facade';

/**
 * The single-device cockpit (`/live/:deviceId`) — video-first with a collapsible rail
 * (docs/main/CYCLES-PLAN.md §2, §9). Dumb by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md): every read-model
 * and command lives in `LiveFacade`, which this component injects exclusively. `TelemetryStore`/
 * `DetectionsStore` stay in this component's own `providers` (one poller-set per route activation,
 * unchanged) so the facade and this page's child components (`<vision-telemetry-osd>`,
 * `<vision-detections-strip>`, `<vision-tactical-map>`, `<vision-stream-info>`) keep DI-sharing the
 * exact same store instances as before this refactor.
 *
 * **Header** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2): `page-head` is now `<vision-page-bar>`, titled
 * with the device's own name (this page has no nav entry/canonical icon of its own — it's a
 * drill-down from Devices/Wall/Command, not a sidebar destination, so `icon` is omitted).
 */
@Component({
  selector: 'vision-live',
  imports: [Player, RouterLink, TelemetryOsd, TacticalMap, DetectionsStrip, FollowHud, StreamInfoPanel, Notice, EmptyState, PageBar, MapTools],
  templateUrl: './live.html',
  styleUrl: './live.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, DetectionsStore, LiveFacade],
})
export class LivePage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly deviceId = input.required<string>();

  protected readonly facade = inject(LiveFacade);

  /** The map inset's own component instance, for the Map tools drawer's Layers section (docs/
   * conclusions/MAP-UX-RESEARCH.md M1) — mirrors `command.ts#tacticalMap`/`cockpit.ts#tacticalMap`'s
   * identical doc comment and type-based `viewChild` query. */
  protected readonly tacticalMap = viewChild(TacticalMap);

  /** This inset's one HUD door (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.2, D9) — read-write now,
   * not read-only: `layers: 'view'` (administration stays `/command`-only), no `cockpit` (this page
   * flies nothing). A one-member `UiStore` group, not a bare `signal<boolean>` — `architecture.spec.ts`
   * requires every overlay flag route through one, mirroring `command.ts#overlay`'s identical fix.
   * Transient, no persistence — mirrors the map inset's own `mapInsetVisible` posture. */
  private readonly overlay = new UiStore();
  protected mapToolsOpen(): boolean {
    return this.overlay.isOpen('map-tools');
  }
  protected openMapTools(): void {
    this.overlay.open('map-tools');
  }
  protected closeMapTools(): void {
    this.overlay.close('map-tools');
  }
  protected readonly mapToolsCapabilities: MapToolsCapabilities = { marks: true, layers: 'view', draw: false, zones: true };

  /** The four declutter levels, in cycle order — the segmented control's own `@for` source, shared
   *  with `cv-control-panel.ts`'s identical field so the two never drift apart. */
  protected readonly declutterLevels = DECLUTTER_LEVELS;
  /** The declutter level's own display name — thin wrapper so the template calls it as a method. */
  protected boxesModeLabel(mode: BoxesMode): string {
    return declutterLevelLabel(mode);
  }

  constructor() {
    effect(() => this.facade.setDeviceId(this.deviceId()));
  }
}
