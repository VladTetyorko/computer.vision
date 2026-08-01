import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { RcInputService } from './rc-input.service';
import type { ManualControlChannelBinding } from '../api/models';
import {
  DEFAULT_LATENCY_WINDOW,
  buildChannelsFrame,
  buildEngageFrame,
  buildReleaseFrame,
  computeLatencyMs,
  parseManualControlServerMessage,
  pushLatencySample,
  rollingAverageMs,
  sendIntervalMs,
} from './manual-control-logic';

/** Same-origin endpoint (docs/RC-CONTROL-PHASE1-PLAN.md §4) — a relative `WebSocket` URL resolves
 * against the document's own origin/scheme (ws:// under http, wss:// under https) per the
 * WebSocket constructor spec, the identical "no URL to configure" convention
 * `core/live/live-store.ts`'s `new EventSource('/api/live')` already established. Same-origin
 * session cookie rides the handshake automatically, same as every `fetch`/`EventSource` call in
 * this app — no bearer token, no `withCredentials` to set. */
const MANUAL_CONTROL_WS_URL = '/ws/manual-control';

export type ManualControlEngageState = 'idle' | 'engaging' | 'engaged' | 'denied' | 'released';

/**
 * `ManualControlClient` — the browser half of the SITL RC relay (docs/RC-CONTROL-PHASE1-PLAN.md §4,
 * R5). Opens one `WebSocket` per {@link engage} call, streams `RcInputService`'s live axes/buttons
 * back at the server-confirmed rate once `engaged`, and tracks glass-to-stick latency from each
 * `ack`. Frame encode/decode/latency math is pure (`manual-control-logic.ts`); this class is the
 * thin, browser-touching wiring around it — mirrors `rc-input.service.ts`'s own split.
 *
 * **Provided per host**, exactly like `RcInputService` (`features/fly/rc-monitor.ts`'s own
 * `providers` array carries both) — this class injects `RcInputService` directly, DI-sharing
 * whichever instance the host provided, the same "one page-scoped service injects another"
 * idiom `FlyFacade` uses for `TelemetryStore`/`DetectionsStore`/`WeatherStore`.
 *
 * <h2>The deadman — every trigger funnels through {@link release}</h2>
 * `release()` is idempotent (a no-op once {@link state} is already `'idle'`/`'denied'`/
 * `'released'`), so every deadman trigger the plan names can call it unconditionally with no extra
 * bookkeeping at the call site:
 * - **Explicit release** — the RC drawer's own RELEASE control (`rc-monitor.ts`).
 * - **The RC panel closing** — this class's own `DestroyRef.onDestroy`. The panel unmounts this
 *   provider's whole injector (`rc-monitor.ts`'s host is only ever in the DOM via
 *   `@if (isPanelOpen('rc'))`), so this class never needs to observe `isPanelOpen` itself — its
 *   own destruction *is* the "panel closed" signal.
 * - **The tab hiding** — a `visibilitychange` listener, wired/torn down alongside this class's own
 *   lifecycle.
 * - **The gamepad disconnecting** — a constructor `effect()` over `RcInputService.connected()`.
 * - **The socket dropping** (any cause — network, a server-side abrupt close) — the raw
 *   `WebSocket`'s own `onclose`/`onerror`.
 * - **The server's own `watchdog`/`released` frames** — the session is already released
 *   server-side; this class mirrors that by tearing its own socket down too (see the connection-
 *   reuse note below for why every release path — not just these two — closes the socket).
 *
 * <h2>One socket per session, deliberately — not one socket reused across sessions</h2>
 * §4's frozen contract allows a connection to `engage` again after a `released`/`watchdog` frame
 * on the *same* socket ("one active session **per connection**", not "per socket lifetime"). This
 * client instead closes the socket on every release path and opens a fresh one on the next
 * {@link engage}. Simpler state machine — a socket's lifetime always equals exactly one session, so
 * there is never a "stale session, live socket" case to reconcile — at the cost of one extra WS
 * handshake per re-engage. A deliberate v1 simplification, not a protocol requirement.
 *
 * <h2>No optimistic UI — except in the one direction where "optimistic" isn't the right word</h2>
 * Mirrors `flight-command-panel.ts`'s own rule for the *positive* direction: {@link state} only
 * ever reads `'engaged'` after the server's own `engaged` frame — never assumed from having sent
 * `engage`. The *negative* direction is different on purpose: every deadman path sets `'released'`
 * synchronous with this class giving up on the connection. That is not a guess about what the
 * server did — it is simply true the instant this browser stops transmitting, regardless of
 * whether the server's own `released` frame ever arrives (a closing tab may never see it).
 */
@Injectable()
export class ManualControlClient {
  private readonly rc = inject(RcInputService);

  private readonly _state = signal<ManualControlEngageState>('idle');
  private readonly _deniedReason = signal<string | undefined>(undefined);
  private readonly _latencyMs = signal<number | undefined>(undefined);
  private readonly _channelMap = signal<readonly ManualControlChannelBinding[] | undefined>(undefined);
  private readonly _rateHz = signal<number | undefined>(undefined);
  private readonly _watchdogTripped = signal(false);

