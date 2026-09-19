import { DestroyRef, Injectable, computed, effect, inject, untracked } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { AuthFacade } from '../../core/auth/auth-facade';
import { FleetFacade } from '../../core/fleet/fleet-facade';
import { DiscoveryInboxFacade } from '../../core/discovery/discovery-inbox-facade';
import { WebSerialGateway } from '../provisioning/web-serial-gateway';
import { dispatchAndAwait } from '../../core/state/dispatch-bridge';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  buildTestDroneRequest,
} from '../../core/fleet/simulation-logic';
import { creatorOwnershipGroup } from '../../core/org/pilot-logic';
import { buildTelemetryRequest } from '../../shared/map/flight-plan-logic';
import type {
  CreateAssetRequest,
  DiscoveredDevice,
  DiscoveryCandidate,
  ParameterWriteRequest,
  TelemetryPlanRequest,
} from '../../core/api/models';
import {
  CUSTOM_PROTOCOL_OPTION,
  REGISTERABLE_PROTOCOLS,
  placeholderForProtocol,
} from './protocols';
import {
  FIT_OUT_ROLE_HINTS,
  FIT_OUT_ROLE_LABELS,
  combinedSysidCollision,
  effectiveProtocol,
  emptyFitOutRows,
  fitOutDeviceSpecs,
  usesLegacySimulationPath,
  type FitOutFindMethod,
  type FitOutRole,
} from '../../core/onboarding/fit-out-logic';
import {
  buildCreateAssetRequest,
  buildIdentityRequest,
  buildPostSimulationAssetEdit,
  currentProbeRequest,
  currentVerifyRequest,
  isLastProbeOk,
  isTelemetryOnlyProtocol,
  type IdentifyDraft,
  type SourceMode,
  type WizardStep,
} from './onboarding-logic';
import { OnboardingPhotoBuffer } from './onboarding-photo-buffer';
import { OnboardingApiActions, OnboardingPageActions } from './state/onboarding.actions';
import { onboardingWizardFeature } from './state/onboarding.reducer';
import { SCAN_TIMEOUTS, SIMULATE_MODE_HINTS } from './state/onboarding.model';
import type { Firmware, LinkType } from './drone-config-logic';
import type { FlightPlanForm } from '../../shared/map/flight-plan-logic';
import type { SimulateMode } from '../../core/fleet/simulation-logic';

/** A register/attach response's own `sysidPushRequired`/`assignedSysid` pair — see `foundCandidateSysidCollision`'s own doc comment. */
interface CandidateCollisionResponse {
  readonly sysidPushRequired?: boolean;
  readonly assignedSysid?: number;
}

function collisionFrom(response: CandidateCollisionResponse): number | null {
  return response.sysidPushRequired === true && response.assignedSysid !== undefined ? response.assignedSysid : null;
}

/**
 * Replaces `OnboardingStore` (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N8b) — the wizard's
 * read/dispatch boundary over the `onboardingWizard` slice (`state/onboarding.reducer.ts`) plus the
 * two things that stay out of it: `OnboardingPhotoBuffer` (the photo `File`/`Blob`/object-URL) and
 * the discovery-inbox ref-count (`DiscoveryInboxFacade#activate`/`#release` — a facade may inject
 * another facade; an `@ngrx/effects` class, `state/onboarding.effects.ts`, must not — §3 rule 4).
 *
 * Page-provided on `OnboardingPage` (`providers: [OnboardingWizardFacade, OnboardingFacade,
 * DiscoveryInboxFacade]`), same lifetime as the slice itself (`state/onboarding.providers.ts`,
 * registered on the route rather than root) — a fresh instance per visit to `/add-source`.
 *
 * `OnboardingFacade` (this page's *other* facade, pre-existing) still injects this class as
 * `readonly store = inject(OnboardingWizardFacade)` — every `facade.store.xxx` call site across the
 * six step components and their templates keeps working unchanged, since this class reproduces
 * `OnboardingStore`'s full public surface field-for-field. The one place a template called
 * `facade.store.someSignal.set(...)` directly (a `WritableSignal`, which an NgRx facade must never
 * expose — §3 rule 8, "facades expose only `Signal<T>`) has been changed to a matching
 * `setXxx(value)` method call in that template — see this wave's own report for the full list.
 */
