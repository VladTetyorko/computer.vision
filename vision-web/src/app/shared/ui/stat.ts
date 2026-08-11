import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * `vision-stat` — the shared KPI / stat tile (docs/plans/done/STYLE-TOKENS-PLAN.md §Shared primitives),
 * generalising asset-detail's hand-built `.kpi-tile` row into one primitive.
 *
 * Follows the `dataviz` skill's stat-tile contract (see its `marks-and-anatomy.md`): a muted,
 * uppercase caption *over* the value, and the value in **semibold proportional figures** — NOT the
 * telemetry register. `tabular-nums`/`--mono` is deliberately avoided here: it stabilises small,
 * fast-ticking inline numerals (the Fly HUD / player readouts, which stay in their own specialised
 * components), but on a large standalone headline number proportional figures read better. So a stat
 * tile and an OSD readout are intentionally different registers, not the same component.
 *
 * `label` + `value` are required; `sub` is an optional caption below the value; `live` shows the
 * global pulsing `.dot.live` beside the value for an in-progress metric; `tone` colours the value
 * only (default = full-contrast text) when the number itself carries a status meaning. Trailing
 * `<ng-content>` inside the value line lets a caller append a unit or a second figure.
 */
@Component({
  selector: 'vision-stat',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stat-tile" [class]="'tone-' + tone()">
      <div class="stat-label">{{ label() }}</div>
      <div class="stat-value">
        {{ value() }}
        @if (live()) {
          <span class="dot live" title="Currently in progress"></span>
        }
        <ng-content />
      </div>
      @if (sub(); as sub) {
        <div class="stat-sub">{{ sub }}</div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .stat-tile {
      background: var(--panel-raised);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      padding: var(--space-8) var(--space-16);
      height: 100%;
    }

    .stat-label {
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
    }

    /* Proportional, semibold — dataviz stat-tile contract; no tabular-nums on a large headline. */
    .stat-value {
      display: flex;
      align-items: baseline;
      gap: var(--space-8);
      margin-top: var(--space-4);
      font-size: 1.4rem;
      font-weight: 600;
      color: var(--text);
    }

    .stat-sub {
      margin-top: var(--space-2);
      font-size: 0.72rem;
      color: var(--text-muted);
    }

    .tone-info .stat-value { color: var(--color-info); }
    .tone-warn .stat-value { color: var(--color-warn); }
    .tone-danger .stat-value { color: var(--color-danger); }
    .tone-ok .stat-value { color: var(--color-success); }
    .tone-muted .stat-value { color: var(--text-muted); }
  `,
})
export class Stat {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  /** Optional caption below the value (e.g. "last 30 days"). */
  readonly sub = input<string>();
  /** Shows the pulsing live dot beside the value for an in-progress metric. */
  readonly live = input<boolean>(false);
  readonly tone = input<'default' | 'info' | 'warn' | 'danger' | 'ok' | 'muted'>('default');
}
