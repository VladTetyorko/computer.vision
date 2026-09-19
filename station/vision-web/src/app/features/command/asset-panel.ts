import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Player } from '../../shared/player/player';
import { ReturnHomeButton } from '../../shared/ui/return-home-button';
import { attentionAgeLabel, batteryAttentionSeverity, quietVerdict, type AttentionReason } from './command-logic';
import { canCommandReturnHome, deriveDiagnostics, gpsFixLabel, gpsSeverity } from '../../core/telemetry/flight-state-logic';
import type { AssetAttention, ActiveStream } from '../../core/api/models';
import type { FleetMarker, LastContact } from '../../core/map/map-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import type { AssetRoute, RouteSpan } from '../../core/map-data/route-logic';
import { lastContactLabel, markerLastContact } from '../../shared/map/tactical-map/tactical-map-logic';

export type AssetPanelTab = 'status' | 'telemetry' | 'video';

/**
 * The right-docked asset detail panel (docs/plans/done/UX-REWORK-PLAN.md §U-c bullet 1) — the one genuinely
 * new component this rework adds. Everything it *shows* is reused: `<vision-player>` (the shared
 * video surface, unmodified, same as every other live-video consumer in this app) for the Video
 * tab, and the `dl.facts`/`.fact` telemetry-facts grid idiom already established independently by
 * `features/asset-detail/**`/`shared/player/stream-info-panel.ts`/`features/replay/**` (a fourth,
 * consistent copy, not a new pattern) for Status/Telemetry.
 *
 * **Deliberately dumb** — every input is data `CommandPage` already has from its own existing
 * pollers (see that page's own class doc comment for the full "zero new recurring requests" case):
 * `asset` from the one `GET /api/fleet/summary` poll, `marker` from the embedded
 * `CommandFacade`'s own `MapFacade` (battery/altitude/heading/position/sample-age, so this
 * panel never spins up a second `TelemetryStore` poller for an asset the map is already tracking —
 * same reuse `shared/map/live-dock.ts` already established for the docked-preview panel this
 * replaces), and `videoDeviceId`/`stream` from `CommandPage`'s one-shot
 * `MapFacade.resolveWatchDevice` call on selection plus the always-on root `FleetStore`. This
 * component issues no HTTP itself and holds no store — only the active-tab signal.
 *
 * Tabs, not stacked sections (the plan allowed either) — a `.segmented` control (docs/plans/done/UX-REWORK-PLAN.md
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
  /**
   * docs/plans/active/OPERATOR-UX-4-PLAN.md finding N2, §2 N2 — fed by `CommandFacade#selectedAttentionReasons`,
   * the SAME `attentionByAssetId` map the rail's own row severity/rank reads (`CommandFacade`'s own
   * doc comment on that computed). This panel used to derive its own `reasons` from `attentionReasons(
   * this.asset(), this.marker()?.gpsFixType)` — passing only `gpsFixType`, never `geofenceBreaches`/
   * `pipelineErrorDetail`, so a CRIT asset (breach/pipeline-error) could show "All quiet" here while the
   * rail behind it correctly showed CRIT (N2's exact repro). One shared read-model — computed once,
   * consumed twice — makes that class of drift impossible by construction rather than patching this
   * one gap; see `command-facade.ts`'s own doc comment for the full accounting.
   */
  readonly reasons = input.required<readonly AttentionReason[]>();

  /**
   * The Telemetry tab's Route control (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.4, wave W3) —
   * every one of these is a plain input/output fed from `CommandFacade`'s own `RouteFacade`
   * orchestration, same "deliberately dumb" discipline as the rest of this panel's inputs (this
   * class doc comment's own "zero new recurring requests" case still holds: `RouteFacade` fetches
   * on `show()`/`hide()`, never a poll). `routeSpanChanged` is the segmented control's own click.
   */
  readonly routeSpan = input.required<RouteSpan>();
  readonly routes = input.required<readonly AssetRoute[]>();
  readonly routesLoading = input.required<boolean>();
  readonly routesError = input.required<boolean>();
  readonly routesNoUsages = input.required<boolean>();
  readonly routeSpanChanged = output<RouteSpan>();

  /** `4d 2h ago`, never a raw second count — the one age vocabulary (`humanAge`). */
  protected sampleAgeText(seconds: number | undefined): string {
    return seconds === undefined ? '—' : `${humanAge(seconds)} ago`;
  }

  /** `CommandPage`'s own `/fly?asset=…&watch=1` navigation — this panel never touches the router. */
  readonly watchLive = output<void>();
  /** `CommandPage`'s own `/assets/:assetId` navigation. */
  readonly openDetails = output<void>();
  readonly closePanel = output<void>();

  protected readonly activeTab = signal<AssetPanelTab>('status');

  protected readonly ageLabel = computed(() => attentionAgeLabel(this.asset()));
  protected readonly batterySeverity = computed(() => batteryAttentionSeverity(this.marker()?.batteryPercent));

  /**
   * "All quiet" earns its words (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.6 D5) — the Status
   * tab's own honest verdict, computed straight off two inputs this panel already has (`reasons`,
   * `marker`), never a third store injected for it. `contact` reuses
   * `tactical-map-logic.ts#markerLastContact`'s identical fallback the map's own popup already
   * applies (this panel's `marker` predates the `lastContact` field existing at all, so a caller
   * that somehow still doesn't populate it degrades to the marker's own live `sampleAgeSeconds`
   * before finally landing on `'unknown'` — never silently mismatching what the map itself shows for
   * the same asset).
   */
  protected readonly contact = computed<LastContact>(() => {
    const marker = this.marker();
    return marker ? markerLastContact(marker) : { source: 'unknown' };
  });
  protected readonly verdict = computed(() => quietVerdict(this.reasons(), this.contact()));
  /** The `'no-basis'` line's own `{last-contact label}` — the exact fact backing (or not backing) the verdict. */
  protected readonly noBasisContactLabel = computed(() => lastContactLabel(this.contact()));

  // --- Mode / Armed / GPS (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) — prefer the live marker (fresher,
  // sourced from the latest telemetry sample's own `flightState`) and fall back to the
  // fleet-summary-level `AssetAttention` fields (still honest, just possibly a poll cycle behind);
  // GPS has no fleet-summary-level fallback at all (see `AssetAttention`'s own doc comment) — a
  // selected-but-not-currently-plotted asset simply shows '—', never a fabricated fix.
  protected readonly modeLabel = computed(() => this.marker()?.flightMode ?? this.asset().flightMode);
  protected readonly armed = computed(() => this.marker()?.armed ?? this.asset().armed);
  protected readonly gpsFixType = computed(() => this.marker()?.gpsFixType);
  protected readonly gpsLabel = computed(() => gpsFixLabel(this.gpsFixType()));
  protected readonly gpsSeverityTier = computed(() => gpsSeverity(this.gpsFixType()));

  /** docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e — reuses the same pure derivation `features/fly/diagnostics-card.ts`
   * feeds from `TelemetryStore`; here fed from the marker's own `extra` (see that field's own doc
   * comment on `FleetMarker` for why this panel needs no second telemetry poller). */
  protected readonly diagnostics = computed(() => deriveDiagnostics(this.marker()?.extra));

  /**
   * docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 — gates the panel-actions row's `<vision-return-home-button>`
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

  /** One route row's own label — `Flight started {humanAge} ago`, the same age vocabulary every other timestamp in this panel already uses. */
  protected routeRowLabel(route: AssetRoute): string {
    return `Flight started ${humanAge((Date.now() - Date.parse(route.startedAt)) / 1000)} ago`;
  }
}
