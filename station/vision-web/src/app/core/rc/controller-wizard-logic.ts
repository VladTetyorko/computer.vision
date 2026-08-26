import { controlKey } from './control-action-logic';
import { blankControlDraft, draftKey, positionsOf, type ControlDraft, type ProfileDraft } from './controller-setup-logic';
import type {
  ControlAction,
  ControlActionParameter,
  ControlBinding,
  ControlCatalog,
  ControlFunction,
  ControlInputKind,
  ControlProfile,
  ControlSource,
  ControlTravel,
  PositionAction,
  SwitchPosition,
  VehicleKind,
} from '../api/models';

/**
 * Pure, Angular-free step derivation behind the controller setup wizard
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3, wave X3) — turning a built-in layout into a
 * sequence an operator can walk through one control at a time, and turning what they actually move
 * on the transmitter into the answer to that step.
 *
 * <h2>Why steps come from the built-in, not a fixed list</h2>
 * A rover has no roll or pitch; an aircraft has no steering. Hardcoding "throttle, roll, pitch, yaw,
 * arm, mode" would either ask a rover operator to bind a control that does not exist or silently
 * skip the rover's own steering. {@link wizardSteps} instead reads the built-in {@link ControlProfile}
 * for the kind — the same totality guarantee `ControlProfile#forKind` already gives the backend
 * (CONTROLLER-SETUP-CONTEXT.md §3 C7) — so the step list is exactly the controls that kind's stick
 * layout actually has.
 *
 * <h2>Detection is min/max since the step opened, not a single frame</h2>
 * A gamepad/transmitter read on one frame tells you almost nothing — every axis reads *something*
 * every tick, and idle hardware jitters by a few thousandths. {@link observeInputs} accumulates the
 * low/high excursion of every control since the step was opened, and {@link detectedControl} names
 * the one that travelled the furthest, which is the same "flick the switch you mean" gesture every
 * ground station in CONTROLLER-SETUP-CONTEXT.md §2.2's survey offers, just phrased as a pure
 * reduction over frames instead of an imperative "am I still learning" state machine.
 */

/**
 * How far an input's excursion (`max - min`) must span before this file treats it as the control
 * the operator meant to move, rather than idle jitter. A stick at rest wanders by a few
 * thousandths on most hardware; a deliberate flick or push covers a large fraction of the input's
 * own range (±1 for an axis, 0..1 for a button), so a threshold well below "half travel" still
 * comfortably clears rest noise while catching a light touch.
 */
const DETECT_TRAVEL_THRESHOLD = 0.4;

/**
 * How far off centre a single reading must be before it counts as the *start* of an excursion, for
 * the purpose of recording which way the operator moved first ({@link DetectSample#firstSign}).
 * Lower than {@link DETECT_TRAVEL_THRESHOLD} on purpose — this only has to catch *which direction*
 * the first deliberate move went, not how far the control eventually travelled.
 */
const EXCURSION_THRESHOLD = 0.2;

/**
 * The magnitude an axis reading must cross to count as "at an end" when guessing a switch's kind —
 * the same detent `core/rc/control-action-logic.ts#positionOf` and the backend's own
 * `SwitchPosition.of` use to tell LOW/HIGH from MIDDLE. Reusing the firmware's own boundary here
 * means a guess this file makes agrees with how the reading will actually be quantized once bound.
 */
const AXIS_DETENT = 0.5;

/** The order a CHANNEL step's function is asked in when the built-in binds it: throttle first (the
 * one whose rest position is safety-critical on every kind), then whichever of yaw/steering the
 * vehicle has, then pitch, then roll. A kind that does not bind a function simply has no step for
 * it — a rover's built-in has no YAW/PITCH/ROLL binding, so only THROTTLE and STEERING appear. */
const CHANNEL_FUNCTION_ORDER: readonly ControlFunction[] = ['THROTTLE', 'YAW', 'STEERING', 'PITCH', 'ROLL'];

/** The always-true caveat on the arm step (CONTROLLER-UX-PLAN.md §2.3's own wireframe text) — a
 * bound switch cannot show the two-stage confirm dialog a click gets, so it earns its own warning
 * instead (decision C9). */
