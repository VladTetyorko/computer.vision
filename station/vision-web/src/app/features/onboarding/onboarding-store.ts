import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { AuthStore } from '../../core/auth/auth-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  buildTestDroneRequest,
  type SimulateMode,
} from '../../core/fleet/simulation-logic';
import { isProbeDisabledError } from '../../core/readiness/readiness-logic';
import { buildMavlinkScanRequest, isClaimedVehicle } from './drone-scan-logic';
import { detectSysidCollision, sysidParameterName } from './sysid-collision-logic';
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
  Category,
  CreateAssetRequest,
  DiscoveredDevice,
  NetworkAddress,
  ParameterWriteRequest,
  ParameterWriteResponse,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  ScanResult,
  TelemetryPlanRequest,
  UserSummary,
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
  FIT_OUT_ROLE_HINTS,
  FIT_OUT_ROLE_LABELS,
  canAdvanceFromFitOut,
  canAdvanceFromFitOutProve,
  collectRowOptions,
  combinedSysidCollision,
  effectiveProtocol,
  emptyFitOutRows,
  needsProve,
  roleForDevice,
  usesLegacySimulationPath,
  type FitOutFindMethod,
  type FitOutRole,
  type FitOutRowValue,
  type FitOutRows,
} from '../../core/onboarding/fit-out-logic';
import {
  buildCreateAssetRequest,
  buildIdentityRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  buildVerifyRequest,
  canAdvanceFromIdentify,
  creatorOwnershipGroup,
  defaultPilotSelection,
  isTelemetryOnlyProtocol,
  nextStep,
  pilotsInGroup,
  prevStep,
  type IdentifyDraft,
  type StepContext,
  type WizardStep,
} from './onboarding-logic';

/** Scan durations worth offering — mirrors the pre-wizard Devices page's own choice exactly. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/**
 * The MAVLink heartbeat scanner's well-known listen port (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — used only
 * as `mavlinkPort`'s initial value until `GET /api/system/network` resolves, so the "configure your
 * drone" sub-step never renders with an empty port while the request is in flight. Every real value
 * comes from the network response itself (`SystemNetworkResponse#mavlinkPort`), never assumed.
 */
const DEFAULT_MAVLINK_PORT = 14_550;

/** Shown under the legacy Simulate mode selector — one sentence per mode, docs/main/CYCLES-PLAN.md §4's own wording. */
const SIMULATE_MODE_HINTS: Record<SimulateMode, string> = {
  direct: 'Plays the file straight through the pipeline — the simplest way to see it work.',
  rtsp: 'Rehearse the real protocol path: the platform transmits your file over RTSP and ingests it back like real hardware.',
  synthetic: 'No file needed — creates a still, pattern-only test asset with no telemetry.',
  testDrone: 'No file needed — places a moving drone on a circular flight path around a home point, watchable immediately.',
};

/** One fit-out row's Prove state — Test + Verify results, tracked independently per role (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6: "results shown per row"). */
interface RowProveState {
  readonly probing: boolean;
  readonly lastProbeRequest: ProbeDeviceRequest | null;
  readonly lastProbeResult: ProbeDeviceResult | null;
  readonly lastProbeError: string | null;
  readonly verifying: boolean;
  readonly lastVerifyRequest: ProbeCandidateRequest | null;
  readonly lastVerifyResult: VehicleProfile | null;
  readonly lastVerifyError: string | null;
  readonly verifyDisabled: boolean;
  /** See `fit-out-logic.ts#combinedSysidCollision`'s own doc comment — only a `mavlink` (Sense) row realistically ever sets this, but every row carries the field so the aggregate stays a total function. */
  readonly sysidCollision: number | null;
  readonly sysidParameterToWrite: 'MAV_SYSID' | 'SYSID_THISMAV' | null;
}

function emptyRowProveState(): RowProveState {
  return {
    probing: false,
    lastProbeRequest: null,
    lastProbeResult: null,
    lastProbeError: null,
    verifying: false,
    lastVerifyRequest: null,
    lastVerifyResult: null,
    lastVerifyError: null,
    verifyDisabled: false,
    sysidCollision: null,
    sysidParameterToWrite: null,
  };
}

