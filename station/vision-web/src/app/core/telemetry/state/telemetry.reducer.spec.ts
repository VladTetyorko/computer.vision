import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '../../api/models';
import { TelemetryApiActions, TelemetryPageActions } from './telemetry.actions';
import { initialTelemetryState } from './telemetry.model';
import { telemetryFeature, transportSelectorFor } from './telemetry.reducer';

const { reducer } = telemetryFeature;

function sample(overrides: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-1', at: '2026-07-22T00:00:00Z', latitude: 1, longitude: 2, ...overrides };
}

describe('telemetry reducer', () => {
  it('Tracked seeds a fresh, empty entry keyed by deviceId, storing the given assetId', () => {
    const state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    expect(state.byDeviceId['dev-1']).toEqual({ assetId: 'a-1', backfill: [], pollSamples: [] });
  });

  it('a re-Tracked key drops its previous session data rather than keeping stale samples', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryApiActions.backfillReceived({ deviceId: 'dev-1', usageId: 'u-1', samples: [sample()] }));
    state = reducer(state, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-2' }));
    expect(state.byDeviceId['dev-1']).toEqual({ assetId: 'a-2', backfill: [], pollSamples: [] });
  });

  it('Reset removes the entry entirely', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryPageActions.reset({ deviceId: 'dev-1' }));
    expect(state.byDeviceId['dev-1']).toBeUndefined();
  });

  it('Usage Not Found is a no-op, leaving the freshly-tracked empty entry as it is', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: undefined }));
    state = reducer(state, TelemetryApiActions.usageNotFound({ deviceId: 'dev-1' }));
    expect(state.byDeviceId['dev-1']).toEqual({ assetId: undefined, backfill: [], pollSamples: [] });
  });

  it('Backfill Received sets both backfill and pollSamples to the same initial list', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryApiActions.backfillReceived({ deviceId: 'dev-1', usageId: 'u-1', samples: [sample()] }));
    expect(state.byDeviceId['dev-1']).toEqual({ assetId: 'a-1', backfill: [sample()], pollSamples: [sample()] });
  });

  it('Backfill Received for a since-reset device is a no-op (superseded async lookup)', () => {
    const state = reducer(
      initialTelemetryState,
      TelemetryApiActions.backfillReceived({ deviceId: 'dev-1', usageId: 'u-1', samples: [sample()] }),
    );
    expect(state.byDeviceId['dev-1']).toBeUndefined();
  });

  it('Poll Received replaces only pollSamples, leaving backfill untouched', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryApiActions.backfillReceived({ deviceId: 'dev-1', usageId: 'u-1', samples: [sample()] }));
    const polled = sample({ at: '2026-07-22T00:00:05Z' });
    state = reducer(state, TelemetryApiActions.pollReceived({ deviceId: 'dev-1', samples: [polled] }));
    expect(state.byDeviceId['dev-1']).toEqual({ assetId: 'a-1', backfill: [sample()], pollSamples: [polled] });
  });

  it('Poll Failed is a no-op — the last-known-good pollSamples survive unchanged', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryApiActions.backfillReceived({ deviceId: 'dev-1', usageId: 'u-1', samples: [sample()] }));
    const before = state.byDeviceId['dev-1'];
    state = reducer(state, TelemetryApiActions.pollFailed({ deviceId: 'dev-1' }));
    expect(state.byDeviceId['dev-1']).toEqual(before);
  });

  it('two devices are stored independently — one never disturbs the other', () => {
    let state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    state = reducer(state, TelemetryPageActions.tracked({ deviceId: 'dev-2', assetId: 'a-2' }));
    state = reducer(state, TelemetryPageActions.reset({ deviceId: 'dev-1' }));
    expect(state.byDeviceId['dev-1']).toBeUndefined();
    expect(state.byDeviceId['dev-2']).toEqual({ assetId: 'a-2', backfill: [], pollSamples: [] });
  });
});

describe('transportSelectorFor', () => {
  it('resolves "poll" when the device has no assetId, regardless of connection state', () => {
    const state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: undefined }));
    const projector = transportSelectorFor('dev-1').projector;
    expect(projector(state.byDeviceId, 'open')).toBe('poll');
  });

  it('resolves "live" only when the connection is open and the device has an assetId', () => {
    const state = reducer(initialTelemetryState, TelemetryPageActions.tracked({ deviceId: 'dev-1', assetId: 'a-1' }));
    const projector = transportSelectorFor('dev-1').projector;
    expect(projector(state.byDeviceId, 'open')).toBe('live');
    expect(projector(state.byDeviceId, 'connecting')).toBe('poll');
  });

  it('resolves "poll" for a device with no entry at all', () => {
    const projector = transportSelectorFor('unknown-device').projector;
    expect(projector(initialTelemetryState.byDeviceId, 'open')).toBe('poll');
  });
});
