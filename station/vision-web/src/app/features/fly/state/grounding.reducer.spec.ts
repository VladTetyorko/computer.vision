import { describe, expect, it } from 'vitest';
import type { ReadinessReport } from '../../../core/api/models';
import { GroundingApiActions, GroundingPageActions } from './grounding.actions';
import { initialGroundingState } from './grounding.model';
import { groundingFeature } from './grounding.reducer';

const { reducer, selectGroundedBlocker, selectGroundedReason } = groundingFeature;

function report(assetId: string, blockers: readonly string[] = []): ReadinessReport {
  return {
    assetId,
    verdict: 'NO_GO',
    evaluatedAt: '2024-01-01T00:00:00Z',
    profileObservedAt: null,
    features: [],
    blockers,
  };
}

describe('groundingFeature reducer', () => {
  it('starts idle — nothing tracked, nothing confirmed', () => {
    expect(initialGroundingState).toEqual({ assetId: undefined, report: undefined });
  });

  it('trackRequested replaces the tracked asset and clears the previous report', () => {
    const answered = reducer(
      reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-1' })),
      GroundingApiActions.readSucceeded({ assetId: 'a-1', report: report('a-1') }),
    );
    expect(answered.report).toBeDefined();

    // Switching assets must never leave the previous vehicle's verdict on screen for the new one.
    expect(reducer(answered, GroundingPageActions.trackRequested({ assetId: 'a-2' }))).toEqual({
      assetId: 'a-2',
      report: undefined,
    });
  });

  it('resetRequested returns to idle', () => {
    const tracked = reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    expect(reducer(tracked, GroundingPageActions.resetRequested())).toEqual(initialGroundingState);
  });

  it('applies a read that still names the tracked asset', () => {
    const tracked = reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-1' }));
    const answered = reducer(tracked, GroundingApiActions.readSucceeded({ assetId: 'a-1', report: report('a-1') }));

    expect(answered.report?.assetId).toBe('a-1');
  });

  it('drops a read for an asset that is no longer tracked', () => {
    const tracked = reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-2' }));
    const answered = reducer(tracked, GroundingApiActions.readSucceeded({ assetId: 'a-1', report: report('a-1') }));

    expect(answered).toBe(tracked);
  });

  it('a failed read clears the report rather than leaving a stale one standing', () => {
    const answered = reducer(
      reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-1' })),
      GroundingApiActions.readSucceeded({ assetId: 'a-1', report: report('a-1', ['MAINTENANCE_GROUNDED:REPAIR:cracked arm']) }),
    );
    const failed = reducer(answered, GroundingApiActions.readFailed({ assetId: 'a-1' }));

    expect(failed.report).toBeUndefined();
  });

  it('drops a failure for an asset that is no longer tracked', () => {
    const tracked = reducer(initialGroundingState, GroundingPageActions.trackRequested({ assetId: 'a-2' }));
    expect(reducer(tracked, GroundingApiActions.readFailed({ assetId: 'a-1' }))).toBe(tracked);
  });
});

describe('groundingFeature selectors', () => {
  it('parse a MAINTENANCE_GROUNDED blocker into the banner wording both surfaces render', () => {
    const state = { assetId: 'a-1', report: report('a-1', ['MAINTENANCE_GROUNDED:REPAIR:cracked arm']) };

    expect(selectGroundedBlocker.projector(state.report)).toEqual({ kind: 'REPAIR', summary: 'cracked arm' });
    expect(selectGroundedReason.projector(selectGroundedBlocker.projector(state.report))).toContain('cracked arm');
  });

  it('answer undefined for an asset carrying no grounding blocker', () => {
    expect(selectGroundedBlocker.projector(report('a-1', ['BATTERY']))).toBeUndefined();
    expect(selectGroundedReason.projector(undefined)).toBeUndefined();
  });

  it('answer undefined when nothing was confirmed — an unread or failed report is never a grounded vehicle', () => {
    expect(selectGroundedBlocker.projector(undefined)).toBeUndefined();
  });
});
