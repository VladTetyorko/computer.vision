import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type {
  AssetEdit,
  AssetSummary,
  Category,
  CreateAssetDeviceSpec,
  CreateAssetRequest,
  DiscoveredDevice,
  DiscoveryCandidate,
  DiscoveryStatusResponse,
  Membership,
  NetworkAddress,
  ParameterWriteRequest,
  ParameterWriteResponse,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  ScanResult,
  UserSummary,
  VehicleProfile,
} from '../../../core/api/models';
import type { FitOutRole, FitOutRowValue } from '../../../core/onboarding/fit-out-logic';
import type { SimulateMode } from '../../../core/fleet/simulation-logic';
import type { Firmware, LinkType } from '../drone-config-logic';
import type { FlightPlanForm } from '../../../shared/map/flight-plan-logic';
import type { SourceMode, WizardStep } from '../onboarding-logic';

/**
 * Every wizard-page-initiated event (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N8b) — sync field
 * edits, navigation, and the `Requested` half of every async operation. `OnboardingWizardFacade` is
 * the only dispatcher; the six step components never dispatch directly (they call facade methods,
 * exactly as they called `OnboardingStore` methods before).
 */
export const OnboardingPageActions = createActionGroup({
  source: 'Onboarding Page',
  events: {
    // --- Identify ---
    'Display Name Changed': props<{ value: string }>(),
    'Registration Number Changed': props<{ value: string }>(),
    'Serial Number Changed': props<{ value: string }>(),
    'Make Changed': props<{ value: string }>(),
    'Model Changed': props<{ value: string }>(),
    'Category Chosen': props<{ slug: string }>(),
    'Photo Processing Started': emptyProps(),
    /** A local (no HTTP) outcome fact, not an "API" event — `OnboardingPhotoBuffer#choose` never calls `VisionApi`. */
    'Photo Accepted': emptyProps(),
    'Photo Rejected': props<{ error: string }>(),
    'Photo Removed': emptyProps(),

    // --- Source / fit-out rows ---
    'Row Value Set': props<{ role: FitOutRole; value: FitOutRowValue }>(),
    'Row Find Chosen': props<{ role: FitOutRole; method: 'listen' | 'drone' | 'register' | 'discover' }>(),
    'Row Find Back': props<{ role: FitOutRole }>(),
    'Row Cleared': props<{ role: FitOutRole }>(),
    'Row Protocol Selected': props<{ role: FitOutRole; value: string }>(),
    'Row Custom Protocol Set': props<{ role: FitOutRole; value: string }>(),
    'Row Uri Set': props<{ role: FitOutRole; value: string }>(),
    'Row Option Added': props<{ role: FitOutRole }>(),
    'Row Option Removed': props<{ role: FitOutRole; index: number }>(),
    'Row Option Key Updated': props<{ role: FitOutRole; index: number; key: string }>(),
    'Row Option Value Updated': props<{ role: FitOutRole; index: number; value: string }>(),
    /** Sight row's `discover` finder — `OnboardingStore#useCandidate`. */
    'Sight Candidate Chosen': props<{ candidate: DiscoveredDevice }>(),
    /** Sense row's `listen` finder — `OnboardingStore#useDroneVehicle`; a no-op in the reducer for an already-claimed vehicle. */
    'Sense Drone Candidate Chosen': props<{ candidate: DiscoveredDevice }>(),
    /** The waiting room's live "Use" action, and the `?candidateId=` query-param entrance — `OnboardingStore#useHeardCandidate`/`prefillRowFromCandidate`. */
    'Row Prefilled From Candidate': props<{ candidate: DiscoveryCandidate }>(),
    'Source Mode Chosen': props<{ mode: SourceMode }>(),
    'Back To Source Fork': emptyProps(),

    // --- "Found nearby" feed + Confirm interstitial ---
    'Found Candidate Chosen': props<{ candidate: DiscoveryCandidate }>(),
    'Confirm Credential Set': props<{ value: string }>(),
    'Continue From Confirm': emptyProps(),
    'Back From Confirm': emptyProps(),
    'Attach Existing From Confirm': emptyProps(),

    // --- Discover (Sight) / Listen for drones (Sense) ---
    'Scan Timeout Set': props<{ value: number }>(),
    'Scan Requested': props<{ timeoutMs: number }>(),
    'Drone Scan Requested': emptyProps(),

    // --- Guided drone config ---
    'Drone Firmware Chosen': props<{ firmware: Firmware }>(),
    'Drone Link Chosen': props<{ link: LinkType }>(),
    'Selected Server Address Set': props<{ address: string }>(),
    'Continue To Drone Config': emptyProps(),
    'Back From Drone Config': emptyProps(),

    // --- Legacy whole-vehicle Simulate ---
    'Sim Mode Set': props<{ mode: SimulateMode }>(),
    'Sim Video Path Set': props<{ value: string }>(),
    'Sim Latitude Set': props<{ value: number | null }>(),
    'Sim Longitude Set': props<{ value: number | null }>(),
    'Sim Auto Start Set': props<{ value: boolean }>(),
    'Flight Plan Dialog Opened': emptyProps(),
    'Flight Plan Saved': props<{ plan: FlightPlanForm }>(),
    'Flight Plan Cancelled': emptyProps(),
    'Flight Plan Cleared': emptyProps(),

    // --- Prove ---
    'Test Row Requested': props<{ role: FitOutRole; request: ProbeDeviceRequest }>(),
    'Verify Row Requested': props<{ role: FitOutRole; request: ProbeCandidateRequest }>(),

    // --- Attach ---
    'Attach Target Chosen': props<{ target: 'new' | 'existing' }>(),
    'Existing Assets Requested': emptyProps(),
    'Selected Existing Asset Id Set': props<{ assetId: string }>(),
    /** Brackets the whole `createAsset()`/`attachToExistingAsset()` orchestration — mirrors the old
     *  store's shared `creating` flag, set by whichever of the two entry points actually runs. */
    'Create Flow Started': emptyProps(),
    'Create Flow Settled': emptyProps(),
    'Existing Asset Path Set': emptyProps(),
    /** `OnboardingStore#applyFoundCandidateCollision` — a plain fact derived from a register/attach response. */
    'Found Candidate Collision Applied': props<{ collision: number | null }>(),
    'Create Asset Requested': props<{ request: CreateAssetRequest }>(),
    'Update Asset Edit Requested': props<{ assetId: string; edit: AssetEdit }>(),
    'Register And Assign Requested': props<{ assetId: string; specs: readonly CreateAssetDeviceSpec[] }>(),
    'Upload Asset Image Requested': props<{ assetId: string; displayName: string }>(),
    /** The wizard's own "ready" fact (`OnboardingStore#finishCreate`'s toast site) — fires the generic success toast independent of which creation branch ran. */
    'Sysid Step Entered': props<{ assetId: string; displayName: string; prefillSysidValue: number | null }>(),
    'Handover Step Entered': props<{ assetId: string; displayName: string }>(),

    // --- Sysid ---
    'Sysid Value Set': props<{ value: number | null }>(),
    'Write Sysid Requested': props<{ assetId: string; request: ParameterWriteRequest }>(),

    // --- Hand-over ---
    'Pilot Candidates Requested': props<{ group: Membership | undefined; creatorUserId: string | undefined }>(),
    'Custodian Selected': props<{ userId: string | null }>(),
    'Handover Location Set': props<{ value: string }>(),
    'Set Asset Custody Requested': props<{ assetId: string; custodianId: string; location: string }>(),
    'Handover Outcome Set': props<{ outcome: 'issued' | 'stocked' }>(),
    'Leave In Stock': emptyProps(),

    // --- Waiting room + navigation ---
    'Category Options Requested': emptyProps(),
    'System Network Requested': emptyProps(),
    'Candidate Query Prefill Requested': props<{ candidateId: string }>(),
    'Discovery Status Requested': props<{ nowMs: number }>(),
    'Step Advanced': emptyProps(),
    'Step Back': emptyProps(),
    'Step Jumped': props<{ step: WizardStep }>(),
  },
});

