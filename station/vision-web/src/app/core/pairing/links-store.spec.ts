import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { LinksStore } from './links-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { LiveStore, type LiveConnectionState } from '../live/live-store';
import { PollScheduler } from '../poll-scheduler';
import type { LinkGroupResponse, LinkView } from '../api/models';

/** Lets the fire-and-forget promise chain inside `track()`/`pin()`/etc settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(overrides: Partial<Record<'getAssetLinks' | 'pinAssetLink' | 'releaseAssetLinkPin', ReturnType<typeof vi.fn>>> = {}) {
  return {
    getAssetLinks: overrides.getAssetLinks ?? vi.fn().mockResolvedValue(group('a-1')),
    pinAssetLink: overrides.pinAssetLink ?? vi.fn().mockResolvedValue(group('a-1')),
    releaseAssetLinkPin: overrides.releaseAssetLinkPin ?? vi.fn().mockResolvedValue(group('a-1')),
  };
}

function stubToasts() {
  return { error: vi.fn(), info: vi.fn(), ok: vi.fn(), warning: vi.fn(), notification: vi.fn() };
}

/** Mirrors `geo-store.spec.ts#stubLiveStore` exactly, narrowed to the links topic surface. */
function stubLiveStore(initialState: LiveConnectionState = 'closed') {
  const stateSignal = signal<LiveConnectionState>(initialState);
  const perAsset = new Map<string, ReturnType<typeof signal<LinkGroupResponse | undefined>>>();
  const signalFor = (assetId: string) => {
    let existing = perAsset.get(assetId);
    if (existing === undefined) {
      existing = signal<LinkGroupResponse | undefined>(undefined);
      perAsset.set(assetId, existing);
    }
    return existing;
  };
  return {
    connectionState: stateSignal.asReadonly(),
    linksFor: vi.fn((assetId: string) => signalFor(assetId)),
    trackLinks: vi.fn(),
    untrackLinks: vi.fn(),
    setState: (state: LiveConnectionState) => stateSignal.set(state),
    pushResult: (assetId: string, result: LinkGroupResponse) => signalFor(assetId).set(result),
  };
}

/** Captures every `PollScheduler.schedule` registration so a test can assert on/off without real timers. */
function stubScheduler() {
  const calls: { periodMs: number; stop: ReturnType<typeof vi.fn> }[] = [];
  const schedule = vi.fn((periodMs: number) => {
    const stop = vi.fn();
    calls.push({ periodMs, stop });
    return stop;
  });
  const lastFor = (periodMs: number) => [...calls].reverse().find((call) => call.periodMs === periodMs);
  return { schedule, calls, lastFor };
}

function inject(
  api: ReturnType<typeof stubApi>,
  options: { live?: ReturnType<typeof stubLiveStore>; scheduler?: ReturnType<typeof stubScheduler>; toasts?: ReturnType<typeof stubToasts> } = {},
): LinksStore {
  const providers: unknown[] = [
    LinksStore,
    { provide: VisionApi, useValue: api },
    { provide: ToastService, useValue: options.toasts ?? stubToasts() },
  ];
  if (options.live) {
    providers.push({ provide: LiveStore, useValue: options.live });
  }
  if (options.scheduler) {
    providers.push({ provide: PollScheduler, useValue: options.scheduler });
  }
  TestBed.configureTestingModule({ providers });
  return TestBed.inject(LinksStore);
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
  return {
    assetId,
    links: [link()],
    activeLinkId: 'link-1',
    pinned: false,
    ...partial,
  };
}

