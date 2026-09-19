import { describe, expect, it } from 'vitest';
import type { DiscoveryCandidate } from '../../../core/api/models';
import { emptyFitOutRows } from '../../../core/onboarding/fit-out-logic';
import { OnboardingApiActions, OnboardingPageActions } from './onboarding.actions';
import { initialOnboardingWizardState } from './onboarding.model';
import { onboardingWizardFeature } from './onboarding.reducer';

const { reducer } = onboardingWizardFeature;

function candidate(overrides: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'cand-1',
    method: 'mavlink',
    name: 'Rover-9',
    address: '192.168.0.20:14550',
    details: {},
    firstSeen: '2026-09-06T09:00:00Z',
    lastSeen: '2026-09-06T09:59:00Z',
    status: 'NEW',
    suggestedStreamProtocol: 'mavlink',
    suggestedStreamUri: 'udp://192.168.0.20:14550',
    ...overrides,
  };
}

describe('onboardingWizardFeature reducer', () => {
  it('starts on source, with both fit-out rows empty', () => {
    expect(initialOnboardingWizardState.step).toBe('source');
    expect(initialOnboardingWizardState.rows).toEqual(emptyFitOutRows());
    expect(initialOnboardingWizardState.preProvenRoles).toEqual([]);
  });

  describe('Identify fields', () => {
    it('displayNameChanged/categoryChosen/etc set their own field only', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.displayNameChanged({ value: 'Falcon-2' }));
      expect(state.displayName).toBe('Falcon-2');
      expect(state.registrationNumber).toBe('');
    });

    it('photoProcessingStarted/Accepted/Rejected/Removed drive the honest-degrade photoError field', () => {
      const started = reducer(initialOnboardingWizardState, OnboardingPageActions.photoProcessingStarted());
      expect(started.photoProcessing).toBe(true);
      const rejected = reducer(started, OnboardingPageActions.photoRejected({ error: 'Choose a JPEG, PNG, or WebP image.' }));
      expect(rejected).toEqual({ ...started, photoProcessing: false, photoError: 'Choose a JPEG, PNG, or WebP image.' });
      const accepted = reducer(rejected, OnboardingPageActions.photoAccepted());
      expect(accepted.photoError).toBeNull();
    });
  });

  describe('fit-out rows — preProvenRoles un-trusts on any edit', () => {
    it('rowUriSet/rowProtocolSelected/etc each clear preProvenRoles for that role only', () => {
      const seeded = { ...initialOnboardingWizardState, preProvenRoles: ['sight', 'sense'] as const };
      const state = reducer(seeded, OnboardingPageActions.rowUriSet({ role: 'sight', value: 'rtsp://x' }));
      expect(state.preProvenRoles).toEqual(['sense']);
      expect(state.rows.sight.uri).toBe('rtsp://x');
    });

    it('rowCleared resets the row to empty and always clears originCandidateId', () => {
      const seeded = {
        ...initialOnboardingWizardState,
        originCandidateId: 'cand-1',
        preProvenRoles: ['sight'] as const,
        rows: { ...initialOnboardingWizardState.rows, sight: { ...initialOnboardingWizardState.rows.sight, uri: 'rtsp://x', value: 'find' as const } },
      };
      const state = reducer(seeded, OnboardingPageActions.rowCleared({ role: 'sight' }));
      expect(state.rows.sight.uri).toBe('');
      expect(state.rows.sight.value).toBe('none');
      expect(state.originCandidateId).toBeNull();
      expect(state.preProvenRoles).toEqual([]);
    });
  });

  describe('sourceModeChosen', () => {
    it('"equipment" clears both rows to none and sets equipmentConfirmed', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.sourceModeChosen({ mode: 'equipment' }));
      expect(state.rows.sense.value).toBe('none');
      expect(state.rows.sight.value).toBe('none');
      expect(state.equipmentConfirmed).toBe(true);
      expect(state.sourceMode).toBe('equipment');
    });

    it('"passive" seeds sense→listen and sight→discover for any row not already resolved', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.sourceModeChosen({ mode: 'passive' }));
      expect(state.rows.sense).toMatchObject({ value: 'find', findMethod: 'listen' });
      expect(state.rows.sight).toMatchObject({ value: 'find', findMethod: 'discover' });
    });

    it('"passive" never overwrites a row already pre-proven from a discovery-candidate entrance', () => {
      const seeded = {
        ...initialOnboardingWizardState,
        preProvenRoles: ['sight'] as const,
        rows: { ...initialOnboardingWizardState.rows, sight: { ...initialOnboardingWizardState.rows.sight, value: 'find' as const, findMethod: 'register' as const, uri: 'rtsp://prefilled' } },
      };
      const state = reducer(seeded, OnboardingPageActions.sourceModeChosen({ mode: 'passive' }));
      expect(state.rows.sight.uri).toBe('rtsp://prefilled');
      expect(state.rows.sight.findMethod).toBe('register');
    });

    it('"manual" seeds both rows to register', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.sourceModeChosen({ mode: 'manual' }));
      expect(state.rows.sense).toMatchObject({ value: 'find', findMethod: 'register' });
      expect(state.rows.sight).toMatchObject({ value: 'find', findMethod: 'register' });
    });
  });

  describe('"Found nearby" feed → Confirm interstitial', () => {
    it('foundCandidateChosen with a resolvable suggestion prefills the row and jumps straight to confirm', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.foundCandidateChosen({ candidate: candidate() }));
      expect(state.step).toBe('confirm');
      expect(state.selectedCandidate).toEqual(candidate());
      expect(state.originCandidateId).toBe('cand-1');
      expect(state.rows.sense.uri).toBe('udp://192.168.0.20:14550');
    });

    it('foundCandidateChosen with no suggested stream yet is a true no-op — notifyPrefillMiss$ toasts instead', () => {
      const unresolvable = candidate({ suggestedStreamProtocol: undefined, suggestedStreamUri: undefined });
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.foundCandidateChosen({ candidate: unresolvable }));
      expect(state).toBe(initialOnboardingWizardState);
    });

    it('backFromConfirm clears the prefilled row and returns to source, not confirm', () => {
      const chosen = reducer(initialOnboardingWizardState, OnboardingPageActions.foundCandidateChosen({ candidate: candidate() }));
      const state = reducer(chosen, OnboardingPageActions.backFromConfirm());
      expect(state.step).toBe('source');
      expect(state.selectedCandidate).toBeNull();
      expect(state.rows.sense.uri).toBe('');
    });

    it('attachExistingFromConfirm routes to attach with attachTarget already set to existing', () => {
      const chosen = reducer(initialOnboardingWizardState, OnboardingPageActions.foundCandidateChosen({ candidate: candidate() }));
      const state = reducer(chosen, OnboardingPageActions.attachExistingFromConfirm());
      expect(state.step).toBe('attach');
      expect(state.attachTarget).toBe('existing');
    });
  });

  describe('the create/attach orchestration brackets', () => {
    it('createFlowStarted/Settled toggle creating exactly like the old shared flag', () => {
      const started = reducer(initialOnboardingWizardState, OnboardingPageActions.createFlowStarted());
      expect(started.creating).toBe(true);
      expect(reducer(started, OnboardingPageActions.createFlowSettled()).creating).toBe(false);
    });

    it('sysidStepEntered prefills sysidValue only when a collision was actually assigned', () => {
      const withValue = reducer(initialOnboardingWizardState, OnboardingPageActions.sysidStepEntered({ assetId: 'a1', displayName: 'Falcon', prefillSysidValue: 7 }));
      expect(withValue).toMatchObject({ step: 'sysid', createdAssetId: 'a1', createdAssetDisplayName: 'Falcon', sysidValue: 7 });

      const seeded = { ...initialOnboardingWizardState, sysidValue: 3 };
      const noOverwrite = reducer(seeded, OnboardingPageActions.sysidStepEntered({ assetId: 'a1', displayName: 'Falcon', prefillSysidValue: null }));
      expect(noOverwrite.sysidValue).toBe(3);
    });

    it('handoverStepEntered resets the outcome/error facts for the new asset', () => {
      const seeded = { ...initialOnboardingWizardState, handoverOutcome: 'issued' as const, handoverError: 'stale' };
      const state = reducer(seeded, OnboardingPageActions.handoverStepEntered({ assetId: 'a1', displayName: 'Falcon' }));
      expect(state).toMatchObject({ step: 'handover', createdAssetId: 'a1', createdAssetDisplayName: 'Falcon', handoverOutcome: null, handoverError: null });
    });
  });

  describe('navigation', () => {
    it('stepJumped is a one-way door once an asset has been created', () => {
      const withAsset = { ...initialOnboardingWizardState, createdAssetId: 'a1', step: 'handover' as const };
      const state = reducer(withAsset, OnboardingPageActions.stepJumped({ step: 'source' }));
      expect(state.step).toBe('handover');
    });

    it('stepJumped is free navigation before any asset exists', () => {
      const state = reducer(initialOnboardingWizardState, OnboardingPageActions.stepJumped({ step: 'identify' }));
      expect(state.step).toBe('identify');
    });

    it('stepAdvanced only moves on when canAdvanceOf allows it (identify needs a name and a category)', () => {
      const identify = { ...initialOnboardingWizardState, step: 'identify' as const };
      expect(reducer(identify, OnboardingPageActions.stepAdvanced()).step).toBe('identify');

      const ready = { ...identify, displayName: 'Falcon', category: 'multirotor' };
      expect(reducer(ready, OnboardingPageActions.stepAdvanced()).step).not.toBe('identify');
    });
  });

  describe('Prove — probe/verify outcomes', () => {
    it('testRowRequested/Succeeded/Failed drive probing + lastProbeError per role, independently of the other role', () => {
      const requested = reducer(initialOnboardingWizardState, OnboardingPageActions.testRowRequested({ role: 'sight', request: { protocol: 'rtsp', uri: 'rtsp://x' } }));
      expect(requested.proveByRole.sight.probing).toBe(true);
      expect(requested.proveByRole.sense.probing).toBe(false);

      const failed = reducer(requested, OnboardingApiActions.testRowFailed({ role: 'sight', request: { protocol: 'rtsp', uri: 'rtsp://x' }, error: 'timed out' }));
      expect(failed.proveByRole.sight).toMatchObject({ probing: false, lastProbeError: 'timed out' });
    });

    it('sysidCollisionChecked is silent-degrade on Failed — no handler, previous collision fact stays', () => {
      const checked = reducer(
        initialOnboardingWizardState,
        OnboardingApiActions.sysidCollisionChecked({ role: 'sense', collision: 12, parameterToWrite: 'MAV_SYSID' }),
      );
      const afterFailedRetry = reducer(checked, OnboardingApiActions.sysidCollisionCheckFailed());
      expect(afterFailedRetry.proveByRole.sense).toMatchObject({ sysidCollision: 12, sysidParameterToWrite: 'MAV_SYSID' });
    });
  });

  describe('extraSelectors', () => {
    it('selectCanAdvance reads the gate for the current step only, ignoring the others', () => {
      expect(onboardingWizardFeature.selectCanAdvance.projector('source', true, false, false)).toBe(true);
      expect(onboardingWizardFeature.selectCanAdvance.projector('prove', false, true, false)).toBe(true);
      expect(onboardingWizardFeature.selectCanAdvance.projector('identify', false, false, true)).toBe(true);
      expect(onboardingWizardFeature.selectCanAdvance.projector('attach', true, true, true)).toBe(false);
    });

    it('selectIdentifyCategoryOptions filters to unconnected categories only on the equipment path', () => {
      const options = onboardingWizardFeature.selectIdentifyCategoryOptions.projector(
        [
          { slug: 'multirotor', name: 'Multirotor' },
          { slug: 'battery', name: 'Battery' },
        ],
        true,
        [
          { slug: 'multirotor', name: 'Multirotor', connected: true, attributeHints: [] },
          { slug: 'battery', name: 'Battery', connected: false, attributeHints: [] },
        ],
      );
      expect(options).toEqual([{ slug: 'battery', name: 'Battery' }]);
    });

    it('selectIdentifyCategoryOptions returns every option unfiltered off the equipment path', () => {
      const options = onboardingWizardFeature.selectIdentifyCategoryOptions.projector(
        [{ slug: 'multirotor', name: 'Multirotor' }],
        false,
        [{ slug: 'multirotor', name: 'Multirotor', connected: true, attributeHints: [] }],
      );
      expect(options).toEqual([{ slug: 'multirotor', name: 'Multirotor' }]);
    });
  });
});