/** Every server (or local-async-operation) response — see individual doc comments for which. */
export const OnboardingApiActions = createActionGroup({
  source: 'Onboarding API',
  events: {
    /** Silent-degrade (`OnboardingStore#loadCategoryOptions`) — no `Failed` handler needed beyond clearing nothing. */
    'Category Options Succeeded': props<{ categoryOptions: readonly { slug: string; name: string }[]; categories: readonly Category[] }>(),
    'Category Options Failed': emptyProps(),

    'System Network Succeeded': props<{
      addresses: readonly NetworkAddress[];
      mavlinkPort: number;
      videoPushPort: number | undefined;
      videoPushPathPrefix: string | undefined;
    }>(),
    'System Network Failed': emptyProps(),

    'Candidate Query Prefill Succeeded': props<{ candidate: DiscoveryCandidate }>(),
    /** Also the "candidate not found" case — silent-degrade, matches `OnboardingStore#applyCandidateQueryPrefill`. */
    'Candidate Query Prefill Failed': emptyProps(),

    'Discovery Status Succeeded': props<{ discoveryStatus: DiscoveryStatusResponse }>(),
    'Discovery Status Failed': emptyProps(),

    'Scan Succeeded': props<{ result: ScanResult }>(),
    'Scan Failed': props<{ error: string }>(),
    'Drone Scan Succeeded': props<{ result: ScanResult }>(),
    'Drone Scan Failed': props<{ error: string }>(),

    'Test Row Succeeded': props<{ role: FitOutRole; request: ProbeDeviceRequest; result: ProbeDeviceResult }>(),
    'Test Row Failed': props<{ role: FitOutRole; request: ProbeDeviceRequest; error: string }>(),
    'Verify Row Succeeded': props<{ role: FitOutRole; request: ProbeCandidateRequest; result: VehicleProfile }>(),
    'Verify Row Failed': props<{ role: FitOutRole; request: ProbeCandidateRequest; error: string | null; disabled: boolean }>(),
    /** Chained automatically off `Verify Row Succeeded` — never operator-initiated. Silent-degrade. */
    'Sysid Collision Checked': props<{ role: FitOutRole; collision: number | null; parameterToWrite: 'MAV_SYSID' | 'SYSID_THISMAV' | null }>(),
    'Sysid Collision Check Failed': emptyProps(),

    'Existing Assets Succeeded': props<{ assets: readonly AssetSummary[] }>(),
    'Existing Assets Failed': props<{ error: string }>(),

    'Create Asset Succeeded': props<{ assetId: string; displayName: string }>(),
    'Create Asset Failed': props<{ error: string }>(),
    'Update Asset Edit Succeeded': props<{ displayName: string }>(),
    'Update Asset Edit Failed': props<{ error: string }>(),
    'Register And Assign Succeeded': emptyProps(),
    'Register And Assign Failed': props<{ error: string }>(),
    'Upload Asset Image Succeeded': props<{ displayName: string }>(),
    'Upload Asset Image Failed': props<{ displayName: string; error: string }>(),

    'Pilot Candidates Succeeded': props<{ candidates: readonly UserSummary[]; defaultCustodianId: string | null }>(),
    /** Silent-degrade (`GET /api/users` may 403) — `pilotCandidates` resets to `[]`, `ownerGroupName` stays whatever `Pilot Candidates Requested` already set. */
    'Pilot Candidates Failed': emptyProps(),
    'Set Asset Custody Succeeded': emptyProps(),
    'Set Asset Custody Failed': props<{ error: string }>(),
    'Write Sysid Succeeded': props<{ result: ParameterWriteResponse }>(),
    'Write Sysid Failed': props<{ error: string }>(),
  },
});
