import { Injectable } from '@angular/core';
import { IMPROV_SERIAL_BAUD_RATE } from './improv-serial-protocol';

/**
 * The narrow surface this feature needs from a real Web Serial port — small enough to fake in unit
 * tests without touching real hardware or the whatwg Streams API at all. `provisioning-facade.ts` is
 * the only file that talks to this interface; every protocol decision lives in
 * `improv-serial-protocol.ts`, which never imports this file.
 */
export interface ImprovSerialConnection {
  /** Registers a raw-byte listener, called once per chunk in arrival order for as long as the
   *  connection stays open. Returns an unsubscribe function. */
  onData(listener: (chunk: Uint8Array) => void): () => void;
  /** Fires once if the device goes away on its own (unplugged, powered off) — never fires as a
   *  result of this page's own {@link close}. */
  onDisconnect(listener: () => void): () => void;
  /** Writes one complete, already-framed Improv packet. */
  write(frame: Uint8Array): Promise<void>;
  /** Releases the reader/writer locks and closes the underlying port. Idempotent — safe to call
   *  more than once (component destroy racing an explicit Disconnect click both call it). */
  close(): Promise<void>;
}

/** The minimal subset of the real (unbundled — see this file's own top comment)
 *  `navigator.serial`/`SerialPort` Web Serial API this file depends on. */
interface RawSerialPort {
  readonly readable: ReadableStream<Uint8Array> | null;
  readonly writable: WritableStream<Uint8Array> | null;
  open(options: { baudRate: number }): Promise<void>;
  close(): Promise<void>;
}

interface RawSerial {
  requestPort(): Promise<RawSerialPort>;
}

/** `navigator.serial` isn't in this project's TypeScript DOM lib (checked: TS 5.9's `lib.dom.d.ts`
 *  has no `Serial`/`SerialPort` — the Web Serial API is Chromium-only and never shipped a standard
 *  lib entry). Declared locally rather than augmenting the global `Navigator` interface, so this
 *  file can't collide with a future real declaration landing in `lib.dom.d.ts` or another module. */
type NavigatorWithSerial = Navigator & { readonly serial?: RawSerial };

class NavigatorSerialConnection implements ImprovSerialConnection {
  private reader?: ReadableStreamDefaultReader<Uint8Array>;
  private readonly dataListeners = new Set<(chunk: Uint8Array) => void>();
  private readonly disconnectListeners = new Set<() => void>();
  private closing = false;
  private readonly readLoop: Promise<void>;

  constructor(private readonly port: RawSerialPort) {
    this.readLoop = this.runReadLoop();
  }

  private async runReadLoop(): Promise<void> {
    if (!this.port.readable) {
      return;
    }
    this.reader = this.port.readable.getReader();
    try {
      while (true) {
        const { value, done } = await this.reader.read();
        if (done) {
          break;
        }
        if (value && value.length > 0) {
          for (const listener of this.dataListeners) {
            listener(value);
          }
        }
      }
    } catch {
      // A read error (device unplugged mid-read, USB link dropped) ends the loop the same as a
      // clean `done` — surfaced as a disconnect below, never thrown into the caller.
    } finally {
      try {
        this.reader?.releaseLock();
      } catch {
        // Already released (e.g. `close()` cancelled the reader concurrently) — nothing to do.
      }
      this.reader = undefined;
      if (!this.closing) {
        for (const listener of this.disconnectListeners) {
          listener();
        }
      }
    }
  }

  onData(listener: (chunk: Uint8Array) => void): () => void {
    this.dataListeners.add(listener);
    return () => this.dataListeners.delete(listener);
  }

  onDisconnect(listener: () => void): () => void {
    this.disconnectListeners.add(listener);
    return () => this.disconnectListeners.delete(listener);
  }

  async write(frame: Uint8Array): Promise<void> {
    if (!this.port.writable) {
      throw new Error('The device port is not writable.');
    }
    const writer = this.port.writable.getWriter();
    try {
      await writer.write(frame);
    } finally {
      try {
        writer.releaseLock();
      } catch {
        // Ignored — mirrors the reference Improv SDK's own best-effort release.
      }
    }
  }

  async close(): Promise<void> {
    if (this.closing) {
      return;
    }
    this.closing = true;
    try {
      await this.reader?.cancel();
    } catch {
      // Ignored — the port may already be gone.
    }
    await this.readLoop.catch(() => undefined);
    try {
      await this.port.close();
    } catch {
      // Ignored — closing an already-closed/unplugged port is not this caller's problem.
    }
    this.dataListeners.clear();
    this.disconnectListeners.clear();
  }
}

/**
 * The one seam between this feature and the real browser. `providedIn: 'root'` resolves to the real
 * `navigator.serial` app-wide; a test replaces it wholesale (`{ provide: WebSerialGateway, useValue:
 * fakeGateway }`) so nothing in `provisioning-facade.spec.ts`/`provisioning-logic.spec.ts` needs
 * real hardware, a secure context, or a Chromium browser to run.
 */
@Injectable({ providedIn: 'root' })
export class WebSerialGateway {
  /** Web Serial exists only in Chromium-family browsers (Chrome/Edge/Opera/Brave/Arc — not Firefox,
   *  not Safari) and only in a secure context (HTTPS, or `localhost`/`127.0.0.1` during development).
   *  Checked live, never cached — the page reflects reality if it's reloaded in a different browser. */
  isSupported(): boolean {
    if (typeof navigator === 'undefined' || typeof window === 'undefined') {
      return false;
    }
    return 'serial' in navigator && window.isSecureContext;
  }

  /** Opens the browser's own device picker and, once the operator chooses a port, opens it at
   *  Improv's fixed baud rate. Rejects with the browser's own error (a cancelled picker throws
   *  `DOMException('NotFoundError')` — see `provisioning-logic.ts#describeSerialRequestError` for
   *  how the facade turns that into honest copy) — never call this without checking
   *  {@link isSupported} first. */
  async requestConnection(): Promise<ImprovSerialConnection> {
    const serial = (navigator as NavigatorWithSerial).serial;
    if (!serial) {
      throw new Error('Web Serial is not available in this browser.');
    }
    const port = await serial.requestPort();
    await port.open({ baudRate: IMPROV_SERIAL_BAUD_RATE });
    return new NavigatorSerialConnection(port);
  }
}
