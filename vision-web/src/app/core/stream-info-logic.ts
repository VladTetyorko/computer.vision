import type { Device } from './api/models';

/**
 * Pure derivations behind `ui/stream-info-panel.ts` (docs/MVP2-PLAN.md §U-info, folded into
 * docs/CYCLES-PLAN.md §11/CD-b): turning plumbing (`protocol`/`uri`/`options`, a raw `startedAt`
 * timestamp) into what a field user actually reads at a glance — a human source description and a
 * live session duration — rather than leading with identifiers. Split out so it is unit-testable
 * without HTTP, timers, or a component — mirrors `core/telemetry-logic.ts`.
 */

export interface SourceDescription {
  readonly protocolLabel: string;
  readonly description: string;
}

const PROTOCOL_LABELS: Readonly<Record<string, string>> = {
  rtsp: 'RTSP',
  mjpeg: 'MJPEG',
  file: 'File',
  sim: 'Simulated',
};

/**
 * `host[:port]` from a URI, or `undefined` if it doesn't parse as one — used instead of the raw
 * URI so an embedded `user:pass@` never appears in the primary, always-visible description (the
 * raw `uri`, credentials included, is still reachable in the "Technical details" disclosure —
 * demoted, not deleted).
 */
function hostOf(uri: string): string | undefined {
  try {
    const url = new URL(uri);
    return url.port ? `${url.hostname}:${url.port}` : url.hostname;
  } catch {
    return undefined;
  }
}

/** The last path segment of a file URI/path — `/srv/videos/flight.mp4` → `flight.mp4`. */
function fileName(uri: string): string {
  const parts = uri.split(/[/\\]/);
  return parts[parts.length - 1] || uri;
}

/**
 * A protocol chip label + one human-readable line describing *what* the source is, e.g.
 * `{ protocolLabel: 'MJPEG', description: '192.168.0.107:8080' }` or
 * `{ protocolLabel: 'File', description: 'flight.mp4 (looped)' }` — never a bare URI.
 *
 * `file`'s `loop` option (`adapter-rtsp/FfmpegVideoSource#OPTION_LOOP`) is surfaced as "(looped)"
 * since a looping demo file behaves differently from a one-shot recording and a viewer should know
 * which they're watching. An unrecognized protocol falls back to its own uppercased name and,
 * where the URI parses as one, its host — never invented video facts (docs/MVP2-PLAN.md §U-info:
 * "implements what current APIs already serve").
 */
export function describeSource(device: Pick<Device, 'protocol' | 'uri' | 'options'>): SourceDescription {
  const protocolLabel = PROTOCOL_LABELS[device.protocol] ?? device.protocol.toUpperCase();
  switch (device.protocol) {
    case 'file': {
      const looped = device.options?.['loop'] === 'true';
      return { protocolLabel, description: `${fileName(device.uri)}${looped ? ' (looped)' : ''}` };
    }
    case 'sim':
      return { protocolLabel, description: 'Pattern generator, no camera' };
    default:
      return { protocolLabel, description: hostOf(device.uri) ?? device.uri };
  }
}

/** Seconds elapsed since `startedAt` — the raw value `formatDuration` renders. */
export function sessionDurationSeconds(startedAt: string, nowMs: number): number {
  return Math.max(0, (nowMs - Date.parse(startedAt)) / 1000);
}

/**
 * A live-ticking session duration, e.g. `12s`, `4m 07s`, `1h 03m` — the "Started" timestamp's
 * user-meaningful replacement (docs/MVP2-PLAN.md §U-info: "Started becomes a live-ticking
 * duration"). The raw `startedAt` timestamp itself stays available in "Technical details".
 */
export function formatDuration(totalSeconds: number): string {
  const total = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  }
  return `${seconds}s`;
}

/**
 * The info panel's latency line — reuses `ui/player.ts`'s own measured `behindLive` (surfaced via
 * its `latencyChanged` output) rather than re-measuring, so the number never disagrees with the
 * badge already drawn on the video (docs/MVP2-PLAN.md §U-info: "viewer latency estimate from the
 * player").
 */
export function formatLatency(behindLiveSeconds: number | null): string {
  return behindLiveSeconds === null ? 'measuring…' : `~${behindLiveSeconds.toFixed(1)}s behind live`;
}

/**
 * The info panel's "Transport" fact (docs/MVP2-PLAN.md §L / §U3) — names whichever transport
 * `ui/player.ts` actually attached (`transportChanged`), replacing the old hardcoded "HLS" now
 * that WHEP-first playback exists. Kept a one-line pure function, mirroring `formatLatency`,
 * rather than a template ternary — this app's own convention for anything worth a unit test.
 */
export function transportLabel(transport: 'webrtc' | 'hls'): string {
  return transport === 'webrtc' ? 'WebRTC (WHEP)' : 'HLS';
}
