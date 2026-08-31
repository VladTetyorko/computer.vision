import { describe, expect, it } from 'vitest';
import { ImprovCurrentState } from './improv-serial-protocol';
import {
  canSubmitWifiSettings,
  describeSerialRequestError,
  deviceStateChipTone,
  isCancelledPickerError,
  type SerialSessionPhase,
} from './provisioning-logic';

describe('canSubmitWifiSettings', () => {
  const base = {
    session: 'open' as SerialSessionPhase,
    deviceState: ImprovCurrentState.READY,
    ssid: 'MyNetwork',
    sending: false,
  };

  it('allows sending when the session is open, the device is ready, and an SSID is typed', () => {
    expect(canSubmitWifiSettings(base)).toBe(true);
  });

  it('allows sending with no password (open networks are real)', () => {
    expect(canSubmitWifiSettings(base)).toBe(true);
  });

  it('refuses when the session is not open', () => {
    expect(canSubmitWifiSettings({ ...base, session: 'connecting' })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, session: 'idle' })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, session: 'link-lost' })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, session: 'request-failed' })).toBe(false);
  });

  it('refuses when the device has not reported READY', () => {
    expect(canSubmitWifiSettings({ ...base, deviceState: undefined })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, deviceState: ImprovCurrentState.STOPPED })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, deviceState: ImprovCurrentState.PROVISIONING })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, deviceState: ImprovCurrentState.PROVISIONED })).toBe(false);
  });

  it('refuses a blank or whitespace-only SSID', () => {
    expect(canSubmitWifiSettings({ ...base, ssid: '' })).toBe(false);
    expect(canSubmitWifiSettings({ ...base, ssid: '   ' })).toBe(false);
  });

  it('refuses while a send is already in flight', () => {
    expect(canSubmitWifiSettings({ ...base, sending: true })).toBe(false);
  });
});

describe('deviceStateChipTone', () => {
  it('is neutral for READY, STOPPED, and unknown/undefined', () => {
    expect(deviceStateChipTone(ImprovCurrentState.READY)).toBe('neutral');
    expect(deviceStateChipTone(ImprovCurrentState.STOPPED)).toBe('neutral');
    expect(deviceStateChipTone(undefined)).toBe('neutral');
  });

  it('is warn while provisioning is in progress', () => {
    expect(deviceStateChipTone(ImprovCurrentState.PROVISIONING)).toBe('warn');
  });

  it('is ok once provisioned', () => {
    expect(deviceStateChipTone(ImprovCurrentState.PROVISIONED)).toBe('ok');
  });
});

describe('describeSerialRequestError', () => {
  it('names a cancelled device picker', () => {
    expect(describeSerialRequestError(new DOMException('cancelled', 'NotFoundError'))).toBe(
      'No device was selected.',
    );
  });

  it('names a permission/security block', () => {
    expect(describeSerialRequestError(new DOMException('blocked', 'SecurityError'))).toBe(
      'The browser blocked access to serial devices on this page.',
    );
  });

  it('names a port already in use', () => {
    expect(describeSerialRequestError(new DOMException('busy', 'NetworkError'))).toBe(
      'Could not open the device — it may already be in use by another program or browser tab.',
    );
  });

  it('names a stale/invalid connection', () => {
    expect(describeSerialRequestError(new DOMException('stale', 'InvalidStateError'))).toBe(
      'The device connection is no longer valid — try connecting again.',
    );
  });

  it('falls back to the DOMException message for an unrecognized DOMException name', () => {
    expect(describeSerialRequestError(new DOMException('something odd', 'AbortError'))).toBe('something odd');
  });

  it('falls back to a generic message for an unrecognized DOMException with no message', () => {
    expect(describeSerialRequestError(new DOMException('', 'AbortError'))).toBe('Could not connect to the device.');
  });

  it('uses a plain Error message', () => {
    expect(describeSerialRequestError(new Error('port vanished'))).toBe('port vanished');
  });

  it('never fabricates a message for a non-Error throw', () => {
    expect(describeSerialRequestError('nope')).toBe('Could not connect to the device.');
    expect(describeSerialRequestError(undefined)).toBe('Could not connect to the device.');
  });
});

describe('isCancelledPickerError', () => {
  it('is true only for a NotFoundError DOMException', () => {
    expect(isCancelledPickerError(new DOMException('cancelled', 'NotFoundError'))).toBe(true);
  });

  it('is false for any other error shape', () => {
    expect(isCancelledPickerError(new DOMException('blocked', 'SecurityError'))).toBe(false);
    expect(isCancelledPickerError(new Error('nope'))).toBe(false);
    expect(isCancelledPickerError(undefined)).toBe(false);
  });
});
