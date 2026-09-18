import type {
  AssetEdit,
  AssetIdentity,
  CreateAssetRequest,
  DiscoveryCandidate,
  NetworkAddress,
  ProbeCandidateRequest,
  ProbeDeviceRequest,
  ProbeDeviceResult,
  VehicleProfile,
} from '../../core/api/models';
import {
  FIT_OUT_ROLES,
  fitOutDeviceSpecs,
  type FitOutFindMethod,
  type FitOutRole,
  type FitOutRowValue,
  type FitOutRows,
} from '../../core/onboarding/fit-out-logic';
import type { SimulateMode } from '../../core/fleet/simulation-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * Pure logic behind the onboarding wizard (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1,
 * wave W2): step-state transitions, poka-yoke advance gating, and request builders. Split out so it
 * is unit-testable without HTTP, the router, or a canvas — mirrors every other `*-logic.ts` module
 * in this app (`features/devices/devices-page-logic.ts`, `core/telemetry/telemetry-logic.ts`).
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
 * The wizard's six steps (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1 D2/D3 — replaces the
 * pre-W2 `'identify'|'connect'|'prove'|'register'|'sysid'|'handover'` union). Five are visible in
 * the rail (see {@link visibleSteps}); **`sysid`** never is, exactly like before — the wizard reaches
 * it only via an explicit call from `OnboardingStore#finishCreate`, when the just-created asset's
 * own Prove-step probe collided with a sysid an already-registered device claims
 * (`sysid-collision-logic.ts#detectSysidCollision`); every other path skips straight to `handover`.
 *
 * - **`source`** (was `connect`, moved first — D2): the "honest fork" — It comes to us / We go to
 *   it / Find it for me / Nothing to connect — feeding the same fit-out table
 *   (`core/onboarding/fit-out-logic.ts`) the pre-W2 wizard's Connect step did. "Nothing to connect"
 *   sets both rows to `none` with `equipmentConfirmed: true` (D3) — {@link isEquipmentPath} is what
 *   the rest of this file reads back, never a category fact chosen later.
 * - **`prove`**: runs the Test + Verify probes per filled `find` row not already pre-proven by a
 *   discovery-inbox candidate pick (§3.1's candidate-entrance table) — skipped entirely when no row
 *   still needs it.
 * - **`identify`** (moved after `source`/`prove` — D2 "the device names itself first"): name,
 *   category, photo, plus serial/make/model/registration. On the equipment path this step's own
 *   action button reads "Receive" instead of "Next" and performs the create call directly
 *   (`OnboardingStore#receiveEquipmentAsset`), skipping `attach` entirely — `nextStep`'s own
 *   `identify` case below still defines an `attach` target for totality, but no equipment flow ever
 *   calls `next()` from this step to reach it. The category picker itself is filtered to
 *   `connected: false` categories only on this path (D3).
 * - **`attach`** (was `register`) — the flow diagram's own "Attach" fork (§0.2): **new** creates the
 *   asset exactly as `register` used to (`POST /api/assets`, one call, N devices); **existing**
 *   attaches this visit's connection onto an already-registered asset instead — the candidate
 *   entrance's atomic `POST /api/discovery/inbox/{id}/attach` (C1) when this visit started from a
 *   discovery candidate, or a plain register-device-then-assign otherwise. The existing-asset path
 *   skips `handover`'s custodian picker (the asset already has one) straight to the terminal proof.
 * - **`handover`** (was `assign`): "Issue to" a custodian or "Leave in stock" for the **new**-asset
 *   path; for the **existing**-asset path this step renders only its own terminal outcome, never the
 *   picker. Ends in the two-half Sight/Sense proof + "Open cockpit ›" terminal screen (D9), not an
 *   automatic router redirect.
 *
 * Like `sysid`, `handover` is never reached via `next()` either — only via a successful
 * `finishCreate`/`receiveEquipmentAsset`/`attachToExisting` — and is never back-navigable into
 * `attach` post-creation. `nextStep`/`prevStep` still define total cases for both purely so they
 * stay total over the whole `WizardStep` union; `onboarding.html`'s own footer is what actually
 * withholds the generic Back/Next buttons on these two steps.
 *
 * - **`confirm`** (docs/plans/active/LINK-PAIRING-PLAN.md §3.7/§7, wave L4) — the "Found nearby" feed's
 *   own interstitial, reached only by clicking a card (`OnboardingStore#chooseFoundCandidate`), never
 *   via `next()`/`visibleSteps()` — exactly the same "hidden terminal-ish step" carve-out as `sysid`/
 *   `handover` above, kept out of the rail so it never competes with the real
 *   `source`/`prove`/`identify`/`attach` sequence for a rail slot. Shows the candidate's own detected
 *   facts, the one optional credentials field, and — on this screen's own "This is my old X" link —
 *   a shortcut into `attach`'s existing-asset picker (`OnboardingStore#attachExistingFromConfirm`).
 *   Its own Continue (`OnboardingStore#continueFromConfirm`) re-enters the ordinary
 *   `nextStep('source', ctx)` decision (the candidate's role is already pre-proven by the prefill, so
 *   that call already resolves straight to `identify`, skipping `prove` — no new transition rule
 *   needed); Back (`OnboardingStore#backFromConfirm`) clears the prefilled row and returns to
 *   `source`'s own found-nearby feed.
 */
export type WizardStep = 'source' | 'prove' | 'identify' | 'attach' | 'confirm' | 'sysid' | 'handover';

/** Every step, used internally where the full universe matters (e.g. this file's own totality checks). Not what the rail renders — see {@link visibleSteps}. */
export const WIZARD_STEPS: readonly WizardStep[] = ['source', 'prove', 'identify', 'attach', 'confirm', 'sysid', 'handover'];

/**
 * Both fit-out rows answered `—` — the equipment path (D3): a battery, a prop, a case, nothing that
 * connects. Only reachable past `source` via the fork's own "Nothing to connect" tile
 * (`fit-out-logic.ts#canAdvanceFromFitOut`'s `equipmentConfirmed` parameter), so checking the rows
 * alone (no separate flag to thread through every step) is sufficient here.
 */
export function isEquipmentPath(rows: FitOutRows): boolean {
  return FIT_OUT_ROLES.every((role) => rows[role].value === 'none');
}

/**
 * The rail's own visible steps (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1's frozen
 * pseudocode) — `sysid` never included, on any branch, same reason as before. `needsProve` is the
 * caller's own *effective* value (`OnboardingStore` excludes a role pre-proven by a discovery-inbox
 * candidate pick from `fit-out-logic.ts#needsProve`'s raw answer), not re-derived here — this stays
 * a pure function of exactly the two facts that decide shape.
 */
export function visibleSteps(rows: FitOutRows, needsProve: boolean): readonly WizardStep[] {
  if (isEquipmentPath(rows)) {
    return ['source', 'identify', 'handover'];
  }
  return needsProve
    ? ['source', 'prove', 'identify', 'attach', 'handover']
    : ['source', 'identify', 'attach', 'handover'];
}

/** What {@link nextStep}/{@link prevStep} need to know beyond the current step. */
export interface StepContext {
  /** {@link isEquipmentPath} for the current fit-out rows. */
  readonly equipment: boolean;
  /** The caller's own *effective* `fit-out-logic.ts#needsProve` — see {@link visibleSteps}'s own doc comment. */
  readonly needsProve: boolean;
}

/**
 * The next step for the wizard's own forward-only "Next" action. Calling this on `'attach'` is a
 * caller bug — there is no forward step from it via `next()` (`OnboardingStore#finishCreate` is what
 * actually advances past it, only after the create/attach call succeeds) — so, like every other
 * total function in this file, it is left as a harmless no-op (returns `'attach'`) rather than
 * throwing.
 */
export function nextStep(current: WizardStep, ctx: StepContext): WizardStep {
  switch (current) {
    case 'source':
      if (ctx.equipment) {
        return 'identify';
      }
      return ctx.needsProve ? 'prove' : 'identify';
    case 'prove':
      return 'identify';
    case 'identify':
      return ctx.equipment ? 'handover' : 'attach'; // the equipment short-circuit never actually calls next() here — see WizardStep's own identify doc comment.
    case 'attach':
      return 'attach'; // terminal via next() — see this function's own doc comment.
    case 'confirm':
      return 'confirm'; // never reached via next() — OnboardingStore#continueFromConfirm re-enters via 'source', see WizardStep's own confirm doc comment.
    case 'sysid':
      return 'sysid'; // terminal, same reason.
    case 'handover':
      return 'handover'; // terminal, same reason.
  }
}

/** The inverse of {@link nextStep} — back-navigable, per the plan's own "rail … back-navigable" ask. */
export function prevStep(current: WizardStep, ctx: StepContext): WizardStep {
  switch (current) {
    // `handover`'s own immediate predecessor is `identify` on the equipment path (there is no
    // `attach` step to return to) or `attach` otherwise — kept only for totality, same as `sysid`
    // below; `onboarding.html` never renders a Back button on either step.
    case 'handover':
      return ctx.equipment ? 'identify' : 'attach';
    case 'sysid':
      return 'attach';
    case 'attach':
      return 'identify';
    case 'confirm':
      return 'confirm'; // never reached via prev() — OnboardingStore#backFromConfirm sets 'source' directly, see WizardStep's own confirm doc comment.
    case 'identify':
      if (ctx.equipment) {
        return 'source';
      }
      return ctx.needsProve ? 'prove' : 'source';
    case 'prove':
      return 'source';
    case 'source':
      return 'source';
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
// `creatorOwnershipGroup`, `pilotsInGroup` and `defaultPilotSelection` used to live here. They moved
// to `core/org/pilot-logic.ts` verbatim (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5, wave W4)
// the moment the Inventory page's own "Issue to…" dialog became a second consumer of the same
// question — this file's standing "a second consumer moves shared logic to `core/`" precedent. The
// wizard imports them from there now; `onboarding-store.ts` re-exports nothing of its own.

// --- Terminal Ready screen: the two-half Sight/Sense proof (D9, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1) --
// "Sight ✓ first frame 1280×720 H.264 / Sense ✓ heartbeat, sysid 7 — ArduPilot rover", sourced from
// facts the wizard already fetched during Prove — never a fresh probe, never fabricated. A row this
// wizard never proved (a `simulate` row, or one filled straight from a discovery candidate — see
// `core/onboarding/intake-logic.ts`'s own doc comment for why that skips Prove) renders honestly
// unproven rather than a green tick it hasn't earned (CLAUDE.md degrade-honestly).

/** One half's terminal proof — `proven` gates the green check; `label` is always a plain fact, never a placeholder. */
export interface RoleProof {
  readonly proven: boolean;
  readonly label: string;
}

/** Sight's terminal proof from the Prove step's own Test result — `find` rows only. */
export function sightTerminalProof(rowValue: FitOutRowValue, result: ProbeDeviceResult | null | undefined): RoleProof {
  if (rowValue === 'none') {
    return { proven: false, label: '—' };
  }
  if (rowValue === 'simulate') {
    return { proven: false, label: 'simulated, not yet verified' };
  }
  if (!result || !result.ok) {
    return { proven: false, label: 'not yet verified' };
  }
  const dims = result.widthPx && result.heightPx ? `${result.widthPx}×${result.heightPx}` : undefined;
  const bits = [dims, result.codec].filter((bit): bit is string => Boolean(bit));
  return { proven: true, label: bits.length > 0 ? `first frame ${bits.join(' ')}` : 'first frame received' };
}

/** Sense's terminal proof from the Prove step's own Verify result — `find` rows only. */
export function senseTerminalProof(rowValue: FitOutRowValue, result: VehicleProfile | null | undefined): RoleProof {
  if (rowValue === 'none') {
    return { proven: false, label: '—' };
  }
  if (rowValue === 'simulate') {
    return { proven: false, label: 'simulated, not yet verified' };
  }
  if (!result) {
    return { proven: false, label: 'not yet verified' };
  }
  const bits = ['heartbeat'];
  if (result.sysid !== null) {
    bits.push(`sysid ${result.sysid}`);
  }
  const trailer = [result.firmware, result.vehicleKind].filter((bit): bit is string => Boolean(bit)).join(' ');
  return { proven: true, label: trailer.length > 0 ? `${bits.join(', ')} — ${trailer}` : bits.join(', ') };
}

// --- `source` step: the honest fork (D3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §0.4) ----------------
// Four tiles, one per row of the fork table: **passive** ("It comes to us" — the standing MAVLink
// lobby / mediamtx push, `core/onboarding/intake-logic.ts`'s own waiting room), **manual** ("We go
// to it" — both roles now get the plain register-form, fixing D3's Sight-only defect), **scan**
// ("Find it for me" — the five existing scanners, unchanged plumbing), **equipment** ("Nothing to
// connect" — both rows `—`, `equipmentConfirmed: true`). `OnboardingStore` holds the chosen mode as
// its own orchestration-level signal, orthogonal to each row's own `findMethod` — a row already
// filled from a discovery-candidate entrance renders as its own resolved summary regardless of
// which tile is selected.

export type SourceMode = 'passive' | 'manual' | 'scan' | 'equipment';

/**
 * Which fit-out row a discovery candidate's method belongs on — `DiscoveryCandidate` carries no
 * `capabilities` field the way `Device` does (so `fit-out-logic.ts#roleForDevice` cannot be reused
 * verbatim here), but every method this app's scanners report is already role-specific by
 * construction: `mavlink` is the only telemetry-carrying one, everything else (`onvif`/`mdns`/
 * `v4l2`/`mediamtx`) is video.
 */
export function roleForDiscoveryMethod(method: string): FitOutRole {
  return method.toLowerCase() === 'mavlink' ? 'sense' : 'sight';
}

/**
 * One fit-out row's resolved fields, prefilled straight from a discovery-inbox candidate
 * (`?candidateId=` entrance, §3.1's candidate-entrance table) — `undefined` when the candidate has
 * no suggested stream yet (a bare heartbeat sighting the sweep hasn't resolved further), so the
 * caller degrades honestly by leaving the row empty rather than filling it with nothing. `findMethod`
 * is chosen for the row's own display only (`fit-out-logic.ts#FIT_OUT_FIND_METHODS` is a
 * template-rendering lookup, not an enforced constraint) — `listen`/`discover`, never `register`,
 * since this data arrived passively, not typed by the operator.
 */
export interface DiscoveryCandidatePrefill {
  readonly role: FitOutRole;
  readonly findMethod: FitOutFindMethod;
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Readonly<Record<string, string>>;
  readonly displayName: string;
  readonly category?: string;
}

export function prefillFromDiscoveryCandidate(candidate: DiscoveryCandidate): DiscoveryCandidatePrefill | undefined {
  if (!candidate.suggestedStreamProtocol || !candidate.suggestedStreamUri) {
    return undefined;
  }
  const role = roleForDiscoveryMethod(candidate.method);
  return {
    role,
    findMethod: role === 'sense' ? 'listen' : 'discover',
    protocol: candidate.suggestedStreamProtocol,
    uri: candidate.suggestedStreamUri,
    options: candidate.suggestedStreamOptions,
    displayName: candidate.name,
    category: candidate.suggestedCategory,
  };
}

/**
 * The "It comes to us" tile's own push-address card (§3.2 C3, wave U6) — composed from
 * `GET /api/system/network`'s LAN-first `addresses` plus the optional `videoPushPort`/
 * `videoPushPathPrefix`, never fabricated. `undefined` whenever either optional field is absent
 * (push is unconfigured on this station) or no address is known at all — the caller then omits the
 * card with an honest reason instead of showing a broken URL (CLAUDE.md degrade-honestly).
 *
 * The trailing path segment is deliberately a placeholder, not a real name: this card renders on the
 * `source` step, *before* `identify` ever asks for one (D2 — Source now comes first), so there is no
 * asset name yet to compose in. `base` already ends in the resolved `pathPrefix`; the caller renders
 * `placeholder` as an obviously-fill-this-in run (e.g. `<em>`), mirroring how API docs write
 * `<your-bucket-name>` rather than a real, if wrong, example.
 */
export interface PushAddressCard {
  readonly base: string;
  readonly placeholder: string;
}

export function composePushAddress(
  addresses: readonly NetworkAddress[],
  videoPushPort: number | undefined,
  videoPushPathPrefix: string | undefined,
): PushAddressCard | undefined {
  if (videoPushPort === undefined || videoPushPathPrefix === undefined) {
    return undefined;
  }
  const address = addresses.find((a) => a.kind === 'LAN') ?? addresses[0];
  if (!address) {
    return undefined;
  }
  return { base: `rtsp://${address.address}:${videoPushPort}/${videoPushPathPrefix}`, placeholder: 'your-camera-name' };
}

/**
 * "last heard 12s ago" / "last scanned 12s ago" — one relative-age render shared by the P1
 * diagnostic panel's own facts (`lastDatagramAt`, a source's `lastScanAt`), reusing `humanAge`
 * (`core/telemetry/telemetry-logic.ts`) the same way `discovery-inbox-logic.ts#candidateAgeLabel`
 * already does for a candidate card — `nowMs` is threaded in by the caller, never `Date.now()` here,
 * so this stays a pure, clock-free function. `undefined` for a missing/unparseable timestamp, never
 * a fabricated "just now".
 */
export function relativeAge(iso: string | undefined, nowMs: number): string | undefined {
  if (!iso) {
    return undefined;
  }
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) {
    return undefined;
  }
  return `${humanAge(Math.max(0, (nowMs - parsed) / 1000))} ago`;
}
