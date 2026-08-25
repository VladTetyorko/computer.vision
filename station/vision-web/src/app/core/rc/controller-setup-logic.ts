import { actionLabel, controlKey, controlLabel } from './control-action-logic';
import { DEFAULT_STICK_MODE, type StickMode } from './controller-diagram-logic';
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

/** Highest RC channel the domain accepts (`ControlBinding`'s own `[1,18]`). */
const MAX_RC_CHANNEL = 18;

/**
 * The channels worth offering: what the relay actually puts on the wire, straight from the served
 * catalogue (`RcChannels#RELAYED_CHANNELS`, 8 today). Deliberately **not** the same number as
 * `MAX_RC_CHANNEL` above — the backend validates `[1,18]` because the MAVLink message has the
 * fields, but a binding above the relayed count is stored, displayed and never sent. Falls back to
 * the validation range only when the catalogue has not loaded, where offering nothing would be worse.
 */
export function relayedChannels(catalog: ControlCatalog | undefined): number {
  return catalog?.maxRcChannel ?? MAX_RC_CHANNEL;
}

/** `1..relayedChannels(catalog)` — what a channel picker should list. */
export function channelOptions(catalog: ControlCatalog | undefined): readonly number[] {
  return Array.from({ length: relayedChannels(catalog) }, (_, i) => i + 1);
}

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
  /** How the owner's transmitter is arranged — drawing only, saved with the layout. */
  readonly stickMode: StickMode;
  readonly forwardIsUp: boolean;
}

/** One vehicle kind's layouts, as the picker lists them. */
export interface ProfileGroup {
  readonly kind: VehicleKind;
  /** The kind in the catalogue's words, e.g. `Ground vehicle`. */
  readonly label: string;
  readonly profiles: readonly ControlProfile[];
  /** The one a session would engage for this kind — saved if the operator activated one, else the built-in. */
  readonly active: ControlProfile | undefined;
}

/**
 * The operator's layouts, grouped by the vehicle kind they apply to.
 *
 * A flat list showed four built-ins each carrying an **Active** chip directly under the words "one
 * per vehicle kind is active at a time", which reads as four active layouts and is the opposite of
 * what it means. Grouped, the same four chips read correctly: one active layout *per group*. Kinds
 * follow the catalogue's own order so the list does not reshuffle as layouts are created, and a
 * kind the operator has no layouts for is omitted rather than shown empty — the built-in for every
 * kind is always present, so an empty group cannot happen with a loaded catalogue anyway.
 *
 * Within a group, the operator's own layouts come first and the built-in last: the built-in is the
 * fallback, and a fallback belongs at the bottom of the list it backs.
 */
export function groupByKind(
  profiles: readonly ControlProfile[],
  catalog: ControlCatalog | undefined,
): readonly ProfileGroup[] {
  const order = (catalog?.vehicleKinds ?? []).map((k) => k.name);
  const kinds = [...new Set(profiles.map((p) => p.kind))].sort(
    (a, b) => indexOrLast(order, a) - indexOrLast(order, b),
  );
  return kinds.map((kind) => {
    const own = profiles.filter((p) => p.kind === kind);
    const sorted = [...own].sort((a, b) => rank(a) - rank(b));
    return {
      kind,
      label: catalog?.vehicleKinds.find((k) => k.name === kind)?.label ?? kind,
      profiles: sorted,
      active: sorted.find((p) => p.active),
    };
  });
}

function indexOrLast(order: readonly VehicleKind[], kind: VehicleKind): number {
  const at = order.indexOf(kind);
  return at === -1 ? order.length : at;
}

function rank(profile: ControlProfile): number {
  return profile.source === 'BUILT_IN' ? 1 : 0;
}

/**
 * The wire carries a plain number; only 1–4 are transmitter modes. A stored value outside that is a
 * data problem the operator cannot act on, so the picture falls back to the common arrangement
 * rather than refusing to draw.
 */
export function asStickMode(value: number | undefined): StickMode {
  return value === 1 || value === 2 || value === 3 || value === 4 ? value : DEFAULT_STICK_MODE;
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
/**
 * The one line the diagram writes under a control: what it currently does, in the operator's words.
 *
 * Deliberately short and deliberately truthful — a control whose role is `ACTIONS` but which has no
 * action on any position reads as unassigned rather than as "fires commands", because from where
 * the operator is standing it does nothing.
 */
export function bindingSummary(control: ControlDraft, catalog: ControlCatalog | undefined): string {
  if (control.role === 'CHANNEL') {
    const named = catalog?.functions.find((f) => f.name === control.function)?.label ?? control.function;
    return `CH${control.rcChannel} \u00b7 ${named}`;
  }
  const commands = control.positions
    .filter((p) => p.action)
    .map((p) => actionLabel(p.action, p.parameter));
  return commands.length === 0 ? 'nothing yet' : commands.join(' \u00b7 ');
}

export function draftFrom(profile: ControlProfile): ProfileDraft {
  const controls = [
    ...profile.channelMap.map(channelDraft),
    ...profile.actionMap.map((b, i) => actionDraft(b, firstFreeChannelAfter(profile.channelMap, i))),
  ].sort(byInput);
  return {
    id: profile.id,
    kind: profile.kind,
    name: profile.name,
    controls,
    stickMode: asStickMode(profile.stickMode),
    forwardIsUp: profile.forwardIsUp,
  };
}

/** Rows in a stable, readable order: axes before buttons, by index. */
export function byInput(a: ControlDraft, b: ControlDraft): number {
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
 * A **button** starts on the actions role with nothing chosen: a row that arrived already driving a
 * channel would mean adding a control silently changed what the sticks do. An **axis** starts on
 * the channel role, on a channel nothing else drives, because the alternative is the one role/kind
 * pairing with nothing to fill in — a continuous axis has no positions to fire from, so every
 * autodetected stick would arrive in a state the operator's first move is to undo.
 *
 * Either way the row commands nothing until it is saved and the layout activated.
 */
export function blankControlDraft(source: ControlSource, sourceIndex: number, draft: ProfileDraft): ControlDraft {
  return {
    source,
    sourceIndex,
    kind: source === 'AXIS' ? 'AXIS' : 'BUTTON',
    // A continuous axis has no positions, so a row that starts on ACTIONS starts on the one
    // combination the editor cannot fill in -- the operator's first move would always be to undo it.
    role: source === 'AXIS' ? 'CHANNEL' : 'ACTIONS',
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

function toChannelBinding(control: ControlDraft): ControlBinding {
  return {
    source: control.source,
    kind: control.kind,
    function: control.function,
    sourceIndex: control.sourceIndex,
    rcChannel: control.rcChannel,
    minMicros: MIN_MICROS,
    centerMicros: control.travel === 'CENTERED' ? CENTER_MICROS : MIN_MICROS,
    maxMicros: MAX_MICROS,
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
    stickMode: draft.stickMode,
    forwardIsUp: draft.forwardIsUp,
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

  const relayed = relayedChannels(catalog);
  for (const control of draft.controls.filter((c) => c.role === 'CHANNEL' && c.rcChannel > relayed)) {
    issues.push(
      `${draftLabel(control)} drives CH${control.rcChannel}, which this link never sends — it carries CH1–CH${relayed}.`,
    );
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
