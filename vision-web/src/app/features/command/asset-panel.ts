import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Player } from '../../shared/player/player';
import { attentionAgeLabel, attentionReasons, batteryAttentionSeverity } from './command-logic';
import type { AssetAttention, ActiveStream } from '../../core/api/models';
import type { FleetMarker } from '../../core/map/map-logic';

export type AssetPanelTab = 'status' | 'telemetry' | 'video';

/**
 * The right-docked asset detail panel (docs/UX-REWORK-PLAN.md §U-c bullet 1) — the one genuinely
 * new component this rework adds. Everything it *shows* is reused: `<vision-player>` (the shared
 * video surface, unmodified, same as every other live-video consumer in this app) for the Video
 * tab, and the `dl.facts`/`.fact` telemetry-facts grid idiom already established independently by
 * `features/asset-detail/**`/`shared/player/stream-info-panel.ts`/`features/replay/**` (a fourth,
 * consistent copy, not a new pattern) for Status/Telemetry.
 *
 * **Deliberately dumb** — every input is data `CommandPage` already has from its own existing
 * pollers (see that page's own class doc comment for the full "zero new recurring requests" case):
 * `asset` from the one `GET /api/fleet/summary` poll, `marker` from the embedded
 * `<vision-fleet-map>`'s own `FleetMapStore` (battery/altitude/heading/position/sample-age, so this
 * panel never spins up a second `TelemetryStore` poller for an asset the map is already tracking —
 * same reuse `shared/map/live-dock.ts` already established for the docked-preview panel this
 * replaces), and `videoDeviceId`/`stream` from `CommandPage`'s one-shot
 * `FleetMapStore.resolveWatchDevice` call on selection plus the always-on root `FleetStore`. This
 * component issues no HTTP itself and holds no store — only the active-tab signal.
 *
 * Tabs, not stacked sections (the plan allowed either) — a `.segmented` control (docs/UX-REWORK-PLAN.md
 * §U-b item 5's own "mutually-exclusive mode buttons" primitive, reused verbatim) keeps the ~380px
 * panel from needing to scroll past three stacked cards' worth of content for a quick glance.
 */
@Component({
  selector: 'vision-asset-panel',
  imports: [Player],
  templateUrl: './asset-panel.html',
  styleUrl: './asset-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetPanel {
  readonly asset = input.required<AssetAttention>();
  readonly marker = input<FleetMarker | undefined>(undefined);
  readonly videoDeviceId = input<string | undefined>(undefined);
  readonly stream = input<ActiveStream | undefined>(undefined);

  /** `CommandPage`'s own `/fly?asset=…&watch=1` navigation — this panel never touches the router. */
  readonly watchLive = output<void>();
  /** `CommandPage`'s own `/assets/:assetId` navigation. */
  readonly openDetails = output<void>();
  readonly closePanel = output<void>();

  protected readonly activeTab = signal<AssetPanelTab>('status');

  protected readonly reasons = computed(() => attentionReasons(this.asset()));
  protected readonly ageLabel = computed(() => attentionAgeLabel(this.asset()));
  protected readonly batterySeverity = computed(() => batteryAttentionSeverity(this.marker()?.batteryPercent));

  protected selectTab(tab: AssetPanelTab): void {
    this.activeTab.set(tab);
  }
}
