import { controlKey, controlLabel } from './control-action-logic';
import type {
  ActionBinding,
  ControlAction,
  ControlBinding,
  ControlCatalog,
  ControlFunction,
  ControlInputKind,
  ControlProfile,
  ControlSource,
  ControlTravel,
  PositionAction,
  SwitchPosition,
  UpdateControlProfileRequest,
  VehicleKind,
} from '../api/models';

/**
 * Pure, Angular-free editing model behind the controller setup page
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C1/C2/C4) — one editable row per physical
 * control, and the rules that turn those rows back into the two maps the wire contract carries.
 *
 * <h2>Why a row is not a binding</h2>
 * The wire has two lists — `channelMap` (controls that stream into an RC channel) and `actionMap`
 * (controls whose positions fire one-shot commands). An operator does not think in two lists: they
 * think "this switch does *that*", and a control is in exactly one of the two at a time (decision
 * C2). So the editor holds one row per control with a `role`, and {@link toUpdateRequest} splits it.
 * The split is the last thing that happens, which is what makes "make this switch arm instead of
 * driving CH7" a single click rather than a delete in one list and an add in another.
 *
 * <h2>Microseconds are derived, never typed</h2>
 * A row carries a {@link ControlTravel}, not three pulse widths. The backend derives travel *from*
 * the microseconds (`ControlBinding#travel`), so the UI producing them from the travel the operator
 * picked keeps the two definitions in agreement by construction. Asking an operator for 1500 would
 * be asking them to hand-maintain an invariant the domain already owns.
 */

/**
 * RC's own pulse-width envelope, mirroring the backend's `RcChannels`. Protocol constants, not
 * tunables: 1000/1500/2000 µs is what every receiver and autopilot on this platform's wire means by
 * minimum, rest and maximum, and a deployment that changed them would be speaking a different
 * protocol, not configuring this one.
 */
const MIN_MICROS = 1000;
const CENTER_MICROS = 1500;
const MAX_MICROS = 2000;

/**
 * Highest RC channel the domain accepts (`ControlBinding`'s own `[1,16]`). Narrowed from 18 by
 * FLEET-RADIO R3/F17: ArduPilot reads only channels 1-16 from an `RC_CHANNELS_OVERRIDE`, so a
 * binding on 17 or 18 could never reach a servo. Exported so the setup wizard's Advanced channel
 * picker and the all-controls editor build the same list from one source.
 */
export const MAX_RC_CHANNEL = 16;

/** What one control does: stream into a channel, or fire commands from its positions. */
export type ControlRole = 'CHANNEL' | 'ACTIONS';

/** One editable row — a physical control and everything the operator chose about it. */
export interface ControlDraft {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly kind: ControlInputKind;
  readonly role: ControlRole;
  /** `CHANNEL` role only. */
  readonly function: ControlFunction;
  /** `CHANNEL` role only, 1-based. */
  readonly rcChannel: number;
  /** `CHANNEL` role only — what the microseconds are derived from. */
  readonly travel: ControlTravel;
  /** `CHANNEL` role only. */
  readonly reversed: boolean;
  /** `ACTIONS` role only; a position with nothing chosen is simply absent. */
  readonly positions: readonly PositionAction[];
}

/** One layout, as the page edits it. */
export interface ProfileDraft {
  readonly id: string;
  readonly kind: VehicleKind;
  readonly name: string;
  readonly controls: readonly ControlDraft[];
}

/** A row's stable identity — the physical control, since one control is bound at most once. */
export function draftKey(control: ControlDraft): string {
  return controlKey(control.source, control.sourceIndex);
}

/** A row's operator-facing name — the same "Axis 3"/"Sw 5" the RC monitor prints. */
export function draftLabel(control: ControlDraft): string {
  return controlLabel(control.source, control.sourceIndex);
}

function channelDraft(binding: ControlBinding): ControlDraft {
  return {
    source: binding.source,
    sourceIndex: binding.sourceIndex,
    kind: binding.kind,
    role: 'CHANNEL',
    function: binding.function,
    rcChannel: binding.rcChannel,
    travel: binding.centerMicros === binding.minMicros ? 'UNIDIRECTIONAL' : 'CENTERED',
    reversed: binding.reversed,
    positions: [],
  };
}

function actionDraft(binding: ActionBinding, usedChannel: number): ControlDraft {
  return {
    source: binding.source,
    sourceIndex: binding.sourceIndex,
    kind: binding.kind,
    role: 'ACTIONS',
    function: 'AUX_1',
    rcChannel: usedChannel,
    travel: 'CENTERED',
    reversed: false,
    positions: [...binding.positions],
  };
}

