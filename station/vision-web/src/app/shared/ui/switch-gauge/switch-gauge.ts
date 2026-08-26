import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { SwitchPosition } from '../../../core/api/models';

/**
 * `vision-switch-gauge` — the small detent-cell primitive behind a bound-switch row in
 * `vision-transmitter-view` (docs/plans/active/CONTROLLER-UX-PLAN.md §2.1): one cell per position,
 * the live one lit.
 *
 * Deliberately dumb: it draws `positions` exactly as given and knows nothing about
 * `ControlInputKind`, a catalogue, or what a lit cell *means* — the caller
 * (`core/rc/transmitter-view-logic.ts#actionSwitchRows`) already resolved the cell count from the
 * catalogue and the live position from the raw reading. A `BUTTON`'s single cell (`positions`
 * length 1) draws round rather than as a pill — a button has one detent, not a row of them.
 */
@Component({
  selector: 'vision-switch-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './switch-gauge.html',
  styleUrl: './switch-gauge.css',
})
export class SwitchGauge {
  /** The cells to draw, in order — length 1 (button), 2 (2-position switch) or 3 (3-position). */
  readonly positions = input.required<readonly SwitchPosition[]>();
  /** Which cell is lit right now; `undefined` lights none (e.g. before a first frame arrives). */
  readonly active = input<SwitchPosition | undefined>(undefined);
  /** Whether the lit cell is mid-hold (decision C9) — fills linearly over the hold window. */
  readonly holding = input<boolean>(false);

  protected readonly round = computed(() => this.positions().length === 1);
}
