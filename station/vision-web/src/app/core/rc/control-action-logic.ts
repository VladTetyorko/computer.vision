import type {
  ActionBinding,
  ControlAction,
  ControlCatalog,
  ControlBinding,
  ControlInputKind,
  ControlProfile,
  ControlSource,
  SwitchPosition,
  VehicleKind,
} from '../api/models';
import { defaultAxisLabel, defaultButtonLabel } from './rc-input-logic';

/**
 * Pure, Angular-free logic behind bound-control action dispatch
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C2/C3/C4) — quantization, edge
 * detection, and the "is this safe to fire" rules, split out so every one of them unit-tests
 * without a browser, a gamepad, or HTTP. Same split `core/rc/rc-input-logic.ts` and
 * `core/rc/control-surface-logic.ts` already use.
 *
 * **The quantizer here mirrors the backend's `SwitchPosition.of` exactly**, and must keep doing so:
 * a channel binding's microseconds are computed server-side from the same raw reading this file
 * turns into a position. If the two disagreed, one switch would simultaneously *show* middle on the
 * channel map and *fire* the HIGH action — the kind of split-brain that is invisible until it
 * matters. The thresholds are the firmware's own detents, not a UI choice.
 */

/** Normalized magnitude at which a switch reported on an axis is off-centre — `SwitchPosition.AXIS_DETENT`. */
const AXIS_DETENT = 0.5;

/** Normalized value at which a switch reported on a button is pressed — `SwitchPosition.BUTTON_PRESSED`. */
const BUTTON_PRESSED = 0.5;

/**
 * Quantizes one raw reading into the position the operator has the control in — the browser-side
 * twin of the backend's single platform-wide quantizer.
 *
 * @param source which array the reading came from: an axis reports −1..1 and can express three
 *   positions, a button reports 0..1 and can only ever be LOW or HIGH
 * @param kind how the operator declared the control behaves; `'SWITCH_3'` is the only kind that
 *   ever returns `'MIDDLE'`
 * @param raw the raw reading; out-of-range values are clamped by the comparisons, never rejected
 */
export function positionOf(source: ControlSource, kind: ControlInputKind, raw: number): SwitchPosition {
  if (source === 'BUTTON') {
    return raw >= BUTTON_PRESSED ? 'HIGH' : 'LOW';
  }
  if (kind === 'SWITCH_3') {
    if (raw <= -AXIS_DETENT) {
      return 'LOW';
    }
    return raw >= AXIS_DETENT ? 'HIGH' : 'MIDDLE';
  }
  return raw >= 0 ? 'HIGH' : 'LOW';
}

/**
 * How one bound control reads in a sentence — the same names the RC monitor already prints for its
 * live bars, so "Sw 3" in a toast points at the row the operator is looking at rather than at an
 * internal key.
 */
export function controlLabel(source: ControlSource, sourceIndex: number): string {
  return source === 'AXIS' ? defaultAxisLabel(sourceIndex) : defaultButtonLabel(sourceIndex);
}

/** A stable key for one physical control, so positions can be remembered across frames. */
export function controlKey(source: ControlSource, sourceIndex: number): string {
  return `${source}:${sourceIndex}`;
}

