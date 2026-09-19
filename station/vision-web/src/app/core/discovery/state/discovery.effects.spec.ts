import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { Store, provideState, provideStore } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { DiscoveryCandidate, DiscoverySource } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { LiveSocketActions } from '../../live/state/live.actions';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { DiscoveryApiActions, DiscoveryPageActions } from './discovery.actions';
import {
  attach$,
  attachCandidate$,
  dismiss$,
  notifyAttachCandidateSuccess$,
  notifyAttachSuccess$,
  notifyFailure$,
  notifySuccess$,
  poll$,
  refresh$,
  register$,
  restore$,
} from './discovery.effects';
import { discoveryInboxFeature } from './discovery.reducer';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

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

function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number, callback: () => void | Promise<void>) => {
    void callback;
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function setup(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}, scheduler = stubScheduler()) {
  const actions = new ReplaySubject<Action>(1);
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), notification: vi.fn() };
  const api = {
    listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [], sources: [] }),
    registerDiscoveryCandidate: vi.fn(),
    dismissDiscoveryCandidate: vi.fn(),
    restoreDiscoveryCandidate: vi.fn(),
    attachDiscoveryCandidate: vi.fn(),
    registerDevice: vi.fn(),
    assignDevice: vi.fn(),
    ...apiOverrides,
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(discoveryInboxFeature),
      provideState(liveFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
      { provide: ToastService, useValue: toasts },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, toasts, store };
}

describe('discovery effects — poll$ (the root-singleton demand gate)', () => {
  it('never fetches or schedules until the first activated()', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.listDiscoveryInboxCandidates).not.toHaveBeenCalled();
    expect(scheduler.schedule).not.toHaveBeenCalled();
  });

  it('activated() fetches once and schedules the poll; a second concurrent activated() does neither again', async () => {
    const { api, scheduler, store } = setup({
      listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate()], sources: [source()] }),
    });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(DiscoveryPageActions.activated());
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
    expect(scheduler.schedule).toHaveBeenCalledTimes(1);

    store.dispatch(DiscoveryPageActions.activated());
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
    expect(scheduler.schedule).toHaveBeenCalledTimes(1);
  });

  it('stops the poll only once every consumer has released, then refreshes again on reactivation', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(DiscoveryPageActions.activated());
    store.dispatch(DiscoveryPageActions.activated());
    await flush();
    const firstPoll = scheduler.lastFor(30_000);

    store.dispatch(DiscoveryPageActions.released());
    expect(firstPoll?.stop).not.toHaveBeenCalled();
    store.dispatch(DiscoveryPageActions.released());
    expect(firstPoll?.stop).toHaveBeenCalledOnce();

    store.dispatch(DiscoveryPageActions.activated());
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2);
  });

  it('reconciles exactly once on reconnect, and stays silent for as long as live holds (D1 acceptance criterion)', async () => {
    const { api, store } = setup({
      listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate()], sources: [source()] }),
    });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(DiscoveryPageActions.activated());
    await flush();

    store.dispatch(LiveSocketActions.opened());
    await flush();
    // (the first entry into 'live' already reconciled once here — not the segment under test)

    store.dispatch(LiveSocketActions.closed());
    await flush();
    // falling back to poll refreshes immediately too — also not the segment under test.
    api.listDiscoveryInboxCandidates.mockClear();

    store.dispatch(LiveSocketActions.opened());
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

    store.dispatch(DiscoveryPageActions.activated()); // a second concurrent consumer while already live
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1); // no further request
  });

  it('activating while already live does one initial GET, not zero, and never schedules the poll', async () => {
    const { api, scheduler, store } = setup();
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(LiveSocketActions.opened());
    store.dispatch(DiscoveryPageActions.activated());
    await flush();

    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
    expect(scheduler.schedule).not.toHaveBeenCalled();
  });

  it('sources changes only on the reconnect reconcile — the discovery SSE topic carries none (L2b)', async () => {
    const { api, store } = setup({
      listDiscoveryInboxCandidates: vi
        .fn()
        .mockResolvedValueOnce({ candidates: [], sources: [source({ status: 'OK' })] })
        .mockResolvedValueOnce({ candidates: [], sources: [source({ status: 'UNREACHABLE' })] }),
    });
    TestBed.runInInjectionContext(() => poll$()).subscribe((action) => store.dispatch(action));

    store.dispatch(DiscoveryPageActions.activated());
    await flush();
    expect(store.selectSignal(discoveryInboxFeature.selectSources)()).toEqual([source({ status: 'OK' })]);

    store.dispatch(LiveSocketActions.opened());
    await flush();
    expect(store.selectSignal(discoveryInboxFeature.selectSources)()).toEqual([source({ status: 'UNREACHABLE' })]);

    // A burst of live-only candidate deltas moves candidates and nothing else — no REST channel for
    // it (this reducer-level fold is exercised fully in discovery.reducer.spec.ts; this only proves
    // it never triggers a THIRD `poll$` fetch).
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'discovery', payload: { action: 'REPORTED', candidate: candidate({ id: 'live-1' }) } } }));
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2);
    expect(store.selectSignal(discoveryInboxFeature.selectSources)()).toEqual([source({ status: 'UNREACHABLE' })]);
  });
});