const ARM_HOLD_CAVEAT = 'Arm from a switch needs a hold — a stray flick will not arm the vehicle.';

/** Shown on every CHANNEL step when the vehicle has not said what kind it is — the built-in for
 * `UNKNOWN` is a generic four-centred-axis map that is not tailored to any real machine
 * (`ControlProfile#unknownMap`'s own doc comment), so the wizard says so rather than presenting it
 * with the same confidence as a recognised kind's layout. */
const UNKNOWN_KIND_CAVEAT =
  'This vehicle has not reported what kind it is, so this is a generic layout, not one built for your machine. Check every control against your machine before you fly it.';

/** What kind of step this is — one row of the wizard's own step rail. */
export type WizardStepKind = 'CHANNEL' | 'ARM' | 'MODE' | 'EXTRAS' | 'REVIEW';

/** One step of the wizard, in the order the operator walks them. */
export interface WizardStep {
  readonly id: string;
  readonly kind: WizardStepKind;
  /** e.g. "Throttle", "Arm". */
  readonly title: string;
  /** Operator prose — what to physically do, said in the second person. */
  readonly instruction: string;
  /** `CHANNEL` steps only — the function this step is binding. */
  readonly function?: ControlFunction;
  /** `CHANNEL` steps only — the RC channel the built-in drives this function on. */
  readonly defaultChannel?: number;
  /** `CHANNEL` steps only — the travel the built-in uses for this function. */
  readonly defaultTravel?: ControlTravel;
  /** A warning worth showing under the instruction, or absent when there is nothing to warn about. */
  readonly caveat?: string;
}

function channelTitle(fn: ControlFunction, catalog: ControlCatalog | undefined): string {
  const fromCatalog = catalog?.functions.find((f) => f.name === fn)?.label;
  if (fromCatalog) {
    return fromCatalog;
  }
  switch (fn) {
    case 'THROTTLE':
      return 'Throttle';
    case 'YAW':
      return 'Yaw';
    case 'STEERING':
      return 'Steering';
    case 'PITCH':
      return 'Pitch';
    case 'ROLL':
      return 'Roll';
    default:
      return fn;
  }
}

function channelInstruction(fn: ControlFunction): string {
  switch (fn) {
    case 'THROTTLE':
      return 'Move the throttle all the way up, then all the way down.';
    case 'YAW':
      return 'Move the yaw stick all the way left, then all the way right.';
    case 'STEERING':
      return 'Turn the steering stick all the way left, then all the way right.';
    case 'PITCH':
      return 'Move the pitch stick all the way forward, then all the way back.';
    case 'ROLL':
      return 'Move the roll stick all the way left, then all the way right.';
    default:
      return 'Move this control through its full range.';
  }
}

/** The travel a built-in's binding uses, read the same way `controller-setup-logic.ts#channelDraft`
 * reads it back out of the microseconds — `travel` itself is optional on the wire (server-derived,
 * ignored on write), so a built-in that omits it is still read correctly. */
function travelOf(binding: ControlBinding): ControlTravel {
  return binding.travel ?? (binding.centerMicros === binding.minMicros ? 'UNIDIRECTIONAL' : 'CENTERED');
}

function channelSteps(builtIn: ControlProfile | undefined, catalog: ControlCatalog | undefined): WizardStep[] {
  if (!builtIn) {
    return [];
  }
  const bound = new Map(builtIn.channelMap.map((b) => [b.function, b] as const));
  const caveat = builtIn.kind === 'UNKNOWN' ? UNKNOWN_KIND_CAVEAT : undefined;
  const steps: WizardStep[] = [];
  for (const fn of CHANNEL_FUNCTION_ORDER) {
    const binding = bound.get(fn);
    if (!binding) {
      continue;
    }
    steps.push({
      id: `channel-${fn.toLowerCase()}`,
      kind: 'CHANNEL',
      title: channelTitle(fn, catalog),
      instruction: channelInstruction(fn),
      function: fn,
      defaultChannel: binding.rcChannel,
      defaultTravel: travelOf(binding),
      caveat,
    });
  }
  return steps;
}

