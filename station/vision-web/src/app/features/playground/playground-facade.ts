import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';
import {
  buildSimulationRequest,
  buildTestDroneRequest,
  type FileSimulateForm,
  type TestDroneForm,
} from '../../core/fleet/simulation-logic';
import { buildTelemetryRequest, type FlightPlanForm } from '../../shared/map/flight-plan-logic';
import {
  PLAYGROUND_MODES,
  PLAYGROUND_MODE_HINTS,
  PLAYGROUND_MODE_LABELS,
  canSubmitPlayground,
  playgroundModeNeedsVideoPath,
  type PlaygroundMode,
} from './playground-logic';
import type { SimulationResponse, StartSimulationRequest } from '../../core/api/models';

/**
 * `PlaygroundPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — owns every store injection,
 * the create-form's draft state, and the one HTTP-backed command (`submit`). The page itself stays a
 * dumb OnPush shell reading these signals, per this app's usual Component→Facade→Store→Service
 * layering.
 *
 * **The only place a simulated asset is created** (docs/plans/active/LINK-PAIRING-PLAN.md §3.7/§4 row
 * L4) — `FleetStore.simulate()` (`POST /api/simulations`) is called from exactly this facade now;
 * the onboarding wizard's own former call site is dormant (`OnboardingStore#createViaSimulation`
 * stays in place but is unreachable from a fresh choice — `features/onboarding/source-step.ts`'s own
 * class doc comment has the full history). `core/fleet/simulation-logic.ts`'s request-builders are
 * reused unchanged — this file adds no new request-shaping, only a narrower three-mode form
 * (`playground-logic.ts`'s own doc comment explains why `synthetic` is left out).
 */
@Injectable()
export class PlaygroundFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  readonly fleet = inject(FleetStore);

  readonly modes = PLAYGROUND_MODES;
  readonly modeLabels = PLAYGROUND_MODE_LABELS;
  readonly modeHints = PLAYGROUND_MODE_HINTS;

  /**
   * Deploy-time capability, read off the same `GET /api/system/network` response the onboarding
   * wizard already fetches for its drone-config snippets — see `SystemNetworkResponse
   * .simulationEnabled`'s own doc comment (`core/api/models.ts`) for why there is no dedicated
   * endpoint yet, and why this defaults to `true` (enabled) both before the fetch resolves and when
   * the field itself is absent: dev-parity (`vision.auth.enabled=false`) and an un-upgraded backend
   * both keep today's "simulate creation is unconditionally available" behavior rather than silently
   * losing the feature to an assumed field nobody has shipped yet.
   */
  readonly simulationEnabled = signal(true);
  readonly checkingCapability = signal(true);

  readonly mode = signal<PlaygroundMode>('testDrone');
  readonly name = signal('');
  readonly videoPath = signal('');
  readonly latitude = signal<number | null>(null);
  readonly longitude = signal<number | null>(null);
  readonly autoStart = signal(true);
  readonly flightPlan = signal<FlightPlanForm | undefined>(undefined);
  readonly flightPlanDialogOpen = signal(false);

  readonly busy = signal(false);
  /** The most recently created asset, shown as a dismissible success card — cleared by
   *  {@link dismissCreated} or the next successful {@link submit}. */
  readonly lastCreated = signal<SimulationResponse | undefined>(undefined);

  readonly needsVideoPath = computed(() => playgroundModeNeedsVideoPath(this.mode()));
  readonly canSubmit = computed(
    () => !this.busy() && canSubmitPlayground({ mode: this.mode(), videoPath: this.videoPath() }),
  );

  readonly flightPlanSummary = computed(() => {
    const plan = this.flightPlan();
    return plan ? `${plan.waypoints.length} waypoints · ${plan.routeMode}` : null;
  });

  /** Reads {@link simulationEnabled} — best-effort, like every other capability probe in this app
   *  (`core/training/training-store.ts`'s own precedent): a failed fetch degrades to the same
   *  dev-parity default a missing field would, never a blocked page. */
  async load(): Promise<void> {
    this.checkingCapability.set(true);
    try {
      const network = await this.api.systemNetwork();
      this.simulationEnabled.set(network.simulationEnabled ?? true);
    } catch {
      this.simulationEnabled.set(true);
    } finally {
      this.checkingCapability.set(false);
    }
  }

  setMode(mode: PlaygroundMode): void {
    this.mode.set(mode);
  }

  openFlightPlanDialog(): void {
    this.flightPlanDialogOpen.set(true);
  }

  onFlightPlanSaved(plan: FlightPlanForm): void {
    this.flightPlan.set(plan);
    this.flightPlanDialogOpen.set(false);
  }

  onFlightPlanCancelled(): void {
    this.flightPlanDialogOpen.set(false);
  }

  clearFlightPlan(): void {
    this.flightPlan.set(undefined);
  }

  dismissCreated(): void {
    this.lastCreated.set(undefined);
  }

  /** `FleetStore.simulate()` already toasts on failure (`run()`'s own doc comment) — this only adds
   *  the success toast/card, matching that store's own "caller decides the success path" contract. */
  async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await this.fleet.simulate(this.buildRequest());
      if (result) {
        this.toasts.ok(`Created "${this.name().trim() || result.assetId}" in Playground.`);
        this.lastCreated.set(result);
        this.resetForm();
      }
    } finally {
      this.busy.set(false);
    }
  }

  private buildRequest(): StartSimulationRequest {
    const telemetry = this.flightPlan() ? buildTelemetryRequest(this.flightPlan()!) : undefined;
    if (this.mode() === 'testDrone') {
      const form: TestDroneForm = {
        name: this.name(),
        latitude: this.latitude(),
        longitude: this.longitude(),
        autoStart: this.autoStart(),
        telemetry,
      };
      return buildTestDroneRequest(form);
    }
    const form: FileSimulateForm = {
      name: this.name(),
      videoPath: this.videoPath(),
      mode: this.mode() as 'direct' | 'rtsp',
      latitude: this.latitude(),
      longitude: this.longitude(),
      autoStart: this.autoStart(),
      telemetry,
    };
    return buildSimulationRequest(form);
  }

  /** `mode`/`autoStart` are deliberately preserved — an operator spinning up several of the same
   *  kind of test asset in a row shouldn't have to re-pick either after every create. */
  private resetForm(): void {
    this.name.set('');
    this.videoPath.set('');
    this.latitude.set(null);
    this.longitude.set(null);
    this.flightPlan.set(undefined);
  }
}
