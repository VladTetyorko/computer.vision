import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { FlightPlanDialog } from '../../shared/map/fleet-plan-dialog/flight-plan-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { OnboardingFacade } from './onboarding-facade';
import type { FitOutRole } from '../../core/onboarding/fit-out-logic';

/**
 * The `source` step (docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4 — supersedes the W3 four-tile
 * fork) — the wizard's new first step, and now one primary path plus a demoted fallback: the **Found
 * nearby** feed (`OnboardingFacade#foundNearbyCandidates`) is every live discovery candidate worth a
 * decision, simulated ones excluded (`excludeSimulated` — `features/playground` is the only place a
 * simulated asset is created now); clicking a card jumps straight to the `confirm` step
 * (`OnboardingStore#chooseFoundCandidate`). Nothing found, or the device wasn't heard/seen? An
 * `<details>` one level down — "Other ways to add a device" — holds the old fork tiles minus
 * **It comes to us** (the feed already *is* that path, so offering it again would be the same action
 * twice): **We go to it** (type in an address), **Find it for me** (the five existing scanners),
 * **Nothing to connect** (the equipment short-circuit), plus **Provision Wi-Fi over USB** (moved in
 * from its own top-level tile — same `/provision-wifi` navigation, gated on
 * `WebSerialGateway.isSupported()` but never hidden when unsupported,
 * `OnboardingStore.provisionWifiSupported`'s own doc comment).
 *
 * Non-routed child of `OnboardingPage` — injects `OnboardingFacade` directly, licensed by
 * `core/ui/architecture.spec.ts`'s own carve-out for presentational children sharing a
 * host-provided facade (the same pattern `flight-command-panel`/`telemetry-osd`/`pilots-card` use).
 *
 * Once a fork tile is chosen (`OnboardingStore.sourceMode()` — still reachable from a demoted tile, or
 * from the `?candidateId=` query-param entrance which sets it to `'passive'` directly), each fit-out
 * row (Sense/Sight) is rendered by exactly one of three partials, decided per row, not per mode — a row
 * already resolved (filled, or pre-proven) always shows its own summary card regardless of the current
 * mode (`OnboardingFacade#rowResolved`); an unresolved row under `passive` shows the live waiting room
 * (no picker, no Scan button — it is truly passive); every other unresolved row falls through to the
 * pre-W2 Connect step's own tile-grid/register/discover/listen/drone markup, narrowed only by
 * `OnboardingFacade#rowFindMethods` on the `scan` mode (excludes `register`, `manual`'s own tile). The
 * "Use a test source" tile that used to sit in every row's own grid is gone (L4) — its legacy
 * plumbing (`setRowValue(role, 'simulate')`, `showLegacySimulateConfig`) is left dormant rather than
 * ripped out, see `OnboardingStore#chooseSourceMode`'s own doc comment.
 */
@Component({
  selector: 'vision-source-step',
  imports: [FormsModule, RouterLink, FlightPlanDialog, Notice, EmptyState],
  templateUrl: './source-step.html',
  styleUrl: './source-step.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceStep {
  protected readonly facade = inject(OnboardingFacade);

  protected trackByRole = (_index: number, role: FitOutRole): FitOutRole => role;
}
