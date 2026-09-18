import { describe, expect, it } from 'vitest';
import { DEBUG_HISTORY_LIMIT, pushHistoryEntry, statusFamily, type DebugHistoryEntry } from './debug-history';

function entry(path: string): DebugHistoryEntry {
  return { method: 'GET', path, status: 200, statusText: 'OK', ms: 12 };
}

describe('pushHistoryEntry', () => {
  it('adds the newest entry to the front', () => {
    const history = pushHistoryEntry([entry('/a')], entry('/b'));
    expect(history.map((item) => item.path)).toEqual(['/b', '/a']);
  });

  it('caps at the default limit, dropping the oldest', () => {
    const full = Array.from({ length: DEBUG_HISTORY_LIMIT }, (_, i) => entry(`/${i}`));
    const history = pushHistoryEntry(full, entry('/new'));

    expect(history).toHaveLength(DEBUG_HISTORY_LIMIT);
    expect(history[0].path).toBe('/new');
    expect(history.some((item) => item.path === `/${DEBUG_HISTORY_LIMIT - 1}`)).toBe(false);
  });

  it('honors a custom limit', () => {
    const history = pushHistoryEntry([entry('/a'), entry('/b')], entry('/c'), 2);
    expect(history.map((item) => item.path)).toEqual(['/c', '/a']);
  });

  it('does not mutate the input array', () => {
    const original = [entry('/a')];
    pushHistoryEntry(original, entry('/b'));
    expect(original).toHaveLength(1);
  });

  it('carries the request body through, for both the curl builder and history replay', () => {
    const withBody: DebugHistoryEntry = { ...entry('/api/devices'), method: 'POST', body: '{"name":"x"}' };
    const history = pushHistoryEntry([], withBody);
    expect(history[0].body).toBe('{"name":"x"}');
  });

  it('preserves an entry with no body as undefined, not a coerced empty string', () => {
    const history = pushHistoryEntry([], entry('/api/devices'));
    expect(history[0].body).toBeUndefined();
  });
});

describe('statusFamily', () => {
  it('is ok across the 2xx and 3xx range', () => {
    expect(statusFamily(200)).toBe('ok');
    expect(statusFamily(204)).toBe('ok');
    expect(statusFamily(301)).toBe('ok');
  });

  it('is warn across the 4xx range', () => {
    expect(statusFamily(400)).toBe('warn');
    expect(statusFamily(404)).toBe('warn');
    expect(statusFamily(499)).toBe('warn');
  });

  it('is danger across the 5xx range', () => {
    expect(statusFamily(500)).toBe('danger');
    expect(statusFamily(503)).toBe('danger');
  });

  it('is danger for status 0 — DebugApiService\'s convention for an unreached backend', () => {
    expect(statusFamily(0)).toBe('danger');
  });
});