/**
 * The wizard's full step list for one vehicle kind — one {@link WizardStep} of kind `'CHANNEL'` per
 * function the built-in binds, in {@link CHANNEL_FUNCTION_ORDER}, followed by the fixed `ARM` →
 * `MODE` → `EXTRAS` → `REVIEW` tail (CONTROLLER-UX-PLAN.md §2.3 U7 — order carries meaning: sticks
 * before switches, arm before mode, review last).
 *
 * `builtIn`/`catalog` absent (still loading) yields no `CHANNEL` steps but still the fixed tail —
 * there is always something to show, never a blank page while a request is in flight.
 */
export function wizardSteps(
  kind: VehicleKind,
  builtIn: ControlProfile | undefined,
  catalog: ControlCatalog | undefined,
): readonly WizardStep[] {
  return [
    ...channelSteps(builtIn, catalog),
    {
      id: 'arm',
      kind: 'ARM',
      title: 'Arm',
      instruction: 'Flip the switch you want to arm with.',
      caveat: ARM_HOLD_CAVEAT,
    },
    {
      id: 'mode',
      kind: 'MODE',
      title: 'Mode',
      instruction: 'Flip your mode switch.',
    },
    {
      id: 'extras',
      kind: 'EXTRAS',
      title: 'Extras',
      instruction: 'Anything else on the radio? Pick a switch for emergency stop, return home, or an aux function.',
    },
    {
      id: 'review',
      kind: 'REVIEW',
      title: 'Review',
      instruction: 'Move every stick and flip every switch — check each one lands where you expect.',
    },
  ];
}

const ARM_ACTIONS: ReadonlySet<ControlAction> = new Set(['ARM', 'TOGGLE_ARM']);
const MODE_ACTIONS: ReadonlySet<ControlAction> = new Set(['SET_MODE']);
const EXTRAS_ACTIONS: ReadonlySet<ControlAction> = new Set(['EMERGENCY_STOP', 'RETURN_TO_HOME', 'AUX_FUNCTION']);

/** {@link ARM_ACTIONS} widened with `DISARM` — the family {@link actionsForStep} offers the `ARM`
 * step's per-position selects. Kept distinct from {@link ARM_ACTIONS}: a step counts as *done*
 * ({@link stepStatus}) only once something actually arms, but the editor offering the Arm step must
 * still let the operator choose Disarm for the position that is not Arm — {@link ARM_STEP_DEFAULTS}
 * itself defaults `LOW → DISARM` alongside `HIGH → ARM`. */
const ARM_STEP_ACTIONS: ReadonlySet<ControlAction> = new Set(['ARM', 'DISARM', 'TOGGLE_ARM']);

/** Whether any `ACTIONS` control in the draft fires one of `actions` from any of its positions. */
function firesAny(draft: ProfileDraft, actions: ReadonlySet<ControlAction>): boolean {
  return draft.controls.some((c) => c.role === 'ACTIONS' && c.positions.some((p) => actions.has(p.action)));
}

/**
 * Which non-`CHANNEL` step kind a bound `ACTIONS` control belongs to, read off what its positions
 * actually fire — used by the `EXTRAS` step's "anything else on the radio" list, so it shows only
 * controls nothing has claimed yet rather than re-offering the Arm or Mode step's own control as if
 * it were still unbound. `undefined` for a `CHANNEL` control, or an `ACTIONS` control with nothing
 * chosen on any position yet.
 */
export function actionStepKindOf(control: ControlDraft): 'ARM' | 'MODE' | 'EXTRAS' | undefined {
  if (control.role !== 'ACTIONS') {
    return undefined;
  }
  if (control.positions.some((p) => ARM_ACTIONS.has(p.action))) {
    return 'ARM';
  }
  if (control.positions.some((p) => MODE_ACTIONS.has(p.action))) {
    return 'MODE';
  }
  if (control.positions.some((p) => EXTRAS_ACTIONS.has(p.action))) {
    return 'EXTRAS';
  }
  return undefined;
}

