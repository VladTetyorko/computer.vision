import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import type { ControlFunction, ManualControlChannelBinding } from '../api/models';
import { REST_VALUE, axesFrom, padsFrom, springsBack } from './control-surface-logic';
import { actionKeyIdFor, isTypingTarget, type ActionKeyId } from './keyboard-action-logic';

/**
 * `KeyboardRcInputService` — the keyboard's half of the RC input seam
 * (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave K), a third {@link RcSourceKind} alongside the
 * Gamepad-API transmitter and the on-screen drag surface (`rc-source.service.ts`). Exposes
 * `axes()`/`buttons()` in the same shape `RcInputService`/`VirtualRcInputService` expose their own,
 * so `ManualControlClient` streams it without knowing the difference (P10: source, not client).
 *
 * **Provided per host** — `rc-monitor.ts`'s own `providers` array owns this alongside the other two
 * input services, so a closed drawer stops listening.
 *
 * <h2>Keys drive functions, not axis indexes</h2>
 * A gamepad axis is a physical stick; a keyboard has none, so this service maps *keys* to *control
 * functions* — `W`/`S` (and `↑`/`↓` on a one-pad layout) drive throttle, `A`/`D` drive whichever of
 * yaw/steering the vehicle actually binds, and on a two-pad layout the arrow keys are freed up for
 * `↑`/`↓` pitch and `←`/`→` roll. {@link bind} supplies the engaged channel map (or the operator's
 * own resolved profile before one exists) so this service knows which `sourceIndex` each function
 * currently lives on — the same map `VirtualRcInputService`/`rc-monitor.ts` read pads from
 * (`padsFrom`/`transmitter-view-logic.ts#normalizeChannelMap`).
 *
 * <h2>A tap is a nudge, a hold is full deflection</h2>
 * Values ramp linearly toward the extreme over {@link RAMP_MS} rather than snapping — ramp state is
 * per bound *function*, not per key, so `W` and `↑` both held for the same throttle (the one-pad
 * redundancy) don't ramp twice as fast, and `W`+`S` held together cancel to a standstill rather than
 * fighting. On release, a centred control springs back to rest immediately
 * (`control-surface-logic.ts#springsBack`) — a unidirectional throttle holds exactly where it was
 * left, the same "no spring on this one" rule the on-screen surface and a real stick both follow.
 *
 * <h2>The deadmen</h2>
 * {@link setEnabled}`(false)` — driven by `RcSource` whenever another source is selected — and a
 * `blur`/`visibilitychange(hidden)` on the window both release every held key immediately, the same
 * instant-cutoff guarantee the gamepad's unplug deadman gives `ManualControlClient` (this source is
 * always {@link https://en.wikipedia.org/wiki/Dead_man%27s_switch "live"} otherwise, exactly like the
 * on-screen surface — it cannot be unplugged, so `RcSource.live()` never demotes it on that account).
 * Listeners are only ever attached while {@link setEnabled}`(true)` — an unselected keyboard source
 * must not steal `W`/`A`/`S`/`D` from the rest of the page.
 *
 * <h2>Action keys ride the same deadmen, not the ramp</h2>
 * `Space`/`Shift+Enter`/`1`-`4` (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3) are edge-triggered,
 * not ramped: this service only tracks *which of them are currently physically held*
 * ({@link actionKeysDown}), republishing it every {@link TICK_MS} tick — with a fresh `Set` identity
 * even when nothing changed — for as long as any is down, piggybacking on the same tick loop the axis
 * ramp already runs. That heartbeat is what lets `ControlActionDispatcher`'s frame-driven 600 ms hold
 * check keep re-evaluating "has enough time passed yet" while, say, `Shift+Enter` is held and no
 * axis is moving — reusing that check rather than this service (or the dispatcher) starting a second
 * `setTimeout`-style hold timer. Resolving *what* a held key currently means — mode names, arm vs.
 * disarm, the danger/hold policy — is entirely the dispatcher's job
 * (`core/rc/keyboard-action-logic.ts`); this service only ever reports which physical keys are down,
 * the same "source, not client" split the axis half already keeps. The same `blur`/
 * `visibilitychange(hidden)`/`setEnabled(false)` deadmen above clear these too, immediately — a lost
 * `keyup` can never let a re-focus complete an abandoned arm hold.
 */
