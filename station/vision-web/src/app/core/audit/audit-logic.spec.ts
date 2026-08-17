import { describe, expect, it } from 'vitest';
import type { AssetSummary, AuditEntry, UserSummary } from '../api/models';
import { auditResult, buildAuditRows, distinctActions, distinctActors, filterAuditRows } from './audit-logic';

function entry(partial: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: 'e-0',
    occurredAt: '2026-08-16T10:00:00Z',
    actor: 'u-1',
    action: 'CREATED',
    targetType: 'ASSET',
    targetId: 'a-1',
    summary: 'Created asset Falcon',
    details: {},
    ...partial,
  };
}

function user(partial: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'u-1',
    username: 'jane',
    displayName: 'Jane Pilot',
    email: 'jane@example.com',
    enabled: true,
    memberships: [],
    ...partial,
  };
}

function asset(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-1',
    displayName: 'Falcon-1',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'org',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

describe('auditResult', () => {
  it('reads success when no result detail is present (asset/device services never write one)', () => {
    expect(auditResult(entry({ details: {} }))).toEqual({ denied: false, label: 'Success' });
  });

  it('reads success for a non-denial result value (e.g. "CREATED"/"STARTED")', () => {
    expect(auditResult(entry({ details: { result: 'CREATED' } }))).toEqual({ denied: false, label: 'Success' });
  });

  it('reads a denial and extracts the reason after the colon', () => {
    expect(auditResult(entry({ details: { result: 'DENIED:out of scope' } }))).toEqual({
      denied: true,
      label: 'Denied — out of scope',
    });
  });

  it('reads a denial with no reason as plain "Denied"', () => {
    expect(auditResult(entry({ details: { result: 'DENIED' } }))).toEqual({ denied: true, label: 'Denied' });
  });

  it('is case-insensitive on the DENIED prefix', () => {
    expect(auditResult(entry({ details: { result: 'denied:out of scope' } })).denied).toBe(true);
  });
});

describe('buildAuditRows', () => {
  it('resolves the actor to a display name when the user is loaded', () => {
    const rows = buildAuditRows([entry({ actor: 'u-1' })], [user({ userId: 'u-1', displayName: 'Jane Pilot' })], [], 0);
    expect(rows[0].actorLabel).toBe('Jane Pilot');
  });

  it('falls back to a short id fragment for an unresolvable actor — never fabricated', () => {
    const rows = buildAuditRows([entry({ actor: 'unknown-actor-id' })], [], [], 0);
    expect(rows[0].actorLabel).toBe('unknown-');
  });

  it('resolves an ASSET target to its display name when the asset is loaded', () => {
    const rows = buildAuditRows(
      [entry({ targetType: 'ASSET', targetId: 'a-1' })],
      [],
      [asset({ assetId: 'a-1', displayName: 'Falcon-1' })],
      0,
    );
    expect(rows[0].targetName).toBe('Falcon-1');
  });

  it('falls back to a short id fragment for an unresolvable ASSET target', () => {
    const rows = buildAuditRows([entry({ targetType: 'ASSET', targetId: 'unknown-asset-id' })], [], [], 0);
    expect(rows[0].targetName).toBe('unknown-');
  });

  it('never resolves a non-ASSET target to a name — always a short id fragment', () => {
    const rows = buildAuditRows(
      [entry({ targetType: 'DEVICE', targetId: 'device-12345' })],
      [],
      [asset({ assetId: 'device-12345', displayName: 'Should never show' })],
      0,
    );
    expect(rows[0].targetName).toBe('device-1');
  });

  it('carries the denied/resultLabel fields through from auditResult', () => {
    const rows = buildAuditRows([entry({ details: { result: 'DENIED:out of scope' } })], [], [], 0);
    expect(rows[0].denied).toBe(true);
    expect(rows[0].resultLabel).toBe('Denied — out of scope');
  });

  it('preserves input order (assumed newest-first, never re-sorted)', () => {
    const rows = buildAuditRows([entry({ id: 'e-1' }), entry({ id: 'e-2' })], [], [], 0);
    expect(rows.map((r) => r.id)).toEqual(['e-1', 'e-2']);
  });
});

describe('filterAuditRows', () => {
  const rows = buildAuditRows(
    [
      entry({ id: 'e-1', actor: 'u-1', action: 'CREATED' }),
      entry({ id: 'e-2', actor: 'u-2', action: 'DELETED' }),
      entry({ id: 'e-3', actor: 'u-1', action: 'DELETED' }),
    ],
    [user({ userId: 'u-1', displayName: 'Jane' }), user({ userId: 'u-2', displayName: 'Bo' })],
    [],
    0,
  );

  it('returns every row for an empty filter', () => {
    expect(filterAuditRows(rows, {})).toEqual(rows);
  });

  it('filters by actorId alone', () => {
    expect(filterAuditRows(rows, { actorId: 'u-1' }).map((r) => r.id)).toEqual(['e-1', 'e-3']);
  });

  it('filters by action alone', () => {
    expect(filterAuditRows(rows, { action: 'DELETED' }).map((r) => r.id)).toEqual(['e-2', 'e-3']);
  });

  it('filters by both actorId and action together (AND, not OR)', () => {
    expect(filterAuditRows(rows, { actorId: 'u-1', action: 'DELETED' }).map((r) => r.id)).toEqual(['e-3']);
  });

  it('matches nothing for a combination that does not co-occur', () => {
    expect(filterAuditRows(rows, { actorId: 'u-2', action: 'CREATED' })).toEqual([]);
  });
});

describe('distinctActors', () => {
  it('lists every actor once, alphabetical by label', () => {
    const rows = buildAuditRows(
      [entry({ actor: 'u-1' }), entry({ actor: 'u-2' }), entry({ actor: 'u-1' })],
      [user({ userId: 'u-1', displayName: 'Zeb' }), user({ userId: 'u-2', displayName: 'Amy' })],
      [],
      0,
    );
    expect(distinctActors(rows)).toEqual([
      { value: 'u-2', label: 'Amy' },
      { value: 'u-1', label: 'Zeb' },
    ]);
  });

  it('is empty for no rows', () => {
    expect(distinctActors([])).toEqual([]);
  });
});

describe('distinctActions', () => {
  it('lists every action once, alphabetical by its friendly label', () => {
    const rows = buildAuditRows(
      [entry({ action: 'DELETED' }), entry({ action: 'CREATED' }), entry({ action: 'DELETED' })],
      [],
      [],
      0,
    );
    expect(distinctActions(rows)).toEqual([
      { value: 'CREATED', label: 'Created' },
      { value: 'DELETED', label: 'Deleted' },
    ]);
  });
});
