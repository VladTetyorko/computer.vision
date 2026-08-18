import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { AuthStore } from '../../core/auth/auth-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import { deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  buildTestDroneRequest,
  type SimulateMode,
} from '../../core/fleet/simulation-logic';
import { isProbeDisabledError } from '../../core/readiness/readiness-logic';
import { buildMavlinkScanRequest, isClaimedVehicle } from './drone-scan-logic';
import {
  buildDroneDeviceSpec,
  linkCompatibility,
  configSnippets,
  type ConfigBlock,
  type Firmware,
  type LinkCompatibility,
  type LinkType,
} from './drone-config-logic';
import { buildTelemetryRequest, type FlightPlanForm } from '../../shared/map/flight-plan-logic';
import type {
  DiscoveredDevice,
  NetworkAddress,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  ScanResult,
  TelemetryPlanRequest,
  VehicleProfile,
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
  buildVerifyRequest,
  canAdvanceFromConnect,
  canAdvanceFromProfile,
  canAdvanceFromTest,
  canAdvanceFromVerify,
  creatorOwnershipGroup,
  defaultPilotSelection,
  nextStep,
  pilotsInGroup,
  prevStep,
  type ConnectMethod,
  type WizardStep,
} from './onboarding-logic';
import type { UserSummary } from '../../core/api/models';

interface OptionRow {
  key: string;
  value: string;
}

/** Scan durations worth offering — mirrors the pre-wizard Devices page's own choice exactly. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/**
 * The MAVLink heartbeat scanner's well-known listen port (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — used only
 * as `mavlinkPort`'s initial value until `GET /api/system/network` resolves, so the "configure your
 * drone" sub-step never renders with an empty port while the request is in flight. Every real value
 * comes from the network response itself (`SystemNetworkResponse#mavlinkPort`), never assumed.
 */
const DEFAULT_MAVLINK_PORT = 14_550;

/** Shown under the Simulate mode selector — one sentence per mode, docs/main/CYCLES-PLAN.md §4's own wording. */
const SIMULATE_MODE_HINTS: Record<SimulateMode, string> = {
  direct: 'Plays the file straight through the pipeline — the simplest way to see it work.',
  rtsp: 'Rehearse the real protocol path: the platform transmits your file over RTSP and ingests it back like real hardware.',
  synthetic: 'No file needed — creates a still, pattern-only test asset with no telemetry.',
  testDrone: 'No file needed — places a moving drone on a circular flight path around a home point, watchable immediately.',
};

