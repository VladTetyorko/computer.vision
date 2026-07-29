import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import {
  DEFAULT_CATEGORY_OPTIONS,
  deriveCategoryOptions,
  type CategoryOption,
} from '../../core/fleet/category-logic';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  buildTestDroneRequest,
  type SimulateMode,
} from '../../core/fleet/simulation-logic';
import { buildMavlinkScanRequest, isClaimedVehicle, prefillFromVehicle } from './drone-scan-logic';
import { buildTelemetryRequest, type FlightPlanForm } from '../../shared/map/flight-plan-logic';
import type {
  DiscoveredDevice,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  ScanResult,
  TelemetryPlanRequest,
} from '../../core/api/models';
import {
  CUSTOM_PROTOCOL_OPTION,
  REGISTERABLE_PROTOCOLS,
  placeholderForProtocol,
  protocolSelectionFor,
} from './protocols';
import { downscaleImageToJpeg, isAcceptableImageType } from './image-downscale';
import {
  buildCreateAssetRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  canAdvanceFromConnect,
  canAdvanceFromProfile,
  canAdvanceFromTest,
  nextStep,
  prevStep,
  type ConnectMethod,
  type WizardStep,
} from './onboarding-logic';

interface OptionRow {
  key: string;
  value: string;
}

/** Scan durations worth offering — mirrors the pre-wizard Devices page's own choice exactly. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/** Shown under the Simulate mode selector — one sentence per mode, docs/CYCLES-PLAN.md §4's own wording. */
const SIMULATE_MODE_HINTS: Record<SimulateMode, string> = {
  direct: 'Plays the file straight through the pipeline — the simplest way to see it work.',
  rtsp: 'Rehearse the real protocol path: the platform transmits your file over RTSP and ingests it back like real hardware.',
  synthetic: 'No file needed — creates a still, pattern-only test asset with no telemetry.',
  testDrone: 'No file needed — places a moving drone on a circular flight path around a home point, watchable immediately.',
};

/**
 * The onboarding wizard's own "component store" (docs/UX-REWORK-PLAN.md §U-d) — provided per-route
 * on `OnboardingPage` (`providers: [OnboardingStore]`, same DI-sharing idiom as
 * `AssetDetailPage`'s `TelemetryStore`/`DetectionsStore`), not `providedIn: 'root'`: wizard state has
 * no reason to survive leaving `/add-source`, and a fresh instance per visit means a second pass
 * through the wizard never starts warm with a previous attempt's half-filled form.
 *
 * Holds every signal across all four steps and orchestrates the actual HTTP calls; every yes/no
 * decision (can this step advance?) and every request shape is delegated to the pure functions in
 * `onboarding-logic.ts` — this class is deliberately thin glue, not where the interesting logic
 * lives (mirrors `features/devices/devices.ts`'s own relationship with `devices-page-logic.ts`
 * before this cycle moved the wizard out of it).
 */
@Injectable()
export class OnboardingStore {
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  readonly step = signal<WizardStep>('profile');

  // --- Step 1: Profile -----------------------------------------------------------------------

  readonly displayName = signal('');
  readonly registrationNumber = signal('');
  readonly category = signal('');
  readonly categoryOptions = signal<readonly CategoryOption[]>(DEFAULT_CATEGORY_OPTIONS);

  readonly photoFile = signal<File | null>(null);
  readonly photoBlob = signal<Blob | null>(null);
  readonly photoPreviewUrl = signal<string | null>(null);
  readonly photoProcessing = signal(false);
  readonly photoError = signal<string | null>(null);

  readonly canAdvanceProfile = computed(() => canAdvanceFromProfile(this.displayName(), this.category()));

  chooseCategory(slug: string): void {
    this.category.set(slug);
  }

