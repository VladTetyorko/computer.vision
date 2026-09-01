import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { RcSource } from './rc-source.service';
import { KeyboardRcInputService } from './keyboard-rc-input.service';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { describeHttpError } from '../api-error';
import type { ControlAction, FlightCommandResult, ControlProfile, SwitchPosition, VehicleKind } from '../api/models';
import {
  actionLabel,
  controlKey,
  controlLabel,
  pendingActions,
  positionsFrom,
  type ControlActionRules,
} from './control-action-logic';
import {
  holdContinues,
  newActionKeyPresses,
  resolveActionKey,
  toggleArmVerb,
  type ActionKeyId,
} from './keyboard-action-logic';

/**
 * How long a dangerous action's switch must be held before the command is sent, in milliseconds
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C9).
 *
 * The clicked Arm control earns its safety from a two-stage confirm dialog; a bound switch cannot
 * show one, so it earns its own friction instead. Long enough that a knocked switch does not arm an
 * aircraft, short enough that a deliberate flick-and-hold still feels like a control rather than a
 * form. It is not a tunable: an operator who could shorten it would, and the number exists exactly
 * because the moment it matters is the moment nobody is thinking about settings.
 *
 * Reused verbatim for the keyboard's own `Shift`+`Enter` arm hold and any dangerous mode digit
 * (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3) — one constant, one hold policy, whichever control
 * earns it.
 */
const DANGEROUS_HOLD_MS = 600;

/** No key held — the identity `KeyboardRcInputService#actionKeysDown` reads as before the service
 * exists at all (specs that build a narrower harness) or while nothing is pressed. */
const NO_ACTION_KEYS: ReadonlySet<ActionKeyId> = new Set();

/**
 * `ControlActionDispatcher` — turns a flick of a bound switch, or a keyboard action key, into one
 * command (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C2/C3/C9;
 * docs/plans/active/MAVLINK-COMMANDS-PLAN.md decisions D2d/D3).
 *
 * <h2>Why this is REST and not the manual-control socket</h2>
 * A one-shot command does not need a 33 Hz transport, and routing it over the existing command
 * endpoints buys three things for free: the `/ws/manual-control` contract stays frozen, every
 * command keeps the scope gate and audit entry it already has, and — the operator-visible one — a
 * bound switch works **without taking stick control**. Arm the rover from its switch, then decide
 * whether to drive it from this browser at all.
 *
 * <h2>What it will not do</h2>
 * <ul>
 *   <li><b>Fire on arrival.</b> The first frame after binding is read as already-settled
 *       (`control-action-logic.ts#pendingActions`), so a switch left in the ARM position before this
 *       drawer opened does not arm anything the moment it does.</li>
 *   <li><b>Repeat.</b> Edge-triggered: a held switch (or held action key) fires once.</li>
 *   <li><b>Arm on a knock.</b> Anything the catalogue calls dangerous — or, for the keyboard,
 *       `TOGGLE_ARM` unconditionally — must stay held for {@link DANGEROUS_HOLD_MS} first; letting
 *       go early cancels it silently.</li>
 *   <li><b>Overlap.</b> One command is in flight at a time — a switch flicked three times, or a
 *       switch and an action key fired in the same tick, while the first request is still open sends
 *       one command, not several.</li>
 * </ul>
 *
 * <h2>Two independent producers, one dispatcher</h2>
 * A bound switch's edge/hold state (`positions`/`holdKey`/`holdPosition`/`holdSince`) and the
 * keyboard's own action keys (`keyboardPrev`/`keyHoldId`/`keyHoldSince`) are tracked in separate
 * fields — they are genuinely independent physical inputs that can be mid-hold at the same time — but
 * both are driven by the exact same {@link onFrame}, the same {@link DANGEROUS_HOLD_MS} constant, and
 * the same frame-driven "has enough time passed since `Date.now()`-stamped `…Since`" check, never a
 * second `setTimeout`. Both funnel into the one {@link send}, which is what actually enforces
 * one-command-in-flight and the honest ACCEPTED/NO_ACK/error toast split. Space/`Shift`+`Enter`/`1`-`4`
 * work whether or not the operator has ever bound a switch layout at all — they are not part of any
 * `ControlProfile.actionMap` — gated only on the same asset + command permission a bound switch
 * already requires.
 *
 * **Provided per host**, like the rest of the RC stack: it exists only while the Controller drawer
 * is mounted, so nothing dispatches from a page the operator is not looking at.
 */
