/**
 * Protocols this build of Vision can actually register a device against (docs/UX-QUICKWINS-PLAN.md
 * QF-2 — the Register-manually form's protocol field, previously free text, is now a `<select>` of
 * exactly this list). Moved here from `features/devices/` when the onboarding wizard
 * (docs/UX-REWORK-PLAN.md §U-d) absorbed the Register/Discover/Simulate connect methods wholesale —
 * the Warehouse page never showed this select at all, it only ever rendered `Device.protocol`
 * verbatim as a chip. Each entry is a **consuming** (RX) protocol — one `VideoSourcePort`/
 * `TelemetrySourcePort#supports()` implementation actually accepts it — verified against each
 * adapter's own source, not assumed:
 *
 * - `rtsp` — `adapters/adapter-rtsp/.../FfmpegVideoSource#supports` (`PROTOCOL_RTSP = "rtsp"`)
 * - `file` — the same `FfmpegVideoSource#supports` (`PROTOCOL_FILE = "file"`, `uri` scheme must be `file:`)
 * - `mjpeg` — `adapters/adapter-mjpeg/.../MjpegVideoSource#supports` (`PROTOCOL = "mjpeg"`, `uri` scheme `http`/`https`)
 * - `v4l2` — `adapters/adapter-v4l2/.../V4l2VideoSource#supports` (`PROTOCOL_V4L2 = "v4l2"`, `uri` scheme must be `file:`, a device path)
 * - `sim` — `adapters/adapter-simulation/.../SimulatedVideoSource#supports` (`PROTOCOL = "sim"`) and
 *   `SimulatedTelemetrySource#supports` (same `"sim"`, requires the `TELEMETRY` capability too)
 * - `mavlink` — `adapters/adapter-mavlink/.../MavlinkTelemetrySource#supports` (`PROTOCOL = "mavlink"`,
 *   `uri` scheme must be `udp:` with a positive port — this app binds/listens, never dials out)
 *
 * Deliberately **excludes** each adapter's TX-only half (`RtspFeedTransmitter`/`MjpegFeedTransmitter`/
 * `MavlinkFeedTransmitter`'s own `supports(FeedSpec)`) — those back the Simulate wizard's
 * `rtsp`/`mjpeg`/mavlink-telemetry *transmission* path (rehearsing the protocol by emitting a feed),
 * never a device this page can register to consume.
 *
 * **Must be kept in sync with the adapter set** (each `adapters/adapter-*` module's own `MODULE.md`)
 * — a future ingest adapter needs its protocol string added here too, or its devices are only
 * reachable through the `Custom…` escape hatch below.
 */
export interface ProtocolOption {
  /** The exact lower-case protocol string a `StreamDescriptor`/`Device` carries. */
  readonly value: string;
  /** One line: what this protocol is, in plain words — no jargon-only labels. */
  readonly hint: string;
  /** A realistic example URI for this protocol, shown as the URI field's placeholder. */
  readonly placeholder: string;
}

export const REGISTERABLE_PROTOCOLS: readonly ProtocolOption[] = [
  {
    value: 'rtsp',
    hint: 'IP camera / network video stream',
    placeholder: 'rtsp://192.168.1.50:554/stream',
  },
  {
    value: 'file',
    hint: 'A video file already on the server, played on a loop',
    placeholder: 'file:///srv/videos/flight.mp4',
  },
  {
    value: 'mjpeg',
    hint: 'HTTP MJPEG camera or stream endpoint',
    placeholder: 'http://192.168.1.60:8080/video',
  },
  {
    value: 'v4l2',
    hint: 'USB/local camera attached to this machine (Linux V4L2)',
    placeholder: 'file:///dev/video0',
  },
  {
    value: 'sim',
    hint: 'Built-in synthetic test source — no hardware needed',
    placeholder: 'sim://demo',
  },
  {
    value: 'mavlink',
    hint: 'MAVLink 2 telemetry over UDP (flight controller / SITL) — this app listens, it does not dial out',
    placeholder: 'udp://0.0.0.0:14550',
  },
];

/**
 * The register form's `<select>` sentinel for "none of the above" — reveals the old free-text
 * protocol input as an escape hatch for a future adapter not in {@link REGISTERABLE_PROTOCOLS} yet.
 * Never sent to the backend: `protocolSelectionFor`/the component's own `protocol` computed always
 * resolve this back to whatever the user typed in the free-text field.
 */
export const CUSTOM_PROTOCOL_OPTION = '__custom__';

/** Generic placeholder shown before a protocol is chosen, or for an unrecognized `Custom…` value. */
const GENERIC_PLACEHOLDER = 'protocol://host/path';

/** The URI field's placeholder for the given protocol — a realistic example, or a generic fallback. */
export function placeholderForProtocol(protocol: string): string {
  return REGISTERABLE_PROTOCOLS.find((option) => option.value === protocol)?.placeholder ?? GENERIC_PLACEHOLDER;
}

/** True when `protocol` is one of the ones this build actually supports (not the `Custom…` sentinel). */
export function isKnownProtocol(protocol: string): boolean {
  return REGISTERABLE_PROTOCOLS.some((option) => option.value === protocol);
}

/** What the `<select>`/free-text pair should show for an already-known protocol string. */
export interface ProtocolSelection {
  /** The `<select>`'s bound value: a known protocol, or {@link CUSTOM_PROTOCOL_OPTION}. */
  readonly select: string;
  /** The free-text field's value — populated only when `select` is the `Custom…` sentinel. */
  readonly custom: string;
}

/**
 * Resolves a protocol string (e.g. from a discovery candidate's `suggestedProtocol`-like field, or
 * any other prefill source) into `<select>`/free-text state: a known protocol selects it directly;
 * an unknown one (or one this build doesn't list) falls through to `Custom…` with the original
 * value preserved in the free-text field, so nothing the backend already sent is ever silently
 * dropped. Blank/absent resolves to no selection at all (the form's own "choose a protocol" state).
 */
export function protocolSelectionFor(protocol: string | undefined): ProtocolSelection {
  const value = (protocol ?? '').trim();
  if (value.length === 0) {
    return { select: '', custom: '' };
  }
  return isKnownProtocol(value) ? { select: value, custom: '' } : { select: CUSTOM_PROTOCOL_OPTION, custom: value };
}