/**
 * The onboarding wizard's own "component store" (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave
 * W6, replacing the pre-W6 single-device wizard). Provided per-route on `OnboardingPage`
 * (`providers: [OnboardingStore]`, same DI-sharing idiom as `AssetDetailPage`'s
 * `TelemetryStore`/`DetectionsStore`), not `providedIn: 'root'`: wizard state has no reason to
 * survive leaving `/add-source`, and a fresh instance per visit means a second pass through the
 * wizard never starts warm with a previous attempt's half-filled form.
 *
 * Holds every signal across all six steps (five visible, `sysid` hidden — see
 * `onboarding-logic.ts#WizardStep`) and orchestrates the actual HTTP calls; every yes/no decision
 * (can this step advance?) and every request shape is delegated to the pure functions in
 * `onboarding-logic.ts`/`core/onboarding/fit-out-logic.ts` — this class is deliberately thin glue,
 * not where the interesting logic lives.
 *
 * **The fit-out table's two rows share one finder each**: `scan`/`useCandidate` (the `register`/
 * `discover` finders) always act on the **Sight** row; `scanForDrones`/`useDroneVehicle`/the guided
 * drone-config sub-flow (`listen`/`drone`) always act on the **Sense** row. A finder never needs to
 * know which row it is feeding — `FIT_OUT_FIND_METHODS` in `fit-out-logic.ts` fixes the mapping —
 * so every one of those methods keeps its pre-W6 body, only now writing into that row's own slice of
 * `rows` instead of the old flat `protocolSelect`/`customProtocol`/`uri`/`options` signals.
 */
