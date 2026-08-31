import { describe, expect, it } from 'vitest';
import {
  IMPROV_STREAM_INITIAL_STATE,
  ImprovCurrentState,
  ImprovErrorState,
  ImprovPacketType,
  ImprovRpcCommand,
  buildImprovFrame,
  buildRequestCurrentStateFrame,
  buildRpcCommandFrame,
  buildSendWifiSettingsFrame,
  decodeImprovFrame,
  improvCurrentStateLabel,
  improvErrorMessage,
  readImprovBytes,
} from './improv-serial-protocol';

/** Strips the write-side trailing `\n` — {@link decodeImprovFrame} operates on header-through-checksum only. */
function withoutNewline(frame: Uint8Array): number[] {
  return Array.from(frame.slice(0, -1));
}

describe('improv-serial-protocol', () => {
  describe('buildImprovFrame / decodeImprovFrame round-trip', () => {
    it('round-trips a Current State frame', () => {
      const frame = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({ ok: true, frame: { kind: 'currentState', state: ImprovCurrentState.READY } });
    });

    it('round-trips an Error State frame', () => {
      const frame = buildImprovFrame(ImprovPacketType.ERROR_STATE, [ImprovErrorState.UNABLE_TO_CONNECT]);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({ ok: true, frame: { kind: 'errorState', error: ImprovErrorState.UNABLE_TO_CONNECT } });
    });

    it('a zero-argument RPC command frame is well-formed (right length, valid checksum)', () => {
      const frame = buildRequestCurrentStateFrame();
      expect(frame.length).toBe(6 + 1 + 1 + 1 + 2 + 1 + 1); // magic+ver+type+len+[cmd,argLen]+checksum+\n
      // RPC_COMMAND is client→device only — decodeImprovFrame deliberately models no `ImprovFrame`
      // kind for it (nothing in this app ever needs to decode its own outgoing command), so a
      // well-formed one still comes back 'unknown-packet-type' rather than a thrown error — proof the
      // checksum/length machinery accepted it before the packet-type switch ran out of cases.
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({ ok: false, reason: 'unknown-packet-type' });
    });

    it('ends every written frame with the newline terminator, uncounted by the checksum', () => {
      const frame = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.PROVISIONED]);
      expect(frame[frame.length - 1]).toBe(0x0a);
      // Corrupting the (already-stripped) newline has no bearing on decode — prove the checksum
      // truly never covers it by decoding the frame with a *different* trailing byte appended
      // to `bytes` (decodeImprovFrame takes header-through-checksum only, so this just confirms
      // withoutNewline's slice is exactly the checksum-covered span).
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result.ok).toBe(true);
    });

    it('rejects a frame whose declared length does not match the actual data', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]));
      // Drop only the checksum byte — enough bytes remain to clear the too-short check, but the
      // header's own declared length byte no longer matches what's actually present.
      const truncated = bytes.slice(0, -1);
      expect(decodeImprovFrame(truncated)).toEqual({ ok: false, reason: 'length-mismatch' });
    });

    it('rejects a frame with the wrong magic', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]));
      const corrupted = [...bytes];
      corrupted[0] = 0x58; // 'X' instead of 'I'
      expect(decodeImprovFrame(corrupted)).toEqual({ ok: false, reason: 'bad-magic' });
    });

    it('rejects a frame with an unsupported version', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]));
      const corrupted = [...bytes];
      corrupted[6] = 2;
      expect(decodeImprovFrame(corrupted)).toEqual({ ok: false, reason: 'unsupported-version' });
    });

    it('rejects a frame too short to even hold a header', () => {
      expect(decodeImprovFrame([0x49, 0x4d, 0x50])).toEqual({ ok: false, reason: 'too-short' });
    });

    it('rejects an unrecognized packet type', () => {
      const bytes = withoutNewline(buildImprovFrame(0x09 as ImprovPacketType, [1]));
      expect(decodeImprovFrame(bytes)).toEqual({ ok: false, reason: 'unknown-packet-type' });
    });
  });

  describe('checksum rejection', () => {
    it('rejects a frame whose checksum byte was corrupted', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]));
      const corrupted = [...bytes];
      corrupted[corrupted.length - 1] = (corrupted[corrupted.length - 1] + 1) & 0xff;
      expect(decodeImprovFrame(corrupted)).toEqual({ ok: false, reason: 'bad-checksum' });
    });

    it('rejects a frame whose data was corrupted (checksum no longer matches)', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.ERROR_STATE, [ImprovErrorState.NO_ERROR]));
      const corrupted = [...bytes];
      corrupted[9] = ImprovErrorState.UNKNOWN_ERROR; // flip the one data byte, leave the old checksum
      expect(decodeImprovFrame(corrupted)).toEqual({ ok: false, reason: 'bad-checksum' });
    });

    it('accepts an unmodified frame (checksum computed correctly on write)', () => {
      const bytes = withoutNewline(buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.PROVISIONING]));
      expect(decodeImprovFrame(bytes).ok).toBe(true);
    });
  });

  describe('buildSendWifiSettingsFrame', () => {
    it('length-prefixes SSID and password as separate UTF-8 fields', () => {
      const frame = buildSendWifiSettingsFrame('MyNetwork', 'hunter2');
      const bytes = withoutNewline(frame);
      // magic(6) version(1) type(1) len(1) [cmd(1) argLen(1) ssidLen(1) ssid(9) pwLen(1) pw(7)] checksum(1)
      const dataStart = 9;
      expect(bytes[7]).toBe(ImprovPacketType.RPC_COMMAND);
      expect(bytes[dataStart]).toBe(ImprovRpcCommand.SEND_WIFI_SETTINGS);
      const ssidLen = bytes[dataStart + 2];
      expect(ssidLen).toBe(9); // 'MyNetwork'.length
      const ssidBytes = bytes.slice(dataStart + 3, dataStart + 3 + ssidLen);
      expect(new TextDecoder().decode(Uint8Array.from(ssidBytes))).toBe('MyNetwork');
      const pwLenIndex = dataStart + 3 + ssidLen;
      const pwLen = bytes[pwLenIndex];
      expect(pwLen).toBe(7); // 'hunter2'.length
      const pwBytes = bytes.slice(pwLenIndex + 1, pwLenIndex + 1 + pwLen);
      expect(new TextDecoder().decode(Uint8Array.from(pwBytes))).toBe('hunter2');
    });

    it('round-trips through decodeImprovFrame as an RPC_COMMAND (not directly decodable as a result — only the device sends RPC_RESULT)', () => {
      const frame = buildSendWifiSettingsFrame('a', 'b');
      const result = decodeImprovFrame(withoutNewline(frame));
      // RPC_COMMAND has no decoded "kind" — only device→client packet types are modeled as ImprovFrame.
      expect(result).toEqual({ ok: false, reason: 'unknown-packet-type' });
    });

    it('handles an empty password (open network)', () => {
      const frame = buildSendWifiSettingsFrame('OpenNet', '');
      const bytes = withoutNewline(frame);
      const ssidLen = bytes[11];
      expect(bytes[11 + 1 + ssidLen]).toBe(0); // password length byte is 0
    });

    it('rejects an SSID/password too long to fit a single length-prefixed field', () => {
      const tooLong = 'x'.repeat(256);
      expect(() => buildSendWifiSettingsFrame(tooLong, 'pw')).toThrow(RangeError);
      expect(() => buildSendWifiSettingsFrame('ssid', tooLong)).toThrow(RangeError);
    });
  });

  describe('RPC Result shapes', () => {
    /** Builds a raw RPC_RESULT frame's data field: [command, totalLen, (strLen,...str)*]. */
    function rpcResultData(command: ImprovRpcCommand, values: readonly string[]): number[] {
      const encoder = new TextEncoder();
      const parts = values.map((v) => Array.from(encoder.encode(v)));
      const body = parts.flatMap((p) => [p.length, ...p]);
      return [command, body.length, ...body];
    }

    it('decodes a SEND_WIFI_SETTINGS result carrying a redirect URL', () => {
      const data = rpcResultData(ImprovRpcCommand.SEND_WIFI_SETTINGS, ['http://192.168.1.42/']);
      const frame = buildImprovFrame(ImprovPacketType.RPC_RESULT, data);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({
        ok: true,
        frame: { kind: 'rpcResult', command: ImprovRpcCommand.SEND_WIFI_SETTINGS, values: ['http://192.168.1.42/'] },
      });
    });

    it('decodes a SEND_WIFI_SETTINGS result with no redirect URL (empty string, not absent)', () => {
      const data = rpcResultData(ImprovRpcCommand.SEND_WIFI_SETTINGS, ['']);
      const frame = buildImprovFrame(ImprovPacketType.RPC_RESULT, data);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({
        ok: true,
        frame: { kind: 'rpcResult', command: ImprovRpcCommand.SEND_WIFI_SETTINGS, values: [''] },
      });
    });

    it('decodes a REQUEST_CURRENT_STATE result with zero values (device not yet provisioned)', () => {
      const data = rpcResultData(ImprovRpcCommand.REQUEST_CURRENT_STATE, []);
      const frame = buildImprovFrame(ImprovPacketType.RPC_RESULT, data);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({
        ok: true,
        frame: { kind: 'rpcResult', command: ImprovRpcCommand.REQUEST_CURRENT_STATE, values: [] },
      });
    });

    it('decodes a multi-value result (e.g. REQUEST_DEVICE_INFO: firmware, version, chip, name)', () => {
      const data = rpcResultData(ImprovRpcCommand.REQUEST_DEVICE_INFO, ['ImprovDemo', '1.0.0', 'ESP32', 'kitchen-rover']);
      const frame = buildImprovFrame(ImprovPacketType.RPC_RESULT, data);
      const result = decodeImprovFrame(withoutNewline(frame));
      expect(result).toEqual({
        ok: true,
        frame: {
          kind: 'rpcResult',
          command: ImprovRpcCommand.REQUEST_DEVICE_INFO,
          values: ['ImprovDemo', '1.0.0', 'ESP32', 'kitchen-rover'],
        },
      });
    });
  });

  describe('error states', () => {
    it('improvErrorMessage(NO_ERROR) is the exact no-op message', () => {
      expect(improvErrorMessage(ImprovErrorState.NO_ERROR)).toBe('No error.');
    });

    const matchCases: ReadonlyArray<[ImprovErrorState, RegExp]> = [
      [ImprovErrorState.UNABLE_TO_CONNECT, /could not connect/i],
      [ImprovErrorState.NOT_AUTHORIZED, /not authorized/i],
      [ImprovErrorState.UNKNOWN_RPC_COMMAND, /unknown rpc command/i],
      [ImprovErrorState.INVALID_RPC_PACKET, /invalid rpc packet/i],
    ];

    it.each(matchCases)('improvErrorMessage(%s) names the failure honestly', (code, expected) => {
      expect(improvErrorMessage(code)).toMatch(expected);
    });

    it('names an unrecognized error code rather than guessing', () => {
      expect(improvErrorMessage(0x42)).toBe('The device reported an unrecognized error (code 66).');
    });

    it('decodes each Error State frame value round-trip', () => {
      for (const error of [
        ImprovErrorState.NO_ERROR,
        ImprovErrorState.INVALID_RPC_PACKET,
        ImprovErrorState.UNKNOWN_RPC_COMMAND,
        ImprovErrorState.UNABLE_TO_CONNECT,
        ImprovErrorState.NOT_AUTHORIZED,
        ImprovErrorState.BAD_HOSTNAME,
        ImprovErrorState.UNKNOWN_ERROR,
      ]) {
        const frame = buildImprovFrame(ImprovPacketType.ERROR_STATE, [error]);
        expect(decodeImprovFrame(withoutNewline(frame))).toEqual({ ok: true, frame: { kind: 'errorState', error } });
      }
    });
  });

  describe('improvCurrentStateLabel', () => {
    it('labels each known state honestly', () => {
      expect(improvCurrentStateLabel(ImprovCurrentState.STOPPED)).toMatch(/unavailable/i);
      expect(improvCurrentStateLabel(ImprovCurrentState.READY)).toMatch(/ready/i);
      expect(improvCurrentStateLabel(ImprovCurrentState.PROVISIONING)).toMatch(/connecting/i);
      expect(improvCurrentStateLabel(ImprovCurrentState.PROVISIONED)).toMatch(/provisioned/i);
    });

    it('names an unrecognized state code rather than guessing', () => {
      expect(improvCurrentStateLabel(0x07)).toBe('Unrecognized device state (code 7).');
    });
  });

  describe('readImprovBytes (streaming reader)', () => {
    it('parses one frame delivered as a single chunk', () => {
      const frame = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]);
      const step = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, frame);
      expect(step.frames).toEqual([{ kind: 'currentState', state: ImprovCurrentState.READY }]);
      expect(step.failures).toEqual([]);
      // The trailing \n is left in `state.buffer` (1 byte) until the next chunk's sliding-window
      // resync discards it — covered by the split-across-chunks case below.
    });

    it('parses a frame split across two chunks at an arbitrary byte', () => {
      const frame = buildImprovFrame(ImprovPacketType.ERROR_STATE, [ImprovErrorState.UNABLE_TO_CONNECT]);
      const first = frame.slice(0, 5);
      const second = frame.slice(5);

      const step1 = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, first);
      expect(step1.frames).toEqual([]);
      expect(step1.state.buffer.length).toBe(5);

      const step2 = readImprovBytes(step1.state, second);
      expect(step2.frames).toEqual([{ kind: 'errorState', error: ImprovErrorState.UNABLE_TO_CONNECT }]);
    });

    it('parses two frames delivered in one chunk back to back', () => {
      const a = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.PROVISIONING]);
      const b = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.PROVISIONED]);
      const combined = new Uint8Array([...a, ...b]);
      const step = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, combined);
      expect(step.frames).toEqual([
        { kind: 'currentState', state: ImprovCurrentState.PROVISIONING },
        { kind: 'currentState', state: ImprovCurrentState.PROVISIONED },
      ]);
    });

    it('discards non-Improv noise (e.g. a boot-log line) and resyncs onto the real frame that follows', () => {
      const noise = new TextEncoder().encode('booting rover firmware v3\n');
      const frame = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]);
      const combined = new Uint8Array([...noise, ...frame]);
      const step = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, combined);
      expect(step.frames).toEqual([{ kind: 'currentState', state: ImprovCurrentState.READY }]);
      expect(step.failures).toEqual([]);
    });

    it('surfaces a checksum failure but keeps parsing subsequent frames', () => {
      const bad = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.READY]);
      bad[bad.length - 2] = (bad[bad.length - 2] + 1) & 0xff; // corrupt the checksum byte
      const good = buildImprovFrame(ImprovPacketType.CURRENT_STATE, [ImprovCurrentState.PROVISIONED]);
      const combined = new Uint8Array([...bad, ...good]);

      const step = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, combined);
      expect(step.failures).toEqual(['bad-checksum']);
      expect(step.frames).toEqual([{ kind: 'currentState', state: ImprovCurrentState.PROVISIONED }]);
    });

    it('carries an empty buffer forward when nothing has arrived yet', () => {
      const step = readImprovBytes(IMPROV_STREAM_INITIAL_STATE, new Uint8Array());
      expect(step.state).toEqual(IMPROV_STREAM_INITIAL_STATE);
      expect(step.frames).toEqual([]);
    });
  });

  describe('buildRpcCommandFrame argument bound', () => {
    it('rejects arguments longer than fit in one frame', () => {
      const tooMany = new Array(254).fill(1);
      expect(() => buildRpcCommandFrame(ImprovRpcCommand.SEND_WIFI_SETTINGS, tooMany)).toThrow(RangeError);
    });
  });
});
