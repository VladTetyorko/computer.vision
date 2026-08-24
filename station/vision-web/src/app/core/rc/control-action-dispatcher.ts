import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { RcSource } from './rc-source.service';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { describeHttpError } from '../api-error';
import type { ControlAction, FlightCommandResult, ControlProfile, SwitchPosition } from '../api/models';
import {
  actionLabel,
  controlKey,
  controlLabel,
  pendingActions,
  positionsFrom,
  type ControlActionRules,
} from './control-action-logic';

/**
 * How long a dangerous action's switch must be held before the command is sent, in milliseconds
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C9).
 *
 * The clicked Arm control earns its safety from a two-stage confirm dialog; a bound switch cannot
 * show one, so it earns its own friction instead. Long enough that a knocked switch does not arm an
 * aircraft, short enough that a deliberate flick-and-hold still feels like a control rather than a
 * form. It is not a tunable: an operator who could shorten it would, and the number exists exactly
 * because the moment it matters is the moment nobody is thinking about settings.
 */
const DANGEROUS_HOLD_MS = 600;

/**
 * `ControlActionDispatcher` — turns a flick of a bound switch into one command
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C2/C3/C9).
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
 *   <li><b>Repeat.</b> Edge-triggered: a held switch fires once.</li>
 *   <li><b>Arm on a knock.</b> Anything the catalogue calls dangerous must stay in position for
 *       {@link DANGEROUS_HOLD_MS} first; leaving early cancels it silently.</li>
 *   <li><b>Overlap.</b> One command is in flight at a time — a switch flicked three times while the
 *       first request is still open sends one command, not three.</li>
 * </ul>
 *
 * **Provided per host**, like the rest of the RC stack: it exists only while the Controller drawer
 * is mounted, so nothing dispatches from a page the operator is not looking at.
 */
@Injectable()
export class ControlActionDispatcher {
  private readonly source = inject(RcSource);
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  private readonly assetId = signal<string | undefined>(undefined);
  private readonly profile = signal<ControlProfile | undefined>(undefined);
  private readonly rules = signal<ControlActionRules>({ dangerous: new Set(), levels: new Map() });
  private readonly armed = signal<boolean | undefined>(undefined);
  private readonly enabled = signal(false);

  /** The last command a bound control actually sent, for the drawer's one-line "what just happened". */
  private readonly _lastFired = signal<string | undefined>(undefined);
  readonly lastFired = this._lastFired.asReadonly();

  /** A dangerous action counting down its hold, so the drawer can say "hold…" rather than nothing. */
  private readonly _holding = signal<string | undefined>(undefined);
  readonly holding = this._holding.asReadonly();

  private positions: ReadonlyMap<string, SwitchPosition> = new Map();
  private holdKey: string | undefined;
  private holdPosition: SwitchPosition | undefined;
  private holdSince = 0;
  private inFlight = false;

  constructor() {
    effect(() => {
      const axes = this.source.axes();
      const buttons = this.source.buttons();
      untracked(() => this.onFrame(axes, buttons));
    });
  }

  /**
   * Points the dispatcher at one asset and one layout.
   *
   * @param assetId the asset a fired command is sent to; `undefined` disables dispatch
   * @param profile the layout whose `actionMap` is watched; `undefined` disables dispatch
   * @param rules the catalogue's own danger flags and switch levels
   * @param enabled whether the operator may command this asset at all — a viewer's switches do
   *   nothing, the same gate the flight controls use
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
    this.assetId.set(assetId);
    this.profile.set(profile);
    this.rules.set(rules);
    this.enabled.set(enabled);
  }

  /** Latest telemetry's armed flag — what `TOGGLE_ARM` resolves against, read at the moment of use. */
  setArmed(armed: boolean | undefined): void {
    this.armed.set(armed);
  }

  private onFrame(axes: readonly number[], buttons: readonly number[]): void {
    const profile = this.profile();
    const assetId = this.assetId();
    if (!profile || !assetId || !this.enabled() || profile.actionMap.length === 0) {
      this.positions = new Map();
      this.cancelHold();
      return;
    }

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
          controlLabel(binding.source, binding.sourceIndex));
      }
    }

    for (const action of fired) {
      if (action.dangerous) {
        this.holdKey = action.key;
        this.holdPosition = action.position;
        this.holdSince = Date.now();
        this._holding.set(`Hold ${action.label} to ${actionLabel(action.action, action.parameter).toLowerCase()}`);
      } else {
        void this.send(assetId, action.action, action.parameter, action.position, action.label);
      }
    }
  }

  private cancelHold(): void {
    this.holdKey = undefined;
    this.holdPosition = undefined;
    this.holdSince = 0;
    this._holding.set(undefined);
  }

  private async send(
    assetId: string,
    action: ControlAction,
    parameter: string | null | undefined,
    position: SwitchPosition,
    from: string,
  ): Promise<void> {
    if (this.inFlight) {
      return;
    }
    this.inFlight = true;
    const what = actionLabel(action, parameter);
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
