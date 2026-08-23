import type {
  AssetEdit,
  CreateAssetRequest,
  Membership,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  Role,
  UserSummary,
} from '../../core/api/models';
import type { SimulateMode } from '../../core/fleet/simulation-logic';
import { withRegistrationNumber } from '../../core/fleet/asset-attributes';

/**
 * Pure logic behind the onboarding wizard (docs/plans/done/UX-REWORK-PLAN.md §U-d): step-state transitions,
 * poka-yoke advance gating, and request builders. Split out so it is unit-testable without HTTP,
 * the router, or a canvas — mirrors every other `*-logic.ts` module in this app
 * (`features/devices/devices-page-logic.ts`, `core/telemetry/telemetry-logic.ts`).
 *
 * `OnboardingStore` (the wizard's own "component store", provided per-route like
 * `TelemetryStore`/`DetectionsStore`) is the only caller — it owns every signal and orchestrates
 * the actual HTTP calls, but defers every yes/no and every request shape to the functions here.
 */

/**
 * The wizard's six steps, always in this order — `nextStep`/`prevStep` are the only way to move
 * through the first five. **`assign`** (docs/plans/done/OPS-UX-PLAN.md §2 A3, "Who flies this?") is the
 * exception: the wizard never reaches it via `next()` (the `create` step's own action button is
 * what gets there, only after `POST /api/assets` actually succeeds — see `OnboardingStore#finishCreate`)
 * and it is never back-navigable into `create` (the asset already exists by the time it renders;
 * "going back" would misleadingly suggest undoing that). `nextStep`/`prevStep` still define total
 * cases for it (returning `'assign'`/`'create'` respectively) purely so both functions stay total
 * over the whole `WizardStep` union — `onboarding.html`'s own footer is what actually withholds the
 * Back/Next buttons on this step (see its own template comment).
 *
 * **`verify`** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 3/O6, inserted between `test` and
 * `create`) is where the platform actually observes the aircraft's own vehicle link — a
 * pre-registration `POST /api/onboarding/probe` (`OnboardingStore#verify`), distinct from `test`'s
 * `POST /api/devices/probe` (one decoded video frame). Reached only from `test` when the Connect
 * method is `register` — the same set that reaches `test` at all (`nextStep`'s own `connect` case;
 * `discover`/`listen`/`drone` all pivot to `register` before either step, `simulate` skips both).
 * **Never itself blocking**: `canAdvanceFromVerify` below is unconditionally `true` — this wave
 * resolves the plan's own open question OQ3 ("does a NO-GO verdict block, or only advise?", §10) as
 * advisory-only pending an operator's own answer, and separately, `vision.onboarding.probe.enabled`
 * defaults `false` (D17) so a probe attempt commonly 409s outright; neither case may strand an
 * operator mid-wizard over a read this platform cannot promise will succeed.
 */
export type WizardStep = 'profile' | 'connect' | 'test' | 'verify' | 'create' | 'assign';

export const WIZARD_STEPS: readonly WizardStep[] = ['profile', 'connect', 'test', 'verify', 'create', 'assign'];

/**
 * The Connect step's entry points (docs/plans/done/UX-REWORK-PLAN.md §U-d — "the existing 3-choice connect
 * component (port / resource / simulate) stays"). `discover` is never the method a probe or a
 * create request is built against — picking "Use" on a scan candidate switches the method to
 * `register` with the candidate's fields prefilled, exactly like the pre-wizard Devices page did
 * (`useCandidate`, moved here verbatim in behavior).
 *
 * `listen` (docs/plans/active/DRONE-INFRA-PLAN.md I-b — "Listen for drones") is a fourth tile, alongside the
 * original three, for a MAVLink-heartbeat-only scan (`drone-scan-logic.ts`): it behaves exactly
 * like `discover` in every gate below — never itself advanceable — since picking an unclaimed
 * vehicle flips the method to `register` with the connect form prefilled
 * (`OnboardingStore#useDroneVehicle`), the same "candidate → register" pivot `discover` already
 * uses.
 *
 * `drone` (docs/plans/active/DRONE-INFRA-PLAN.md I-g, wave B — "Add a real drone") is a fifth tile: a guided
 * firmware×link picker plus parameterized copy-paste config snippets
 * (`drone-config-logic.ts#linkCompatibility`/`configSnippets`), entirely **sub-states of this one
 * Connect step** (`OnboardingStore#droneSubStep`, `'picker' | 'config'`, deliberately not part of
 * this file's own `WizardStep` machine — see that signal's own doc comment). It behaves exactly
 * like `discover`/`listen` in every gate below too — never itself advanceable — for the identical
 * reason: once the operator has configured the aircraft, `OnboardingStore#finishDroneConfigAndListen`
 * flips the method straight to `listen`, handing off to that method's own already-existing scan/
 * pick/register-pivot flow verbatim (per the plan's own "listen is the scan" wording — no second
 * scanner, no second vehicle-list UI). `drone` therefore never itself reaches `test`/`create`.
 */
