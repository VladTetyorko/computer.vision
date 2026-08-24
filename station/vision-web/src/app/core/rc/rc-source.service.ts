import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { RcInputService } from './rc-input.service';
import { VirtualRcInputService } from './virtual-rc-input.service';

/** Where stick values come from: a plugged-in transmitter/gamepad, or the on-screen surface. */
export type RcSourceKind = 'gamepad' | 'virtual';

/**
 * `RcSource` — the one seam `ManualControlClient` reads its stick values through
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10).
 *
 * Manual control used to inject `RcInputService` directly, which made a USB gamepad the *only*
 * possible input: no pad, no control, and the client's own gamepad-disconnect deadman would have
 * released any session that somehow started without one. Everything else about a session — the
 * watchdog, the keepalive cadence, latency, audit, the scope gate — was already input-agnostic, so
 * the right fix was a second *source*, not a second client.
 *
 * **Provided per host**, with `RcInputService`, `VirtualRcInputService` and `ManualControlClient`.
 *
 * <h2>Which source is selected</h2>
 * The on-screen surface is the floor — it is always available — and a connected gamepad is promoted
 * over it automatically, because an operator who plugged a transmitter in meant to use it.
 * {@link use} overrides that choice explicitly.
 *
 * The automatic promotion is deliberately one-way, and never happens mid-session:
 * <ul>
 *   <li><b>It never demotes.</b> A transmitter unplugged while selected leaves this source selected
 *       and {@link live} {@code false}, so `ManualControlClient`'s deadman releases the session.
 *       Quietly falling back to an on-screen surface resting at idle would turn a yanked USB cable
 *       into a silent handover instead of the failsafe the operator is entitled to.</li>
 *   <li><b>It never promotes over a live on-screen session.</b> A gamepad plugged in mid-session
 *       would otherwise take over at whatever position its physical sticks happen to be sitting
 *       at.</li>
 * </ul>
 * Either way the operator's way out is the picker, which says as much.
 */
@Injectable()
export class RcSource {
  private readonly gamepad = inject(RcInputService);
  private readonly virtual = inject(VirtualRcInputService);

  /** Seeded from the gamepad's state at construction, not left to the first effect flush: a host
   * that mounts with a transmitter already plugged in must read as `'gamepad'` immediately, or a
   * session engaged in that same flush would stream the wrong source's axes for a frame. */
  private readonly _kind = signal<RcSourceKind>(this.gamepad.connected() ? 'gamepad' : 'virtual');
  private readonly _pinned = signal(false);

  readonly kind = this._kind.asReadonly();

  /** The selected source's axes — the exact array a `channels` frame carries. */
  readonly axes = computed<readonly number[]>(() =>
    this._kind() === 'gamepad' ? this.gamepad.axes() : this.virtual.axes(),
  );

  readonly buttons = computed<readonly number[]>(() =>
    this._kind() === 'gamepad' ? this.gamepad.buttons() : this.virtual.buttons(),
  );

  /**
   * Whether the selected source can currently produce input at all.
   *
   * `ManualControlClient` releases a live session when this goes `false`, which for the gamepad
   * source is the unplug deadman it always had. The on-screen surface is always live — it cannot be
   * unplugged — so its deadmen are the other four the client already wires: an explicit release, the
   * drawer closing, the tab hiding, and the socket dropping.
   */
  readonly live = computed(() => (this._kind() === 'gamepad' ? this.gamepad.connected() : true));

  constructor() {
    effect(() => {
      // A bound on-screen surface means a session is live on it (`VirtualRcInputService#bindTo` is
      // called on `engaged` and cleared on release) — the one state promotion must not interrupt.
      const engagedOnScreen = this.virtual.bindings().length > 0;
      if (this.gamepad.connected() && !this._pinned() && !engagedOnScreen && this._kind() === 'virtual') {
        this._kind.set('gamepad');
      }
    });
  }

  /**
   * Explicitly select a source, pinning it against the automatic choice above.
   *
   * @param kind the source to use
   */
  use(kind: RcSourceKind): void {
    this._pinned.set(true);
    this._kind.set(kind);
  }
}