/**
 * The catalogue actions one `ARM`/`MODE`/`EXTRAS` step's per-position selects should offer —
 * narrowed to the family that step is actually asking about (Arm/Disarm/Toggle arm, Set mode, or
 * the Extras trio) rather than the old flat editor's single list of every action regardless of
 * which step opened it. A `CHANNEL`/`REVIEW` step (or an absent catalogue) yields every action the
 * catalogue has, unfiltered — there is no narrower family to apply.
 */
export function actionsForStep(
  step: WizardStep,
  catalog: ControlCatalog | undefined,
): readonly {
  readonly name: ControlAction;
  readonly label: string;
  readonly parameter: ControlActionParameter;
  readonly dangerous: boolean;
}[] {
  const all = catalog?.actions ?? [];
  const family: ReadonlySet<ControlAction> | undefined =
    step.kind === 'ARM'
      ? ARM_STEP_ACTIONS
      : step.kind === 'MODE'
        ? MODE_ACTIONS
        : step.kind === 'EXTRAS'
          ? EXTRAS_ACTIONS
          : undefined;
  return family ? all.filter((a) => family.has(a.name)) : all;
}

/**
 * Whether a step is done, read off the draft rather than tracked as separate wizard state — the
 * step rail can never disagree with what is actually bound, because it is computed from the same
 * draft the review step and the save button read.
 *
 * `REVIEW` is never `'done'`: it is not a thing to complete, it is where completion is checked.
 */
export function stepStatus(step: WizardStep, draft: ProfileDraft): 'done' | 'open' {
  switch (step.kind) {
    case 'CHANNEL':
      return draft.controls.some((c) => c.role === 'CHANNEL' && c.function === step.function) ? 'done' : 'open';
    case 'ARM':
      return firesAny(draft, ARM_ACTIONS) ? 'done' : 'open';
    case 'MODE':
      return firesAny(draft, MODE_ACTIONS) ? 'done' : 'open';
    case 'EXTRAS':
      return firesAny(draft, EXTRAS_ACTIONS) ? 'done' : 'open';
    case 'REVIEW':
      return 'open';
  }
}

/**
 * One physical control's observed excursion since a step opened. `min`/`max` are in the reading's
 * own native range (`[-1,1]` for an axis, `[0,1]` for a button); `firstSign` is the sign
 * (`-1`/`0`/`1`) of the first reading whose magnitude crossed {@link EXCURSION_THRESHOLD} away from
 * rest — `0` if the control never moved far enough to count — and is what
 * {@link channelStepDefaults} reads to preselect a direction tile.
 */
export interface DetectSample {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly min: number;
  readonly max: number;
  readonly firstSign: number;
}

function signOfFirstExcursion(raw: number): number {
  return Math.abs(raw) >= EXCURSION_THRESHOLD ? Math.sign(raw) : 0;
}

function accumulate(
  target: Map<string, DetectSample>,
  source: ControlSource,
  values: readonly number[],
): void {
  values.forEach((raw, sourceIndex) => {
    const key = controlKey(source, sourceIndex);
    const existing = target.get(key);
    target.set(key, {
      source,
      sourceIndex,
      min: existing ? Math.min(existing.min, raw) : raw,
      max: existing ? Math.max(existing.max, raw) : raw,
      // Locked at the first excursion that clears the threshold; a control that already moved away
      // from rest keeps the direction it first moved in, no matter which way it drifts afterward.
      firstSign: existing && existing.firstSign !== 0 ? existing.firstSign : signOfFirstExcursion(raw),
    });
  });
}

/**
 * Folds one frame's raw readings into `samples`, returning a new map (samples are never mutated in
 * place, so a caller can hold onto a previous frame's snapshot safely).
 *
 * Every index in `axes`/`buttons` is recorded on every call, including ones sitting at rest — that
 * is what lets {@link detectedControl} tell "moved a little" apart from "never touched": an
 * untouched control's `min`/`max` collapse to its resting value and its travel reads as `0`.
 */
export function observeInputs(
  samples: ReadonlyMap<string, DetectSample>,
  axes: readonly number[],
  buttons: readonly number[],
): ReadonlyMap<string, DetectSample> {
  const next = new Map(samples);
  accumulate(next, 'AXIS', axes);
  accumulate(next, 'BUTTON', buttons);
  return next;
}

