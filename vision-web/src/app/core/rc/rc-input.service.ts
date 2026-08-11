import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { RcDeviceInfo, computeUpdateRateHz, deviceLabel } from './rc-input-logic';

/** How many recent frame timestamps feed the update-rate estimate (~0.5s at 60Hz). */
const RATE_WINDOW = 30;

/**
 * `RcInputService` — the browser-facing half of the RC monitor (docs/plans/active/RC-CONTROL-PLAN.md Phase 0):
 * reads a plugged-in transmitter via the **Gamepad API** and exposes its live state as signals. No
 * backend, no MAVLink, no drone — Phase 0 is read-only.
 *
 * **Provided per host** (like `TelemetryStore`), not root — the Fly cockpit's RC drawer provides one
 * instance and drives its lifecycle: `start()` on mount wires the `gamepadconnected`/`disconnected`
 * events and picks up an already-present pad; the component's own destroy tears everything down
 * (also guarded here via `DestroyRef`, so a forgotten `stop()` can't leak the rAF loop or listeners).
 *
 * The app is zoneless, so the rAF loop writing signals is exactly what drives change detection.
 * rAF pauses when the tab is hidden — fine for a monitor (`updateRateHz` reads 0), and a property the
 * Phase-1 relay watchdog will lean on rather than fight.
 */
@Injectable()
export class RcInputService {
  private readonly _connected = signal(false);
  private readonly _device = signal<RcDeviceInfo | null>(null);
  private readonly _axes = signal<readonly number[]>([]);
  private readonly _buttons = signal<readonly number[]>([]);
  private readonly _rateHz = signal(0);

  readonly connected = this._connected.asReadonly();
  readonly device = this._device.asReadonly();
  readonly axes = this._axes.asReadonly();
  readonly buttons = this._buttons.asReadonly();
  readonly updateRateHz = this._rateHz.asReadonly();
  readonly deviceLabel = computed(() => deviceLabel(this._device()));

  private rafId: number | null = null;
  private activeIndex: number | null = null;
  private frameTimes: number[] = [];
  private started = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Whether this browser exposes the Gamepad API at all. */
  supported(): boolean {
    return typeof navigator !== 'undefined' && typeof navigator.getGamepads === 'function';
  }

  /** Begin listening for a controller. Idempotent; a no-op where the Gamepad API is absent. */
  start(): void {
    if (this.started || !this.supported()) return;
    this.started = true;
    window.addEventListener('gamepadconnected', this.onConnect);
    window.addEventListener('gamepaddisconnected', this.onDisconnect);
    // A pad already present before we subscribed (browsers only surface it after the first input,
    // but a re-opened drawer may catch one mid-session).
    const existing = (navigator.getGamepads?.() ?? []).find((p): p is Gamepad => p != null);
    if (existing) this.attach(existing);
  }

  /** Stop listening and release the controller. Idempotent. */
  stop(): void {
    if (!this.started) return;
    this.started = false;
    window.removeEventListener('gamepadconnected', this.onConnect);
    window.removeEventListener('gamepaddisconnected', this.onDisconnect);
    this.detach();
  }

  private readonly onConnect = (e: GamepadEvent): void => this.attach(e.gamepad);

  private readonly onDisconnect = (e: GamepadEvent): void => {
    if (e.gamepad.index === this.activeIndex) this.detach();
  };

  private attach(pad: Gamepad): void {
    this.activeIndex = pad.index;
    this._device.set({
      id: pad.id,
      index: pad.index,
      axisCount: pad.axes.length,
      buttonCount: pad.buttons.length,
    });
    this._connected.set(true);
    this.frameTimes = [];
    if (this.rafId == null) this.rafId = requestAnimationFrame(this.loop);
  }

  private detach(): void {
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
    this.activeIndex = null;
    this.frameTimes = [];
    this._connected.set(false);
    this._device.set(null);
    this._axes.set([]);
    this._buttons.set([]);
    this._rateHz.set(0);
  }

  private readonly loop = (): void => {
    this.rafId = null;
    if (this.activeIndex == null) return;
    const pad = (navigator.getGamepads?.() ?? [])[this.activeIndex];
    if (!pad) {
      this.detach();
      return;
    }
    this._axes.set(Array.from(pad.axes));
    this._buttons.set(pad.buttons.map((b) => b.value));

    this.frameTimes.push(performance.now());
    if (this.frameTimes.length > RATE_WINDOW) this.frameTimes.shift();
    this._rateHz.set(computeUpdateRateHz(this.frameTimes));

    this.rafId = requestAnimationFrame(this.loop);
  };
}
