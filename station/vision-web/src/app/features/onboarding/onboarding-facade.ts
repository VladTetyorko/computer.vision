import { Injectable, computed, inject } from '@angular/core';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { OnboardingStore } from './onboarding-store';
import { visibleSteps, type WizardStep } from './onboarding-logic';
import {
  FIT_OUT_FIND_METHODS,
  FIT_OUT_ROLES,
  combinedSysidCollision,
  usesLegacySimulationPath,
  type FitOutFindMethod,
  type FitOutRole,
} from '../../core/onboarding/fit-out-logic';
import {
  detailsSummary,
  isClaimedVehicle,
  vehicleDetailChips,
  type VehicleDetailChip,
} from './drone-scan-logic';
import {
  FIRMWARES,
  FIRMWARE_LABELS,
  LINKS,
  LINK_HINTS,
  LINK_LABELS,
  type ConfigBlock,
} from './drone-config-logic';
import { outcomeLabel, outcomeTone } from '../../core/readiness/readiness-logic';
import type { DiscoveredDevice, ParameterWriteResponse } from '../../core/api/models';

interface StepDescriptor {
  readonly step: WizardStep;
  readonly label: string;
}

/** The stepper's own label row (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 — "Identify · Connect · Prove · Register · Hand over"). `sysid` is never included — see `onboarding-logic.ts#WizardStep`'s own doc comment. */
const STEP_LABELS: Record<WizardStep, string> = {
  identify: 'Identify',
  connect: 'Connect',
  prove: 'Prove',
  register: 'Register',
  sysid: 'Sysid',
  handover: 'Hand over',
};

/** Each fit-out row's own finder tile labels (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §6) — `FIT_OUT_FIND_METHODS` in `fit-out-logic.ts` fixes which of these a given role actually offers. */
const FIND_METHOD_LABELS: Record<FitOutFindMethod, string> = {
  register: 'Enter a stream address',
  discover: 'Find cameras on my network',
  listen: 'Find nearby drones',
  drone: 'Add a real drone',
};

/** Each finder tile's own one-line sub-copy — moved verbatim from the pre-W6 Connect step's five tiles. */
const FIND_METHOD_HINTS: Record<FitOutFindMethod, string> = {
  register: "Paste a camera's stream URL if you already have one (RTSP, MJPEG, HTTP).",
  discover: "Scan your local network and pick from what's found — no address needed.",
  listen: 'Listen for drones already broadcasting telemetry — nothing to type.',
  drone: "Connect a flight controller (Betaflight, INAV, ArduPilot) — we'll walk you through it with the connection settings pre-filled.",
};

/**
 * `OnboardingPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — orchestrates `OnboardingStore`/
 * `SettingsStore`/`ToastService`, exactly what the page injected directly before this refactor.
 * `OnboardingStore` already owns the wizard's whole step machine/draft state/HTTP orchestration (this
 * component's own page-provided "component store"); this facade adds only the small set of
 * page-local read-models/commands `OnboardingPage` used to own directly (label maps, the summary
 * lines, the clipboard/download helpers) — every one byte-for-byte unchanged from the pre-W6 wizard
 * except where the fit-out table's per-row shape required it (`connectionSummary` below, now
 * `rowSummary`; `onConnectBack`, now `onRowBack`).
 *
 * `OnboardingStore.flightPlanDialogOpen` stays where it already lives (that store) rather than
 * moving to a `UiStore` group — it's the only dialog this page ever shows, so there is nothing for
 * it to be mutually exclusive *with* (docs/plans/done/UI-ARCHITECTURE-PLAN.md's own explicit carve-out for
 * this exact field).
 */
@Injectable()
export class OnboardingFacade {
  readonly store = inject(OnboardingStore);
  readonly settings = inject(SettingsStore);
  private readonly toasts = inject(ToastService);

  /** Two steps for equipment (`connected: false`), five otherwise — see `onboarding-logic.ts#visibleSteps`'s own doc comment. */
  readonly steps = computed<readonly StepDescriptor[]>(() =>
    visibleSteps(this.store.categoryConnected()).map((step) => ({ step, label: STEP_LABELS[step] })),
  );

  readonly findMethodLabels = FIND_METHOD_LABELS;
  readonly findMethodHints = FIND_METHOD_HINTS;

  /** The Connect step's two rows, in render order — see `fit-out-logic.ts#FIT_OUT_ROLES`. */
  readonly fitOutRoles = FIT_OUT_ROLES;
  /** Which finder tiles each row offers — see `fit-out-logic.ts#FIT_OUT_FIND_METHODS`'s own doc comment. */
  readonly fitOutFindMethods = FIT_OUT_FIND_METHODS;

