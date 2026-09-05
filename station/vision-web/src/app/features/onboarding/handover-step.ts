import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The `handover` step (docs/plans/done/OPS-UX-PLAN.md §2 A3; docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * §3.1/D9, wave W3) — "Issue to" a custodian or "Leave in stock" for the **new**-asset path (the
 * `existingAssetPath` fork skips this picker outright, since that asset already has an owner —
 * `OnboardingStore#enterHandoverStep`'s own doc comment), then always ends in the same terminal
 * screen: the two-half Sight/Sense proof (`OnboardingFacade#sightProof`/`senseProof`, replacing the
 * old single `handoverNext()` router-link guess with per-half honesty, D9) plus an
 * "Open cockpit ›" link to `/fly/:assetId?autostart=1` — the cockpit side of that query param is
 * wave W5's own job, not this one's.
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `SourceStep`'s own doc comment). Owns its own inline footer throughout (excluded from the shell's
 * shared Back/Next footer, along with `attach`/`sysid`) — there is nothing to go "back" to once the
 * asset already exists, and no forward step past this one.
 */
@Component({
  selector: 'vision-handover-step',
  imports: [FormsModule, RouterLink, EmptyState, Notice],
  templateUrl: './handover-step.html',
  styleUrl: './handover-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HandoverStep {
  protected readonly facade = inject(OnboardingFacade);
}
