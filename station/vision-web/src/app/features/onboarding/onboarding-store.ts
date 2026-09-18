import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { AuthStore } from '../../core/auth/auth-store';
import { FleetStore } from '../../core/fleet/fleet-store';
import { DiscoveryInboxStore } from '../../core/discovery/discovery-inbox-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { WebSerialGateway } from '../provisioning/web-serial-gateway';
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
  AssetSummary,
  Category,
  CreateAssetRequest,
  DiscoveredDevice,
  DiscoveryCandidate,
  DiscoveryStatusResponse,
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
  FIT_OUT_ROLES,
  FIT_OUT_ROLE_HINTS,
  FIT_OUT_ROLE_LABELS,
  canAdvanceFromFitOut,
  collectRowOptions,
  combinedSysidCollision,
  effectiveProtocol,
  emptyFitOutRow,
  emptyFitOutRows,
  fitOutDeviceSpecs,
  isRowFilled,
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
  composePushAddress,
  isEquipmentPath,
  isTelemetryOnlyProtocol,
  nextStep,
  prefillFromDiscoveryCandidate,
  prevStep,
  roleForDiscoveryMethod,
  type IdentifyDraft,
  type SourceMode,
  type StepContext,
  type WizardStep,
} from './onboarding-logic';
// The Hand-over step's own roster rule lives in `core/` since it grew a second consumer —
// the Inventory page's Issue dialog (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5, wave W4).
import { creatorOwnershipGroup, defaultPilotSelection, pilotsInGroup } from '../../core/org/pilot-logic';

/** Scan durations worth offering — mirrors the pre-wizard Devices page's own choice exactly. */
const SCAN_TIMEOUTS = [2_000, 4_000, 8_000] as const;

/**
 * The MAVLink heartbeat scanner's well-known listen port (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — used only
 * as `mavlinkPort`'s initial value until `GET /api/system/network` resolves, so the "configure your
 * drone" sub-step never renders with an empty port while the request is in flight. Every real value
 * comes from the network response itself (`SystemNetworkResponse#mavlinkPort`), never assumed.
 */
const DEFAULT_MAVLINK_PORT = 14_550;

/**
 * The waiting room's own poll cadence (§3.1, wave W3) — a multiple of `PollScheduler`'s 1s heartbeat
 * (its own doc comment). Snappier than `DiscoveryInboxStore`'s 30s inbox-sweep floor (matches the
 * backend's own sweep cadence, `vision.discovery.inbox.sweep-seconds`) since this is an
 * actively-watched screen — an operator staring at "listening… nothing yet" notices a 30s lag; a
 * one-off `GET /api/discovery/status` summary is cheap enough that 3s doesn't meaningfully load the
 * backend, and this poll only ever runs while the `source` step itself is on screen (see the
 * `step()` effect in the constructor).
 */
const DISCOVERY_STATUS_POLL_MS = 3_000;

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
 * The onboarding wizard's own "component store" (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * §3.1, wave W2+W3 — replaces the pre-W2 single-fork-less wizard). Provided per-route on
 * `OnboardingPage` (`providers: [OnboardingStore, OnboardingFacade]`, same DI-sharing idiom as
 * `AssetDetailPage`'s `TelemetryStore`/`DetectionsStore`), not `providedIn: 'root'`: wizard state has
 * no reason to survive leaving `/add-source`, and a fresh instance per visit means a second pass
 * through the wizard never starts warm with a previous attempt's half-filled form.
 *
 * Holds every signal across all six steps (five visible, `sysid` hidden — see
 * `onboarding-logic.ts#WizardStep`) and orchestrates every HTTP call; every yes/no decision (can
 * this step advance?) and every request shape is delegated to the pure functions in
 * `onboarding-logic.ts`/`core/onboarding/fit-out-logic.ts` — this class is deliberately thin glue,
 * not where the interesting logic lives.
 *
 * **The fit-out table's two rows share one finder each**: `scan`/`useCandidate` (Sight's `discover`
 * finder) always act on the **Sight** row; `scanForDrones`/`useDroneVehicle`/the guided drone-config
 * sub-flow (`listen`/`drone`) always act on the **Sense** row. `FIT_OUT_FIND_METHODS` in
 * `fit-out-logic.ts` fixes the mapping — every one of those methods keeps its pre-W2 body, only
 * writing into that row's own slice of `rows`.
 *
 * **The `source` step's fork is a view-selector layered on top of the same rows** (`sourceMode`
 * below) — it decides which sub-UI an *unresolved* row renders (waiting room / register-form /
 * scanner), never overwriting a row the operator (or a discovery-candidate entrance) has already
 * resolved. See {@link chooseSourceMode}'s own doc comment.
 */