/** What {@link detectedControl} names as the control the operator moved. */
export interface DetectedControl {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly inputKind: ControlInputKind;
  /** The winning sample's `max - min` — how far it travelled, for a live "detected" readout. */
  readonly travelled: number;
}

/**
 * Guesses what kind of switch an axis is, from how far it swung either side of centre
 * (CONTROLLER-SETUP-CONTEXT.md §4.2: `SWITCH_3` reads `axes[]` only, `SWITCH_2` reads either).
 *
 * An axis that visited both sides of centre (near `-1` *and* near `+1`) is read as a `SWITCH_3` —
 * a genuine third, central detent is what makes both ends reachable from the same rest point. An
 * axis that only ever reached one side (the hardware many 2-position switches wired to an axis
 * actually report: a `0..1` swing, never negative) is read as a `SWITCH_2`. This is a guess, shown
 * to the operator as an editable field, not a claim — CONTROLLER-UX-PLAN.md §2.3's own wireframe
 * shows a `[change]` link right next to it for exactly this reason.
 */
function guessAxisKind(min: number, max: number): ControlInputKind {
  return min <= -AXIS_DETENT && max >= AXIS_DETENT ? 'SWITCH_3' : 'SWITCH_2';
}

/**
 * The control that travelled the furthest since the step opened, above {@link DETECT_TRAVEL_THRESHOLD}
 * — `undefined` when nothing has moved far enough yet, so a step with no detection shows "waiting",
 * never a fabricated guess.
 *
 * @param purpose `'CHANNEL'` reports the winning control's own {@link ControlSource} as its
 *   {@link ControlInputKind} (a channel step is always binding a stick); `'ACTION'` runs
 *   {@link guessAxisKind} for an axis, or reports `'BUTTON'` outright for a button.
 */
export function detectedControl(
  samples: ReadonlyMap<string, DetectSample>,
  purpose: 'CHANNEL' | 'ACTION',
): DetectedControl | undefined {
  let winner: DetectSample | undefined;
  let winnerTravel = 0;
  for (const sample of samples.values()) {
    const travel = sample.max - sample.min;
    if (travel > winnerTravel) {
      winnerTravel = travel;
      winner = sample;
    }
  }
  if (!winner || winnerTravel < DETECT_TRAVEL_THRESHOLD) {
    return undefined;
  }
  const inputKind: ControlInputKind =
    winner.source === 'BUTTON'
      ? 'BUTTON'
      : purpose === 'CHANNEL'
        ? 'AXIS'
        : guessAxisKind(winner.min, winner.max);
  return { source: winner.source, sourceIndex: winner.sourceIndex, inputKind, travelled: winnerTravel };
}

/** What a `CHANNEL` step defaults to, once a control has been detected for it. */
export interface ChannelStepAnswers {
  readonly reversed: boolean;
  readonly travel: ControlTravel;
  readonly rcChannel: number;
}

/**
 * The preselected answers for a `CHANNEL` step: `reversed` from whether the detected control's
 * first deliberate excursion was negative (the operator was asked to move it "up" first — a
 * negative first reading means this input's raw sign is inverted relative to that), `travel`/
 * `rcChannel` carried straight from the built-in ({@link WizardStep#defaultTravel}/
 * {@link WizardStep#defaultChannel}) since detection has no opinion about either.
 *
 * `detected` absent (nothing has moved far enough yet) falls back to the built-in's own direction
 * and channel, un-reversed — the safe starting point an operator who skips the step keeps.
 */
export function channelStepDefaults(
  step: WizardStep,
  detected: DetectedControl | undefined,
  samples: ReadonlyMap<string, DetectSample>,
): ChannelStepAnswers {
  const sample = detected ? samples.get(controlKey(detected.source, detected.sourceIndex)) : undefined;
  return {
    reversed: (sample?.firstSign ?? 0) < 0,
    travel: step.defaultTravel ?? 'CENTERED',
    rcChannel: step.defaultChannel ?? 1,
  };
}

/** `position` → the action ARM defaults that position to; a position absent here (`MIDDLE`) is left
 * unbound, per CONTROLLER-UX-PLAN.md §2.3's own table. */
