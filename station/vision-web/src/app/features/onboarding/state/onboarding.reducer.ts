import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import type { DiscoveryCandidate } from '../../../core/api/models';
import {
  FIT_OUT_ROLES,
  canAdvanceFromFitOut,
  emptyFitOutRow,
  isRowFilled,
  type FitOutFindMethod,
  type FitOutRole,
  type FitOutRows,
} from '../../../core/onboarding/fit-out-logic';
import { isClaimedVehicle } from '../drone-scan-logic';
import { buildDroneDeviceSpec, configSnippets, linkCompatibility } from '../drone-config-logic';
import {
  applyResolvedConnection,
  canAdvanceFromIdentify,
  composePushAddress,
  currentProbeRequest,
  emptyFitOutRowLike,
  isEquipmentPath,
  isLastProbeOk,
  nextStep,
  prefillFromDiscoveryCandidate,
  prevStep,
  roleForDiscoveryMethod,
  type StepContext,
  type WizardStep,
} from '../onboarding-logic';
import { OnboardingApiActions, OnboardingPageActions } from './onboarding.actions';
import { emptyRowProveState, initialOnboardingWizardState, type OnboardingWizardState, type RowProveState } from './onboarding.model';

// --- Reducer-local pure helpers (never exported — the selector half below composes the same
// underlying `onboarding-logic.ts`/`fit-out-logic.ts` functions differently, over root state
// instead of this slice's own local `state` object; see this file's own extraSelectors doc comment). --

function stepContextOf(state: OnboardingWizardState): StepContext {
  return { equipment: isEquipmentPath(state.rows), needsProve: rowsNeedProveOf(state) };
}

function rowsNeedProveOf(state: OnboardingWizardState): boolean {
  return FIT_OUT_ROLES.some((role) => state.rows[role].value === 'find' && !state.preProvenRoles.includes(role));
}

function canAdvanceProveOf(state: OnboardingWizardState): boolean {
  return FIT_OUT_ROLES.filter((role) => state.rows[role].value === 'find' && !state.preProvenRoles.includes(role)).every(
    (role) => isLastProbeOk(currentProbeRequest(state.rows[role]), state.proveByRole[role].lastProbeRequest, state.proveByRole[role].lastProbeResult) === true,
  );
}

function canAdvanceOf(state: OnboardingWizardState): boolean {
  switch (state.step) {
    case 'source':
      return canAdvanceFromFitOut(state.rows, state.equipmentConfirmed);
    case 'prove':
      return canAdvanceProveOf(state);
    case 'identify':
      return canAdvanceFromIdentify(state.displayName, state.category);
    default:
      return false; // attach/confirm/sysid/handover each drive their own action, not "Next" — see `WizardStep`'s own doc comment.
  }
}

/** `preProvenRoles` un-trusts the moment that role's connection fields are edited again — mirrors `OnboardingStore#clearPreProven`. */
function withoutPreProven(roles: readonly FitOutRole[], role: FitOutRole): readonly FitOutRole[] {
  return roles.includes(role) ? roles.filter((r) => r !== role) : roles;
}

function withPreProven(roles: readonly FitOutRole[], role: FitOutRole): readonly FitOutRole[] {
  return roles.includes(role) ? roles : [...roles, role];
}

function patchRow(rows: FitOutRows, role: FitOutRole, patch: Partial<FitOutRows[FitOutRole]>): FitOutRows {
  return { ...rows, [role]: { ...rows[role], ...patch } };
}

/** Mirrors `OnboardingStore#clearRow` — always clears `originCandidateId`, regardless of which role. */
function clearRow(state: OnboardingWizardState, role: FitOutRole): OnboardingWizardState {
  return {
    ...state,
    rows: { ...state.rows, [role]: emptyFitOutRow(role) },
    preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    originCandidateId: null,
  };
}

/** Mirrors `OnboardingStore#prefillRowFromCandidate` — `undefined` (no suggested stream yet) leaves
 *  state untouched; `onboarding.effects.ts#notifyPrefillMiss$` fires the matching info toast off the
 *  same dispatched action, recomputing this same pure predicate. */
