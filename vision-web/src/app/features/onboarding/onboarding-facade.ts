import { Injectable, computed, inject } from '@angular/core';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { OnboardingStore } from './onboarding-store';
import { WIZARD_STEPS, type ConnectMethod, type WizardStep } from './onboarding-logic';
import { isClaimedVehicle, vehicleDetailChips, type VehicleDetailChip } from './drone-scan-logic';
import {
  FIRMWARES,
  FIRMWARE_LABELS,
  LINKS,
  LINK_HINTS,
  LINK_LABELS,
  type ConfigBlock,
} from './drone-config-logic';
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
  drone: 'Add a real drone',
};

/**
 * `OnboardingPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — orchestrates `OnboardingStore`/
 * `SettingsStore`/`ToastService`, exactly what the page injected directly before this refactor.
 * `OnboardingStore` already owns the wizard's whole step machine/draft state/HTTP orchestration (this
 * component's own page-provided "component store"); this facade adds only the small set of
 * page-local read-models/commands `OnboardingPage` used to own directly (label maps, the summary
 * lines, the clipboard/download helpers) — every one byte-for-byte unchanged.
 *
 * `OnboardingStore.flightPlanDialogOpen` stays where it already lives (that store) rather than
 * moving to a `UiStore` group — it's the only dialog this page ever shows, so there is nothing for
 * it to be mutually exclusive *with* (docs/UI-ARCHITECTURE-PLAN.md's own explicit carve-out for
 * this exact field).
 */
@Injectable()
export class OnboardingFacade {
  readonly store = inject(OnboardingStore);
  readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);

  readonly steps: readonly StepDescriptor[] = WIZARD_STEPS.map((step) => ({
    step,
    label: STEP_LABELS[step],
  }));

  readonly connectMethodLabels = CONNECT_METHOD_LABELS;

  // --- "Add a real drone" (docs/DRONE-INFRA-PLAN.md I-g) — the picker's own option lists/labels,
  //     same "defined alongside the other display config, not the store" convention
  //     `CONNECT_METHOD_LABELS`/`STEP_LABELS` already follow above.
  readonly firmwareOptions = FIRMWARES;
  readonly linkOptions = LINKS;
  readonly firmwareLabels = FIRMWARE_LABELS;
  readonly linkLabels = LINK_LABELS;
  readonly linkHints = LINK_HINTS;

  readonly categoryName = computed(() => {
    const slug = this.store.category();
    return this.store.categoryOptions().find((option) => option.slug === slug)?.name ?? slug;
  });

  /** The Create step's one-line summary of the Connect step's outcome — never re-derives a request. */
  readonly connectionSummary = computed(() => {
    switch (this.store.connectMethod()) {
      case 'register':
      case 'discover':
      case 'listen':
      case 'drone':
        // 'drone' never actually reaches Create — it always hands off to 'listen' first
        // (`OnboardingStore#finishDroneConfigAndListen`), which itself always pivots to 'register'
        // on "Use" — kept as a real branch anyway, same "unreachable in practice, still correct"
        // precedent 'discover'/'listen' already established here.
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

  stepIndex(step: WizardStep): number {
    return WIZARD_STEPS.indexOf(step);
  }

  detailPairs(details: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(details).map(([key, value]) => ({ key, value }));
  }

  // --- "Listen for drones" results list (docs/DRONE-INFRA-PLAN.md I-b) — thin helpers over
  //     `drone-scan-logic.ts`'s own pure functions, same pattern as `detailPairs` above.

  droneVehicleClaimed(candidate: DiscoveredDevice): boolean {
    return isClaimedVehicle(candidate);
  }

  droneVehicleChips(candidate: DiscoveredDevice): readonly VehicleDetailChip[] {
    return vehicleDetailChips(candidate);
  }

  onPhotoSelected(file: File | undefined): void {
    if (file) {
      void this.store.choosePhoto(file);
    }
  }

  // --- "Add a real drone" (docs/DRONE-INFRA-PLAN.md I-g) -------------------------------------------

  /**
   * The Connect step's "‹ back" row is shared by every method (`onboarding.html`'s
   * `.method-chosen-row`); for `drone` specifically it must step back one sub-state
   * (`config` → `picker`) before falling through to the generic "leave this method entirely" — every
   * other method has only one sub-state, so this is the one place that distinction matters.
   */
  onConnectBack(): void {
    if (this.store.connectMethod() === 'drone' && this.store.droneSubStep() === 'config') {
      this.store.backFromDroneConfig();
      return;
    }
    this.store.connectMethod.set(null);
  }

  /**
   * Copies one config block's body (docs/DRONE-INFRA-PLAN.md I-g) — mirrors
   * `shared/player/stream-info-panel.ts#copyViewUrl`'s existing `navigator.clipboard` + toast
   * try/catch precedent verbatim rather than inventing a second clipboard affordance.
   */
  async copyBlock(block: ConfigBlock): Promise<void> {
    try {
      await navigator.clipboard.writeText(block.body);
      this.toasts.ok(`"${block.title}" copied.`);
    } catch {
      this.toasts.error('Could not copy automatically — select the text above and copy it manually.');
    }
  }

  /** Client-side `Blob` → `<a download>` (docs/DRONE-INFRA-PLAN.md I-g) — only blocks with a `filename` offer this. */
  downloadBlock(block: ConfigBlock): void {
    if (!block.filename) {
      return;
    }
    const url = URL.createObjectURL(new Blob([block.body], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = block.filename;
    link.click();
    URL.revokeObjectURL(url);
  }
}
