import type { ActiveStream, Capability, CreateAssetDeviceSpec, Device } from '../api/models';
import { freshness } from '../telemetry/telemetry-logic';

/**
 * The Connect step's fit-out table (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §6,
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4 wave W6): one row per role — **Sense** (telemetry) and
 * **Sight** (video) — replacing the pre-W6 wizard's single Connect method. Closes coupling C2
 * (`buildCreateAssetRequest` used to emit exactly one `DeviceSpec`): a vehicle with both a real
 * flight controller and a real camera now registers both devices in one visit.
 *
 * Framework-free by design (no Angular, no HTTP) — `OnboardingStore` is the only caller, owning the
 * actual finder sub-forms (`register`/`discover`/`listen`/`drone`, unchanged) and threading their
 * resolved `{protocol, uri, options}` through the row shape this module defines.
 */

/** The fit-out table's two rows. Fixed, not data-driven — every category wants exactly these two. */
export type FitOutRole = 'sense' | 'sight';

export const FIT_OUT_ROLES: readonly FitOutRole[] = ['sense', 'sight'];

export const FIT_OUT_ROLE_LABELS: Readonly<Record<FitOutRole, string>> = {
  sense: 'Sense',
  sight: 'Sight',
};

export const FIT_OUT_ROLE_HINTS: Readonly<Record<FitOutRole, string>> = {
  sense: 'Telemetry — the flight controller / autopilot link.',
  sight: 'Video — the camera stream.',
};

/**
 * Which of the wizard's existing finders each row offers (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §6's
 * mockup: "listen / drone" on the telemetry row, "register / discover" on the video row). Each
 * finder is fixed to exactly one role — a scanner never needs to know which row it is feeding — so
 * `OnboardingStore`'s existing `scan`/`useCandidate` (Sight) and `scanForDrones`/`useDroneVehicle`/
 * drone-config methods (Sense) keep their pre-W6 bodies verbatim, only now writing into that row's
 * slice of state instead of the old flat signals.
 */
export type FitOutFindMethod = 'listen' | 'drone' | 'register' | 'discover';

export const FIT_OUT_FIND_METHODS: Readonly<Record<FitOutRole, readonly FitOutFindMethod[]>> = {
  sense: ['listen', 'drone'],
  sight: ['register', 'discover'],
};

/** A row's top-level choice — the fit-out table's three cell values (`find… · simulate · —`). */
export type FitOutRowValue = 'find' | 'simulate' | 'none';

/** One option row of a resolved connection's advanced `options` map (mirrors the pre-W6 Connect form's own shape). */
export interface FitOutOptionRow {
  readonly key: string;
  readonly value: string;
}

/**
 * One fit-out row's full draft state. `protocolSelect`/`customProtocol` are the register-form's own
 * `<select>`/free-text pair (`protocols.ts#ProtocolSelection`) — kept as raw UI state here rather
 * than a single resolved `protocol` string so the row can round-trip through the same register-form
 * template the pre-W6 Connect step already used, unchanged. Use {@link effectiveProtocol} to resolve
 * the string a request actually sends.
 */
export interface FitOutRowDraft {
  readonly role: FitOutRole;
  readonly value: FitOutRowValue;
  readonly findMethod: FitOutFindMethod | null;
  readonly protocolSelect: string;
  readonly customProtocol: string;
  readonly uri: string;
  readonly options: readonly FitOutOptionRow[];
}

export function emptyFitOutRow(role: FitOutRole): FitOutRowDraft {
  return { role, value: 'none', findMethod: null, protocolSelect: '', customProtocol: '', uri: '', options: [] };
}

export type FitOutRows = Readonly<Record<FitOutRole, FitOutRowDraft>>;

export function emptyFitOutRows(): FitOutRows {
  return { sense: emptyFitOutRow('sense'), sight: emptyFitOutRow('sight') };
}

/** The register form's `<select>`'s custom-protocol sentinel, duplicated from `protocols.ts` to keep this module framework/feature-free — see that file's own `CUSTOM_PROTOCOL_OPTION` doc comment. */
const CUSTOM_PROTOCOL_SENTINEL = '__custom__';

/** The protocol string a request actually sends for this row — the free-text field when the sentinel is selected, otherwise the select's own value. */
export function effectiveProtocol(row: FitOutRowDraft): string {
  return row.protocolSelect === CUSTOM_PROTOCOL_SENTINEL ? row.customProtocol : row.protocolSelect;
}

