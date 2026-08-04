import { describe, expect, it } from 'vitest';
import type { AuditEntry } from '../api/models';
import { activityAccentTone, dayLabel, groupActivityByDay } from './activity-logic';

function entry(partial: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: 'e-1',
    occurredAt: '2026-08-04T10:00:00.000Z',
    actor: 'admin',
    action: 'CREATED',
    targetType: 'ASSET',
    targetId: 'a-1',
    summary: 'Created asset 1',
    details: {},
    ...partial,
  };
}

// A fixed reference instant: 2026-08-04T12:00:00Z (noon UTC), so "today"/"yesterday" boundaries in
// this spec are unambiguous regardless of the machine's own local timezone offset (each case below
// stays well clear of local-midnight edge cases by using a UTC noon anchor and day-scale deltas).
const NOW_MS = Date.parse('2026-08-04T12:00:00.000Z');

describe('dayLabel', () => {
  it('labels the same calendar day "Today"', () => {
    expect(dayLabel('2026-08-04T09:00:00.000Z', NOW_MS)).toBe('Today');
  });

  it('labels the previous calendar day "Yesterday"', () => {
    expect(dayLabel('2026-08-03T09:00:00.000Z', NOW_MS)).toBe('Yesterday');
  });

  it('labels anything older with a full date', () => {
    expect(dayLabel('2026-07-20T09:00:00.000Z', NOW_MS)).toBe(
      new Date('2026-07-20T09:00:00.000Z').toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
      }),
    );
  });

  it('is symmetric — the reference instant itself is always "Today"', () => {
    expect(dayLabel(new Date(NOW_MS).toISOString(), NOW_MS)).toBe('Today');
  });
});

describe('groupActivityByDay', () => {
  it('buckets consecutive same-day entries under one header, preserving order', () => {
    const entries = [
      entry({ id: '1', occurredAt: '2026-08-04T11:00:00.000Z' }),
      entry({ id: '2', occurredAt: '2026-08-04T10:00:00.000Z' }),
      entry({ id: '3', occurredAt: '2026-08-03T09:00:00.000Z' }),
    ];
    const groups = groupActivityByDay(entries, NOW_MS);
    expect(groups.map((g) => g.label)).toEqual(['Today', 'Yesterday']);
    expect(groups[0].entries.map((e) => e.id)).toEqual(['1', '2']);
    expect(groups[1].entries.map((e) => e.id)).toEqual(['3']);
  });

  it('returns an empty list for no entries', () => {
    expect(groupActivityByDay([], NOW_MS)).toEqual([]);
  });

  it('re-opens a new group for a day that reappears non-adjacently, rather than merging it back in', () => {
    // Out-of-order input (not this app's real feed shape, but the function must not silently
    // reorder or merge non-adjacent same-day runs — it groups adjacency, not identity).
    const entries = [
      entry({ id: '1', occurredAt: '2026-08-04T11:00:00.000Z' }),
      entry({ id: '2', occurredAt: '2026-08-03T09:00:00.000Z' }),
      entry({ id: '3', occurredAt: '2026-08-04T08:00:00.000Z' }),
    ];
    const groups = groupActivityByDay(entries, NOW_MS);
    expect(groups.map((g) => g.label)).toEqual(['Today', 'Yesterday', 'Today']);
    expect(groups.map((g) => g.entries.map((e) => e.id))).toEqual([['1'], ['2'], ['3']]);
  });

  it('does not mutate the input', () => {
    const entries = [entry({ id: '1' })];
    const copy = [...entries];
    groupActivityByDay(entries, NOW_MS);
    expect(entries).toEqual(copy);
  });
});

describe('activityAccentTone', () => {
  it('maps the four design-doc verbs to their named tones', () => {
    expect(activityAccentTone('CREATED')).toBe('success');
    expect(activityAccentTone('UPDATED')).toBe('info');
    expect(activityAccentTone('DEACTIVATED')).toBe('warn');
    expect(activityAccentTone('DELETED')).toBe('danger');
  });

  it('extends the two remaining real backend actions along an existing family', () => {
    expect(activityAccentTone('ACTIVATED')).toBe('success');
    expect(activityAccentTone('RESTORED')).toBe('info');
  });

  it('falls back to info for an unrecognized action, never a blank/missing tone', () => {
    expect(activityAccentTone('SOMETHING_NEW')).toBe('info');
  });
});
