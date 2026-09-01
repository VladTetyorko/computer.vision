import type { ControlAction } from '../api/models';

/**
 * Pure logic behind the keyboard's *action* keys — Space (e-stop), `Shift`+`Enter` (arm toggle) and
 * `1`-`4` (mode select) — split out from `KeyboardRcInputService` and `ControlActionDispatcher` the
 * same way `control-action-logic.ts` already splits a bound switch's own edge/hold rules
 * (docs/plans/active/MAVLINK-COMMANDS-PLAN.md decision D3).
 *
 * These keys are deliberately **not** part of an operator's `ActionBinding` layout — they are fixed
 * shortcuts every session gets, on top of whatever switches the operator has bound — so this file has
 * no notion of a `ControlProfile`. What it shares with `control-action-logic.ts` is the same
 * catalogue-driven danger model and edge-triggered firing: `ControlActionDispatcher` feeds both
 * through its one frame-driven `holdKey`/`holdSince`-style hold check rather than forking a second
 * timer (D3's own instruction — "do not build a second hold timer").
 */

/** One recognized action-key chord. `MODE_1`..`MODE_4` read `1`-`4` in order, never `Digit5+` — the
 * vehicle's own `selectableModes` rarely offers more than a handful worth a bare key. */
export type ActionKeyId = 'EMERGENCY_STOP' | 'TOGGLE_ARM' | 'MODE_1' | 'MODE_2' | 'MODE_3' | 'MODE_4';

const MODE_KEY_INDEX: Readonly<Partial<Record<ActionKeyId, number>>> = {
  MODE_1: 0,
  MODE_2: 1,
  MODE_3: 2,
  MODE_4: 3,
};

/** How each chord reads in a toast/hold notice — "Space", "Shift+Enter", "1".."4". */
const ACTION_KEY_LABEL: Readonly<Record<ActionKeyId, string>> = {
  EMERGENCY_STOP: 'Space',
  TOGGLE_ARM: 'Shift+Enter',
  MODE_1: '1',
  MODE_2: '2',
  MODE_3: '3',
  MODE_4: '4',
};

/**
 * Which action key `code` (a `KeyboardEvent.code`, layout-independent) represents, or `undefined` for
 * a key this app doesn't claim as an action key — including a bare `Enter` with no `Shift`, left for
 * the axis-key resolver (and, everywhere else, an ordinary form-submit key) to ignore.
 *
 * `Shift`+`Enter` is checked at the moment `Enter` goes down, using the browser's own live modifier
 * state (`event.shiftKey`) — not a separately-tracked "is Shift currently held" flag — so releasing
 * Shift first and `Enter` second does not retroactively un-arm a chord that already registered. The
 * hold itself still cancels the ordinary way once `Enter` comes back up, or on blur — see
 * {@link holdContinues}.
 */
export function actionKeyIdFor(code: string, shiftKey: boolean): ActionKeyId | undefined {
  if (code === 'Space') {
    return 'EMERGENCY_STOP';
  }
  if (code === 'Enter') {
    return shiftKey ? 'TOGGLE_ARM' : undefined;
  }
  switch (code) {
    case 'Digit1':
      return 'MODE_1';
    case 'Digit2':
      return 'MODE_2';
    case 'Digit3':
      return 'MODE_3';
    case 'Digit4':
      return 'MODE_4';
    default:
      return undefined;
  }
}

/** What one action key resolves to right now — the command it sends and whether it needs the
 * dangerous hold — or `undefined` when it currently has nothing to fire. */
export interface ResolvedActionKey {
  readonly action: ControlAction;
  readonly parameter?: string | null;
  /** How this fire reads in a toast/hold notice — see {@link actionKeyIdFor}'s callers. */
  readonly label: string;
  /** Whether this needs `ControlActionDispatcher`'s existing `DANGEROUS_HOLD_MS` hold before firing. */
  readonly dangerous: boolean;
}

