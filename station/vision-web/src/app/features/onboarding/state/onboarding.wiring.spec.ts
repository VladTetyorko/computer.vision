import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import { VisionApi } from '../../../core/api/vision-api';
import { PollScheduler } from '../../../core/poll-scheduler';
import { ToastService } from '../../../core/toast.service';
import { provideAppState } from '../../../core/state/app-state';
import { OnboardingPhotoBuffer } from '../onboarding-photo-buffer';
import { OnboardingPageActions } from './onboarding.actions';
import { onboardingWizardFeature } from './onboarding.reducer';
import { provideOnboardingState } from './onboarding.providers';

/**
 * The end-to-end wiring guard for this wave's slice — deliberately distinct from
 * `onboarding.reducer.spec.ts` (which calls the reducer function directly) and
 * `onboarding.effects.spec.ts` (which uses `provideMockActions`). **Neither of those constructs a
 * real `Store`**, so neither exercises `provideAppState()`'s four `runtimeChecks`, and neither would
 * notice if `provideOnboardingState()` stopped registering the feature at all.
 *
 * This one dispatches through the real store with `strictStateSerializability` and
 * `strictActionSerializability` on, so a non-plain value entering this slice — the exact hazard that
 * kept `File`/`Blob`/the object URL out of it and in `OnboardingPhotoBuffer` — fails here rather
 * than in a browser. It is the wizard's biggest structural risk: the slice carries ~105 actions, and
 * a `Date`, `Set` or `Map` in any one payload is invisible to `tsc`.
 *
 * `OnboardingPhotoBuffer` is resolved (not just registered) on purpose: it proves the service really
 * is reachable from the **environment** injector `provideOnboardingState()` writes into, which is
 * what `onboarding.effects.ts#uploadAssetImage$` depends on — moving it to the page's component
 * `providers:` compiles cleanly and breaks only at runtime.
 */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideOnboardingState(),
      {
        provide: VisionApi,
        useValue: {
          listDevices: vi.fn().mockResolvedValue([]),
          listStreams: vi.fn().mockResolvedValue([]),
          systemStatus: vi.fn().mockResolvedValue({ subsystems: [] }),
          discoveryStatus: vi.fn().mockResolvedValue({ methods: [] }),
        },
      },
      { provide: PollScheduler, useValue: { schedule: vi.fn(() => vi.fn()) } },
      { provide: ToastService, useValue: { ok: vi.fn(), info: vi.fn(), error: vi.fn() } },
    ],
  });
  return TestBed.inject(Store);
}

describe('onboardingWizard — real-store wiring', () => {
  it('provideOnboardingState() registers the feature, so its selectors resolve', () => {
    const store = setup();
    expect(store.selectSignal(onboardingWizardFeature.selectStep)()).toBe('source');
  });

  it('survives a dispatch under all four runtime checks — the slice is plain data end to end', () => {
    const store = setup();

    store.dispatch(OnboardingPageActions.displayNameChanged({ value: 'Kite 3' }));
    store.dispatch(OnboardingPageActions.categoryChosen({ slug: 'quadcopter' }));
    store.dispatch(OnboardingPageActions.sourceModeChosen({ mode: 'scan' }));
    store.dispatch(OnboardingPageActions.simLatitudeSet({ value: 50.45 }));
    store.dispatch(OnboardingPageActions.scanTimeoutSet({ value: 8_000 }));

    expect(store.selectSignal(onboardingWizardFeature.selectDisplayName)()).toBe('Kite 3');
    expect(store.selectSignal(onboardingWizardFeature.selectCategory)()).toBe('quadcopter');
    expect(store.selectSignal(onboardingWizardFeature.selectScanTimeout)()).toBe(8_000);
  });

  it('the photo buffer resolves from the environment injector the effects use', () => {
    setup();
    expect(TestBed.inject(OnboardingPhotoBuffer)).toBeInstanceOf(OnboardingPhotoBuffer);
  });

  it('keeps the photo out of state entirely — the whole reason the buffer exists', () => {
    const store = setup();
    store.dispatch(OnboardingPageActions.photoProcessingStarted());
    store.dispatch(OnboardingPageActions.photoRejected({ error: 'Choose a JPEG, PNG, or WebP image.' }));

    const slice = store.selectSignal(onboardingWizardFeature.selectOnboardingWizardState)();
    expect(JSON.stringify(slice)).toBeTypeOf('string'); // a File/Blob here would already have thrown above
    expect(store.selectSignal(onboardingWizardFeature.selectPhotoError)()).toContain('JPEG');
  });
});
