/**
 * Improv Serial wire protocol codec — https://www.improv-wifi.com/serial/, the open ESPHome/
 * Nabu-Casa standard for provisioning a device onto Wi-Fi over USB with no companion app, cited in
 * docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §2/§4 (wave Z5). Pure, framework-free — no
 * DOM/Web Serial dependency — so it's unit-testable without a real port; `web-serial-gateway.ts` is
 * the only file in this feature that touches actual hardware.
 *
 * Verified against the reference implementation (`improv-wifi/sdk-serial-js`,
 * github.com/improv-wifi/sdk-serial-js, `src/const.ts` + `src/serial.ts`, fetched 2026-08-31) rather
 * than the protocol page's own summary table alone — that page's error-state list omits
 * `NOT_AUTHORIZED` entirely, and the reference SDK's own source comment marks it BLE-only ("serial
 * is always authorized"). It's kept here anyway, decodable but never sent by this codec: a value a
 * real serial device is not expected to send is still parsed honestly rather than silently dropped
 * (CLAUDE.md "degrade honestly" — never a fabricated-looking gap when an unexpected byte arrives).
 *
 * <h2>Frame layout</h2>
 * `"IMPROV"`(6 bytes) + version(1) + type(1) + length(1) + data(`length` bytes) + checksum(1)
 * [+ `\n`, write-side only — the newline is never covered by the checksum]. `checksum` is the low
 * byte of the sum of every byte from the magic through the end of `data` (i.e. everything except
 * itself and the newline).
 */

// "IMPROV"
const IMPROV_MAGIC = [0x49, 0x4d, 0x50, 0x52, 0x4f, 0x56] as const;
const IMPROV_VERSION = 1;
const FRAME_TERMINATOR = 0x0a; // '\n', appended on write only
const FRAME_HEADER_BYTES = IMPROV_MAGIC.length + 3; // magic(6) + version(1) + type(1) + length(1)

/** Improv Serial's one fixed baud rate — every compatible device listens at this rate regardless of
 *  firmware; there is nothing to auto-detect or configure. */
export const IMPROV_SERIAL_BAUD_RATE = 115200;

export enum ImprovPacketType {
  CURRENT_STATE = 0x01, // device → client
  ERROR_STATE = 0x02, // device → client
  RPC_COMMAND = 0x03, // client → device
  RPC_RESULT = 0x04, // device → client
}

/** The device's own provisioning state, carried in a Current State (0x01) packet's one data byte. */
export enum ImprovCurrentState {
  STOPPED = 0x00, // Provisioning unavailable right now (e.g. Wi-Fi hardware disabled)
  READY = 0x02, // Authorized and waiting for Wi-Fi credentials
  PROVISIONING = 0x03, // Credentials received, attempting to connect
  PROVISIONED = 0x04, // Connected successfully
}

/** The device's own error code, carried in an Error State (0x02) packet's one data byte. */
export enum ImprovErrorState {
  NO_ERROR = 0x00,
  INVALID_RPC_PACKET = 0x01, // the command sent was malformed
  UNKNOWN_RPC_COMMAND = 0x02, // the command sent isn't one this device supports
  UNABLE_TO_CONNECT = 0x03, // credentials were valid RPC, but the Wi-Fi connection attempt failed
  NOT_AUTHORIZED = 0x04, // BLE-transport-only per the reference SDK; see this file's own doc comment
  BAD_HOSTNAME = 0x05,
  UNKNOWN_ERROR = 0xff,
}

/** RPC commands the client may send (0x03 packets). Only the four this page's flow needs — hostname/
 *  device-name/network-state RPCs exist in the wider spec but have no caller here. */
export enum ImprovRpcCommand {
  SEND_WIFI_SETTINGS = 0x01,
  REQUEST_CURRENT_STATE = 0x02,
  REQUEST_DEVICE_INFO = 0x03,
  REQUEST_WIFI_NETWORKS = 0x04,
}

function sumChecksum(bytes: readonly number[]): number {
  let sum = 0;
  for (const b of bytes) {
    sum += b;
  }
  return sum & 0xff;
}

/** Builds one complete, checksummed, newline-terminated Improv frame ready to write to the wire. */
export function buildImprovFrame(type: ImprovPacketType, data: readonly number[]): Uint8Array {
  if (data.length > 255) {
    throw new RangeError(`Improv frame data too long (${data.length} bytes, max 255)`);
  }
  const withoutChecksum = [...IMPROV_MAGIC, IMPROV_VERSION, type, data.length, ...data];
  const checksum = sumChecksum(withoutChecksum);
  return Uint8Array.from([...withoutChecksum, checksum, FRAME_TERMINATOR]);
}

/** Wraps an RPC command + its argument bytes in an RPC Command (0x03) frame — data is
 *  `[command, args.length, ...args]`, per the reference SDK's `_sendRPC`. */
export function buildRpcCommandFrame(command: ImprovRpcCommand, args: readonly number[]): Uint8Array {
  if (args.length > 253) {
    throw new RangeError(`Improv RPC arguments too long (${args.length} bytes, max 253)`);
  }
  return buildImprovFrame(ImprovPacketType.RPC_COMMAND, [command, args.length, ...args]);
}

