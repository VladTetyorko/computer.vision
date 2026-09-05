import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Notice } from '../../shared/ui/notice';
import { OnboardingFacade } from './onboarding-facade';
import type { FitOutRole } from '../../core/onboarding/fit-out-logic';

/**
 * The `prove` step (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1, wave W3) — Test + Verify,
 * run once per filled `find` row that isn't already pre-proven (a discovery-candidate entrance, or
 * the waiting room's own live "Use" — both set `OnboardingStore.preProvenRoles`, which this step's
 * own row loop excludes: re-proving a row the operator just watched arrive live would be busywork,
 * not honesty). Unchanged in substance from the pre-W2 wizard's own Prove step
 * (`docs/plans/done/WAREHOUSE-UX-PLAN.md` §3.4 wave W6).
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `SourceStep`'s own doc comment).
 */
@Component({
  selector: 'vision-prove-step',
  imports: [Notice],
  templateUrl: './prove-step.html',
  styleUrl: './prove-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProveStep {
  protected readonly facade = inject(OnboardingFacade);

  protected trackByRole = (_index: number, role: FitOutRole): FitOutRole => role;
}