describe('discovery effects — refresh$', () => {
  it('fetches immediately, independent of activeConsumers', async () => {
    const { actions, api } = setup({ listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate()], sources: [] }) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => refresh$()).subscribe((a) => seen.push(a));

    actions.next(DiscoveryPageActions.refreshRequested());
    await flush();

    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledOnce();
    expect(seen).toEqual([DiscoveryApiActions.pollStarted(), DiscoveryApiActions.pollSucceeded({ candidates: [candidate()], sources: [] })]);
  });
});

describe('discovery effects — mutations', () => {
  it('register$ registers then re-polls (the "await this.refresh()" step)', async () => {
    const result = { assetId: 'asset-9', displayName: 'New rover', category: 'rover' };
    const { actions, api } = setup({
      registerDiscoveryCandidate: vi.fn().mockResolvedValue(result),
      listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate({ status: 'REGISTERED' })], sources: [] }),
    });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => register$()).subscribe((a) => seen.push(a));

    actions.next(DiscoveryPageActions.registerRequested({ id: 'cand-1', request: { displayName: 'New rover', category: 'rover' } }));
    await flush();

    expect(api.registerDiscoveryCandidate).toHaveBeenCalledExactlyOnceWith('cand-1', { displayName: 'New rover', category: 'rover' });
    expect(seen).toEqual([
      DiscoveryApiActions.registerSucceeded({ id: 'cand-1', result }),
      DiscoveryApiActions.pollStarted(),
      DiscoveryApiActions.pollSucceeded({ candidates: [candidate({ status: 'REGISTERED' })], sources: [] }),
    ]);
  });

  it('register$ reports Register Failed without ever re-polling on failure', async () => {
    const { actions, api } = setup({ registerDiscoveryCandidate: vi.fn().mockRejectedValue(new Error('boom')) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => register$()).subscribe((a) => seen.push(a));

    actions.next(DiscoveryPageActions.registerRequested({ id: 'cand-1', request: { displayName: 'x', category: 'y' } }));
    await flush();

    expect(seen).toHaveLength(1);
    expect((seen[0] as ReturnType<typeof DiscoveryApiActions.registerFailed>).id).toBe('cand-1');
    expect(api.listDiscoveryInboxCandidates).not.toHaveBeenCalled();
  });

  it('attach$ registers a device then assigns it, composing the toast payload from both calls', async () => {
    const { actions, api } = setup({
      registerDevice: vi.fn().mockResolvedValue({ id: 'dev-1' }),
      assignDevice: vi.fn().mockResolvedValue({ displayName: 'Rover 1' }),
    });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => attach$()).subscribe((a) => seen.push(a));

    actions.next(
      DiscoveryPageActions.attachRequested({ id: 'cand-1', deviceSpec: { name: 'Camera', protocol: 'rtsp', uri: 'rtsp://x', options: {} }, assetId: 'asset-1' }),
    );
    await flush();

    expect(api.registerDevice).toHaveBeenCalledExactlyOnceWith({ name: 'Camera', protocol: 'rtsp', uri: 'rtsp://x', options: {} });
    expect(api.assignDevice).toHaveBeenCalledExactlyOnceWith('asset-1', 'dev-1');
    expect(seen).toEqual([DiscoveryApiActions.attachSucceeded({ id: 'cand-1', deviceName: 'Camera', displayName: 'Rover 1' })]);
  });

  it('attachCandidate$ calls the atomic endpoint and reports the result', async () => {
    const updated = candidate({ status: 'REGISTERED' });
    const { actions, api } = setup({ attachDiscoveryCandidate: vi.fn().mockResolvedValue(updated) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => attachCandidate$()).subscribe((a) => seen.push(a));

    actions.next(DiscoveryPageActions.attachCandidateRequested({ id: 'cand-1', assetId: 'asset-1' }));
    await flush();

    expect(api.attachDiscoveryCandidate).toHaveBeenCalledExactlyOnceWith('cand-1', { assetId: 'asset-1' });
    expect(seen).toEqual([DiscoveryApiActions.attachCandidateSucceeded({ id: 'cand-1', result: updated })]);
  });

  it('dismiss$ and restore$ call their own endpoints and report the server-returned candidate', async () => {
    const dismissed = candidate({ status: 'DISMISSED' });
    const restored = candidate({ status: 'NEW' });
    const { actions, api } = setup({
      dismissDiscoveryCandidate: vi.fn().mockResolvedValue(dismissed),
      restoreDiscoveryCandidate: vi.fn().mockResolvedValue(restored),
    });
    const dismissSeen: unknown[] = [];
    const restoreSeen: unknown[] = [];
    TestBed.runInInjectionContext(() => dismiss$()).subscribe((a) => dismissSeen.push(a));
    TestBed.runInInjectionContext(() => restore$()).subscribe((a) => restoreSeen.push(a));

    actions.next(DiscoveryPageActions.dismissRequested({ id: 'cand-1' }));
    await flush();
    expect(dismissSeen).toEqual([DiscoveryApiActions.dismissSucceeded({ id: 'cand-1', result: dismissed })]);

    actions.next(DiscoveryPageActions.restoreRequested({ id: 'cand-1' }));
    await flush();
    expect(restoreSeen).toEqual([DiscoveryApiActions.restoreSucceeded({ id: 'cand-1', result: restored })]);
  });
});

