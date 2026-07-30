import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { PreflightItem } from '../../core/telemetry/flight-state-logic';

/**
 * The Fly cockpit's pre-flight checklist card (docs/FC-INTEGRATIONS-PLAN.md F-d) — a compact,
 * always-5-row rundown of `flight-state-logic.ts#derivePreflight`'s own rows (Video feed,
 * Telemetry link, GPS fix, Battery, Armable), each glyphed `✓`/`✕`/`—` for ok/fail/unknown.
 * Deliberately dumb: `items` is the only input, computed by `fly.ts` from
 * `TelemetryStore.latest()`/`primaryDevice()`/`live()` — this component issues no HTTP and holds no
 * store, mirroring `features/command/asset-panel.ts`'s own "deliberately dumb" convention.
 *
 * `fly.ts` shows this only pre-arm (watch mode off, and either no telemetry yet or the FC reports
 * `armed !== true`) — once the aircraft is confirmed armed, the OSD chip bar is the live instrument,
 * not this ground-check card. Arming blockers (the Armable row's `detail`) render verbatim, exactly
 * as the flight controller reported them (`^(PreArm|Arm): …` STATUSTEXT strings) — never
 * paraphrased, so a pilot sees the same words their FC ground station would show.
 */
@Component({
  selector: 'vision-preflight-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="checklist surface-hud-strong">
      <h3>Pre-flight</h3>
      <ul>
        @for (item of items(); track item.label) {
          <li [class]="'row-' + item.state">
            <span class="glyph">{{ glyph(item.state) }}</span>
            <span class="label">{{ item.label }}</span>
            @if (item.detail) {
              <span class="detail">{{ item.detail }}</span>
            }
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .checklist {
      pointer-events: auto;
      border-radius: var(--radius);
      padding: var(--space-8) var(--space-16);
      width: 15rem;
    }

    h3 {
      margin: 0 0 var(--space-8);
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: color-mix(in srgb, var(--text) 65%, transparent);
    }

    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }

    li {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--space-8);
      font-size: 0.78rem;
      color: var(--text);
    }

    .glyph {
      flex: none;
      width: 1rem;
      font-family: var(--mono);
      font-weight: 700;
      text-align: center;
      color: var(--text-faint);
    }

    .label {
      flex: 1;
      min-width: 6rem;
    }

    .detail {
      flex-basis: 100%;
      padding-left: var(--space-24);
      font-size: 0.72rem;
      color: color-mix(in srgb, var(--text) 62%, transparent);
    }

    .row-ok .glyph {
      color: var(--color-success-text);
    }

    .row-fail .glyph {
      color: var(--color-danger-text);
    }

    .row-fail .detail {
      color: var(--color-danger-text);
    }

    .row-unknown .glyph {
      color: var(--text-faint);
    }
  `,
})
export class PreflightChecklist {
  readonly items = input.required<readonly PreflightItem[]>();

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