  readonly state = this._state.asReadonly();
  readonly deniedReason = this._deniedReason.asReadonly();
  readonly latencyMs = this._latencyMs.asReadonly();
  readonly channelMap = this._channelMap.asReadonly();
  readonly rateHz = this._rateHz.asReadonly();
  readonly watchdogTripped = this._watchdogTripped.asReadonly();

  private ws: WebSocket | null = null;
  private sendTimer: ReturnType<typeof setInterval> | null = null;
  private seq = 0;
  private latencyWindow: readonly number[] = [];

  constructor() {
    document.addEventListener('visibilitychange', this.onVisibilityChange);

    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      this.release();
    });

    // Gamepad-disconnect deadman — see this class's own doc comment.
    effect(() => {
      if (!this.rc.connected() && this.isSessionLive()) {
        this.release();
      }
    });
  }

  private isSessionLive(): boolean {
    const state = this._state();
    return state === 'engaging' || state === 'engaged';
  }

  private readonly onVisibilityChange = (): void => {
    if (document.hidden && this.isSessionLive()) {
      this.release();
    }
  };

  /**
   * Explicit engage gesture — a no-op while a session is already engaging/engaged (call
   * {@link release} first). Opens a fresh `WebSocket` and sends the `engage` frame once it opens;
   * {@link state} moves to `'engaged'` only once the server's own `engaged` frame arrives.
   */
  engage(assetId: string): void {
    if (this.isSessionLive()) {
      return;
    }
    this.teardownSocket(); // defensive — guarantees no leaked prior socket before opening a new one.
    this._deniedReason.set(undefined);
    this._watchdogTripped.set(false);
    this._channelMap.set(undefined);
    this._rateHz.set(undefined);
    this._latencyMs.set(undefined);
    this.latencyWindow = [];
    this.seq = 0;
    this._state.set('engaging');

    const ws = new WebSocket(MANUAL_CONTROL_WS_URL);
    this.ws = ws;
    ws.onopen = () => ws.send(JSON.stringify(buildEngageFrame(assetId)));
    ws.onmessage = (event) => this.handleMessage(event.data as string);
    ws.onclose = () => this.handleSocketGone();
    ws.onerror = () => this.handleSocketGone();
  }

  /** Explicit release — also the common deadman path (see this class's own doc comment).
   * Idempotent: a no-op once nothing is engaging/engaged. */
  release(): void {
    if (!this.isSessionLive()) {
      return;
    }
    const ws = this.ws;
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(buildReleaseFrame()));
      } catch {
        // The socket is on its way down regardless — the teardown below still runs.
      }
    }
    this._state.set('released');
    this.teardownSocket();
  }

  private handleMessage(raw: string): void {
    const msg = parseManualControlServerMessage(raw);
    if (!msg) {
      return; // Malformed/unknown frame — defensively ignored, see `parseManualControlServerMessage`.
    }
    switch (msg.type) {
      case 'engaged':
        this._channelMap.set(msg.channelMap);
        this._rateHz.set(msg.rateHz);
        this._state.set('engaged');
        this.startSendLoop(msg.rateHz);
        break;
      case 'denied':
        this._deniedReason.set(msg.reason);
        this._state.set('denied');
        this.teardownSocket();
        break;
      case 'ack': {
        const latency = computeLatencyMs(msg.tSent, Date.now());
        this.latencyWindow = pushLatencySample(this.latencyWindow, latency, DEFAULT_LATENCY_WINDOW);
        this._latencyMs.set(rollingAverageMs(this.latencyWindow));
        break;
      }
      case 'released':
        this._state.set('released');
        this.teardownSocket();
        break;
      case 'watchdog':
        this._watchdogTripped.set(true);
        this._state.set('released');
        this.teardownSocket();
        break;
    }
  }

  /** The socket closed/errored without this class having asked for it — a deadman path in its own
   * right (network drop, server-side abrupt close). Unreachable after this class's own teardown
   * paths (`teardownSocket` nulls every handler, including this one, before calling `close()`), so
   * this only ever fires for a genuinely unexpected drop. */
  private handleSocketGone(): void {
    if (!this.ws) {
      return;
    }
    this.ws = null;
    this.stopSendLoop();
    if (this.isSessionLive()) {
      this._state.set('released');
    }
  }

  private startSendLoop(rateHz: number): void {
    this.stopSendLoop();
    this.sendTimer = setInterval(() => this.sendChannelsFrame(), sendIntervalMs(rateHz));
  }

  private stopSendLoop(): void {
    if (this.sendTimer != null) {
      clearInterval(this.sendTimer);
      this.sendTimer = null;
    }
  }

  /** One `channels` frame per tick while engaged — always the *current* `RcInputService` reading
   * (latest-wins is the server's own job per §4/the adapter's mailbox; this client just samples). */
  private sendChannelsFrame(): void {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN || this._state() !== 'engaged') {
      return;
    }
    this.seq += 1;
    const frame = buildChannelsFrame(this.rc.axes(), this.rc.buttons(), this.seq, Date.now());
    ws.send(JSON.stringify(frame));
  }

  private teardownSocket(): void {
    this.stopSendLoop();
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      try {
        ws.close();
      } catch {
        // Already closed/closing — nothing left to do.
      }
    }
  }
}