const ARM_STEP_DEFAULTS: Partial<Record<SwitchPosition, ControlAction>> = { LOW: 'DISARM', HIGH: 'ARM' };

/**
 * The default position→action bindings for an `ACTIONS` step, in the catalogue's own position
 * order. Only the `ARM` step has defaults — `LOW → DISARM`, `HIGH → ARM`, `MIDDLE` left for the
 * operator to choose (a 3-position switch has no obvious third arm-family action). `MODE`/`EXTRAS`
 * start with nothing chosen: there is no safe default flight mode or aux function to guess.
 */
export function actionStepDefaults(
  step: WizardStep,
  inputKind: ControlInputKind,
  catalog: ControlCatalog | undefined,
): readonly PositionAction[] {
  if (step.kind !== 'ARM') {
    return [];
  }
  return positionsOf(catalog, inputKind)
    .filter((position) => ARM_STEP_DEFAULTS[position] !== undefined)
    .map((position) => ({ position, action: ARM_STEP_DEFAULTS[position]!, parameter: null }));
}

/** One tile of the "which way is more" question, asked on every `CHANNEL` step. */
export interface DirectionTile {
  readonly reversed: boolean;
  readonly label: string;
}

/** One tile of the "where does it rest" question, asked only for `THROTTLE`. */
export interface RestsTile {
  readonly travel: ControlTravel;
  readonly label: string;
}

/** The question tiles a `CHANNEL` step's right-hand pane offers, for one function. */
export interface WizardQuestions {
  readonly direction: readonly DirectionTile[];
  /** Empty for every function but `THROTTLE` — travel only has an operator-meaningful choice where
   * "does it spring back to idle or hold" is actually ambiguous (VEHICLE-CONTROL-PROFILES-CONTEXT.md
   * §2 P3: every other function is always `CENTERED`). */
  readonly rests: readonly RestsTile[];
}

/**
 * The question tiles for one channel function (CONTROLLER-UX-PLAN.md §2.3's own table). Direction
 * is asked for every function — "up is more" is meaningful for a steering axis exactly as much as
 * a throttle. Rests is asked only for `THROTTLE`, in the operator's own words rather than the
 * `CENTERED`/`UNIDIRECTIONAL` names the wire uses.
 */
export function questionsFor(fn: ControlFunction): WizardQuestions {
  return {
    direction: [
      { reversed: false, label: 'Up is more' },
      { reversed: true, label: 'Up is less' },
    ],
    rests:
      fn === 'THROTTLE'
        ? [
            { travel: 'UNIDIRECTIONAL', label: 'At the bottom — idle is 0 %' },
            { travel: 'CENTERED', label: 'In the centre — centre is stop' },
          ]
        : [],
  };
}

/**
 * Applies a finished `CHANNEL` step to the draft: the detected control becomes (or is re-declared
 * as) a `CHANNEL` row bound to `step.function` with `answers`.
 *
 * Two invariants this keeps, both from `controller-setup-logic.ts`'s own "one control per input,
 * one control per function" rule:
 * - if the detected input already has a row (of either role), that row is **re-roled**, never
 *   duplicated;
 * - if a *different* row already drives `step.function` — the built-in's own binding, still sitting
 *   on whichever control the operator did not just move — it is dropped, so the function only ever
 *   lives on the control the operator actually pointed at.
 *
 * A no-op (returns `draft` unchanged) when nothing has been detected yet, or the step has no
 * function to bind (never true for a real `CHANNEL` step; guards a caller passing the wrong kind).
 */
export function applyChannelStep(
  draft: ProfileDraft,
  step: WizardStep,
  detected: DetectedControl | undefined,
  answers: ChannelStepAnswers,
): ProfileDraft {
  if (!detected || !step.function) {
    return draft;
  }
  const key = controlKey(detected.source, detected.sourceIndex);
  const fn = step.function;
  const others = draft.controls.filter(
    (c) => draftKey(c) !== key && !(c.role === 'CHANNEL' && c.function === fn),
  );
  const existing = draft.controls.find((c) => draftKey(c) === key);
  const base: ControlDraft = existing ?? blankControlDraft(detected.source, detected.sourceIndex, draft);
  const bound: ControlDraft = {
    ...base,
    kind: detected.inputKind,
    role: 'CHANNEL',
    function: fn,
    rcChannel: answers.rcChannel,
    travel: answers.travel,
    reversed: answers.reversed,
    positions: [],
  };
  return { ...draft, controls: [...others, bound] };
}