/**
 * Opens one layout for editing.
 *
 * Rows come out in a stable, readable order — axes before buttons, by index — rather than in
 * whichever order the two maps happened to arrive in, so a saved-and-reopened layout looks the same
 * as it did before the save.
 */
export function draftFrom(profile: ControlProfile): ProfileDraft {
  const controls = [
    ...profile.channelMap.map(channelDraft),
    ...profile.actionMap.map((b, i) => actionDraft(b, firstFreeChannelAfter(profile.channelMap, i))),
  ].sort(byInput);
  return { id: profile.id, kind: profile.kind, name: profile.name, controls };
}

function byInput(a: ControlDraft, b: ControlDraft): number {
  if (a.source !== b.source) {
    return a.source === 'AXIS' ? -1 : 1;
  }
  return a.sourceIndex - b.sourceIndex;
}

/** A channel to fall back on if an action row is later switched to the channel role. */
function firstFreeChannelAfter(channelMap: readonly ControlBinding[], offset: number): number {
  const used = new Set(channelMap.map((b) => b.rcChannel));
  let channel = 1;
  let skipped = 0;
  while (channel <= MAX_RC_CHANNEL) {
    if (!used.has(channel)) {
      if (skipped === offset) {
        return channel;
      }
      skipped += 1;
    }
    channel += 1;
  }
  return MAX_RC_CHANNEL;
}

/** The lowest channel no row in the draft drives — what a new channel row starts on. */
export function nextFreeChannel(draft: ProfileDraft): number {
  const used = new Set(draft.controls.filter((c) => c.role === 'CHANNEL').map((c) => c.rcChannel));
  for (let channel = 1; channel <= MAX_RC_CHANNEL; channel += 1) {
    if (!used.has(channel)) {
      return channel;
    }
  }
  return MAX_RC_CHANNEL;
}

/**
 * A new row for one physical control, with the only defaults that are actually safe.
 *
 * A new control starts on the **actions** role with nothing chosen: a row that arrived already
 * driving a channel would mean adding a control silently changed what the sticks do. Nothing here
 * commands anything until the operator says what it is.
 */
export function blankControlDraft(source: ControlSource, sourceIndex: number, draft: ProfileDraft): ControlDraft {
  return {
    source,
    sourceIndex,
    kind: source === 'AXIS' ? 'AXIS' : 'BUTTON',
    role: 'ACTIONS',
    function: 'AUX_1',
    rcChannel: nextFreeChannel(draft),
    travel: source === 'AXIS' ? 'CENTERED' : 'UNIDIRECTIONAL',
    reversed: false,
    positions: [],
  };
}

/** The positions a kind reports, from the catalogue — empty for a continuous axis. */
export function positionsOf(catalog: ControlCatalog | undefined, kind: ControlInputKind): readonly SwitchPosition[] {
  return catalog?.inputKinds.find((k) => k.name === kind)?.positions ?? [];
}

/** The kinds a control read from `source` may be declared as, from the catalogue. */
export function kindsFor(catalog: ControlCatalog | undefined, source: ControlSource): readonly ControlInputKind[] {
  return (catalog?.inputKinds ?? []).filter((k) => k.sources.includes(source)).map((k) => k.name);
}

/**
 * Re-declares what a control *is*, dropping whatever no longer applies.
 *
 * Positions the new kind does not have go, rather than lingering invisibly — a 3-position switch
 * redeclared as a 2-position one must not keep firing a MIDDLE action nothing can reach.
 */
export function withKind(
  control: ControlDraft,
  kind: ControlInputKind,
  catalog: ControlCatalog | undefined,
): ControlDraft {
  const allowed = new Set(positionsOf(catalog, kind));
  return {
    ...control,
    kind,
    travel: kind === 'BUTTON' ? 'UNIDIRECTIONAL' : control.travel,
    positions: control.positions.filter((p) => allowed.has(p.position)),
  };
}

/** What one position is set to fire, or `undefined` when the operator has chosen nothing for it. */
export function actionAt(control: ControlDraft, position: SwitchPosition): PositionAction | undefined {
  return control.positions.find((p) => p.position === position);
}

/**
 * Sets (or clears, with `undefined`) what one position fires, keeping the catalogue's own position
 * order so the rows do not reshuffle as an operator fills them in.
 */
export function withPositionAction(
  control: ControlDraft,
  position: SwitchPosition,
  action: ControlAction | undefined,
  parameter: string | null | undefined,
  order: readonly SwitchPosition[],
): ControlDraft {
  const kept = control.positions.filter((p) => p.position !== position);
  const next = action === undefined ? kept : [...kept, { position, action, parameter: parameter ?? null }];
  return { ...control, positions: next.slice().sort((a, b) => order.indexOf(a.position) - order.indexOf(b.position)) };
}

