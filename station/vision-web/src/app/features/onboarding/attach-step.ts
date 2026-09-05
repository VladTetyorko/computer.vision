import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The `attach` step (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §0.2/§3.1, wave W3 — was
 * `register`) — the facts summary + create call, now forked in two: **New vehicle** (unchanged
 * from the pre-W3 Register step — one `POST /api/assets` call, every filled row's device embedded)
 * or **Add to an existing asset** (this visit's connection attaches onto an already-registered
 * asset instead — the atomic candidate-attach path, `DiscoveryInboxStore#attachCandidate`, when this
 * visit started from a discovery candidate, or a plain register-then-assign otherwise;
 * `OnboardingStore#attachToExistingAsset`'s own doc comment).
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `SourceStep`'s own doc comment). Owns its own Create/Attach action button (excluded from the
 * shell's shared Back/Next footer, along with `sysid`/`handover` — see `onboarding.html`'s own
 * footer comment).
 */
@Component({
  selector: 'vision-attach-step',
  imports: [FormsModule],
  templateUrl: './attach-step.html',
  styleUrl: './attach-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttachStep {
  protected readonly facade = inject(OnboardingFacade);
}