@Injectable()
export class KeyboardRcInputService {
  private readonly _bindings = signal<readonly ManualControlChannelBinding[]>([]);
  private readonly _values = signal<ReadonlyMap<number, number>>(new Map());
  private readonly _enabled = signal(false);

  /** The engaged/resolved map this service currently reads function→sourceIndex from. */
  readonly bindings = this._bindings.asReadonly();

  /** The `axes` array a `channels` frame carries — same shape `RcInputService.axes()` produces. */
  readonly axes = computed(() => axesFrom(this._values(), this._bindings()));

  /** No function this service drives is a button — arm/disarm/mode stay on the flight-command panel
   * and bound switches, exactly like `VirtualRcInputService`. */
  readonly buttons = computed<readonly number[]>(() => []);

  private readonly heldKeys = new Map<string, KeyTarget>();
  private tickHandle: ReturnType<typeof setInterval> | null = null;
  private lastTickAt = 0;

  /** Physical key `code` → the action it drives, for whichever of Space/Shift+Enter/1-4 are
   * currently held (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3). */
  private readonly heldActionKeys = new Map<string, ActionKeyId>();
  private readonly _actionKeysDown = signal<ReadonlySet<ActionKeyId>>(new Set());

  /** Which action keys are physically down right now — `ControlActionDispatcher`'s own edge/hold
   * state machine reads this every RC frame, exactly as it reads switch positions off
   * `RcSource.buttons()`. A fresh `Set` every tick while any is held (see the class doc's "Action
   * keys ride the same deadmen" section), so the dispatcher's reactive effect keeps re-running even
   * while no axis is moving. */
  readonly actionKeysDown = this._actionKeysDown.asReadonly();