@Injectable()
export class ControlActionDispatcher {
  private readonly source = inject(RcSource);
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  /** `{ optional: true }` for the same reason `RcSource#keyboard` is (CONTROLLER-UX-PLAN.md §5 wave
   * K's own gotcha): the narrower hand-built harnesses in `control-action-dispatcher.spec.ts` never
   * provide a `KeyboardRcInputService`, and every read below only runs once an asset is actually
   * bound and enabled — unreachable in those specs regardless. Not a "null means the feature is off"
   * contract (CLAUDE.md §10): `rc-monitor.ts`'s own `providers` array always lists all three RC
   * sources, so a `null` here is never actually read in the running app. */
  private readonly keyboard = inject(KeyboardRcInputService, { optional: true });

  private readonly assetId = signal<string | undefined>(undefined);
  private readonly profile = signal<ControlProfile | undefined>(undefined);
  private readonly rules = signal<ControlActionRules>({ dangerous: new Set(), levels: new Map() });
  private readonly armed = signal<boolean | undefined>(undefined);
  private readonly enabled = signal(false);

  /** The vehicle's own mode names, refreshed whenever {@link assetId} changes
   * (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3) — what `1`-`4` read from, never a hardcoded list.
   * `undefined`/failed reads degrade to `[]`: a digit past what the vehicle actually reported does
   * nothing rather than fabricating a mode name (CLAUDE.md's "degrade honestly"). */
  private readonly selectableModes = signal<readonly string[]>([]);
  /** The vehicle kind the same capability read reported — used for a keyboard-fired command's toast
   * wording (e.g. a rover's e-stop reading "Hold") when no `ControlProfile` happens to be bound, so
   * the wording is no less accurate for a keyboard shortcut than for a bound switch. */
  private readonly fetchedVehicleKind = signal<VehicleKind | undefined>(undefined);
  /** Guards a stale in-flight capability read from a since-abandoned asset overwriting a later one. */
  private capabilitiesRequestId = 0;

  /** The last command a bound control actually sent, for the drawer's one-line "what just happened". */
  private readonly _lastFired = signal<string | undefined>(undefined);
  readonly lastFired = this._lastFired.asReadonly();

  /** A dangerous action counting down its hold, so the drawer can say "hold…" rather than nothing.
   * Shared between the switch and keyboard hold machinery below — whichever last started or cancelled
   * a hold owns this line; two dangerous holds racing each other is a cosmetic-only edge case (see
   * the class doc), never one that changes which commands actually fire. */
  private readonly _holding = signal<string | undefined>(undefined);
  readonly holding = this._holding.asReadonly();

  // --- Bound-switch edge/hold state (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md, unchanged) ----
  private positions: ReadonlyMap<string, SwitchPosition> = new Map();
  private holdKey: string | undefined;
  private holdPosition: SwitchPosition | undefined;
  private holdSince = 0;

  // --- Keyboard action-key edge/hold state (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3) --------
  /**
   * The action keys considered already-held as of the last observation. Unlike the switch positions
   * above, this can be seeded *synchronously* in {@link bind} from whatever
   * `KeyboardRcInputService#actionKeysDown` reports right now, rather than deferred to the next
   * reactive frame: a keyboard has no idle polling heartbeat the way a gamepad does, so the "next
   * frame" after `bind()` would otherwise often *be* the operator's own next keypress, which is
   * exactly the edge that must not be swallowed. Reading the live value at bind-time gives the same
   * "a control already in its firing position when this UI starts watching does not fire" guarantee
   * `pendingActions` gives switches, without also eating the operator's first genuine press.
   */
  private keyboardPrev: ReadonlySet<ActionKeyId> = NO_ACTION_KEYS;
  private keyHoldId: ActionKeyId | undefined;
  private keyHoldSince = 0;

  private inFlight = false;

  constructor() {
    effect(() => {
      const axes = this.source.axes();
      const buttons = this.source.buttons();
      const actionKeysDown = this.keyboard?.actionKeysDown() ?? NO_ACTION_KEYS;
      untracked(() => this.onFrame(axes, buttons, actionKeysDown));
    });

    effect(() => {
      const assetId = this.assetId();
      untracked(() => this.refreshSelectableModes(assetId));
    });
  }

