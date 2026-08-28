import type {
  ActionBinding,
  ControlBinding,
  ControlCatalog,
  ControlFunction,
  ControlInputKind,
  ControlSource,
  ManualControlChannelBinding,
  SwitchPosition,
  VehicleKind,
} from '../api/models';
import {
  REST_VALUE,
  knobLeftPercent,
  knobTopPercent,
  padsFrom,
  type ControlPad,
} from './control-surface-logic';
import {
  actionLabel,
  controlKey,
  controlLabel,
  positionOf,
  rulesFrom,
} from './control-action-logic';

/**
 * Pure, Angular-free logic behind `vision-transmitter-view`
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §2.1) — assembling the pads, switch rows and unmapped
 * line the component draws, split out so all of it unit-tests without a browser or `TestBed`, the
 * same way every other `core/rc/*-logic.ts` file does.
 */

/**
 * A pad's two bindings accept either shape the wire carries: the engaged frame's
 * {@link ManualControlChannelBinding} (always fully populated) or a profile's own
 * {@link ControlBinding} (`travel`/`label` optional there only because a *write* ignores them —
 * a read always fills them in, but this type still has to allow for their absence honestly).
 */
export type ChannelMapLike = readonly ManualControlChannelBinding[] | readonly ControlBinding[];

function isControlBinding(
  binding: ManualControlChannelBinding | ControlBinding,
): binding is ControlBinding {
  return 'deadband' in binding;
}

function functionLabel(fn: ControlFunction, catalog: ControlCatalog | undefined): string {
  return catalog?.functions.find((f) => f.name === fn)?.label ?? fn;
}

/**
 * Adapts one profile {@link ControlBinding} into the {@link ManualControlChannelBinding} shape
 * `control-surface-logic.ts#padsFrom` and the knob math already understand — filling `travel`/
 * `label` from the catalogue only in the defensive case a read genuinely omitted them, never
 * fabricating a value beyond "the safest structural default" (`'CENTERED'`, the type most controls
 * use, and never a guessed idle position for a control this view hasn't been told is unidirectional).
 */
export function toChannelBinding(
  binding: ControlBinding,
  catalog: ControlCatalog | undefined,
): ManualControlChannelBinding {
  return {
    source: binding.source,
    kind: binding.kind,
    function: binding.function,
    travel: binding.travel ?? 'CENTERED',
    sourceIndex: binding.sourceIndex,
    rcChannel: binding.rcChannel,
    minMicros: binding.minMicros,
    centerMicros: binding.centerMicros,
    maxMicros: binding.maxMicros,
    label: binding.label ?? functionLabel(binding.function, catalog),
  };
}

/** Normalizes either wire shape {@link ChannelMapLike} accepts into one the pad/knob math reads. */
export function normalizeChannelMap(
  channelMap: ChannelMapLike,
  catalog: ControlCatalog | undefined,
): readonly ManualControlChannelBinding[] {
  return channelMap.map((binding) =>
    isControlBinding(binding) ? toChannelBinding(binding, catalog) : binding,
  );
}

/** The pads to draw — {@link normalizeChannelMap} then the unchanged `padsFrom`. */
export function displayPads(
  channelMap: ChannelMapLike,
  catalog: ControlCatalog | undefined,
): readonly ControlPad[] {
  return padsFrom(normalizeChannelMap(channelMap, catalog));
}

/** Where a pad axis's rest mark sits, and whether it draws heavier (an idle stop, not a crossing). */
export interface PadRestMark {
  readonly percent: number;
  readonly heavy: boolean;
}

function restMark(
  binding: ManualControlChannelBinding | undefined,
  percentOf: (binding: ManualControlChannelBinding | undefined, value: number) => number,
): PadRestMark | undefined {
  return binding
    ? { percent: percentOf(binding, REST_VALUE), heavy: binding.travel === 'UNIDIRECTIONAL' }
    : undefined;
}

/**
 * The pad's horizontal rest mark, or `undefined` for a vertical-only pad.
 *
 * A centred control's rest sits at 50% (the crosshair's own centre); a unidirectional one sits at
 * 0% (the pad's low edge) and draws heavier — the same distinction
 * `control-surface-logic.ts#springsBack` draws for what happens when the operator lets go.
 */
export function padRestX(pad: ControlPad): PadRestMark | undefined {
  return restMark(pad.x, knobLeftPercent);
}

/** The pad's vertical rest mark, or `undefined` for a horizontal-only pad. See {@link padRestX}. */
export function padRestY(pad: ControlPad): PadRestMark | undefined {
  return restMark(pad.y, knobTopPercent);
}

/**
 * Mirrors `ControlInputKind.java`'s own `positions()` table — used only when the catalogue hasn't
 * loaded. Safe as a fallback specifically *because* these counts are the enum's own fixed shape
 * (one Java source of truth with four members), not a deployment-varying fact like
 * `ControlAction`'s dangerous flags (decision C8, which this file does not hardcode — see
 * {@link actionSwitchRows}'s use of `rulesFrom`). The catalogue is still tried first so a normal
 * load never silently drifts from it.
 */
