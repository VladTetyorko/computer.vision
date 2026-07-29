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
 * - `mjpeg` — `adapters/adapter-mjpeg/.../MjpegVideoSource#supports` (`PROTOCOL = "mjpeg"`, `uri` scheme `http`/`https`)
 * - `srt` — the same `FfmpegVideoSource#supports` as `rtsp`/`file`, extended in
 *   docs/DRONE-INFRA-PLAN.md I-h wave A (`adapters/adapter-rtsp/**`, landed concurrently with this
 *   task) to accept the `srt` protocol string; **not** independently re-verified against that
 *   adapter's source here — this task's own file scope was `vision-web/**` only, so the plan's own
 *   frozen contract (protocol string `srt`, `uri` scheme `srt:`) is the source of truth for this entry.
 * - `udp` — the same `FfmpegVideoSource#supports`, same I-h wave A, protocol string `udp` (MPEG-TS
 *   assumed), same not-independently-re-verified caveat as `srt` above.
 * - `file` — the same `FfmpegVideoSource#supports` (`PROTOCOL_FILE = "file"`, `uri` scheme must be `file:`)
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
 * **List order is grouped by likelihood/relatedness, not alphabetical or add-order** (docs/DRONE-INFRA-PLAN.md
 * I-h wave B — 8 entries is enough that scanability started to matter): network camera/video streams
 * first (`rtsp`, `mjpeg`, `srt`, `udp` — the ones an operator is most often choosing between), then
 * local sources (`v4l2`, `file`), then the two special-purpose entries (`sim`, `mavlink`) last. Plain
 * reordering was chosen over `<optgroup>`-style visual grouping: the register form's `<select>`
 * (`features/onboarding/onboarding.html`) already renders one flat `@for` over this array with no
 * group data attached to `ProtocolOption`, and true optgroups would need either a new field here
 * (breaking "keep the list's existing shape" — every consumer, `placeholderForProtocol`/
 * `isKnownProtocol`/`protocolSelectionFor` included, assumes a flat array) or a second lookup
 * structure kept in sync with this one by hand; each option's own `{{ value }} — {{ hint }}` rendering
 * already gives a one-line description right in the dropdown, which was judged enough for a
 * single-digit-length list without the added surface. Revisit if the list keeps growing.
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
    value: 'mjpeg',
    hint: 'HTTP MJPEG camera or stream endpoint',
    placeholder: 'http://192.168.1.60:8080/video',
  },
  {
    value: 'srt',
    hint:
      'Low-latency drone/FPV video over SRT (4G/5G, long-range links) — the drone usually dials ' +
      'in, so set mode=listener under Stream options; leave it as caller to dial out to an encoder.',
    placeholder: 'srt://0.0.0.0:8890',
  },
  {
    value: 'udp',
    hint:
      'MPEG-TS over UDP (ground-station encoder, e.g. ffmpeg -f mpegts udp://…) — this app listens ' +
      'on the port, it does not dial out',
    placeholder: 'udp://0.0.0.0:5600',
  },
  {
    value: 'v4l2',
    hint: 'USB/local camera attached to this machine (Linux V4L2)',
    placeholder: 'file:///dev/video0',
  },
  {
    value: 'file',
    hint: 'A video file already on the server, played on a loop',
    placeholder: 'file:///srv/videos/flight.mp4',
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