  /**
   * Whether the Connect step must also render the legacy whole-vehicle Simulate sub-form (mode
   * picker, video path, flight plan) below the fit-out table — see
   * `fit-out-logic.ts#usesLegacySimulationPath`'s own doc comment for exactly which row combination
   * this is. A plain simulated Sense/Sight row outside that combination needs no further setup: the
   * row itself already reads "Test source" once chosen.
   */
  readonly showLegacySimulateConfig = computed(() => usesLegacySimulationPath(this.store.rows()));

  // --- "Add a real drone" (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — the picker's own option lists/labels,
  //     same "defined alongside the other display config, not the store" convention
  //     `FIND_METHOD_LABELS`/`STEP_LABELS` already follow above.
  readonly firmwareOptions = FIRMWARES;
  readonly linkOptions = LINKS;
  readonly firmwareLabels = FIRMWARE_LABELS;
  readonly linkLabels = LINK_LABELS;
  readonly linkHints = LINK_HINTS;

  readonly categoryName = computed(() => {
    const slug = this.store.category();
    return this.store.categoryOptions().find((option) => option.slug === slug)?.name ?? slug;
  });

  /** The Register step's own equipment/Receive wording — "Receive" for a `connected: false` category, "Create asset" otherwise. */
  readonly registerActionLabel = computed(() => (this.store.categoryConnected() ? 'Create asset' : 'Receive'));

  /** The Register step's one-line-per-row summary of the Connect step's outcome — never re-derives a request. */
  rowSummary(role: FitOutRole): string {
    const row = this.store.rows()[role];
    switch (row.value) {
      case 'none':
        return '—';
      case 'find':
        return `${this.store.rowProtocol(role)} — ${row.uri}`;
      case 'simulate':
        // The legacy whole-vehicle Simulate path only ever fires for the Sight row
        // (`fit-out-logic.ts#usesLegacySimulationPath`) — a simulated Sense row alone (no legacy
        // path) is the plain "sim://telemetry" device `fitOutRowToDeviceSpec` builds.
        return role === 'sight' && this.store.rows().sight.value === 'simulate' && this.store.rows().sense.value !== 'find'
          ? this.simulateSummary()
          : 'Simulated test source';
    }
  }

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
    return this.steps().findIndex((s) => s.step === step);
  }

  /** See {@link detailsSummary} — the general "Discover on network" table's own Details column. */
  detailsSummary(details: Record<string, string>): string {
    return detailsSummary(details);
  }

  // --- "Listen for drones" results list (docs/plans/active/DRONE-INFRA-PLAN.md I-b) — thin helpers over
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

  // --- "Add a real drone" (docs/plans/active/DRONE-INFRA-PLAN.md I-g) -------------------------------------------

  /**
   * A row's "‹ back" affordance is shared by every finder (`onboarding.html`'s
   * `.method-chosen-row`, now rendered once per fit-out row); for `drone` specifically it must step
   * back one sub-state (`config` → `picker`) before falling through to the generic "leave this
   * finder entirely" — every other finder has only one sub-state, so this is the one place that
   * distinction matters. `drone` only ever appears on the Sense row (`FIT_OUT_FIND_METHODS`), but
   * this checks the row's own `findMethod` rather than hardcoding that, so a future finder
   * reassignment can't silently break it.
   */
  onRowBack(role: FitOutRole): void {
    const row = this.store.rows()[role];
    if (row.findMethod === 'drone' && this.store.droneSubStep() === 'config') {
      this.store.backFromDroneConfig();
      return;
    }
    this.store.backFromRowFind(role);
  }

  /**
   * Copies one config block's body (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — mirrors
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

  /** Client-side `Blob` → `<a download>` (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — only blocks with a `filename` offer this. */
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

  // --- Step 4.5: fix a sysid collision (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0) -----------------
  // Reuses `core/readiness/readiness-logic.ts#outcomeLabel`/`outcomeTone` — the same
  // `ParameterWriteOutcome`-shaped `'ACCEPTED'|'DENIED'|'NO_ACK'|'UNSUPPORTED'` union
  // `RemediationAction#outcome` already carries, `features/readiness/readiness.html`'s own second
  // consumer of both.

  readonly outcomeLabel = outcomeLabel;

  /** The collided sysid itself, for the step's own wording — {@link combinedSysidCollision} over both rows' Prove results (only a `mavlink` Sense row realistically ever sets one, but this stays total over both). */
  readonly sysidCollision = computed(() =>
    combinedSysidCollision({ sense: this.store.proveByRole().sense.sysidCollision, sight: this.store.proveByRole().sight.sysidCollision }),
  );

  /** {@link outcomeTone} maps to a `vision-notice` variant — `'muted'` (UNSUPPORTED) has no notice-variant equivalent, so it renders as `'neutral'`, mirroring `readiness.ts#remediationVariant`'s own precedent. */
  sysidOutcomeVariant(outcome: ParameterWriteResponse['outcome']): 'neutral' | 'warn' | 'danger' | 'ok' {
    const tone = outcomeTone(outcome);
    return tone === 'muted' ? 'neutral' : tone;
  }
}
