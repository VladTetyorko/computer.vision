import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { LinkGroupResponse, LinkView } from '../../api/models';
import { VisionApi } from '../../api/vision-api';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { LinksApiActions, LinksLiveActions, LinksPageActions } from './links.actions';
import { notifyFailure$, pin$, releasePin$, refreshNow$, seed$, track$ } from './links.effects';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
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

function link(partial: Partial<LinkView> = {}): LinkView {
  return {
    id: 'link-1',
    carrier: 'UDP',
    serialRole: 'NONE',
    label: 'Wi-Fi',
    active: true,
    receiving: true,
    heartbeatAgeSeconds: 2,
    ...partial,
  };
}

function group(assetId: string, partial: Partial<LinkGroupResponse> = {}): LinkGroupResponse {
  return { assetId, links: [link()], activeLinkId: 'link-1', pinned: false, ...partial };
}

function setup(apiOverrides: Partial<Record<'getAssetLinks' | 'pinAssetLink' | 'releaseAssetLinkPin', ReturnType<typeof vi.fn>>> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const scheduler = stubScheduler();
  const toasts = { error: vi.fn(), info: vi.fn(), ok: vi.fn(), warning: vi.fn(), notification: vi.fn() };
  const api = {
    getAssetLinks: apiOverrides.getAssetLinks ?? vi.fn().mockResolvedValue(group('a-1')),
    pinAssetLink: apiOverrides.pinAssetLink ?? vi.fn().mockResolvedValue(group('a-1')),
    releaseAssetLinkPin: apiOverrides.releaseAssetLinkPin ?? vi.fn().mockResolvedValue(group('a-1')),
  };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
      { provide: ToastService, useValue: toasts },
    ],
  });
  return { actions, api, scheduler, toasts };
}

describe('links effects — track$', () => {
  it('polls immediately when the initial transport is poll, reporting Poll Started then Poll Succeeded', async () => {
    const mine = group('a-1', { pinned: true });
    const { actions } = setup({ getAssetLinks: vi.fn().mockResolvedValue(mine) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(seen).toEqual([
      LinksApiActions.pollStarted({ hostId: 'command' }),
      LinksApiActions.pollSucceeded({ hostId: 'command', group: mine }),
    ]);
  });

  it('registers a 5s poll cadence when starting in poll transport', async () => {
    const { actions, scheduler } = setup();
    TestBed.runInInjectionContext(() => track$()).subscribe();

    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();

    expect(scheduler.lastFor(5_000)).toBeDefined();
  });

  it('never polls at all when the initial transport is already live', async () => {
    const { actions, scheduler, api } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'live' }));
    await flush();

    expect(scheduler.lastFor(5_000)).toBeUndefined();
    expect(api.getAssetLinks).not.toHaveBeenCalled();
    expect(seen).toEqual([]);
  });

  it('stops the poll timer on a Transport Resolved flip to live, and resumes on a flip back', async () => {
    const { actions, scheduler, api } = setup();
    TestBed.runInInjectionContext(() => track$()).subscribe();

    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    const poll = scheduler.lastFor(5_000);

    actions.next(LinksLiveActions.transportResolved({ hostId: 'command', transport: 'live' }));
    await flush();
    expect(poll?.stop).toHaveBeenCalledOnce();

    actions.next(LinksLiveActions.transportResolved({ hostId: 'command', transport: 'poll' }));
    await flush();
    expect(api.getAssetLinks).toHaveBeenCalledTimes(2); // the initial read, plus the resume's immediate re-read
    expect(scheduler.lastFor(5_000)).toBeDefined();
  });

  it('reports Poll Disabled on a 404, and Poll Failed (not silence) on an ordinary failure — loading must still clear', async () => {
    const { actions: disabledActions } = setup({
      getAssetLinks: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })),
    });
    const disabledSeen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => disabledSeen.push(a));
    disabledActions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    expect(disabledSeen).toEqual([
      LinksApiActions.pollStarted({ hostId: 'command' }),
      LinksApiActions.pollDisabled({ hostId: 'command' }),
    ]);
  });

  it('reports Poll Failed on an ordinary error', async () => {
    const { actions } = setup({ getAssetLinks: vi.fn().mockRejectedValue(new Error('network down')) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));
    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    expect(seen).toEqual([
      LinksApiActions.pollStarted({ hostId: 'command' }),
      LinksApiActions.pollFailed({ hostId: 'command' }),
    ]);
  });

  it("two hosts never clobber each other's poll ticking", async () => {
    const { actions, api } = setup({
      getAssetLinks: vi.fn((assetId: string) => Promise.resolve(group(assetId))),
    });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => track$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.trackRequested({ hostId: 'command', assetId: 'a-1', initialTransport: 'poll' }));
    await flush();
    actions.next(LinksPageActions.trackRequested({ hostId: 'fly', assetId: 'a-2', initialTransport: 'poll' }));
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledWith('a-1');
    expect(api.getAssetLinks).toHaveBeenCalledWith('a-2');
    expect(seen).toContainEqual(LinksApiActions.pollSucceeded({ hostId: 'command', group: group('a-1') }));
    expect(seen).toContainEqual(LinksApiActions.pollSucceeded({ hostId: 'fly', group: group('a-2') }));
  });
});

