import type {
  AssetSummary,
  Category,
  DiscoveryCandidate,
  DiscoveryStatusResponse,
  NetworkAddress,
  ParameterWriteResponse,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  ScanResult,
  UserSummary,
  VehicleProfile,
} from '../../../core/api/models';
import { emptyFitOutRows, type FitOutRole, type FitOutRows } from '../../../core/onboarding/fit-out-logic';
import type { SimulateMode } from '../../../core/fleet/simulation-logic';
import type { Firmware, LinkType } from '../drone-config-logic';
import type { SourceMode, WizardStep } from '../onboarding-logic';
import type { FlightPlanForm } from '../../../shared/map/flight-plan-logic';

/**
 * `OnboardingWizardFacade`'s own slice (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N8b, replacing
 * `OnboardingStore`) — page-provided on `OnboardingPage`, not root-registered (see
 * `onboarding.providers.ts`'s own doc comment): wizard state has no reason to survive leaving
 * `/add-source`.
 *
 * **What stays out of this state (§3 rule 11 — runtime checks stay on)**: `File`/`Blob`/the photo's
 * object-URL live in `OnboardingPhotoBuffer` (a small page-provided service, the `GlobalOverlayStore`
 * split precedent) — only the serializable outcome (`photoProcessing`/`photoError`) reaches here.
 * `preProvenRoles` was a `ReadonlySet<FitOutRole>` on the old store; here it is a plain
 * `readonly FitOutRole[]`, deduped in the reducer. The discovery-status poll's `PollScheduler` handle
 * and the `stopDiscoveryStatusPoll` teardown closure never were state — they are recreated as an
 * effect (`onboarding.effects.ts#discoveryStatusPoll$`) gated on `step === 'source'`.
 * `WebSerialGateway#isSupported()` is a synchronous capability probe, exposed as a facade-level
 * `computed()` rather than a stored fact — see `OnboardingWizardFacade`'s own doc comment.
 */
export interface OnboardingWizardState {
  readonly step: WizardStep;

  // --- Identify ---
  readonly displayName: string;
  readonly registrationNumber: string;
  readonly serialNumber: string;
  readonly make: string;
  readonly model: string;
  readonly category: string;
  readonly categoryOptions: readonly { readonly slug: string; readonly name: string }[];
  readonly categories: readonly Category[];
  readonly photoProcessing: boolean;
  readonly photoError: string | null;

  // --- Source / fit-out rows ---
  readonly rows: FitOutRows;
  readonly sourceMode: SourceMode | null;
  readonly equipmentConfirmed: boolean;
  readonly preProvenRoles: readonly FitOutRole[];
  readonly originCandidateId: string | null;
  readonly selectedCandidate: DiscoveryCandidate | null;
  readonly confirmCredential: string;
  readonly foundCandidateSysidCollision: number | null;

  // --- Discover (Sight) ---
  readonly scanTimeout: number;
  readonly scanning: boolean;
  readonly scanResult: ScanResult | null;

  // --- Listen for drones (Sense) ---
  readonly droneScanning: boolean;
  readonly droneScanResult: ScanResult | null;

  // --- Guided drone config ---
  readonly droneSubStep: 'picker' | 'config';
  readonly droneFirmware: Firmware | null;
  readonly droneLink: LinkType | null;
  readonly networkAddresses: readonly NetworkAddress[];
  readonly mavlinkPort: number;
  readonly selectedServerAddress: string;
  readonly videoPushPort: number | undefined;
  readonly videoPushPathPrefix: string | undefined;

  // --- Legacy whole-vehicle Simulate ---
  readonly simMode: SimulateMode;
  readonly simVideoPath: string;
  readonly simLatitude: number | null;
  readonly simLongitude: number | null;
  readonly simAutoStart: boolean;
  readonly flightPlanDialogOpen: boolean;
  readonly flightPlan: FlightPlanForm | undefined;

  // --- Waiting room ---
  readonly discoveryStatus: DiscoveryStatusResponse | null;
  readonly nowMs: number;

  // --- Prove ---
  readonly proveByRole: Readonly<Record<FitOutRole, RowProveState>>;

  // --- Attach ---
  readonly creating: boolean;
  readonly attachTarget: 'new' | 'existing';
  readonly existingAssets: readonly AssetSummary[];
  readonly existingAssetsLoading: boolean;
  readonly selectedExistingAssetId: string | null;
  readonly existingAssetPath: boolean;

  // --- Sysid ---
  readonly writingSysid: boolean;
  readonly sysidWriteResult: ParameterWriteResponse | null;
  readonly sysidWriteError: string | null;
  readonly sysidValue: number | null;

