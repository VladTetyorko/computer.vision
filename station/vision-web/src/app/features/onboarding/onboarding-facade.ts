import { Injectable, computed, inject } from '@angular/core';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { OnboardingStore } from './onboarding-store';
import {
  relativeAge,
  senseTerminalProof,
  sightTerminalProof,
  visibleSteps,
  type RoleProof,
  type SourceMode,
  type WizardStep,
} from './onboarding-logic';
import {
  FIT_OUT_FIND_METHODS,
  FIT_OUT_ROLES,
  combinedSysidCollision,
  isRowFilled,
  usesLegacySimulationPath,
  type FitOutFindMethod,
  type FitOutRole,
} from '../../core/onboarding/fit-out-logic';
import {
  MAVLINK_METHOD,
  MEDIAMTX_METHOD,
  freshestNewCandidate,
  intakeState,
  type IntakeState,
} from '../../core/onboarding/intake-logic';
import {
  DEFAULT_DISCOVERY_INBOX_VISIBILITY,
  candidateAgeLabel,
  candidateNeedsCredential,
  discoveryMethodLabel,
  excludeSimulated,
  visibleCandidates,
} from '../../core/discovery/discovery-inbox-logic';
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
import { humanAge } from '../../core/telemetry/telemetry-logic';
import type { DiscoveredDevice, DiscoveryCandidate, ParameterWriteResponse } from '../../core/api/models';
import type { StepRailItem } from '../../shared/ui/step-rail';

interface StepDescriptor {
  readonly step: WizardStep;
  readonly label: string;
}

/** The rail's own label row (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1). `sysid` is never
 *  included — see `onboarding-logic.ts#WizardStep`'s own doc comment. */
