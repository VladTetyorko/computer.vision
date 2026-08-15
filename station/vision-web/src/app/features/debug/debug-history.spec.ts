import { describe, expect, it } from 'vitest';
import { DEBUG_HISTORY_LIMIT, pushHistoryEntry, type DebugHistoryEntry } from './debug-history';

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
});
