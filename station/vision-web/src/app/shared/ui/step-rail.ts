import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from './icon';

/** One rail row, already resolved by the caller — no draft/status-function threading here (unlike
 *  the RC-setup-specific ancestor this was lifted from, `features/controller/step-rail.ts` before
 *  CONTROLLER-UX; see this file's own class doc). `done` and "is this the currently open step"
 *  ({@link StepRail#currentIndex}) are independent on purpose — a step can be both: already bound
 *  *and* the one the operator has open right now, rendered as a green check glyph inside the same
 *  blue "current" selection bar, exactly as the ancestor rendered it. */
export interface StepRailItem {
  readonly id: string;
  readonly label: string;
  readonly done: boolean;
}

/**
 * `vision-step-rail` — a generic multi-step wizard's own step list
 * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §4 wave W1, lifted from
 * `features/controller/step-rail.ts`'s original CONTROLLER-UX X4 implementation): one row per
 * {@link StepRailItem}, ticked once its own `done` is `true`, highlighted on `currentIndex`,
 * clickable to jump — every step is re-enterable by design, nothing here locks a later step until
 * an earlier one is finished (decision U8 of the RC-setup wizard this pattern originated in, kept
 * as this component's own contract since every caller since has wanted the same thing).
 *
 * **Deliberately dumb, more so than its ancestor.** The RC-setup version took `steps`/`draft`/
 * `currentIndex` and computed each row's `done`-ness itself via a feature-specific `stepStatus(step,
 * draft)` closure — coupling this component to one feature's own step/draft shape. Generalizing it
 * for a second caller (the onboarding wizard's own step machine, a completely different
 * `'source'|'prove'|...` union with nothing in common with the RC wizard's `WizardStep`) meant
 * pulling that computation out: every caller now resolves its own `readonly StepRailItem[]` — done
 * already decided — and this component only ever renders what it's handed. `currentIndex` stays a
 * plain position, exactly like the ancestor, rather than folded into each item — see
 * {@link StepRailItem}'s own doc comment for why done/current must stay independent facts. `jump`
 * still emits a plain index (not an id) — every existing/known caller keeps its own step array as
 * the index space it jumps within, so there is nothing here to resolve an id back to.
 */
@Component({
  selector: 'vision-step-rail',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './step-rail.html',
  styleUrl: './step-rail.css',
})
export class StepRail {
  readonly items = input.required<readonly StepRailItem[]>();
  readonly currentIndex = input.required<number>();

  /** The index of the row the operator clicked — the page owns actually jumping to it. */
  readonly jump = output<number>();
}
