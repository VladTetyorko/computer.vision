import type {
  AssetEdit,
  AssetIdentity,
  CreateAssetRequest,
  Membership,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  Role,
  UserSummary,
} from '../../core/api/models';
import { fitOutDeviceSpecs, type FitOutRows } from '../../core/onboarding/fit-out-logic';
import type { SimulateMode } from '../../core/fleet/simulation-logic';

/**
 * Pure logic behind the onboarding wizard (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave W6):
 * step-state transitions, poka-yoke advance gating, and request builders. Split out so it is
 * unit-testable without HTTP, the router, or a canvas — mirrors every other `*-logic.ts` module in
 * this app (`features/devices/devices-page-logic.ts`, `core/telemetry/telemetry-logic.ts`).
 *
 * `OnboardingStore` (the wizard's own "component store", provided per-route like
 * `TelemetryStore`/`DetectionsStore`) is the only caller — it owns every signal and orchestrates the
 * actual HTTP calls, but defers every yes/no and every request shape to the functions here. The
 * fit-out table's own row semantics (one row per role, `find`/`simulate`/`none`) live in
 * `core/onboarding/fit-out-logic.ts` instead of here — that module is framework/feature-free and
 * this one is not (`ProbeDeviceRequest` etc. are wire types, but this file also owns the pilot/
 * ownership-group logic below, which is onboarding-specific, not a generic core concern).
 */

/**
 * The wizard's six steps. Five are visible in the stepper (see {@link visibleSteps}); **`sysid`**
 * never is, exactly like the pre-W6 wizard's own `sysid` step — the wizard reaches it only via an
 * explicit call from `OnboardingStore#finishCreate`, when the just-created asset's own Prove-step
 * probe collided with a sysid an already-registered device claims
 * (`sysid-collision-logic.ts#detectSysidCollision`); every other path skips straight to `handover`.
 *
 * - **`identify`** (was `profile`): name, category, photo, plus serial/make/model/registration
 *   (docs/plans/active/WAREHOUSE-UX-PLAN.md D1). If the chosen category is `connected: false`
 *   (equipment — a battery, a spare prop), this step's own action button reads "Receive" instead of
 *   "Next" and performs the create call directly (`OnboardingStore#receiveEquipmentAsset`), skipping
 *   `connect`/`prove`/`register` as rendered steps entirely — `nextStep`'s own `identify` case below
 *   still defines a `register` target for totality, but no real equipment flow ever calls `next()`
 *   from this step to reach it.
 * - **`connect`**: the fit-out table (`core/onboarding/fit-out-logic.ts`) — one row per role (Sense/
 *   Sight), replacing the pre-W6 five-tile single-device Connect step. Closes coupling C2: a vehicle
 *   with both a real flight controller and a real camera registers both in one visit.
 * - **`prove`** (was `test` + `verify`, merged): runs the Test + Verify probes per filled `find` row,
 *   results shown per row (`OnboardingStore`'s own per-role Prove state). Skipped entirely when no
 *   row needs proving (`fit-out-logic.ts#needsProve`) — generalizing the pre-W6 "simulate skips test"
 *   rule to every row, not just a single wizard-wide method.
 * - **`register`** (was `create`): unchanged in substance — one `POST /api/assets`, now with
 *   `identity` and N devices (`fit-out-logic.ts#fitOutDeviceSpecs`) instead of exactly one.
 * - **`handover`** (was `assign`): "Issue to" a custodian (`VisionApi#setAssetCustody` +
 *   `assignPilot`, since `issue` alone does not create a pilot assignment) or "Leave in stock". Ends
 *   in a completed sub-state naming the next verb — readiness for a connected vehicle, inventory for
 *   equipment — rather than an automatic router redirect (`OnboardingStore#handoverOutcome`).
 *
 * Like `sysid`, `handover` is never reached via `next()` either — only via a successful
 * `finishCreate`/`receiveEquipmentAsset` — and is never back-navigable into `register` (the asset
 * already exists by the time it renders). `nextStep`/`prevStep` still define total cases for both
 * purely so they stay total over the whole `WizardStep` union; `onboarding.html`'s own footer is what
 * actually withholds the generic Back/Next buttons on these two steps.
 */
export type WizardStep = 'identify' | 'connect' | 'prove' | 'register' | 'sysid' | 'handover';

/** Every step, used internally where the full universe matters (e.g. this file's own totality checks). Not what the stepper renders — see {@link visibleSteps}. */
export const WIZARD_STEPS: readonly WizardStep[] = ['identify', 'connect', 'prove', 'register', 'sysid', 'handover'];

/**
 * The stepper's own label row (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 — "Identify · Connect ·
 * Prove · Register · Hand over"). `sysid` is never included, on either branch — see
 * {@link WizardStep}'s own doc comment. An equipment category (`connected: false`) shows only the
 * two steps its own flow actually renders; a connected one shows all five, `prove` included even
 * though a particular visit might skip past it at runtime (the label row describes the wizard's
 * shape, not one run's actual path — same as the pre-W6 stepper always showing "Test"/"Verify" even
 * on the simulate path that skipped both).
 */
