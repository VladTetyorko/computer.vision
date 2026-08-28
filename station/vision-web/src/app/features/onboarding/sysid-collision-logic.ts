import type { Device, ParameterReading, VehicleProfile } from '../../core/api/models';

/**
 * Pure logic behind the onboarding wizard's sysid-collision step (docs/plans/active/FLEET-RADIO-PLAN.md
 * R5/F0 — ArduPilot 4.7 renamed the vehicle-identity parameter `SYSID_THISMAV` to `MAV_SYSID`, and
 * every FC ships with `sysid=1` by default, so a second vehicle on the same link/port is
 * invisible/colliding until an operator gives it a distinct sysid). Split out per this app's own
 * convention (`onboarding-logic.ts`'s doc comment): Angular-free, unit-tested without HTTP, the
 * router, or a component. `OnboardingStore#verify`/`#writeSysid` are the only callers.
 */

/**
 * The sysid an about-to-be-registered vehicle collides with, if any. `profile.sysid` (from the
 * Verify step's pre-registration probe, `VehicleProfile#sysid`) is compared against every
 * already-registered device's own `options['sysid']` (the MAVLink adapter's pin-to-this-vehicle
 * option, `drone-scan-logic.ts#vehicleSysid`'s server-side counterpart). Returns the colliding
 * sysid, or `null` when there is nothing to collide with (no sysid observed yet, or no existing
 * device claims it) — a fresh candidate always compares against the *entire* existing fleet, since
 * the asset being onboarded does not exist yet at Verify time
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md D7: the pre-registration probe is never persisted).
 */
export function detectSysidCollision(
  profile: VehicleProfile | null,
  existingDevices: readonly Device[],
): number | null {
  if (!profile || profile.sysid === null) {
    return null;
  }
  const sysid = profile.sysid;
  const collides = existingDevices.some((device) => {
    const raw = device.options['sysid'];
    return raw !== undefined && Number(raw) === sysid;
  });
  return collides ? sysid : null;
}

/**
 * Which spelling to write (F0: ArduPilot 4.7 renamed `SYSID_THISMAV` to `MAV_SYSID`, and MAVLink has
 * no "no such parameter" reply, so writing the wrong one is a silent timeout, not a fast failure).
 * Unlike `AssetParameterController#resolveSpelling` (server-side, station/vision-api), there is no
 * asset yet to consult a persisted profile for — this wizard's own pre-registration `VehicleProfile`
 * (from the Verify step) is the only observation available, so it is read directly: if the probe's
 * own `parameters` list already contains a reading for `MAV_SYSID`, the vehicle answered under the
 * modern spelling and that is what gets written; else if it contains `SYSID_THISMAV`, that legacy
 * spelling is used.
 *
 * The fallback — neither spelling read back, e.g. a short or interrupted probe — is **reachable**,
 * because `detectSysidCollision` above works off `profile.sysid` (the heartbeat's own source system
 * id) and never needs the parameter at all. It resolves to `MAV_SYSID`: R0 made the platform's
 * default probe list modern-spelling-only and gave it a two-phase legacy fallback, so an *observed*
 * `SYSID_THISMAV` is positive evidence of pre-4.7 firmware, while observing neither is no evidence
 * at all — and the firmware generation this platform targets (its own SITL image is pinned to
 * `stable-4.7.0`) answers the modern name. Still a guess, not an observation: writing the wrong
 * spelling is a silent timeout rather than an error, so a probe that reliably reads this parameter
 * back is the real fix.
 */
export function sysidParameterName(profile: VehicleProfile): 'MAV_SYSID' | 'SYSID_THISMAV' {
  const names = new Set(profile.parameters.map((reading: ParameterReading) => reading.name));
  if (names.has('MAV_SYSID')) {
    return 'MAV_SYSID';
  }
  if (names.has('SYSID_THISMAV')) {
    return 'SYSID_THISMAV';
  }
  return 'MAV_SYSID';
}
