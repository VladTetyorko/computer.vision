import { describe, expect, it } from 'vitest';
import type { SystemStatus } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { SystemStatusApiActions, SystemStatusPageActions } from './system-status.actions';
import { initialSystemStatusState } from './system-status.model';
import { systemStatusFeature } from './system-status.reducer';

const { reducer } = systemStatusFeature;

function status(partial: Partial<SystemStatus> = {}): SystemStatus {
  return { overall: 'OK', checkedAt: '2026-08-15T00:00:00Z', subsystems: [], ...partial };
}

describe('systemStatusFeature reducer', () => {
  it('starts undefined, not loading, no error', () => {
    expect(initialSystemStatusState.status).toBeUndefined();
    expect(initialSystemStatusState.loading).toBe(false);
    expect(initialSystemStatusState.error).toBeUndefined();
  });

  it('refreshRequested sets loading true', () => {
    const state = reducer(initialSystemStatusState, SystemStatusPageActions.refreshRequested());
    expect(state.loading).toBe(true);
  });

  it('refreshSucceeded sets status, clears error, clears loading', () => {
    const loading = reducer(initialSystemStatusState, SystemStatusPageActions.refreshRequested());
    const failed = reducer(loading, SystemStatusApiActions.refreshFailed({ error: 'down' }));
    const state = reducer(failed, SystemStatusApiActions.refreshSucceeded({ status: status({ overall: 'DEGRADED' }) }));

    expect(state.status?.overall).toBe('DEGRADED');
    expect(state.error).toBeUndefined();
    expect(state.loading).toBe(false);
    expect(systemStatusFeature.selectOverall.projector(state.status)).toBe('DEGRADED');
  });

  it('refreshFailed degrades to stale-but-present data — status untouched, error set', () => {
    const withData = reducer(initialSystemStatusState, SystemStatusApiActions.refreshSucceeded({ status: status() }));
    const failed = reducer(withData, SystemStatusApiActions.refreshFailed({ error: 'network down' }));

    expect(failed.status).toEqual(status());
    expect(failed.error).toBe('network down');
    expect(failed.loading).toBe(false);
  });

  describe('the always-on `system` SSE topic', () => {
    it('projects a live arrival directly onto status, and clears a previous error', () => {
      const failed = reducer(initialSystemStatusState, SystemStatusApiActions.refreshFailed({ error: 'down' }));
      const state = reducer(
        failed,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'system', payload: status({ overall: 'DEGRADED' }) } }),
      );

      expect(state.status?.overall).toBe('DEGRADED');
      expect(state.error).toBeUndefined();
    });

    it('ignores every other envelope type — a true identity no-op', () => {
      const state = reducer(
        initialSystemStatusState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }),
      );
      expect(state).toBe(initialSystemStatusState);
    });
  });
});