/**
 * Resolves one action key against live data — never a hardcoded mode list, and never a fixed danger
 * rule for `SET_MODE` beyond what the catalogue itself says.
 *
 * - `EMERGENCY_STOP` is **always** immediate (`dangerous: false`), even though a bound switch's own
 *   e-stop is usually catalogued as dangerous — D3's rationale is explicit that an accidental e-stop
 *   is the safe outcome, so the keyboard shortcut never earns the friction a switch does.
 * - `TOGGLE_ARM` is **always** the dangerous hold — the same 600 ms a bound arm switch earns,
 *   unconditionally, not gated on the catalogue.
 * - `MODE_1`..`MODE_4` read `selectableModes[0..3]`; a digit past what the vehicle actually reports
 *   resolves to `undefined` — the caller sends nothing rather than fabricating a mode name that was
 *   never in the vehicle's own list. Whether firing holds is read straight from the catalogue's own
 *   `SET_MODE` entry (`dangerousActions`) — the identical rule a bound switch's `SET_MODE` position
 *   already follows, so a keyboard shortcut can never be less careful than a physical control for the
 *   same action.
 *
 * @param id the chord that fired
 * @param selectableModes the vehicle's own mode names, in the order it reports them
 * @param dangerousActions the catalogue's own danger flags (`ControlActionRules.dangerous`)
 */
export function resolveActionKey(
  id: ActionKeyId,
  selectableModes: readonly string[],
  dangerousActions: ReadonlySet<ControlAction>,
): ResolvedActionKey | undefined {
  const label = ACTION_KEY_LABEL[id];
  if (id === 'EMERGENCY_STOP') {
    return { action: 'EMERGENCY_STOP', label, dangerous: false };
  }
  if (id === 'TOGGLE_ARM') {
    return { action: 'TOGGLE_ARM', label, dangerous: true };
  }
  const index = MODE_KEY_INDEX[id];
  const mode = index === undefined ? undefined : selectableModes[index];
  if (mode === undefined) {
    return undefined;
  }
  return { action: 'SET_MODE', parameter: mode, label, dangerous: dangerousActions.has('SET_MODE') };
}

/**
 * `"arm"` or `"disarm"` — resolved from the **live** telemetry flag the caller passes, never a
 * remembered client-side toggle. Mirrors `ControlActionDispatcher#command`'s own `TOGGLE_ARM`
 * resolution (`armed() ? disarm : arm`) exactly, so a hold-in-progress notice never promises a
 * different verb than what actually sends once the hold completes; `undefined` (armed state not yet
 * known) reads the same as `false` — the safer assumption when the platform genuinely doesn't know.
 */
export function toggleArmVerb(armed: boolean | undefined): 'arm' | 'disarm' {
  return armed === true ? 'disarm' : 'arm';
}

/**
 * Which action keys landed a genuinely **new** press this frame — edge-triggered exactly like a
 * bound switch's own `pendingActions` (`control-action-logic.ts`), so a key held across many frames
 * (or the browser's own auto-repeat, which never changes *this* held-set's membership) fires once,
 * not once per frame.
 *
 * Unlike a physical switch, there is no "already-settled" first-frame rule here: a keyboard's held
 * set legitimately starts empty, so the very first press after the source is enabled is meant to
 * fire, not be swallowed as background state. `ControlActionDispatcher` applies its own version of
 * "treat the first observation as settled" only across an enable/disable or asset change (its own
 * `bind()`/`onFrame`), not inside this function.
 */
export function newActionKeyPresses(
  previouslyDown: ReadonlySet<ActionKeyId>,
  currentlyDown: ReadonlySet<ActionKeyId>,
): readonly ActionKeyId[] {
  const pressed: ActionKeyId[] = [];
  for (const id of currentlyDown) {
    if (!previouslyDown.has(id)) {
      pressed.push(id);
    }
  }
  return pressed;
}

/**
 * Whether an in-progress hold for `id` is still backed by a physically-held key. `false` the instant
 * it isn't — including `currentlyDown` being empty because a blur/tab-hide/`setEnabled(false)`
 * cleared every held key — which is exactly what turns a lost `keyup` into a cancelled hold instead
 * of one that silently completes on refocus (D3's safety-edges requirement).
 */
export function holdContinues(id: ActionKeyId | undefined, currentlyDown: ReadonlySet<ActionKeyId>): boolean {
  return id !== undefined && currentlyDown.has(id);
}

/**
 * Never steals a keystroke meant for a form field — shared with `KeyboardRcInputService`'s axis-key
 * handling so one global `keydown` listener stays out of an
 * `<input>`/`<select>`/`<textarea>`/contenteditable regardless of focus, for action keys exactly as
 * for W/A/S/D (typing `1` in a form must not switch modes).
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  // `isContentEditable` reads `undefined` rather than `false` on at least one DOM test environment
  // this suite runs under — coerced explicitly so this always returns a real boolean, never a falsy
  // non-boolean that would still satisfy an `if`/`&&` caller but fail a strict `toBe(false)`.
  return tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA' || Boolean(target.isContentEditable);
}