  /** Downscales in the background (`image-downscale.ts`); the field input stays usable meanwhile. */
  async choosePhoto(file: File): Promise<void> {
    if (!isAcceptableImageType(file.type)) {
      this.photoError.set('Choose a JPEG, PNG, or WebP image.');
      return;
    }
    this.photoFile.set(file);
    this.photoError.set(null);
    this.photoProcessing.set(true);
    try {
      const blob = await downscaleImageToJpeg(file);
      this.setPreview(blob);
    } catch {
      this.photoError.set('Could not process that image — try a different file.');
      this.photoBlob.set(null);
    } finally {
      this.photoProcessing.set(false);
    }
  }

  removePhoto(): void {
    this.revokePreview();
    this.photoFile.set(null);
    this.photoBlob.set(null);
    this.photoError.set(null);
  }

  private setPreview(blob: Blob): void {
    this.revokePreview();
    this.photoBlob.set(blob);
    this.photoPreviewUrl.set(URL.createObjectURL(blob));
  }

  private revokePreview(): void {
    const url = this.photoPreviewUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.photoPreviewUrl.set(null);
  }

  // --- Step 2: Connect (the pre-existing 3-choice register/discover/simulate component, moved
  //     verbatim from `features/devices/devices.ts` — docs/UX-REWORK-PLAN.md §U-d) -------------

  readonly connectMethod = signal<ConnectMethod | null>(null);
  readonly registerableProtocols = REGISTERABLE_PROTOCOLS;
  readonly customProtocolOption = CUSTOM_PROTOCOL_OPTION;

  readonly protocolSelect = signal('');
  readonly customProtocol = signal('');
  readonly uri = signal('');
  readonly options = signal<readonly OptionRow[]>([]);

  readonly isCustomProtocol = computed(() => this.protocolSelect() === CUSTOM_PROTOCOL_OPTION);
  /** The protocol string actually sent — the select's value, or the free-text field under `Custom…`. */
  readonly protocol = computed(() => (this.isCustomProtocol() ? this.customProtocol() : this.protocolSelect()));
  readonly uriPlaceholder = computed(() => placeholderForProtocol(this.protocol()));

  chooseMethod(method: ConnectMethod): void {
    this.connectMethod.set(method);
  }

  addOptionRow(): void {
    this.options.update((rows) => [...rows, { key: '', value: '' }]);
  }

  removeOptionRow(index: number): void {
    this.options.update((rows) => rows.filter((_, i) => i !== index));
  }

