import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { DiscoveryInboxStore } from './discovery-inbox-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type {
  DiscoveryCandidate,
  DiscoveryEventPayload,
  DiscoveryInboxResponse,
  DiscoverySource,
} from '../api/models';

/**
 * First spec for `DiscoveryInboxStore` (no prior file of its kind existed) — construction,
 * activate/release ref-counting (this store's own pre-existing `=== 1`/`stopPollingFn` naming, left
 * as-is per the class doc rather than churned to match the `core/map-data/**` stores' `> 1`/
 * `stopPollFn`), the `discovery` SSE topic's candidate-only fold, and the D1 live gate's frozen
 * reconnect acceptance criterion (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3/§5 L2) — plus
 * the L2b trap this store carries: `sources` is populated *only* by the poll/reconcile GET, never by
 * the SSE fold, so the reconcile-on-reconnect is the one thing keeping it from going stale forever
 * once live holds.
 */

function candidate(overrides: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'cand-1',
    method: 'onvif',
    name: 'Camera 1',
    address: '192.168.0.10',
    details: {},
    firstSeen: '2026-09-06T09:00:00Z',
    lastSeen: '2026-09-06T09:59:00Z',
    status: 'NEW',
    ...overrides,
  };
}

function source(overrides: Partial<DiscoverySource> = {}): DiscoverySource {
  return { id: 'onvif', status: 'OK', lastScanAt: '2026-09-06T09:59:00Z', ...overrides };
}