describe('links effects — seed$', () => {
  it('fetches once for the Defect-A seed and reports the same outcome actions as a poll', async () => {
    const mine = group('a-1', { pinned: true });
    const { actions, api } = setup({ getAssetLinks: vi.fn().mockResolvedValue(mine) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => seed$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.seedRequested({ hostId: 'command', assetId: 'a-1' }));
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(seen).toEqual([
      LinksApiActions.pollStarted({ hostId: 'command' }),
      LinksApiActions.pollSucceeded({ hostId: 'command', group: mine }),
    ]);
  });
});

describe('links effects — refreshNow$', () => {
  it('fetches immediately, independent of any recurring cadence', async () => {
    const { actions, api } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => refreshNow$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.refreshNowRequested({ hostId: 'command', assetId: 'a-1' }));
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(seen).toEqual([
      LinksApiActions.pollStarted({ hostId: 'command' }),
      LinksApiActions.pollSucceeded({ hostId: 'command', group: group('a-1') }),
    ]);
  });
});

describe('links effects — pin$ / releasePin$', () => {
  it('pin$ calls the API and reports Pin Succeeded', async () => {
    const updated = group('a-1', { pinned: true });
    const { actions, api } = setup({ pinAssetLink: vi.fn().mockResolvedValue(updated) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => pin$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.pinRequested({ hostId: 'command', assetId: 'a-1', linkId: 'link-1' }));
    await flush();

    expect(api.pinAssetLink).toHaveBeenCalledExactlyOnceWith('a-1', 'link-1');
    expect(seen).toEqual([LinksApiActions.pinSucceeded({ hostId: 'command', group: updated })]);
  });

  it('pin$ reports Pin Failed with a described error rather than throwing', async () => {
    const { actions } = setup({ pinAssetLink: vi.fn().mockRejectedValue(new Error('boom')) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => pin$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.pinRequested({ hostId: 'command', assetId: 'a-1', linkId: 'link-1' }));
    await flush();

    expect(seen).toHaveLength(1);
    expect((seen[0] as ReturnType<typeof LinksApiActions.pinFailed>).hostId).toBe('command');
  });

  it('releasePin$ calls the API and reports Release Pin Succeeded', async () => {
    const updated = group('a-1', { pinned: false });
    const { actions, api } = setup({ releaseAssetLinkPin: vi.fn().mockResolvedValue(updated) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => releasePin$()).subscribe((a) => seen.push(a));

    actions.next(LinksPageActions.releasePinRequested({ hostId: 'command', assetId: 'a-1' }));
    await flush();

    expect(api.releaseAssetLinkPin).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(seen).toEqual([LinksApiActions.releasePinSucceeded({ hostId: 'command', group: updated })]);
  });
});

describe('links effects — notifyFailure$', () => {
  it('toasts on Pin Failed and Release Pin Failed — registered with dispatch: false (see org.effects.spec.ts precedent, which likewise only asserts the toast call)', async () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();

    actions.next(LinksApiActions.pinFailed({ hostId: 'command', error: 'boom 1' }));
    actions.next(LinksApiActions.releasePinFailed({ hostId: 'command', error: 'boom 2' }));

    expect(toasts.error).toHaveBeenCalledWith('boom 1');
    expect(toasts.error).toHaveBeenCalledWith('boom 2');
  });
});