  /**
   * Which axis-driving key `code`s (`KeyW`/`KeyA`/`KeyS`/`KeyD`/the four arrows) are physically held
   * right now — the on-video HUD's keyboard "key-glyph ticker"
   * (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3: "keyboard mode shows key-glyph ticker instead of
   * stick glyphs") reads this to light up the keys actually driving the vehicle, the same idea as
   * {@link actionKeysDown} but for the ramped axis keys rather than the edge-triggered action chords.
   * Republished on every held/released key (not every {@link TICK_MS} tick like `actionKeysDown` —
   * this set only ever changes on a key edge, so there is nothing to re-emit while it's steady), with
   * a fresh `Set` identity each time, mirroring `emitActionKeys`'s own "always a new object" contract.
   * Codes only, not functions — a key that resolves to no bound function (`resolveKey` returning
   * `undefined`) never enters `heldKeys` in the first place, so this is already only ever the keys
   * that are actually driving something.
   */
  private readonly _axisKeysDown = signal<ReadonlySet<string>>(new Set());
  readonly axisKeysDown = this._axisKeysDown.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.setEnabled(false));
  }

  /**
   * Shapes this surface to a channel map — the engaged frame's own `ManualControlChannelBinding[]`,
   * or the operator's resolved profile before one exists (`rc-monitor.ts#transmitterChannelMap`,
   * normalized via `transmitter-view-logic.ts#normalizeChannelMap`). Safe to call repeatedly as that
   * map changes; any value already ramping is dropped rather than carried into a binding it may no
   * longer describe.
   *
   * @param bindings the channel map to read function→sourceIndex from
   */
  bind(bindings: readonly ManualControlChannelBinding[]): void {
    this._bindings.set(bindings);
    this._values.set(new Map());
  }

  /**
   * Whether this service is listening for keyboard input at all — `rc-monitor.ts` wires this to
   * `RcSource.kind() === 'keyboard'` so only the selected source ever captures `window` keys.
   * Disabling releases every held key immediately, same as a blur/hide.
   *
   * @param enabled whether to attach (`true`) or detach (`false`) the window listeners
   */
  setEnabled(enabled: boolean): void {
    if (enabled === this._enabled()) {
      return;
    }
    this._enabled.set(enabled);
    if (enabled) {
      window.addEventListener('keydown', this.onKeyDown);
      window.addEventListener('keyup', this.onKeyUp);
      window.addEventListener('blur', this.releaseAll);
      document.addEventListener('visibilitychange', this.onVisibilityChange);
    } else {
      window.removeEventListener('keydown', this.onKeyDown);
      window.removeEventListener('keyup', this.onKeyUp);
      window.removeEventListener('blur', this.releaseAll);
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      this.releaseAll();
    }
  }

  private readonly onVisibilityChange = (): void => {
    if (document.hidden) {
      this.releaseAll();
    }
  };

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    if (isTypingTarget(event.target)) {
      return;
    }

    const actionId = actionKeyIdFor(event.code, event.shiftKey);
    if (actionId) {
      event.preventDefault();
      if (!this.heldActionKeys.has(event.code)) {
        this.heldActionKeys.set(event.code, actionId);
        this.emitActionKeys();
        this.ensureTicking();
      }
      return;
    }

    const target = resolveKey(event.code, this._bindings());
    if (!target) {
      return;
    }
    event.preventDefault();
    if (!this.heldKeys.has(event.code)) {
      this.heldKeys.set(event.code, target);
      this.emitAxisKeys();
      this.ensureTicking();
    }
  };

  private readonly onKeyUp = (event: KeyboardEvent): void => {
    if (this.heldActionKeys.delete(event.code)) {
      this.emitActionKeys();
      if (this.heldKeys.size === 0 && this.heldActionKeys.size === 0) {
        this.stopTicking();
      }
      return;
    }
    this.releaseKey(event.code);
  };

  private readonly releaseAll = (): void => {
    for (const code of [...this.heldKeys.keys()]) {
      this.releaseKey(code);
    }
    if (this.heldActionKeys.size > 0) {
      this.heldActionKeys.clear();
      this.emitActionKeys();
    }
    this.stopTicking();
  };

  private releaseKey(code: string): void {
    const target = this.heldKeys.get(code);
    if (!target) {
      return;
    }
    this.heldKeys.delete(code);
    this.emitAxisKeys();
    const stillHeld = [...this.heldKeys.values()].some((held) => held.function === target.function);
    if (!stillHeld) {
      const binding = this.findBinding(target.function);
      if (binding && springsBack(binding)) {
        this.setValue(binding.sourceIndex, REST_VALUE);
      }
    }
    if (this.heldKeys.size === 0 && this.heldActionKeys.size === 0) {
      this.stopTicking();
    }
  }

  /** Publishes {@link actionKeysDown} with a brand-new `Set` — deliberately not a value-equal
   * short-circuit, since the tick loop relies on this always registering as "changed" so the
   * dispatcher's effect re-runs on every tick, not only when membership actually differs. */
  private emitActionKeys(): void {
    this._actionKeysDown.set(new Set(this.heldActionKeys.values()));
  }

  /** Publishes {@link axisKeysDown} — see that field's own doc comment. Called at every `heldKeys`
   * mutation point (key down, key release, `releaseAll`'s own per-key `releaseKey` calls). */
  private emitAxisKeys(): void {
    this._axisKeysDown.set(new Set(this.heldKeys.keys()));
  }

  private findBinding(fn: ControlFunction): ManualControlChannelBinding | undefined {
    return this._bindings().find((b) => b.source === 'AXIS' && b.function === fn);
  }

  private setValue(sourceIndex: number, value: number): void {
    const next = new Map(this._values());
    next.set(sourceIndex, value);
    this._values.set(next);
  }

  private ensureTicking(): void {
    if (this.tickHandle !== null) {
      return;
    }
    this.lastTickAt = Date.now();
    this.tickHandle = setInterval(() => this.tick(), TICK_MS);
  }

  private stopTicking(): void {
    if (this.tickHandle !== null) {
      clearInterval(this.tickHandle);
      this.tickHandle = null;
    }
  }

  private tick(): void {
    const now = Date.now();
    const elapsedMs = now - this.lastTickAt;
    this.lastTickAt = now;
    if (this.heldKeys.size === 0 && this.heldActionKeys.size === 0) {
      this.stopTicking();
      return;
    }

    if (this.heldKeys.size > 0) {
      // Net direction per function — two keys held for the same function (the one-pad W/↑ redundancy,
      // or opposite keys like W+S) fold to one sign rather than ramping at double speed or fighting.
      const netByFunction = new Map<ControlFunction, number>();
      for (const target of this.heldKeys.values()) {
        netByFunction.set(target.function, (netByFunction.get(target.function) ?? 0) + target.direction);
      }

      for (const [fn, net] of netByFunction) {
        if (net === 0) {
          continue;
        }
        const binding = this.findBinding(fn);
        if (!binding) {
          continue;
        }
        const current = this._values().get(binding.sourceIndex) ?? REST_VALUE;
        this.setValue(binding.sourceIndex, rampedValue(binding, current, net > 0 ? 1 : -1, elapsedMs));
      }
    }

    if (this.heldActionKeys.size > 0) {
      // Republished every tick, not only on change — see the class doc's "Action keys ride the same
      // deadmen" section for why this heartbeat is what lets the dispatcher's 600 ms hold check keep
      // advancing while, e.g., only Shift+Enter is held and no axis is moving.
      this.emitActionKeys();
    }
  }
}