/**
 * Applies a finished `ARM`/`MODE`/`EXTRAS` step to the draft: the detected control becomes (or is
 * re-declared as) an `ACTIONS` row with `positions`.
 *
 * Only the "re-role, do not duplicate" half of {@link applyChannelStep}'s invariant applies here —
 * every built-in ships an empty `actionMap` (`ControlProfile#forKind`'s own doc comment: "guessing
 * which button of an unknown gamepad should arm a vehicle is precisely the wrong kind of helpful"),
 * so there is never a pre-existing action binding on a *different* control to remove.
 */
export function applyActionStep(
  draft: ProfileDraft,
  detected: { readonly source: ControlSource; readonly sourceIndex: number },
  inputKind: ControlInputKind,
  positions: readonly PositionAction[],
): ProfileDraft {
  const key = controlKey(detected.source, detected.sourceIndex);
  const existing = draft.controls.find((c) => draftKey(c) === key);
  const base: ControlDraft = existing ?? blankControlDraft(detected.source, detected.sourceIndex, draft);
  const bound: ControlDraft = { ...base, kind: inputKind, role: 'ACTIONS', positions };
  const others = draft.controls.filter((c) => draftKey(c) !== key);
  return { ...draft, controls: [...others, bound] };
}

/** One row of the review step's checklist. `ok` is `undefined` when the check does not apply to
 * this draft (a kind that has no idle-rest throttle, or a layout with no channel controls at all
 * yet) — rendered as neither pass nor fail, never guessed. */
export interface ReviewCheck {
  readonly label: string;
  readonly ok: boolean | undefined;
}

function hasMoved(sample: DetectSample | undefined): boolean {
  return sample !== undefined && sample.max - sample.min >= DETECT_TRAVEL_THRESHOLD;
}

/**
 * The review step's checklist (CONTROLLER-UX-PLAN.md §2.3's "Review" row) — every check is read off
 * the draft plus what has actually been observed on the transmitter during the review step, never
 * assumed from earlier steps having been completed.
 *
 * - **Throttle rests at idle** — only meaningful for `COPTER`/`PLANE`, where a released stick must
 *   be idle, not half power (`ControlProfile`'s own doc comment). `undefined` for `ROVER`/`UNKNOWN`,
 *   where a centred throttle is the *correct* travel, not a defect to check for.
 * - **Every stick moved at least once** — every bound `CHANNEL` control has an observed excursion
 *   above {@link DETECT_TRAVEL_THRESHOLD} in `samples`. `undefined` when the draft has no channel
 *   controls at all (nothing to move).
 * - **Arm is on a switch** / **Mode is on a switch** — mirrors {@link stepStatus}'s own `ARM`/`MODE`
 *   check, so the review list can never disagree with the step rail about whether those steps are
 *   done.
 */
export function reviewChecks(
  draft: ProfileDraft,
  kind: VehicleKind,
  samples: ReadonlyMap<string, DetectSample>,
): readonly ReviewCheck[] {
  const channelControls = draft.controls.filter((c) => c.role === 'CHANNEL');
  const throttle = channelControls.find((c) => c.function === 'THROTTLE');
  const throttleOk =
    kind === 'COPTER' || kind === 'PLANE' ? (throttle ? throttle.travel === 'UNIDIRECTIONAL' : false) : undefined;
  const everyStickMoved =
    channelControls.length === 0 ? undefined : channelControls.every((c) => hasMoved(samples.get(draftKey(c))));

  return [
    { label: 'Throttle rests at idle', ok: throttleOk },
    { label: 'Every stick moved at least once', ok: everyStickMoved },
    { label: 'Arm is on a switch', ok: firesAny(draft, ARM_ACTIONS) },
    { label: 'Mode is on a switch', ok: firesAny(draft, MODE_ACTIONS) },
  ];
}