@Injectable()
export class OnboardingWizardFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);
  private readonly fleet = inject(FleetFacade);
  private readonly auth = inject(AuthFacade);
  private readonly discoveryInbox = inject(DiscoveryInboxFacade);
  private readonly webSerial = inject(WebSerialGateway);
  private readonly route = inject(ActivatedRoute);
  private readonly photoBuffer = inject(OnboardingPhotoBuffer);

  // --- Identify ---
  readonly step = this.store.selectSignal(onboardingWizardFeature.selectStep);
  readonly displayName = this.store.selectSignal(onboardingWizardFeature.selectDisplayName);
  readonly registrationNumber = this.store.selectSignal(onboardingWizardFeature.selectRegistrationNumber);
  readonly serialNumber = this.store.selectSignal(onboardingWizardFeature.selectSerialNumber);
  readonly make = this.store.selectSignal(onboardingWizardFeature.selectMake);
  readonly model = this.store.selectSignal(onboardingWizardFeature.selectModel);
  readonly category = this.store.selectSignal(onboardingWizardFeature.selectCategory);
  readonly categoryOptions = this.store.selectSignal(onboardingWizardFeature.selectCategoryOptions);
  readonly categoryConnected = this.store.selectSignal(onboardingWizardFeature.selectCategoryConnected);
  readonly equipment = this.store.selectSignal(onboardingWizardFeature.selectEquipment);
  readonly identifyCategoryOptions = this.store.selectSignal(onboardingWizardFeature.selectIdentifyCategoryOptions);
  readonly canAdvanceIdentify = this.store.selectSignal(onboardingWizardFeature.selectCanAdvanceIdentify);
  readonly photoProcessing = this.store.selectSignal(onboardingWizardFeature.selectPhotoProcessing);
  readonly photoError = this.store.selectSignal(onboardingWizardFeature.selectPhotoError);
  /** Owned by `OnboardingPhotoBuffer`, not the slice — see this class's own doc comment. */
  readonly photoPreviewUrl = this.photoBuffer.previewUrl;

  // --- Source / fit-out rows ---
  readonly rows = this.store.selectSignal(onboardingWizardFeature.selectRows);
  readonly fitOutRoleLabels = FIT_OUT_ROLE_LABELS;
  readonly fitOutRoleHints = FIT_OUT_ROLE_HINTS;
  readonly registerableProtocols = REGISTERABLE_PROTOCOLS;
  readonly customProtocolOption = CUSTOM_PROTOCOL_OPTION;
  readonly sourceMode = this.store.selectSignal(onboardingWizardFeature.selectSourceMode);
  readonly equipmentConfirmed = this.store.selectSignal(onboardingWizardFeature.selectEquipmentConfirmed);
  readonly canAdvanceFromSource = this.store.selectSignal(onboardingWizardFeature.selectCanAdvanceFromSource);
  readonly preProvenRoles = this.store.selectSignal(onboardingWizardFeature.selectPreProvenRoles);
  readonly originCandidateId = this.store.selectSignal(onboardingWizardFeature.selectOriginCandidateId);
  readonly selectedCandidate = this.store.selectSignal(onboardingWizardFeature.selectSelectedCandidate);
  readonly confirmCredential = this.store.selectSignal(onboardingWizardFeature.selectConfirmCredential);
  readonly foundCandidateSysidCollision = this.store.selectSignal(onboardingWizardFeature.selectFoundCandidateSysidCollision);

  // --- Discover (Sight) ---
  readonly scanTimeouts = SCAN_TIMEOUTS;
  readonly scanTimeout = this.store.selectSignal(onboardingWizardFeature.selectScanTimeout);
  readonly scanning = this.store.selectSignal(onboardingWizardFeature.selectScanning);
  readonly scanResult = this.store.selectSignal(onboardingWizardFeature.selectScanResult);

  // --- Listen for drones (Sense) ---
  readonly droneScanning = this.store.selectSignal(onboardingWizardFeature.selectDroneScanning);
  readonly droneScanResult = this.store.selectSignal(onboardingWizardFeature.selectDroneScanResult);

  // --- Guided drone config ---
  readonly droneSubStep = this.store.selectSignal(onboardingWizardFeature.selectDroneSubStep);
  readonly droneFirmware = this.store.selectSignal(onboardingWizardFeature.selectDroneFirmware);
  readonly droneLink = this.store.selectSignal(onboardingWizardFeature.selectDroneLink);
  readonly networkAddresses = this.store.selectSignal(onboardingWizardFeature.selectNetworkAddresses);
  readonly mavlinkPort = this.store.selectSignal(onboardingWizardFeature.selectMavlinkPort);
  readonly selectedServerAddress = this.store.selectSignal(onboardingWizardFeature.selectSelectedServerAddress);
  readonly pushAddressCard = this.store.selectSignal(onboardingWizardFeature.selectPushAddressCard);
  /** Checked once (`WebSerialGateway#isSupported`'s own doc comment) — a facade-level `computed()`
   *  rather than a stored fact, since it is a synchronous, non-reactive browser capability probe with
   *  nothing for a reducer/effect to usefully own. */
  readonly provisionWifiSupported = computed(() => this.webSerial.isSupported());
  readonly droneCompatibility = this.store.selectSignal(onboardingWizardFeature.selectDroneCompatibility);
  readonly droneConfigBlocks = this.store.selectSignal(onboardingWizardFeature.selectDroneConfigBlocks);

  // --- Legacy whole-vehicle Simulate ---
  readonly simMode = this.store.selectSignal(onboardingWizardFeature.selectSimMode);
  readonly simVideoPath = this.store.selectSignal(onboardingWizardFeature.selectSimVideoPath);
  readonly simLatitude = this.store.selectSignal(onboardingWizardFeature.selectSimLatitude);
  readonly simLongitude = this.store.selectSignal(onboardingWizardFeature.selectSimLongitude);
  readonly simAutoStart = this.store.selectSignal(onboardingWizardFeature.selectSimAutoStart);
  readonly simModeHint = computed(() => SIMULATE_MODE_HINTS[this.simMode()]);
  readonly simNeedsVideoPath = computed(() => this.simMode() === 'direct' || this.simMode() === 'rtsp');
  readonly simNeedsHomePoint = computed(() => this.simMode() !== 'synthetic');
  readonly flightPlanDialogOpen = this.store.selectSignal(onboardingWizardFeature.selectFlightPlanDialogOpen);
  readonly flightPlan = this.store.selectSignal(onboardingWizardFeature.selectFlightPlan);
  readonly flightPlanSummary = computed(() => {
    const plan = this.flightPlan();
    return plan ? `${plan.waypoints.length} waypoints · ${plan.routeMode}` : null;
  });

  // --- Waiting room ---
  readonly discoveryStatus = this.store.selectSignal(onboardingWizardFeature.selectDiscoveryStatus);
  readonly nowMs = this.store.selectSignal(onboardingWizardFeature.selectNowMs);
  readonly discoveryCandidates = this.discoveryInbox.candidates;

  // --- Prove ---
  readonly proveByRole = this.store.selectSignal(onboardingWizardFeature.selectProveByRole);
  readonly rowsNeedProve = this.store.selectSignal(onboardingWizardFeature.selectRowsNeedProve);
  readonly canAdvanceProve = this.store.selectSignal(onboardingWizardFeature.selectCanAdvanceProve);

  // --- Attach ---
  readonly creating = this.store.selectSignal(onboardingWizardFeature.selectCreating);
  readonly attachTarget = this.store.selectSignal(onboardingWizardFeature.selectAttachTarget);
  readonly existingAssets = this.store.selectSignal(onboardingWizardFeature.selectExistingAssets);
  readonly existingAssetsLoading = this.store.selectSignal(onboardingWizardFeature.selectExistingAssetsLoading);
  readonly selectedExistingAssetId = this.store.selectSignal(onboardingWizardFeature.selectSelectedExistingAssetId);
  readonly existingAssetPath = this.store.selectSignal(onboardingWizardFeature.selectExistingAssetPath);

  // --- Sysid ---
  readonly writingSysid = this.store.selectSignal(onboardingWizardFeature.selectWritingSysid);
  readonly sysidWriteResult = this.store.selectSignal(onboardingWizardFeature.selectSysidWriteResult);
  readonly sysidWriteError = this.store.selectSignal(onboardingWizardFeature.selectSysidWriteError);
  readonly sysidValue = this.store.selectSignal(onboardingWizardFeature.selectSysidValue);

  // --- Hand-over ---
  readonly createdAssetId = this.store.selectSignal(onboardingWizardFeature.selectCreatedAssetId);
  readonly createdAssetDisplayName = this.store.selectSignal(onboardingWizardFeature.selectCreatedAssetDisplayName);
  readonly pilotCandidates = this.store.selectSignal(onboardingWizardFeature.selectPilotCandidates);
  readonly pilotCandidatesLoading = this.store.selectSignal(onboardingWizardFeature.selectPilotCandidatesLoading);
  readonly ownerGroupName = this.store.selectSignal(onboardingWizardFeature.selectOwnerGroupName);
  readonly selectedCustodianId = this.store.selectSignal(onboardingWizardFeature.selectSelectedCustodianId);
  readonly handoverLocation = this.store.selectSignal(onboardingWizardFeature.selectHandoverLocation);
  readonly handingOver = this.store.selectSignal(onboardingWizardFeature.selectHandingOver);
  readonly handoverError = this.store.selectSignal(onboardingWizardFeature.selectHandoverError);
  readonly handoverOutcome = this.store.selectSignal(onboardingWizardFeature.selectHandoverOutcome);

  // --- Navigation ---
  readonly canAdvance = this.store.selectSignal(onboardingWizardFeature.selectCanAdvance);

  constructor() {
    this.store.dispatch(OnboardingPageActions.categoryOptionsRequested());
    this.store.dispatch(OnboardingPageActions.systemNetworkRequested());
    const candidateId = this.route.snapshot.queryParamMap.get('candidateId');
    if (candidateId) {
      this.store.dispatch(OnboardingPageActions.candidateQueryPrefillRequested({ candidateId }));
    }

    // The discovery-inbox ref-count — only ever matters while the `source` step itself is on screen,
    // exactly like `OnboardingStore`'s own constructor `step()` effect. The actual poll GET
    // (`GET /api/discovery/status`) is a separate mechanism, `state/onboarding.effects.ts#discoveryStatusPoll$`,
    // watching the same `step` fact independently via `store.select` — this facade owns only the half
    // that requires injecting another facade.
    effect(() => {
      const onSource = this.step() === 'source';
      untracked(() => {
        if (onSource) {
          this.discoveryInbox.activate();
        } else {
          this.discoveryInbox.release();
        }
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.discoveryInbox.release();
    });
  }

  // --- Identify ---

  setDisplayName(value: string): void {
    this.store.dispatch(OnboardingPageActions.displayNameChanged({ value }));
  }

  setRegistrationNumber(value: string): void {
    this.store.dispatch(OnboardingPageActions.registrationNumberChanged({ value }));
  }

  setSerialNumber(value: string): void {
    this.store.dispatch(OnboardingPageActions.serialNumberChanged({ value }));
  }

  setMake(value: string): void {
    this.store.dispatch(OnboardingPageActions.makeChanged({ value }));
  }

  setModel(value: string): void {
    this.store.dispatch(OnboardingPageActions.modelChanged({ value }));
  }

  chooseCategory(slug: string): void {
    this.store.dispatch(OnboardingPageActions.categoryChosen({ slug }));
  }

  /** Downscales in the background (`OnboardingPhotoBuffer`); the field input stays usable meanwhile. */
  async choosePhoto(file: File): Promise<void> {
    this.store.dispatch(OnboardingPageActions.photoProcessingStarted());
    const outcome = await this.photoBuffer.choose(file);
    this.store.dispatch(outcome.ok ? OnboardingPageActions.photoAccepted() : OnboardingPageActions.photoRejected({ error: outcome.error }));
  }

  removePhoto(): void {
    this.photoBuffer.remove();
    this.store.dispatch(OnboardingPageActions.photoRemoved());
  }

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

  // --- Source / fit-out rows ---

  setRowValue(role: FitOutRole, value: 'find' | 'simulate' | 'none'): void {
    this.store.dispatch(OnboardingPageActions.rowValueSet({ role, value }));
  }

  chooseRowFind(role: FitOutRole, method: FitOutFindMethod): void {
    this.store.dispatch(OnboardingPageActions.rowFindChosen({ role, method }));
  }

  backFromRowFind(role: FitOutRole): void {
    this.store.dispatch(OnboardingPageActions.rowFindBack({ role }));
  }

  clearRow(role: FitOutRole): void {
    this.store.dispatch(OnboardingPageActions.rowCleared({ role }));
  }

  setRowProtocolSelect(role: FitOutRole, value: string): void {
    this.store.dispatch(OnboardingPageActions.rowProtocolSelected({ role, value }));
  }

  setRowCustomProtocol(role: FitOutRole, value: string): void {
    this.store.dispatch(OnboardingPageActions.rowCustomProtocolSet({ role, value }));
  }

  setRowUri(role: FitOutRole, value: string): void {
    this.store.dispatch(OnboardingPageActions.rowUriSet({ role, value }));
  }

  addRowOption(role: FitOutRole): void {
    this.store.dispatch(OnboardingPageActions.rowOptionAdded({ role }));
  }

  removeRowOption(role: FitOutRole, index: number): void {
    this.store.dispatch(OnboardingPageActions.rowOptionRemoved({ role, index }));
  }

  updateRowOptionKey(role: FitOutRole, index: number, key: string): void {
    this.store.dispatch(OnboardingPageActions.rowOptionKeyUpdated({ role, index, key }));
  }

  updateRowOptionValue(role: FitOutRole, index: number, value: string): void {
    this.store.dispatch(OnboardingPageActions.rowOptionValueUpdated({ role, index, value }));
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

  /** Whether the last successful probe for this row was for *these exact* connection fields — see `onboarding-logic.ts#isLastProbeOk`'s own doc comment. */
  lastProbeOk(role: FitOutRole): boolean | undefined {
    const prove = this.proveByRole()[role];
    return isLastProbeOk(currentProbeRequest(this.rows()[role]), prove.lastProbeRequest, prove.lastProbeResult);
  }

  telemetryOnlyLink(role: FitOutRole): boolean {
    return isTelemetryOnlyProtocol(this.rowProtocol(role));
  }

  useCandidate(candidate: DiscoveredDevice): void {
    this.store.dispatch(OnboardingPageActions.sightCandidateChosen({ candidate }));
  }

  useDroneVehicle(candidate: DiscoveredDevice): void {
    this.store.dispatch(OnboardingPageActions.senseDroneCandidateChosen({ candidate }));
  }

  useHeardCandidate(candidate: DiscoveryCandidate): void {
    this.store.dispatch(OnboardingPageActions.rowPrefilledFromCandidate({ candidate }));
  }

  chooseSourceMode(mode: SourceMode): void {
    this.store.dispatch(OnboardingPageActions.sourceModeChosen({ mode }));
  }

  backToSourceFork(): void {
    this.store.dispatch(OnboardingPageActions.backToSourceFork());
  }

  // --- "Found nearby" feed + Confirm interstitial ---

  chooseFoundCandidate(candidate: DiscoveryCandidate): void {
    this.store.dispatch(OnboardingPageActions.foundCandidateChosen({ candidate }));
  }

  setConfirmCredential(value: string): void {
    this.store.dispatch(OnboardingPageActions.confirmCredentialSet({ value }));
  }

  continueFromConfirm(): void {
    this.store.dispatch(OnboardingPageActions.continueFromConfirm());
  }

  backFromConfirm(): void {
    this.store.dispatch(OnboardingPageActions.backFromConfirm());
  }

  attachExistingFromConfirm(): void {
    this.store.dispatch(OnboardingPageActions.attachExistingFromConfirm());
  }

  // --- Discover (Sight) / Listen for drones (Sense) ---

  setScanTimeout(value: number): void {
    this.store.dispatch(OnboardingPageActions.scanTimeoutSet({ value }));
  }

  async scan(): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.scanRequested({ timeoutMs: this.scanTimeout() }),
      OnboardingApiActions.scanSucceeded,
      OnboardingApiActions.scanFailed,
      () => undefined,
      () => undefined,
    );
  }

  async scanForDrones(): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.droneScanRequested(),
      OnboardingApiActions.droneScanSucceeded,
      OnboardingApiActions.droneScanFailed,
      () => undefined,
      () => undefined,
    );
  }

  // --- Guided drone config ---

  chooseDroneFirmware(firmware: Firmware): void {
    this.store.dispatch(OnboardingPageActions.droneFirmwareChosen({ firmware }));
  }

  chooseDroneLink(link: LinkType): void {
    this.store.dispatch(OnboardingPageActions.droneLinkChosen({ link }));
  }

  setSelectedServerAddress(address: string): void {
    this.store.dispatch(OnboardingPageActions.selectedServerAddressSet({ address }));
  }

  continueToDroneConfig(): void {
    this.store.dispatch(OnboardingPageActions.continueToDroneConfig());
  }

  backFromDroneConfig(): void {
    this.store.dispatch(OnboardingPageActions.backFromDroneConfig());
  }

  /** The hand-off — see `OnboardingStore#finishDroneConfigAndListen`'s own doc comment. */
  async finishDroneConfigAndListen(): Promise<void> {
    this.chooseRowFind('sense', 'listen');
    await this.scanForDrones();
  }

  // --- Legacy whole-vehicle Simulate ---

  setSimMode(mode: SimulateMode): void {
    this.store.dispatch(OnboardingPageActions.simModeSet({ mode }));
  }

  setSimVideoPath(value: string): void {
    this.store.dispatch(OnboardingPageActions.simVideoPathSet({ value }));
  }

  setSimLatitude(value: number | null): void {
    this.store.dispatch(OnboardingPageActions.simLatitudeSet({ value }));
  }

  setSimLongitude(value: number | null): void {
    this.store.dispatch(OnboardingPageActions.simLongitudeSet({ value }));
  }

  setSimAutoStart(value: boolean): void {
    this.store.dispatch(OnboardingPageActions.simAutoStartSet({ value }));
  }

  openFlightPlanDialog(): void {
    this.store.dispatch(OnboardingPageActions.flightPlanDialogOpened());
  }

  onFlightPlanSaved(plan: FlightPlanForm): void {
    this.store.dispatch(OnboardingPageActions.flightPlanSaved({ plan }));
  }

  onFlightPlanCancelled(): void {
    this.store.dispatch(OnboardingPageActions.flightPlanCancelled());
  }

  clearFlightPlan(): void {
    this.store.dispatch(OnboardingPageActions.flightPlanCleared());
  }

  private currentTelemetryRequest(): TelemetryPlanRequest | undefined {
    const plan = this.flightPlan();
    return plan ? buildTelemetryRequest(plan) : undefined;
  }

  // --- Prove ---

  async testRow(role: FitOutRole): Promise<void> {
    const request = currentProbeRequest(this.rows()[role]);
    if (!request) {
      return;
    }
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.testRowRequested({ role, request }),
      OnboardingApiActions.testRowSucceeded,
      OnboardingApiActions.testRowFailed,
      () => undefined,
      () => undefined,
    );
  }

  async verifyRow(role: FitOutRole): Promise<void> {
    const request = currentVerifyRequest(this.rows()[role]);
    if (!request) {
      return;
    }
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.verifyRowRequested({ role, request }),
      OnboardingApiActions.verifyRowSucceeded,
      OnboardingApiActions.verifyRowFailed,
      () => undefined,
      () => undefined,
    );
  }

  // --- Attach ---

  chooseAttachTarget(target: 'new' | 'existing'): void {
    this.store.dispatch(OnboardingPageActions.attachTargetChosen({ target }));
  }

  selectExistingAsset(assetId: string): void {
    this.store.dispatch(OnboardingPageActions.selectedExistingAssetIdSet({ assetId }));
  }

  async createAsset(): Promise<void> {
    if (this.creating()) {
      return;
    }
    if (this.attachTarget() === 'existing') {
      await this.attachToExistingAsset();
      return;
    }
    this.store.dispatch(OnboardingPageActions.createFlowStarted());
    try {
      const candidateId = this.originCandidateId();
      if (this.selectedCandidate() && candidateId) {
        await this.createViaCandidateRegister(candidateId);
      } else if (usesLegacySimulationPath(this.rows())) {
        await this.createViaSimulation();
      } else {
        await this.createViaConnection();
      }
    } finally {
      this.store.dispatch(OnboardingPageActions.createFlowSettled());
    }
  }

  private async createViaCandidateRegister(candidateId: string): Promise<void> {
    const draft = { displayName: this.displayName().trim() || 'New asset', category: this.category().trim() };
    const result = await this.discoveryInbox.register(candidateId, draft);
    if (!result) {
      return; // failure already toasted by DiscoveryInboxFacade#register
    }
    this.store.dispatch(OnboardingPageActions.foundCandidateCollisionApplied({ collision: collisionFrom(result) }));
    const edit = buildPostSimulationAssetEdit(this.identifyDraft());
    if (Object.keys(edit).length === 0) {
      await this.finishCreate(result.assetId, result.displayName);
      return;
    }
    const displayName = await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.updateAssetEditRequested({ assetId: result.assetId, edit }),
      OnboardingApiActions.updateAssetEditSucceeded,
      OnboardingApiActions.updateAssetEditFailed,
      (action) => action.displayName as string | null,
      () => null,
    );
    if (displayName === null) {
      return; // failure already toasted by `notifyFailure$`
    }
    await this.finishCreate(result.assetId, displayName);
  }

  private async createViaConnection(): Promise<void> {
    const request = buildCreateAssetRequest(this.identifyDraft(), this.rows());
    await this.createFromRequestAndFinish(request);
  }

  async receiveEquipmentAsset(): Promise<void> {
    if (this.creating() || !this.canAdvanceIdentify()) {
      return;
    }
    this.store.dispatch(OnboardingPageActions.createFlowStarted());
    try {
      const request = buildCreateAssetRequest(this.identifyDraft(), emptyFitOutRows());
      await this.createFromRequestAndFinish(request);
    } finally {
      this.store.dispatch(OnboardingPageActions.createFlowSettled());
    }
  }

  private async createFromRequestAndFinish(request: CreateAssetRequest): Promise<void> {
    const created = await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.createAssetRequested({ request }),
      OnboardingApiActions.createAssetSucceeded,
      OnboardingApiActions.createAssetFailed,
      (action) => ({ assetId: action.assetId, displayName: action.displayName }) as { assetId: string; displayName: string } | null,
      () => null,
    );
    if (!created) {
      return; // failure already toasted by `notifyFailure$`
    }
    await this.finishCreate(created.assetId, created.displayName);
  }

  private async createViaSimulation(): Promise<void> {
    if (this.simMode() === 'synthetic') {
      const synthetic = buildSyntheticRegisterRequest(this.displayName());
      const identity = buildIdentityRequest(this.identifyDraft());
      const request: CreateAssetRequest = {
        displayName: this.displayName().trim(),
        category: this.category().trim(),
        ...(identity ? { identity } : {}),
        devices: [{ name: this.displayName().trim(), protocol: synthetic.protocol, uri: synthetic.uri }],
      };
      await this.createFromRequestAndFinish(request);
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
      return; // failure already toasted by FleetFacade
    }

    const edit = buildPostSimulationAssetEdit(this.identifyDraft());
    if (Object.keys(edit).length === 0) {
      await this.finishCreate(response.assetId, this.displayName().trim() || response.assetId);
      return;
    }
    const displayName = await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.updateAssetEditRequested({ assetId: response.assetId, edit }),
      OnboardingApiActions.updateAssetEditSucceeded,
      OnboardingApiActions.updateAssetEditFailed,
      (action) => action.displayName as string | null,
      () => null,
    );
    if (displayName === null) {
      return;
    }
    await this.finishCreate(response.assetId, displayName || response.assetId);
  }

  private async attachToExistingAsset(): Promise<void> {
    const assetId = this.selectedExistingAssetId();
    if (!assetId) {
      return;
    }
    this.store.dispatch(OnboardingPageActions.createFlowStarted());
    try {
      const asset = this.existingAssets().find((a) => a.assetId === assetId);
      const displayName = asset?.displayName ?? assetId;
      const candidateId = this.originCandidateId();
      const ok = candidateId ? await this.discoveryInbox.attachCandidate(candidateId, assetId) : await this.registerAndAssignRows(assetId);
      if (!ok) {
        return;
      }
      if (candidateId) {
        const updated = this.discoveryInbox.candidates().find((c) => c.id === candidateId);
        if (updated) {
          this.store.dispatch(OnboardingPageActions.foundCandidateCollisionApplied({ collision: collisionFrom(updated) }));
        }
      }
      this.store.dispatch(OnboardingPageActions.existingAssetPathSet());
      await this.fleet.refresh({ quiet: true });
      await this.finishCreate(assetId, displayName);
    } finally {
      this.store.dispatch(OnboardingPageActions.createFlowSettled());
    }
  }

  private async registerAndAssignRows(assetId: string): Promise<boolean> {
    const specs = fitOutDeviceSpecs(this.rows(), this.displayName().trim() || 'Device');
    return dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.registerAndAssignRequested({ assetId, specs }),
      OnboardingApiActions.registerAndAssignSucceeded,
      OnboardingApiActions.registerAndAssignFailed,
      () => true,
      () => false,
    );
  }

  private async finishCreate(assetId: string, displayName: string): Promise<void> {
    if (this.photoBuffer.currentBlob) {
      await dispatchAndAwait(
        this.store,
        this.actions$,
        OnboardingPageActions.uploadAssetImageRequested({ assetId, displayName }),
        OnboardingApiActions.uploadAssetImageSucceeded,
        OnboardingApiActions.uploadAssetImageFailed,
        () => undefined,
        () => undefined,
      );
    }
    await this.fleet.refresh({ quiet: true });
    // FLEET-RADIO-PLAN.md R5/F0 — see `OnboardingStore#finishCreate`'s own doc comment for the full
    // sysid-vs-handover routing rule this reproduces verbatim.
    const collision =
      combinedSysidCollision({ sense: this.proveByRole().sense.sysidCollision, sight: this.proveByRole().sight.sysidCollision }) ??
      this.foundCandidateSysidCollision();
    if (collision !== null) {
      this.store.dispatch(OnboardingPageActions.sysidStepEntered({ assetId, displayName, prefillSysidValue: this.foundCandidateSysidCollision() }));
    } else {
      await this.enterHandoverStep(assetId, displayName);
    }
  }

  // --- Sysid ---

  setSysidValue(value: number | null): void {
    this.store.dispatch(OnboardingPageActions.sysidValueSet({ value }));
  }

  async writeSysid(): Promise<void> {
    const assetId = this.createdAssetId();
    const parameterName =
      this.proveByRole().sense.sysidParameterToWrite ??
      this.proveByRole().sight.sysidParameterToWrite ??
      (this.foundCandidateSysidCollision() !== null ? 'MAV_SYSID' : null);
    const value = this.sysidValue();
    if (!assetId || parameterName === null || value === null || this.writingSysid()) {
      return;
    }
    const request: ParameterWriteRequest = { name: parameterName, value, consent: true };
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.writeSysidRequested({ assetId, request }),
      OnboardingApiActions.writeSysidSucceeded,
      OnboardingApiActions.writeSysidFailed,
      () => undefined,
      () => undefined,
    );
  }

  continueFromSysidStep(): void {
    const assetId = this.createdAssetId();
    if (!assetId) {
      return;
    }
    void this.enterHandoverStep(assetId, this.createdAssetDisplayName());
  }

  // --- Hand-over ---

  private async enterHandoverStep(assetId: string, displayName: string): Promise<void> {
    this.store.dispatch(OnboardingPageActions.handoverStepEntered({ assetId, displayName }));
    if (this.existingAssetPath()) {
      return; // that asset already has an owner — no roster to fetch, no picker to show.
    }
    const creator = this.auth.user();
    const group = creatorOwnershipGroup(creator?.memberships ?? []);
    await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.pilotCandidatesRequested({ group, creatorUserId: creator?.userId }),
      OnboardingApiActions.pilotCandidatesSucceeded,
      OnboardingApiActions.pilotCandidatesFailed,
      () => undefined,
      () => undefined,
    );
  }

  selectCustodian(userId: string | null): void {
    this.store.dispatch(OnboardingPageActions.custodianSelected({ userId }));
  }

  setHandoverLocation(location: string): void {
    this.store.dispatch(OnboardingPageActions.handoverLocationSet({ value: location }));
  }

  async issueToCustodian(): Promise<void> {
    const assetId = this.createdAssetId();
    const custodianId = this.selectedCustodianId();
    if (!assetId || !custodianId || this.handingOver()) {
      return;
    }
    const location = this.handoverLocation().trim();
    const ok = await dispatchAndAwait(
      this.store,
      this.actions$,
      OnboardingPageActions.setAssetCustodyRequested({ assetId, custodianId, location }),
      OnboardingApiActions.setAssetCustodySucceeded,
      OnboardingApiActions.setAssetCustodyFailed,
      () => true,
      () => false,
    );
    if (!ok) {
      return;
    }
    await this.fleet.refresh({ quiet: true });
    this.store.dispatch(OnboardingPageActions.handoverOutcomeSet({ outcome: 'issued' }));
  }

  leaveInStock(): void {
    this.store.dispatch(OnboardingPageActions.leaveInStock());
  }

  // --- Step navigation ---

  next(): void {
    this.store.dispatch(OnboardingPageActions.stepAdvanced());
  }

  back(): void {
    this.store.dispatch(OnboardingPageActions.stepBack());
  }

  jumpToStep(step: WizardStep): void {
    this.store.dispatch(OnboardingPageActions.stepJumped({ step }));
  }
}