export type ConnectMethod = 'register' | 'discover' | 'simulate' | 'listen' | 'drone';

/**
 * The next step for the wizard's own forward-only "Next"/submit action. `simulate` skips `test`
 * entirely (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1's own wording: "simulate path may skip step 3, it
 * always produces frames" — there is no separate device to probe ahead of starting it; starting the
 * simulation *is* the proof it works). Calling this on `'create'` is a caller bug — there is no step
 * after it — so it is left undefined behavior (returns `'create'`, a harmless no-op) rather than
 * throwing, matching every other total function in this file.
 */
export function nextStep(current: WizardStep, method: ConnectMethod | null): WizardStep {
  switch (current) {
    case 'profile':
      return 'connect';
    case 'connect':
      return method === 'simulate' ? 'create' : 'test';
    case 'test':
      return 'verify';
    case 'verify':
    case 'create':
      return 'create';
    case 'assign':
      return 'assign'; // terminal, like 'create' above — see this type's own doc comment.
  }
}

/** The inverse of {@link nextStep} — back-navigable, per the plan's own "stepper … back-navable" ask. */
export function prevStep(current: WizardStep, method: ConnectMethod | null): WizardStep {
  switch (current) {
    // `assign`'s own immediate predecessor is `create`, kept only for totality — see this type's
    // own doc comment for why `onboarding.html` never actually renders a Back button here.
    case 'assign':
      return 'create';
    case 'create':
      return method === 'simulate' ? 'connect' : 'verify';
    case 'verify':
      return 'test';
    case 'test':
      return 'connect';
    case 'connect':
    case 'profile':
      return 'profile';
  }
}

// --- Poka-yoke: advance gating (docs/plans/done/UX-REWORK-PLAN.md §U-a2 rule 1 — prevention over confirmation) -

/** Profile step: a name and a category are the only two facts every asset must have. */
export function canAdvanceFromProfile(displayName: string, category: string): boolean {
  return displayName.trim().length > 0 && category.trim().length > 0;
}

/** Only the file-based simulate modes need a server-side path; `synthetic`/`testDrone` need nothing. */
export function simulateNeedsVideoPath(mode: SimulateMode): boolean {
  return mode === 'direct' || mode === 'rtsp';
}

/** Enough of the Connect step's draft state to decide whether "Next" may be pressed. */
export interface ConnectDraft {
  readonly method: ConnectMethod | null;
  readonly protocol: string;
  readonly uri: string;
  readonly simMode: SimulateMode;
  readonly simVideoPath: string;
}

/**
 * Connect step: `register` needs a protocol and a URI (mirrors the pre-wizard Register form's own
 * `canSubmit`); `discover`/`listen`/`drone` alone (no candidate chosen yet — see
 * {@link ConnectMethod}'s doc comment) never satisfy this, since none of the three carries a
 * protocol/URI of its own until "Use" flips the method to `register`; `simulate` needs a video path
 * only for the two file-based modes.
 */
export function canAdvanceFromConnect(draft: ConnectDraft): boolean {
  switch (draft.method) {
    case 'register':
      return draft.protocol.trim().length > 0 && draft.uri.trim().length > 0;
    case 'simulate':
      return !simulateNeedsVideoPath(draft.simMode) || draft.simVideoPath.trim().length > 0;
    case 'discover':
    case 'listen':
    case 'drone':
    case null:
      return false;
  }
}

/**
 * Protocols that carry telemetry and no video, so the Test step must stop asking them for a frame
 * (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B3).
 *
 * A list rather than a single string because the *concept* is "telemetry-only link", not "mavlink":
 * every `infra/edge/` recipe (ELRS backpack, ESP32/WiFi bridge, companion computer) is one, they
 * simply all speak `mavlink` today. A second entry belongs here, not in a widened conditional.
 *
 * This only picks the wording. The backend decides what a probe actually does — it asks the
 * telemetry sources whichever protocol arrives — so a protocol missing from this list still probes
 * correctly, it is just described in video terms while it does.
 */
export const TELEMETRY_ONLY_PROTOCOLS: readonly string[] = ['mavlink'];

