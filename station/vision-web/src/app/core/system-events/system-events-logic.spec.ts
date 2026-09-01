import { describe, expect, it } from 'vitest';
import type { LiveEvent } from '../api/models';
import {
  PIPELINE_ERROR_ATTENTION_WINDOW_MS,
  SYSTEM_EVENTS_DISPLAY_LIMIT,
  activePipelineErrorMessagesByStreamId,
  systemEventRows,
  toSystemEventRow,
} from './system-events-logic';

function liveEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return {
    id: 'e-0',
    at: '2026-08-15T00:00:00Z',
    type: 'STREAM_STARTED',
    message: 'Stream started',
    attributes: {},
    ...partial,
  };
}

describe('toSystemEventRow', () => {
  it('excludes DETECTION — that feed already has its own surface', () => {
    expect(toSystemEventRow(liveEvent({ type: 'DETECTION', message: 'Person detected' }))).toBeUndefined();
  });

  it('maps PIPELINE_ERROR/GEOFENCE_BREACH to danger', () => {
    expect(toSystemEventRow(liveEvent({ type: 'PIPELINE_ERROR', message: 'RTSP source unreachable' }))?.severity).toBe(
      'danger',
    );
    expect(toSystemEventRow(liveEvent({ type: 'GEOFENCE_BREACH', message: 'KEEP-OUT breach' }))?.severity).toBe(
      'danger',
    );
  });

  it('maps DEVICE_OFFLINE to warn', () => {
    expect(toSystemEventRow(liveEvent({ type: 'DEVICE_OFFLINE', message: 'Device offline' }))?.severity).toBe('warn');
  });

  it('maps LINK_LOST/BATTERY_LOW to danger with their own titles (S4, ASSET-FLOWS-PLAN.md §2)', () => {
    const linkLost = toSystemEventRow(liveEvent({ type: 'LINK_LOST', message: 'Telemetry link lost' }));
    expect(linkLost?.severity).toBe('danger');
    expect(linkLost?.title).toBe('Link lost');

    const batteryLow = toSystemEventRow(liveEvent({ type: 'BATTERY_LOW', message: 'Battery at 9%' }));
    expect(batteryLow?.severity).toBe('danger');
    expect(batteryLow?.title).toBe('Battery low');
  });

  it('maps DEVICE_ONLINE/STREAM_STARTED/STREAM_STOPPED/TRAINING to neutral', () => {
    for (const type of ['DEVICE_ONLINE', 'STREAM_STARTED', 'STREAM_STOPPED', 'TRAINING']) {
      expect(toSystemEventRow(liveEvent({ type }))?.severity).toBe('neutral');
    }
  });

  it('gives every mapped type a sentence-case title distinct from the raw enum constant', () => {
    expect(toSystemEventRow(liveEvent({ type: 'PIPELINE_ERROR' }))?.title).toBe('Pipeline error');
    expect(toSystemEventRow(liveEvent({ type: 'DEVICE_OFFLINE' }))?.title).toBe('Device offline');
  });

  it('humanizes an unrecognized type rather than crashing — forward-compat, never reached today', () => {
    const row = toSystemEventRow(liveEvent({ type: 'SOME_NEW_TYPE' }));
    expect(row?.title).toBe('Some new type');
    expect(row?.severity).toBe('neutral');
  });

  it('carries the backend message verbatim as detail, never re-worded', () => {
    expect(toSystemEventRow(liveEvent({ message: 'RTSP source unreachable: connection refused' }))?.detail).toBe(
      'RTSP source unreachable: connection refused',
    );
  });

  it('preserves an absent streamId (device-level events) rather than fabricating one', () => {
    expect(toSystemEventRow(liveEvent({ type: 'DEVICE_ONLINE', streamId: undefined }))?.streamId).toBeUndefined();
  });
});