  // --- Hand-over ---
  readonly createdAssetId: string | null;
  readonly createdAssetDisplayName: string;
  readonly pilotCandidates: readonly UserSummary[];
  readonly pilotCandidatesLoading: boolean;
  readonly ownerGroupName: string | undefined;
  readonly selectedCustodianId: string | null;
  readonly handoverLocation: string;
  readonly handingOver: boolean;
  readonly handoverError: string | null;
  readonly handoverOutcome: 'issued' | 'stocked' | null;
}

/** One fit-out row's Prove state — see `OnboardingStore`'s identical interface, moved here verbatim. */
export interface RowProveState {
  readonly probing: boolean;
  readonly lastProbeRequest: ProbeDeviceRequest | null;
  readonly lastProbeResult: ProbeDeviceResult | null;
  readonly lastProbeError: string | null;
  readonly verifying: boolean;
  readonly lastVerifyRequest: ProbeCandidateRequest | null;
  readonly lastVerifyResult: VehicleProfile | null;
  readonly lastVerifyError: string | null;
  readonly verifyDisabled: boolean;
  readonly sysidCollision: number | null;
  readonly sysidParameterToWrite: 'MAV_SYSID' | 'SYSID_THISMAV' | null;
}

export function emptyRowProveState(): RowProveState {
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

/** Scan durations worth offering — mirrors the pre-wizard Devices page's own choice exactly. */
export const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/** `mavlinkPort`'s initial value until `GET /api/system/network` resolves — see `OnboardingStore`'s identical constant doc comment. */
export const DEFAULT_MAVLINK_PORT = 14_550;

/** The waiting room's own poll cadence — see `OnboardingStore`'s identical `DISCOVERY_STATUS_POLL_MS` doc comment. */
export const DISCOVERY_STATUS_POLL_MS = 3_000;

/** Shown under the legacy Simulate mode selector — one sentence per mode. */
export const SIMULATE_MODE_HINTS: Record<SimulateMode, string> = {
  direct: 'Plays the file straight through the pipeline — the simplest way to see it work.',
  rtsp: 'Rehearse the real protocol path: the platform transmits your file over RTSP and ingests it back like real hardware.',
  synthetic: 'No file needed — creates a still, pattern-only test asset with no telemetry.',
  testDrone: 'No file needed — places a moving drone on a circular flight path around a home point, watchable immediately.',
};

export const initialOnboardingWizardState: OnboardingWizardState = {
  step: 'source',

  displayName: '',
  registrationNumber: '',
  serialNumber: '',
  make: '',
  model: '',
  category: '',
  categoryOptions: [],
  categories: [],
  photoProcessing: false,
  photoError: null,

  rows: emptyFitOutRows(),
  sourceMode: null,
  equipmentConfirmed: false,
  preProvenRoles: [],
  originCandidateId: null,
  selectedCandidate: null,
  confirmCredential: '',
  foundCandidateSysidCollision: null,

  scanTimeout: 4_000,
  scanning: false,
  scanResult: null,

  droneScanning: false,
  droneScanResult: null,

  droneSubStep: 'picker',
  droneFirmware: null,
  droneLink: null,
  networkAddresses: [],
  mavlinkPort: DEFAULT_MAVLINK_PORT,
  selectedServerAddress: '',
  videoPushPort: undefined,
  videoPushPathPrefix: undefined,

  simMode: 'direct',
  simVideoPath: '',
  simLatitude: null,
  simLongitude: null,
  simAutoStart: true,
  flightPlanDialogOpen: false,
  flightPlan: undefined,

  discoveryStatus: null,
  // Set once by the discovery-status poll's own first tick (an effect, per §3 rule 3 — no `Date.now()`
  // in a reducer); a route-scoped slice's `initialState` module can be evaluated once and reused
  // across repeat navigations within one session, so a `Date.now()` literal here would go stale.
  nowMs: 0,

  proveByRole: { sense: emptyRowProveState(), sight: emptyRowProveState() },

  creating: false,
  attachTarget: 'new',
  existingAssets: [],
  existingAssetsLoading: false,
  selectedExistingAssetId: null,
  existingAssetPath: false,

  writingSysid: false,
  sysidWriteResult: null,
  sysidWriteError: null,
  sysidValue: null,

  createdAssetId: null,
  createdAssetDisplayName: '',
  pilotCandidates: [],
  pilotCandidatesLoading: false,
  ownerGroupName: undefined,
  selectedCustodianId: null,
  handoverLocation: '',
  handingOver: false,
  handoverError: null,
  handoverOutcome: null,
};
