import type {
  ManualControlChannelBinding,
  ManualControlChannelsMessage,
  ManualControlEngageMessage,
  ManualControlReleaseMessage,
  ManualControlServerMessage,
} from '../api/models';

/**
 * Pure helpers behind `manual-control-client.ts` (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4, R5) — client→
 * server frame builders, defensive server→client frame parsing, and the latency/send-rate math.
 * Frame-free and dependency-free so it unit-tests without a browser/`TestBed`/a real `WebSocket` —
 * mirrors `core/rc/rc-input-logic.ts`'s own split (the one browser-touching part, the `WebSocket`
 * itself, stays in the client class).
 */

const MIN_SEND_HZ = 10;
const MAX_SEND_HZ = 50;

/** Rolling-average window for the ack-derived latency readout (`pushLatencySample`'s own default
 * `max`) — mirrors `rc-input.service.ts#RATE_WINDOW`'s reasoning (a short recent-sample window
 * beats one noisy reading), sized for the plan's ~20-30Hz ack cadence (~0.3-0.5s of smoothing). */
export const DEFAULT_LATENCY_WINDOW = 10;

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

// --- Client → server frame builders --------------------------------------------------------------

export function buildEngageFrame(assetId: string): ManualControlEngageMessage {
  return { type: 'engage', assetId };
}

export function buildChannelsFrame(
  axes: readonly number[],
  buttons: readonly number[],
  seq: number,
  tSent: number,
): ManualControlChannelsMessage {
  return { type: 'channels', axes: [...axes], buttons: [...buttons], seq, tSent };
}

export function buildReleaseFrame(): ManualControlReleaseMessage {
  return { type: 'release' };
}

// --- Server → client frame parsing (defensive — mirrors `geofence-logic.ts#parseGeofenceBreach`) -

function isChannelBinding(value: unknown): value is ManualControlChannelBinding {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const b = value as Record<string, unknown>;
  return (
    (b['source'] === 'AXIS' || b['source'] === 'BUTTON') &&
    typeof b['sourceIndex'] === 'number' &&
    typeof b['rcChannel'] === 'number' &&
    typeof b['label'] === 'string'
  );
}

function isEngaged(msg: Record<string, unknown>): msg is Record<string, unknown> & { type: 'engaged' } {
  return (
    typeof msg['assetId'] === 'string' &&
    typeof msg['rateHz'] === 'number' &&
    Array.isArray(msg['channelMap']) &&
    (msg['channelMap'] as unknown[]).every(isChannelBinding)
  );
}

/** `code` is intentionally NOT validated against the four frozen refusal codes here — the backend
 * (`ManualControlWebSocketHandler`, vision-api) also sends `denied` with a handler-defensive code
 * (`MALFORMED`/`UNKNOWN_TYPE`/`BAD_REQUEST`) for a frame it couldn't process at all, which is not
 * part of the closed engage-refusal set but must still surface `reason` to the operator, not be
 * silently dropped. */
function isDenied(msg: Record<string, unknown>): boolean {
  return typeof msg['code'] === 'string' && typeof msg['reason'] === 'string';
}

function isAck(msg: Record<string, unknown>): boolean {
  return typeof msg['seq'] === 'number' && typeof msg['tSent'] === 'number' && typeof msg['tServer'] === 'number';
}

const RELEASED_REASONS = new Set(['EXPLICIT', 'SOCKET_CLOSE']);

function isReleased(msg: Record<string, unknown>): boolean {
  return typeof msg['reason'] === 'string' && RELEASED_REASONS.has(msg['reason']);
}

function isWatchdog(msg: Record<string, unknown>): boolean {
  return typeof msg['timeoutMs'] === 'number';
}

/**
 * Defensive decode of one server→client text frame — any malformed/missing field → `undefined`,
 * never a thrown error, the identical "no crash on a bad frame" rule `geofence-logic.ts
 * #parseGeofenceBreach`'s own doc comment states. The backend (R4) is built in parallel against
 * this same frozen §4 contract, so a mismatch here degrades to "frame ignored", not a torn-down
 * relay session over one bad message.
 */
