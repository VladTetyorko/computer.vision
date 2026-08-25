import { controlKey, controlLabel, positionOf } from './control-action-logic';
import { relayedChannels, type ControlDraft, type ProfileDraft } from './controller-setup-logic';
import type { ControlCatalog, SwitchPosition } from '../api/models';

/**
 * What the station would actually put on the wire for each RC channel, right now
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15).
 *
 * <h2>Why this exists</h2>
 * Everything else on the setup page shows the *input*: the diagram draws a stick at -60%, the
 * cockpit monitor draws the same -60%. Nothing showed the *output*, which meant `reversed` and
 * `travel` were chosen blind — the first place an operator could discover that a reversed throttle
 * was reversed the wrong way was a vehicle that moved. A layout is a claim about what the vehicle
 * will receive, and a claim nobody can check is not much of a claim.
 *
 * <h2>This mirrors the backend, and must keep mirroring it</h2>
 * The mapping is `ControlBinding#toMicros` (vision-flight) restated in TypeScript, including the
 * order of operations: clamp, reverse, deadband, piecewise-linear map, clamp. A switch driving a
 * channel snaps to a detent instead of relaying its raw reading, exactly as `switchMicros` does.
 * This is a **preview**, never the wire: `ChannelMap#apply` on the backend remains the only thing
 * that produces the frame, so a drift between the two shows up here as a wrong number rather than
 * as a wrong command.
 */

/** RC's own pulse-width envelope, mirroring the backend's `RcChannels`. */
const MIN_MICROS = 1000;
const CENTER_MICROS = 1500;
const MAX_MICROS = 2000;

/**
 * MAVLink #70's "leave this channel unchanged" sentinel, which is what `ChannelMap#apply` fills in
 * for every channel no binding drives. Carried here so the strip can say *not sent* rather than
 * imply a relayed 1500.
 */
export const IGNORE_MICROS = 0xffff;

/** One channel of the outgoing frame, as the operator would see it leave. */
export interface ChannelOutput {
  readonly rcChannel: number;
  /** `undefined` when nothing drives this channel — the frame leaves it unchanged. */
  readonly micros: number | undefined;
  /** Where `micros` sits across `[1000,2000]`, as a percentage — for a bar. */
  readonly offsetPercent: number;
  /** Where this channel rests, as a percentage — for the bar's rest tick. */
  readonly restPercent: number;
  /** The row driving it, so a click can open that control's editor. */
  readonly controlKey: string | undefined;
  /** `Axis 3`, `Sw 5`, or `undefined` when nothing drives it. */
  readonly controlLabel: string | undefined;
  /** `Throttle`, in the catalogue's words. */
  readonly functionLabel: string | undefined;
  readonly reversed: boolean;
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

/** Where a control rests, in microseconds — the minimum for one-way travel, the centre otherwise. */
export function restMicros(control: ControlDraft): number {
  return control.travel === 'UNIDIRECTIONAL' ? MIN_MICROS : CENTER_MICROS;
}

function reversePosition(position: SwitchPosition): SwitchPosition {
  if (position === 'LOW') {
    return 'HIGH';
  }
  return position === 'HIGH' ? 'LOW' : 'MIDDLE';
}

/**
 * One raw reading mapped to a pulse width, exactly as `ControlBinding#toMicros` would map it.
 *
 * @param control the row, whose `travel` stands in for the three microsecond fields the wire carries
 * @param raw     `[-1,1]` from the axes array, `[0,1]` from the buttons array; out of range is clamped
 */
export function microsFor(control: ControlDraft, raw: number): number {
  const center = restMicros(control);
  if (control.kind === 'SWITCH_2' || control.kind === 'SWITCH_3') {
    const position = positionOf(control.source, control.kind, raw);
    switch (control.reversed ? reversePosition(position) : position) {
      case 'LOW':
        return MIN_MICROS;
      case 'MIDDLE':
        return center;
      default:
        return MAX_MICROS;
    }
  }
  if (control.source === 'BUTTON') {
    const pressed = control.reversed ? 1 - clamp(raw, 0, 1) : clamp(raw, 0, 1);
    return Math.round(clamp(MIN_MICROS + pressed * (MAX_MICROS - MIN_MICROS), MIN_MICROS, MAX_MICROS));
  }
  const value = control.reversed ? -clamp(raw, -1, 1) : clamp(raw, -1, 1);
  const micros = value >= 0 ? center + value * (MAX_MICROS - center) : center + value * (center - MIN_MICROS);
  return Math.round(clamp(micros, MIN_MICROS, MAX_MICROS));
}

/** Where a pulse width sits across the full envelope, as a percentage. */
export function microsToPercent(micros: number): number {
  return ((clamp(micros, MIN_MICROS, MAX_MICROS) - MIN_MICROS) / (MAX_MICROS - MIN_MICROS)) * 100;
}

function readRaw(source: 'AXIS' | 'BUTTON', index: number, axes: readonly number[], buttons: readonly number[]): number {
  const values = source === 'AXIS' ? axes : buttons;
  return values[index] ?? 0;
}

/**
 * Every relayed channel, driven or not, in channel order.
 *
 * Undriven channels are listed rather than omitted: "CH5 is not sent" is the answer to a question an
 * operator actually asks, and a strip that showed only the bound ones would let a channel the
 * vehicle needs go missing without ever appearing. When two rows drive one channel — which
 * `draftIssues` already refuses to save — the first in row order wins here, matching the backend's
 * own last-writer-wins only in that it shows *a* value rather than pretending the conflict away.
 */
export function channelOutputs(
  draft: ProfileDraft | undefined,
  axes: readonly number[],
  buttons: readonly number[],
  catalog: ControlCatalog | undefined,
): readonly ChannelOutput[] {
  const driving = new Map<number, ControlDraft>();
  for (const control of draft?.controls ?? []) {
    if (control.role === 'CHANNEL' && !driving.has(control.rcChannel)) {
      driving.set(control.rcChannel, control);
    }
  }
  return Array.from({ length: relayedChannels(catalog) }, (_, i) => {
    const rcChannel = i + 1;
    const control = driving.get(rcChannel);
    if (!control) {
      return {
        rcChannel,
        micros: undefined,
        offsetPercent: 0,
        restPercent: 0,
        controlKey: undefined,
        controlLabel: undefined,
        functionLabel: undefined,
        reversed: false,
      };
    }
    const micros = microsFor(control, readRaw(control.source, control.sourceIndex, axes, buttons));
    return {
      rcChannel,
      micros,
      offsetPercent: microsToPercent(micros),
      restPercent: microsToPercent(restMicros(control)),
      controlKey: controlKey(control.source, control.sourceIndex),
      controlLabel: controlLabel(control.source, control.sourceIndex),
      functionLabel: catalog?.functions.find((f) => f.name === control.function)?.label ?? control.function,
      reversed: control.reversed,
    };
  });
}
