import type { DiscoveryCandidate, DiscoveryStatusResponse } from '../api/models';
import type { FitOutRowDraft } from './fit-out-logic';

/**
 * The `source`/`prove` steps' one live vocabulary (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * §3.3) — turns `GET /api/discovery/status` (C2) plus the discovery inbox's own candidates into
 * what a fit-out row's waiting room actually shows, for a `find` row whose link hasn't been proven
 * yet. Every branch names a fact this app actually observed (CLAUDE.md degrade-honestly): there is
 * no bare spinner here, and no state is ever inferred from absence — "nothing yet" is `listening`,
 * not `failed` (S1/S3 make honest failure detection impossible at the socket for the two commonest
 * cases, MavlinkTelemetrySource/MjpegVideoSource's own silent-open behavior).
 *
 * **`proven` is never produced by {@link intakeState} itself** — deliberate narrowing from the
 * plan's own sketch. Whether a row's own Prove-step probe actually succeeded is a fact this
 * function has no access to (it only ever sees the *passive* signals: what has arrived at the
 * socket/push path, and the discovery inbox's own candidates) — `OnboardingStore` already tracks
 * that separately (`probeOkByRole`, unchanged by this wave) and is the one place that ever renders
 * the `proven` branch, layering it over whatever this function returns once its own probe result
 * says so. Keeping `intakeState` pure over exactly `{row, status, candidates, nowMs}` means it
 * never has to be told about a probe request/response it did not itself make.
 */
export type IntakeState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'listening'; readonly where: string; readonly seenNothing: boolean; readonly hint?: string }
  | { readonly kind: 'heard'; readonly what: string; readonly ageMs: number }
  | { readonly kind: 'proven'; readonly what: string }
  | { readonly kind: 'failed'; readonly reason: string };

/** The two discovery methods a fit-out row's live intake ever reads — exported (W3) so a caller
 *  picking a role's own freshest candidate (via {@link freshestNewCandidate}) uses the same method
 *  string this file's own `senseIntake`/`sightIntake` do. */
export const MAVLINK_METHOD = 'mavlink';
export const MEDIAMTX_METHOD = 'mediamtx';

/** The freshest `NEW` candidate for one discovery method, or `undefined` — mirrors
 *  `discovery-inbox-logic.ts#visibleCandidates`'s own `lastSeen`-descending sort, narrowed to one
 *  method and one status since a row only ever cares about "the newest thing this method reported
 *  that nobody has acted on yet". Exported (W3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1)
 *  so the `source` step's own live "Use" action can act on exactly the candidate {@link intakeState}'s
 *  `heard` branch is describing, rather than re-deriving a possibly-different "freshest" pick. */
export function freshestNewCandidate(
  candidates: readonly DiscoveryCandidate[],
  method: string,
): DiscoveryCandidate | undefined {
  return candidates
    .filter((candidate) => candidate.status === 'NEW' && candidate.method === method)
    .reduce<DiscoveryCandidate | undefined>((best, candidate) => {
      if (!best || Date.parse(candidate.lastSeen) > Date.parse(best.lastSeen)) {
        return candidate;
      }
      return best;
    }, undefined);
}

/** Sense (telemetry): the standing MAVLink lobby's own facts. */
function senseIntake(
  status: DiscoveryStatusResponse,
  candidates: readonly DiscoveryCandidate[],
  nowMs: number,
): IntakeState {
  const telemetry = status.telemetryIntake;
  if (!telemetry.bound) {
    return { kind: 'failed', reason: 'Not listening on the MAVLink port — the lobby is not bound.' };
  }
  const where = `Listening on ${telemetry.bindAddress}`;

  // The discovery inbox's own sweep (≤30s late) has already turned this into a named candidate —
  // prefer it over the raw sysid counter below, since it carries a name/vehicle guess.
  const candidate = freshestNewCandidate(candidates, MAVLINK_METHOD);
  if (candidate) {
    return {
      kind: 'heard',
      what: candidate.name,
      ageMs: Math.max(0, nowMs - Date.parse(candidate.lastSeen)),
    };
  }

  // A heartbeat the sweep hasn't caught up to yet — still a real, named fact (S1's "silent open"
  // means this counter is the only proof available in that narrow window).
  if (telemetry.unclaimedSysids.length > 0) {
    const ageMs = telemetry.lastDatagramAt ? Math.max(0, nowMs - Date.parse(telemetry.lastDatagramAt)) : 0;
    return { kind: 'heard', what: `Heartbeat from sysid ${telemetry.unclaimedSysids[0]}`, ageMs };
  }

  // P2's own diagnostic — bytes arriving, nothing decoding, is a wrong-protocol/garbage answer no
  // other signal in this app can express.
  if (telemetry.datagramsReceived > 0 && telemetry.framesDecoded === 0) {
    return {
      kind: 'listening',
      where,
      seenNothing: false,
      hint:
        `${telemetry.datagramsReceived} datagrams arrived on ${telemetry.bindAddress} but none decoded ` +
        'as MAVLink 2 — check the protocol version and the port.',
    };
  }

  return { kind: 'listening', where, seenNothing: telemetry.datagramsReceived === 0 };
}

/** Sight (video): mediamtx's own push-path facts (C3/U6) — absent entirely when publish is
 *  unconfigured on this station, the one genuine `failed` case this function ever returns for
 *  Sight (a config fact, not a transient one — there is nothing to wait out). */
function sightIntake(
  status: DiscoveryStatusResponse,
  candidates: readonly DiscoveryCandidate[],
  nowMs: number,
): IntakeState {
  const video = status.videoIntake;
  if (!video) {
    return { kind: 'failed', reason: 'No video push address is configured on this station.' };
  }
  const where = `Watching ${video.pathPrefix} for a pushed stream`;

  const candidate = freshestNewCandidate(candidates, MEDIAMTX_METHOD);
  if (candidate) {
    return {
      kind: 'heard',
      what: candidate.name,
      ageMs: Math.max(0, nowMs - Date.parse(candidate.lastSeen)),
    };
  }

  // A path went live moments ago and the sweep hasn't caught up yet — same "still a real fact"
  // reasoning as `senseIntake`'s own unclaimed-sysid branch. No arrival timestamp exists for a
  // ready path (mediamtx reports readiness, not first-frame time), so `ageMs: 0` — "just now" is
  // never wrong by more than one sweep interval.
  if (video.readyPaths.length > 0) {
    return { kind: 'heard', what: `A stream is publishing on ${video.readyPaths[0]}`, ageMs: 0 };
  }

  return { kind: 'listening', where, seenNothing: true };
}

/**
 * One fit-out row's live intake state — `idle` for a `simulate`/`none` row (nothing to prove), the
 * role-appropriate live read for a `find` row. `nowMs` is threaded in by the caller, never
 * `Date.now()` here (mirrors `discovery-inbox-logic.ts#candidateAgeLabel`'s identical reasoning) —
 * this stays a pure, clock-free function.
 */
export function intakeState(
  row: FitOutRowDraft,
  status: DiscoveryStatusResponse,
  candidates: readonly DiscoveryCandidate[],
  nowMs: number,
): IntakeState {
  if (row.value !== 'find') {
    return { kind: 'idle' };
  }
  return row.role === 'sense' ? senseIntake(status, candidates, nowMs) : sightIntake(status, candidates, nowMs);
}