  /**
   * Points the dispatcher at one asset and one layout.
   *
   * @param assetId the asset a fired command is sent to; `undefined` disables dispatch
   * @param profile the layout whose `actionMap` is watched; `undefined` disables dispatch
   * @param rules the catalogue's own danger flags and switch levels
   * @param enabled whether the operator may command this asset at all — a viewer's switches (and
   *   action keys) do nothing, the same gate the flight controls use
   */
  bind(
    assetId: string | undefined,
    profile: ControlProfile | undefined,
    rules: ControlActionRules,
    enabled: boolean,
  ): void {
    if (this.profile()?.id !== profile?.id || this.assetId() !== assetId) {
      // A different layout means the remembered positions describe controls that may no longer be
      // bound; clearing them re-arms the "first frame is settled" rule rather than firing an edge
      // against a stale map.
      this.positions = new Map();
      this.cancelHold();
    }
    if (this.assetId() !== assetId || this.enabled() !== enabled) {
      // Same rule for the keyboard's own state: a different asset or a permission flip must not let
      // an in-progress hold (or a stale "already pressed" snapshot) survive across it. Seeded from
      // the live held-set right now (see the field's own doc) rather than deferred to the next frame.
      this.keyboardPrev = this.keyboard?.actionKeysDown() ?? NO_ACTION_KEYS;
      this.cancelKeyHold();
    }
    this.assetId.set(assetId);
    this.profile.set(profile);
    this.rules.set(rules);
    this.enabled.set(enabled);
  }

  /** Latest telemetry's armed flag — what `TOGGLE_ARM` resolves against, read at the moment of use. */
  setArmed(armed: boolean | undefined): void {
    this.armed.set(armed);
  }

  private onFrame(axes: readonly number[], buttons: readonly number[], actionKeysDown: ReadonlySet<ActionKeyId>): void {
    const profile = this.profile();
    const assetId = this.assetId();
    const enabled = this.enabled();
    const vehicleKind = profile?.kind ?? this.fetchedVehicleKind();

    // --- Bound switches (unchanged behaviour) -----------------------------------------------------
    if (!profile || !assetId || !enabled || profile.actionMap.length === 0) {
      this.positions = new Map();
      this.cancelHold();
    } else {
      const current = positionsFrom(profile.actionMap, axes, buttons);
      const fired = pendingActions(profile.actionMap, this.positions, current, this.rules().dangerous);
      this.positions = current;

      // A hold in progress is cancelled the moment its switch leaves the position that started it.
      const holdKey = this.holdKey;
      if (holdKey !== undefined) {
        const held = current.get(holdKey);
        const binding = profile.actionMap.find((b) => controlKey(b.source, b.sourceIndex) === holdKey);
        const holdingAction = binding?.positions.find((p) => p.position === held);
        if (!binding || !holdingAction || held === undefined || held !== this.holdPosition) {
          this.cancelHold();
        } else if (Date.now() - this.holdSince >= DANGEROUS_HOLD_MS) {
          this.cancelHold();
          void this.send(assetId, holdingAction.action, holdingAction.parameter, held,
            controlLabel(binding.source, binding.sourceIndex), profile.kind);
        }
      }

      for (const action of fired) {
        if (action.dangerous) {
          this.holdKey = action.key;
          this.holdPosition = action.position;
          this.holdSince = Date.now();
          const what = actionLabel(action.action, action.parameter, profile.kind).toLowerCase();
          this._holding.set(`Hold ${action.label} to ${what}`);
        } else {
          void this.send(assetId, action.action, action.parameter, action.position, action.label, profile.kind);
        }
      }
    }

    // --- Keyboard action keys (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3) --------------------
    // Independent of any bound profile: Space/Shift+Enter/1-4 work whether or not the operator has
    // ever set up a switch layout, gated only on the same asset + command permission a bound switch
    // already requires.
    if (!assetId || !enabled) {
      this.keyboardPrev = NO_ACTION_KEYS;
      this.cancelKeyHold();
      return;
    }

    if (this.keyHoldId !== undefined) {
      if (!holdContinues(this.keyHoldId, actionKeysDown)) {
        this.cancelKeyHold();
      } else if (Date.now() - this.keyHoldSince >= DANGEROUS_HOLD_MS) {
        const id = this.keyHoldId;
        this.cancelKeyHold();
        const resolved = resolveActionKey(id, this.selectableModes(), this.rules().dangerous);
        if (resolved) {
          void this.send(assetId, resolved.action, resolved.parameter, 'HIGH', resolved.label, vehicleKind);
        }
      }
    }

    const pressed = newActionKeyPresses(this.keyboardPrev, actionKeysDown);
    this.keyboardPrev = actionKeysDown;

    for (const id of pressed) {
      const resolved = resolveActionKey(id, this.selectableModes(), this.rules().dangerous);
      if (!resolved) {
        // e.g. a mode digit past what the vehicle actually reports -- an honest no-op, not a guess.
        continue;
      }
      if (resolved.dangerous) {
        this.keyHoldId = id;
        this.keyHoldSince = Date.now();
        const what = id === 'TOGGLE_ARM'
          ? toggleArmVerb(this.armed())
          : actionLabel(resolved.action, resolved.parameter, vehicleKind).toLowerCase();
        this._holding.set(`Hold ${resolved.label} to ${what}`);
      } else {
        void this.send(assetId, resolved.action, resolved.parameter, 'HIGH', resolved.label, vehicleKind);
      }
    }
  }

