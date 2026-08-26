import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { stepStatus, type WizardStep } from '../../core/rc/controller-wizard-logic';
import type { ProfileDraft } from '../../core/rc/controller-setup-logic';

/**
 * `vision-step-rail` — the setup wizard's own step list
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3, wave X4): one row per {@link WizardStep}, ticked
 * once {@link stepStatus} reads it as done, highlighted on the one currently open, clickable to jump
 * (decision U8 — every step is re-enterable, nothing here locks a later step until an earlier one is
 * finished).
 *
 * Deliberately dumb: `stepStatus` is read straight off `draft` on every render rather than tracked
 * as separate rail state, so the rail can never disagree with what the draft actually has bound —
 * the same reasoning `stepStatus`'s own doc comment gives.
 */
@Component({
  selector: 'vision-step-rail',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './step-rail.html',
  styleUrl: './step-rail.css',
})
export class StepRail {
  readonly steps = input.required<readonly WizardStep[]>();
  readonly draft = input.required<ProfileDraft>();
  readonly currentIndex = input.required<number>();

  /** The index of the step the operator clicked — the page owns actually jumping to it. */
  readonly jump = output<number>();

  protected readonly statusOf = (step: WizardStep) => stepStatus(step, this.draft());
}
