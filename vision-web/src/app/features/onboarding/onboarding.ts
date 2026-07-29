import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SettingsStore } from '../../core/settings/settings-store';
import { FlightPlanDialog } from '../../shared/map/fleet-plan-dialog/flight-plan-dialog';
import { OnboardingStore } from './onboarding-store';
import { WIZARD_STEPS, type ConnectMethod, type WizardStep } from './onboarding-logic';
import { isClaimedVehicle, vehicleDetailChips, type VehicleDetailChip } from './drone-scan-logic';
import type { DiscoveredDevice } from '../../core/api/models';

interface StepDescriptor {
  readonly step: WizardStep;
  readonly label: string;
}

const STEP_LABELS: Record<WizardStep, string> = {
  profile: 'Profile',
  connect: 'Connect',
  test: 'Test',
  create: 'Create',
};

const CONNECT_METHOD_LABELS: Record<ConnectMethod, string> = {
  register: 'Register manually',
  discover: 'Discover on network',
  simulate: 'Simulate',
  listen: 'Listen for drones',
};

/**
 * The onboarding wizard's own route (`/add-source`, docs/UX-REWORK-PLAN.md §U-d) — replaces the
 * inline "+ Add source" card the pre-wizard Devices/Warehouse page used to open on itself. Four
 * steps, one visible at a time, back-navigable, all state kept in `OnboardingStore` (this
 * component's own page-provided "component store" — see that class's own doc comment): Profile
 * (name/registration/photo/category) → Connect (the pre-existing 3-choice register/discover/
 * simulate component, moved here verbatim) → Test (probe + decoded frame before save — UX-DESIGN
 * §5.1's "test-before-save", skipped for Simulate) → Create (summary, then the actual
 * `POST /api/assets`/`POST /api/simulations` call).
 *
 * This component itself is deliberately thin — a `@switch` over `store.step()` plus a Back/Next
 * footer — every decision and request shape lives in `onboarding-logic.ts`/`OnboardingStore`.
 */
@Component({
  selector: 'vision-onboarding',
  imports: [FormsModule, RouterLink, FlightPlanDialog],
  templateUrl: './onboarding.html',
  styleUrl: './onboarding.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OnboardingStore],
})
export class OnboardingPage {
  protected readonly store = inject(OnboardingStore);
  protected readonly settings = inject(SettingsStore);

  protected readonly steps: readonly StepDescriptor[] = WIZARD_STEPS.map((step) => ({
    step,
    label: STEP_LABELS[step],
  }));

  protected readonly connectMethodLabels = CONNECT_METHOD_LABELS;

  protected readonly categoryName = computed(() => {
    const slug = this.store.category();
    return this.store.categoryOptions().find((option) => option.slug === slug)?.name ?? slug;
  });

  /** The Create step's one-line summary of the Connect step's outcome — never re-derives a request. */
  protected readonly connectionSummary = computed(() => {
    switch (this.store.connectMethod()) {
      case 'register':
      case 'discover':
      case 'listen':
        return `${this.store.protocol()} — ${this.store.uri()}`;
      case 'simulate':
        return this.simulateSummary();
      case null:
        return '—';
    }
  });

  private simulateSummary(): string {
    switch (this.store.simMode()) {
      case 'direct':
        return `Simulated — plays "${this.store.simVideoPath()}" directly`;
      case 'rtsp':
        return `Simulated — transmits "${this.store.simVideoPath()}" over RTSP`;
      case 'synthetic':
        return 'Simulated — synthetic pattern, no telemetry';
      case 'testDrone':
        return 'Simulated — moving test drone';
    }
  }

  protected stepIndex(step: WizardStep): number {
    return WIZARD_STEPS.indexOf(step);
  }

  protected detailPairs(details: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(details).map(([key, value]) => ({ key, value }));
  }

  // --- "Listen for drones" results list (docs/DRONE-INFRA-PLAN.md I-b) — thin template helpers over
  //     `drone-scan-logic.ts`'s own pure functions, same pattern as `detailPairs` above.

  protected droneVehicleClaimed(candidate: DiscoveredDevice): boolean {
    return isClaimedVehicle(candidate);
  }

  protected droneVehicleChips(candidate: DiscoveredDevice): readonly VehicleDetailChip[] {
    return vehicleDetailChips(candidate);
  }

  protected onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      void this.store.choosePhoto(file);
    }
    input.value = ''; // lets the same file be re-selected later (e.g. right after "Remove")
  }
}