/** RPC 0x02 — asks the device to (re-)send its Current State. Also used as the post-connect
 *  handshake probe: a real Improv device always answers this, an unrelated serial device never does. */
export function buildRequestCurrentStateFrame(): Uint8Array {
  return buildRpcCommandFrame(ImprovRpcCommand.REQUEST_CURRENT_STATE, []);
}

/** RPC 0x01 — Send Wi-Fi settings: SSID + password, each UTF-8, length-prefixed (max 255 bytes
 *  apiece). Neither value is logged, cached, or persisted anywhere by this codec — see
 *  `provisioning-facade.ts`'s own doc comment for where that promise is upheld end to end. */
export function buildSendWifiSettingsFrame(ssid: string, password: string): Uint8Array {
  const encoder = new TextEncoder();
  const ssidBytes = Array.from(encoder.encode(ssid));
  const passwordBytes = Array.from(encoder.encode(password));
  if (ssidBytes.length > 255 || passwordBytes.length > 255) {
    throw new RangeError('Wi-Fi SSID/password too long to encode as a single Improv field (max 255 bytes each)');
  }
  return buildRpcCommandFrame(ImprovRpcCommand.SEND_WIFI_SETTINGS, [
    ssidBytes.length,
    ...ssidBytes,
    passwordBytes.length,
    ...passwordBytes,
  ]);
}

export interface ImprovCurrentStateFrame {
  readonly kind: 'currentState';
  readonly state: ImprovCurrentState;
}

export interface ImprovErrorStateFrame {
  readonly kind: 'errorState';
  readonly error: ImprovErrorState;
}

/** A decoded RPC Result (0x04) — `values` is the device's length-prefixed UTF-8 string list; for
 *  `SEND_WIFI_SETTINGS`/`REQUEST_CURRENT_STATE` the first (only) entry is the post-provision
 *  redirect URL, empty string when the device has none to offer. */
export interface ImprovRpcResultFrame {
  readonly kind: 'rpcResult';
  readonly command: ImprovRpcCommand;
  readonly values: readonly string[];
}

export type ImprovFrame = ImprovCurrentStateFrame | ImprovErrorStateFrame | ImprovRpcResultFrame;

export type ImprovDecodeFailureReason =
  | 'too-short'
  | 'bad-magic'
  | 'unsupported-version'
  | 'length-mismatch'
  | 'bad-checksum'
  | 'unknown-packet-type';

export type ImprovDecodeResult =
  | { readonly ok: true; readonly frame: ImprovFrame }
  | { readonly ok: false; readonly reason: ImprovDecodeFailureReason };

function decodeRpcResult(data: readonly number[]): ImprovRpcResultFrame | undefined {
  if (data.length < 2) {
    return undefined;
  }
  const command = data[0] as ImprovRpcCommand;
  const totalLength = data[1];
  const decoder = new TextDecoder('utf-8');
  const values: string[] = [];
  let index = 2;
  const end = Math.min(2 + totalLength, data.length);
  while (index < end) {
    const strLength = data[index];
    const start = index + 1;
    values.push(decoder.decode(Uint8Array.from(data.slice(start, start + strLength))));
    index = start + strLength;
  }
  return { kind: 'rpcResult', command, values };
}

function decodeFramePayload(type: number, data: readonly number[]): ImprovFrame | undefined {
  switch (type) {
    case ImprovPacketType.CURRENT_STATE:
      return { kind: 'currentState', state: (data[0] ?? ImprovCurrentState.STOPPED) as ImprovCurrentState };
    case ImprovPacketType.ERROR_STATE:
      return { kind: 'errorState', error: (data[0] ?? ImprovErrorState.NO_ERROR) as ImprovErrorState };
    case ImprovPacketType.RPC_RESULT:
      return decodeRpcResult(data);
    default:
      return undefined;
  }
}

/** Decodes one complete frame — header through checksum, WITHOUT the trailing `\n`. Exported
 *  directly for round-trip/checksum-rejection tests; used internally by {@link readImprovBytes} for
 *  the real byte-stream case, where frame boundaries aren't known in advance. */
export function decodeImprovFrame(bytes: readonly number[]): ImprovDecodeResult {
  if (bytes.length < FRAME_HEADER_BYTES + 1) {
    return { ok: false, reason: 'too-short' };
  }
  if (!IMPROV_MAGIC.every((b, i) => bytes[i] === b)) {
    return { ok: false, reason: 'bad-magic' };
  }
  if (bytes[6] !== IMPROV_VERSION) {
    return { ok: false, reason: 'unsupported-version' };
  }
  const type = bytes[7];
  const length = bytes[8];
  if (bytes.length !== FRAME_HEADER_BYTES + length + 1) {
    return { ok: false, reason: 'length-mismatch' };
  }
  const data = bytes.slice(FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + length);
  const checksum = bytes[FRAME_HEADER_BYTES + length];
  const expected = sumChecksum(bytes.slice(0, FRAME_HEADER_BYTES + length));
  if (checksum !== expected) {
    return { ok: false, reason: 'bad-checksum' };
  }
  const frame = decodeFramePayload(type, data);
  if (!frame) {
    return { ok: false, reason: 'unknown-packet-type' };
  }
  return { ok: true, frame };
}