export function parseManualControlServerMessage(raw: string): ManualControlServerMessage | undefined {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (typeof data !== 'object' || data === null) {
    return undefined;
  }
  const msg = data as Record<string, unknown>;
  switch (msg['type']) {
    case 'engaged':
      return isEngaged(msg) ? (msg as unknown as ManualControlServerMessage) : undefined;
    case 'denied':
      return isDenied(msg) ? (msg as unknown as ManualControlServerMessage) : undefined;
    case 'ack':
      return isAck(msg) ? (msg as unknown as ManualControlServerMessage) : undefined;
    case 'released':
      return isReleased(msg) ? (msg as unknown as ManualControlServerMessage) : undefined;
    case 'watchdog':
      return isWatchdog(msg) ? (msg as unknown as ManualControlServerMessage) : undefined;
    default:
      return undefined;
  }
}

// --- Latency (glass-to-stick RTT, docs/plans/done/RC-CONTROL-PHASE1-PLAN.md's own "the watchdog is the
// feature, latency is a safety property" framing) --------------------------------------------------

/** RTT for one `ack`, clamped at 0 (defensive against clock skew — `tSent` is this client's own
 * earlier `Date.now()` reading, so `nowMs` should never be smaller in practice). */
export function computeLatencyMs(tSent: number, nowMs: number): number {
  return Math.max(0, nowMs - tSent);
}

/** Appends `sample` to `window`, trimmed to the most recent `max` — pure (returns a new array,
 * never mutates `window`), mirroring this codebase's "no mutation" convention for list-shaped pure
 * helpers (e.g. `org-logic.ts#buildGroupTree`'s own doc comment). */
export function pushLatencySample(
  window: readonly number[],
  sample: number,
  max = DEFAULT_LATENCY_WINDOW,
): readonly number[] {
  const next = [...window, sample];
  return next.length > max ? next.slice(next.length - max) : next;
}

/** The rolling readout `ManualControlClient#latencyMs` exposes — `undefined` for an empty window
 * (no `ack` yet), so the UI can render "—" rather than a fabricated `0`. */
export function rollingAverageMs(window: readonly number[]): number | undefined {
  if (window.length === 0) {
    return undefined;
  }
  const sum = window.reduce((acc, v) => acc + v, 0);
  return sum / window.length;
}

// --- Send cadence ---------------------------------------------------------------------------------

/** Keepalive interval in ms for a server-confirmed `rateHz` — how long an *unchanged* stick may go
 * without sending, clamped to [{@link MIN_SEND_HZ}, {@link MAX_SEND_HZ}] (mirroring the adapter's own
 * `vision.rc.override-hz` clamp, 10..50) so a malformed/extreme `rateHz` from a mismatched backend
 * can never spin the browser's own timer unreasonably fast/slow. Well under the server's 300ms
 * input-loss watchdog by design — a motionless stick is not input loss. */
export function keepaliveIntervalMs(rateHz: number): number {
  return Math.round(1000 / clamp(rateHz, MIN_SEND_HZ, MAX_SEND_HZ));
}

/** Smallest gap between two `channels` frames, mirroring the adapter's own wire ceiling
 * (`vision.rc.max-override-hz`). A change that arrives inside this gap is not dropped — the next
 * sampling tick re-offers it, so the cost is at most one animation frame. */
export const MIN_SEND_GAP_MS = Math.round(1000 / MAX_SEND_HZ);

/** How often the backstop timer re-evaluates {@link shouldSendChannels} — the ceiling gap, so a
 * parked stick's keepalive still lands within one gap of its deadline even with no input at all. */
export const SEND_CHECK_INTERVAL_MS = MIN_SEND_GAP_MS;

export function channelsEqual(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

/**
 * The one send rule, shared by the input-driven path and the backstop timer
 * (docs/plans/active/RC-LATENCY-PLAN.md §2 B). A stick that moved reaches the socket in the frame it
 * was sampled, subject only to the wire ceiling; a stick that did not still reports at the keepalive
 * floor so the server's watchdog keeps seeing input.
 *
 * Because both callers share this rule and both update `msSinceLastSend`, the combined send rate is
 * bounded by the ceiling rather than being the sum of the two paths.
 */
export function shouldSendChannels(changed: boolean, msSinceLastSend: number, keepaliveMs: number): boolean {
  if (msSinceLastSend >= keepaliveMs) {
    return true; // floor: the watchdog must keep seeing frames from a motionless stick
  }
  return changed && msSinceLastSend >= MIN_SEND_GAP_MS; // ceiling: never faster than the wire allows
}