  private cancelHold(): void {
    this.holdKey = undefined;
    this.holdPosition = undefined;
    this.holdSince = 0;
    this._holding.set(undefined);
  }

  private cancelKeyHold(): void {
    if (this.keyHoldId === undefined) {
      return;
    }
    this.keyHoldId = undefined;
    this.keyHoldSince = 0;
    this._holding.set(undefined);
  }

  /**
   * Refetches the vehicle's own mode names whenever the bound asset changes, so `1`-`4` always read
   * from fresh capability data rather than a value threaded in once at bind time. A stale in-flight
   * request from a since-abandoned asset is dropped rather than applied ({@link capabilitiesRequestId});
   * a failed or missing read degrades to an empty list, so the digit keys simply do nothing rather
   * than send a fabricated mode name (CLAUDE.md's "degrade honestly").
   */
  private refreshSelectableModes(assetId: string | undefined): void {
    const requestId = ++this.capabilitiesRequestId;
    if (!assetId) {
      this.selectableModes.set([]);
      this.fetchedVehicleKind.set(undefined);
      return;
    }
    this.api
      .flightCapabilities(assetId)
      .then((capability) => {
        if (requestId === this.capabilitiesRequestId) {
          this.selectableModes.set(capability.selectableModes);
          this.fetchedVehicleKind.set(capability.vehicleKind);
        }
      })
      .catch((error) => {
        if (requestId === this.capabilitiesRequestId) {
          this.selectableModes.set([]);
          this.fetchedVehicleKind.set(undefined);
        }
        console.warn('[ControlActionDispatcher] could not load flight capabilities — mode-digit keys stay inert', {
          assetId,
          error,
        });
      });
  }

  private async send(
    assetId: string,
    action: ControlAction,
    parameter: string | null | undefined,
    position: SwitchPosition,
    from: string,
    vehicleKind: VehicleKind | undefined,
  ): Promise<void> {
    if (this.inFlight) {
      return;
    }
    this.inFlight = true;
    const what = actionLabel(action, parameter, vehicleKind);
    try {
      const result = await this.dispatch(assetId, action, parameter, position);
      // NO_ACK is reported as its own outcome, never folded into success: the command genuinely
      // left, and whether the vehicle heard it is the operator's to know (same rule the clicked
      // flight controls follow -- features/fly/flight-command-panel-logic.ts).
      if (result === 'ACCEPTED') {
        this._lastFired.set(`${from} · ${what}`);
        this.toasts.ok(`${what} — ${from}`);
      } else {
        this._lastFired.set(`${from} · ${what} · no acknowledgement`);
        this.toasts.warn(`${what} sent from ${from} — no acknowledgement from the vehicle.`);
      }
    } catch (error) {
      this._lastFired.set(`${from} · ${what} · failed`);
      this.toasts.error(describeHttpError(error));
    } finally {
      this.inFlight = false;
    }
  }

  private dispatch(
    assetId: string,
    action: ControlAction,
    parameter: string | null | undefined,
    position: SwitchPosition,
  ): Promise<FlightCommandResult> {
    return this.command(assetId, action, parameter, position).then((response) => response.result);
  }

  private command(
    assetId: string,
    action: ControlAction,
    parameter: string | null | undefined,
    position: SwitchPosition,
  ): Promise<{ readonly result: FlightCommandResult }> {
    switch (action) {
      case 'ARM':
        return this.api.arm(assetId);
      case 'DISARM':
        return this.api.disarm(assetId);
      case 'TOGGLE_ARM':
        // Against the latest telemetry, not a remembered state: an aircraft that armed from its own
        // transmitter must still disarm from this switch.
        return this.armed() ? this.api.disarm(assetId) : this.api.arm(assetId);
      case 'EMERGENCY_STOP':
        return this.api.emergencyStop(assetId);
      case 'RETURN_TO_HOME':
        return this.api.returnHome(assetId);
      case 'SET_MODE':
        return this.api.setMode(assetId, parameter ?? '');
      case 'AUX_FUNCTION':
        return this.api.auxFunction(assetId, {
          function: Number(parameter ?? 0),
          // The level of the position that actually fired, mapped by the catalogue -- not by this
          // app assuming HIGH is 2.
          level: this.rules().levels.get(position) ?? 0,
        });
    }
  }

}
