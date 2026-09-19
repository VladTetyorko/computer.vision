import { TestBed } from '@angular/core/testing';
import { provideMockActions } from '@ngrx/effects/testing';
import type { Action } from '@ngrx/store';
import { Store, provideState, provideStore } from '@ngrx/store';
import { ReplaySubject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { VisionApi } from '../../../core/api/vision-api';
import { PollScheduler } from '../../../core/poll-scheduler';
import { ToastService } from '../../../core/toast.service';
import { OnboardingPhotoBuffer } from '../onboarding-photo-buffer';
import { OnboardingApiActions, OnboardingPageActions } from './onboarding.actions';
import {
  createAsset$,
  discoveryStatusPoll$,
  loadExistingAssetsOnTarget$,
  notifyAssetReady$,
  notifyFailure$,
  testRow$,
  uploadAssetImage$,
  verifyRow$,
} from './onboarding.effects';
import { onboardingWizardFeature } from './onboarding.reducer';

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
  return { schedule, calls };
}

function setup(apiOverrides: Record<string, ReturnType<typeof vi.fn>> = {}, photoBufferOverrides: Partial<OnboardingPhotoBuffer> = {}) {
  const actions = new ReplaySubject<Action>(1);
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), notification: vi.fn() };
  const scheduler = stubScheduler();
  const api = {
    discoveryStatus: vi.fn().mockResolvedValue({ candidatesTotal: 0, sourcesOk: 0, sourcesTotal: 0 }),
    listAssets: vi.fn().mockResolvedValue([]),
    listCategories: vi.fn().mockResolvedValue([]),
    systemNetwork: vi.fn(),
    listDiscoveryInboxCandidates: vi.fn(),
    scan: vi.fn(),
    probeDevice: vi.fn(),
    probeVehicleCandidate: vi.fn(),
    listDevices: vi.fn().mockResolvedValue([]),
    createAsset: vi.fn(),
    updateAsset: vi.fn(),
    registerDevice: vi.fn(),
    assignDevice: vi.fn(),
    uploadAssetImage: vi.fn().mockResolvedValue(undefined),
    listUsers: vi.fn().mockResolvedValue([]),
    writeAssetParameter: vi.fn(),
    setAssetCustody: vi.fn(),
    ...apiOverrides,
  };
  const photoBuffer = { currentBlob: null, ...photoBufferOverrides };
  TestBed.configureTestingModule({
    providers: [
      provideMockActions(() => actions),
      provideStore(),
      provideState(onboardingWizardFeature),
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: scheduler },
      { provide: ToastService, useValue: toasts },
      { provide: OnboardingPhotoBuffer, useValue: photoBuffer },
    ],
  });
  const store = TestBed.inject(Store);
  return { actions, api, scheduler, toasts, store, photoBuffer };
}

describe('onboarding effects — discoveryStatusPoll$ (gated on step === "source")', () => {
  it('never fetches while a different step is active', async () => {
    const { api, store } = setup();
    store.dispatch(OnboardingPageActions.stepJumped({ step: 'identify' }));
    TestBed.runInInjectionContext(() => discoveryStatusPoll$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.discoveryStatus).not.toHaveBeenCalled();
  });

  it('fetches immediately on entering source, and schedules a repeating tick', async () => {
    const { api, scheduler, store } = setup();
    // already on 'source' — the slice's own initial step.
    TestBed.runInInjectionContext(() => discoveryStatusPoll$()).subscribe((action) => store.dispatch(action));
    await flush();

    expect(api.discoveryStatus).toHaveBeenCalledOnce();
    expect(scheduler.schedule).toHaveBeenCalledOnce();
  });

  it('stops fetching once the step leaves source', async () => {
    const { api, store } = setup();
    TestBed.runInInjectionContext(() => discoveryStatusPoll$()).subscribe((action) => store.dispatch(action));
    await flush();
    api.discoveryStatus.mockClear();

    store.dispatch(OnboardingPageActions.stepJumped({ step: 'identify' }));
    await flush();
    expect(api.discoveryStatus).not.toHaveBeenCalled();
  });
});

describe('onboarding effects — Prove chain', () => {
  it('testRow$ maps a probe result straight to Test Row Succeeded', async () => {
    const result = { ok: true, telemetryDetected: false, warnings: [] };
    const { actions, api } = setup({ probeDevice: vi.fn().mockResolvedValue(result) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => testRow$()).subscribe((a) => seen.push(a));

    const request = { protocol: 'rtsp', uri: 'rtsp://x' };
    actions.next(OnboardingPageActions.testRowRequested({ role: 'sight', request }));
    await flush();

    expect(api.probeDevice).toHaveBeenCalledExactlyOnceWith(request);
    expect(seen).toEqual([OnboardingApiActions.testRowSucceeded({ role: 'sight', request, result })]);
  });

  it('verifyRow$ chains a successful verify straight into the sysid-collision device list read', async () => {
    const profile = { firmware: 'ardupilot', sysid: 7 } as unknown as Parameters<typeof OnboardingApiActions.verifyRowSucceeded>[0]['result'];
    const { actions, api } = setup({
      probeVehicleCandidate: vi.fn().mockResolvedValue(profile),
      listDevices: vi.fn().mockResolvedValue([]),
    });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => verifyRow$()).subscribe((a) => seen.push(a));

    const request = { protocol: 'mavlink', uri: 'udp://x' };
    actions.next(OnboardingPageActions.verifyRowRequested({ role: 'sense', request }));
    await flush();

    expect(api.listDevices).toHaveBeenCalledOnce();
    expect(seen[0]).toEqual(OnboardingApiActions.verifyRowSucceeded({ role: 'sense', request, result: profile }));
    expect((seen[1] as ReturnType<typeof OnboardingApiActions.sysidCollisionChecked>).type).toBe(OnboardingApiActions.sysidCollisionChecked.type);
  });
});