const STEP_LABELS: Record<WizardStep, string> = {
  source: 'Source',
  prove: 'Prove',
  identify: 'Identify',
  attach: 'Attach',
  confirm: 'Confirm',
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

/** Each finder tile's own one-line sub-copy — moved verbatim from the pre-W2 Connect step's tiles. */
const FIND_METHOD_HINTS: Record<FitOutFindMethod, string> = {
  register: "Paste a camera's stream URL if you already have one (RTSP, MJPEG, HTTP).",
  discover: "Scan your local network and pick from what's found — no address needed.",
  listen: 'Listen for drones already broadcasting telemetry — nothing to type.',
  drone: "Connect a flight controller (Betaflight, INAV, ArduPilot) — we'll walk you through it with the connection settings pre-filled.",
};

/** One of the `source` step's four fork tiles (D3, wave W3). */
export interface SourceModeOption {
  readonly mode: SourceMode;
  readonly label: string;
  readonly hint: string;
}

const SOURCE_MODE_OPTIONS: readonly SourceModeOption[] = [
  {
    mode: 'passive',
    label: 'It comes to us',
    hint: 'Passive listening — the standing MAVLink lobby and the mediamtx push path pick this up on their own. Nothing to type, nothing to scan.',
  },
  {
    mode: 'manual',
    label: 'We go to it',
    hint: 'Type in a stream or telemetry address you already have — a camera or a flight controller, either one.',
  },
  {
    mode: 'scan',
    label: 'Find it for me',
    hint: 'Scan the local network for cameras, and listen for nearby drones already broadcasting telemetry.',
  },
  {
    mode: 'equipment',
    label: 'Nothing to connect',
    hint: 'A battery, propeller, case, or other piece of gear with no video or telemetry link of its own.',
  },
];

/**
 * `OnboardingPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — orchestrates `OnboardingStore`/
 * `SettingsStore`/`ToastService`. `OnboardingStore` already owns the wizard's whole step machine/
 * draft state/HTTP orchestration (this component's own page-provided "component store"); this facade
 * adds the page-local read-models/commands every step component needs (label maps, per-row
 * summaries, the rail's own items, the waiting room's live intake render, the clipboard/download
 * helpers) — every one byte-for-byte carried over from the pre-W2 wizard except where the fit-out
 * table's per-row shape or the new `source`/`attach` steps required a change (`connectionSummary`,
 * now `rowSummary`; `onConnectBack`, now `onRowBack`; the rail/intake/source-mode additions below are
 * new to this wave).
 *
 * Every one of the six per-step components (`source-step`/`prove-step`/`identify-step`/
 * `attach-step`/`sysid-step`/`handover-step`) injects this facade directly — the non-routed-child
 * carve-out `core/ui/architecture.spec.ts` documents (they sit inside `OnboardingPage`'s own
 * `providers: [OnboardingStore, OnboardingFacade]` injector, so nothing is re-provided) — never
 * `OnboardingStore` itself, keeping one single access path (`facade.xxx`/`facade.store.xxx`)
 * consistent with the page shell's own rule (routed pages inject only their facade).
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

  /** Two steps for equipment, four/five otherwise — see `onboarding-logic.ts#visibleSteps`'s own doc comment. */
  readonly steps = computed<readonly StepDescriptor[]>(() =>
    visibleSteps(this.store.rows(), this.store.rowsNeedProve()).map((step) => ({ step, label: STEP_LABELS[step] })),
  );

  readonly sourceModeOptions = SOURCE_MODE_OPTIONS;

  /** The three demoted one-level-down links below the "Found nearby" feed (docs/plans/active/
   *  LINK-PAIRING-PLAN.md §3.7, wave L4) — every fork tile except `'passive'`, which the feed itself
   *  now replaces outright rather than gating behind a click. */
  readonly demotedSourceModeOptions = computed(() => SOURCE_MODE_OPTIONS.filter((option) => option.mode !== 'passive'));

  /**
   * The "Found nearby" feed's own card list (docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4) —
   * every discovery candidate worth a decision (`visibleCandidates`'s own NEW-first filter, the same
   * one the discovery inbox's "Found devices" section already uses), minus anything simulated
   * (`excludeSimulated` — simulated sources are created exclusively from `/playground` and must never
   * pass as a physically-present found device).
   */
  readonly foundNearbyCandidates = computed(() =>
    excludeSimulated(visibleCandidates(this.store.discoveryCandidates(), DEFAULT_DISCOVERY_INBOX_VISIBILITY)),
  );

  /** "vehicle" for a MAVLink candidate, "camera" for everything else — the Confirm screen's own
   *  "This is my old {noun}" link text and empty-state copy. */
  foundCandidateNounFor(candidate: DiscoveryCandidate): string {
    return candidate.method.toLowerCase() === MAVLINK_METHOD ? 'vehicle' : 'camera';
  }

  /**
   * The Confirm screen's own facts `<dl>` (docs/plans/active/LINK-PAIRING-PLAN.md §3.7 — "detected
   * facts"): name, discovery method, and every detail the candidate itself carries, verbatim — never
   * a fabricated field. `details` is a plain `Record<string,string>`, so entries render in whatever
   * order the backend sent them; that's acceptable here (an inspection list, not a form).
   */
  candidateDetailEntries(candidate: DiscoveryCandidate): readonly { readonly label: string; readonly value: string }[] {
    const entries: { label: string; value: string }[] = [
      { label: 'Name', value: candidate.name },
      { label: 'Found via', value: discoveryMethodLabel(candidate.method) },
      { label: 'Address', value: candidate.address },
    ];
    for (const [key, value] of Object.entries(candidate.details)) {
      entries.push({ label: key, value });
    }
    return entries;
  }

  /** Whether the Confirm screen's one credential field should render at all — see
   *  `candidateNeedsCredential`'s own doc comment for the honest gap this papers over. */
  candidateNeedsCredential(candidate: DiscoveryCandidate): boolean {
    return candidateNeedsCredential(candidate);
  }

  readonly findMethodLabels = FIND_METHOD_LABELS;
  readonly findMethodHints = FIND_METHOD_HINTS;

  /** The Source step's two rows, in render order — see `fit-out-logic.ts#FIT_OUT_ROLES`. */
  readonly fitOutRoles = FIT_OUT_ROLES;

  /**
   * Which finder tiles a row's own grid offers right now — `fit-out-logic.ts#FIT_OUT_FIND_METHODS`'s
   * fixed per-role list, narrowed on the `scan` fork tile to exclude `register` (that method is
   * `manual`'s own tile — offering it again here would overlap the fork's two modes). `manual` never
   * renders a tile grid at all (`OnboardingStore#chooseSourceMode` auto-resolves both rows straight
   * to `register`), so this narrowing only ever matters for `scan`.
   *
   * **No "Use a test source" tile any more** (docs/plans/active/LINK-PAIRING-PLAN.md §3.7, wave L4 —
   * "`/playground` is the ONLY place a simulated asset is created"): the row's own tile grid
   * (`source-step.html`'s `method-grid`) renders only this list now, never a Simulate option. A row's
   * `value` can still, in principle, already *be* `'simulate'` (a pre-existing draft, e.g. restored
   * from a stale in-progress session) — `source-step.html`'s `@else if (…value === 'simulate')`
   * branch and the legacy whole-vehicle Simulate sub-form below it ({@link showLegacySimulateConfig})
   * stay in place purely to render that state honestly rather than crash on it; neither is reachable
   * from a fresh choice any more (`FitOutRowValue`'s `'simulate'` member and `OnboardingStore#setRowValue`
   * are both left in place for the same reason — dormant, not deleted — see `source-step.ts`'s own
   * class doc comment).
   */
  rowFindMethods(role: FitOutRole): readonly FitOutFindMethod[] {
    const all = FIT_OUT_FIND_METHODS[role];
    return this.store.sourceMode() === 'scan' ? all.filter((method) => method !== 'register') : all;
  }

  /** A row already resolved (filled, or pre-proven from a discovery-candidate entrance) — renders its own summary card regardless of `sourceMode` (`OnboardingStore#chooseSourceMode`'s own doc comment). */
  rowResolved(role: FitOutRole): boolean {
    return this.store.preProvenRoles().has(role) || isRowFilled(this.store.rows()[role]);
  }

  /** The freshest `NEW` discovery candidate the waiting room's own live "Use" action would act on for this role — `undefined` until one has actually arrived. */
  heardCandidateFor(role: FitOutRole): DiscoveryCandidate | undefined {
    return freshestNewCandidate(this.store.discoveryCandidates(), role === 'sense' ? MAVLINK_METHOD : MEDIAMTX_METHOD);
  }

  /**
   * Whether the Source step must also render the legacy whole-vehicle Simulate sub-form (mode
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

  /** The Identify step's own primary-action wording — "Receive" on the equipment path (performs the create call directly), "Next" otherwise. */
  readonly identifyActionLabel = computed(() => (this.store.equipment() ? 'Receive' : 'Next'));

  /** The Attach step's own one-line-per-row summary of the Source step's outcome — never re-derives a request. */
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

  /** `-1` only on the hidden `sysid` interstitial (never in `steps()` — see `onboarding-logic.ts#WizardStep`'s own doc comment); `vision-step-rail` simply highlights nothing for that one transient step, an honest, momentary degrade rather than a fabricated position. */
  readonly currentStepIndex = computed(() => this.stepIndex(this.store.step()));

  /** The rail's own items — a step already passed (index less than the current one) reads `done`. */
  readonly railItems = computed<readonly StepRailItem[]>(() => {
    const current = this.currentStepIndex();
    return this.steps().map((s, i) => ({ id: s.step, label: s.label, done: current >= 0 && i < current }));
  });

  /** The rail's own `(jump)` output — see `OnboardingStore#jumpToStep`'s own doc comment for the one-way-door-past-creation rule this enforces. */
  jumpTo(index: number): void {
    const target = this.steps()[index]?.step;
    if (target) {
      this.store.jumpToStep(target);
    }
  }

  /** See {@link detailsSummary} — the general "Discover on network" table's own Details column. */
  detailsSummary(details: Record<string, string>): string {
    return detailsSummary(details);
  }

  // --- "Listen for drones" results list (docs/plans/active/DRONE-INFRA-PLAN.md I-b) — thin helpers over
  //     `drone-scan-logic.ts`'s own pure functions, same pattern as `detailsSummary` above.

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
   * A row's "‹ back" affordance is shared by every finder (`source-step.html`'s own resolved-row
   * card, one per fit-out row); for `drone` specifically it must step back one sub-state (`config` →
   * `picker`) before falling through to the generic "leave this finder entirely" — every other
   * finder has only one sub-state, so this is the one place that distinction matters. `drone` only
   * ever appears on the Sense row (`FIT_OUT_FIND_METHODS`), but this checks the row's own
   * `findMethod` rather than hardcoding that, so a future finder reassignment can't silently break it.
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

  // --- Step: fix a sysid collision (docs/plans/active/FLEET-RADIO-PLAN.md R5/F0) -----------------
  // Reuses `core/readiness/readiness-logic.ts#outcomeLabel`/`outcomeTone` — the same
  // `ParameterWriteOutcome`-shaped `'ACCEPTED'|'DENIED'|'NO_ACK'|'UNSUPPORTED'` union
  // `RemediationAction#outcome` already carries, `features/readiness/readiness.html`'s own second
  // consumer of both.

  readonly outcomeLabel = outcomeLabel;

  /** The collided sysid itself, for the step's own wording — {@link combinedSysidCollision} over both
   *  rows' Prove results (only a `mavlink` Sense row realistically ever sets one, but this stays
   *  total over both), falling back to `store.foundCandidateSysidCollision` for a found-nearby
   *  candidate that never ran Prove at all (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling #3). */
  readonly sysidCollision = computed(
    () =>
      combinedSysidCollision({ sense: this.store.proveByRole().sense.sysidCollision, sight: this.store.proveByRole().sight.sysidCollision }) ??
      this.store.foundCandidateSysidCollision(),
  );

  /** {@link outcomeTone} maps to a `vision-notice` variant — `'muted'` (UNSUPPORTED) has no notice-variant equivalent, so it renders as `'neutral'`, mirroring `readiness.ts#remediationVariant`'s own precedent. */
  sysidOutcomeVariant(outcome: ParameterWriteResponse['outcome']): 'neutral' | 'warn' | 'danger' | 'ok' {
    const tone = outcomeTone(outcome);
    return tone === 'muted' ? 'neutral' : tone;
  }

  // --- Source step: waiting room + P1 diagnostics (§3.1 scope items 6/7, wave W3) ------------------

  discoveryMethodLabel(method: string): string {
    return discoveryMethodLabel(method);
  }

  candidateAgeLabel(candidate: DiscoveryCandidate): string {
    return candidateAgeLabel(candidate.lastSeen, this.store.nowMs());
  }

  /** The P1 diagnostic panel's own relative-age facts (`lastDatagramAt`, a source's own `lastScanAt`) — see `onboarding-logic.ts#relativeAge`'s own doc comment. `undefined` renders as the usual faint em dash, never a fabricated "just now". */
  diagnosticAge(iso: string | undefined): string | undefined {
    return relativeAge(iso, this.store.nowMs());
  }

  /** "12s ago" from a raw millisecond duration (`IntakeState`'s own `heard` variant carries `ageMs`, not a timestamp to re-diff) — same `humanAge` render as {@link diagnosticAge}, just fed a duration directly instead of an ISO instant. */
  agoFromMs(ms: number): string {
    return `${humanAge(ms / 1000)} ago`;
  }

  /**
   * One fit-out row's live waiting-room state (§3.1 scope item 6) — layers the store's own recorded
   * Prove-step probe result over `core/onboarding/intake-logic.ts#intakeState`'s purely passive read,
   * exactly as that module's own doc comment specifies ("`OnboardingStore` already tracks that
   * separately … and is the one place that ever renders the `proven` branch"). A row pre-proven by a
   * discovery-candidate entrance (§3.1's candidate-entrance table) is deliberately **not** rendered
   * `proven` here — only the Hand-over step's own terminal proof does that, and only once an actual
   * probe ran (`onboarding-logic.ts#sightTerminalProof`/`senseTerminalProof`'s own doc comment); this
   * waiting room stays honest about the difference between "arrived over the wire" and "verified".
   *
   * While the very first `GET /api/discovery/status` poll is still in flight (`discoveryStatus()`
   * still `null`), a `find` row reads `listening`/"Checking…" rather than `idle` — it genuinely is
   * trying to prove itself, this app just has no data back yet; a `none`/`simulate` row still reads
   * `idle`, matching `intakeState`'s own rule.
   */
  intakeFor(role: FitOutRole): IntakeState {
    const row = this.store.rows()[role];
    const status = this.store.discoveryStatus();
    const raw: IntakeState = status
      ? intakeState(row, status, this.store.discoveryCandidates(), this.store.nowMs())
      : row.value === 'find'
        ? { kind: 'listening', where: 'Checking…', seenNothing: true }
        : { kind: 'idle' };
    if (this.store.lastProbeOk(role) === true) {
      return { kind: 'proven', what: raw.kind === 'heard' ? raw.what : 'verified' };
    }
    return raw;
  }

  // --- Hand-over step: two-half terminal proof (§3.1 scope item 5, D9) -----------------------------

  /** Sight's half of the Hand-over screen's terminal proof (D9) — replaces the old `handoverNext()`
   *  router-link guess entirely with honest per-half evidence, since the two halves can be proven
   *  independently (e.g. Sight proven from its own Prove-step Test, Sense still simulated). Reads
   *  straight off the Prove step's own recorded result — a pre-proven-but-never-actually-tested row
   *  (a discovery-candidate entrance, or the waiting room's own "Use") has no `lastProbeResult` here,
   *  so it renders the same honest "not yet verified" as any other unproven row; see
   *  `onboarding-logic.ts#sightTerminalProof`'s own doc comment. */
  sightProof(): RoleProof {
    return sightTerminalProof(this.store.rows().sight.value, this.store.proveByRole().sight.lastProbeResult);
  }

  /** Sense's half of the same terminal proof — mirrors {@link sightProof} off the Prove step's own Verify result. */
  senseProof(): RoleProof {
    return senseTerminalProof(this.store.rows().sense.value, this.store.proveByRole().sense.lastVerifyResult);
  }
}
