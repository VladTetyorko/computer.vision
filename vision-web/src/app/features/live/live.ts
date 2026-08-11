import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player } from '../../shared/player/player';
import { StreamInfoPanel } from '../../shared/player/stream-info-panel';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
import { DetectionsStore } from '../../core/detections/detections-store';
import { TelemetryOsd } from './telemetry-osd';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { DetectionsStrip } from '../../shared/player/detections-strip';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
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
  imports: [Player, RouterLink, TelemetryOsd, TacticalMap, DetectionsStrip, StreamInfoPanel, Notice, EmptyState, PageBar],
  templateUrl: './live.html',
  styleUrl: './live.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, DetectionsStore, LiveFacade],
})
export class LivePage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly deviceId = input.required<string>();

  protected readonly facade = inject(LiveFacade);

  constructor() {
    effect(() => this.facade.setDeviceId(this.deviceId()));
  }
}