describe('LinksStore', () => {
  it('polls the per-asset links route and exposes the group', async () => {
    const mine = group('a-1', { pinned: true });
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(mine) });

    const store = inject(api);
    store.track('a-1');
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(store.group()).toEqual(mine);
    store.reset();
  });

  it('silently degrades on an unrelated poll failure, keeping the last-known group', async () => {
    const mine = group('a-2');
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValueOnce(mine).mockRejectedValueOnce(new Error('network down')) });

    const store = inject(api);
    store.track('a-2');
    await flush();
    expect(store.group()).toEqual(mine);
  });

  it('reset() clears the group and stops polling', async () => {
    const mine = group('a-3');
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(mine) });

    const store = inject(api);
    store.track('a-3');
    await flush();
    expect(store.group()).toEqual(mine);

    store.reset();
    expect(store.group()).toBeUndefined();
  });

  // --- Backend-absent degrade (class doc: a 404 on this route can only mean "not mounted") --------

  it('sets disabled() on a 404, leaving group() undefined — never a blocked page or fabricated list', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });

    const store = inject(api);
    expect(store.disabled()).toBe(false);
    store.track('a-404');
    await flush();

    expect(store.disabled()).toBe(true);
    expect(store.group()).toBeUndefined();
    store.reset();
  });

  it('leaves disabled() false for an unrelated poll failure', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new Error('network down')) });

    const store = inject(api);
    store.track('a-5');
    await flush();

    expect(store.disabled()).toBe(false);
    store.reset();
  });

  it('reset() clears disabled() back to false for the next track() session', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });

    const store = inject(api);
    store.track('a-6');
    await flush();
    expect(store.disabled()).toBe(true);

    store.reset();
    expect(store.disabled()).toBe(false);
  });

  // --- LiveStore projection (§3.4 "whole group snapshot, never a diff") ---------------------------

  it('subscribes live when LiveStore is open, reading straight from linksFor(assetId) — latest-wins', () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(group('a-9')) });
    const live = stubLiveStore('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-9');

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-9'); // the seed read — see Defect A
    expect(scheduler.lastFor(5_000)).toBeUndefined();

    const first = group('a-9', { activeLinkId: 'link-1' });
    live.pushResult('a-9', first);
    TestBed.tick();
    expect(store.group()).toEqual(first);

    const second = group('a-9', { activeLinkId: 'link-2', links: [link({ id: 'link-2', label: 'Ground radio' })] });
    live.pushResult('a-9', second);
    TestBed.tick();
    expect(store.group()).toEqual(second); // replaced, not accumulated
    store.reset();
  });

  // --- Seed read on a fresh live session (Defect A — the panel never fetched on the live transport) -

  it('seeds group() from one REST read when live is already open at track() time', async () => {
    const seed = group('a-9', { activeLinkId: 'link-1', pinned: true });
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(seed) });
    const live = stubLiveStore('open');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    expect(store.group()).toBeUndefined();
    store.track('a-9');
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(store.group()).toEqual(seed);
    expect(scheduler.lastFor(5_000)).toBeUndefined(); // still no recurring poll while live
    store.reset();
  });

  it('a live snapshot that arrives before the seed read resolves is not clobbered by it', async () => {
    let resolveSeed!: (value: LinkGroupResponse) => void;
    const seedPromise = new Promise<LinkGroupResponse>((resolve) => {
      resolveSeed = resolve;
    });
    const api = stubApi({ getAssetLinks: vi.fn().mockReturnValue(seedPromise) });
    const live = stubLiveStore('open');

    const store = inject(api, { live });
    store.track('a-10');

    const pushed = group('a-10', { activeLinkId: 'live-first' });
    live.pushResult('a-10', pushed);
    TestBed.tick();
    expect(store.group()).toEqual(pushed);

    resolveSeed(group('a-10', { activeLinkId: 'seed-arrived-late' }));
    await flush();
    expect(store.group()).toEqual(pushed); // the live push already won; the late seed is discarded
    store.reset();
  });

  it('falls back to polling while LiveStore is not open, still subscribing for later', async () => {
    const api = stubApi();
    const live = stubLiveStore('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-11');
    await flush();

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-11');
    expect(scheduler.lastFor(5_000)).toBeDefined();
    store.reset();
  });

  it('switches from poll to live, stopping the poll, when LiveStore opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveStore('connecting');
    const scheduler = stubScheduler();

    const store = inject(api, { live, scheduler });
    store.track('a-12');
    await flush();
    const poll = scheduler.lastFor(5_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick();

    expect(poll?.stop).toHaveBeenCalledOnce();
    store.reset();
  });

  it('reset() releases the live subscription', () => {
    const api = stubApi();
    const live = stubLiveStore('open');

    const store = inject(api, { live });
    store.track('a-14');
    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-14');

    store.reset();
    expect(live.untrackLinks).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('re-tracking the same assetId is a no-op — subscribes live exactly once', () => {
    const api = stubApi();
    const live = stubLiveStore('open');

    const store = inject(api, { live });
    for (let i = 0; i < 5; i++) {
      store.track('a-17');
    }

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackLinks).not.toHaveBeenCalled();
    store.reset();
  });

  // --- Pin / release-pin (§3.4 — AUTO vs pinned election) ------------------------------------------

  it('pin() calls the API and applies the returned group', async () => {
    const updated = group('a-18', { pinned: true, activeLinkId: 'link-1' });
    const api = stubApi({ pinAssetLink: vi.fn().mockResolvedValue(updated) });

    const store = inject(api);
    store.track('a-18');
    await store.pin('a-18', 'link-1');

    expect(api.pinAssetLink).toHaveBeenCalledExactlyOnceWith('a-18', 'link-1');
    expect(store.group()).toEqual(updated);
    store.reset();
  });

  it('pin() toasts on failure rather than throwing', async () => {
    const toasts = stubToasts();
    const api = stubApi({ pinAssetLink: vi.fn().mockRejectedValue(new Error('boom')) });

    const store = inject(api, { toasts });
    store.track('a-19');
    await store.pin('a-19', 'link-1');

    expect(toasts.error).toHaveBeenCalledOnce();
    store.reset();
  });

  it('releasePin() calls the API and applies the returned group', async () => {
    const updated = group('a-20', { pinned: false });
    const api = stubApi({ releaseAssetLinkPin: vi.fn().mockResolvedValue(updated) });

    const store = inject(api);
    store.track('a-20');
    await store.releasePin('a-20');

    expect(api.releaseAssetLinkPin).toHaveBeenCalledExactlyOnceWith('a-20');
    expect(store.group()).toEqual(updated);
    store.reset();
  });

  it('releasePin() toasts on failure rather than throwing', async () => {
    const toasts = stubToasts();
    const api = stubApi({ releaseAssetLinkPin: vi.fn().mockRejectedValue(new Error('boom')) });

    const store = inject(api, { toasts });
    store.track('a-21');
    await store.releasePin('a-21');

    expect(toasts.error).toHaveBeenCalledOnce();
    store.reset();
  });

  // --- refreshNow (post-recovery-action immediate re-read) -----------------------------------------

  it('refreshNow() re-polls immediately while polling is the active transport', async () => {
    const first = group('a-22');
    const second = group('a-22', { pinned: true });
    const getAssetLinks = vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    const api = stubApi({ getAssetLinks });

    const store = inject(api);
    store.track('a-22');
    await flush();
    expect(store.group()).toEqual(first);

    await store.refreshNow();
    expect(getAssetLinks).toHaveBeenCalledTimes(2);
    expect(store.group()).toEqual(second);
    store.reset();
  });

  it('refreshNow() is a no-op while live is the active transport — beyond track()\'s own one-time seed read', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(group('a-23')) });
    const live = stubLiveStore('open');

    const store = inject(api, { live });
    store.track('a-23');
    await flush(); // let the seed read (Defect A) settle
    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-23');

    await store.refreshNow();
    expect(api.getAssetLinks).toHaveBeenCalledOnce(); // still just the seed — refreshNow() itself added nothing
    store.reset();
  });
});