describe('discovery effects — toasts', () => {
  it('notifyFailure$ toasts every mutation failure', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();

    actions.next(DiscoveryApiActions.registerFailed({ id: 'a', error: 'reg boom' }));
    actions.next(DiscoveryApiActions.dismissFailed({ id: 'a', error: 'dismiss boom' }));
    expect(toasts.error).toHaveBeenCalledWith('reg boom');
    expect(toasts.error).toHaveBeenCalledWith('dismiss boom');
  });

  it('notifySuccess$/notifyAttachSuccess$/notifyAttachCandidateSuccess$ each compose their own message', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifySuccess$()).subscribe();
    TestBed.runInInjectionContext(() => notifyAttachSuccess$()).subscribe();
    TestBed.runInInjectionContext(() => notifyAttachCandidateSuccess$()).subscribe();

    actions.next(DiscoveryApiActions.registerSucceeded({ id: 'a', result: { assetId: 'asset-1', displayName: 'Rover', category: 'rover' } }));
    expect(toasts.ok).toHaveBeenCalledWith('Added "Rover" to inventory.');

    actions.next(DiscoveryApiActions.attachSucceeded({ id: 'a', deviceName: 'Camera', displayName: 'Rover' }));
    expect(toasts.ok).toHaveBeenCalledWith('Attached "Camera" to "Rover".');

    actions.next(DiscoveryApiActions.attachCandidateSucceeded({ id: 'a', result: candidate({ name: 'Camera 1' }) }));
    expect(toasts.ok).toHaveBeenCalledWith('Attached "Camera 1" to the asset.');
  });
});