  updateOptionKey(index: number, key: string): void {
    this.options.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  updateOptionValue(index: number, value: string): void {
    this.options.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }

  private collectOptions(): Record<string, string> | undefined {
    const entries = this.options()
      .filter((row) => row.key.trim().length > 0)
      .map((row) => [row.key.trim(), row.value] as const);
    return entries.length > 0 ? Object.fromEntries(entries) : undefined;
  }

  // --- Connect: discovery ----------------------------------------------------------------------

  readonly scanTimeouts = SCAN_TIMEOUTS;
  readonly scanTimeout = signal<number>(4_000);
  readonly scanning = signal(false);
  readonly scanResult = signal<ScanResult | null>(null);

  async scan(): Promise<void> {
    this.scanning.set(true);
    try {
      const result = await this.api.scan({ timeoutMs: this.scanTimeout() });
      this.scanResult.set(result);
      if (result.failedMethods.length > 0) {
        this.toasts.error(`These scanners failed and found nothing: ${result.failedMethods.join(', ')}.`);
      }
      if (result.devices.length === 0 && result.failedMethods.length === 0) {
        this.toasts.info('Scan finished — nothing responded on this network.');
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.scanning.set(false);
    }
  }

  /** Fills the register fields from a discovery candidate and switches to the register sub-view. */
  useCandidate(candidate: DiscoveredDevice): void {
    const selection = protocolSelectionFor(candidate.protocol);
    this.protocolSelect.set(selection.select);
    this.customProtocol.set(selection.custom);
    this.uri.set(candidate.uri ?? candidate.address);
    this.options.set([]);
    this.connectMethod.set('register');
    if (this.displayName().trim().length === 0) {
      this.displayName.set(candidate.name);
    }
    if (!candidate.uri) {
      this.toasts.info(`${candidate.method} could not supply a stream URI — check the address before registering.`);
    }
  }

  // --- Connect: "Listen for drones" (docs/DRONE-INFRA-PLAN.md I-b) — a MAVLink-heartbeat-only scan,
  //     distinct from the general `discover` method above; see `drone-scan-logic.ts`'s own doc
  //     comment for why this is a separate pure-logic module rather than folded into that one. ------

  readonly droneScanning = signal(false);
  readonly droneScanResult = signal<ScanResult | null>(null);

  /**
   * No `timeoutMs` is sent — the scanner self-time-boxes (docs/DRONE-INFRA-PLAN.md I-b: "scans take
   * ~5-10s"), so unlike the general scan above there is no timeout picker to read from. "Allow
   * cancel-by-navigation" (the plan's own wording): leaving `/add-source` destroys this
   * per-route-provided store (see this class's own doc comment), so an in-flight scan's eventual
   * response simply has nowhere left to land — no `AbortController` needed for that guarantee.
   */
  async scanForDrones(): Promise<void> {
    this.droneScanning.set(true);
    try {
      const result = await this.api.scan(buildMavlinkScanRequest());
      this.droneScanResult.set(result);
      if (result.failedMethods.length > 0) {
        this.toasts.error('The MAVLink scanner failed — check that nothing else is bound to its port.');
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.droneScanning.set(false);
    }
  }

  /**
   * Fills the register fields from an unclaimed heard vehicle and switches to the register
   * sub-view — the same "candidate → register" pivot `useCandidate` uses, plus
   * `suggestedCategory` (docs/DRONE-INFRA-PLAN.md I-b — closes this wizard's own previously-documented
   * gap of never wiring a discovery suggestion into the Profile step's category picker, for this one
   * path). A no-op for an already-claimed vehicle (the UI never offers this action for one, but a
   * defensive check costs nothing — see `isClaimedVehicle`'s own doc comment for why one can exist
   * in the results list at all).
   */
  useDroneVehicle(candidate: DiscoveredDevice): void {
    if (isClaimedVehicle(candidate)) {
      return;
    }
    const prefill = prefillFromVehicle(candidate);
    const selection = protocolSelectionFor(prefill.protocol);
    this.protocolSelect.set(selection.select);
    this.customProtocol.set(selection.custom);
    this.uri.set(prefill.uri);
    this.options.set(
      prefill.options ? Object.entries(prefill.options).map(([key, value]) => ({ key, value })) : [],
    );
    this.connectMethod.set('register');
    if (this.displayName().trim().length === 0) {
      this.displayName.set(candidate.name);
    }
    if (prefill.suggestedCategory && this.category().trim().length === 0) {
      this.chooseCategory(prefill.suggestedCategory);
    }
  }

  // --- Connect: simulate -----------------------------------------------------------------------

  readonly simMode = signal<SimulateMode>('direct');
  readonly simVideoPath = signal('');
  readonly simLatitude = signal<number | null>(null);
  readonly simLongitude = signal<number | null>(null);
  readonly simAutoStart = signal(true);

  readonly simModeHint = computed(() => SIMULATE_MODE_HINTS[this.simMode()]);
  readonly simNeedsVideoPath = computed(() => this.simMode() === 'direct' || this.simMode() === 'rtsp');
  readonly simNeedsHomePoint = computed(() => this.simMode() !== 'synthetic');

  readonly flightPlanDialogOpen = signal(false);
  readonly flightPlan = signal<FlightPlanForm | undefined>(undefined);

  readonly flightPlanSummary = computed(() => {
    const plan = this.flightPlan();
    return plan ? `${plan.waypoints.length} waypoints · ${plan.routeMode}` : null;
  });

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

  private currentTelemetryRequest(): TelemetryPlanRequest | undefined {
    const plan = this.flightPlan();
    return plan ? buildTelemetryRequest(plan) : undefined;
  }

  // --- Connect: advance gate + step 3 (Test) ----------------------------------------------------

  readonly canAdvanceConnect = computed(() =>
    canAdvanceFromConnect({
      method: this.connectMethod(),
      protocol: this.protocol(),
      uri: this.uri(),
      simMode: this.simMode(),
      simVideoPath: this.simVideoPath(),
    }),
  );

  /** `null` for `discover` (no candidate chosen yet) and `simulate` (this step is skipped for it). */
  private readonly currentProbeRequest = computed<ProbeDeviceRequest | null>(() => {
    if (this.connectMethod() !== 'register') {
      return null;
    }
    return buildProbeRequest({ protocol: this.protocol(), uri: this.uri(), options: this.collectOptions() });
  });

  readonly probing = signal(false);
  private readonly lastProbeRequest = signal<ProbeDeviceRequest | null>(null);
  readonly lastProbeResult = signal<ProbeDeviceResult | null>(null);
  readonly lastProbeError = signal<string | null>(null);

  /** Whether the last successful probe was for *these exact* connection fields, not a stale one. */
  private readonly probeStillCurrent = computed(() => {
    const last = this.lastProbeRequest();
    const current = this.currentProbeRequest();
    return (
      last !== null &&
      current !== null &&
      last.protocol === current.protocol &&
      last.uri === current.uri &&
      JSON.stringify(last.options ?? {}) === JSON.stringify(current.options ?? {})
    );
  });

  private readonly lastProbeOk = computed(
    () => this.probeStillCurrent() && this.lastProbeResult()?.ok === true,
  );

  readonly canAdvanceTest = computed(() => canAdvanceFromTest(this.connectMethod(), this.lastProbeOk()));

  async probe(): Promise<void> {
    const request = this.currentProbeRequest();
    if (!request) {
      return;
    }
    this.probing.set(true);
    this.lastProbeError.set(null);
    try {
      const result = await this.api.probeDevice(request);
      this.lastProbeRequest.set(request);
      this.lastProbeResult.set(result);
      if (!result.ok) {
        this.lastProbeError.set('The device did not report success — check the warnings below.');
      }
    } catch (error) {
      this.lastProbeRequest.set(request);
      this.lastProbeResult.set(null);
      this.lastProbeError.set(describeHttpError(error));
    } finally {
      this.probing.set(false);
    }
  }

  // --- Step 4: Create ---------------------------------------------------------------------------

  readonly creating = signal(false);

  async createAsset(): Promise<void> {
    if (this.creating()) {
      return;
    }
    this.creating.set(true);
    try {
      if (this.connectMethod() === 'simulate') {
        await this.createViaSimulation();
      } else {
        await this.createViaConnection();
      }
    } finally {
      this.creating.set(false);
    }
  }

  /** Register/Discover paths: one `POST /api/assets` call, the device embedded (docs/UX-REWORK-PLAN.md §U-d item 2). */
  private async createViaConnection(): Promise<void> {
    const request = buildCreateAssetRequest(
      { displayName: this.displayName(), registrationNumber: this.registrationNumber(), category: this.category() },
      { protocol: this.protocol(), uri: this.uri(), options: this.collectOptions() },
    );
    try {
      const created = await this.api.createAsset(request);
      await this.finishCreate(created.assetId, created.displayName);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  /**
   * Simulate path (docs/UX-REWORK-PLAN.md §U-d item 1): `synthetic` carries no video/telemetry at
   * all — it is, underneath, a fixed `sim`/`sim://demo` connection, so it goes through the exact
   * same `POST /api/assets` call as Register/Discover (closing the same orphaned-device dead end
   * for it too, rather than perpetuating the pre-wizard "Add the simulated source" quick-add's own
   * orphan — see this method's own inline comment). `direct`/`rtsp`/`testDrone` genuinely need the
   * simulation service to wire up a synthetic pipeline, so those three route through
   * `POST /api/simulations` "as today", then a follow-up `PATCH` applies name/attributes (the
   * simulation endpoint has no `attributes`/custom-`displayName`-at-create-time-honoring-photo
   * shape of its own).
   */
  private async createViaSimulation(): Promise<void> {
    try {
      if (this.simMode() === 'synthetic') {
        // Not an orphaned raw-device registration (unlike the pre-wizard "Add the simulated
        // source" quick-add still used by features/devices/devices.ts's own empty state) — wrapped
        // into a real asset immediately, exactly like Register/Discover.
        const synthetic = buildSyntheticRegisterRequest(this.displayName());
        const request = buildCreateAssetRequest(
          { displayName: this.displayName(), registrationNumber: this.registrationNumber(), category: this.category() },
          { protocol: synthetic.protocol, uri: synthetic.uri },
        );
        const created = await this.api.createAsset(request);
        await this.finishCreate(created.assetId, created.displayName);
        return;
      }

      const telemetry = this.currentTelemetryRequest();
      const request =
        this.simMode() === 'testDrone'
          ? buildTestDroneRequest({
              name: this.displayName(),
              latitude: this.simLatitude(),
              longitude: this.simLongitude(),
              autoStart: this.simAutoStart(),
              telemetry,
            })
          : buildSimulationRequest({
              name: this.displayName(),
              videoPath: this.simVideoPath(),
              mode: this.simMode() as 'direct' | 'rtsp',
              latitude: this.simLatitude(),
              longitude: this.simLongitude(),
              autoStart: this.simAutoStart(),
              telemetry,
            });
      const response = await this.fleet.simulate(request);
      if (!response) {
        return; // failure already toasted by FleetStore.run()
      }

      const edit = buildPostSimulationAssetEdit({
        displayName: this.displayName(),
        registrationNumber: this.registrationNumber(),
      });
      const displayName =
        Object.keys(edit).length > 0
          ? (await this.api.updateAsset(response.assetId, edit)).displayName
          : this.displayName().trim();
      await this.finishCreate(response.assetId, displayName || response.assetId);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  private async finishCreate(assetId: string, displayName: string): Promise<void> {
    const blob = this.photoBlob();
    if (blob) {
      try {
        await this.api.uploadAssetImage(assetId, blob);
      } catch (error) {
        this.toasts.error(`"${displayName}" was created, but the photo could not be uploaded: ${describeHttpError(error)}`);
      }
    }
    await this.fleet.refresh({ quiet: true });
    this.toasts.ok(`"${displayName}" is ready.`);
    await this.router.navigate(['/assets', assetId]);
  }

  // --- Step navigation (docs/UX-REWORK-PLAN.md §U-d item 1 — stepper, back-navable) -------------

  readonly canAdvance = computed(() => {
    switch (this.step()) {
      case 'profile':
        return this.canAdvanceProfile();
      case 'connect':
        return this.canAdvanceConnect();
      case 'test':
        return this.canAdvanceTest();
      case 'create':
        return false; // the Create step has its own "Create asset" action, not a "Next"
    }
  });

  next(): void {
    if (!this.canAdvance()) {
      return;
    }
    this.step.set(nextStep(this.step(), this.connectMethod()));
  }

  back(): void {
    this.step.set(prevStep(this.step(), this.connectMethod()));
  }

  constructor() {
    void this.loadCategoryOptions();
    inject(DestroyRef).onDestroy(() => this.revokePreview());
  }

  private async loadCategoryOptions(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.categoryOptions.set(deriveCategoryOptions(assets));
    } catch {
      // Silent-degrade — the fallback list (DEFAULT_CATEGORY_OPTIONS) is already in place.
    }
  }
}