/** What the catalogue says an action needs configured alongside it. */
export function parameterKindOf(catalog: ControlCatalog | undefined, action: ControlAction | undefined) {
  return catalog?.actions.find((a) => a.name === action)?.parameter ?? 'NONE';
}

/**
 * The pulse-width envelope one `CHANNEL` row's travel derives, in this file's own protocol
 * constants — exported so a second consumer (the setup wizard's Advanced disclosure, wave X4) can
 * show an operator the same derived µs {@link toChannelBinding} sends, without a second file
 * restating 1000/1500/2000 as its own copy of the same protocol constants.
 */
export function microsFor(travel: ControlTravel): { readonly min: number; readonly center: number; readonly max: number } {
  return { min: MIN_MICROS, center: travel === 'CENTERED' ? CENTER_MICROS : MIN_MICROS, max: MAX_MICROS };
}

function toChannelBinding(control: ControlDraft): ControlBinding {
  const micros = microsFor(control.travel);
  return {
    source: control.source,
    kind: control.kind,
    function: control.function,
    sourceIndex: control.sourceIndex,
    rcChannel: control.rcChannel,
    minMicros: micros.min,
    centerMicros: micros.center,
    maxMicros: micros.max,
    deadband: 0,
    reversed: control.reversed,
  };
}

/**
 * Turns the rows back into the two maps the `PUT` carries.
 *
 * An `ACTIONS` row with nothing chosen is dropped rather than sent as an empty binding — the
 * backend refuses one, and an operator who added a row and left it blank meant "not yet", not
 * "reject my save".
 */
export function toUpdateRequest(draft: ProfileDraft): UpdateControlProfileRequest {
  return {
    name: draft.name.trim(),
    channelMap: draft.controls.filter((c) => c.role === 'CHANNEL').map(toChannelBinding),
    actionMap: draft.controls
      .filter((c) => c.role === 'ACTIONS' && c.positions.length > 0)
      .map((c) => ({ source: c.source, kind: c.kind, sourceIndex: c.sourceIndex, positions: c.positions })),
  };
}

/**
 * Everything wrong with the draft, said the way an operator can fix it.
 *
 * These are the same invariants the backend enforces on save (one control bound once, one channel
 * driven once, a parameterized action carrying its parameter). Saying them here does not replace
 * that check — it just means the operator finds out while looking at the row, instead of losing a
 * page of work to a 400.
 */
export function draftIssues(draft: ProfileDraft, catalog: ControlCatalog | undefined): readonly string[] {
  const issues: string[] = [];
  if (draft.name.trim().length === 0) {
    issues.push('Give this layout a name.');
  }

  const seenControls = new Set<string>();
  for (const control of draft.controls) {
    const key = draftKey(control);
    if (seenControls.has(key)) {
      issues.push(`${draftLabel(control)} is listed twice — one control can only do one thing.`);
    }
    seenControls.add(key);
  }

  const seenChannels = new Set<number>();
  for (const control of draft.controls.filter((c) => c.role === 'CHANNEL')) {
    if (seenChannels.has(control.rcChannel)) {
      issues.push(`CH${control.rcChannel} is driven by more than one control.`);
    }
    seenChannels.add(control.rcChannel);
  }

  for (const control of draft.controls.filter((c) => c.role === 'ACTIONS')) {
    for (const position of control.positions) {
      const needs = parameterKindOf(catalog, position.action);
      if (needs !== 'NONE' && !position.parameter) {
        issues.push(`${draftLabel(control)} ${position.position.toLowerCase()} needs a ${
          needs === 'MODE_NAME' ? 'flight mode' : 'function number'
        }.`);
      }
    }
  }
  return issues;
}

/**
 * Which control just moved, against a baseline captured when learning started — the "flick the
 * switch you mean" gesture, so an operator never has to know which array index their transmitter
 * reports a switch on.
 *
 * Deliberately magnitude-based rather than "any change": a stick at rest jitters by a few
 * thousandths on most hardware, and a learn mode that latched onto that would name the wrong
 * control at the exact moment the operator is trusting it to name the right one.
 */
const LEARN_THRESHOLD = 0.5;

export function movedControl(
  axes: readonly number[],
  buttons: readonly number[],
  baseline: { readonly axes: readonly number[]; readonly buttons: readonly number[] },
): { readonly source: ControlSource; readonly sourceIndex: number } | undefined {
  for (const [index, value] of axes.entries()) {
    if (Math.abs(value - (baseline.axes[index] ?? 0)) >= LEARN_THRESHOLD) {
      return { source: 'AXIS', sourceIndex: index };
    }
  }
  for (const [index, value] of buttons.entries()) {
    if (Math.abs(value - (baseline.buttons[index] ?? 0)) >= LEARN_THRESHOLD) {
      return { source: 'BUTTON', sourceIndex: index };
    }
  }
  return undefined;
}