@Injectable()
export class OnboardingStore {
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthStore);

  readonly step = signal<WizardStep>('identify');

  // --- Step 1: Identify (docs/plans/active/WAREHOUSE-UX-PLAN.md D1 — name/category/photo, plus serial/make/model/registration) --

  readonly displayName = signal('');
  readonly registrationNumber = signal('');
  readonly serialNumber = signal('');
  readonly make = signal('');
  readonly model = signal('');
  readonly category = signal('');
  /** The picker's own `{slug,name}` options — see `loadCategoryOptions` below. */
  readonly categoryOptions = signal<readonly { slug: string; name: string }[]>([]);
  /** The raw category list (`GET /api/categories`), kept alongside `categoryOptions` only so {@link categoryConnected} can read `Category#connected` — a fact the derived picker options don't carry. */
  private readonly categories = signal<readonly Category[]>([]);

  readonly photoFile = signal<File | null>(null);
  readonly photoBlob = signal<Blob | null>(null);
  readonly photoPreviewUrl = signal<string | null>(null);
  readonly photoProcessing = signal(false);
  readonly photoError = signal<string | null>(null);

  readonly canAdvanceIdentify = computed(() => canAdvanceFromIdentify(this.displayName(), this.category()));

  /**
   * Whether the chosen category wraps at least one device (`Category#connected`,
   * docs/plans/active/WAREHOUSE-UX-PLAN.md D4). Defaults `true` (the historical assumption every
   * asset needs a device) when the category isn't in `categories()` yet — a fresh page load before
   * `loadCategoryOptions` resolves, or a category typed in ahead of the seed list — never blocking or
   * silently offering the equipment short-circuit for a category this app hasn't actually confirmed.
   * Drives the whole Identify→Register short-circuit ({@link WizardStep}'s own `identify` doc
   * comment) and the Hand-over end screen's own next-verb link.
   */
  readonly categoryConnected = computed(() => this.categories().find((c) => c.slug === this.category())?.connected ?? true);

  private identifyDraft(): IdentifyDraft {
    return {
      displayName: this.displayName(),
      category: this.category(),
      registrationNumber: this.registrationNumber(),
      serialNumber: this.serialNumber(),
      make: this.make(),
      model: this.model(),
    };
  }

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

  // --- Step 2: Connect — the fit-out table (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §6,
  //     docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6). One row per role; see this class's own
  //     doc comment for why each finder is fixed to exactly one row. ------------------------------

  readonly rows = signal<FitOutRows>(emptyFitOutRows());
  readonly fitOutRoleLabels = FIT_OUT_ROLE_LABELS;
  readonly fitOutRoleHints = FIT_OUT_ROLE_HINTS;
  readonly registerableProtocols = REGISTERABLE_PROTOCOLS;
  readonly customProtocolOption = CUSTOM_PROTOCOL_OPTION;

  readonly canAdvanceConnect = computed(() => canAdvanceFromFitOut(this.rows()));

  /** The row's top-level choice (`find… · simulate · —`) — switching resets that row's own draft (finder, protocol, uri, options) entirely, so a stale half-entered connection from a previously-chosen value can never leak into the new one. */
  setRowValue(role: FitOutRole, value: FitOutRowValue): void {
    this.rows.update((r) => ({ ...r, [role]: { ...emptyFitOutRowLike(r[role]), value } }));
  }

  /** Picks one of that row's own finders (`FIT_OUT_FIND_METHODS`) — `drone` also resets the guided config sub-step to its start. */
  chooseRowFind(role: FitOutRole, method: FitOutFindMethod): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], value: 'find', findMethod: method } }));
    if (method === 'drone') {
      this.droneSubStep.set('picker');
    }
  }

  /** Back from a row's resolved/scanning state to its own finder-tile choice — mirrors the pre-W6 `onConnectBack`'s unconditional reset. */
  backFromRowFind(role: FitOutRole): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], findMethod: null } }));
  }

  setRowProtocolSelect(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], protocolSelect: value } }));
  }

  setRowCustomProtocol(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], customProtocol: value } }));
  }

  setRowUri(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], uri: value } }));
  }

  addRowOption(role: FitOutRole): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], options: [...r[role].options, { key: '', value: '' }] } }));
  }

  removeRowOption(role: FitOutRole, index: number): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], options: r[role].options.filter((_, i) => i !== index) } }));
  }

  updateRowOptionKey(role: FitOutRole, index: number, key: string): void {
    this.rows.update((r) => ({
      ...r,
      [role]: { ...r[role], options: r[role].options.map((o, i) => (i === index ? { ...o, key } : o)) },
    }));
  }

  updateRowOptionValue(role: FitOutRole, index: number, value: string): void {
    this.rows.update((r) => ({
      ...r,
      [role]: { ...r[role], options: r[role].options.map((o, i) => (i === index ? { ...o, value } : o)) },
    }));
  }

  rowProtocol(role: FitOutRole): string {
    return effectiveProtocol(this.rows()[role]);
  }

  rowUriPlaceholder(role: FitOutRole): string {
    return placeholderForProtocol(this.rowProtocol(role));
  }

  rowIsCustomProtocol(role: FitOutRole): boolean {
    return this.rows()[role].protocolSelect === CUSTOM_PROTOCOL_OPTION;
  }

  /** Fills a row's register fields from a resolved connection and switches it to the standard register sub-view — the "candidate → register" pivot every finder below uses. */
  private applyResolvedConnection(
    role: FitOutRole,
    resolved: { protocol: string | undefined; uri: string; options?: Record<string, string> },
  ): void {
    const selection = protocolSelectionFor(resolved.protocol);
    this.rows.update((r) => ({
      ...r,
      [role]: {
        ...r[role],
        value: 'find',
        findMethod: 'register',
        protocolSelect: selection.select,
        customProtocol: selection.custom,
        uri: resolved.uri,
        options: resolved.options ? Object.entries(resolved.options).map(([key, value]) => ({ key, value })) : [],
      },
    }));
  }

  // --- Connect: discovery (Sight row — `register`/`discover`) -------------------------------------

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

  /** Fills the Sight row's register fields from a discovery candidate. */
  useCandidate(candidate: DiscoveredDevice): void {
    this.applyResolvedConnection('sight', { protocol: candidate.protocol, uri: candidate.uri ?? candidate.address });
    if (this.displayName().trim().length === 0) {
      this.displayName.set(candidate.name);
    }
    if (!candidate.uri) {
      this.toasts.info(`${candidate.method} could not supply a stream URI — check the address before registering.`);
    }
  }

  // --- Connect: "Listen for drones" (docs/plans/active/DRONE-INFRA-PLAN.md I-b) — Sense row — a MAVLink-heartbeat-only
  //     scan, distinct from the general `discover` method above; see `drone-scan-logic.ts`'s own doc
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
   * Fills the Sense row's register fields from an unclaimed heard vehicle — the same
   * "candidate → register" pivot `useCandidate` uses, plus `suggestedCategory`
   * (docs/plans/active/DRONE-INFRA-PLAN.md I-b — wiring a discovery suggestion into the Identify step's category
   * picker). A no-op for an already-claimed vehicle (the UI never offers this action for one, but a
   * defensive check costs nothing — see `isClaimedVehicle`'s own doc comment for why one can exist in
   * the results list at all).
   *
   * Protocol/uri/options come from `buildDroneDeviceSpec` (`drone-config-logic.ts`), pinned to this
   * platform's own authoritative `mavlinkPort` (from `GET /api/system/network`) rather than trusting
   * the scan candidate's own echoed `uri`/`address` — strictly more robust, and it's what "the pin
   * that makes multi-drone-on-one-port work (I-a)" means in practice.
   */
  useDroneVehicle(candidate: DiscoveredDevice): void {
    if (isClaimedVehicle(candidate)) {
      return;
    }
    const spec = buildDroneDeviceSpec(candidate, this.mavlinkPort());
    this.applyResolvedConnection('sense', { protocol: spec.protocol, uri: spec.uri, options: spec.options });
    if (this.displayName().trim().length === 0) {
      this.displayName.set(candidate.name);
    }
    if (candidate.suggestedCategory && this.category().trim().length === 0) {
      this.chooseCategory(candidate.suggestedCategory);
    }
  }

  // --- Connect: "Add a real drone" (docs/plans/active/DRONE-INFRA-PLAN.md I-g, wave B) — Sense row — the guided
  //     firmware×link picker + parameterized copy-paste config. Once configured,
  //     `finishDroneConfigAndListen` hands off to the existing `listen` finder above verbatim — no
  //     second scanner, no second vehicle-list UI. ------------------------------------------------

  /** `'picker'` (firmware×link + compatibility verdict) → `'config'` (the parameterized snippets). Not part of `WizardStep` — purely "where inside the Sense row's `drone` finder are we". */
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
   * The hand-off (docs/plans/active/DRONE-INFRA-PLAN.md I-g step 3, "listen is the scan"): switches the Sense row
   * straight to the existing `listen` finder and starts its scan, exactly as if the operator had
   * picked that tile directly. Everything past this point — the vehicle list, claimed-vehicle
   * dimming, `useDroneVehicle`'s "Use" pivot, Prove, Register — is the pre-existing I-b flow,
   * entirely unmodified by this method.
   */
  async finishDroneConfigAndListen(): Promise<void> {
    this.chooseRowFind('sense', 'listen');
    await this.scanForDrones();
  }

  // --- Connect: the legacy whole-vehicle Simulate path (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §7/§9 —
  //     `fit-out-logic.ts#usesLegacySimulationPath`) — reached only when the Sight row is `simulate`
  //     and the Sense row is not a real `find` link. Unchanged in substance from the pre-W6 wizard's
  //     own Simulate step: the rich mode picker (direct/rtsp/synthetic/testDrone) is not expressible
  //     per fit-out row, so it stays a single, wizard-wide sub-form rendered under the Sight row. --

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

  // --- Step 3: Prove — Test + Verify, per filled `find` row (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6,
  //     merging the pre-W6 wizard's separate Test/Verify steps into one, run once per row) ----------

  readonly proveByRole = signal<Record<FitOutRole, RowProveState>>({ sense: emptyRowProveState(), sight: emptyRowProveState() });

  readonly rowsNeedProve = computed(() => needsProve(this.rows()));

  private currentProbeRequest(role: FitOutRole): ProbeDeviceRequest | null {
    const row = this.rows()[role];
    if (row.value !== 'find') {
      return null;
    }
    return buildProbeRequest({ protocol: effectiveProtocol(row), uri: row.uri, options: collectRowOptions(row) });
  }

  private currentVerifyRequest(role: FitOutRole): ProbeCandidateRequest | null {
    const row = this.rows()[role];
    if (row.value !== 'find') {
      return null;
    }
    return buildVerifyRequest({ protocol: effectiveProtocol(row), uri: row.uri, options: collectRowOptions(row) });
  }

  private sameConnection(
    a: { protocol: string; uri: string; options?: Record<string, string> } | null,
    b: { protocol: string; uri: string; options?: Record<string, string> } | null,
  ): boolean {
    return (
      a !== null &&
      b !== null &&
      a.protocol === b.protocol &&
      a.uri === b.uri &&
      JSON.stringify(a.options ?? {}) === JSON.stringify(b.options ?? {})
    );
  }

  /** Whether the last successful probe for this row was for *these exact* connection fields, not a stale one — `undefined` before any probe has run against the current fields. */
  lastProbeOk(role: FitOutRole): boolean | undefined {
    const state = this.proveByRole()[role];
    return this.sameConnection(state.lastProbeRequest, this.currentProbeRequest(role))
      ? state.lastProbeResult?.ok === true
      : undefined;
  }

  /**
   * Whether the row's chosen connection carries telemetry and no video
   * (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B3) — read by `onboarding.html` to word that row's
   * Prove card for the device in front of the operator instead of demanding a frame that, on a
   * flight-controller link, cannot exist. In practice only the Sense row is ever telemetry-only, but
   * this is computed the same way for both — no reason to special-case a role here when the protocol
   * string already carries the answer.
   */
  telemetryOnlyLink(role: FitOutRole): boolean {
    return isTelemetryOnlyProtocol(this.rowProtocol(role));
  }

  readonly canAdvanceProve = computed(() =>
    canAdvanceFromFitOutProve(this.rows(), { sense: this.lastProbeOk('sense'), sight: this.lastProbeOk('sight') }),
  );

  private updateProve(role: FitOutRole, patch: Partial<RowProveState>): void {
    this.proveByRole.update((p) => ({ ...p, [role]: { ...p[role], ...patch } }));
  }

  async testRow(role: FitOutRole): Promise<void> {
    const request = this.currentProbeRequest(role);
    if (!request) {
      return;
    }
    this.updateProve(role, { probing: true, lastProbeError: null });
    try {
      const result = await this.api.probeDevice(request);
      this.updateProve(role, {
        lastProbeRequest: request,
        lastProbeResult: result,
        lastProbeError: result.ok ? null : 'The device did not report success — check the warnings below.',
      });
    } catch (error) {
      this.updateProve(role, { lastProbeRequest: request, lastProbeResult: null, lastProbeError: describeHttpError(error) });
    } finally {
      this.updateProve(role, { probing: false });
    }
  }

  /**
   * Observes the row's candidate vehicle link (`POST /api/onboarding/probe`) — never persisted, no
   * asset exists yet (D7). A failure here never blocks {@link canAdvanceProve} — this is strictly
   * informative, mirroring the pre-W6 Verify step's own unconditional advance rule.
   */
  async verifyRow(role: FitOutRole): Promise<void> {
    const request = this.currentVerifyRequest(role);
    if (!request) {
      return;
    }
    this.updateProve(role, {
      verifying: true,
      lastVerifyError: null,
      verifyDisabled: false,
      sysidCollision: null,
      sysidParameterToWrite: null,
    });
    try {
      const result = await this.api.probeVehicleCandidate(request);
      this.updateProve(role, { lastVerifyRequest: request, lastVerifyResult: result });
      await this.detectSysidCollisionQuietly(role, result);
    } catch (error) {
      this.updateProve(role, { lastVerifyRequest: request, lastVerifyResult: null });
      if (isProbeDisabledError(error)) {
        this.updateProve(role, { verifyDisabled: true });
      } else {
        this.updateProve(role, { lastVerifyError: describeHttpError(error) });
      }
    } finally {
      this.updateProve(role, { verifying: false });
    }
  }

  /**
   * The device-list read feeding a row's `sysidCollision` (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0) —
   * silent-degrade: a failed fetch (a caller without fleet-wide visibility, a network blip) must
   * never throw out of {@link verifyRow} or block/mislead the wizard. It simply leaves the row's
   * `sysidCollision` at `null` — an honest "no collision detected", not a fabricated one.
   */
  private async detectSysidCollisionQuietly(role: FitOutRole, profile: VehicleProfile): Promise<void> {
    try {
      const devices = await this.api.listDevices();
      const collision = detectSysidCollision(profile, devices);
      this.updateProve(role, {
        sysidCollision: collision,
        sysidParameterToWrite: collision !== null ? sysidParameterName(profile) : null,
      });
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  // --- Step 4: Register (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6 — unchanged in substance from the
  //     pre-W6 Create step, now with `identity` and N devices instead of one) -----------------------

  readonly creating = signal(false);

  async createAsset(): Promise<void> {
    if (this.creating()) {
      return;
    }
    this.creating.set(true);
    try {
      if (usesLegacySimulationPath(this.rows())) {
        await this.createViaSimulation();
      } else {
        await this.createViaConnection();
      }
    } finally {
      this.creating.set(false);
    }
  }

  /** The multi-device fit-out path: one `POST /api/assets` call, every filled row's device embedded (closes coupling C2). */
  private async createViaConnection(): Promise<void> {
    const request = buildCreateAssetRequest(this.identifyDraft(), this.rows());
    try {
      const created = await this.api.createAsset(request);
      await this.finishCreate(created.assetId, created.displayName);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  /**
   * The equipment short-circuit's own action (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 — "Register happens
   * with zero devices"). Called directly from the Identify step's own "Receive" button for a
   * `connected: false` category — `connect`/`prove`/`register` are never rendered as steps at all
   * (see `onboarding-logic.ts#WizardStep`'s own `identify` doc comment); `devices` on the resulting
   * request is simply omitted (`fitOutDeviceSpecs` of empty rows is `[]`), which `AssetSpec#toSpec`
   * treats identically to an explicit empty list.
   */
  async receiveEquipmentAsset(): Promise<void> {
    if (this.creating() || !this.canAdvanceIdentify()) {
      return;
    }
    this.creating.set(true);
    try {
      const request = buildCreateAssetRequest(this.identifyDraft(), emptyFitOutRows());
      const created = await this.api.createAsset(request);
      await this.finishCreate(created.assetId, created.displayName);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.creating.set(false);
    }
  }

  /**
   * The legacy whole-vehicle Simulate path (`usesLegacySimulationPath`): `synthetic` carries no
   * video/telemetry at all — it is, underneath, a fixed `sim`/`sim://demo` connection, so it goes
   * through the exact same `POST /api/assets` call as the multi-device path (closing the same
   * orphaned-device dead end for it too). `direct`/`rtsp`/`testDrone` genuinely need the simulation
   * service to wire up a synthetic pipeline, so those three route through `POST /api/simulations` "as
   * today", then a follow-up `PATCH` applies name/identity (the simulation endpoint has no
   * `identity`/custom-`displayName`-at-create-time-honoring-photo shape of its own).
   */
  private async createViaSimulation(): Promise<void> {
    try {
      if (this.simMode() === 'synthetic') {
        // Not an orphaned raw-device registration (unlike the pre-wizard "Add the simulated
        // source" quick-add still used by features/devices/devices.ts's own empty state) — wrapped
        // into a real asset immediately, exactly like the multi-device path.
        const synthetic = buildSyntheticRegisterRequest(this.displayName());
        const identity = buildIdentityRequest(this.identifyDraft());
        const request: CreateAssetRequest = {
          displayName: this.displayName().trim(),
          category: this.category().trim(),
          ...(identity ? { identity } : {}),
          devices: [{ name: this.displayName().trim(), protocol: synthetic.protocol, uri: synthetic.uri }],
        };
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

      const edit = buildPostSimulationAssetEdit(this.identifyDraft());
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
    // FLEET-RADIO-PLAN.md R5/F0: a row whose Prove-step profile collided with an already-claimed
    // sysid detours through the sysid step first — every other path (no collision detected, the
    // equipment short-circuit, or the legacy Simulate path, neither of which ever runs Prove) goes
    // straight to Hand-over, byte-identical to this wizard's behavior before this step existed.
    const collision = combinedSysidCollision({
      sense: this.proveByRole().sense.sysidCollision,
      sight: this.proveByRole().sight.sysidCollision,
    });
    if (collision !== null) {
      await this.enterSysidStep(assetId, displayName);
    } else {
      await this.enterHandoverStep(assetId, displayName);
    }
  }

  // --- Step 4.5: fix a sysid collision (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0) ----------------
  // Entered only from `finishCreate` above, only when a Prove-step row's profile collided with an
  // already-claimed sysid. Like Hand-over below, this step is offered only *after* `POST /api/assets`
  // has already succeeded — a failed write here can never be mistaken for "the asset wasn't
  // created", the asset demonstrably already exists by the time any of this runs. Advisory, never a
  // hard block: `continueFromSysidStep` always proceeds into Hand-over regardless of whether a write
  // was even attempted, let alone whether it succeeded.

  readonly writingSysid = signal(false);
  readonly sysidWriteResult = signal<ParameterWriteResponse | null>(null);
  readonly sysidWriteError = signal<string | null>(null);
  /** The operator's chosen replacement sysid — left for them to type; this wizard does not attempt to suggest a "free" sysid (out of scope for this wave). */
  readonly sysidValue = signal<number | null>(null);

  private async enterSysidStep(assetId: string, displayName: string): Promise<void> {
    this.createdAssetId.set(assetId);
    this.createdAssetDisplayName.set(displayName);
    this.step.set('sysid');
  }

  /**
   * Writes the operator's chosen sysid to the newly-created asset's own device, under whichever
   * spelling was resolved (F0) — the Sense row's own, since only a `mavlink` link ever collides.
   * `consent: true` is sent only because this call itself is the operator's explicit click on the
   * step's own "Write sysid" button — never defaulted, never invoked automatically. No-op with
   * nothing to write or while already writing.
   */
  async writeSysid(): Promise<void> {
    const assetId = this.createdAssetId();
    const parameterName = this.proveByRole().sense.sysidParameterToWrite ?? this.proveByRole().sight.sysidParameterToWrite;
    const value = this.sysidValue();
    if (!assetId || parameterName === null || value === null || this.writingSysid()) {
      return;
    }
    this.writingSysid.set(true);
    this.sysidWriteError.set(null);
    try {
      const request: ParameterWriteRequest = { name: parameterName, value, consent: true };
      const result = await this.api.writeAssetParameter(assetId, request);
      this.sysidWriteResult.set(result);
    } catch (error) {
      this.sysidWriteResult.set(null);
      this.sysidWriteError.set(describeHttpError(error));
    } finally {
      this.writingSysid.set(false);
    }
  }

  /** Proceeds into Hand-over — the step's "Continue" action. Also effectively "skip": a collision is informative, never a hard block, so this never checks whether a write was attempted or succeeded. */
  continueFromSysidStep(): void {
    const assetId = this.createdAssetId();
    if (!assetId) {
      return;
    }
    void this.enterHandoverStep(assetId, this.createdAssetDisplayName());
  }

  // --- Step 5: Hand over (docs/plans/done/OPS-UX-PLAN.md §2 A3; docs/plans/active/WAREHOUSE-UX-PLAN.md
  //     §3.4 D3, wave W6 — replaces the pre-W6 "Pilots"/Assign step). Offered only *after*
  //     `POST /api/assets` has already succeeded — every signal below is therefore about handing the
  //     asset off, never creation, and `handoverError` is read that way too: a failure here can never
  //     be mistaken for "the asset wasn't created" because the asset demonstrably already exists by
  //     the time any of this runs. ------------------------------------------------------------------

  readonly createdAssetId = signal<string | null>(null);
  readonly createdAssetDisplayName = signal('');

  /** The custodian candidate list — every enabled PILOT-role member of the asset's own (silently-assigned) ownership group. Empty means "couldn't offer anyone", not "nobody exists" — see `ownerGroupName`'s own doc comment for how the template tells those two apart. */
  readonly pilotCandidates = signal<readonly UserSummary[]>([]);
  readonly pilotCandidatesLoading = signal(false);
  /** `undefined` only when the creator's own group could not be resolved at all (a membership-less account) — `onboarding.html` reads this to distinguish "nobody in your group flies yet" from "couldn't tell what your group even is", never fabricating either. */
  readonly ownerGroupName = signal<string | undefined>(undefined);
  /** Single-select — "Issue to" hands the asset to exactly one custodian (D3), unlike the pre-W6 Assign step's multi-pilot checklist. */
  readonly selectedCustodianId = signal<string | null>(null);
  readonly handoverLocation = signal('');
  readonly handingOver = signal(false);
  /** Set only if `setAssetCustody`/`assignPilot` itself fails — see this section's own class-doc paragraph for why that can never read as a creation failure. */
  readonly handoverError = signal<string | null>(null);
  /**
   * The step's own completed sub-state (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 — "the end screen must
   * name the next verb"), rendered in place of an automatic router redirect: `'issued'`/`'stocked'`
   * once the operator has picked one of the step's two actions, `null` while the step itself is still
   * showing the custodian picker.
   */
  readonly handoverOutcome = signal<'issued' | 'stocked' | null>(null);

  /** The end screen's one link — readiness for a connected (device-bearing) vehicle, inventory for equipment (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4). */
  readonly handoverNext = computed(() => {
    const assetId = this.createdAssetId();
    return this.categoryConnected() && assetId
      ? { label: 'Open readiness ›', path: ['/assets', assetId, 'readiness'] as const, queryParams: undefined }
      : { label: 'Back to inventory', path: ['/assets'] as const, queryParams: { tab: 'equipment' } };
  });

  selectCustodian(userId: string | null): void {
    this.selectedCustodianId.set(userId);
  }

  setHandoverLocation(location: string): void {
    this.handoverLocation.set(location);
  }

  private async enterHandoverStep(assetId: string, displayName: string): Promise<void> {
    this.createdAssetId.set(assetId);
    this.createdAssetDisplayName.set(displayName);
    this.handoverOutcome.set(null);
    this.handoverError.set(null);
    this.step.set('handover');
    this.pilotCandidatesLoading.set(true);
    try {
      const creator = this.auth.user();
      const group = creatorOwnershipGroup(creator?.memberships ?? []);
      this.ownerGroupName.set(group?.groupName);
      const users = await this.api.listUsers();
      this.pilotCandidates.set(pilotsInGroup(users, group?.groupId));
      this.selectedCustodianId.set(creator ? defaultPilotSelection(creator.userId, group)[0] ?? null : null);
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

  /**
   * The step's primary action — hands the asset to the selected custodian. `setAssetCustody`'s own
   * `ISSUE` action does not create a pilot assignment (verified by reading
   * `DefaultAssetCustodyService#issue`, which only touches `Custody`/`InventoryState`), so this calls
   * `assignPilot` immediately after, exactly mirroring the pre-W6 Assign step's own effect. No-op
   * with nobody selected — the picker's own "Issue to" button stays disabled for that case.
   */
  async issueToCustodian(): Promise<void> {
    const assetId = this.createdAssetId();
    const custodianId = this.selectedCustodianId();
    if (!assetId || !custodianId || this.handingOver()) {
      return;
    }
    this.handingOver.set(true);
    this.handoverError.set(null);
    try {
      const location = this.handoverLocation().trim();
      await this.api.setAssetCustody(assetId, { action: 'ISSUE', custodianId, ...(location.length > 0 ? { location } : {}) });
      await this.api.assignPilot(assetId, custodianId);
      await this.fleet.refresh({ quiet: true });
      this.handoverOutcome.set('issued');
    } catch (error) {
      // The asset already exists (see this section's own class-doc paragraph) — this message is
      // rendered plainly on the step itself (`onboarding.html`), not folded into a generic toast,
      // precisely so it never reads as "the asset wasn't saved".
      this.handoverError.set(describeHttpError(error));
    } finally {
      this.handingOver.set(false);
    }
  }

  /** The step's secondary action — leaves the asset in stock; the roster can always issue it later. */
  leaveInStock(): void {
    this.handoverOutcome.set('stocked');
  }

  // --- Step navigation (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1 — stepper, back-navable) -------------

  private readonly stepContext = computed<StepContext>(() => ({
    connected: this.categoryConnected(),
    needsProve: this.rowsNeedProve(),
  }));

  readonly canAdvance = computed(() => {
    switch (this.step()) {
      case 'identify':
        return this.canAdvanceIdentify();
      case 'connect':
        return this.canAdvanceConnect();
      case 'prove':
        return this.canAdvanceProve();
      case 'register':
        return false; // the Register step has its own "Create asset"/"Receive" action, not a "Next"
      case 'sysid':
        return false; // the sysid step has its own "Write sysid"/"Continue" actions, not a "Next"
      case 'handover':
        return false; // the Hand-over step has its own "Issue to"/"Leave in stock" actions, not a "Next"
    }
  });

  next(): void {
    if (!this.canAdvance()) {
      return;
    }
    this.step.set(nextStep(this.step(), this.stepContext()));
  }

  back(): void {
    this.step.set(prevStep(this.step(), this.stepContext()));
  }

  constructor() {
    void this.loadCategoryOptions();
    void this.loadSystemNetwork();
    void this.applyDevicePrefill();
    inject(DestroyRef).onDestroy(() => this.revokePreview());
  }

  private async loadCategoryOptions(): Promise<void> {
    try {
      const [assets, categories] = await Promise.all([this.api.listAssets(), this.api.listCategories()]);
      this.categories.set(categories);
      const bySlug = new Map<string, { slug: string; name: string }>();
      for (const c of categories) {
        bySlug.set(c.slug, { slug: c.slug, name: c.name });
      }
      for (const asset of assets) {
        if (!bySlug.has(asset.category)) {
          bySlug.set(asset.category, { slug: asset.category, name: asset.categoryName });
        }
      }
      this.categoryOptions.set([...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name)));
    } catch {
      // Silent-degrade — `categoryOptions`/`categories` just stay whatever they already were (empty on first load).
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

  /**
   * The wizard's own entry-point prefill contract (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W6
   * handoff"): a `?deviceId=<uuid>` query param on `/add-source` prefills the Connect step with that
   * device's own `protocol`/`uri`/`options`, landing on whichever fit-out row matches its
   * capabilities (`fit-out-logic.ts#roleForDevice` — TELEMETRY-capable is Sense, everything else is
   * Sight). Background, best-effort, silent-degrade like every other read in this constructor: an
   * unknown id, a 403, or no `deviceId` at all simply leaves both rows at their empty default —
   * never a blocked page over a read this app cannot promise will succeed.
   *
   * **Known limitation**: this creates a *new* device row on the asset the wizard is about to
   * register, not a reference to the original `Device` by id (`CreateAssetRequest` carries no such
   * field for `devices[]` — only `deviceIds[]` does, which this wizard's multi-role fit-out table
   * does not thread through). A caller linking here to "attach this already-registered device to a
   * new asset" gets a duplicate device row with the same protocol/uri, not a move — see this wave's
   * WAREHOUSE-UX-CONTEXT.md handoff for the full contract and this tradeoff.
   */
  private async applyDevicePrefill(): Promise<void> {
    const deviceId = this.route.snapshot.queryParamMap.get('deviceId');
    if (!deviceId) {
      return;
    }
    try {
      const devices = await this.api.listDevices();
      const device = devices.find((d) => d.id === deviceId);
      if (!device) {
        return;
      }
      this.applyResolvedConnection(roleForDevice(device), {
        protocol: device.protocol,
        uri: device.uri,
        options: Object.keys(device.options).length > 0 ? device.options : undefined,
      });
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }
}

/** Resets everything about a row except its `role` — used by {@link OnboardingStore.setRowValue} so switching a row's top-level choice never leaves a stale finder/protocol/uri/options behind. */
function emptyFitOutRowLike(row: FitOutRows[FitOutRole]): FitOutRows[FitOutRole] {
  return { role: row.role, value: 'none', findMethod: null, protocolSelect: '', customProtocol: '', uri: '', options: [] };
}
