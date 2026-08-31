import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import {
  ImprovCurrentState,
  ImprovErrorState,
  ImprovRpcCommand,
  IMPROV_STREAM_INITIAL_STATE,
  buildRequestCurrentStateFrame,
  buildSendWifiSettingsFrame,
  improvCurrentStateLabel,
  improvErrorMessage,
  readImprovBytes,
  type ImprovFrame,
  type ImprovStreamState,
} from './improv-serial-protocol';
import {
  canSubmitWifiSettings,
  describeSerialRequestError,
  deviceStateChipTone,
  isCancelledPickerError,
  type SerialSessionPhase,
} from './provisioning-logic';
import { WebSerialGateway, type ImprovSerialConnection } from './web-serial-gateway';

/** How long to wait for the device's first Current State frame after opening the port before telling
 *  the operator the handshake stalled — long enough for a slow USB-CDC enumeration, short enough that
 *  "this doesn't look like an Improv device" reads promptly rather than as a stuck spinner. A local UI
 *  timing constant, same precedent as `keyboard-rc-input.service.ts`'s `RAMP_MS`/`TICK_MS` — not a
 *  domain value, so it stays a documented constant here rather than moving to server config. */
const HANDSHAKE_TIMEOUT_MS = 3000;

/**
 * `ProvisioningPage`'s facade (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §4/§8, wave Z5) —
 * `/provision-wifi`, Wi-Fi-over-USB provisioning via the open Improv Serial standard
 * (https://www.improv-wifi.com/serial/). Pure frontend: no `VisionApi` call anywhere in this flow —
 * credentials go straight from this page to the device over the operator's own USB cable, and the
 * device is never told this platform's address. Once it's on Wi-Fi it announces itself and the
 * existing discovery inbox picks it up on its own (§2, §4 P1) — see `onboarding.html`'s entry link
 * for the one-line pointer shown before the operator leaves the wizard for this page.
 *
 * Owns three layers of state that change independently:
 * - {@link session} — the USB link's own lifecycle (idle → connecting → open, or the two failure
 *   exits `request-failed`/`link-lost`). Wraps {@link WebSerialGateway}, the only DOM-touching seam.
 * - {@link deviceState}/{@link deviceError} — what the device itself last reported, decoded by
 *   `improv-serial-protocol.ts` from whatever bytes {@link WebSerialGateway} hands this facade. Never
 *   fabricated: an unrecognized code renders its own raw value (`improvCurrentStateLabel`/
 *   `improvErrorMessage`'s own doc comments).
 * - {@link ssid}/{@link password}/{@link sending}/{@link redirectUrl} — this page's own form and the
 *   one RPC call it drives. **Never persisted** — plain signals, no `panel-state.ts` helper, no
 *   `localStorage` call anywhere in this file; the credentials live only as long as this facade does.
 *
 * `WebSerialGateway` is injected (not `VisionApi`/any `*Store` — `core/ui/architecture.spec.ts`'s
 * `ROUTED_PAGES` guard only allows those two, and a hardware gateway is neither) so
 * `provisioning-facade.spec.ts` swaps in a fake `ImprovSerialConnection` and never touches a real
 * port, a secure context, or a Chromium browser.
 */
@Injectable()
export class ProvisioningFacade {
  private readonly gateway = inject(WebSerialGateway);

  private connection: ImprovSerialConnection | undefined;
  private readonly unsubscribers: Array<() => void> = [];
  private streamState: ImprovStreamState = IMPROV_STREAM_INITIAL_STATE;
  private handshakeTimer: ReturnType<typeof setTimeout> | null = null;

  /** Whether the browser can speak Web Serial at all — checked live so a reload in a different
   *  browser/context reflects reality (`WebSerialGateway#isSupported`'s own doc comment). */
  readonly supported = this.gateway.isSupported();

  readonly session = signal<SerialSessionPhase>('idle');
  /** Set only alongside {@link session} `'request-failed'` — honest copy for whatever the browser's
   *  own `requestPort()`/`open()` rejected with (`provisioning-logic.ts#describeSerialRequestError`). */
  readonly sessionError = signal<string | undefined>(undefined);

  /** True from the moment the port opens until either the device's first frame arrives or
   *  {@link handshakeTimedOut} fires — drives the "Waiting for the device…" state. */
  readonly awaitingFirstState = signal(false);
  /** True once {@link HANDSHAKE_TIMEOUT_MS} elapses with no frame at all — most likely a non-Improv
   *  device, or one not yet running Improv-capable firmware. Does not close the port; the operator can
   *  keep waiting or disconnect. */
  readonly handshakeTimedOut = signal(false);

  readonly deviceState = signal<ImprovCurrentState | undefined>(undefined);
  readonly deviceError = signal<ImprovErrorState | undefined>(undefined);

  readonly ssid = signal('');
  readonly password = signal('');
  readonly passwordVisible = signal(false);
  readonly sending = signal(false);
  /** The redirect URL from the device's RPC Result, when it sent one — `undefined` means "provisioned
   *  with nothing to hand off to", not "unknown"; the Improv spec allows an empty string here. */
  readonly redirectUrl = signal<string | undefined>(undefined);

  readonly canSend = computed(() =>
    canSubmitWifiSettings({
      session: this.session(),
      deviceState: this.deviceState(),
      ssid: this.ssid(),
      sending: this.sending(),
    }),
  );

  readonly deviceStateLabel = computed(() => {
    const state = this.deviceState();
    return state === undefined ? undefined : improvCurrentStateLabel(state);
  });
  readonly deviceStateTone = computed(() => deviceStateChipTone(this.deviceState()));
  readonly deviceErrorMessage = computed(() => {
    const error = this.deviceError();
    return error === undefined ? undefined : improvErrorMessage(error);
  });
  readonly provisioned = computed(() => this.deviceState() === ImprovCurrentState.PROVISIONED);

