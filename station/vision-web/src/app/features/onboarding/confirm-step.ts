import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The `confirm` step (docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4) — reached only by clicking
 * a card in the Source step's own "Found nearby" feed (`OnboardingStore#chooseFoundCandidate`); a
 * hidden interstitial like `sysid`/`handover`, never part of `visibleSteps`/the step rail. Shows what
 * was actually detected (`OnboardingFacade#candidateDetailEntries` — name, discovery method, address,
 * every raw `details` entry the backend sent, never a fabricated field), the one optional credential
 * field (only when `OnboardingFacade#candidateNeedsCredential` reads a probe-refused hint off the
 * candidate — see that function's own doc comment for why this is a heuristic, not a real backend
 * signal yet), and a way out for "this isn't new" (`OnboardingStore#attachExistingFromConfirm` — the
 * existing attach-onto-an-asset path, reused unchanged).
 *
 * Continuing (`OnboardingStore#continueFromConfirm`) hands off to the ordinary `source`→`identify`
 * machinery — the row is already pre-proven by `chooseFoundCandidate`'s own prefill, so `nextStep`
 * resolves straight past `prove` with zero new step-machine logic. The category default (MAV_TYPE/
 * ONVIF model → `suggestedCategory`) and the "Open cockpit / Leave in stock" hand-over are therefore
 * not this component's concern at all — they're the existing `identify`/`handover` steps, unchanged.
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `AttachStep`/`SysidStep`'s own doc comments). Owns its own Back/Continue footer, excluded from the
 * shell's shared footer (`onboarding.html`'s own footer comment).
 */
@Component({
  selector: 'vision-confirm-step',
  imports: [FormsModule],
  templateUrl: './confirm-step.html',
  styleUrl: './confirm-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmStep {
  protected readonly facade = inject(OnboardingFacade);
}
