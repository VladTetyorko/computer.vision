import { ImprovCurrentState } from './improv-serial-protocol';

/**
 * Pure UI-facing helpers for `/provision-wifi` (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * §4/§8, wave Z5). `provisioning-facade.ts` is the only caller — kept as a separate module rather
 * than folded into `improv-serial-protocol.ts` because these functions are about *this page's own
 * form/session state*, not the wire format (the codec file stays reusable by anything that ever
 * speaks Improv Serial; this one is specific to the one page that has a Connect button and an SSID
 * field).
 */

/** The device-link session's own lifecycle, independent of the Improv protocol state layered on top
 *  of it once a connection is open (`ImprovCurrentState` — the device's own provisioning phase). */
export type SerialSessionPhase =
  | 'idle' // never connected this page visit
  | 'connecting' // requestPort()/open() in flight
  | 'open' // port open, read loop running
  | 'link-lost' // the device vanished on its own (unplugged) — distinct from the operator's own Disconnect
  | 'request-failed'; // requestPort()/open() itself rejected (cancelled picker, permission denied, port busy, …)

/**
 * Whether the "Send" button may fire — session must be open, the device must have reported itself
 * `READY` (anything else — still booting, mid-provision, already provisioned, unavailable — means
 * sending would either be ignored or misleading), an SSID must be typed, and no send may already be
 * in flight. Password is deliberately not required — an open network is a real, if rare, case.
 */
export function canSubmitWifiSettings(params: {
  readonly session: SerialSessionPhase;
  readonly deviceState: ImprovCurrentState | undefined;
  readonly ssid: string;
  readonly sending: boolean;
}): boolean {
  return (
    params.session === 'open' &&
    params.deviceState === ImprovCurrentState.READY &&
    params.ssid.trim().length > 0 &&
    !params.sending
  );
}

/** Chip tone for the device's current Improv state — `neutral` (no modifier class) for the two
 *  steady/waiting states, `warn` while credentials are being tried (advisory: don't unplug), `ok`
 *  once provisioned. Never `danger` — a `STOPPED` device isn't a failure of this page, and an actual
 *  device-reported failure surfaces through the separate Error State banner, not this chip. */
export function deviceStateChipTone(state: ImprovCurrentState | undefined): 'neutral' | 'warn' | 'ok' {
  switch (state) {
    case ImprovCurrentState.PROVISIONING:
      return 'warn';
    case ImprovCurrentState.PROVISIONED:
      return 'ok';
    default:
      return 'neutral';
  }
}

/**
 * Turns whatever `WebSerialGateway#requestConnection()` rejected with into honest, specific copy —
 * never a bare "Error" or a raw `[object Object]`. A cancelled device picker is deliberately its own
 * case: the operator changed their mind, not a failure worth a red banner.
 */
export function describeSerialRequestError(err: unknown): string {
  if (err instanceof DOMException) {
    switch (err.name) {
      case 'NotFoundError':
        return 'No device was selected.';
      case 'SecurityError':
        return 'The browser blocked access to serial devices on this page.';
      case 'NetworkError':
        return 'Could not open the device — it may already be in use by another program or browser tab.';
      case 'InvalidStateError':
        return 'The device connection is no longer valid — try connecting again.';
      default:
        return err.message || 'Could not connect to the device.';
    }
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return 'Could not connect to the device.';
}

/** True for the one error worth swallowing outright (the operator closed the browser's own device
 *  picker without choosing anything) — `provisioning-facade.ts#connect` uses this to decide whether
 *  to show a banner at all, versus quietly returning to the idle state. */
export function isCancelledPickerError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'NotFoundError';
}
