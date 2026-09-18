import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { StepRail } from '../../shared/ui/step-rail';
import { AttachStep } from './attach-step';
import { ConfirmStep } from './confirm-step';
import { HandoverStep } from './handover-step';
import { IdentifyStep } from './identify-step';
import { OnboardingFacade } from './onboarding-facade';
import { OnboardingStore } from './onboarding-store';
import { ProveStep } from './prove-step';
import { SourceStep } from './source-step';
import { SysidStep } from './sysid-step';

/**
 * The onboarding wizard's own route (`/add-source`, docs/plans/done/UX-REWORK-PLAN.md §U-d) — replaces
 * the inline "+ Add source" card the pre-wizard Devices/Warehouse page used to open on itself. Seven
 * steps (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1, wave W2+W3, plus `confirm` from
 * docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4 — five visible, `confirm`/`sysid` hidden; see
 * `onboarding-logic.ts#WizardStep`'s own doc comment for the full per-step contract):
 * **Source** (the "Found nearby" feed + demoted fallback tiles, `SourceStep`) → **Confirm** (hidden,
 * reached only from a found-nearby card, `ConfirmStep`) → **Prove** (Test + Verify per unresolved row,
 * `ProveStep`) → **Identify** (name/category/photo, `IdentifyStep`) → **Attach** (new asset, or
 * attach onto an existing one, `AttachStep`) → **Sysid** (hidden collision-fix interstitial,
 * `SysidStep`) → **Hand-over** (`HandoverStep`).
 *
 * **Retired the pre-W2 monolith** (a single 1161-line `onboarding.html` switching over every step
 * inline) **into one 3-file component per step** — this shell is now deliberately thin: the rail
 * (`vision-step-rail`, `OnboardingFacade#railItems`/`currentStepIndex`/`jumpTo`) plus a `@switch`
 * delegating to each step's own component, plus one shared Back/Next footer for the three steps
 * plain enough to share it (`source`/`prove`/`identify`) — `attach`/`sysid`/`handover` each own a
 * fully custom footer inline in their own template instead (their own action verbs don't fit a
 * generic "Next": Attach's "Create asset"/"Attach", Sysid's "Write sysid"/"Continue", Hand-over's
 * "Issue to"/"Leave in stock"/"Open cockpit ›" — exactly which steps the old monolith's own footer
 * comment already excluded, `register`/`sysid`/`handover` there).
 *
 * Every step component injects `OnboardingFacade` directly (non-routed presentational children,
 * licensed by `core/ui/architecture.spec.ts`'s own carve-out) — this page is the sole place that
 * `provides` `OnboardingStore`/`OnboardingFacade` (docs/plans/done/UI-ARCHITECTURE-PLAN.md), same
 * DI-sharing idiom as `AssetDetailPage`'s `TelemetryStore`/`DetectionsStore`.
 *
 * **Page bar + centered form (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2/§2.3,
 * docs/extracts/design/07-add-source.md, wave 2).** `.page--form` (`styles.css`) centers the whole
 * wizard at 880px — a form this narrow reads as unfinished at full fluid width.
 */
@Component({
  selector: 'vision-onboarding',
  imports: [RouterLink, PageBar, StepRail, SourceStep, ProveStep, IdentifyStep, AttachStep, ConfirmStep, SysidStep, HandoverStep],
  templateUrl: './onboarding.html',
  styleUrl: './onboarding.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OnboardingStore, OnboardingFacade],
})
export class OnboardingPage {
  protected readonly facade = inject(OnboardingFacade);
}