/** See {@link TELEMETRY_ONLY_PROTOCOLS}. Case/whitespace-tolerant, like the backend's own default. */
export function isTelemetryOnlyProtocol(protocol: string | null | undefined): boolean {
  return protocol !== null && protocol !== undefined
    && TELEMETRY_ONLY_PROTOCOLS.includes(protocol.trim().toLowerCase());
}

/**
 * Test step (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1, poka-yoke): "cannot advance to save while the last
 * probe failed" — for `register`/`discover` paths only. `simulate` never reaches this step at all
 * (see {@link nextStep}), so it trivially always may advance; kept as a real branch (not assumed
 * true by the caller) so a future caller that *does* invoke this for `simulate` gets the right
 * answer rather than undefined behavior. `lastProbeOk` is `undefined` before any probe has run —
 * exactly the "not yet allowed" state a fresh Test step starts in.
 *
 * Deliberately unchanged by the telemetry-only work: a frameless probe still returns `ok: true`
 * (that is the whole point of the second 200 shape), so "the last probe succeeded" remains the one
 * question this gate asks. Only the wording around it had to learn the difference.
 */
export function canAdvanceFromTest(method: ConnectMethod | null, lastProbeOk: boolean | undefined): boolean {
  return method === 'simulate' || lastProbeOk === true;
}

/**
 * Verify step (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 3/O6): unconditionally
 * advanceable — see {@link WizardStep}'s own doc comment for why a vehicle-link observation may
 * never strand an operator mid-wizard. Takes `method` only so this function's own signature makes
 * the "does not actually depend on it" fact checkable at every call site, the same total-function
 * shape every other `canAdvanceFrom*` gate here uses, rather than a bare `true` a caller could
 * mistake for a stub.
 */
export function canAdvanceFromVerify(method: ConnectMethod | null): boolean {
  void method;
  return true;
}

// --- Request builders --------------------------------------------------------------------------

/** What the Test step's probe call needs — the same three fields a register candidate carries. */
export interface ProbeConnectionDraft {
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
}

/** Builds `POST /api/devices/probe`'s body, trimming protocol/URI like every other request builder. */
export function buildProbeRequest(draft: ProbeConnectionDraft): ProbeDeviceRequest {
  const options = draft.options;
  return {
    protocol: draft.protocol.trim(),
    uri: draft.uri.trim(),
    ...(options && Object.keys(options).length > 0 ? { options } : {}),
  };
}

/**
 * Builds `POST /api/onboarding/probe`'s body for the Verify step (§3.1 stage 3/O6) — the same
 * trimmed `{protocol, uri, options}` shape {@link buildProbeRequest} builds for the Test step's own
 * video probe, since `ProbeCandidateRequest` deliberately mirrors `ProbeDeviceRequest` (its own
 * doc comment).
 */
export function buildVerifyRequest(draft: ProbeConnectionDraft): ProbeCandidateRequest {
  return buildProbeRequest(draft);
}

/** The Profile step's facts, trimmed and validated already by {@link canAdvanceFromProfile}. */
export interface ProfileDraft {
  readonly displayName: string;
  readonly registrationNumber: string;
  readonly category: string;
}

/**
 * Builds `POST /api/assets`'s body for the `register`/`discover` Connect paths (docs/plans/done/UX-REWORK-PLAN.md
 * §U-d item 2 — "closes the orphaned-device dead end": one call creates the asset *and* registers
 * its device, via `CreateAssetRequest#devices`, rather than registering a device first and hoping
 * something files it under an asset later). The device is given the asset's own display name — this
 * wizard makes assets, not devices, first-class; there is no separate device-naming field anywhere
 * in it.
 */
export function buildCreateAssetRequest(
  profile: ProfileDraft,
  connect: ProbeConnectionDraft,
): CreateAssetRequest {
  const displayName = profile.displayName.trim();
  const attributes = withRegistrationNumber({}, profile.registrationNumber);
  return {
    displayName,
    category: profile.category.trim(),
    ...(Object.keys(attributes).length > 0 ? { attributes } : {}),
    devices: [
      {
        name: displayName,
        protocol: connect.protocol.trim(),
        uri: connect.uri.trim(),
        ...(connect.options && Object.keys(connect.options).length > 0 ? { options: connect.options } : {}),
      },
    ],
  };
}