function inboxResponse(overrides: Partial<DiscoveryInboxResponse> = {}): DiscoveryInboxResponse {
  return { candidates: [], sources: [], ...overrides };
}

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listDiscoveryInboxCandidates: vi.fn().mockResolvedValue(inboxResponse()),
    registerDiscoveryCandidate: vi.fn(),
    dismissDiscoveryCandidate: vi.fn(),
    restoreDiscoveryCandidate: vi.fn(),
    attachDiscoveryCandidate: vi.fn(),
    registerDevice: vi.fn(),
    assignDevice: vi.fn(),
    ...overrides,
  };
}

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveFacade` doc comment. */
function stubLiveFacade() {
  const events = signal<readonly DiscoveryEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    discoveryEvents: events.asReadonly(),
    push: (incoming: readonly DiscoveryEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
}

function createInactive(api: ReturnType<typeof stubApi>) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  const live = stubLiveFacade();
  const scheduleFn = vi.fn().mockReturnValue(vi.fn());
  TestBed.configureTestingModule({
    providers: [
      DiscoveryInboxStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: { schedule: scheduleFn } },
      { provide: LiveFacade, useValue: live },
    ],
  });
  return { store: TestBed.inject(DiscoveryInboxStore), toasts, live, scheduleFn };
}

/** Lets the fire-and-forget promise chain inside `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('DiscoveryInboxStore', () => {
  describe('construction', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();

      expect(api.listDiscoveryInboxCandidates).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();
      expect(store.candidates()).toEqual([]);
      expect(store.sources()).toEqual([]);
    });
  });

  describe('activate/release', () => {
    it('activate() fetches once and schedules the poll; a second concurrent activate() does neither again', async () => {
      const api = stubApi({
        listDiscoveryInboxCandidates: vi
          .fn()
          .mockResolvedValue(inboxResponse({ candidates: [candidate()], sources: [source()] })),
      });
      const { store, scheduleFn } = createInactive(api);

      store.activate();
      await flush();
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
      expect(store.candidates()).toEqual([candidate()]);
      expect(store.sources()).toEqual([source()]);

      store.activate(); // a second concurrent consumer — no further fetch or schedule
      await flush();
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released, then refreshes again on reactivation', async () => {
      const api = stubApi();
      const stopFn = vi.fn();
      const { store, scheduleFn } = createInactive(api);
      scheduleFn.mockReturnValue(stopFn);

      store.activate();
      store.activate();
      await flush();
      store.release();
      expect(stopFn).not.toHaveBeenCalled();
      store.release();
      expect(stopFn).toHaveBeenCalledTimes(1);

      store.activate();
      await flush();
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { store } = createInactive(stubApi());
      expect(() => store.release()).not.toThrow();
    });
  });

  describe('the discovery SSE topic', () => {
    it('folds a live delta into candidates without waiting for the next poll', async () => {
      const api = stubApi();
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();

      live.push([{ action: 'REPORTED', candidate: candidate({ id: 'cand-2', name: 'New camera' }) }]);
      TestBed.tick();

      expect(store.candidates().map((c) => c.id)).toContain('cand-2');
      expect(store.candidates().find((c) => c.id === 'cand-2')?.name).toBe('New camera');
    });

    it('runs the fold unconditionally, even with no active consumer', () => {
      const api = stubApi();
      const { store, live } = createInactive(api);
      // deliberately never activate() — the fold is documented as running from construction alone.

      live.push([{ action: 'REPORTED', candidate: candidate() }]);
      TestBed.tick();

      expect(store.candidates()).toEqual([candidate()]);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({
        listDiscoveryInboxCandidates: vi
          .fn()
          .mockResolvedValue(inboxResponse({ candidates: [candidate()], sources: [source()] })),
      });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      // Falling back to polling refreshes immediately too (the D1 table's own
      // `>0 | false | live → refresh once, then start poll` row) — a separate, legitimate call,
      // also not the segment under test. Only now do we isolate "entering open".
      api.listDiscoveryInboxCandidates.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      store.activate(); // a second concurrent consumer while already live — no further request
      TestBed.tick();
      await flush();
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
    });

    it('a store that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listDiscoveryInboxCandidates: vi.fn().mockResolvedValue(inboxResponse()) });
      const { store, live, scheduleFn } = createInactive(api);
      live.connectionState.set('open');

      store.activate();
      await flush();

      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled();
    });

    /**
     * L2b, the trap this store carries (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5): the
     * `discovery` SSE topic only ever carries `DiscoveryEventPayload`, which has no `sources` field
     * at all — so once live is up and the poll has stopped, `sources` can *only* change on the one
     * reconcile GET a genuine reconnect (`open → closed → open`) triggers. This test drives exactly
     * that sequence and proves `sources` tracks the reconcile while a burst of live candidate deltas
     * lands alongside it and moves only `candidates`.
     */
    it('sources changes only on the reconnect reconcile — never via the live fold, even while candidates keep moving', async () => {
      const api = stubApi({
        listDiscoveryInboxCandidates: vi
          .fn()
          .mockResolvedValueOnce(inboxResponse({ sources: [source({ status: 'OK' })] })) // initial poll GET
          .mockResolvedValueOnce(inboxResponse({ sources: [source({ status: 'UNREACHABLE' })] })), // the reconnect reconcile
      });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();
      expect(store.sources()).toEqual([source({ status: 'OK' })]);

      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2); // the reconcile fired
      expect(store.sources()).toEqual([source({ status: 'UNREACHABLE' })]); // sources caught up, from that GET alone

      // Live now holds open — a burst of live-only candidate deltas must move `candidates` and
      // nothing else; `sources` has no SSE channel to update it at all.
      live.push([{ action: 'REPORTED', candidate: candidate({ id: 'cand-live-1' }) }]);
      live.push([{ action: 'REPORTED', candidate: candidate({ id: 'cand-live-2' }) }]);
      TestBed.tick();

      expect(store.candidates().map((c) => c.id)).toEqual(expect.arrayContaining(['cand-live-1', 'cand-live-2']));
      expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2); // still just the one reconcile — no REST for the live deltas
      expect(store.sources()).toEqual([source({ status: 'UNREACHABLE' })]); // unchanged — no channel carries a fresher answer
    });
  });
});
