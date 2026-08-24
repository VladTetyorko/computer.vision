import { Injectable, computed, signal } from '@angular/core';
import type { ManualControlChannelBinding } from '../api/models';
import { REST_VALUE, axesFrom, springsBack } from './control-surface-logic';

/**
 * `VirtualRcInputService` — the on-screen control surface's half of the RC input seam
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10). Holds one normalized value per
 * bound axis and exposes them in exactly the shape `RcInputService` (the Gamepad API reader)
 * exposes its own, so `ManualControlClient` streams either without knowing which it has.
 *
 * **Provided per host**, alongside `RcInputService` and `ManualControlClient` — the RC drawer's own
 * `providers` array owns all three, so a closed drawer takes the whole surface down with it.
 *
 * <h2>It starts at rest, and that is a safety property</h2>
 * {@link bindTo} seeds every axis to {@link REST_VALUE} before the first frame is ever sent, so a
 * session cannot begin with a throttle anywhere but idle (copter) or stop (rover). That closes
 * `OPERATOR-CONTROL-CONTEXT.md`'s **G6** — "nothing checks throttle position before engaging" — for
 * this input source by construction rather than by a check: with a virtual stick the platform owns
 * the initial position, so there is no stale physical stick to be caught out by.
 */
@Injectable()
export class VirtualRcInputService {
  private readonly _values = signal<ReadonlyMap<number, number>>(new Map());
  private readonly _bindings = signal<readonly ManualControlChannelBinding[]>([]);

  /** The engaged map this surface is currently shaped by; empty until {@link bindTo}. */
  readonly bindings = this._bindings.asReadonly();

  /** The `axes` array a `channels` frame carries — same shape `RcInputService.axes()` produces. */
  readonly axes = computed(() => axesFrom(this._values(), this._bindings()));

  /** No profile binds a button (arm/disarm/mode go through the flight-command panel), so: always empty. */
  readonly buttons = computed<readonly number[]>(() => []);

  /**
   * Shapes this surface to the map the server sent on `engaged`, with every control at rest.
   *
   * @param bindings the engaged channel map
   */
  bindTo(bindings: readonly ManualControlChannelBinding[]): void {
    this._bindings.set(bindings);
    this._values.set(new Map(bindings.filter((b) => b.source === 'AXIS').map((b) => [b.sourceIndex, REST_VALUE])));
  }

  /** Drops every value and unbinds — called when a session ends, so a re-engage starts clean. */
  clear(): void {
    this._bindings.set([]);
    this._values.set(new Map());
  }

  /**
   * The current normalized value of one bound axis.
   *
   * @param sourceIndex the axis index, as the server reported it on the binding
   */
  value(sourceIndex: number): number {
    return this._values().get(sourceIndex) ?? REST_VALUE;
  }

  /**
   * Moves one axis. Values outside the binding's own range are the caller's problem to clamp —
   * `control-surface-logic.ts#valueFromFraction` already does, and the backend clamps again.
   *
   * @param sourceIndex the axis index
   * @param value       the new normalized value
   */
  set(sourceIndex: number, value: number): void {
    const next = new Map(this._values());
    next.set(sourceIndex, value);
    this._values.set(next);
  }

  /**
   * The operator let go of one control: a centred control springs back to rest, a unidirectional
   * throttle holds where it was left (see {@link springsBack} for why the two differ).
   *
   * @param binding the binding that was released
   */
  release(binding: ManualControlChannelBinding): void {
    if (springsBack(binding)) {
      this.set(binding.sourceIndex, REST_VALUE);
    }
  }

  /** Returns every control to rest — the panic path behind the surface's own "Centre all" control. */
  centreAll(): void {
    this.bindTo(this._bindings());
  }
}
