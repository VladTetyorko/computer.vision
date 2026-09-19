import { EnvironmentInjector, Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import type { LinkGroupResponse, LinkView } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveFacade, type LiveConnectionState } from '../live/live-facade';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import { provideAppState } from '../state/app-state';
import { LinksFacade } from './links-facade';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(
  overrides: Partial<Record<'getAssetLinks' | 'pinAssetLink' | 'releaseAssetLinkPin', ReturnType<typeof vi.fn>>> = {},
) {
  return {
    getAssetLinks: overrides.getAssetLinks ?? vi.fn().mockResolvedValue(group('a-1')),
    pinAssetLink: overrides.pinAssetLink ?? vi.fn().mockResolvedValue(group('a-1')),
    releaseAssetLinkPin: overrides.releaseAssetLinkPin ?? vi.fn().mockResolvedValue(group('a-1')),
  };
}

function stubToasts() {
  return { error: vi.fn(), info: vi.fn(), ok: vi.fn(), warning: vi.fn(), notification: vi.fn() };
}

function stubLiveFacade(initialState: LiveConnectionState = 'closed') {
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

function setUpFacade(
  api: ReturnType<typeof stubApi>,
  options: {
    live?: ReturnType<typeof stubLiveFacade>;
    scheduler?: ReturnType<typeof stubScheduler>;
    toasts?: ReturnType<typeof stubToasts>;
  } = {},
): LinksFacade {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      LinksFacade,
      { provide: VisionApi, useValue: api },
      { provide: LiveFacade, useValue: options.live ?? stubLiveFacade() },
      { provide: PollScheduler, useValue: options.scheduler ?? stubScheduler() },
      { provide: ToastService, useValue: options.toasts ?? stubToasts() },
    ],
  });
  return TestBed.inject(LinksFacade);
}