describe('systemEventRows', () => {
  it('drops DETECTION and keeps every other type, in the given (newest-first) order', () => {
    const events = [
      liveEvent({ id: '1', type: 'DETECTION' }),
      liveEvent({ id: '2', type: 'PIPELINE_ERROR' }),
      liveEvent({ id: '3', type: 'STREAM_STOPPED' }),
    ];
    expect(systemEventRows(events).map((row) => row.id)).toEqual(['2', '3']);
  });

  it('caps at the given max without scanning past it', () => {
    const events = Array.from({ length: 30 }, (_, i) => liveEvent({ id: `e-${i}` }));
    expect(systemEventRows(events, 5)).toHaveLength(5);
  });

  it('defaults to SYSTEM_EVENTS_DISPLAY_LIMIT', () => {
    const events = Array.from({ length: SYSTEM_EVENTS_DISPLAY_LIMIT + 10 }, (_, i) => liveEvent({ id: `e-${i}` }));
    expect(systemEventRows(events)).toHaveLength(SYSTEM_EVENTS_DISPLAY_LIMIT);
  });
});

describe('activePipelineErrorMessagesByStreamId', () => {
  const NOW = Date.parse('2026-08-15T12:00:00Z');

  it('is empty when nothing has errored', () => {
    const events = [liveEvent({ id: '1', type: 'STREAM_STARTED', streamId: 's1' })];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).size).toBe(0);
  });

  it('records a fresh PIPELINE_ERROR by its own streamId, message verbatim', () => {
    const events = [
      liveEvent({
        id: '1',
        type: 'PIPELINE_ERROR',
        streamId: 's1',
        message: 'RTSP source unreachable',
        at: new Date(NOW - 1_000).toISOString(),
      }),
    ];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).get('s1')).toBe('RTSP source unreachable');
  });

  it('clears once a later STREAM_STARTED for the same streamId arrives (newest-first: STARTED before ERROR)', () => {
    const events = [
      liveEvent({ id: '2', type: 'STREAM_STARTED', streamId: 's1', at: new Date(NOW - 1_000).toISOString() }),
      liveEvent({ id: '1', type: 'PIPELINE_ERROR', streamId: 's1', at: new Date(NOW - 5_000).toISOString() }),
    ];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).has('s1')).toBe(false);
  });

  it('does not let an older PIPELINE_ERROR for the same stream override the newest-seen state', () => {
    const events = [
      liveEvent({ id: '2', type: 'STREAM_STARTED', streamId: 's1', at: new Date(NOW - 1_000).toISOString() }),
      liveEvent({ id: '1', type: 'PIPELINE_ERROR', streamId: 's1', at: new Date(NOW - 500_000).toISOString() }),
    ];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).has('s1')).toBe(false);
  });

  it('decays a PIPELINE_ERROR older than the attention window with no clearing STREAM_STARTED', () => {
    const staleAt = new Date(NOW - PIPELINE_ERROR_ATTENTION_WINDOW_MS - 1_000).toISOString();
    const events = [liveEvent({ id: '1', type: 'PIPELINE_ERROR', streamId: 's1', at: staleAt })];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).has('s1')).toBe(false);
  });

  it('keeps a PIPELINE_ERROR right at the edge of the window', () => {
    const at = new Date(NOW - PIPELINE_ERROR_ATTENTION_WINDOW_MS + 1_000).toISOString();
    const events = [liveEvent({ id: '1', type: 'PIPELINE_ERROR', streamId: 's1', at })];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).has('s1')).toBe(true);
  });

  it('tracks multiple streams independently', () => {
    // Newest-first overall (the function's own contract — see its doc comment): s2's STREAM_STARTED
    // is s2's most recent event, so it must sort ahead of s2's own, older PIPELINE_ERROR here.
    const events = [
      liveEvent({ id: '3', type: 'STREAM_STARTED', streamId: 's2', at: new Date(NOW - 1_000).toISOString() }),
      liveEvent({ id: '2', type: 'PIPELINE_ERROR', streamId: 's2', message: 'b', at: new Date(NOW - 2_000).toISOString() }),
      liveEvent({ id: '1', type: 'PIPELINE_ERROR', streamId: 's1', message: 'a', at: new Date(NOW - 1_000).toISOString() }),
    ];
    const active = activePipelineErrorMessagesByStreamId(events, NOW);
    expect(active.get('s1')).toBe('a');
    expect(active.has('s2')).toBe(false);
  });

  it('ignores device-level events with no streamId', () => {
    const events = [liveEvent({ id: '1', type: 'DEVICE_OFFLINE', streamId: undefined })];
    expect(activePipelineErrorMessagesByStreamId(events, NOW).size).toBe(0);
  });
});
