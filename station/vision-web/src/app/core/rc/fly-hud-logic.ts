import type { ControlFunction, ManualControlChannelBinding } from '../api/models';
import type { ManualControlEngageState } from './manual-control-client';
import type { RcSourceKind } from './rc-source.service';
import type { IconName } from '../../shared/ui/icon-registry';

/**
 * Pure logic behind `fly-hud.ts` (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3, wave WEB1) — the
 * on-video control HUD's own badge/widget-shape/toast rules, split out so they unit-test without
 * `TestBed`, the same split every other `*-logic.ts` file beside a component in this codebase keeps.
 */

// --- At-rest badge (§3 "one commandable badge (icon + one word: Ready/No link/Not commandable)") --

export interface HudBadge {
  readonly text: 'Ready' | 'No link' | 'Not commandable';
  readonly icon: IconName;
}

export interface HudBadgeInput {
  readonly canCommand: boolean;
  readonly sourceKind: RcSourceKind;
  readonly gamepadConnected: boolean;
}

/**
 * The at-rest zone's one badge — a narrower mirror of `rc-monitor-logic.ts#engageDisabledReason`'s
 * own priority order (the most fundamental blocker first), but only the two conditions §3 names a
 * badge state for: this component only ever mounts once an asset is selected
 * (`fly-hud.ts`'s own host gate, mirroring `flight-command-panel.ts`), so `hasAsset`/"Pick a drone
 * first" never applies here the way it does to `rc-monitor-logic.ts`'s own, wider gate — and
 * `engageState === 'engaging'`/an in-flight session has no badge state of its own; the Take-control
 * pill's own label ("Engaging…") already says that, and the badge stays whatever it last was.
 */
export function hudBadgeFor(input: HudBadgeInput): HudBadge {
  if (!input.canCommand) {
    return { text: 'Not commandable', icon: 'alert' };
  }
  if (input.sourceKind === 'gamepad' && !input.gamepadConnected) {
    return { text: 'No link', icon: 'signal' };
  }
  return { text: 'Ready', icon: 'check' };
}

// --- Engaged input widget shape (§3 "Shapes derive from profile's travel semantics — one component,
// axis-shape driven, not two forks") ---------------------------------------------------------------

export type HudElementKind = 'throttle' | 'glyph2d' | 'bar';

/** One element of the engaged HUD's live input widget. `x`/`y` bindings carry their own `travel`,
 * which is what `control-surface-logic.ts#displayPercentFor` reads to decide a bar's rest point and
 * a glyph's centre-mark — this file only decides *grouping*, never re-derives that math. */
export interface HudElement {
  readonly id: string;
  readonly kind: HudElementKind;
  readonly label: string;
  /** Horizontal binding — set for `'bar'` and `'glyph2d'`. */
  readonly x?: ManualControlChannelBinding;
  /** Vertical binding — set for `'throttle'` and `'glyph2d'`. */
  readonly y?: ManualControlChannelBinding;
}

/**
 * Groups the engaged channel map into the HUD's own live elements — deliberately **not**
 * `control-surface-logic.ts#padsFrom`, which pairs `YAW`/`STEERING` with `THROTTLE` onto one shared
 * pad for the transmitter *picture* (a natural "one stick" reading of a physical control surface).
 * §3's own text draws the HUD's live readout differently: throttle always gets its own dedicated
 * bar — "steering ... + throttle" for a rover, "... + throttle bar" for a copter/plane, throttle
 * named last and separately in both — because on video, at a glance, throttle is the one number an
 * operator's eye needs to find fastest and a shared pad would bury it inside a 2D position.
 *
 * Order: a roll+pitch pair (always `CENTERED`, always paired on an aircraft) becomes one
 * `'glyph2d'` first; every other bound axis except throttle becomes its own `'bar'` in map order
 * (a rover's `STEERING`, an aircraft's `YAW`); throttle — if bound — is always appended last as its
 * own `'throttle'` element. A binding no rule here claims still gets its own `'bar'` rather than
 * being dropped, mirroring `padsFrom`'s own "never drop an axis" precedent — a profile shape this
 * file hasn't been taught about is rendered incompletely, never silently ignored.
 */
export function hudElementsFrom(bindings: readonly ManualControlChannelBinding[]): readonly HudElement[] {
  const axes = bindings.filter((b) => b.source === 'AXIS');
  const claimed = new Set<ControlFunction>();
  const elements: HudElement[] = [];

  const roll = axes.find((b) => b.function === 'ROLL');
  const pitch = axes.find((b) => b.function === 'PITCH');
  if (roll && pitch) {
    claimed.add('ROLL');
    claimed.add('PITCH');
    elements.push({ id: 'ROLL-PITCH', kind: 'glyph2d', label: `${pitch.label} / ${roll.label}`, x: roll, y: pitch });
  }

  for (const binding of axes) {
    if (binding.function === 'THROTTLE' || claimed.has(binding.function)) {
      continue;
    }
    claimed.add(binding.function);
    elements.push({ id: binding.function, kind: 'bar', label: binding.label, x: binding });
  }

  const throttle = axes.find((b) => b.function === 'THROTTLE');
  if (throttle) {
    elements.push({ id: 'THROTTLE', kind: 'throttle', label: throttle.label, y: throttle });
  }

  return elements;
}