export function visibleSteps(connected: boolean): readonly WizardStep[] {
  return connected ? ['identify', 'connect', 'prove', 'register', 'handover'] : ['identify', 'handover'];
}

/** What {@link nextStep}/{@link prevStep} need to know beyond the current step. */
export interface StepContext {
  /** `Category#connected` for the chosen category — see {@link WizardStep}'s own `identify` case. */
  readonly connected: boolean;
  /** `fit-out-logic.ts#needsProve` for the current Connect draft. */
  readonly needsProve: boolean;
}

/**
 * The next step for the wizard's own forward-only "Next" action. Calling this on `'register'` is a
 * caller bug — there is no forward step from it via `next()` (`OnboardingStore#finishCreate` is what
 * actually advances past it, only after `POST /api/assets` succeeds) — so, like every other total
 * function in this file, it is left as a harmless no-op (returns `'register'`) rather than throwing.
 */
export function nextStep(current: WizardStep, ctx: StepContext): WizardStep {
  switch (current) {
    case 'identify':
      return ctx.connected ? 'connect' : 'register';
    case 'connect':
      return ctx.needsProve ? 'prove' : 'register';
    case 'prove':
    case 'register':
      return 'register';
    case 'sysid':
      return 'sysid'; // terminal, like 'register' above — see this type's own doc comment.
    case 'handover':
      return 'handover'; // terminal, like 'register' above — see this type's own doc comment.
  }
}

/** The inverse of {@link nextStep} — back-navigable, per the plan's own "stepper … back-navable" ask. */
export function prevStep(current: WizardStep, ctx: StepContext): WizardStep {
  switch (current) {
    // `handover`'s own immediate predecessor is `register`, kept only for totality — see
    // `WizardStep`'s own doc comment for why `onboarding.html` never renders a Back button here.
    case 'handover':
      return 'register';
    // `sysid`'s own immediate predecessor is conceptually `register` too — kept only for totality,
    // same as `handover` above; `onboarding.html` never renders a Back button on this step either.
    case 'sysid':
      return 'register';
    case 'register':
      if (!ctx.connected) {
        return 'identify'; // the equipment short-circuit never actually renders this step, but stays total.
      }
      return ctx.needsProve ? 'prove' : 'connect';
    case 'prove':
      return 'connect';
    case 'connect':
    case 'identify':
      return 'identify';
  }
}

// --- Poka-yoke: advance gating (docs/plans/done/UX-REWORK-PLAN.md §U-a2 rule 1 — prevention over confirmation) -

/** Identify step: a name and a category are the only two facts every asset must have — serial/make/model/registration are all optional. */
export function canAdvanceFromIdentify(displayName: string, category: string): boolean {
  return displayName.trim().length > 0 && category.trim().length > 0;
}

/** Only the file-based legacy-simulate modes need a server-side path; `synthetic`/`testDrone` need nothing. */
export function simulateNeedsVideoPath(mode: SimulateMode): boolean {
  return mode === 'direct' || mode === 'rtsp';
}

/**
 * Protocols that carry telemetry and no video, so a Prove-step row must stop asking them for a frame
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

// --- Request builders --------------------------------------------------------------------------

/** What the Prove step's probe/verify calls need — the same three fields a resolved fit-out row carries. */
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
 * Builds `POST /api/onboarding/probe`'s body for the Prove step's Verify half
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 3/O6) — the same trimmed
 * `{protocol, uri, options}` shape {@link buildProbeRequest} builds for its Test half, since
 * `ProbeCandidateRequest` deliberately mirrors `ProbeDeviceRequest` (its own doc comment).
 */
export function buildVerifyRequest(draft: ProbeConnectionDraft): ProbeCandidateRequest {
  return buildProbeRequest(draft);
}

/** The Identify step's facts, trimmed and validated already by {@link canAdvanceFromIdentify}. */
export interface IdentifyDraft {
  readonly displayName: string;
  readonly category: string;
  readonly registrationNumber: string;
  readonly serialNumber: string;
  readonly make: string;
  readonly model: string;
}

/**
 * Builds `CreateAssetRequest#identity`/`AssetEdit#identity` from the Identify step's draft —
 * `undefined` when every field is blank, never an object of empty strings. Registration rides here
 * now, not in `attributes` — `core/fleet/asset-attributes.ts`'s `registrationNumber` attribute-key
 * convention predates `dto.IdentityRequest`/D8's new first-class `registration` column and is left
 * untouched for `features/asset-detail/**`'s own inline edit (a different wave's file scope); see
 * this wave's WAREHOUSE-UX-CONTEXT.md handoff for the discrepancy this leaves between the two paths.
 */