/** How long a fully-held key takes to ramp a control from rest to its extreme, in ms — long enough
 * that a tap reads as a nudge and a hold reads as a deliberate full deflection, short enough that
 * "hold to drive" doesn't feel laggy on a bench. Not a tunable (CLAUDE.md — no magic numbers, but
 * also no knob for a value nobody should be adjusting per session). */
export const RAMP_MS = 250;

/** How often the ramp advances while a key is held, in ms — smooth enough (~60Hz) that a held key
 * reads as continuous motion rather than visible steps. Also the republish interval for
 * {@link KeyboardRcInputService#actionKeysDown} while any action key is held (exported for specs). */
export const TICK_MS = 16;

interface KeyTarget {
  readonly function: ControlFunction;
  readonly direction: 1 | -1;
}

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

/**
 * One tick's worth of ramp toward `direction`'s extreme, clamped to what `binding.travel` allows —
 * a unidirectional throttle's rest-to-full distance is `[0,1]`, a centred control's is `[-1,1]`, but
 * either way "full deflection" is one unit of travel away from rest, so both reach it in the same
 * {@link RAMP_MS} regardless of travel kind.
 */
function rampedValue(binding: ManualControlChannelBinding, current: number, direction: 1 | -1, elapsedMs: number): number {
  const [lo, hi] = binding.travel === 'UNIDIRECTIONAL' ? [0, 1] : [-1, 1];
  return clamp(current + direction * (elapsedMs / RAMP_MS), lo, hi);
}

/**
 * Which function/direction a physical key drives against the currently bound `channelMap`, or
 * `undefined` for a key this service doesn't claim (either not one of its own keys, or one of its
 * keys but the function it would drive isn't bound at all — e.g. `←`/`→` on a rover with no roll).
 *
 * `code` is `KeyboardEvent.code` (layout-independent physical key), not `.key` — `W`/`A`/`S`/`D`
 * read the same regardless of the operator's keyboard layout, the same convention every game uses.
 */
function resolveKey(code: string, bindings: readonly ManualControlChannelBinding[]): KeyTarget | undefined {
  const has = (fn: ControlFunction): boolean => bindings.some((b) => b.source === 'AXIS' && b.function === fn);
  const onePad = padsFrom(bindings).length <= 1;

  if ((code === 'KeyW' || code === 'KeyS') && has('THROTTLE')) {
    return { function: 'THROTTLE', direction: code === 'KeyW' ? 1 : -1 };
  }
  if (code === 'KeyA' || code === 'KeyD') {
    const fn = (['YAW', 'STEERING'] as const).find(has);
    if (fn) {
      return { function: fn, direction: code === 'KeyD' ? 1 : -1 };
    }
  }
  if (code === 'ArrowUp' || code === 'ArrowDown') {
    if (onePad && has('THROTTLE')) {
      return { function: 'THROTTLE', direction: code === 'ArrowUp' ? 1 : -1 };
    }
    if (!onePad && has('PITCH')) {
      return { function: 'PITCH', direction: code === 'ArrowUp' ? 1 : -1 };
    }
  }
  if ((code === 'ArrowLeft' || code === 'ArrowRight') && !onePad && has('ROLL')) {
    return { function: 'ROLL', direction: code === 'ArrowRight' ? 1 : -1 };
  }
  return undefined;
}
