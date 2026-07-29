import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import type { DiagnosticRow } from '../../core/telemetry/flight-state-logic';

/**
 * The Fly cockpit's Diagnostics HUD card (docs/FC-INTEGRATIONS-PLAN.md F-e) — a compact,
 * collapsible rundown of `flight-state-logic.ts#deriveDiagnostics`'s rows (wind, vibration, EKF,
 * rangefinder, mission progress), ArduPilot-only extras that ride `TelemetrySample.extra`.
 * Deliberately dumb, same convention as `PreflightChecklist`/`FailsafeBanner`: `rows` is the only
 * input, computed once by `fly.ts` from `TelemetryStore.latest()?.extra` — this component issues no
 * HTTP and holds no store, only its own collapse/expand toggle.
 *
 * **Hidden entirely with no rows** (both here, via the internal `@if`, and at `fly.html`'s own call
 * site, the same belt-and-suspenders double-guard `FailsafeBanner` already uses) — a non-ArduPilot
 * firmware, or an ArduPilot that simply hasn't emitted any of the underlying MAVLink messages yet,
 * never gets an empty card cluttering the HUD.
 *
 * Severity colors reuse this app's existing tokens, never `--live` (reserved for a genuine
 * failsafe): plain/default for `'ok'`, `--warn` amber for `'warn'`, `--danger` red for `'bad'`;
 * rows with no `severity` at all (rangefinder, mission — F-e defines no threshold for either) render
 * plain, never a fabricated tier.
 */
@Component({
  selector: 'vision-diagnostics-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (rows().length > 0) {
      <div class="diagnostics">
        <button
          type="button"
          class="diag-head"
          (click)="expanded.set(!expanded())"
          [attr.aria-expanded]="expanded()"
        >
          <h3>Diagnostics</h3>
          <span class="chevron" [class.open]="expanded()" aria-hidden="true">▾</span>
        </button>
        @if (expanded()) {
          <ul>
            @for (row of rows(); track row.key) {
              <li [class]="'row-' + (row.severity ?? 'plain')">
                <span class="label">{{ row.label }}</span>
                <span class="value">{{ row.value }}</span>
              </li>
            }
          </ul>
        }
      </div>
    }
  `,
  styles: `
    .diagnostics {
      pointer-events: auto;
      background: rgb(6 9 14 / 78%);
      backdrop-filter: blur(6px);
      border: 1px solid rgb(255 255 255 / 9%);
      border-radius: var(--radius);
      padding: 0.6rem 0.75rem;
      width: 15rem;
    }

    .diag-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      background: none;
      border: none;
      padding: 0;
      margin: 0 0 0.4rem;
      cursor: pointer;
      color: inherit;
    }

    h3 {
      margin: 0;
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: rgb(231 236 243 / 65%);
    }

    .chevron {
      color: rgb(231 236 243 / 55%);
      transition: transform 0.15s ease;
      transform: rotate(-90deg);
    }

    .chevron.open {
      transform: rotate(0deg);
    }

    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
    }

    li {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
      font-size: 0.78rem;
      color: #e7ecf3;
    }

    .label {
      color: rgb(231 236 243 / 65%);
    }

    .value {
      font-family: var(--mono);
      font-variant-numeric: tabular-nums;
      text-align: right;
    }

    /* Severity colors (docs/FC-INTEGRATIONS-PLAN.md F-e): plain default for 'ok'/no-threshold rows,
       --warn amber for 'warn', --danger red for 'bad' — never --live, which this app reserves for a
       genuine failsafe (see FailsafeBanner's own doc comment). */
    .row-warn .value {
      color: var(--warn);
    }

    .row-bad .value {
      color: var(--danger);
    }
  `,
})
export class DiagnosticsCard {
  readonly rows = input.required<readonly DiagnosticRow[]>();

  protected readonly expanded = signal(true);
}
