import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Notice } from '../../shared/ui/notice';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The hidden `sysid` interstitial (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0, wave W3 — never in
 * `visibleSteps()`/the rail, see `onboarding-logic.ts#WizardStep`'s own doc comment). Reached only
 * from `attach` (or the equipment short-circuit's Receive — though a collision there is vacuous,
 * `prove` never runs), only when a Prove-step row's probe collided with a sysid an already-registered
 * device claims — every other path skips straight to `handover`. Advisory only — Continue always
 * proceeds.
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `SourceStep`'s own doc comment). Owns its own "Write sysid"/"Continue" actions (excluded from the
 * shell's shared Back/Next footer, along with `attach`/`handover`).
 */
@Component({
  selector: 'vision-sysid-step',
  imports: [FormsModule, Notice],
  templateUrl: './sysid-step.html',
  styleUrl: './sysid-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SysidStep {
  protected readonly facade = inject(OnboardingFacade);
}