  constructor() {
    inject(DestroyRef).onDestroy(() => void this.disconnect());
  }

  /** Opens the browser's own device picker and, once a port is chosen, opens it and probes for an
   *  Improv device. A no-op while already `connecting`/`open`. */
  async connect(): Promise<void> {
    if (this.session() === 'connecting' || this.session() === 'open') {
      return;
    }
    this.session.set('connecting');
    this.sessionError.set(undefined);
    this.resetDeviceState();

    let connection: ImprovSerialConnection;
    try {
      connection = await this.gateway.requestConnection();
    } catch (err) {
      if (isCancelledPickerError(err)) {
        this.session.set('idle');
      } else {
        this.session.set('request-failed');
        this.sessionError.set(describeSerialRequestError(err));
      }
      return;
    }

    this.connection = connection;
    this.unsubscribers.push(connection.onData((chunk) => this.onData(chunk)));
    this.unsubscribers.push(connection.onDisconnect(() => this.onLinkLost()));
    this.session.set('open');
    this.awaitingFirstState.set(true);

    try {
      await connection.write(buildRequestCurrentStateFrame());
      this.handshakeTimer = setTimeout(() => {
        if (this.awaitingFirstState()) {
          this.handshakeTimedOut.set(true);
        }
      }, HANDSHAKE_TIMEOUT_MS);
    } catch (err) {
      // The port opened but the very first write failed — treat it the same as never having
      // connected rather than leaving the operator staring at a "waiting for device" spinner. The
      // read loop's own `onDisconnect` may also fire independently; `disconnect()` is idempotent.
      this.session.set('request-failed');
      this.sessionError.set(describeSerialRequestError(err));
      await this.disconnect();
    }
  }

  /** Releases the reader/writer/port and returns to `idle`. Safe to call more than once (an explicit
   *  Disconnect click racing component destroy, or destroy after a link-lost). Clears every
   *  device/session signal — nothing about a previous device carries into the next connection. */
  async disconnect(): Promise<void> {
    this.clearHandshakeTimer();
    const connection = this.connection;
    this.connection = undefined;
    for (const unsubscribe of this.unsubscribers.splice(0)) {
      unsubscribe();
    }
    if (connection) {
      await connection.close();
    }
    this.session.set('idle');
    this.sessionError.set(undefined);
    this.resetDeviceState();
  }

  /** Sends the typed SSID/password as Improv RPC 0x01 — guarded by {@link canSend}; a stray call while
   *  it's false (e.g. a racing keyboard Enter) is a no-op rather than a thrown error. */
  async send(): Promise<void> {
    const connection = this.connection;
    if (!connection || !this.canSend()) {
      return;
    }
    this.sending.set(true);
    this.deviceError.set(undefined);
    this.redirectUrl.set(undefined);
    try {
      await connection.write(buildSendWifiSettingsFrame(this.ssid(), this.password()));
    } catch (err) {
      this.sending.set(false);
      this.sessionError.set(describeSerialRequestError(err));
    }
  }

  setSsid(value: string): void {
    this.ssid.set(value);
  }

  setPassword(value: string): void {
    this.password.set(value);
  }

  togglePasswordVisible(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  /** Disconnects and clears the form — "provision another device" after a success, or "start over"
   *  after a failure, without leaving the previous SSID/password sitting in memory. */
  async startOver(): Promise<void> {
    await this.disconnect();
    this.ssid.set('');
    this.password.set('');
    this.passwordVisible.set(false);
  }

  private resetDeviceState(): void {
    this.streamState = IMPROV_STREAM_INITIAL_STATE;
    this.awaitingFirstState.set(false);
    this.handshakeTimedOut.set(false);
    this.deviceState.set(undefined);
    this.deviceError.set(undefined);
    this.sending.set(false);
    this.redirectUrl.set(undefined);
  }

  private onData(chunk: Uint8Array): void {
    const step = readImprovBytes(this.streamState, chunk);
    this.streamState = step.state;
    if (step.frames.length > 0) {
      this.awaitingFirstState.set(false);
      this.clearHandshakeTimer();
    }
    for (const frame of step.frames) {
      this.handleFrame(frame);
    }
    // `step.failures` (bad checksum, unknown packet type, …) are silently skipped — the codec's own
    // doc comment on `readImprovBytes` explains why: pre-Improv boot-log chatter on the same wire is
    // expected noise, and a corrupted frame is still consumed for its declared length, so the parser
    // never wedges. Nothing here has an "honest error" to show for noise that isn't this operator's.
  }

  private handleFrame(frame: ImprovFrame): void {
    switch (frame.kind) {
      case 'currentState':
        this.deviceState.set(frame.state);
        break;
      case 'errorState':
        this.deviceError.set(frame.error === ImprovErrorState.NO_ERROR ? undefined : frame.error);
        if (frame.error !== ImprovErrorState.NO_ERROR) {
          this.sending.set(false);
        }
        break;
      case 'rpcResult':
        if (frame.command === ImprovRpcCommand.SEND_WIFI_SETTINGS) {
          this.sending.set(false);
          const url = frame.values[0];
          this.redirectUrl.set(url && url.length > 0 ? url : undefined);
        }
        break;
    }
  }

  private onLinkLost(): void {
    this.clearHandshakeTimer();
    this.connection = undefined;
    for (const unsubscribe of this.unsubscribers.splice(0)) {
      unsubscribe();
    }
    this.session.set('link-lost');
    this.sending.set(false);
  }

  private clearHandshakeTimer(): void {
    if (this.handshakeTimer !== null) {
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = null;
    }
  }
}