function applyCandidatePrefill(state: OnboardingWizardState, candidate: DiscoveryCandidate): OnboardingWizardState {
  const prefill = prefillFromDiscoveryCandidate(candidate);
  if (!prefill) {
    return state;
  }
  return {
    ...state,
    rows: patchRow(state.rows, prefill.role, applyResolvedConnection(state.rows[prefill.role], prefill)),
    preProvenRoles: withPreProven(state.preProvenRoles, prefill.role),
    displayName: state.displayName.trim().length === 0 ? prefill.displayName : state.displayName,
    category: prefill.category && state.category.trim().length === 0 ? prefill.category : state.category,
    sourceMode: 'passive',
  };
}

/** Mirrors `OnboardingStore#chooseSourceMode`. */
function applySourceModeChoice(state: OnboardingWizardState, mode: OnboardingWizardState['sourceMode']): OnboardingWizardState {
  if (mode === 'equipment') {
    return {
      ...state,
      rows: { sense: emptyFitOutRowLike(state.rows.sense), sight: emptyFitOutRowLike(state.rows.sight) },
      preProvenRoles: [],
      originCandidateId: null,
      equipmentConfirmed: true,
      sourceMode: 'equipment',
    };
  }
  let rows = state.rows;
  for (const role of FIT_OUT_ROLES) {
    if (!state.preProvenRoles.includes(role) && !isRowFilled(rows[role])) {
      rows = { ...rows, [role]: emptyFitOutRowLike(rows[role]) };
    }
  }
  if (mode === 'passive') {
    rows = initEmptyRow(rows, state.preProvenRoles, 'sense', 'listen');
    rows = initEmptyRow(rows, state.preProvenRoles, 'sight', 'discover');
  } else if (mode === 'manual') {
    rows = initEmptyRow(rows, state.preProvenRoles, 'sense', 'register');
    rows = initEmptyRow(rows, state.preProvenRoles, 'sight', 'register');
  }
  return { ...state, rows, equipmentConfirmed: false, sourceMode: mode };
}

function initEmptyRow(
  rows: FitOutRows,
  preProvenRoles: readonly FitOutRole[],
  role: FitOutRole,
  method: FitOutFindMethod,
): FitOutRows {
  if (preProvenRoles.includes(role) || isRowFilled(rows[role])) {
    return rows;
  }
  return patchRow(rows, role, { value: 'find', findMethod: method });
}

function updateProve(state: OnboardingWizardState, role: FitOutRole, patch: Partial<RowProveState>): OnboardingWizardState {
  return { ...state, proveByRole: { ...state.proveByRole, [role]: { ...state.proveByRole[role], ...patch } } };
}

