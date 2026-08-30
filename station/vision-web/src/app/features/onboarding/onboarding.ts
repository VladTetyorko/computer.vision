import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { FlightPlanDialog } from '../../shared/map/fleet-plan-dialog/flight-plan-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { OnboardingStore } from './onboarding-store';
import { OnboardingFacade } from './onboarding-facade';

/**
 * The onboarding wizard's own route (`/add-source`, docs/plans/done/UX-REWORK-PLAN.md §U-d) — replaces the
 * inline "+ Add source" card the pre-wizard Devices/Warehouse page used to open on itself. Five
 * visible steps (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6 — see
 * `onboarding-logic.ts#WizardStep`'s own doc comment for the full per-step contract and the hidden
 * `sysid` interstitial), all state kept in `OnboardingStore` (this component's own page-provided
 * "component store"): **Identify** (name/category/photo/serial/make/model/registration — a
 * `connected: false` category short-circuits straight to Hand-over via its own "Receive" action) →
 * **Connect** (the fit-out table, `core/onboarding/fit-out-logic.ts` — one row per role, Sense/
 * Sight) → **Prove** (Test + Verify, run per filled row) → **Register** (the actual
 * `POST /api/assets` call) → **Hand-over** (docs/plans/done/OPS-UX-PLAN.md §2 A3 — "Issue to" a custodian or
 * "Leave in stock", ending in a completed sub-state naming the next verb rather than an automatic
 * redirect).
 *
 * This component itself is deliberately thin — a `@switch` over `store.step()` plus a Back/Next
 * footer (Hand-over and Sysid carry their own inline footers instead, see `onboarding.html`'s own
 * footer comment) — every decision and request shape lives in `onboarding-logic.ts`/`OnboardingStore`/
 * `core/onboarding/fit-out-logic.ts`, orchestrated by `OnboardingFacade` (docs/plans/done/UI-ARCHITECTURE-PLAN.md),
 * which this component injects exclusively. `onPhotoSelected` is the one bit of DOM-specific glue
 * left here (resetting the raw `<input type="file">`'s own value so the same file can be
 * re-selected later) — a truly self-contained view concern no other component/route could ever
 * need to stay in sync with.
 *
 * **Page bar + centered form (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2/§2.3, docs/extracts/design/07-add-source.md,
 * wave 2).** `page-head`'s three-line description is gone outright, not moved into a `hint` — the
 * step body already carries its own subtitle ("What is it, and what does it look like?") for exactly
 * this job, so the header would only have restated it. `.page--form` (`styles.css`) centers the whole
 * wizard at 880px — a form this narrow reads as unfinished at full fluid width, and it's this page's
 * own F4 finding that a display-name input doesn't need to be 1075px wide. The three standalone
 * Profile-step fields and the Connect step's protocol/URI/path fields carry the new
 * `--field-sm`/`--field-md`/`--field-lg` size buckets (`onboarding.html`) — the rest of the wizard's
 * inputs already sit inside a responsive grid (`.register-form`/`.option-row`) that was never the
 * 1075px offender the plan named. The step-bar redesign (a dot rail), the photo dropzone, and moving
 * the disabled-`Next` reason onto the button itself are all `docs/extracts/design/07-add-source.md`'s
 * "Suggested design" — bigger changes this wave deliberately leaves alone (its own "note anything left
 * for later" instruction).
 */
@Component({
  selector: 'vision-onboarding',
  imports: [FormsModule, RouterLink, FlightPlanDialog, EmptyState, Notice, PageBar],
  templateUrl: './onboarding.html',
  styleUrl: './onboarding.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OnboardingStore, OnboardingFacade],
})
export class OnboardingPage {
  protected readonly facade = inject(OnboardingFacade);

  protected onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.facade.onPhotoSelected(input.files?.[0]);
    input.value = ''; // lets the same file be re-selected later (e.g. right after "Remove")
  }
}