function headerLooksValid(buffer: readonly number[]): boolean {
  return IMPROV_MAGIC.every((b, i) => buffer[i] === b) && buffer[6] === IMPROV_VERSION;
}

export interface ImprovStreamState {
  readonly buffer: readonly number[];
}

export const IMPROV_STREAM_INITIAL_STATE: ImprovStreamState = { buffer: [] };

export interface ImprovStreamStep {
  readonly state: ImprovStreamState;
  readonly frames: readonly ImprovFrame[];
  readonly failures: readonly ImprovDecodeFailureReason[];
}

/**
 * Feeds one newly-arrived chunk (straight off a `ReadableStreamDefaultReader<Uint8Array>`) through
 * the framing state machine and returns every complete frame it found, `(state, action) => state`
 * shaped like `player-recovery.ts`'s reducers so the byte-stream logic is testable one chunk at a
 * time without a real port. A frame can split across two `read()` calls at an arbitrary byte, so
 * `state.buffer` carries forward whatever partial frame is still being assembled.
 *
 * Resync is a sliding window: once 9 buffered bytes stop looking like a real header (wrong magic or
 * version — a device's own boot-log text sent down the same wire before Improv starts, for
 * instance), the oldest byte is dropped and the window retested, one byte at a time, until either a
 * real header reappears or the buffer drains. A frame whose checksum fails is still consumed for its
 * full declared length (matching the reference SDK — once a length byte is trusted there is no way
 * to recover a different resync point from it), so device chatter can never wedge the parser.
 */
export function readImprovBytes(state: ImprovStreamState, chunk: Uint8Array): ImprovStreamStep {
  const buffer: number[] = [...state.buffer];
  const frames: ImprovFrame[] = [];
  const failures: ImprovDecodeFailureReason[] = [];

  for (const byte of chunk) {
    buffer.push(byte);

    if (buffer.length < FRAME_HEADER_BYTES) {
      continue;
    }

    if (!headerLooksValid(buffer)) {
      while (buffer.length >= FRAME_HEADER_BYTES && !headerLooksValid(buffer)) {
        buffer.shift();
      }
      if (buffer.length < FRAME_HEADER_BYTES) {
        continue;
      }
    }

    const length = buffer[8];
    const total = FRAME_HEADER_BYTES + length + 1;
    if (buffer.length < total) {
      continue;
    }
    const result = decodeImprovFrame(buffer.slice(0, total));
    if (result.ok) {
      frames.push(result.frame);
    } else {
      failures.push(result.reason);
    }
    buffer.splice(0, total);
  }

  return { state: { buffer }, frames, failures };
}

const CURRENT_STATE_LABELS: Readonly<Record<ImprovCurrentState, string>> = {
  [ImprovCurrentState.STOPPED]: 'Provisioning is unavailable on this device right now.',
  [ImprovCurrentState.READY]: 'Ready — waiting for Wi-Fi credentials.',
  [ImprovCurrentState.PROVISIONING]: 'Connecting to Wi-Fi…',
  [ImprovCurrentState.PROVISIONED]: 'Provisioned — connected to Wi-Fi.',
};

/** Honest label for a device's Current State — an unrecognized code names its own raw value rather
 *  than guessing (CLAUDE.md "degrade honestly"). */
export function improvCurrentStateLabel(state: number): string {
  return CURRENT_STATE_LABELS[state as ImprovCurrentState] ?? `Unrecognized device state (code ${state}).`;
}

const ERROR_MESSAGES: Readonly<Record<ImprovErrorState, string>> = {
  [ImprovErrorState.NO_ERROR]: 'No error.',
  [ImprovErrorState.INVALID_RPC_PACKET]: 'The device could not parse that command (invalid RPC packet).',
  [ImprovErrorState.UNKNOWN_RPC_COMMAND]: 'The device does not support that command (unknown RPC command).',
  [ImprovErrorState.UNABLE_TO_CONNECT]:
    'The device could not connect to that Wi-Fi network — check the SSID and password and try again.',
  [ImprovErrorState.NOT_AUTHORIZED]: 'The device is not authorized to accept Wi-Fi credentials right now.',
  [ImprovErrorState.BAD_HOSTNAME]: 'The device rejected its own hostname — this is unrelated to your Wi-Fi credentials.',
  [ImprovErrorState.UNKNOWN_ERROR]: 'The device reported an unspecified error.',
};

/** Honest message for a device-reported error code — never paraphrased into a guess, and an
 *  unrecognized code names its own raw value (CLAUDE.md "never paraphrase a server/device-supplied
 *  failure reason" + "degrade honestly"). */
export function improvErrorMessage(error: number): string {
  return ERROR_MESSAGES[error as ImprovErrorState] ?? `The device reported an unrecognized error (code ${error}).`;
}