export const onboardingWizardFeature = createFeature({
  name: 'onboardingWizard',
  reducer: createReducer(
    initialOnboardingWizardState,

    // --- Identify ---
    on(OnboardingPageActions.displayNameChanged, (state, { value }) => ({ ...state, displayName: value })),
    on(OnboardingPageActions.registrationNumberChanged, (state, { value }) => ({ ...state, registrationNumber: value })),
    on(OnboardingPageActions.serialNumberChanged, (state, { value }) => ({ ...state, serialNumber: value })),
    on(OnboardingPageActions.makeChanged, (state, { value }) => ({ ...state, make: value })),
    on(OnboardingPageActions.modelChanged, (state, { value }) => ({ ...state, model: value })),
    on(OnboardingPageActions.categoryChosen, (state, { slug }) => ({ ...state, category: slug })),
    on(OnboardingPageActions.photoProcessingStarted, (state) => ({ ...state, photoProcessing: true, photoError: null })),
    on(OnboardingPageActions.photoAccepted, (state) => ({ ...state, photoProcessing: false, photoError: null })),
    on(OnboardingPageActions.photoRejected, (state, { error }) => ({ ...state, photoProcessing: false, photoError: error })),
    on(OnboardingPageActions.photoRemoved, (state) => ({ ...state, photoProcessing: false, photoError: null })),

    // --- Source / fit-out rows ---
    on(OnboardingPageActions.rowValueSet, (state, { role, value }) => ({
      ...state,
      rows: patchRow(state.rows, role, { ...emptyFitOutRowLike(state.rows[role]), value }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowFindChosen, (state, { role, method }) => ({
      ...state,
      rows: patchRow(state.rows, role, { value: 'find', findMethod: method }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
      droneSubStep: method === 'drone' ? 'picker' : state.droneSubStep,
    })),
    on(OnboardingPageActions.rowFindBack, (state, { role }) => ({ ...state, rows: patchRow(state.rows, role, { findMethod: null }) })),
    on(OnboardingPageActions.rowCleared, (state, { role }) => clearRow(state, role)),
    on(OnboardingPageActions.rowProtocolSelected, (state, { role, value }) => ({
      ...state,
      rows: patchRow(state.rows, role, { protocolSelect: value }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowCustomProtocolSet, (state, { role, value }) => ({
      ...state,
      rows: patchRow(state.rows, role, { customProtocol: value }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowUriSet, (state, { role, value }) => ({
      ...state,
      rows: patchRow(state.rows, role, { uri: value }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowOptionAdded, (state, { role }) => ({
      ...state,
      rows: patchRow(state.rows, role, { options: [...state.rows[role].options, { key: '', value: '' }] }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowOptionRemoved, (state, { role, index }) => ({
      ...state,
      rows: patchRow(state.rows, role, { options: state.rows[role].options.filter((_, i) => i !== index) }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowOptionKeyUpdated, (state, { role, index, key }) => ({
      ...state,
      rows: patchRow(state.rows, role, { options: state.rows[role].options.map((o, i) => (i === index ? { ...o, key } : o)) }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.rowOptionValueUpdated, (state, { role, index, value }) => ({
      ...state,
      rows: patchRow(state.rows, role, { options: state.rows[role].options.map((o, i) => (i === index ? { ...o, value } : o)) }),
      preProvenRoles: withoutPreProven(state.preProvenRoles, role),
    })),
    on(OnboardingPageActions.sightCandidateChosen, (state, { candidate }) => ({
      ...state,
      rows: patchRow(state.rows, 'sight', applyResolvedConnection(state.rows.sight, { protocol: candidate.protocol, uri: candidate.uri ?? candidate.address })),
      preProvenRoles: withoutPreProven(state.preProvenRoles, 'sight'),
      displayName: state.displayName.trim().length === 0 ? candidate.name : state.displayName,
    })),
    on(OnboardingPageActions.senseDroneCandidateChosen, (state, { candidate }) => {
      if (isClaimedVehicle(candidate)) {
        return state;
      }
      const spec = buildDroneDeviceSpec(candidate, state.mavlinkPort);
      return {
        ...state,
        rows: patchRow(state.rows, 'sense', applyResolvedConnection(state.rows.sense, spec)),
        preProvenRoles: withoutPreProven(state.preProvenRoles, 'sense'),
        displayName: state.displayName.trim().length === 0 ? candidate.name : state.displayName,
        category: candidate.suggestedCategory && state.category.trim().length === 0 ? candidate.suggestedCategory : state.category,
      };
    }),
    on(OnboardingPageActions.rowPrefilledFromCandidate, (state, { candidate }) => applyCandidatePrefill(state, candidate)),
    on(OnboardingPageActions.sourceModeChosen, (state, { mode }) => applySourceModeChoice(state, mode)),
    on(OnboardingPageActions.backToSourceFork, (state) => ({ ...state, sourceMode: null })),

    // --- "Found nearby" feed + Confirm interstitial ---
    on(OnboardingPageActions.foundCandidateChosen, (state, { candidate }) => {
      const prefilled = applyCandidatePrefill(state, candidate);
      if (prefilled === state) {
        return state; // no suggested stream yet — `notifyPrefillMiss$` toasts, nothing else changes.
      }
      return {
        ...prefilled,
        confirmCredential: '',
        selectedCandidate: candidate,
        originCandidateId: candidate.id,
        step: 'confirm' as WizardStep,
      };
    }),
    on(OnboardingPageActions.confirmCredentialSet, (state, { value }) => ({ ...state, confirmCredential: value })),
    on(OnboardingPageActions.continueFromConfirm, (state) => {
      const candidate = state.selectedCandidate;
      const credential = state.confirmCredential.trim();
      let rows = state.rows;
      if (candidate && credential.length > 0) {
        const role = roleForDiscoveryMethod(candidate.method);
        rows = patchRow(rows, role, { options: [...rows[role].options, { key: 'password', value: credential }] });
      }
      const next = { ...state, rows };
      return { ...next, step: nextStep('source', stepContextOf(next)) };
    }),
    on(OnboardingPageActions.backFromConfirm, (state) => {
      const candidate = state.selectedCandidate;
      const cleared = candidate ? clearRow(state, roleForDiscoveryMethod(candidate.method)) : state;
      return { ...cleared, selectedCandidate: null, confirmCredential: '', foundCandidateSysidCollision: null, sourceMode: null, step: 'source' };
    }),
    on(OnboardingPageActions.attachExistingFromConfirm, (state) => ({ ...state, attachTarget: 'existing', step: 'attach' })),

    // --- Discover (Sight) / Listen for drones (Sense) ---
    on(OnboardingPageActions.scanTimeoutSet, (state, { value }) => ({ ...state, scanTimeout: value })),
    on(OnboardingPageActions.scanRequested, (state) => ({ ...state, scanning: true })),
    on(OnboardingApiActions.scanSucceeded, (state, { result }) => ({ ...state, scanResult: result, scanning: false })),
    on(OnboardingApiActions.scanFailed, (state) => ({ ...state, scanning: false })),
    on(OnboardingPageActions.droneScanRequested, (state) => ({ ...state, droneScanning: true })),
    on(OnboardingApiActions.droneScanSucceeded, (state, { result }) => ({ ...state, droneScanResult: result, droneScanning: false })),
    on(OnboardingApiActions.droneScanFailed, (state) => ({ ...state, droneScanning: false })),

    // --- Guided drone config ---
    on(OnboardingPageActions.droneFirmwareChosen, (state, { firmware }) => ({ ...state, droneFirmware: firmware })),
    on(OnboardingPageActions.droneLinkChosen, (state, { link }) => ({ ...state, droneLink: link })),
    on(OnboardingPageActions.selectedServerAddressSet, (state, { address }) => ({ ...state, selectedServerAddress: address })),
    on(OnboardingPageActions.continueToDroneConfig, (state) => {
      const compat = state.droneFirmware && state.droneLink ? linkCompatibility(state.droneFirmware, state.droneLink) : null;
      return compat?.level === 'no-go' ? state : { ...state, droneSubStep: 'config' };
    }),
    on(OnboardingPageActions.backFromDroneConfig, (state) => ({ ...state, droneSubStep: 'picker' })),

    // --- Legacy whole-vehicle Simulate ---
    on(OnboardingPageActions.simModeSet, (state, { mode }) => ({ ...state, simMode: mode })),
    on(OnboardingPageActions.simVideoPathSet, (state, { value }) => ({ ...state, simVideoPath: value })),
    on(OnboardingPageActions.simLatitudeSet, (state, { value }) => ({ ...state, simLatitude: value })),
    on(OnboardingPageActions.simLongitudeSet, (state, { value }) => ({ ...state, simLongitude: value })),
    on(OnboardingPageActions.simAutoStartSet, (state, { value }) => ({ ...state, simAutoStart: value })),
    on(OnboardingPageActions.flightPlanDialogOpened, (state) => ({ ...state, flightPlanDialogOpen: true })),
    on(OnboardingPageActions.flightPlanSaved, (state, { plan }) => ({ ...state, flightPlan: plan, flightPlanDialogOpen: false })),
    on(OnboardingPageActions.flightPlanCancelled, (state) => ({ ...state, flightPlanDialogOpen: false })),
    on(OnboardingPageActions.flightPlanCleared, (state) => ({ ...state, flightPlan: undefined })),

    // --- Waiting room ---
    on(OnboardingPageActions.discoveryStatusRequested, (state, { nowMs }) => ({ ...state, nowMs })),
    on(OnboardingApiActions.discoveryStatusSucceeded, (state, { discoveryStatus }) => ({ ...state, discoveryStatus })),
    // `discoveryStatusFailed` — silent-degrade (CLAUDE.md), `discoveryStatus` stays exactly as it was.

    // --- Prove ---
    on(OnboardingPageActions.testRowRequested, (state, { role }) => updateProve(state, role, { probing: true, lastProbeError: null })),
    on(OnboardingApiActions.testRowSucceeded, (state, { role, request, result }) =>
      updateProve(state, role, {
        probing: false,
        lastProbeRequest: request,
        lastProbeResult: result,
        lastProbeError: result.ok ? null : 'The device did not report success — check the warnings below.',
      }),
    ),
    on(OnboardingApiActions.testRowFailed, (state, { role, request, error }) =>
      updateProve(state, role, { probing: false, lastProbeRequest: request, lastProbeResult: null, lastProbeError: error }),
    ),
    on(OnboardingPageActions.verifyRowRequested, (state, { role }) =>
      updateProve(state, role, { verifying: true, lastVerifyError: null, verifyDisabled: false, sysidCollision: null, sysidParameterToWrite: null }),
    ),
    on(OnboardingApiActions.verifyRowSucceeded, (state, { role, request, result }) =>
      updateProve(state, role, { verifying: false, lastVerifyRequest: request, lastVerifyResult: result }),
    ),
    on(OnboardingApiActions.verifyRowFailed, (state, { role, request, error, disabled }) =>
      updateProve(state, role, { verifying: false, lastVerifyRequest: request, lastVerifyResult: null, verifyDisabled: disabled, lastVerifyError: disabled ? null : error }),
    ),
    on(OnboardingApiActions.sysidCollisionChecked, (state, { role, collision, parameterToWrite }) =>
      updateProve(state, role, { sysidCollision: collision, sysidParameterToWrite: parameterToWrite }),
    ),
    // `sysidCollisionCheckFailed` — silent-degrade, no handler (mirrors `detectSysidCollisionQuietly`).

    // --- Attach ---
    on(OnboardingPageActions.attachTargetChosen, (state, { target }) => ({ ...state, attachTarget: target })),
    on(OnboardingPageActions.existingAssetsRequested, (state) => ({ ...state, existingAssetsLoading: true })),
    on(OnboardingApiActions.existingAssetsSucceeded, (state, { assets }) => ({ ...state, existingAssets: assets, existingAssetsLoading: false })),
    on(OnboardingApiActions.existingAssetsFailed, (state) => ({ ...state, existingAssetsLoading: false })),
    on(OnboardingPageActions.selectedExistingAssetIdSet, (state, { assetId }) => ({ ...state, selectedExistingAssetId: assetId })),
    on(OnboardingPageActions.createFlowStarted, (state) => ({ ...state, creating: true })),
    on(OnboardingPageActions.createFlowSettled, (state) => ({ ...state, creating: false })),
    on(OnboardingPageActions.existingAssetPathSet, (state) => ({ ...state, existingAssetPath: true })),
    on(OnboardingPageActions.foundCandidateCollisionApplied, (state, { collision }) => ({ ...state, foundCandidateSysidCollision: collision })),
    // `createAssetRequested`/`Succeeded`/`Failed`, `updateAssetEditRequested`/`Succeeded`/`Failed`,
    // `registerAndAssignRequested`/`Succeeded`/`Failed` and `uploadAssetImageRequested`/`Succeeded`/
    // `Failed` need no reducer case at all — like `FleetApiActions`'s own identical mutations, they
    // exist only so `dispatchAndAwait` has a value to resolve `OnboardingWizardFacade`'s orchestration
    // methods with; `createFlowStarted`/`Settled` and `sysidStepEntered`/`handoverStepEntered` already
    // carry every bit of this flow that is genuinely wizard *state*.
    on(OnboardingPageActions.sysidStepEntered, (state, { assetId, displayName, prefillSysidValue }) => ({
      ...state,
      step: 'sysid',
      createdAssetId: assetId,
      createdAssetDisplayName: displayName,
      sysidValue: prefillSysidValue !== null ? prefillSysidValue : state.sysidValue,
    })),
    on(OnboardingPageActions.handoverStepEntered, (state, { assetId, displayName }) => ({
      ...state,
      step: 'handover',
      createdAssetId: assetId,
      createdAssetDisplayName: displayName,
      handoverOutcome: null,
      handoverError: null,
    })),

    // --- Sysid ---
    on(OnboardingPageActions.sysidValueSet, (state, { value }) => ({ ...state, sysidValue: value })),
    on(OnboardingPageActions.writeSysidRequested, (state) => ({ ...state, writingSysid: true, sysidWriteError: null })),
    on(OnboardingApiActions.writeSysidSucceeded, (state, { result }) => ({ ...state, sysidWriteResult: result, writingSysid: false })),
    on(OnboardingApiActions.writeSysidFailed, (state, { error }) => ({ ...state, sysidWriteResult: null, sysidWriteError: error, writingSysid: false })),

    // --- Hand-over ---
    on(OnboardingPageActions.pilotCandidatesRequested, (state, { group }) => ({ ...state, pilotCandidatesLoading: true, ownerGroupName: group?.groupName })),
    on(OnboardingApiActions.pilotCandidatesSucceeded, (state, { candidates, defaultCustodianId }) => ({
      ...state,
      pilotCandidates: candidates,
      selectedCustodianId: defaultCustodianId,
      pilotCandidatesLoading: false,
    })),
    on(OnboardingApiActions.pilotCandidatesFailed, (state) => ({ ...state, pilotCandidates: [], pilotCandidatesLoading: false })),
    on(OnboardingPageActions.custodianSelected, (state, { userId }) => ({ ...state, selectedCustodianId: userId })),
    on(OnboardingPageActions.handoverLocationSet, (state, { value }) => ({ ...state, handoverLocation: value })),
    on(OnboardingPageActions.setAssetCustodyRequested, (state) => ({ ...state, handingOver: true, handoverError: null })),
    on(OnboardingApiActions.setAssetCustodySucceeded, (state) => ({ ...state, handingOver: false })),
    on(OnboardingApiActions.setAssetCustodyFailed, (state, { error }) => ({ ...state, handingOver: false, handoverError: error })),
    on(OnboardingPageActions.handoverOutcomeSet, (state, { outcome }) => ({ ...state, handoverOutcome: outcome })),
    on(OnboardingPageActions.leaveInStock, (state) => ({ ...state, handoverOutcome: 'stocked' })),

    // --- Identify: category options / system network ---
    on(OnboardingApiActions.categoryOptionsSucceeded, (state, { categoryOptions, categories }) => ({ ...state, categoryOptions, categories })),
    // `categoryOptionsFailed` — silent-degrade, no handler.
    on(OnboardingApiActions.systemNetworkSucceeded, (state, { addresses, mavlinkPort, videoPushPort, videoPushPathPrefix }) => ({
      ...state,
      networkAddresses: addresses,
      mavlinkPort,
      videoPushPort,
      videoPushPathPrefix,
      selectedServerAddress: addresses.length > 0 ? addresses[0].address : state.selectedServerAddress,
    })),
    // `systemNetworkFailed` — silent-degrade, no handler.
    on(OnboardingApiActions.candidateQueryPrefillSucceeded, (state, { candidate }) => ({
      ...applyCandidatePrefill(state, candidate),
      originCandidateId: candidate.id, // set unconditionally — mirrors `OnboardingStore#applyCandidateQueryPrefill`'s own quirk.
    })),
    // `candidateQueryPrefillFailed` — silent-degrade (covers both a transport failure and "candidate not found").

    // --- Navigation ---
    on(OnboardingPageActions.stepAdvanced, (state) => (canAdvanceOf(state) ? { ...state, step: nextStep(state.step, stepContextOf(state)) } : state)),
    on(OnboardingPageActions.stepBack, (state) => ({ ...state, step: prevStep(state.step, stepContextOf(state)) })),
    on(OnboardingPageActions.stepJumped, (state, { step }) => (state.createdAssetId ? state : { ...state, step })),
  ),
  extraSelectors: ({
    selectRows,
    selectPreProvenRoles,
    selectEquipmentConfirmed,
    selectStep,
    selectDisplayName,
    selectCategory,
    selectCategories,
    selectCategoryOptions,
    selectDroneFirmware,
    selectDroneLink,
    selectSelectedServerAddress,
    selectMavlinkPort,
    selectNetworkAddresses,
    selectVideoPushPort,
    selectVideoPushPathPrefix,
    selectProveByRole,
  }) => {
    const selectEquipment = createSelector(selectRows, isEquipmentPath);
    const selectCategoryConnected = createSelector(
      selectCategories,
      selectCategory,
      (categories, category) => categories.find((c) => c.slug === category)?.connected ?? true,
    );
    const selectIdentifyCategoryOptions = createSelector(selectCategoryOptions, selectEquipment, selectCategories, (options, equipment, categories) => {
      if (!equipment) {
        return options;
      }
      const bySlug = new Map(categories.map((c) => [c.slug, c] as const));
      return options.filter((option) => bySlug.get(option.slug)?.connected === false);
    });
    const selectCanAdvanceIdentify = createSelector(selectDisplayName, selectCategory, canAdvanceFromIdentify);
    const selectCanAdvanceFromSource = createSelector(selectRows, selectEquipmentConfirmed, canAdvanceFromFitOut);
    const selectRowsNeedProve = createSelector(
      selectRows,
      selectPreProvenRoles,
      (rows, preProven) => FIT_OUT_ROLES.some((role) => rows[role].value === 'find' && !preProven.includes(role)),
    );
    const selectCanAdvanceProve = createSelector(selectRows, selectPreProvenRoles, selectProveByRole, (rows, preProven, prove) =>
      FIT_OUT_ROLES.filter((role) => rows[role].value === 'find' && !preProven.includes(role)).every(
        (role) => isLastProbeOk(currentProbeRequest(rows[role]), prove[role].lastProbeRequest, prove[role].lastProbeResult) === true,
      ),
    );
    const selectDroneCompatibility = createSelector(selectDroneFirmware, selectDroneLink, (firmware, link) =>
      firmware && link ? linkCompatibility(firmware, link) : null,
    );
    const selectDroneConfigBlocks = createSelector(
      selectDroneFirmware,
      selectDroneLink,
      selectSelectedServerAddress,
      selectMavlinkPort,
      (firmware, link, address, port) => {
        const trimmed = address.trim();
        return !firmware || !link || trimmed.length === 0 ? [] : configSnippets(firmware, link, trimmed, port);
      },
    );
    const selectPushAddressCard = createSelector(selectNetworkAddresses, selectVideoPushPort, selectVideoPushPathPrefix, composePushAddress);
    const selectCanAdvance = createSelector(
      selectStep,
      selectCanAdvanceFromSource,
      selectCanAdvanceProve,
      selectCanAdvanceIdentify,
      (step, canAdvanceFromSource, canAdvanceProve, canAdvanceIdentify): boolean => {
        switch (step) {
          case 'source':
            return canAdvanceFromSource;
          case 'prove':
            return canAdvanceProve;
          case 'identify':
            return canAdvanceIdentify;
          default:
            return false;
        }
      },
    );
    return {
      selectEquipment,
      selectCategoryConnected,
      selectIdentifyCategoryOptions,
      selectCanAdvanceIdentify,
      selectCanAdvanceFromSource,
      selectRowsNeedProve,
      selectCanAdvanceProve,
      selectDroneCompatibility,
      selectDroneConfigBlocks,
      selectPushAddressCard,
      selectCanAdvance,
    };
  },
});