/**
 * Builds the `PATCH /api/assets/{id}` body applied *after* the Simulate Connect path's
 * `POST /api/simulations` call (docs/plans/done/UX-REWORK-PLAN.md §U-d item 1 — "Simulate path routes through
 * POST /api/simulations as today then applies name/photo/attributes via PATCH+PUT"). Deliberately
 * carries no `category` — `DefaultSimulationService` always creates the asset under the fixed
 * `simulated` category regardless of what the Profile step's category picker shows, so sending one
 * here would either no-op or fight the backend's own invariant; the Profile step's category field is
 * simply not honored on this one path (see this module's own onboarding wizard doc comment/MODULE.md
 * for this as a documented, deliberate gap, not an oversight).
 */
export function buildPostSimulationAssetEdit(
  profile: Pick<ProfileDraft, 'displayName' | 'registrationNumber'>,
): AssetEdit {
  const displayName = profile.displayName.trim();
  const attributes = withRegistrationNumber({}, profile.registrationNumber);
  return {
    ...(displayName.length > 0 ? { displayName } : {}),
    ...(Object.keys(attributes).length > 0 ? { attributes } : {}),
  };
}

// --- Step 5: "Who flies this?" (docs/plans/done/OPS-UX-PLAN.md §2 A3) -------------------------------

/** Least→most privileged, mirroring the domain's own `Role` ordinal — used only to find the *highest* of a set of memberships below. */
const ROLE_RANK: Readonly<Record<Role, number>> = { PILOT: 0, MANAGER: 1, ADMIN: 2 };

/**
 * The group a newly-created asset silently belongs to (docs/conclusions/OPS-UX-REVIEW.md §A4 — `POST
 * /api/assets` "sets `Ownership` from the creator"). Mirrors the backend's own rule byte-for-byte
 * (`VisionUserDetails#ownershipOf`, station/vision-app): the group tied to the creator's **highest**
 * `Role` membership, ties broken by encounter order (the backend's own tie-break is undocumented as
 * stable either — see that method's own comment) — never a group the caller has to pick, since the
 * wizard's Profile/Connect/Test/Create steps never ask for one. Returns `undefined` only for a
 * membership-less account (the "couldn't determine your group" honest-degrade case downstream).
 *
 * **Known dev-parity gap** (`vision.auth.enabled=false`): the fixed dev-admin principal's own
 * `MeResponse.memberships` carries a synthetic group id that does not match the real seeded
 * admin/manager/pilot users' own "Root" group id (two different, unrelated ids that merely share a
 * display name) — so this function resolves *a* group correctly, but `pilotsInGroup` below will
 * never find a match against it in that mode. This is a frontend-only wave (docs/plans/done/OPS-UX-PLAN.md
 * §2) with no backend change available to fix the mismatch; the picker's own honest empty state
 * ("nobody in *that* group is a pilot yet") is still a true statement about the data this app can
 * see, never a fabrication — see `onboarding-store.ts#enterAssignStep`'s own note.
 */
export function creatorOwnershipGroup(memberships: readonly Membership[]): Membership | undefined {
  return memberships.reduce<Membership | undefined>((best, candidate) => {
    if (!best || ROLE_RANK[candidate.role] > ROLE_RANK[best.role]) {
      return candidate;
    }
    return best;
  }, undefined);
}

/**
 * Every enabled user holding a `PILOT` membership in `groupId` — the wizard's own candidate list,
 * same `enabled`-only filter `features/asset-detail/pilots-card.ts#assignable` already applies (a
 * disabled account can't sign in to fly anything). `undefined`/unresolved `groupId` yields no
 * candidates at all, never every pilot app-wide — offering the wrong team's roster would be worse
 * than offering none (docs/plans/done/OPS-UX-PLAN.md §2 A3: "offer the group's pilots").
 */
export function pilotsInGroup(users: readonly UserSummary[], groupId: string | undefined): readonly UserSummary[] {
  if (!groupId) {
    return [];
  }
  return users.filter(
    (user) => user.enabled && user.memberships.some((m) => m.groupId === groupId && m.role === 'PILOT'),
  );
}

/**
 * The picker's default selection (docs/plans/done/OPS-UX-PLAN.md §2 A3 — "Default selection: the creator
 * when they are a pilot in that group, else none"). Deliberately checks the creator's *own* role
 * within the resolved ownership group, not their global `topRole`: a MANAGER/ADMIN's ownership
 * group is by construction the group of their own highest-role membership (see
 * {@link creatorOwnershipGroup}'s own doc comment), so their role *there* is never `PILOT` — this
 * only ever preselects the creator for the solo-pilot self-registration case (a plain PILOT's own
 * single membership, still reachable today ahead of the backend's own wave-C gate landing).
 */
export function defaultPilotSelection(creatorUserId: string, ownershipGroup: Membership | undefined): readonly string[] {
  return ownershipGroup?.role === 'PILOT' ? [creatorUserId] : [];
}
