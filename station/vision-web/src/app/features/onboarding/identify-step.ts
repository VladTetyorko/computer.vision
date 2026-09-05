import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The `identify` step (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1, wave W3;
 * docs/plans/done/WAREHOUSE-UX-PLAN.md D1) — name/category/photo/serial/make/model. No longer the
 * wizard's first step (that's `source` now, W2) — the Back button that used to be suppressed here
 * lives in the page shell's shared footer (`onboarding.html`), which now only hides it on `source`.
 *
 * A `connected: false` category (an equipment category — mirrors `source`'s own "Nothing to
 * connect" tile, D3) doesn't change anything rendered *in this step*; it only swaps the shell
 * footer's own action to "Receive" (`OnboardingFacade#identifyActionLabel`).
 *
 * `onPhotoSelected` is the one bit of DOM-specific glue kept here (resetting the raw
 * `<input type="file">`'s own value so the same file can be re-selected later, e.g. right after
 * "Remove") — a truly self-contained view concern, moved from the old page shell now that the photo
 * field itself lives in this component.
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly (same carve-out as
 * `SourceStep`'s own doc comment).
 */
@Component({
  selector: 'vision-identify-step',
  imports: [FormsModule],
  templateUrl: './identify-step.html',
  styleUrl: './identify-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IdentifyStep {
  protected readonly facade = inject(OnboardingFacade);

  protected onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.facade.onPhotoSelected(input.files?.[0]);
    input.value = ''; // lets the same file be re-selected later (e.g. right after "Remove")
  }
}
