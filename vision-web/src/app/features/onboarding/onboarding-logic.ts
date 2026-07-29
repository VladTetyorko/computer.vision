import type { AssetEdit, CreateAssetRequest, ProbeDeviceRequest } from '../../core/api/models';
import type { SimulateMode } from '../../core/fleet/simulation-logic';
import { withRegistrationNumber } from '../../core/fleet/asset-attributes';

/**
 * Pure logic behind the onboarding wizard (docs/UX-REWORK-PLAN.md §U-d): step-state transitions,
 * poka-yoke advance gating, and request builders. Split out so it is unit-testable without HTTP,
 * the router, or a canvas — mirrors every other `*-logic.ts` module in this app
 * (`features/devices/devices-page-logic.ts`, `core/telemetry/telemetry-logic.ts`).
 *
 * `OnboardingStore` (the wizard's own "component store", provided per-route like
 * `TelemetryStore`/`DetectionsStore`) is the only caller — it owns every signal and orchestrates
 * the actual HTTP calls, but defers every yes/no and every request shape to the functions here.
 */

/** The wizard's four steps, always in this order — `nextStep`/`prevStep` are the only way to move. */
export type WizardStep = 'profile' | 'connect' | 'test' | 'create';

export const WIZARD_STEPS: readonly WizardStep[] = ['profile', 'connect', 'test', 'create'];

/**
 * The Connect step's entry points (docs/UX-REWORK-PLAN.md §U-d — "the existing 3-choice connect
 * component (port / resource / simulate) stays"). `discover` is never the method a probe or a
 * create request is built against — picking "Use" on a scan candidate switches the method to
 * `register` with the candidate's fields prefilled, exactly like the pre-wizard Devices page did
 * (`useCandidate`, moved here verbatim in behavior).
 *
 * `listen` (docs/DRONE-INFRA-PLAN.md I-b — "Listen for drones") is a fourth tile, alongside the
 * original three, for a MAVLink-heartbeat-only scan (`drone-scan-logic.ts`): it behaves exactly
 * like `discover` in every gate below — never itself advanceable — since picking an unclaimed
 * vehicle flips the method to `register` with the connect form prefilled
 * (`OnboardingStore#useDroneVehicle`), the same "candidate → register" pivot `discover` already
 * uses.
 */
export type ConnectMethod = 'register' | 'discover' | 'simulate' | 'listen';

/**
 * The next step for the wizard's own forward-only "Next"/submit action. `simulate` skips `test`
 * entirely (docs/UX-REWORK-PLAN.md §U-d item 1's own wording: "simulate path may skip step 3, it
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
    case 'create':
      return 'create';
  }
}

/** The inverse of {@link nextStep} — back-navigable, per the plan's own "stepper … back-navable" ask. */
export function prevStep(current: WizardStep, method: ConnectMethod | null): WizardStep {
  switch (current) {
    case 'create':
      return method === 'simulate' ? 'connect' : 'test';
    case 'test':
      return 'connect';
    case 'connect':
    case 'profile':
      return 'profile';
  }
}

// --- Poka-yoke: advance gating (docs/UX-REWORK-PLAN.md §U-a2 rule 1 — prevention over confirmation) -

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
 * `canSubmit`); `discover`/`listen` alone (no candidate chosen yet — see {@link ConnectMethod}'s doc
 * comment) never satisfy this, since neither carries a protocol/URI of its own until "Use" flips the
 * method to `register`; `simulate` needs a video path only for the two file-based modes.
 */
export function canAdvanceFromConnect(draft: ConnectDraft): boolean {
  switch (draft.method) {
    case 'register':
      return draft.protocol.trim().length > 0 && draft.uri.trim().length > 0;
    case 'simulate':
      return !simulateNeedsVideoPath(draft.simMode) || draft.simVideoPath.trim().length > 0;
    case 'discover':
    case 'listen':
    case null:
      return false;
  }
}

/**
 * Test step (docs/UX-REWORK-PLAN.md §U-d item 1, poka-yoke): "cannot advance to save while the last
 * probe failed" — for `register`/`discover` paths only. `simulate` never reaches this step at all
 * (see {@link nextStep}), so it trivially always may advance; kept as a real branch (not assumed
 * true by the caller) so a future caller that *does* invoke this for `simulate` gets the right
 * answer rather than undefined behavior. `lastProbeOk` is `undefined` before any probe has run —
 * exactly the "not yet allowed" state a fresh Test step starts in.
 */
export function canAdvanceFromTest(method: ConnectMethod | null, lastProbeOk: boolean | undefined): boolean {
  return method === 'simulate' || lastProbeOk === true;
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

/** The Profile step's facts, trimmed and validated already by {@link canAdvanceFromProfile}. */
export interface ProfileDraft {
  readonly displayName: string;
  readonly registrationNumber: string;
  readonly category: string;
}

/**
 * Builds `POST /api/assets`'s body for the `register`/`discover` Connect paths (docs/UX-REWORK-PLAN.md
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
 * `POST /api/simulations` call (docs/UX-REWORK-PLAN.md §U-d item 1 — "Simulate path routes through
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