const FALLBACK_POSITIONS: Readonly<Record<ControlInputKind, readonly SwitchPosition[]>> = {
  AXIS: [],
  BUTTON: ['HIGH'],
  SWITCH_2: ['LOW', 'HIGH'],
  SWITCH_3: ['LOW', 'MIDDLE', 'HIGH'],
};

/** The switch-gauge cells to draw for one input kind: 1 for a button, 2 or 3 for a switch. */
export function positionsForKind(
  kind: ControlInputKind,
  catalog: ControlCatalog | undefined,
): readonly SwitchPosition[] {
  return catalog?.inputKinds.find((k) => k.name === kind)?.positions ?? FALLBACK_POSITIONS[kind];
}

/** One cell of a switch-gauge row's action list ("Disarm · — · Arm"). */
export interface ActionCell {
  readonly position: SwitchPosition;
  readonly lit: boolean;
  readonly text: string;
  readonly dangerous: boolean;
}

/** One bound-switch row: the gauge plus its action text. */
export interface ActionSwitchRow {
  readonly key: string;
  readonly idLabel: string;
  readonly kind: ControlInputKind;
  /** Same order as {@link cells} — the exact shape `vision-switch-gauge`'s `positions` input wants. */
  readonly positions: readonly SwitchPosition[];
  readonly position: SwitchPosition;
  readonly holding: boolean;
  readonly cells: readonly ActionCell[];
}

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

/**
 * Whether the dispatcher's hold-to-fire notice belongs to this row.
 *
 * `holding` is `ControlActionDispatcher.holding()`'s free text — `"Hold ${label} to …"`
 * (`core/rc/control-action-dispatcher.ts`) — not a structured `{key}`; only one control can ever be
 * mid-hold at a time, so matching this row's own {@link controlLabel} against that exact prefix is
 * unambiguous without the dispatcher needing to carry a key on the wire for one view's benefit.
 */
export function holdingMatchesRow(holding: string | undefined, idLabel: string): boolean {
  return holding !== undefined && holding.startsWith(`Hold ${idLabel} `);
}

/** Assembles every action-bound row: gauge cells, live position, and the action text list. */
export function actionSwitchRows(
  actionMap: readonly ActionBinding[],
  axes: readonly number[],
  buttons: readonly number[],
  catalog: ControlCatalog | undefined,
  holding: string | undefined,
  vehicleKind?: VehicleKind,
): readonly ActionSwitchRow[] {
  const dangerous = rulesFrom(catalog).dangerous;
  return actionMap.map((binding) => {
    const idLabel = controlLabel(binding.source, binding.sourceIndex);
    const raw = readRaw(binding.source, binding.sourceIndex, axes, buttons);
    const position = positionOf(binding.source, binding.kind, raw);
    const positions = positionsForKind(binding.kind, catalog);
    const cells = positions.map((p): ActionCell => {
      const fired = binding.positions.find((pa) => pa.position === p);
      return {
        position: p,
        lit: p === position,
        text: fired ? actionLabel(fired.action, fired.parameter, vehicleKind) : '—',
        dangerous: fired ? dangerous.has(fired.action) : false,
      };
    });
    return {
      key: controlKey(binding.source, binding.sourceIndex),
      idLabel,
      kind: binding.kind,
      positions,
      position,
      holding: holdingMatchesRow(holding, idLabel),
      cells,
    };
  });
}

/** One input the device reports that neither map claims. */
export interface UnmappedControl {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly label: string;
}

/**
 * Inputs the device reports that neither `channelMap` nor `actionMap` claims.
 *
 * Duck-types `channelMap` down to the two fields {@link controlKey} needs rather than calling
 * `control-action-logic.ts#boundControlKeys` directly: that helper takes a whole `ControlProfile`,
 * and this component's own `channelMap` input also accepts the engaged frame's
 * {@link ManualControlChannelBinding}`[]`, which lacks `ControlBinding`'s `deadband`/`reversed`
 * fields and so isn't assignable to a real profile. Same key, same rule, without requiring one.
 */
export function unmappedControls(
  channelMap: readonly { readonly source: ControlSource; readonly sourceIndex: number }[],
  actionMap: readonly ActionBinding[],
  axesLength: number,
  buttonsLength: number,
): readonly UnmappedControl[] {
  const bound = new Set<string>();
  for (const binding of channelMap) {
    bound.add(controlKey(binding.source, binding.sourceIndex));
  }
  for (const binding of actionMap) {
    bound.add(controlKey(binding.source, binding.sourceIndex));
  }

  const unmapped: UnmappedControl[] = [];
  for (let i = 0; i < axesLength; i++) {
    if (!bound.has(controlKey('AXIS', i))) {
      unmapped.push({ source: 'AXIS', sourceIndex: i, label: controlLabel('AXIS', i) });
    }
  }
  for (let i = 0; i < buttonsLength; i++) {
    if (!bound.has(controlKey('BUTTON', i))) {
      unmapped.push({ source: 'BUTTON', sourceIndex: i, label: controlLabel('BUTTON', i) });
    }
  }
  return unmapped;
}
