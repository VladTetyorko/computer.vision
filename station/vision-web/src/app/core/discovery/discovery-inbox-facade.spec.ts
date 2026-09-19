import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import type { DiscoveryCandidate } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import { provideAppState } from '../state/app-state';
import { provideDiscoveryState } from './state/discovery.providers';
import { DiscoveryInboxFacade } from './discovery-inbox-facade';

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

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [], sources: [] }),
    registerDiscoveryCandidate: vi.fn(),
    dismissDiscoveryCandidate: vi.fn(),
    restoreDiscoveryCandidate: vi.fn(),
    attachDiscoveryCandidate: vi.fn(),
    registerDevice: vi.fn(),
    assignDevice: vi.fn(),
    ...overrides,
  };
}

function stubScheduler() {
  const schedule = vi.fn().mockReturnValue(vi.fn());
  return { schedule };
}

function setUpFacade(api: ReturnType<typeof stubApi>, toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), notification: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideDiscoveryState(),
      DiscoveryInboxFacade,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: ToastService, useValue: toasts },
    ],
  });
  return { facade: TestBed.inject(DiscoveryInboxFacade), toasts };
}

describe('DiscoveryInboxFacade', () => {
  it('never fetches until the first activate()', async () => {
    const api = stubApi();
    const { facade } = setUpFacade(api);
    await flush();

    expect(api.listDiscoveryInboxCandidates).not.toHaveBeenCalled();
    expect(facade.candidates()).toEqual([]);
    expect(facade.sources()).toEqual([]);
  });

  it('activate() fetches once; release() eventually stops it, reactivation refetches', async () => {
    const api = stubApi({ listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate()], sources: [] }) });
    const { facade } = setUpFacade(api);

    facade.activate();
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(1);
    expect(facade.candidates()).toEqual([candidate()]);

    facade.release();
    facade.activate();
    await flush();
    expect(api.listDiscoveryInboxCandidates).toHaveBeenCalledTimes(2);
  });

  it('an unmatched release() is a defensive no-op, never going negative', () => {
    const { facade } = setUpFacade(stubApi());
    expect(() => facade.release()).not.toThrow();
  });

  it('register() returns the pointer on success and toasts', async () => {
    const result = { assetId: 'asset-9', displayName: 'New rover', category: 'rover' };
    const api = stubApi({ registerDiscoveryCandidate: vi.fn().mockResolvedValue(result) });
    const { facade, toasts } = setUpFacade(api);

    const outcome = await facade.register('cand-1', { displayName: 'New rover', category: 'rover' });

    expect(outcome).toEqual(result);
    expect(toasts.ok).toHaveBeenCalledWith('Added "New rover" to inventory.');
  });

  it('register() returns null and toasts an error on failure', async () => {
    const api = stubApi({ registerDiscoveryCandidate: vi.fn().mockRejectedValue(new Error('boom')) });
    const { facade, toasts } = setUpFacade(api);

    const outcome = await facade.register('cand-1', { displayName: 'x', category: 'y' });

    expect(outcome).toBeNull();
    expect(toasts.error).toHaveBeenCalledOnce();
  });

  it('attachCandidate() returns true on success', async () => {
    const api = stubApi({ attachDiscoveryCandidate: vi.fn().mockResolvedValue(candidate({ status: 'REGISTERED' })) });
    const { facade } = setUpFacade(api);

    const ok = await facade.attachCandidate('cand-1', 'asset-1');
    expect(ok).toBe(true);
  });

  it('attachCandidate() returns false and toasts on failure', async () => {
    const api = stubApi({ attachDiscoveryCandidate: vi.fn().mockRejectedValue(new Error('boom')) });
    const { facade, toasts } = setUpFacade(api);

    const ok = await facade.attachCandidate('cand-1', 'asset-1');
    expect(ok).toBe(false);
    expect(toasts.error).toHaveBeenCalledOnce();
  });

  it('attach() (the legacy two-step path) returns true and toasts on success', async () => {
    const api = stubApi({
      registerDevice: vi.fn().mockResolvedValue({ id: 'dev-1' }),
      assignDevice: vi.fn().mockResolvedValue({ displayName: 'Rover 1' }),
    });
    const { facade, toasts } = setUpFacade(api);

    const ok = await facade.attach('cand-1', { name: 'Camera', protocol: 'rtsp', uri: 'rtsp://x', options: {} }, 'asset-1');
    expect(ok).toBe(true);
    expect(toasts.ok).toHaveBeenCalledWith('Attached "Camera" to "Rover 1".');
  });

  it('dismiss() patches the candidate list with the server response, no toast', async () => {
    const dismissed = candidate({ status: 'DISMISSED' });
    const api = stubApi({
      listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate()], sources: [] }),
      dismissDiscoveryCandidate: vi.fn().mockResolvedValue(dismissed),
    });
    const { facade, toasts } = setUpFacade(api);
    facade.activate();
    await flush();

    await facade.dismiss('cand-1');
    expect(facade.candidates()).toEqual([dismissed]);
    expect(toasts.ok).not.toHaveBeenCalled();
  });

  it('restore() undoes a dismiss the same way', async () => {
    const restored = candidate({ status: 'NEW' });
    const api = stubApi({
      listDiscoveryInboxCandidates: vi.fn().mockResolvedValue({ candidates: [candidate({ status: 'DISMISSED' })], sources: [] }),
      restoreDiscoveryCandidate: vi.fn().mockResolvedValue(restored),
    });
    const { facade } = setUpFacade(api);
    facade.activate();
    await flush();

    await facade.restore('cand-1');
    expect(facade.candidates()).toEqual([restored]);
  });

  it('busyId() tracks exactly the in-flight candidate id and clears on settle', async () => {
    let resolveDismiss!: (value: DiscoveryCandidate) => void;
    const pending = new Promise<DiscoveryCandidate>((resolve) => {
      resolveDismiss = resolve;
    });
    const api = stubApi({ dismissDiscoveryCandidate: vi.fn().mockReturnValue(pending) });
    const { facade } = setUpFacade(api);

    const call = facade.dismiss('cand-1');
    await flush();
    expect(facade.busyId()).toBe('cand-1');

    resolveDismiss(candidate({ status: 'DISMISSED' }));
    await call;
    expect(facade.busyId()).toBeNull();
  });

  describe('two concurrent mutations on different candidates never resolve each other', () => {
    it('dismiss(A) and dismiss(B) in flight together each settle on their own outcome', async () => {
      let resolveA!: (value: DiscoveryCandidate) => void;
      let resolveB!: (value: DiscoveryCandidate) => void;
      const pendingA = new Promise<DiscoveryCandidate>((resolve) => {
        resolveA = resolve;
      });
      const pendingB = new Promise<DiscoveryCandidate>((resolve) => {
        resolveB = resolve;
      });
      const api = stubApi({
        dismissDiscoveryCandidate: vi.fn((id: string) => (id === 'cand-a' ? pendingA : pendingB)),
      });
      const { facade } = setUpFacade(api);

      const callA = facade.dismiss('cand-a');
      const callB = facade.dismiss('cand-b');

      // Resolve B first — A must NOT resolve from B's outcome (a same-type-different-id race).
      resolveB(candidate({ id: 'cand-b', status: 'DISMISSED' }));
      await callB;

      let aSettled = false;
      void callA.then(() => {
        aSettled = true;
      });
      await flush();
      expect(aSettled).toBe(false); // still waiting on its own outcome

      resolveA(candidate({ id: 'cand-a', status: 'DISMISSED' }));
      await callA;
      expect(aSettled).toBe(true);
    });
  });
});
