import type { Device } from './api/models';

/**
 * Pure, Angular-free device-resolution helpers shared across pages.
 *
 * `findVideoDevice` started life in `pages/devices/simulate-logic.ts` (docs/CYCLES-PLAN.md §4 —
 * the "Simulate a source" wizard's Watch action). docs/CYCLES-PLAN.md §6's `/map` tab needs the
 * exact same resolution for its own marker-popup Watch action, and this codebase has no
 * precedent for one page importing another page's module (every cross-page dependency runs
 * through `core/`), so it was lifted here rather than imported across `pages/devices` →
 * `pages/map`. `pages/devices/devices.ts` now imports it from here too; nothing about its
 * behavior changed.
 */

/**
 * The device a freshly-started simulation (or any asset) is watchable through — the first
 * VIDEO-capable device on the asset (a simulated asset always has exactly one, but this makes no
 * such assumption).
 */
export function findVideoDevice(devices: readonly Device[]): Device | undefined {
  return devices.find((device) => device.capabilities.includes('VIDEO'));
}