// --- Input-source pills (§3 — see this wave's own report for why they render at rest too) ---------

/** The input choice is frozen for the life of a session — swapping sticks mid-flight is not a
 * gesture this platform offers, and the engaged widget above is shaped by the engaged map anyway.
 * Moved here from `rc-monitor-logic.ts` alongside the picker itself (docs/plans/active/
 * FLY-CONTROL-UX-PLAN.md §3, wave WEB1) — the drawer that used to own it is now purely
 * informational. */
export function sourceLocked(engageState: ManualControlEngageState): boolean {
  return engageState === 'engaging' || engageState === 'engaged';
}

// --- The connect ritual's own "open" gate (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 2) --------

export interface OpenTakeControlGateInput {
  readonly canCommand: boolean;
  readonly engageState: ManualControlEngageState;
}

/**
 * The at-rest Take-control pill's own poka-yoke reason for *opening the connect-ritual modal* —
 * deliberately a narrower gate than `rc-monitor-logic.ts#engageDisabledReason` (the modal's own
 * "Start control" button reuses that full gate once a tile is picked, see `take-control-modal.ts`'s
 * own doc comment). This one omits `sourceKind`/`gamepadConnected` on purpose: before W4, an operator
 * whose selected source happened to be a disconnected gamepad could still reach the always-visible
 * at-rest source-select pill and switch away from it, *without* Take-control ever needing to be
 * enabled first. W4 deletes that pill — the picker now lives inside the modal itself — so gating the
 * modal's own front door on the very source problem the modal exists to let the operator fix would
 * strand them: disabled, with no path left to reach the control that would un-disable it. Only the
 * two gates that no tile choice could ever resolve — `canCommand` and an already in-flight handshake
 * — block opening the ritual at all; mirrors `fly-hud-logic.ts#hudBadgeFor`'s own "hasAsset never
 * applies here" precedent (this component only ever mounts once an asset is selected).
 */
export function openTakeControlDisabledReason(input: OpenTakeControlGateInput): string | undefined {
  if (input.engageState === 'engaging') {
    return 'Engaging…';
  }
  if (!input.canCommand) {
    return "This drone isn't commandable right now.";
  }
  return undefined;
}

// --- Transient toasts (§3 — "become toasts ... never persistent video text") -----------------------

export interface HudToast {
  readonly kind: 'warn' | 'error';
  readonly text: string;
}

/**
 * The two transient sentences §3 names explicitly — "Control released — failsafe took over" and
 * "Control denied — …" — as one-shot toasts fired on the *edge* into that state, never on every
 * render while it holds (the rail's own diagnostics block, `rc-monitor.html`, is where the sentence
 * lives for as long as the state does). `undefined` for every other transition, including into a
 * plain operator-initiated `'released'` (clicking Release is not a surprise to the operator who just
 * clicked it, so §3 does not name a toast for it — only the *failsafe* release earns one) and for
 * `to === from` (a caller re-evaluating the same state on an unrelated signal change must not
 * re-fire).
 */
export function hudTransitionToast(
  from: ManualControlEngageState,
  to: ManualControlEngageState,
  deniedReason: string | undefined,
  watchdogTripped: boolean,
): HudToast | undefined {
  if (to === from) {
    return undefined;
  }
  if (to === 'denied') {
    return { kind: 'error', text: `Control denied — ${deniedReason ?? 'the vehicle refused control.'}` };
  }
  if (to === 'released' && watchdogTripped) {
    return { kind: 'warn', text: 'Control released — failsafe took over.' };
  }
  return undefined;
}

// --- Keyboard key-glyph ticker (§3 "keyboard mode shows key-glyph ticker instead of stick glyphs") -

/** Physical key `code` → the short, layout-independent glyph the on-video ticker shows for it — the
 * same `code`s `keyboard-rc-input.service.ts#resolveKey`/`axisKeysDown` already read/report. */
const AXIS_KEY_GLYPHS: Record<string, string> = {
  KeyW: 'W',
  KeyA: 'A',
  KeyS: 'S',
  KeyD: 'D',
  ArrowUp: '↑',
  ArrowDown: '↓',
  ArrowLeft: '←',
  ArrowRight: '→',
};

/** One ticker slot. */
export interface AxisKeyGlyph {
  readonly code: string;
  readonly label: string;
}

/**
 * The ticker's own key set for the currently bound layout: the primary W/A/S/D pad always, the
 * arrow pad only once a second pad exists — mirroring
 * `keyboard-rc-input.service.ts#resolveKey`'s own one-pad/two-pad split
 * (`control-surface-logic.ts#padsFrom`) rather than re-deriving it: the arrows only ever drive
 * anything once `padsFrom(bindings).length > 1`, so listing them on a one-pad (rover) layout would
 * show ticker slots that can never light up.
 *
 * @param onePad `padsFrom(channelMap).length <= 1` — the caller's own call, not re-derived here (this
 *   file stays ignorant of the channel map itself, mirroring every other function in this module).
 */
export function axisKeyGlyphs(onePad: boolean): readonly AxisKeyGlyph[] {
  const primary = ['KeyW', 'KeyA', 'KeyS', 'KeyD'];
  const codes = onePad ? primary : [...primary, 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];
  return codes.map((code) => ({ code, label: AXIS_KEY_GLYPHS[code] }));
}