@Injectable()
export class OnboardingStore {
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthStore);
  private readonly discoveryInbox = inject(DiscoveryInboxStore);
  private readonly poll = inject(PollScheduler);
  private readonly webSerial = inject(WebSerialGateway);

  readonly step = signal<WizardStep>('source');

  // --- Step 1: Identify (docs/plans/active/WAREHOUSE-UX-PLAN.md D1 — name/category/photo, plus serial/make/model/registration) --

  readonly displayName = signal('');
  readonly registrationNumber = signal('');
  readonly serialNumber = signal('');
  readonly make = signal('');
  readonly model = signal('');
  readonly category = signal('');
  /** The picker's own `{slug,name}` options — see `loadCategoryOptions` below. */
  readonly categoryOptions = signal<readonly { slug: string; name: string }[]>([]);
  /** The raw category list (`GET /api/categories`), kept alongside `categoryOptions` only so {@link categoryConnected}/{@link identifyCategoryOptions} can read `Category#connected` — a fact the derived picker options don't carry. */
  private readonly categories = signal<readonly Category[]>([]);

  readonly photoFile = signal<File | null>(null);
  readonly photoBlob = signal<Blob | null>(null);
  readonly photoPreviewUrl = signal<string | null>(null);
  readonly photoProcessing = signal(false);
  readonly photoError = signal<string | null>(null);

  readonly canAdvanceIdentify = computed(() => canAdvanceFromIdentify(this.displayName(), this.category()));

  /** Whether the chosen category wraps at least one device (`Category#connected`) — defaults `true` when the category isn't in `categories()` yet (a fresh page load, or a category typed in ahead of the seed list). Feeds {@link identifyCategoryOptions} and the Hand-over end screen's own branch. */
  readonly categoryConnected = computed(() => this.categories().find((c) => c.slug === this.category())?.connected ?? true);

  /**
   * Whether both fit-out rows answered `—` at the `source` step's own "Nothing to connect" tile
   * (D3) — this, not `categoryConnected`, is what now drives the Identify→Hand-over short-circuit
   * (`onboarding-logic.ts#isEquipmentPath`'s own doc comment: removes the old circularity where
   * equipment-ness was a category fact chosen *after* Connect).
   */
  readonly equipment = computed(() => isEquipmentPath(this.rows()));

  /**
   * The category picker's own options on the equipment path (§3.1 scope item 2) — narrowed to
   * `connected: false` categories only, so an operator who has already said "nothing to connect"
   * isn't then offered a vehicle category the rest of this app assumes carries a device. A category
   * this app hasn't resolved a `connected` fact for at all (an asset-derived option not in
   * `categories()`) is excluded here too — an unconfirmed fact is not the same as a confirmed `false`.
   * Every other path (not equipment) sees the full, unfiltered list, unchanged.
   */
  readonly identifyCategoryOptions = computed(() => {
    const options = this.categoryOptions();
    if (!this.equipment()) {
      return options;
    }
    const bySlug = new Map(this.categories().map((c) => [c.slug, c] as const));
    return options.filter((option) => bySlug.get(option.slug)?.connected === false);
  });

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

  // --- Step: Source — the honest fork (D2/D3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §0.4)
  //     feeding the same fit-out table (`core/onboarding/fit-out-logic.ts`) the pre-W2 wizard's
  //     Connect step did. -------------------------------------------------------------------------

  readonly rows = signal<FitOutRows>(emptyFitOutRows());
  readonly fitOutRoleLabels = FIT_OUT_ROLE_LABELS;
  readonly fitOutRoleHints = FIT_OUT_ROLE_HINTS;
  readonly registerableProtocols = REGISTERABLE_PROTOCOLS;
  readonly customProtocolOption = CUSTOM_PROTOCOL_OPTION;

  /** Which of the four fork tiles is active — `null` while the tile grid itself is still showing.
   *  See {@link chooseSourceMode}'s own doc comment for the full contract. */
  readonly sourceMode = signal<SourceMode | null>(null);

  /** Set only by the fork's own "Nothing to connect" tile — {@link canAdvanceFromSource}'s own `equipmentConfirmed` argument (`fit-out-logic.ts#canAdvanceFromFitOut`'s own doc comment). */
  readonly equipmentConfirmed = signal(false);

  readonly canAdvanceFromSource = computed(() => canAdvanceFromFitOut(this.rows(), this.equipmentConfirmed()));

  /** Roles pre-proven by a discovery-inbox candidate pick (query-param entrance or the waiting
   *  room's own live "Use") — excluded from {@link effectiveNeedsProve} and {@link canAdvanceProve},
   *  per `onboarding-logic.ts`'s own `prove` step doc comment. Cleared the moment that role's
   *  connection fields are edited again (`clearPreProven`, called from every row mutator below) —
   *  a manual edit un-trusts the pre-proven claim, mirroring `lastProbeOk`'s own "stale fields"
   *  reasoning. */
  readonly preProvenRoles = signal<ReadonlySet<FitOutRole>>(new Set());

  private clearPreProven(role: FitOutRole): void {
    this.preProvenRoles.update((roles) => {
      if (!roles.has(role)) {
        return roles;
      }
      const next = new Set(roles);
      next.delete(role);
      return next;
    });
  }

  /** The one discovery-inbox candidate id this visit's `attach` step may still attach atomically
   *  (C1) — set only by the `?candidateId=` query-param entrance (never by the waiting room's own
   *  ad-hoc "Use" click, which carries no such "this candidate *is* that asset" promise). Also set by
   *  {@link chooseFoundCandidate} (docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4) — the "Found
   *  nearby" feed's own click-through carries exactly the same "this candidate *is* that asset"
   *  promise as the query-param entrance, and reuses this same field for it. */
  readonly originCandidateId = signal<string | null>(null);

  // --- "Found nearby" feed + Confirm interstitial (docs/plans/active/LINK-PAIRING-PLAN.md §3.7/§7,
  //     wave L4) — replaces the fork tiles' "It comes to us"/"We go to it" pair with one flat,
  //     always-visible feed of every discovery candidate; clicking a card jumps straight to the
  //     `confirm` interstitial rather than the fork's old per-role waiting room. -----------------

  /** The candidate a "Found nearby" card was clicked for — drives the `confirm` step's own facts
   *  panel. Cleared by {@link backFromConfirm}; left set through `identify`/`attach`/`sysid`/
   *  `handover` so `finishCreate` can still recover the client-side sysid heuristic. */
  readonly selectedCandidate = signal<DiscoveryCandidate | null>(null);

  /**
   * The Confirm screen's one optional typed field (task brief: "the ONE typed field only if the
   * probe was refused for credentials"). **Simplified, documented assumption**: there is no backend
   * signal that a probe was refused specifically for credentials (no such field exists on
   * `DiscoveryCandidate` and none is frozen by §3.3/§3.4), so this renders as an always-visible
   * optional disclosure rather than one conditionally triggered by a refusal this app cannot
   * observe — filled in only when the operator knows the device needs one. Sent as an additional
   * `password` device option (harmless/ignored by any adapter that doesn't read it) via
   * {@link chooseFoundCandidate}'s own row prefill, merged in by {@link continueFromConfirm}.
   */
  readonly confirmCredential = signal('');

  /**
   * The sysid this found-nearby asset's pairing collided with, once known — populated from the
   * register/attach response's own `sysidPushRequired`/`assignedSysid` (§7 ruling #3: "adopt is one
   * motion … only on collision assign the lowest free number … and return `sysidPushRequired=true`"),
   * never from a pre-attach guess (there is nothing to guess against before the atomic call actually
   * runs — see {@link applyFoundCandidateCollision}). Read by {@link finishCreate}/{@link writeSysid}
   * alongside the Prove-step-derived `proveByRole` collision, for a candidate that never ran Prove.
   */
  readonly foundCandidateSysidCollision = signal<number | null>(null);

  /**
   * Card click (docs/plans/active/LINK-PAIRING-PLAN.md §3.7): prefills the matching row exactly like
   * {@link useHeardCandidate} always has, then — only if that prefill actually found a stream to fill
   * the row with — additionally marks this visit as candidate-originated ({@link originCandidateId},
   * the same atomic-attach promise the query-param entrance makes) and enters {@link WizardStep}
   * `'confirm'`. A candidate `prefillRowFromCandidate` couldn't use (no suggested stream yet) already
   * toasted its own honest reason and left `sourceMode`/`step` untouched — this method takes no
   * further action on top of that, matching this app's degrade-honestly rule.
   */
  chooseFoundCandidate(candidate: DiscoveryCandidate): void {
    if (!prefillFromDiscoveryCandidate(candidate)) {
      this.toasts.info(`${candidate.method} could not supply a stream address for "${candidate.name}" yet.`);
      return;
    }
    this.useHeardCandidate(candidate);
    this.confirmCredential.set('');
    this.selectedCandidate.set(candidate);
    this.originCandidateId.set(candidate.id);
    this.step.set('confirm');
  }

  /** Confirm's own Continue — re-enters the ordinary `nextStep('source', ctx)` decision (the role is
   *  already pre-proven by {@link chooseFoundCandidate}'s own prefill, so this already resolves
   *  straight to `identify`, skipping `prove`) after folding the optional credential field into the
   *  prefilled row's own options, if one was typed. */
  continueFromConfirm(): void {
    const candidate = this.selectedCandidate();
    const credential = this.confirmCredential().trim();
    if (candidate && credential.length > 0) {
      const role = roleForDiscoveryMethod(candidate.method);
      this.rows.update((r) => ({
        ...r,
        [role]: { ...r[role], options: [...r[role].options, { key: 'password', value: credential }] },
      }));
    }
    this.step.set(nextStep('source', this.stepContext()));
  }

  /** Confirm's own Back — clears the prefilled row (including its candidate-origin tracking) and
   *  returns to `source`'s own found-nearby feed, exactly like the resolved-row summary card's
   *  "Change" affordance ({@link clearRow}) already does for every other entrance. */
  backFromConfirm(): void {
    const candidate = this.selectedCandidate();
    if (candidate) {
      this.clearRow(roleForDiscoveryMethod(candidate.method));
    }
    this.selectedCandidate.set(null);
    this.confirmCredential.set('');
    this.foundCandidateSysidCollision.set(null);
    this.sourceMode.set(null);
    this.step.set('source');
  }

  /** Confirm's own "This is my old rover/camera" link — today's existing-asset attach path
   *  (`attach` step's own segmented control), unchanged, just entered directly rather than via
   *  Identify. */
  attachExistingFromConfirm(): void {
    this.chooseAttachTarget('existing');
    this.step.set('attach');
  }

  /** Populates {@link foundCandidateSysidCollision} from a register/attach response — see that
   *  signal's own doc comment for exactly which fields this reads and why. */
  private applyFoundCandidateCollision(response: { sysidPushRequired?: boolean; assignedSysid?: number }): void {
    this.foundCandidateSysidCollision.set(
      response.sysidPushRequired === true && response.assignedSysid !== undefined ? response.assignedSysid : null,
    );
  }

  /**
   * Picks one of the fork's four tiles. A row this step itself half-initialized under a
   * *previously*-chosen tile (not yet resolved, i.e. `!isRowFilled`, and not pre-proven from a
   * candidate) is reset to empty first — so tapping between tiles never leaves a stale finder
   * behind; a row the operator has actually resolved, or that arrived pre-proven, is left alone
   * regardless of which tile is chosen afterward (this is what lets Sense arrive passively while
   * Sight is filled in manually, per this class's own doc comment).
   *
   * `'equipment'` is the one tile with an immediate, unconditional effect: both rows are forced to
   * `none` and `equipmentConfirmed` is set true right away — there is no second confirmation click,
   * clicking the tile *is* the deliberate "nothing to connect" answer (D3).
   */
  chooseSourceMode(mode: SourceMode): void {
    if (mode === 'equipment') {
      this.setRowValue('sense', 'none');
      this.setRowValue('sight', 'none');
      this.preProvenRoles.set(new Set());
      this.originCandidateId.set(null);
      this.equipmentConfirmed.set(true);
      this.sourceMode.set('equipment');
      return;
    }
    this.equipmentConfirmed.set(false);
    for (const role of FIT_OUT_ROLES) {
      if (!this.preProvenRoles().has(role) && !isRowFilled(this.rows()[role])) {
        this.setRowValue(role, 'none');
      }
    }
    this.sourceMode.set(mode);
    if (mode === 'passive') {
      this.initEmptyRow('sense', 'listen');
      this.initEmptyRow('sight', 'discover');
    } else if (mode === 'manual') {
      this.initEmptyRow('sense', 'register');
      this.initEmptyRow('sight', 'register');
    }
    // 'scan' initializes nothing: both rows stay `none` and render their own tile grid, narrowed by
    // `OnboardingFacade#rowFindMethods` to exclude `register` (manual's own tile) — Sight is left
    // with one real method (`discover`), Sense keeps its full two (`listen`/`drone`).
    //
    // The "Use a test source" tile that used to sit alongside these on every grid is gone (docs/plans/
    // active/LINK-PAIRING-PLAN.md §3.7, wave L4 — `features/playground` is now the only place a
    // simulated asset is created). `setRowValue(role, 'simulate')` and the legacy whole-vehicle
    // Simulate sub-form it drove (`OnboardingFacade#showLegacySimulateConfig`) are left in place but
    // dormant/unreachable from this wizard — nothing in `source-step.html` calls it any more — rather
    // than ripped out, since Playground's own builder (`core/fleet/simulation-logic.ts`) still reuses
    // some of the same plumbing and a clean follow-up removal deserves its own pass, not a rushed one
    // bundled into this wave.
  }

  private initEmptyRow(role: FitOutRole, method: FitOutFindMethod): void {
    if (this.preProvenRoles().has(role) || isRowFilled(this.rows()[role])) {
      return;
    }
    this.chooseRowFind(role, method);
  }

  /** Returns to the fork's own tile grid — never touches `rows`; a resolved row's own summary card renders independent of `sourceMode` regardless (this class's own doc comment). */
  backToSourceFork(): void {
    this.sourceMode.set(null);
  }

  /** The resolved-row summary card's own "Change" affordance — clears exactly that role, including
   *  any pre-proven/candidate-origin tracking, regardless of the current `sourceMode`. */
  clearRow(role: FitOutRole): void {
    this.rows.update((r) => ({ ...r, [role]: emptyFitOutRow(role) }));
    this.clearPreProven(role);
    if (this.originCandidateId()) {
      this.originCandidateId.set(null);
    }
  }

  /** The row's top-level choice (`find… · simulate · —`) — switching resets that row's own draft (finder, protocol, uri, options) entirely, so a stale half-entered connection from a previously-chosen value can never leak into the new one. */
  setRowValue(role: FitOutRole, value: FitOutRowValue): void {
    this.rows.update((r) => ({ ...r, [role]: { ...emptyFitOutRowLike(r[role]), value } }));
    this.clearPreProven(role);
  }

  /** Picks one of that row's own finders (`FIT_OUT_FIND_METHODS`) — `drone` also resets the guided config sub-step to its start. */
  chooseRowFind(role: FitOutRole, method: FitOutFindMethod): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], value: 'find', findMethod: method } }));
    this.clearPreProven(role);
    if (method === 'drone') {
      this.droneSubStep.set('picker');
    }
  }

  /** Back from a row's resolved/scanning state to its own finder-tile choice (only Sense's own scan-mode two-tile picker still nests a sub-view — see `chooseSourceMode`'s own doc comment). */
  backFromRowFind(role: FitOutRole): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], findMethod: null } }));
  }

  setRowProtocolSelect(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], protocolSelect: value } }));
    this.clearPreProven(role);
  }

  setRowCustomProtocol(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], customProtocol: value } }));
    this.clearPreProven(role);
  }

  setRowUri(role: FitOutRole, value: string): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], uri: value } }));
    this.clearPreProven(role);
  }

  addRowOption(role: FitOutRole): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], options: [...r[role].options, { key: '', value: '' }] } }));
    this.clearPreProven(role);
  }

  removeRowOption(role: FitOutRole, index: number): void {
    this.rows.update((r) => ({ ...r, [role]: { ...r[role], options: r[role].options.filter((_, i) => i !== index) } }));
    this.clearPreProven(role);
  }

  updateRowOptionKey(role: FitOutRole, index: number, key: string): void {
    this.rows.update((r) => ({
      ...r,
      [role]: { ...r[role], options: r[role].options.map((o, i) => (i === index ? { ...o, key } : o)) },
    }));
    this.clearPreProven(role);
  }

  updateRowOptionValue(role: FitOutRole, index: number, value: string): void {
    this.rows.update((r) => ({
      ...r,
      [role]: { ...r[role], options: r[role].options.map((o, i) => (i === index ? { ...o, value } : o)) },
    }));
    this.clearPreProven(role);
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

  /** Fills a row's register fields from a resolved connection and switches it to the standard register sub-view — the "candidate → register" pivot every finder below uses. Never pre-proven — only {@link prefillRowFromCandidate} sets that. */
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
    this.clearPreProven(role);
  }

  /**
   * Fills a row straight from a discovery-inbox candidate (§3.1 candidate-entrance table) — used by
   * both the `?candidateId=` query-param entrance and the waiting room's own live "Use" action
   * (`useHeardCandidate` below). Marks the role pre-proven ({@link preProvenRoles}) rather than
   * routing through {@link applyResolvedConnection}: this data already arrived over the wire (a
   * real MAVLink heartbeat, a real mediamtx push), which is why the wizard's own Prove step can
   * honestly skip it (`onboarding-logic.ts`'s own `prove` step doc comment) — it is not, however, a
   * substitute for the Prove step's own recorded probe/verify *result*, so a row filled this way
   * still renders honestly unproven on the terminal screen ({@link sightTerminalProof}/
   * {@link senseTerminalProof} — no fabricated green tick).
   */
  private prefillRowFromCandidate(candidate: DiscoveryCandidate): void {
    const prefill = prefillFromDiscoveryCandidate(candidate);
    if (!prefill) {
      this.toasts.info(`${candidate.method} could not supply a stream address for "${candidate.name}" yet.`);
      return;
    }
    const selection = protocolSelectionFor(prefill.protocol);
    this.rows.update((r) => ({
      ...r,
      [prefill.role]: {
        ...r[prefill.role],
        value: 'find',
        findMethod: prefill.findMethod,
        protocolSelect: selection.select,
        customProtocol: selection.custom,
        uri: prefill.uri,
        options: prefill.options ? Object.entries(prefill.options).map(([key, value]) => ({ key, value })) : [],
      },
    }));
    this.preProvenRoles.update((roles) => new Set(roles).add(prefill.role));
    if (this.displayName().trim().length === 0) {
      this.displayName.set(prefill.displayName);
    }
    if (prefill.category && this.category().trim().length === 0) {
      this.chooseCategory(prefill.category);
    }
    this.sourceMode.set('passive');
  }

  /** The waiting room's own live "Use" action (§3.1 scope item 6) — acts on exactly the candidate {@link intakeState}'s own `heard` branch is describing. */
  useHeardCandidate(candidate: DiscoveryCandidate): void {
    this.prefillRowFromCandidate(candidate);
  }

  // --- Connect: discovery (Sight row — `discover`) -------------------------------------------------

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

  /** `GET /api/system/network`'s own optional video-push facts (C3, wave U6) — `undefined` on a
   *  station with no push configured; feeds {@link pushAddressCard} below. Never fabricated. */
  readonly videoPushPort = signal<number | undefined>(undefined);
  readonly videoPushPathPrefix = signal<string | undefined>(undefined);

  /** The "It comes to us" tile's own push-address card — see `onboarding-logic.ts#composePushAddress`'s own doc comment for the honesty rule and the placeholder-name reasoning. */
  readonly pushAddressCard = computed(() => composePushAddress(this.networkAddresses(), this.videoPushPort(), this.videoPushPathPrefix()));

  /** Web Serial's own live availability (R3, wave U5) — checked once at construction, not cached
   *  across a page the operator never reloads mid-visit; gates the fork's own provision-wifi tile
   *  without ever hiding it (`WebSerialGateway#isSupported`'s own doc comment). */
  readonly provisionWifiSupported = signal(false);

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
   * dimming, `useDroneVehicle`'s "Use" pivot, Prove, Attach — is the pre-existing I-b flow, entirely
   * unmodified by this method.
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

  // --- Waiting room (§3.1 scope item 6, wave W3) — live intake per row, fed by a poll only the
  //     `source` step keeps running (see the constructor's own `step()` effect). -------------------

  readonly discoveryStatus = signal<DiscoveryStatusResponse | null>(null);
  /** Threaded into `intakeState`'s own `nowMs` so a candidate's age stays live without a second clock timer — ticked once per poll, same cadence as `discoveryStatus` itself. */
  readonly nowMs = signal(Date.now());
  /** The discovery inbox's own live candidate list (`DiscoveryInboxStore`, SSE-fed) — reused as-is rather than re-polled here; see this class's own `discoveryInbox` field doc. */
  readonly discoveryCandidates = this.discoveryInbox.candidates;

  private stopDiscoveryStatusPoll: (() => void) | null = null;

  private startDiscoveryStatusPoll(): void {
    if (this.stopDiscoveryStatusPoll) {
      return;
    }
    void this.fetchDiscoveryStatus();
    this.stopDiscoveryStatusPoll = this.poll.schedule(DISCOVERY_STATUS_POLL_MS, () => this.fetchDiscoveryStatus());
    this.discoveryInbox.activate();
  }

  private stopDiscoveryStatusPollNow(): void {
    this.stopDiscoveryStatusPoll?.();
    this.stopDiscoveryStatusPoll = null;
    this.discoveryInbox.release();
  }

  /** Silent-degrade, like every poller in this app — a failed read leaves `discoveryStatus` exactly
   *  as it was (stale, never blanked or fabricated into a `failed` state the wire never actually
   *  reported; see `intakeState`'s own doc comment). */
  private async fetchDiscoveryStatus(): Promise<void> {
    this.nowMs.set(Date.now());
    try {
      this.discoveryStatus.set(await this.api.discoveryStatus());
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  // --- Step: Prove — Test + Verify, per filled `find` row not already pre-proven (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6,
  //     merging the pre-W6 wizard's separate Test/Verify steps into one, run once per row) ----------

  readonly proveByRole = signal<Record<FitOutRole, RowProveState>>({ sense: emptyRowProveState(), sight: emptyRowProveState() });

  /** The caller's own *effective* `fit-out-logic.ts#needsProve` — excludes a role {@link preProvenRoles} already covers, per `onboarding-logic.ts`'s own `prove` step doc comment. Fed to {@link visibleSteps}/`nextStep`/`prevStep` via `stepContext` below. */
  readonly rowsNeedProve = computed(() => {
    const rows = this.rows();
    const preProven = this.preProvenRoles();
    return FIT_OUT_ROLES.some((role) => rows[role].value === 'find' && !preProven.has(role));
  });

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
   * (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B3) — read by the Prove step to word
   * that row's card for the device in front of the operator instead of demanding a frame that, on a
   * flight-controller link, cannot exist.
   */
  telemetryOnlyLink(role: FitOutRole): boolean {
    return isTelemetryOnlyProtocol(this.rowProtocol(role));
  }

  readonly canAdvanceProve = computed(() => {
    const rows = this.rows();
    const preProven = this.preProvenRoles();
    return FIT_OUT_ROLES.filter((role) => rows[role].value === 'find' && !preProven.has(role)).every(
      (role) => this.lastProbeOk(role) === true,
    );
  });

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

  // --- Step: Attach (was Register) — new asset, or attach onto an existing one (§0.2 "Attach" fork) --

  readonly creating = signal(false);

  /** New vehicle (default), or attach this visit's connection onto an already-registered asset instead. */
  readonly attachTarget = signal<'new' | 'existing'>('new');
  readonly existingAssets = signal<readonly AssetSummary[]>([]);
  readonly existingAssetsLoading = signal(false);
  readonly selectedExistingAssetId = signal<string | null>(null);
  /** Set once the Attach step actually attached onto an existing asset — the Hand-over step reads
   *  this to skip its own custodian picker straight to the terminal proof (that asset already has an
   *  owner; see this class's own Hand-over section doc). */
  readonly existingAssetPath = signal(false);

  chooseAttachTarget(target: 'new' | 'existing'): void {
    this.attachTarget.set(target);
    if (target === 'existing' && this.existingAssets().length === 0 && !this.existingAssetsLoading()) {
      void this.loadExistingAssets();
    }
  }

  private async loadExistingAssets(): Promise<void> {
    this.existingAssetsLoading.set(true);
    try {
      this.existingAssets.set(await this.api.listAssets());
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.existingAssetsLoading.set(false);
    }
  }

  selectExistingAsset(assetId: string): void {
    this.selectedExistingAssetId.set(assetId);
  }

  async createAsset(): Promise<void> {
    if (this.creating()) {
      return;
    }
    if (this.attachTarget() === 'existing') {
      await this.attachToExistingAsset();
      return;
    }
    this.creating.set(true);
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
      this.creating.set(false);
    }
  }

  /**
   * The "Found nearby" feed's own "new asset" path (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling
   * #3: "adopt is one motion" — only `DiscoveryInboxController.register`/`attach` actually calls
   * `PairingService.pair` in the same transaction; the generic multi-device `POST /api/assets` every
   * other Source entrance uses below does not). Gated on {@link selectedCandidate} (not just
   * {@link originCandidateId} alone, which the older `?candidateId=` query-param entrance also sets)
   * so this new routing only ever applies to a visit that actually went through
   * {@link chooseFoundCandidate} — the query-param entrance's own existing "new asset" behavior stays
   * byte-identical. Applies `identifyDraft`'s richer facts (serial/make/model/registration) as a
   * follow-up edit, the same "create minimal, then PATCH" idiom {@link createViaSimulation} already
   * uses for its own Simulate-service round-trip.
   */
  private async createViaCandidateRegister(candidateId: string): Promise<void> {
    try {
      const draft = { displayName: this.displayName().trim() || 'New asset', category: this.category().trim() };
      const result = await this.discoveryInbox.register(candidateId, draft);
      if (!result) {
        return; // failure already toasted by DiscoveryInboxStore#run
      }
      this.applyFoundCandidateCollision(result);
      const edit = buildPostSimulationAssetEdit(this.identifyDraft());
      const displayName =
        Object.keys(edit).length > 0
          ? (await this.api.updateAsset(result.assetId, edit)).displayName
          : result.displayName;
      await this.finishCreate(result.assetId, displayName);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
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
   * "Attach to existing" (§0.2's "Attach" fork, "existing" branch): the candidate-entrance's atomic
   * `POST /api/discovery/inbox/{id}/attach` (C1, via `DiscoveryInboxStore#attachCandidate`) when this
   * visit started from a discovery candidate ({@link originCandidateId}); a plain
   * register-device-then-assign otherwise ({@link registerAndAssignRows}) — the same two-call shape
   * `DiscoveryInboxStore#attach` already uses for its own candidate case, repeated here rather than
   * shared since there is no candidate id to reuse that method's own signature with.
   */
  private async attachToExistingAsset(): Promise<void> {
    const assetId = this.selectedExistingAssetId();
    if (!assetId) {
      return;
    }
    this.creating.set(true);
    try {
      const asset = this.existingAssets().find((a) => a.assetId === assetId);
      const displayName = asset?.displayName ?? assetId;
      const candidateId = this.originCandidateId();
      const ok = candidateId
        ? await this.discoveryInbox.attachCandidate(candidateId, assetId)
        : await this.registerAndAssignRows(assetId);
      if (!ok) {
        return;
      }
      if (candidateId) {
        // §7 ruling #3: `attach` pairs in the same transaction as `register` — read the freshly
        // patched candidate back (DiscoveryInboxStore#attachCandidate already replaced it in its own
        // list with the server's real response) for the same `sysidPushRequired`/`assignedSysid` pair
        // `createViaCandidateRegister` reads off `register`'s response directly.
        const updated = this.discoveryInbox.candidates().find((c) => c.id === candidateId);
        if (updated) {
          this.applyFoundCandidateCollision(updated);
        }
      }
      this.existingAssetPath.set(true);
      await this.fleet.refresh({ quiet: true });
      await this.finishCreate(assetId, displayName);
    } finally {
      this.creating.set(false);
    }
  }

  private async registerAndAssignRows(assetId: string): Promise<boolean> {
    const specs = fitOutDeviceSpecs(this.rows(), this.displayName().trim() || 'Device');
    try {
      for (const spec of specs) {
        const device = await this.api.registerDevice(spec);
        await this.api.assignDevice(assetId, device.id);
      }
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    }
  }

  /**
   * The equipment short-circuit's own action (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 — "Register happens
   * with zero devices"). Called directly from the Identify step's own "Receive" button on the
   * equipment path ({@link equipment}) — `prove`/`attach` are never rendered as steps at all (see
   * `onboarding-logic.ts#WizardStep`'s own `identify` doc comment); `devices` on the resulting
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
    // LINK-PAIRING-PLAN.md §7 ruling #3 (wave L4) adds a second source: a found-nearby candidate
    // never runs Prove at all, so its own collision (if any) is only known from the register/attach
    // response — `foundCandidateSysidCollision` — checked here as a fallback, never overriding a
    // real Prove-derived collision when one somehow also exists.
    const collision =
      combinedSysidCollision({
        sense: this.proveByRole().sense.sysidCollision,
        sight: this.proveByRole().sight.sysidCollision,
      }) ?? this.foundCandidateSysidCollision();
    if (collision !== null) {
      await this.enterSysidStep(assetId, displayName);
    } else {
      await this.enterHandoverStep(assetId, displayName);
    }
  }

  // --- Step: fix a sysid collision (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0) --------------------
  // Entered only from `finishCreate` above, only when a Prove-step row's profile collided with an
  // already-claimed sysid. Like Hand-over below, this step is offered only *after* the asset has
  // already been created/attached — a failed write here can never be mistaken for "the asset wasn't
  // created". Advisory, never a hard block: `continueFromSysidStep` always proceeds into Hand-over
  // regardless of whether a write was even attempted, let alone whether it succeeded.

  readonly writingSysid = signal(false);
  readonly sysidWriteResult = signal<ParameterWriteResponse | null>(null);
  readonly sysidWriteError = signal<string | null>(null);
  /** The operator's chosen replacement sysid — left for them to type; this wizard does not attempt to suggest a "free" sysid (out of scope for this wave). */
  readonly sysidValue = signal<number | null>(null);

  private async enterSysidStep(assetId: string, displayName: string): Promise<void> {
    this.createdAssetId.set(assetId);
    this.createdAssetDisplayName.set(displayName);
    // §7 ruling #3: a found-nearby candidate already had a number picked for it by the station
    // (`foundCandidateSysidCollision` holds the register/attach response's own `assignedSysid`) —
    // prefill it so the operator isn't asked to invent a number from scratch; the field stays
    // editable. The Prove-path collision (a genuine already-claimed sysid, no server assignment)
    // leaves this at whatever the operator already typed, same as before.
    const assigned = this.foundCandidateSysidCollision();
    if (assigned !== null) {
      this.sysidValue.set(assigned);
    }
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
    // LINK-PAIRING-PLAN.md §7 ruling #3 (wave L4): a found-nearby candidate never ran Prove, so
    // neither role's own `sysidParameterToWrite` is ever set for it — fall back to the modern
    // spelling (`sysid-collision-logic.ts#sysidParameterName`'s own doc comment: the fallback this
    // platform's own firmware target answers to) whenever `foundCandidateSysidCollision` is the
    // reason this step was entered at all.
    const parameterName =
      this.proveByRole().sense.sysidParameterToWrite ??
      this.proveByRole().sight.sysidParameterToWrite ??
      (this.foundCandidateSysidCollision() !== null ? 'MAV_SYSID' : null);
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

  // --- Step: Hand over (docs/plans/done/OPS-UX-PLAN.md §2 A3; docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
  //     D9) — "Issue to" a custodian or "Leave in stock" for the **new**-asset path; the
  //     **existing**-asset path ({@link existingAssetPath}) skips the picker straight to the
  //     terminal proof, since that asset already has an owner. Ends in the two-half Sight/Sense
  //     proof + "Open cockpit ›" screen (D9), never an automatic router redirect. Offered only
  //     *after* the asset has already been created/attached — every signal below is therefore about
  //     handing the asset off, never creation, and `handoverError` is read that way too. ------------

  readonly createdAssetId = signal<string | null>(null);
  readonly createdAssetDisplayName = signal('');

  /** The custodian candidate list — every enabled PILOT-role member of the asset's own (silently-assigned) ownership group. Empty means "couldn't offer anyone", not "nobody exists" — see `ownerGroupName`'s own doc comment for how the template tells those two apart. */
  readonly pilotCandidates = signal<readonly UserSummary[]>([]);
  readonly pilotCandidatesLoading = signal(false);
  /** `undefined` only when the creator's own group could not be resolved at all (a membership-less account). */
  readonly ownerGroupName = signal<string | undefined>(undefined);
  /** Single-select — "Issue to" hands the asset to exactly one custodian (D3), unlike the pre-W6 Assign step's multi-pilot checklist. */
  readonly selectedCustodianId = signal<string | null>(null);
  readonly handoverLocation = signal('');
  readonly handingOver = signal(false);
  /** Set only if `setAssetCustody`/`assignPilot` itself fails — never a creation failure (see this section's own class-doc paragraph). */
  readonly handoverError = signal<string | null>(null);
  /** `'issued'`/`'stocked'` once the operator has picked one of the step's two actions, `null` while the picker itself is still showing (never reached at all on {@link existingAssetPath}). */
  readonly handoverOutcome = signal<'issued' | 'stocked' | null>(null);

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
    if (this.existingAssetPath()) {
      return; // that asset already has an owner — no roster to fetch, no picker to show.
    }
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
      // below) — `GET /api/users` may 403 for a caller without org-management rights; the asset is
      // already created and unaffected either way.
      this.pilotCandidates.set([]);
    } finally {
      this.pilotCandidatesLoading.set(false);
    }
  }

  /**
   * The step's primary action — hands the asset to the selected custodian, in **one call**:
   * `ISSUE` is custody *and* the `PILOT` assignment, composed server-side (decision D1 /
   * docs/plans/active/INVENTORY-REWORK-PLAN.md §6 row 4, that plan's wave W1 — an idempotent
   * assignment write, with the custody write compensated if it fails).
   *
   * It used to issue and then `assignPilot` as two client calls — WAREHOUSE-UX D3 half-shipped in
   * the wrong layer (INVENTORY-REWORK-CONTEXT.md §3 defect B): the second call could fail on its
   * own, leaving a custodian holding a vehicle they still could not see. No-op with nobody selected
   * — the picker's own "Issue to" button stays disabled for that case.
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
      await this.fleet.refresh({ quiet: true });
      this.handoverOutcome.set('issued');
    } catch (error) {
      this.handoverError.set(describeHttpError(error));
    } finally {
      this.handingOver.set(false);
    }
  }

  /** The step's secondary action — leaves the asset in stock; the roster can always issue it later. */
  leaveInStock(): void {
    this.handoverOutcome.set('stocked');
  }

  // --- Step navigation (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1 — rail, back-navable) -------

  private readonly stepContext = computed<StepContext>(() => ({
    equipment: this.equipment(),
    needsProve: this.rowsNeedProve(),
  }));

  readonly canAdvance = computed(() => {
    switch (this.step()) {
      case 'source':
        return this.canAdvanceFromSource();
      case 'prove':
        return this.canAdvanceProve();
      case 'identify':
        return this.canAdvanceIdentify();
      case 'attach':
        return false; // its own Create/Attach action, not a "Next"
      case 'confirm':
        return false; // its own Continue/Back actions, not the shared footer — see WizardStep's own confirm doc comment
      case 'sysid':
        return false; // its own "Write sysid"/"Continue" actions, not a "Next"
      case 'handover':
        return false; // its own "Issue to"/"Leave in stock" actions, not a "Next"
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

  /**
   * The rail's own `(jump)` output — `vision-step-rail` itself imposes no restriction at all ("every
   * step is re-enterable by design", its own class doc), so this wizard's one-way door past a
   * successful create/attach (`onboarding-logic.ts#WizardStep`'s own doc comment: "never
   * back-navigable into attach post-creation") is enforced here, the one place every rail click
   * funnels through. A no-op once {@link createdAssetId} is set — `attach`/`sysid`/`handover` are
   * never rail targets anyway (`sysid` is excluded from `visibleSteps` entirely, and this guard is
   * what keeps a click on the rail's own "Attach"/"Hand over" chip from re-opening a step whose work
   * is already done).
   */
  jumpToStep(step: WizardStep): void {
    if (this.createdAssetId()) {
      return;
    }
    this.step.set(step);
  }

  constructor() {
    void this.loadCategoryOptions();
    void this.loadSystemNetwork();
    void this.applyCandidateQueryPrefill();
    this.provisionWifiSupported.set(this.webSerial.isSupported());

    // The waiting-room poll (`discoveryStatus`) and the shared discovery-inbox candidate feed only
    // ever matter while the `source` step itself is on screen — started/stopped here rather than
    // unconditionally for the store's whole lifetime, so leaving `/add-source` (destroying this
    // per-route store) or simply moving past `source` releases both immediately.
    effect(() => {
      const onSource = this.step() === 'source';
      untracked(() => {
        if (onSource) {
          this.startDiscoveryStatusPoll();
        } else {
          this.stopDiscoveryStatusPollNow();
        }
      });
    });

    inject(DestroyRef).onDestroy(() => {
      this.revokePreview();
      this.stopDiscoveryStatusPollNow();
    });
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
   * Fetched once, up front, so the "configure your drone" sub-step's snippets and the `source`
   * step's own push-address card are ready to render the moment the operator gets there. Silent-
   * degrade on failure exactly like `loadCategoryOptions` above: `mavlinkPort` keeps its
   * `DEFAULT_MAVLINK_PORT` fallback, `networkAddresses` stays `[]`, and `videoPushPort`/
   * `videoPushPathPrefix` stay `undefined` — {@link pushAddressCard} already renders that
   * combination as "no card" honestly, no separate error state needed.
   */
  private async loadSystemNetwork(): Promise<void> {
    try {
      const network = await this.api.systemNetwork();
      this.networkAddresses.set(network.addresses);
      this.mavlinkPort.set(network.mavlinkPort);
      this.videoPushPort.set(network.videoPushPort);
      this.videoPushPathPrefix.set(network.videoPushPathPrefix);
      if (network.addresses.length > 0) {
        this.selectedServerAddress.set(network.addresses[0].address);
      }
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }

  /**
   * The wizard's own candidate-entrance contract (§3.1's candidate-entrance table): a
   * `?candidateId=<uuid>` query param on `/add-source` prefills the fit-out row that candidate's own
   * method belongs on (`onboarding-logic.ts#roleForDiscoveryMethod`), pre-proven, `needsProve` false
   * for that role. Mirrors the pre-W2 wizard's own `?deviceId=` prefill pattern exactly (background,
   * best-effort, silent-degrade) — no single-candidate-by-id `GET` exists, so this fetches the whole
   * inbox and finds by id, same as that prefill's own `listDevices()` + find.
   */
  private async applyCandidateQueryPrefill(): Promise<void> {
    const candidateId = this.route.snapshot.queryParamMap.get('candidateId');
    if (!candidateId) {
      return;
    }
    try {
      const response = await this.api.listDiscoveryInboxCandidates();
      const candidate = response.candidates.find((c) => c.id === candidateId);
      if (!candidate) {
        return;
      }
      this.prefillRowFromCandidate(candidate);
      this.originCandidateId.set(candidateId);
    } catch {
      // Silent-degrade — see this method's own doc comment.
    }
  }
}

/** Resets everything about a row except its `role` — used by {@link OnboardingStore.setRowValue} so switching a row's top-level choice never leaves a stale finder/protocol/uri/options behind. */
function emptyFitOutRowLike(row: FitOutRows[FitOutRole]): FitOutRows[FitOutRole] {
  return { role: row.role, value: 'none', findMethod: null, protocolSelect: '', customProtocol: '', uri: '', options: [] };
}