/**
 * The onboarding wizard's own "component store" (docs/plans/done/UX-REWORK-PLAN.md §U-d) — provided per-route
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
  private readonly auth = inject(AuthStore);

  readonly step = signal<WizardStep>('profile');

  // --- Step 1: Profile -----------------------------------------------------------------------

  readonly displayName = signal('');
  readonly registrationNumber = signal('');
  readonly category = signal('');
  readonly categoryOptions = signal<readonly CategoryOption[]>([]);

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
  //     verbatim from `features/devices/devices.ts` — docs/plans/done/UX-REWORK-PLAN.md §U-d) -------------

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

  // --- Connect: "Listen for drones" (docs/plans/active/DRONE-INFRA-PLAN.md I-b) — a MAVLink-heartbeat-only scan,
  //     distinct from the general `discover` method above; see `drone-scan-logic.ts`'s own doc
  //     comment for why this is a separate pure-logic module rather than folded into that one. ------

  readonly droneScanning = signal(false);
  readonly droneScanResult = signal<ScanResult | null>(null);

  /**
   * No `timeoutMs` is sent — the scanner self-time-boxes (docs/plans/active/DRONE-INFRA-PLAN.md I-b: "scans take
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
   * `suggestedCategory` (docs/plans/active/DRONE-INFRA-PLAN.md I-b — closes this wizard's own previously-documented
   * gap of never wiring a discovery suggestion into the Profile step's category picker, for this one
   * path). A no-op for an already-claimed vehicle (the UI never offers this action for one, but a
   * defensive check costs nothing — see `isClaimedVehicle`'s own doc comment for why one can exist
   * in the results list at all).
   *
   * **The one shared "Use" handler for both entry points that reach the `listen` method**
   * (docs/plans/active/DRONE-INFRA-PLAN.md I-g's own "listen is the scan" wording — the guided `drone` method
   * hands off to this exact method/UI verbatim once configured, see `finishDroneConfigAndListen`
   * below): protocol/uri/options come from `buildDroneDeviceSpec` (`drone-config-logic.ts`), pinned
   * to this platform's own authoritative `mavlinkPort` (from `GET /api/system/network`) rather than
   * trusting the scan candidate's own echoed `uri`/`address` — strictly more robust (a candidate with
   * no `uri` at all previously fell back to its raw `address`, which is not always a well-formed
   * `udp://` URI), and it's what "the pin that makes multi-drone-on-one-port work (I-a)" means in
   * practice. `suggestedCategory` still comes straight off the candidate — `drone-scan-logic.ts`'s own
   * `prefillFromVehicle` is unaffected by this change and stays fully in use/tested for its own direct
   * callers and coverage elsewhere in this module's test surface.
   */
  useDroneVehicle(candidate: DiscoveredDevice): void {
    if (isClaimedVehicle(candidate)) {
      return;
    }
    const spec = buildDroneDeviceSpec(candidate, this.mavlinkPort());
    const selection = protocolSelectionFor(spec.protocol);
    this.protocolSelect.set(selection.select);
    this.customProtocol.set(selection.custom);
    this.uri.set(spec.uri);
    this.options.set(spec.options ? Object.entries(spec.options).map(([key, value]) => ({ key, value })) : []);
    this.connectMethod.set('register');
    if (this.displayName().trim().length === 0) {
      this.displayName.set(candidate.name);
    }
    if (candidate.suggestedCategory && this.category().trim().length === 0) {
      this.chooseCategory(candidate.suggestedCategory);
    }
  }

  // --- Connect: "Add a real drone" (docs/plans/active/DRONE-INFRA-PLAN.md I-g, wave B) — the guided firmware×link
  //     picker + parameterized copy-paste config, both sub-states of this one Connect step
  //     (deliberately not added to `onboarding-logic.ts#WizardStep`, per the plan's own "minimize new
  //     wizard-state surface" instruction). Once configured, `finishDroneConfigAndListen` hands off to
  //     the existing `listen` method above verbatim — no second scanner, no second vehicle-list UI. ---

  /**
   * `'picker'` (firmware×link + compatibility verdict) → `'config'` (the parameterized snippets).
   * Not part of `WizardStep` — this is purely "where inside the Connect step's `drone` method are we",
   * the same relationship `connectMethod` itself already has to `WizardStep`.
   */
  readonly droneSubStep = signal<'picker' | 'config'>('picker');
  readonly droneFirmware = signal<Firmware | null>(null);
  readonly droneLink = signal<LinkType | null>(null);

  readonly networkAddresses = signal<readonly NetworkAddress[]>([]);
  readonly mavlinkPort = signal<number>(DEFAULT_MAVLINK_PORT);
  /** The address actually used to render snippets — pre-selected from `networkAddresses`, editable
   *  (manual-entry fallback) when that list came back empty. */
  readonly selectedServerAddress = signal<string>('');

  readonly droneCompatibility = computed<LinkCompatibility | null>(() => {
    const firmware = this.droneFirmware();
    const link = this.droneLink();
    return firmware && link ? linkCompatibility(firmware, link) : null;
  });

  /** Re-renders live off `selectedServerAddress` — editing the address selector recomputes every block. */
  readonly droneConfigBlocks = computed<readonly ConfigBlock[]>(() => {
    const firmware = this.droneFirmware();
    const link = this.droneLink();
    const address = this.selectedServerAddress().trim();
    if (!firmware || !link || address.length === 0) {
      return [];
    }
    return configSnippets(firmware, link, address, this.mavlinkPort());
  });

  chooseDroneMethod(): void {
    this.connectMethod.set('drone');
    this.droneSubStep.set('picker');
  }

  chooseDroneFirmware(firmware: Firmware): void {
    this.droneFirmware.set(firmware);
  }

  chooseDroneLink(link: LinkType): void {
    this.droneLink.set(link);
  }

  setSelectedServerAddress(address: string): void {
    this.selectedServerAddress.set(address);
  }

  /** Poka-yoke mirrors the picker's own disabled Continue button — a `no-go` combo can't advance here either. */
  continueToDroneConfig(): void {
    if (this.droneCompatibility()?.level === 'no-go') {
      return;
    }
    this.droneSubStep.set('config');
  }

  backFromDroneConfig(): void {
    this.droneSubStep.set('picker');
  }

  /**
   * The hand-off (docs/plans/active/DRONE-INFRA-PLAN.md I-g step 3, "listen is the scan"): switches straight to
   * the existing `listen` method and starts its scan, exactly as if the operator had picked that
   * tile directly from the method grid. Everything past this point — the vehicle list, claimed-vehicle
   * dimming, `useDroneVehicle`'s "Use" pivot to `register`, probe, create — is the pre-existing I-b
   * flow, entirely unmodified by this method.
   */
  async finishDroneConfigAndListen(): Promise<void> {
    this.connectMethod.set('listen');
    await this.scanForDrones();
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

  // --- Step 3.5: Verify (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 3/O6) — a pre-registration
  //     vehicle-link observation, distinct from Test's own video-frame probe above. Mirrors that
  //     step's `probing`/`lastProbeRequest`/`lastProbeResult`/`lastProbeError`/`probeStillCurrent`
  //     shape exactly (same "don't let a stale probe answer for edited fields" guard), plus one
  //     addition Test doesn't need: `verifyDisabled`, first-class-state for the common
  //     `vision.onboarding.probe.enabled=false` case (D17 default) — see `isProbeDisabledError`'s own
  //     doc comment for why the identical `409` also covers "candidate unreachable", and why only the
  //     message text (not the status) tells them apart. -----------------------------------------

  /** `null` for `discover`/`listen`/`drone` (no candidate chosen yet — pivots to `register` first) and `simulate` (this step is skipped for it, see `WizardStep`'s own doc comment). */
  private readonly currentVerifyRequest = computed<ProbeCandidateRequest | null>(() => {
    if (this.connectMethod() !== 'register') {
      return null;
    }
    return buildVerifyRequest({ protocol: this.protocol(), uri: this.uri(), options: this.collectOptions() });
  });

  readonly verifying = signal(false);
  private readonly lastVerifyRequest = signal<ProbeCandidateRequest | null>(null);
  readonly lastVerifyResult = signal<VehicleProfile | null>(null);
  readonly lastVerifyError = signal<string | null>(null);
  /** `true` once a {@link verify} attempt has confirmed `vision.onboarding.probe.enabled=false` on this deployment — read by `onboarding.html` to render the same honest "not enabled here" state `ModelsPage`/`DatasetsPage` use, never a generic error banner for the default, expected case. */
  readonly verifyDisabled = signal(false);

  /** Whether the last observed profile was for *these exact* connection fields, not a stale one — mirrors {@link probeStillCurrent}. */
  readonly verifyStillCurrent = computed(() => {
    const last = this.lastVerifyRequest();
    const current = this.currentVerifyRequest();
    return (
      last !== null &&
      current !== null &&
      last.protocol === current.protocol &&
      last.uri === current.uri &&
      JSON.stringify(last.options ?? {}) === JSON.stringify(current.options ?? {})
    );
  });

  readonly canAdvanceVerify = computed(() => canAdvanceFromVerify(this.connectMethod()));

  /**
   * Observes the candidate's own vehicle link (`POST /api/onboarding/probe`) — never persisted, no
   * asset exists yet (D7). Unlike {@link probe}, a failure here never blocks {@link next} (see
   * {@link canAdvanceVerify}/`canAdvanceFromVerify`'s own doc comment) — this is strictly informative.
   */
  async verify(): Promise<void> {
    const request = this.currentVerifyRequest();
    if (!request) {
      return;
    }
    this.verifying.set(true);
    this.lastVerifyError.set(null);
    this.verifyDisabled.set(false);
    try {
      const result = await this.api.probeVehicleCandidate(request);
      this.lastVerifyRequest.set(request);
      this.lastVerifyResult.set(result);
    } catch (error) {
      this.lastVerifyRequest.set(request);
      this.lastVerifyResult.set(null);
      if (isProbeDisabledError(error)) {
        this.verifyDisabled.set(true);
      } else {
        this.lastVerifyError.set(describeHttpError(error));
      }
    } finally {
      this.verifying.set(false);
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

  /** Register/Discover paths: one `POST /api/assets` call, the device embedded (docs/plans/done/UX-REWORK-PLAN.md §U-d item 2). */
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
   * Simulate path (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1): `synthetic` carries no video/telemetry at
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
    // Docs/plans/active/OPS-UX-PLAN.md §2 A3: the wizard's last step, not a redirect — "Who flies this?"
    // renders in place of navigating straight to /assets/:id, see `enterAssignStep` below.
    await this.enterAssignStep(assetId, displayName);
  }

  // --- Step 5: "Who flies this?" (docs/plans/active/OPS-UX-PLAN.md §2 A3) -------------------------------
  // Offered only *after* `POST /api/assets` has already succeeded (`finishCreate` above is this
  // section's one caller) — every signal below is therefore about assignment, never creation, and
  // `assignmentError` is read that way too (see its own doc comment and `confirmPilots`'s own
  // try/catch): a failure here can never be mistaken for "the asset wasn't created" because the
  // asset demonstrably already exists by the time any of this runs.

  readonly createdAssetId = signal<string | null>(null);
  readonly createdAssetDisplayName = signal('');

  /** The candidate list — every enabled PILOT-role member of the asset's own (silently-assigned) ownership group. Empty means "couldn't offer anyone", not "nobody exists" — see `ownerGroupName`'s own doc comment for how the template tells those two apart. */
  readonly pilotCandidates = signal<readonly UserSummary[]>([]);
  readonly pilotCandidatesLoading = signal(false);
  /** `undefined` only when the creator's own group could not be resolved at all (a membership-less account) — `onboarding.html` reads this to distinguish "nobody in your group flies yet" from "couldn't tell what your group even is", never fabricating either. */
  readonly ownerGroupName = signal<string | undefined>(undefined);
  readonly selectedPilotIds = signal<ReadonlySet<string>>(new Set());
  readonly assigningPilots = signal(false);
  /** Set only if `PUT /api/assets/{id}/pilots/{userId}` itself fails — see this section's own class-doc paragraph for why that can never read as a creation failure. `null` clears it (a fresh attempt, or leaving the step). */
  readonly assignmentError = signal<string | null>(null);

  togglePilot(userId: string): void {
    const next = new Set(this.selectedPilotIds());
    if (next.has(userId)) {
      next.delete(userId);
    } else {
      next.add(userId);
    }
    this.selectedPilotIds.set(next);
  }

  private async enterAssignStep(assetId: string, displayName: string): Promise<void> {
    this.createdAssetId.set(assetId);
    this.createdAssetDisplayName.set(displayName);
    this.step.set('assign');
    this.pilotCandidatesLoading.set(true);
    try {
      const creator = this.auth.user();
      const group = creatorOwnershipGroup(creator?.memberships ?? []);
      this.ownerGroupName.set(group?.groupName);
      const users = await this.api.listUsers();
      this.pilotCandidates.set(pilotsInGroup(users, group?.groupId));
      this.selectedPilotIds.set(new Set(creator ? defaultPilotSelection(creator.userId, group) : []));
    } catch {
      // Silent-degrade (this app's own background-check convention, e.g. `loadCategoryOptions`
      // below) — `GET /api/users` may 403 for a caller without org-management rights (a plain
      // PILOT self-registering, still reachable ahead of the backend's own wave-C gate); the
      // asset is already created and unaffected either way, so this only ever narrows the picker
      // to its own empty state, never blocks the page.
      this.pilotCandidates.set([]);
    } finally {
      this.pilotCandidatesLoading.set(false);
    }
  }

  /** The step's primary action. Assigns every selected pilot, then leaves the wizard — or, with nothing selected, just leaves it (same destination as `skipAssignment`). */
  async confirmPilots(): Promise<void> {
    const assetId = this.createdAssetId();
    if (!assetId || this.assigningPilots()) {
      return;
    }
    const userIds = [...this.selectedPilotIds()];
    if (userIds.length === 0) {
      this.leaveWizard();
      return;
    }
    this.assigningPilots.set(true);
    this.assignmentError.set(null);
    try {
      await Promise.all(userIds.map((userId) => this.api.assignPilot(assetId, userId)));
      this.toasts.ok(userIds.length === 1 ? 'Pilot assigned.' : `${userIds.length} pilots assigned.`);
      this.leaveWizard();
    } catch (error) {
      // The asset already exists (see this section's own class-doc paragraph) — this message is
      // rendered plainly on the step itself (`onboarding.html`), not folded into a generic toast,
      // precisely so it never reads as "the asset wasn't saved".
      this.assignmentError.set(describeHttpError(error));
    } finally {
      this.assigningPilots.set(false);
    }
  }

  /** The step's secondary action — leaves without assigning anyone; the roster can always do this later. */
  skipAssignment(): void {
    this.leaveWizard();
  }

  private leaveWizard(): void {
    const assetId = this.createdAssetId();
    if (assetId) {
      void this.router.navigate(['/assets', assetId]);
    }
  }

  // --- Step navigation (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1 — stepper, back-navable) -------------

  readonly canAdvance = computed(() => {
    switch (this.step()) {
      case 'profile':
        return this.canAdvanceProfile();
      case 'connect':
        return this.canAdvanceConnect();
      case 'test':
        return this.canAdvanceTest();
      case 'verify':
        return this.canAdvanceVerify();
      case 'create':
        return false; // the Create step has its own "Create asset" action, not a "Next"
      case 'assign':
        return false; // the Assign step has its own "Assign & finish"/"Skip for now" actions, not a "Next"
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
    void this.loadSystemNetwork();
    inject(DestroyRef).onDestroy(() => this.revokePreview());
  }

  private async loadCategoryOptions(): Promise<void> {
    try {
      const [assets, categories] = await Promise.all([this.api.listAssets(), this.api.listCategories()]);
      this.categoryOptions.set(deriveCategoryOptions(assets, categories));
    } catch {
      // Silent-degrade — `categoryOptions` just stays whatever it already was (empty on first load).
    }
  }

  /**
   * Fetched once, up front, so the "configure your drone" sub-step's snippets are ready to render
   * the moment the operator gets there (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — not fetched lazily on first
   * pick, which would show a blank/loading config panel on an otherwise-instant step transition.
   * Silent-degrade on failure exactly like `loadCategoryOptions` above: `mavlinkPort` keeps its
   * `DEFAULT_MAVLINK_PORT` fallback and `networkAddresses` stays `[]`, which is the same UI state
   * `SystemNetworkResponse#addresses` being genuinely empty already has to handle (the manual-address
   * input) — no separate error state needed.
   */
  private async loadSystemNetwork(): Promise<void> {
    try {
      const network = await this.api.systemNetwork();
      this.networkAddresses.set(network.addresses);
      this.mavlinkPort.set(network.mavlinkPort);
      if (network.addresses.length > 0) {
        this.selectedServerAddress.set(network.addresses[0].address);
      }
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }
}