describe('LinksFacade', () => {
  it('polls the per-asset links route and exposes the group', async () => {
    const mine = group('a-1', { pinned: true });
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(mine) });

    const facade = setUpFacade(api);
    facade.track('a-1');
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-1');
    expect(facade.group()).toEqual(mine);
  });

  it('silently degrades on an unrelated poll failure, keeping the last-known group', async () => {
    const mine = group('a-2');
    const api = stubApi({
      getAssetLinks: vi.fn().mockResolvedValueOnce(mine).mockRejectedValueOnce(new Error('network down')),
    });

    const facade = setUpFacade(api);
    facade.track('a-2');
    await flush();
    expect(facade.group()).toEqual(mine);
  });

  it('reset() clears the group and stops polling', async () => {
    const mine = group('a-3');
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(mine) });

    const facade = setUpFacade(api);
    facade.track('a-3');
    await flush();
    expect(facade.group()).toEqual(mine);

    facade.reset();
    expect(facade.group()).toBeUndefined();
  });

  it('sets disabled() on a 404, leaving group() undefined — never a blocked page or fabricated list', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });

    const facade = setUpFacade(api);
    expect(facade.disabled()).toBe(false);
    facade.track('a-404');
    await flush();

    expect(facade.disabled()).toBe(true);
    expect(facade.group()).toBeUndefined();
  });

  it('leaves disabled() false for an unrelated poll failure', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new Error('network down')) });

    const facade = setUpFacade(api);
    facade.track('a-5');
    await flush();

    expect(facade.disabled()).toBe(false);
  });

  it('reset() clears disabled() back to false for the next track() session', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 404 })) });

    const facade = setUpFacade(api);
    facade.track('a-6');
    await flush();
    expect(facade.disabled()).toBe(true);

    facade.reset();
    expect(facade.disabled()).toBe(false);
  });

  it('subscribes live when LiveFacade is open, reading straight from linksFor(assetId) — latest-wins', async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(group('a-9')) });
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-9');
    await flush(); // the Defect-A seed read

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(scheduler.lastFor(5_000)).toBeUndefined();

    const first = group('a-9', { activeLinkId: 'link-1' });
    live.pushResult('a-9', first);
    TestBed.tick();
    expect(facade.group()).toEqual(first);

    const second = group('a-9', { activeLinkId: 'link-2', links: [link({ id: 'link-2', label: 'Ground radio' })] });
    live.pushResult('a-9', second);
    TestBed.tick();
    expect(facade.group()).toEqual(second); // replaced, not accumulated
  });

  it('seeds group() from one REST read when live is already open at track() time', async () => {
    const seed = group('a-9', { activeLinkId: 'link-1', pinned: true });
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(seed) });
    const live = stubLiveFacade('open');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    expect(facade.group()).toBeUndefined();
    facade.track('a-9');
    await flush();

    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-9');
    expect(facade.group()).toEqual(seed);
    expect(scheduler.lastFor(5_000)).toBeUndefined();
  });

  it('a live snapshot that arrives before the seed read resolves is not clobbered by it', async () => {
    let resolveSeed!: (value: LinkGroupResponse) => void;
    const seedPromise = new Promise<LinkGroupResponse>((resolve) => {
      resolveSeed = resolve;
    });
    const api = stubApi({ getAssetLinks: vi.fn().mockReturnValue(seedPromise) });
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    facade.track('a-10');

    const pushed = group('a-10', { activeLinkId: 'live-first' });
    live.pushResult('a-10', pushed);
    TestBed.tick();
    expect(facade.group()).toEqual(pushed);

    resolveSeed(group('a-10', { activeLinkId: 'seed-arrived-late' }));
    await flush();
    expect(facade.group()).toEqual(pushed); // the live push already won; the late seed is discarded
  });

  it('falls back to polling while LiveFacade is not open, still subscribing for later', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-11');
    await flush();

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-11');
    expect(scheduler.lastFor(5_000)).toBeDefined();
  });

  it('switches from poll to live, stopping the poll, when LiveFacade opens mid-session', async () => {
    const api = stubApi();
    const live = stubLiveFacade('connecting');
    const scheduler = stubScheduler();

    const facade = setUpFacade(api, { live, scheduler });
    facade.track('a-12');
    await flush();
    const poll = scheduler.lastFor(5_000);
    expect(poll?.stop).not.toHaveBeenCalled();

    live.setState('open');
    TestBed.tick();
    await flush();

    expect(poll?.stop).toHaveBeenCalledOnce();
  });

  it('reset() releases the live subscription', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    facade.track('a-14');
    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-14');

    facade.reset();
    expect(live.untrackLinks).toHaveBeenCalledExactlyOnceWith('a-14');
  });

  it('re-tracking the same assetId is a no-op — subscribes live exactly once', () => {
    const api = stubApi();
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    for (let i = 0; i < 5; i++) {
      facade.track('a-17');
    }

    expect(live.trackLinks).toHaveBeenCalledExactlyOnceWith('a-17');
    expect(live.untrackLinks).not.toHaveBeenCalled();
  });

  it('pin() calls the API and applies the returned group', async () => {
    const updated = group('a-18', { pinned: true, activeLinkId: 'link-1' });
    const api = stubApi({ pinAssetLink: vi.fn().mockResolvedValue(updated) });

    const facade = setUpFacade(api);
    facade.track('a-18');
    await facade.pin('a-18', 'link-1');

    expect(api.pinAssetLink).toHaveBeenCalledExactlyOnceWith('a-18', 'link-1');
    expect(facade.group()).toEqual(updated);
  });

  it('pin() toasts on failure rather than throwing', async () => {
    const toasts = stubToasts();
    const api = stubApi({ pinAssetLink: vi.fn().mockRejectedValue(new Error('boom')) });

    const facade = setUpFacade(api, { toasts });
    facade.track('a-19');
    await facade.pin('a-19', 'link-1');

    expect(toasts.error).toHaveBeenCalledOnce();
  });

  it('releasePin() calls the API and applies the returned group', async () => {
    const updated = group('a-20', { pinned: false });
    const api = stubApi({ releaseAssetLinkPin: vi.fn().mockResolvedValue(updated) });

    const facade = setUpFacade(api);
    facade.track('a-20');
    await facade.releasePin('a-20');

    expect(api.releaseAssetLinkPin).toHaveBeenCalledExactlyOnceWith('a-20');
    expect(facade.group()).toEqual(updated);
  });

  it('releasePin() toasts on failure rather than throwing', async () => {
    const toasts = stubToasts();
    const api = stubApi({ releaseAssetLinkPin: vi.fn().mockRejectedValue(new Error('boom')) });

    const facade = setUpFacade(api, { toasts });
    facade.track('a-21');
    await facade.releasePin('a-21');

    expect(toasts.error).toHaveBeenCalledOnce();
  });

  it('refreshNow() re-polls immediately while polling is the active transport', async () => {
    const first = group('a-22');
    const second = group('a-22', { pinned: true });
    const getAssetLinks = vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    const api = stubApi({ getAssetLinks });

    const facade = setUpFacade(api);
    facade.track('a-22');
    await flush();
    expect(facade.group()).toEqual(first);

    await facade.refreshNow();
    expect(getAssetLinks).toHaveBeenCalledTimes(2);
    expect(facade.group()).toEqual(second);
  });

  it("refreshNow() is a no-op while live is the active transport — beyond track()'s own one-time seed read", async () => {
    const api = stubApi({ getAssetLinks: vi.fn().mockResolvedValue(group('a-23')) });
    const live = stubLiveFacade('open');

    const facade = setUpFacade(api, { live });
    facade.track('a-23');
    await flush();
    expect(api.getAssetLinks).toHaveBeenCalledExactlyOnceWith('a-23');

    await facade.refreshNow();
    expect(api.getAssetLinks).toHaveBeenCalledOnce();
  });

  describe('two hosts never clobber each other', () => {
    it('tracking two hosts, pinning one, never rewrites the other', async () => {
      const api = stubApi({
        getAssetLinks: vi.fn((assetId: string) => Promise.resolve(group(assetId))),
        pinAssetLink: vi.fn().mockResolvedValue(group('a-1', { pinned: true })),
      });
      const live = stubLiveFacade('closed');
      TestBed.configureTestingModule({
        providers: [
          provideAppState(),
          { provide: VisionApi, useValue: api },
          { provide: LiveFacade, useValue: live },
          { provide: PollScheduler, useValue: stubScheduler() },
          { provide: ToastService, useValue: stubToasts() },
        ],
      });
      const commandInjector = Injector.create({ providers: [LinksFacade], parent: TestBed.inject(EnvironmentInjector) });
      const flyInjector = Injector.create({ providers: [LinksFacade], parent: TestBed.inject(EnvironmentInjector) });
      const command = commandInjector.get(LinksFacade);
      const fly = flyInjector.get(LinksFacade);

      command.track('a-1');
      fly.track('a-2');
      await flush();
      expect(command.group()).toEqual(group('a-1'));
      expect(fly.group()).toEqual(group('a-2'));

      await command.pin('a-1', 'link-1');

      expect(command.group()).toEqual(group('a-1', { pinned: true }));
      expect(fly.group()).toEqual(group('a-2')); // completely untouched by command's own pin
    });

    it('releasing one host leaves the other tracking exactly as it was', async () => {
      const api = stubApi({ getAssetLinks: vi.fn((assetId: string) => Promise.resolve(group(assetId))) });
      const live = stubLiveFacade('closed');
      TestBed.configureTestingModule({
        providers: [
          provideAppState(),
          { provide: VisionApi, useValue: api },
          { provide: LiveFacade, useValue: live },
          { provide: PollScheduler, useValue: stubScheduler() },
          { provide: ToastService, useValue: stubToasts() },
        ],
      });
      const commandInjector = Injector.create({ providers: [LinksFacade], parent: TestBed.inject(EnvironmentInjector) });
      const flyInjector = Injector.create({ providers: [LinksFacade], parent: TestBed.inject(EnvironmentInjector) });
      const command = commandInjector.get(LinksFacade);
      const fly = flyInjector.get(LinksFacade);

      command.track('a-1');
      fly.track('a-2');
      await flush();

      commandInjector.destroy();

      expect(live.untrackLinks).toHaveBeenCalledWith('a-1');
      expect(fly.group()).toEqual(group('a-2'));
    });
  });
});
