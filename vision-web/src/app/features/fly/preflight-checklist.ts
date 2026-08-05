import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { preflightSummary, type PreflightItem } from '../../core/telemetry/flight-state-logic';
import { Icon } from '../../shared/ui/icon';

/**
 * The Fly cockpit's pre-flight checklist card (docs/FC-INTEGRATIONS-PLAN.md F-d) — a compact,
 * always-5-row rundown of `flight-state-logic.ts#derivePreflight`'s own rows (Video feed,
 * Telemetry link, GPS fix, Battery, Armable), each glyphed `✓`/`✕`/`—` for ok/fail/unknown.
 * Deliberately dumb: the inputs are the only state, computed by `CockpitFacade` from
 * `TelemetryStore.latest()`/`primaryDevice()`/`live()` — this component issues no HTTP and holds no
 * store, mirroring `features/command/asset-panel.ts`'s own "deliberately dumb" convention. That
 * extends to the collapse: `collapsed` is an input and the toggle only emits `collapsedChange`, so
 * the host owns when the card is open (see `CockpitFacade#preflightCollapsed`).
 *
 * `cockpit.html` shows this only pre-arm (watch mode off, and either no telemetry yet or the FC
 * reports `armed !== true`) — once the aircraft is confirmed armed, the OSD chip bar is the live
 * instrument, not this ground-check card. Within that window it is the **pre-start** card: expanded
 * while the stream isn't running (per direct user request — the checklist is the operator's job at
 * that moment), collapsed to its one-line summary head the moment the stream starts, re-expandable
 * by clicking that head. `/operate/preflight`'s standalone page passes no `collapsible` at all and
 * keeps the plain always-expanded heading it always had.
 *
 * Arming blockers (the Armable row's `detail`) render verbatim, exactly as the flight controller
 * reported them (`^(PreArm|Arm): …` STATUSTEXT strings) — never paraphrased, so a pilot sees the
 * same words their FC ground station would show.
 */
@Component({
  selector: 'vision-preflight-checklist',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './preflight-checklist.html',
  styleUrl: './preflight-checklist.css',
})
export class PreflightChecklist {
  readonly items = input.required<readonly PreflightItem[]>();

  /** Opt-in: turns the head into a toggle button. Off → the old plain heading, never collapsible. */
  readonly collapsible = input(false);

  /** Host-owned collapse state; ignored entirely unless `collapsible` is on. */
  readonly collapsed = input(false);

  /** "The user asked to collapse/expand" — the host's own state is what actually decides. */
  readonly collapsedChange = output<boolean>();

  protected readonly isCollapsed = computed(() => this.collapsible() && this.collapsed());

  /** The collapsed head's one-line rollup — worst-state-wins (`flight-state-logic.ts`). */
  protected readonly summary = computed(() => preflightSummary(this.items()));

  protected glyph(state: PreflightItem['state']): string {
    switch (state) {
      case 'ok':
        return '✓';
      case 'fail':
        return '✕';
      default:
        return '—';
    }
  }
}