/** Reads one control's raw value, treating a missing/short array as rest — never as an error. */
function readRaw(
  source: ControlSource,
  sourceIndex: number,
  axes: readonly number[],
  buttons: readonly number[],
): number {
  const values = source === 'AXIS' ? axes : buttons;
  const value = values[sourceIndex];
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** Where every action-bound control is sitting right now, keyed by {@link controlKey}. */
export function positionsFrom(
  bindings: readonly ActionBinding[],
  axes: readonly number[],
  buttons: readonly number[],
): ReadonlyMap<string, SwitchPosition> {
  const positions = new Map<string, SwitchPosition>();
  for (const binding of bindings) {
    const raw = readRaw(binding.source, binding.sourceIndex, axes, buttons);
    positions.set(controlKey(binding.source, binding.sourceIndex), positionOf(binding.source, binding.kind, raw));
  }
  return positions;
}

/** One command a bound control just asked for. */
export interface PendingAction {
  readonly key: string;
  /** {@link controlLabel} for the control that moved — what a toast says, not what a map is keyed by. */
  readonly label: string;
  readonly position: SwitchPosition;
  readonly action: ControlAction;
  readonly parameter?: string | null;
  /** Whether this needs the extra friction of decision C9 — resolved from the catalogue, not guessed. */
  readonly dangerous: boolean;
}

/**
 * What changed since the last frame, and therefore what to send.
 *
 * Edge-triggered, never level-triggered: a switch held in a position fires **once**, when it
 * arrives. Repeating at the poll rate would turn one flick of an arm switch into thirty arm
 * commands a second.
 *
 * `previous` being empty (the first frame after the drawer opens, or after a profile change) is
 * deliberately **not** an edge: every control is read as already-settled, so a switch the operator
 * left in the ARM position before this UI was even looking does not fire the moment it starts
 * looking. Nothing arms because a panel opened.
 */
export function pendingActions(
  bindings: readonly ActionBinding[],
  previous: ReadonlyMap<string, SwitchPosition>,
  current: ReadonlyMap<string, SwitchPosition>,
  dangerousActions: ReadonlySet<ControlAction>,
): readonly PendingAction[] {
  if (previous.size === 0) {
    return [];
  }
  const pending: PendingAction[] = [];
  for (const binding of bindings) {
    const key = controlKey(binding.source, binding.sourceIndex);
    const now = current.get(key);
    const before = previous.get(key);
    if (now === undefined || before === undefined || now === before) {
      continue;
    }
    const positionAction = binding.positions.find((p) => p.position === now);
    if (!positionAction) {
      continue;
    }
    pending.push({
      key,
      label: controlLabel(binding.source, binding.sourceIndex),
      position: now,
      action: positionAction.action,
      parameter: positionAction.parameter,
      dangerous: dangerousActions.has(positionAction.action),
    });
  }
  return pending;
}

/**
 * The catalogue facts a dispatcher needs, lifted out of `GET /api/control-profiles/catalog`
 * (decision C8): which actions are dangerous, and what switch level ArduPilot expects for each
 * position. Neither is restated in this app — the level in particular is the one a client guessing
 * "HIGH is 2" would get wrong silently, firing the wrong end of a switch.
 */
export interface ControlActionRules {
  readonly dangerous: ReadonlySet<ControlAction>;
  readonly levels: ReadonlyMap<SwitchPosition, number>;
}

/**
 * Reads {@link ControlActionRules} out of the catalogue.
 *
 * An absent catalogue (not loaded yet, or the request failed) yields empty rules, and empty rules
 * are the safe end: nothing is known to be dangerous, so nothing is *treated* as dangerous — but
 * the dispatcher that consumes them only fires against a resolved profile, which comes from the
 * same load. There is no state where a command fires with the danger flags missing.
 */
export function rulesFrom(catalog: ControlCatalog | undefined): ControlActionRules {
  return {
    dangerous: new Set((catalog?.actions ?? []).filter((a) => a.dangerous).map((a) => a.name)),
    levels: new Map((catalog?.positions ?? []).map((p) => [p.name, p.level])),
  };
}

/**
 * The layout a session on `kind` would engage with, out of everything the operator has: their
 * active saved profile for that kind, else the built-in for it.
 *
 * The same resolution the backend performs at engage time (`ControlProfileService#activeFor`),
 * repeated here only to answer it *before* engaging — so a bound switch works without taking stick
 * control, which is decision C3's whole point.
 */
export function activeProfileFor(
  profiles: readonly ControlProfile[],
  kind: VehicleKind | undefined,
): ControlProfile | undefined {
  if (!kind) {
    return undefined;
  }
  const forKind = profiles.filter((p) => p.kind === kind);
  return forKind.find((p) => p.active && p.source === 'SAVED') ?? forKind.find((p) => p.source === 'BUILT_IN');
}

/**
 * A short human sentence for what a bound control just did, for the toast/one-line log.
 *
 * `vehicleKind` (docs/plans/active/FLEET-RADIO-PLAN.md R4b) feeds only `EMERGENCY_STOP`'s wording:
 * the backend gave a rover's emergency stop a different, safer meaning — `MAV_CMD_DO_SET_MODE` into
 * ArduRover's `Hold` (an active brake), never a forced disarm, because disarming immediately after
 * would release the very brake the stop just applied (`MavlinkFlightCommander#emergencyStop`'s own
 * javadoc has the full rationale) — so the label must say `Hold`, not read like "cut the motors".
 * Every other kind, `undefined`/`UNKNOWN` included, keeps the platform's historical meaning: a
 * forced disarm, and the label that has always said so. Mirrors the precedent
 * `core/telemetry/flight-state-logic.ts#derivePreflight` set for a per-kind label/rule.
 */
export function actionLabel(action: ControlAction, parameter?: string | null, vehicleKind?: VehicleKind): string {
  switch (action) {
    case 'SET_MODE':
      return `Mode ${parameter ?? ''}`.trim();
    case 'AUX_FUNCTION':
      return `Aux function ${parameter ?? ''}`.trim();
    case 'TOGGLE_ARM':
      return 'Toggle arm';
    case 'EMERGENCY_STOP':
      return vehicleKind === 'ROVER' ? 'Emergency stop (Hold)' : 'Emergency stop';
    case 'RETURN_TO_HOME':
      return 'Return to home';
    case 'ARM':
      return 'Arm';
    case 'DISARM':
      return 'Disarm';
  }
}

/**
 * Which controls a profile has spoken for, so a setup page can tell an operator that an input is
 * already taken rather than letting them bind it twice and be refused on save (the backend enforces
 * this invariant; this is the same rule, said earlier).
 */
export function boundControlKeys(profile: ControlProfile): ReadonlySet<string> {
  const keys = new Set<string>();
  for (const binding of profile.channelMap) {
    keys.add(controlKey(binding.source, binding.sourceIndex));
  }
  for (const binding of profile.actionMap) {
    keys.add(controlKey(binding.source, binding.sourceIndex));
  }
  return keys;
}

/** Which RC channels a profile already drives — the other invariant the backend enforces on save. */
export function usedChannels(channelMap: readonly ControlBinding[]): ReadonlySet<number> {
  return new Set(channelMap.map((b) => b.rcChannel));
}
