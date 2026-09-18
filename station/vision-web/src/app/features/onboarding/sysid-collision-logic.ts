import type { Device, DiscoveryCandidate, ParameterReading, VehicleProfile } from '../../core/api/models';

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

// --- Sysid step copy (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling #2/#3, LF2 web defect #3) --
// `vision.pairing.sysid-range` (server-owned, `station/vision-app`'s config) is the assignable band —
// mirrored here as constants rather than re-fetched, exactly the same "frozen wire constant" posture
// this file already takes with `sysidParameterName`'s firmware-generation assumption. Keep these two
// numbers as the one place the web app names the range; every caller of `describeSysidStep` passes
// them through rather than re-declaring `10`/`250` locally.

/** Mirrors `vision.pairing.sysid-range`'s lower bound (§7 ruling #2). */
export const SYSID_RANGE_MIN = 10;

/** Mirrors `vision.pairing.sysid-range`'s upper bound (§7 ruling #2 — 251-255 are reserved for a GCS/broadcast by MAVLink convention). */
export const SYSID_RANGE_MAX = 250;

/** ArduPilot's (and this platform's rover firmware's) factory-default system id — §7 ruling #2: an assigned sysid must never equal this, or a fresh, unpaired newcomer collides with a paired vehicle on the lobby. */
const FACTORY_DEFAULT_SYSID = 1;

/**
 * The sysid a found-nearby candidate was actually heard broadcasting — read straight off the
 * discovery-probe fields the feed already carries, `suggestedStreamOptions['sysid']` (the richer
 * per-candidate options map) falling back to `details['sysid']` (`drone-scan-logic.ts#vehicleSysid`'s
 * identical convention) for a candidate the server hasn't upgraded to carry the former. `null` for no
 * candidate, or one with no sysid at all (a camera/ONVIF candidate never has one).
 */
export function heardSysidFor(candidate: DiscoveryCandidate | null): number | null {
  if (!candidate) {
    return null;
  }
  const raw = candidate.suggestedStreamOptions?.['sysid'] ?? candidate.details['sysid'];
  if (raw === undefined) {
    return null;
  }
  const sysid = Number(raw);
  return Number.isFinite(sysid) ? sysid : null;
}

export type SysidStepKind = 'factory-default' | 'collision' | 'out-of-range';

export interface SysidStepDescription {
  readonly kind: SysidStepKind;
  readonly title: string;
  readonly message: string;
}

export interface SysidStepInput {
  /** The sysid this vehicle was actually heard broadcasting, if known client-side (a found-nearby candidate's `suggestedStreamOptions?.['sysid']`/`details['sysid']`, or a Prove-step profile's own `sysid`) — `null` when this wizard never observed it directly. */
  readonly heardSysid: number | null;
  /** The number the station assigned (register/attach response's `assignedSysid`, §7 ruling #3), when known — `null` for the legacy connection-form path, which never calls `PairingService.pair` and so never gets one (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling #3's own "adopt is one motion" scope is the found-nearby path only). */
  readonly assignedSysid: number | null;
  readonly sysidRangeMin: number;
  readonly sysidRangeMax: number;
}

/**
 * The sysid step's title/message (docs/plans/active/LINK-PAIRING-PLAN.md §8 defect #3) — replaces the
 * hardcoded "Fix the sysid collision" copy, which fired on every factory-default (sysid=1) first
 * pairing even though §7 ruling #2 makes that the *common* case, not a fleet collision: ArduPilot and
 * this platform's own rover firmware both ship at sysid 1, so the very first time any fresh vehicle
 * is heard it "collides" with nothing — it is simply still wearing its factory tag. Three distinct
 * situations, distinguished by where `heardSysid` sits relative to the assignable range:
 * - `'factory-default'` — `heardSysid === 1`. Not a collision at all; the vehicle just needs its
 *   fleet number written.
 * - `'out-of-range'` — heard outside `[sysidRangeMin, sysidRangeMax]` but not the factory default
 *   (e.g. 0, or 251-255's MAVLink-reserved GCS/broadcast band). Same "write the assigned number"
 *   framing, naming the reservation instead of a fleet collision.
 * - `'collision'` — heard sysid is a plausible fleet id already claimed by another device; the one
 *   genuine collision case, and the only one where the old wording was accurate. Kept close to the
 *   original copy, but now also names the assigned number as the actual fix rather than leaving the
 *   operator to guess one.
 *
 * `heardSysid === null` (nothing observed client-side, e.g. an old backend that never reported
 * `sysidPushRequired`) degrades to the `'collision'` wording without a heard number — honest rather
 * than fabricated, never blocks the step (this step is always advisory, `continueFromSysidStep`
 * proceeds regardless).
 */
export function describeSysidStep(input: SysidStepInput): SysidStepDescription {
  const { heardSysid, assignedSysid, sysidRangeMin, sysidRangeMax } = input;
  const assignedText = assignedSysid !== null ? `${assignedSysid}` : 'a new fleet number';

  if (heardSysid === FACTORY_DEFAULT_SYSID) {
    return {
      kind: 'factory-default',
      title: 'Give this vehicle its fleet number',
      message:
        `This vehicle still answers to system id ${FACTORY_DEFAULT_SYSID} — the factory default every ` +
        `fresh ArduPilot vehicle ships with. The station assigned it ${assignedText} as its fleet number. ` +
        `Write it now; the vehicle applies its new id after its next reboot.`,
    };
  }

  if (heardSysid !== null && (heardSysid < sysidRangeMin || heardSysid > sysidRangeMax)) {
    return {
      kind: 'out-of-range',
      title: 'This vehicle is using a reserved id',
      message:
        `System id ${heardSysid} is outside this fleet's ${sysidRangeMin}-${sysidRangeMax} range — reserved ` +
        `for a ground station or broadcast, not a flyable vehicle. The station assigned it ${assignedText}. ` +
        `Write it now; the vehicle applies its new id after its next reboot.`,
    };
  }

  const heardText = heardSysid !== null ? `${heardSysid}` : 'a system id';
  return {
    kind: 'collision',
    title: 'Fix the sysid collision',
    message:
      `This vehicle answers to system id ${heardText}, which another vehicle in the fleet already claims ` +
      `— until it gets a distinct id, one of the two will be invisible or unreliable on this link. The ` +
      `station assigned it ${assignedText} to fix that; write it now, or continue and fix it later from the ` +
      `asset's readiness page.`,
  };
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
