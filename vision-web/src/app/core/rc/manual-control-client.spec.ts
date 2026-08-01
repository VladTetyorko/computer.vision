import { EnvironmentInjector, createEnvironmentInjector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ManualControlClient } from './manual-control-client';
import { RcInputService } from './rc-input.service';
import { sendIntervalMs } from './manual-control-logic';

/** A minimal `WebSocket` test double — captures every `send()` call and lets a test drive
 * `onopen`/`onmessage`/`onclose`/`onerror` directly, standing in for the real socket lifecycle
 * `ManualControlClient` never gets to exercise against a real backend in this suite (R4, the
 * backend half of docs/RC-CONTROL-PHASE1-PLAN.md §4, lands separately). */
class MockWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  readyState = MockWebSocket.CONNECTING;
  readonly sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(readonly url: string) {
    instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  /** Mirrors the real `WebSocket#close()` — production code always nulls the handlers first
   * (`ManualControlClient#teardownSocket`), so this alone never re-notifies anything. */
  close(): void {
    this.readyState = MockWebSocket.CLOSED;
  }

  // --- test-only helpers, not part of the real WebSocket API -----------------------------------
  triggerOpen(): void {
    this.readyState = MockWebSocket.OPEN;
    this.onopen?.();
  }

  triggerMessage(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) });
  }

  /** An externally-driven drop (network/server) — unlike production `close()`, the handlers are
   * still live, so this actually invokes `onclose`, simulating "the socket dropped on its own". */
  triggerExternalClose(): void {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.();
  }
}

class FakeRcInputService {
  private readonly _connected = signal(true);
  private readonly _axes = signal<readonly number[]>([0, 0]);
  private readonly _buttons = signal<readonly number[]>([0]);
  readonly connected = this._connected.asReadonly();
  readonly axes = this._axes.asReadonly();
  readonly buttons = this._buttons.asReadonly();

  setConnected(value: boolean): void {
    this._connected.set(value);
  }

  setAxes(value: readonly number[]): void {
    this._axes.set(value);
  }

  setButtons(value: readonly number[]): void {
    this._buttons.set(value);
  }
}

let instances: MockWebSocket[] = [];

function lastSocket(): MockWebSocket {
  const ws = instances[instances.length - 1];
  if (!ws) {
    throw new Error('no MockWebSocket was constructed');
  }
  return ws;
}

/**
 * Builds `ManualControlClient` inside its own **child** `EnvironmentInjector` (mirrors what Angular
 * does for a component's own `providers` array, e.g. `rc-monitor.ts`'s) rather than registering it
 * directly on `TestBed`'s root testing module — so the "destroying the client" test below can
 * destroy exactly this child injector (simulating the RC panel unmounting) without tearing down
 * `TestBed`'s own root injector, which the test harness still needs for its automatic per-test
 * teardown.
 */
function create(fakeRc: FakeRcInputService): { client: ManualControlClient; injector: EnvironmentInjector } {
  TestBed.configureTestingModule({});
  const injector = createEnvironmentInjector(
    [ManualControlClient, { provide: RcInputService, useValue: fakeRc }],
    TestBed.inject(EnvironmentInjector),
  );
  return { client: injector.get(ManualControlClient), injector };
}

function engageAndOpen(client: ManualControlClient, assetId = 'asset-1'): MockWebSocket {
  client.engage(assetId);
  const ws = lastSocket();
  ws.triggerOpen();
  return ws;
}

function engageAndConfirm(client: ManualControlClient, rateHz = 30): MockWebSocket {
  const ws = engageAndOpen(client);
  ws.triggerMessage({ type: 'engaged', assetId: 'asset-1', rateHz, channelMap: [] });
  return ws;
}