/** Dispatches to both the real `Store` (what `concatLatestFrom` reads back, post-reducer) and the
 *  mocked `Actions` stream (what the effect actually listens on) — the two are separate channels
 *  under `provideMockActions`, mirroring `live.effects.spec.ts#dispatchBoth`. */
function dispatchBoth(store: Store, actions: ReplaySubject<Action>, action: Action) {
  store.dispatch(action);
  actions.next(action);
}

describe('onboarding effects — loadExistingAssetsOnTarget$ (load-once-on-demand guard)', () => {
  it('requests the list only the first time attachTargetChosen(existing) fires with an empty list', async () => {
    const { actions, store } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => loadExistingAssetsOnTarget$()).subscribe((a) => seen.push(a));

    dispatchBoth(store, actions, OnboardingPageActions.attachTargetChosen({ target: 'existing' }));
    await flush();
    store.dispatch(OnboardingApiActions.existingAssetsSucceeded({ assets: [{ assetId: 'a1' } as never] }));

    dispatchBoth(store, actions, OnboardingPageActions.attachTargetChosen({ target: 'existing' }));
    await flush();

    expect(seen).toEqual([OnboardingPageActions.existingAssetsRequested()]);
  });

  it('never requests it for the "new" target', async () => {
    const { actions, store } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => loadExistingAssetsOnTarget$()).subscribe((a) => seen.push(a));

    dispatchBoth(store, actions, OnboardingPageActions.attachTargetChosen({ target: 'new' }));
    await flush();
    expect(seen).toEqual([]);
  });
});

describe('onboarding effects — createAsset$', () => {
  it('maps a created asset straight to Create Asset Succeeded', async () => {
    const created = { assetId: 'a1', displayName: 'Falcon-2' };
    const { actions, api } = setup({ createAsset: vi.fn().mockResolvedValue(created) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => createAsset$()).subscribe((a) => seen.push(a));

    const request = { displayName: 'Falcon-2', category: 'multirotor', devices: [] };
    actions.next(OnboardingPageActions.createAssetRequested({ request }));
    await flush();

    expect(api.createAsset).toHaveBeenCalledExactlyOnceWith(request);
    expect(seen).toEqual([OnboardingApiActions.createAssetSucceeded({ assetId: 'a1', displayName: 'Falcon-2' })]);
  });

  it('reports Create Asset Failed on a rejected request', async () => {
    const { actions } = setup({ createAsset: vi.fn().mockRejectedValue(new Error('boom')) });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => createAsset$()).subscribe((a) => seen.push(a));

    actions.next(OnboardingPageActions.createAssetRequested({ request: { displayName: 'x', category: 'y', devices: [] } }));
    await flush();

    expect(seen).toHaveLength(1);
    expect((seen[0] as ReturnType<typeof OnboardingApiActions.createAssetFailed>).type).toBe(OnboardingApiActions.createAssetFailed.type);
  });
});

describe('onboarding effects — uploadAssetImage$ (reads the Blob off OnboardingPhotoBuffer, never off the action)', () => {
  it('degrades to a no-op success when no blob is buffered', async () => {
    const { actions, api } = setup();
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => uploadAssetImage$()).subscribe((a) => seen.push(a));

    actions.next(OnboardingPageActions.uploadAssetImageRequested({ assetId: 'a1', displayName: 'Falcon-2' }));
    await flush();

    expect(api.uploadAssetImage).not.toHaveBeenCalled();
    expect(seen).toEqual([OnboardingApiActions.uploadAssetImageSucceeded({ displayName: 'Falcon-2' })]);
  });

  it('uploads the buffered blob when present', async () => {
    const blob = new Blob(['x']);
    const { actions, api } = setup({}, { currentBlob: blob });
    const seen: unknown[] = [];
    TestBed.runInInjectionContext(() => uploadAssetImage$()).subscribe((a) => seen.push(a));

    actions.next(OnboardingPageActions.uploadAssetImageRequested({ assetId: 'a1', displayName: 'Falcon-2' }));
    await flush();

    expect(api.uploadAssetImage).toHaveBeenCalledExactlyOnceWith('a1', blob);
    expect(seen).toEqual([OnboardingApiActions.uploadAssetImageSucceeded({ displayName: 'Falcon-2' })]);
  });
});

describe('onboarding effects — toasts', () => {
  it('notifyFailure$ toasts every listed mutation failure', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyFailure$()).subscribe();

    actions.next(OnboardingApiActions.createAssetFailed({ error: 'create boom' }));
    expect(toasts.error).toHaveBeenCalledWith('create boom');

    actions.next(OnboardingApiActions.existingAssetsFailed({ error: 'list boom' }));
    expect(toasts.error).toHaveBeenCalledWith('list boom');
  });

  it('notifyAssetReady$ fires the same "is ready" toast off either terminal step', () => {
    const { actions, toasts } = setup();
    TestBed.runInInjectionContext(() => notifyAssetReady$()).subscribe();

    actions.next(OnboardingPageActions.sysidStepEntered({ assetId: 'a1', displayName: 'Falcon-2', prefillSysidValue: 7 }));
    expect(toasts.ok).toHaveBeenCalledWith('"Falcon-2" is ready.');

    actions.next(OnboardingPageActions.handoverStepEntered({ assetId: 'a2', displayName: 'Rover-1' }));
    expect(toasts.ok).toHaveBeenCalledWith('"Rover-1" is ready.');
  });
});