export function buildIdentityRequest(
  draft: Pick<IdentifyDraft, 'registrationNumber' | 'serialNumber' | 'make' | 'model'>,
): AssetIdentity | undefined {
  const registration = draft.registrationNumber.trim();
  const serialNumber = draft.serialNumber.trim();
  const make = draft.make.trim();
  const model = draft.model.trim();
  const identity: AssetIdentity = {
    ...(serialNumber.length > 0 ? { serialNumber } : {}),
    ...(make.length > 0 ? { make } : {}),
    ...(model.length > 0 ? { model } : {}),
    ...(registration.length > 0 ? { registration } : {}),
  };
  return Object.keys(identity).length > 0 ? identity : undefined;
}

/**
 * Builds `POST /api/assets`'s body for the multi-device fit-out path (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.4 wave W6 — closes coupling C2). `rows` may be empty (the equipment short-circuit — `devices` is
 * then simply omitted, which `AssetSpec#toSpec` treats identically to an explicit empty list) or
 * carry one or two filled rows; never called on `fit-out-logic.ts#usesLegacySimulationPath`'s own
 * true case, which routes through the simulation request builders instead.
 */
export function buildCreateAssetRequest(identify: IdentifyDraft, rows: FitOutRows): CreateAssetRequest {
  const displayName = identify.displayName.trim();
  const devices = fitOutDeviceSpecs(rows, displayName);
  const identity = buildIdentityRequest(identify);
  return {
    displayName,
    category: identify.category.trim(),
    ...(identity ? { identity } : {}),
    ...(devices.length > 0 ? { devices } : {}),
  };
}

/**
 * Builds the `PATCH /api/assets/{id}` body applied *after* the legacy Simulate Connect path's
 * `POST /api/simulations` call (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §7/§9 —
 * `fit-out-logic.ts#usesLegacySimulationPath`). Deliberately carries no `category` —
 * `DefaultSimulationService` always creates the asset under the fixed `simulated` category
 * regardless of what the Identify step's category picker shows, so sending one here would either
 * no-op or fight the backend's own invariant; the Identify step's category field is simply not
 * honored on this one path (a documented, deliberate gap, not an oversight).
 */
export function buildPostSimulationAssetEdit(
  identify: Pick<IdentifyDraft, 'displayName' | 'registrationNumber' | 'serialNumber' | 'make' | 'model'>,
): AssetEdit {
  const displayName = identify.displayName.trim();
  const identity = buildIdentityRequest(identify);
  return {
    ...(displayName.length > 0 ? { displayName } : {}),
    ...(identity ? { identity } : {}),
  };
}

// --- Hand over: "Who takes this?" (docs/plans/done/OPS-UX-PLAN.md §2 A3; docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 D3) --

/** Least→most privileged, mirroring the domain's own `Role` ordinal — used only to find the *highest* of a set of memberships below. `VIEWER` (docs/plans/active/AUTH-ROLES-PLAN.md, wave B0a/B6) ranks below `PILOT`, same as the real enum's own ordinal order. */
const ROLE_RANK: Readonly<Record<Role, number>> = { VIEWER: 0, PILOT: 1, MANAGER: 2, ADMIN: 3 };

/**
 * The group a newly-created asset silently belongs to (docs/conclusions/OPS-UX-REVIEW.md §A4 — `POST
 * /api/assets` "sets `Ownership` from the creator"). Mirrors the backend's own rule byte-for-byte
 * (`VisionUserDetails#ownershipOf`, station/vision-app): the group tied to the creator's **highest**
 * `Role` membership, ties broken by encounter order (the backend's own tie-break is undocumented as
 * stable either — see that method's own comment) — never a group the caller has to pick, since the
 * wizard's Identify/Connect/Prove/Register steps never ask for one. Returns `undefined` only for a
 * membership-less account (the "couldn't determine your group" honest-degrade case downstream).
 *
 * **Known dev-parity gap** (`vision.auth.enabled=false`): the fixed dev-admin principal's own
 * `MeResponse.memberships` carries a synthetic group id that does not match the real seeded
 * admin/manager/pilot users' own "Root" group id (two different, unrelated ids that merely share a
 * display name) — so this function resolves *a* group correctly, but `pilotsInGroup` below will
 * never find a match against it in that mode. This is a frontend-only gap with no backend change
 * available to fix the mismatch; the picker's own honest empty state ("nobody in *that* group is a
 * pilot yet") is still a true statement about the data this app can see, never a fabrication.
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
 * Every enabled user holding a `PILOT` membership in `groupId` — the Hand-over step's own custodian
 * candidate list, same `enabled`-only filter `features/asset-detail/pilots-card.ts#assignable`
 * already applies (a disabled account can't sign in to fly anything). `undefined`/unresolved
 * `groupId` yields no candidates at all, never every pilot app-wide — offering the wrong team's
 * roster would be worse than offering none.
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
 * single membership).
 */
export function defaultPilotSelection(creatorUserId: string, ownershipGroup: Membership | undefined): readonly string[] {
  return ownershipGroup?.role === 'PILOT' ? [creatorUserId] : [];
}