/** `options` rows with a blank key filtered out, collapsed into the wire's `Record<string,string>` shape — `undefined` when nothing remains, matching every other request builder's own trim-and-omit convention. */
export function collectRowOptions(row: FitOutRowDraft): Record<string, string> | undefined {
  const entries = row.options
    .map((o) => [o.key.trim(), o.value] as const)
    .filter(([key]) => key.length > 0);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

/** A row counts as "filled" once it carries something a create request could actually use. */
export function isRowFilled(row: FitOutRowDraft): boolean {
  switch (row.value) {
    case 'none':
      return false;
    case 'simulate':
      return true;
    case 'find':
      return effectiveProtocol(row).trim().length > 0 && row.uri.trim().length > 0;
  }
}

/**
 * Source step: at least one row must be filled to continue (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md
 * §6's own mockup footer — "at least one row filled"). Both may be filled at once.
 *
 * `equipmentConfirmed` (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1 D3) relaxes this: **both
 * rows `—` is legal when the operator has explicitly answered "nothing to connect" on the fork** —
 * a battery, a prop, a case. Defaults `false` so every existing caller (pre-dating this wave) keeps
 * its old behavior byte-for-byte; the fork's own fourth tile is the only place that ever passes
 * `true`. This is what removes the old circularity where equipment-ness was a category fact chosen
 * *after* Connect, forcing both rows to `—` implicitly instead of by a deliberate, first-class answer.
 */
export function canAdvanceFromFitOut(rows: FitOutRows, equipmentConfirmed = false): boolean {
  return equipmentConfirmed || FIT_OUT_ROLES.some((role) => isRowFilled(rows[role]));
}

/**
 * Whether a `find` row's Prove result is what the Connect step's own poka-yoke rule cares about —
 * only rows actually resolved to a real link ever probe at all; a `simulate`/`none` row has nothing
 * to test (mirrors the pre-W6 `canAdvanceFromTest`'s own "simulate skips the step" rule, generalized
 * per row instead of wizard-wide).
 */
export function needsProve(rows: FitOutRows): boolean {
  return FIT_OUT_ROLES.some((role) => rows[role].value === 'find');
}

/** Prove step: every `find` row must have a successful probe; `simulate`/`none` rows need nothing. */
export function canAdvanceFromFitOutProve(
  rows: FitOutRows,
  probeOkByRole: Readonly<Record<FitOutRole, boolean | undefined>>,
): boolean {
  return FIT_OUT_ROLES.filter((role) => rows[role].value === 'find').every((role) => probeOkByRole[role] === true);
}

/**
 * The whole-vehicle Simulate path still routes through `POST /api/simulations`
 * (docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §7/§9) — its rich mode picker
 * (direct/rtsp/synthetic/testDrone) is not expressible per row (`testDrone` alone produces one
 * combined VIDEO+TELEMETRY device), so this path is kept **only** for the case a per-row multi-device
 * create cannot express: Sight asking to be simulated while Sense is not a real link. Sense may
 * still be `'none'` (today's most common case, video-only simulate) or `'simulate'` too (both rows
 * simulated — "today's demo drone", where the one rich mode already covers both roles). Any row set
 * to `'find'` takes the multi-device path instead, since `/api/simulations` cannot attach to a real
 * link on the other row — documented as a known limitation, not a gap to close in this wave.
 */
export function usesLegacySimulationPath(rows: FitOutRows): boolean {
  return rows.sight.value === 'simulate' && rows.sense.value !== 'find';
}

const SIMULATED_ROLE_CAPABILITIES: Readonly<Record<FitOutRole, readonly Capability[]>> = {
  sense: ['TELEMETRY'],
  sight: ['VIDEO'],
};

const SIMULATED_ROW_URI: Readonly<Record<FitOutRole, string>> = {
  sense: 'sim://telemetry',
  sight: 'sim://demo',
};

/**
 * One row's `CreateAssetDeviceSpec`, or `null` for a `none` row (or a `find` row not actually
 * resolved yet — `canAdvanceFromFitOut` already guards the wizard from reaching Register in that
 * state, so this is defensive, not a case a correct caller hits). A `simulate` row gets an explicit
 * `capabilities` — a simple simulated Sense device needs `TELEMETRY` stated outright, since the
 * backend's own protocol-based default (`mavlink`→TELEMETRY, else→VIDEO) would otherwise infer
 * `VIDEO` for the `sim` protocol.
 */
export function fitOutRowToDeviceSpec(row: FitOutRowDraft, name: string): CreateAssetDeviceSpec | null {
  switch (row.value) {
    case 'none':
      return null;
    case 'simulate':
      return { name, protocol: 'sim', uri: SIMULATED_ROW_URI[row.role], capabilities: SIMULATED_ROLE_CAPABILITIES[row.role], origin: 'SIMULATED' };
    case 'find': {
      const protocol = effectiveProtocol(row).trim();
      const uri = row.uri.trim();
      if (protocol.length === 0 || uri.length === 0) {
        return null;
      }
      const options = collectRowOptions(row);
      return { name, protocol, uri, ...(options ? { options } : {}) };
    }
  }
}

/**
 * Every filled row's device spec, for the multi-device create path (never called on
 * {@link usesLegacySimulationPath}'s own true case — that path builds its device through the
 * existing simulation request builders instead). Each device is named after the asset when only one
 * row is filled (byte-identical to the pre-W6 wizard's single-device naming); once both rows
 * produce a device, each gets a role suffix so the Devices/Links table never shows two identically
 * named devices on one asset.
 */
export function fitOutDeviceSpecs(rows: FitOutRows, displayName: string): readonly CreateAssetDeviceSpec[] {
  const filled = FIT_OUT_ROLES.filter((role) => isRowFilled(rows[role]));
  const nameFor = (role: FitOutRole): string =>
    filled.length > 1 ? `${displayName} — ${FIT_OUT_ROLE_LABELS[role]}` : displayName;
  return filled
    .map((role) => fitOutRowToDeviceSpec(rows[role], nameFor(role)))
    .filter((spec): spec is CreateAssetDeviceSpec => spec !== null);
}

/**
 * Which row a `?deviceId=` prefill (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W6 handoff") lands
 * on — a TELEMETRY-capable device is Sense, everything else is Sight, mirroring
 * `CreateAssetDeviceSpec#capabilities`'s own default rule (`mavlink`→TELEMETRY, else→VIDEO).
 */
export function roleForDevice(device: Pick<Device, 'capabilities'>): FitOutRole {
  return device.capabilities.includes('TELEMETRY') ? 'sense' : 'sight';
}

/**
 * Sysid collision detection (`sysid-collision-logic.ts#detectSysidCollision`) only ever fires for a
 * `mavlink` link, which only the Sense row can carry — checked first, falling back to Sight only so
 * this stays a total function over both roles rather than hardcoding "Sense only" into every caller.
 */
export function combinedSysidCollision(byRole: Readonly<Record<FitOutRole, number | null>>): number | null {
  return byRole.sense ?? byRole.sight;
}

// --- Per-role status after registration (P3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.3) ---
// The wizard proves each row once, then that knowledge used to be thrown away — nothing on
// `/assets/:id` or the cockpit said which half of a vehicle was actually alive. `roleStatus` is the
// one derivation both surfaces read, over facts they already fetch (no new endpoint).

/**
 * A role's status once an asset actually exists — coarser than either wire vocabulary it reads
 * (`StreamState`'s five values, `telemetry-logic.ts#freshness`'s four), because `not-fitted`/
 * `never-seen` must mean the same thing for both roles even though Sight and Sense prove
 * themselves through entirely different facts. `not-fitted` renders `—`, never a green tick
 * (CLAUDE.md degrade-honestly).
 */
export type RoleStatus = 'not-fitted' | 'never-seen' | 'live' | 'stalled' | 'stopped' | 'stale';

/** Freshness tiers that still read as "alive enough" for Sense's coarser vocabulary — `aging` is
 *  not yet worth a distinct warning at this altitude (that finer distinction is `telemetry-logic.ts`'s
 *  own job, e.g. the OSD chip); only `stale` earns its own `RoleStatus` member here. */
const LIVE_FRESHNESS: ReadonlySet<string> = new Set(['live', 'aging']);

/**
 * One row's status, read off facts the calling page already fetches — `GET /api/streams` (Sight)
 * and `AssetAttention.telemetryAgeMs` (Sense) — never a new endpoint.
 *
 * **Sight** narrows `devices` to this asset's VIDEO-capable ones (`roleForDevice`), then looks for a
 * matching `ActiveStream` by `deviceId`: none fitted → `not-fitted`; fitted but no active stream →
 * `stopped` (this app has no way to tell "never started" from "started, then stopped" from
 * `GET /api/streams` alone — a stopped stream simply isn't in that list, per `ActiveStream`'s own
 * doc comment — so `stopped` is the honest, history-agnostic answer for either). A found stream's
 * `state` maps via the same "cannot judge, don't invent a fault" rule `stream-state-logic.ts#videoNotice`
 * already applies: `LIVE`/`STARTING`/`UNOBSERVED`/absent → `live`; `STALLED`/`RECONNECTING` → `stalled`.
 *
 * **Sense** narrows the same way to TELEMETRY-capable devices: none fitted → `not-fitted`;
 * `telemetryAgeMs` absent → `never-seen` (nothing has ever been heard — a fact distinct from
 * "fitted but not currently live", which telemetry has no such state for: `AssetAttention.telemetryAgeMs`
 * is deliberately still reported after a session ends, so a stopped link simply ages into `stale` on
 * its own, without a separate `stopped` branch). Otherwise `telemetry-logic.ts#freshness` decides:
 * `live`/`aging` → `live`, `stale` → `stale`.
 */
export function roleStatus(
  role: FitOutRole,
  devices: readonly Device[],
  streams: readonly Pick<ActiveStream, 'deviceId' | 'state'>[],
  telemetryAgeMs: number | undefined,
): RoleStatus {
  const fitted = devices.filter((device) => roleForDevice(device) === role);
  if (fitted.length === 0) {
    return 'not-fitted';
  }
  if (role === 'sight') {
    const fittedIds = new Set(fitted.map((device) => device.id));
    const stream = streams.find((s) => fittedIds.has(s.deviceId));
    if (!stream) {
      return 'stopped';
    }
    return stream.state === 'STALLED' || stream.state === 'RECONNECTING' ? 'stalled' : 'live';
  }
  // role === 'sense'
  if (telemetryAgeMs === undefined) {
    return 'never-seen';
  }
  return LIVE_FRESHNESS.has(freshness(telemetryAgeMs / 1000)) ? 'live' : 'stale';
}
