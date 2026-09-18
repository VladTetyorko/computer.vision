import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import type { LiveConnected, LiveEnvelope } from '../api/models';

/** Console prefix mirroring the old `LiveStore`'s own `[live]` tag. */
const LOG_PREFIX = '[live]';

/**
 * One lifecycle event off the raw `EventSource` this app's `GET /api/live` connection uses — the
 * non-serializable seam `core/live/state/live.effects.ts` translates into plain actions, so the
 * `live` slice itself only ever holds the resulting state, never the socket
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md §8).
 */
export type LiveGatewayEvent =
  | { readonly kind: 'open' }
  | { readonly kind: 'connected'; readonly connectionId: string; readonly topics: readonly string[] }
  | { readonly kind: 'message'; readonly envelope: LiveEnvelope }
  /** The browser's own native auto-retry after a transient drop — `readyState` cycles back to
   *  `CONNECTING` on its own, re-fetching the *same* URL this gateway's `open()` call built, so any
   *  topic ref-counted before the drop is automatically included in the reconnect for free. */
  | { readonly kind: 'retrying' }
  /** The browser gave up retrying for good — a non-2xx status or the wrong content type (e.g.
   *  `/api/live` 404ing because `vision.live.enabled=false`, or a pre-R-c backend). Always the last
   *  event this Observable ever emits before completing. */
  | { readonly kind: 'fatal' };

/**
 * Owns the one real `EventSource` `GET /api/live` uses (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * §8) — the non-serializable half of what used to be `LiveStore`. `live.effects.ts#connection$` is
 * the only consumer; every decision that used to live on the class (topic ref-counts, accumulated
 * payloads, connection state) now lives in the `live` slice instead, which is what makes this seam
 * fakeable in a spec with a plain object implementing this same two-method surface — no `EventSource`
 * construction at all needed to test an effect (jsdom has none, confirmed by grepping the installed
 * `jsdom` package — no matches, the same finding the old `LiveStore` class doc recorded).
 *
 * **Not unit-tested itself**, for the same reason the old class wasn't: jsdom has no `EventSource` to
 * construct one against, so `open()`'s own wiring is exercised only by code inspection plus
 * `live-fallback-logic.spec.ts`'s pure-logic coverage of every decision this file delegates to it —
 * the "browser-API-heavy class, pure logic extracted and tested, the class itself verified by
 * inspection" precedent `shared/player/player.ts` already established. `live.effects.spec.ts` covers
 * everything downstream of an event this class *could* emit, using a hand-written fake.
 */
@Injectable({ providedIn: 'root' })
export class LiveGateway {
  /** `false` in any environment without a global `EventSource` (jsdom, node) — mirrors `LiveStore`'s
   *  own one-time `available` check, now re-checked on every `open()` call instead of cached once
   *  (harmless: this never changes at runtime in a real browser). */
  isAvailable(): boolean {
    return typeof EventSource !== 'undefined';
  }

  /**
   * Opens exactly one `EventSource` for `topics` (a comma-joined `buildTopicsParam` result, possibly
   * empty) and reports its lifecycle as a cold `Observable` — subscribing opens the connection,
   * unsubscribing closes it (`live.effects.ts`'s own `reconnect()`/`stop()` handling, via `switchMap`
   * cancelling whatever attempt was previously running). Never completes on its own except right
   * after a `'fatal'` event — a transient drop instead cycles `'retrying'` → (native reconnect) →
   * `'open'` forever without this Observable ever completing, exactly like the raw `EventSource` it
   * wraps.
   */
  open(topics: string): Observable<LiveGatewayEvent> {
    return new Observable<LiveGatewayEvent>((subscriber) => {
      const url = topics.length > 0 ? `/api/live?topics=${encodeURIComponent(topics)}` : '/api/live';
      const source = new EventSource(url);
      source.addEventListener('connection', (event) => {
        try {
          const payload = JSON.parse((event as MessageEvent<string>).data) as LiveConnected;
          subscriber.next({ kind: 'connected', connectionId: payload.connectionId, topics: payload.topics });
        } catch (error) {
          console.warn(`${LOG_PREFIX} malformed connection event`, { error });
        }
      });
      source.onopen = () => subscriber.next({ kind: 'open' });
      source.onmessage = (event) => {
        try {
          const envelope = JSON.parse(event.data) as LiveEnvelope;
          subscriber.next({ kind: 'message', envelope });
        } catch (error) {
          console.warn(`${LOG_PREFIX} malformed envelope`, { error, raw: event.data });
        }
      };
      source.onerror = () => {
        if (source.readyState === EventSource.CLOSED) {
          subscriber.next({ kind: 'fatal' });
          subscriber.complete();
        } else {
          subscriber.next({ kind: 'retrying' });
        }
      };
      return () => source.close();
    });
  }
}
