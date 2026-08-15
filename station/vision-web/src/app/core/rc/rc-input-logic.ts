/**
 * Pure helpers behind the RC transmitter monitor (docs/plans/active/RC-CONTROL-PLAN.md Phase 0). Frame-free and
 * dependency-free so it unit-tests without a browser or `TestBed` — the `RcInputService` (the
 * `navigator.getGamepads()` plumbing) is the only browser-touching part.
 *
 * Phase 0 reads the transmitter through the **Gamepad API**: EdgeTX's "USB Joystick" mode enumerates
 * as a standard HID gamepad, so its sticks/pots arrive as `axes` (each -1..1) and its switches as
 * `buttons` (each 0..1) with zero device-specific report parsing. WebHID (raw report, >8 analog axes,
 * exact PWM) is the Phase-1 fidelity upgrade; these helpers stay source-agnostic.
 */

export interface RcDeviceInfo {
  /** Raw `Gamepad.id` (often `"Name (Vendor: xxxx Product: yyyy)"`). */
  readonly id: string;
  /** `navigator.getGamepads()` slot index. */
  readonly index: number;
  readonly axisCount: number;
  readonly buttonCount: number;
}

export interface RcSnapshot {
  /** One value per axis, each in [-1, 1]. */
  readonly axes: readonly number[];
  /** One value per button, each in [0, 1]. */
  readonly buttons: readonly number[];
  /** Sample time in ms (monotonic, e.g. `performance.now()`). */
  readonly timestamp: number;
}

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

/** An axis value (-1..1) as a signed whole-percent (-100..100), for the mono readout. */
export function axisToPercent(v: number): number {
  return Math.round(clamp(v, -1, 1) * 100);
}

/** A button value (0..1) as a whole-percent (0..100). */
export function buttonToPercent(v: number): number {
  return Math.round(clamp(v, 0, 1) * 100);
}

/** Whether a switch/button reads as engaged (default midpoint threshold). */
export function isButtonOn(v: number, threshold = 0.5): boolean {
  return v >= threshold;
}

/**
 * Center-origin bar geometry for an axis in [-1, 1]: the fill grows from the 50% centre line toward
 * the value's side. Returns the left edge as a percent; pair with {@link barWidthPercent}.
 */
export function barLeftPercent(v: number): number {
  const p = ((clamp(v, -1, 1) + 1) / 2) * 100;
  return v >= 0 ? 50 : p;
}

/** Width of the center-origin fill for an axis in [-1, 1], as a percent (0..50). */
export function barWidthPercent(v: number): number {
  const p = ((clamp(v, -1, 1) + 1) / 2) * 100;
  return Math.abs(p - 50);
}

export function defaultAxisLabel(i: number): string {
  return `Axis ${i + 1}`;
}

export function defaultButtonLabel(i: number): string {
  return `Sw ${i + 1}`;
}

/** A human-readable device name from `Gamepad.id`, trimming the trailing "(Vendor: … Product: …)". */
export function deviceLabel(info: RcDeviceInfo | null): string {
  if (!info) return '';
  const name = info.id.replace(/\s*\((?:STANDARD GAMEPAD\s*)?Vendor:.*$/i, '').trim();
  return name || info.id || 'Controller';
}

/**
 * Update rate in whole Hz from a window of recent frame timestamps (ms). Returns 0 for fewer than
 * two samples or a non-positive span — so a just-connected or stalled controller reads 0, not NaN.
 */
export function computeUpdateRateHz(timestampsMs: readonly number[]): number {
  if (timestampsMs.length < 2) return 0;
  const span = timestampsMs[timestampsMs.length - 1] - timestampsMs[0];
  if (span <= 0) return 0;
  return Math.round(((timestampsMs.length - 1) / span) * 1000);
}
