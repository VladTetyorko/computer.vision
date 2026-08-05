import type { DiscoveredDevice, ScanRequest } from '../../core/api/models';

/**
 * Pure logic behind the onboarding wizard's Connect step "Listen for drones" path
 * (docs/DRONE-INFRA-PLAN.md I-b) — a MAVLink-heartbeat-only discovery scan, distinct from the
 * existing protocol-agnostic "Discover on network" method (ONVIF/mDNS/V4L2). Split out per this
 * app's own convention (`onboarding-logic.ts`'s doc comment): Angular-free, unit-tested without
 * HTTP, the router, or a component.
 *
 * `OnboardingStore#scanForDrones`/`#useDroneVehicle` are the only callers — they own the HTTP call
 * and the signals, everything else (what request to send, whether a candidate is already claimed,
 * how to prefill the register form from one) lives here.
 */

/** The `DeviceDiscoveryPort#method()` key `MavlinkHeartbeatScanner` reports (docs/DRONE-INFRA-PLAN.md I-b). */
export const MAVLINK_DISCOVERY_METHOD = 'mavlink';

/**
 * Builds `POST /api/discovery/scan`'s body restricted to the MAVLink heartbeat scanner alone
 * (`ScanRequest#methods`, already supported by the existing discovery contract — no backend/DTO
 * change needed for this path). `timeoutMs` is optional and omitted when not given: the scanner
 * self-time-boxes (docs/DRONE-INFRA-PLAN.md I-b — "scans take ~5-10s"), so the wizard doesn't need
 * its own timeout picker the way the general "Discover on network" method does.
 */
export function buildMavlinkScanRequest(timeoutMs?: number): ScanRequest {
  return {
    methods: [MAVLINK_DISCOVERY_METHOD],
    ...(timeoutMs !== undefined ? { timeoutMs } : {}),
  };
}

/**
 * A vehicle already claimed by an existing device (docs/DRONE-INFRA-PLAN.md I-b's poka-yoke: "a
 * discovered vehicle already claimed by an existing device is labeled as such" — no accidental
 * duplicate assets). `MavlinkHeartbeatScanner` reports this via `details['claimed'] = "true"`
 * (`DiscoveredDevice.details` is a plain string map — no dedicated wire field for it).
 */
export function isClaimedVehicle(candidate: DiscoveredDevice): boolean {
  return candidate.details['claimed'] === 'true';
}

/** The MAVLink system id a candidate was heard on, if the scanner reported one (`details['sysid']`). */
export function vehicleSysid(candidate: DiscoveredDevice): string | undefined {
  return candidate.details['sysid'];
}

/** One detail chip the results list shows for a candidate — see {@link vehicleDetailChips}. */
export interface VehicleDetailChip {
  readonly key: string;
  readonly label: string;
  readonly value: string;
}

/**
 * The results list's own detail chips (docs/DRONE-INFRA-PLAN.md I-b: "details chips (firmware,
 * sysid)") — deliberately a fixed, narrow pair (never more than 2, each independently omitted when
 * its own key is absent) rather than a generic dump of every `details` entry the way the general
 * "Discover on network" table used to (now {@link detailsSummary} below, one plain-text cell —
 * docs/VISUAL-REFRESH-PLAN.md F5 rule 4 caps every row at one chip, and a card here already spends
 * its one chip budget on nothing since this list's whole point is a simpler, drone-specific glance).
 */
export function vehicleDetailChips(candidate: DiscoveredDevice): readonly VehicleDetailChip[] {
  const chips: VehicleDetailChip[] = [];
  const firmware = candidate.details['firmware'];
  if (firmware) {
    chips.push({ key: 'firmware', label: 'Firmware', value: firmware });
  }
  const sysid = vehicleSysid(candidate);
  if (sysid) {
    chips.push({ key: 'sysid', label: 'Sysid', value: sysid });
  }
  return chips;
}

/**
 * A single, comma-joined "key: value" summary of a candidate's raw `details` map — the general
 * "Discover on network" table's own Details column (docs/VISUAL-REFRESH-PLAN.md F5 rule 4: "at most
 * one chip per row… classification is muted text, not a chip"). That column used to render one chip
 * per detail key, unbounded — exactly the pattern F5 bans — collapsed here to plain text the same
 * way `features/devices/devices.html`'s own Capabilities column already merges N capabilities into
 * one muted, comma-joined cell. Returns `''` (never a stray leading/trailing `', '`) when `details`
 * is empty; the template falls back to the usual faint em dash for an empty cell.
 */
export function detailsSummary(details: Record<string, string>): string {
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${value}`)
    .join(', ');
}

/** What picking an unclaimed vehicle fills into the Connect step's `register` sub-form. */
export interface DronePrefill {
  readonly protocol: string;
  readonly uri: string;
  readonly options?: Record<string, string>;
  readonly suggestedCategory?: string;
}

/**
 * Builds the register-form prefill for an unclaimed vehicle (docs/DRONE-INFRA-PLAN.md I-b:
 * "prefills the wizard's connect form from suggestedStream (protocol mavlink, uri, sysid option)
 * and suggestedCategory"). `protocol` falls back to `"mavlink"` (this scan only ever finds MAVLink
 * vehicles) when the candidate somehow carries none — mirrors `useCandidate`'s own
 * `candidate.uri ?? candidate.address` fallback for `uri`. The sysid, when the scanner reported one,
 * rides as the `sysid` stream option (`StreamDescriptor.options['sysid']`,
 * `adapter-mavlink`'s own pin-to-this-vehicle contract — see that module's own docs) — flattening
 * it out of `details` rather than `suggestedStream` directly, since `DiscoveredDeviceResponse` only
 * ever flattens `protocol`/`uri` from the domain's `suggestedStream`, never its `options` map.
 */
export function prefillFromVehicle(candidate: DiscoveredDevice): DronePrefill {
  const sysid = vehicleSysid(candidate);
  return {
    protocol: candidate.protocol ?? MAVLINK_DISCOVERY_METHOD,
    uri: candidate.uri ?? candidate.address,
    ...(sysid ? { options: { sysid } } : {}),
    ...(candidate.suggestedCategory ? { suggestedCategory: candidate.suggestedCategory } : {}),
  };
}
