import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { FlightPlanDialog } from '../../shared/map/fleet-plan-dialog/flight-plan-dialog';
import { Notice } from '../../shared/ui/notice';
import { OnboardingFacade } from './onboarding-facade';
import type { FitOutRole } from '../../core/onboarding/fit-out-logic';

/**
 * The `source` step (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1/§0.4, wave W3) — the wizard's
 * new first step, and the honest fork: **It comes to us** (passive listening off the standing
 * MAVLink lobby / mediamtx push path), **We go to it** (type in an address — both roles, fixing D3's
 * Sight-only defect), **Find it for me** (the five existing scanners, unchanged plumbing beneath
 * this fork), **Nothing to connect** (the equipment short-circuit). A fifth tile,
 * **Provision Wi-Fi over USB**, sits alongside the fork itself (U5/R3) — a navigation tile to
 * `/provision-wifi`, gated on `WebSerialGateway.isSupported()` but never hidden when unsupported
 * (`OnboardingStore.provisionWifiSupported`'s own doc comment).
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly, licensed by
 * `core/ui/architecture.spec.ts`'s own carve-out for presentational children sharing a
 * host-provided facade (the same pattern `flight-command-panel`/`telemetry-osd`/`pilots-card` use).
 *
 * Once a fork tile is chosen (`OnboardingStore.sourceMode()`), each fit-out row (Sense/Sight) is
 * rendered by exactly one of three partials, decided per row, not per mode — a row already resolved
 * (filled, or pre-proven from a discovery-candidate entrance) always shows its own summary card
 * regardless of the current mode (`OnboardingFacade#rowResolved`); an unresolved row under `passive`
 * shows the live waiting room (no picker, no Scan button — it is truly passive); every other
 * unresolved row falls through to the pre-W2 Connect step's own tile-grid/register/discover/listen/
 * drone/simulate markup, unchanged, narrowed only by `OnboardingFacade#rowFindMethods` on the `scan`
 * mode (excludes `register`, `manual`'s own tile). The legacy whole-vehicle Simulate sub-form
 * (`showLegacySimulateConfig`) renders once, below both role cards, exactly as it did in the pre-W2
 * Connect step — its rich mode picker isn't expressible per row.
 */
@Component({
  selector: 'vision-source-step',
  imports: [FormsModule, RouterLink, FlightPlanDialog, Notice],
  templateUrl: './source-step.html',
  styleUrl: './source-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceStep {
  protected readonly facade = inject(OnboardingFacade);

  protected trackByRole = (_index: number, role: FitOutRole): FitOutRole => role;
}
