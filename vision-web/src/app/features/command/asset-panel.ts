import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Player } from '../../shared/player/player';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { attentionAgeLabel, attentionReasons, batteryAttentionSeverity } from './command-logic';
import { canCommandReturnHome, deriveDiagnostics, gpsFixLabel, gpsSeverity } from '../../core/telemetry/flight-state-logic';
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
 * `CommandFacade`'s own `FleetMapStore` (battery/altitude/heading/position/sample-age, so this
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
  imports: [Player, ReturnHomeButton],
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

  /** `marker()?.gpsFixType` feeds the same `gps-degraded` reason the rail's own row rank uses — see `command-logic.ts#gpsDegradedReason`'s doc comment. */
  protected readonly reasons = computed(() => attentionReasons(this.asset(), this.marker()?.gpsFixType));
  protected readonly ageLabel = computed(() => attentionAgeLabel(this.asset()));
  protected readonly batterySeverity = computed(() => batteryAttentionSeverity(this.marker()?.batteryPercent));

  // --- Mode / Armed / GPS (docs/FC-INTEGRATIONS-PLAN.md F-d) — prefer the live marker (fresher,
  // sourced from the latest telemetry sample's own `flightState`) and fall back to the
  // fleet-summary-level `AssetAttention` fields (still honest, just possibly a poll cycle behind);
  // GPS has no fleet-summary-level fallback at all (see `AssetAttention`'s own doc comment) — a
  // selected-but-not-currently-plotted asset simply shows '—', never a fabricated fix.
  protected readonly modeLabel = computed(() => this.marker()?.flightMode ?? this.asset().flightMode);
  protected readonly armed = computed(() => this.marker()?.armed ?? this.asset().armed);
  protected readonly gpsFixType = computed(() => this.marker()?.gpsFixType);
  protected readonly gpsLabel = computed(() => gpsFixLabel(this.gpsFixType()));
  protected readonly gpsSeverityTier = computed(() => gpsSeverity(this.gpsFixType()));

  /** docs/FC-INTEGRATIONS-PLAN.md F-e — reuses the same pure derivation `features/fly/diagnostics-card.ts`
   * feeds from `TelemetryStore`; here fed from the marker's own `extra` (see that field's own doc
   * comment on `FleetMarker` for why this panel needs no second telemetry poller). */
  protected readonly diagnostics = computed(() => deriveDiagnostics(this.marker()?.extra));

  /**
   * docs/DRONE-INFRA-PLAN.md I-e Stage 1 — gates the panel-actions row's `<vision-return-home-button>`
   * (`asset-panel.html`). Fed from `marker()` alone (`firmware`/`sampleAgeSeconds`), the same
   * "no second `TelemetryStore` poller" reuse this panel's Mode/Armed/GPS/diagnostics facts already
   * establish above — `AssetAttention` carries no firmware field at all, only the live marker does.
   */
  protected readonly canBringHome = computed(() =>
    canCommandReturnHome(this.marker()?.firmware, this.marker()?.sampleAgeSeconds),
  );

  protected selectTab(tab: AssetPanelTab): void {
    this.activeTab.set(tab);
  }
}