beforeEach(() => {
  instances = [];
  vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
  vi.useFakeTimers();
  Object.defineProperty(document, 'hidden', { value: false, configurable: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('ManualControlClient', () => {
  it('opens a socket to /ws/manual-control and sends the engage frame once open', () => {
    const { client } = create(new FakeRcInputService());
    client.engage('asset-1');

    const ws = lastSocket();
    expect(ws.url).toBe('/ws/manual-control');
    expect(ws.sent).toEqual([]);
    expect(client.state()).toBe('engaging');

    ws.triggerOpen();
    expect(ws.sent).toEqual([JSON.stringify({ type: 'engage', assetId: 'asset-1' })]);
  });

  it('is a no-op while already engaging/engaged — never opens a second socket', () => {
    const { client } = create(new FakeRcInputService());
    engageAndOpen(client);
    expect(instances.length).toBe(1);

    client.engage('asset-1');
    expect(instances.length).toBe(1);
  });

  it('an engaged frame flips state, stores channelMap/rateHz, and starts streaming channels at the confirmed rate', () => {
    const fakeRc = new FakeRcInputService();
    fakeRc.setAxes([0.4, -0.2]);
    fakeRc.setButtons([1]);
    const { client } = create(fakeRc);
    const channelMap = [{ source: 'AXIS' as const, sourceIndex: 0, rcChannel: 1, label: 'Roll' }];

    const ws = engageAndOpen(client);
    ws.triggerMessage({ type: 'engaged', assetId: 'asset-1', rateHz: 25, channelMap });

    expect(client.state()).toBe('engaged');
    expect(client.channelMap()).toEqual(channelMap);
    expect(client.rateHz()).toBe(25);
    expect(ws.sent.length).toBe(1); // only the engage frame so far — the send loop hasn't ticked yet

    vi.advanceTimersByTime(sendIntervalMs(25));
    expect(ws.sent.length).toBe(2);
    const frame1 = JSON.parse(ws.sent[1]);
    expect(frame1).toMatchObject({ type: 'channels', axes: [0.4, -0.2], buttons: [1], seq: 1 });
    expect(typeof frame1.tSent).toBe('number');

    vi.advanceTimersByTime(sendIntervalMs(25));
    const frame2 = JSON.parse(ws.sent[2]);
    expect(frame2.seq).toBe(2);
  });

  it('an ack updates a rolling latencyMs computed as Date.now() minus tSent (not tServer minus tSent)', () => {
    vi.setSystemTime(0);
    const { client } = create(new FakeRcInputService());
    const ws = engageAndConfirm(client);

    vi.setSystemTime(1_000);
    ws.triggerMessage({ type: 'ack', seq: 1, tSent: 1_000 - 40, tServer: 1_000 - 10 });
    expect(client.latencyMs()).toBe(40);

    vi.setSystemTime(1_100);
    ws.triggerMessage({ type: 'ack', seq: 2, tSent: 1_100 - 20, tServer: 1_100 - 5 });
    expect(client.latencyMs()).toBe(30); // rolling average of [40, 20]
  });

  it('a denied frame sets state/reason and tears the socket down', () => {
    const { client } = create(new FakeRcInputService());
    const ws = engageAndOpen(client);
    ws.triggerMessage({ type: 'denied', code: 'NOT_COMMANDABLE', reason: 'No live source address.' });

    expect(client.state()).toBe('denied');
    expect(client.deniedReason()).toBe('No live source address.');
    expect(ws.readyState).toBe(MockWebSocket.CLOSED);
  });

  it('a released frame sets state released without tripping the watchdog flag', () => {
    const { client } = create(new FakeRcInputService());
    const ws = engageAndConfirm(client);
    ws.triggerMessage({ type: 'released', reason: 'EXPLICIT' });

    expect(client.state()).toBe('released');
    expect(client.watchdogTripped()).toBe(false);
    expect(ws.readyState).toBe(MockWebSocket.CLOSED);
  });

  it('a watchdog frame trips watchdogTripped and releases — the client must re-engage to resume', () => {
    const { client } = create(new FakeRcInputService());
    const ws = engageAndConfirm(client);
    ws.triggerMessage({ type: 'watchdog', timeoutMs: 300 });

    expect(client.state()).toBe('released');
    expect(client.watchdogTripped()).toBe(true);
    expect(ws.readyState).toBe(MockWebSocket.CLOSED);
  });

  it('release() sends a release frame, stops streaming, and closes the socket', () => {
    const { client } = create(new FakeRcInputService());
    const ws = engageAndConfirm(client);

    client.release();

    expect(ws.sent[ws.sent.length - 1]).toBe(JSON.stringify({ type: 'release' }));
    expect(client.state()).toBe('released');
    expect(ws.readyState).toBe(MockWebSocket.CLOSED);

    const sentCountAfterRelease = ws.sent.length;
    vi.advanceTimersByTime(1_000);
    expect(ws.sent.length).toBe(sentCountAfterRelease); // the send loop is stopped, not just paused
  });

  it('release() is a no-op while idle — no socket was ever opened', () => {
    const { client } = create(new FakeRcInputService());
    client.release();
    expect(client.state()).toBe('idle');
    expect(instances.length).toBe(0);
  });

  describe('deadman triggers', () => {
    it('the tab hiding releases an engaged session', () => {
      const { client } = create(new FakeRcInputService());
      const ws = engageAndConfirm(client);

      Object.defineProperty(document, 'hidden', { value: true, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));

      expect(client.state()).toBe('released');
      expect(ws.readyState).toBe(MockWebSocket.CLOSED);
    });

    it('the tab hiding while idle does nothing', () => {
      const { client } = create(new FakeRcInputService());

      Object.defineProperty(document, 'hidden', { value: true, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));

      expect(client.state()).toBe('idle');
    });

    it('the gamepad disconnecting releases an engaged session', () => {
      const fakeRc = new FakeRcInputService();
      const { client } = create(fakeRc);
      const ws = engageAndConfirm(client);

      fakeRc.setConnected(false);
      TestBed.tick();

      expect(client.state()).toBe('released');
      expect(ws.readyState).toBe(MockWebSocket.CLOSED);
    });

    it('an unexpected socket close (network/server drop) releases a live session', () => {
      const { client } = create(new FakeRcInputService());
      const ws = engageAndConfirm(client);

      ws.triggerExternalClose();

      expect(client.state()).toBe('released');
    });

    it('destroying the client (the RC panel closing) releases an engaged session', () => {
      const { client, injector } = create(new FakeRcInputService());
      const ws = engageAndConfirm(client);

      injector.destroy();

      expect(client.state()).toBe('released');
      expect(ws.sent[ws.sent.length - 1]).toBe(JSON.stringify({ type: 'release' }));
    });
  });
});
