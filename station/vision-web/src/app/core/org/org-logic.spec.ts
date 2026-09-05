import { describe, expect, it } from 'vitest';
import {
  buildGroupTree,
  canManageOrg,
  flattenGroupTree,
  formatActivity,
  roleOptions,
} from './org-logic';
import type { AuditEntry, AuthCapability, GroupSummary } from '../api/models';

describe('canManageOrg', () => {
  it.each<[readonly AuthCapability[] | null | undefined, boolean]>([
    [['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'], true], // ADMIN/MANAGER's full set
    [['MANAGE_ORG'], true], // MANAGE_ORG alone is sufficient
    [['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'], false], // PILOT's set — no MANAGE_ORG
    [[], false], // VIEWER — no capabilities at all
    [undefined, false],
    [null, false],
  ])('capabilities=%s → %s', (capabilities, expected) => {
    expect(canManageOrg(capabilities)).toBe(expected);
  });
});

describe('roleOptions', () => {
  it('lists all four roles least→most privileged, with human labels', () => {
    expect(roleOptions()).toEqual([
      { value: 'VIEWER', label: 'Viewer' },
      { value: 'PILOT', label: 'Pilot' },
      { value: 'MANAGER', label: 'Manager' },
      { value: 'ADMIN', label: 'Admin' },
    ]);
  });
});

function group(id: string, name: string, parentGroupId?: string): GroupSummary {
  return parentGroupId === undefined ? { id, name } : { id, name, parentGroupId };
}

describe('buildGroupTree', () => {
  it('nests children under their parent and marks roots at depth 0', () => {
    const tree = buildGroupTree([
      group('root', 'HQ'),
      group('a', 'Alpha', 'root'),
      group('b', 'Bravo', 'root'),
    ]);
    expect(tree).toHaveLength(1);
    expect(tree[0].group.id).toBe('root');
    expect(tree[0].depth).toBe(0);
    expect(tree[0].children.map((c) => c.group.id)).toEqual(['a', 'b']);
    expect(tree[0].children[0].depth).toBe(1);
  });

  it('sorts roots and children by name', () => {
    const tree = buildGroupTree([
      group('z', 'Zulu'),
      group('a', 'Alpha'),
      group('a2', 'Yankee', 'a'),
      group('a1', 'Xray', 'a'),
    ]);
    expect(tree.map((n) => n.group.name)).toEqual(['Alpha', 'Zulu']);
    expect(tree[0].children.map((c) => c.group.name)).toEqual(['Xray', 'Yankee']);
  });

  it('treats an absent or unknown parent as a root', () => {
    const tree = buildGroupTree([group('a', 'Alpha'), group('b', 'Bravo', 'ghost')]);
    expect(tree.map((n) => n.group.id).sort()).toEqual(['a', 'b']);
    expect(tree.every((n) => n.depth === 0)).toBe(true);
  });

  it('is cycle-safe and loss-free: every node in an A↔B cycle appears exactly once', () => {
    const tree = buildGroupTree([group('a', 'Alpha', 'b'), group('b', 'Bravo', 'a')]);
    const flat = flattenGroupTree(tree);
    const ids = flat.map((n) => n.group.id).sort();
    expect(ids).toEqual(['a', 'b']);
    expect(new Set(ids).size).toBe(2); // no node dropped, none duplicated
  });

  it('treats a self-parenting group as a root without looping', () => {
    const tree = buildGroupTree([group('a', 'Alpha', 'a')]);
    expect(tree).toHaveLength(1);
    expect(tree[0].group.id).toBe('a');
    expect(tree[0].children).toHaveLength(0);
  });

  it('does not mutate its input', () => {
    const input = [group('a', 'Alpha'), group('b', 'Bravo', 'a')];
    const copy = JSON.parse(JSON.stringify(input));
    buildGroupTree(input);
    expect(input).toEqual(copy);
  });
});

describe('flattenGroupTree', () => {
  it('pre-orders the tree (parent before its children), preserving order', () => {
    const tree = buildGroupTree([
      group('root', 'HQ'),
      group('a', 'Alpha', 'root'),
      group('a1', 'Deep', 'a'),
      group('b', 'Bravo', 'root'),
    ]);
    expect(flattenGroupTree(tree).map((n) => n.group.id)).toEqual(['root', 'a', 'a1', 'b']);
  });
});

function auditEntry(overrides: Partial<AuditEntry> = {}): AuditEntry {
  return {
    id: 'e-1',
    occurredAt: '2026-07-30T11:59:30.000Z',
    actor: 'u-1',
    action: 'UPDATED',
    targetType: 'ASSET',
    targetId: 'a-1',
    summary: 'Renamed Falcon to Falcon 2',
    details: { name: 'Falcon → Falcon 2' },
    ...overrides,
  };
}

describe('formatActivity', () => {
  const now = Date.parse('2026-07-30T12:00:00.000Z');

  it('maps a known action/target to friendly labels and keeps the summary verbatim', () => {
    const view = formatActivity(auditEntry(), now);
    expect(view.actionLabel).toBe('Updated');
    expect(view.targetLabel).toBe('Asset');
    expect(view.summary).toBe('Renamed Falcon to Falcon 2');
  });

  it('falls back to the raw action/target for an unrecognized value (never blank)', () => {
    const view = formatActivity(auditEntry({ action: 'MIGRATED', targetType: '' }), now);
    expect(view.actionLabel).toBe('MIGRATED');
    expect(view.targetLabel).toBe('');
  });

  it('renders a relative time from occurredAt', () => {
    expect(formatActivity(auditEntry(), now).relativeTime).toBe('30s ago');
  });

  it('flattens details to sorted key/value rows and tolerates missing details', () => {
    const view = formatActivity(
      auditEntry({ details: { name: 'a → b', state: 'ACTIVE → DELETED' } }),
      now,
    );
    expect(view.details).toEqual([
      { key: 'name', value: 'a → b' },
      { key: 'state', value: 'ACTIVE → DELETED' },
    ]);
    expect(formatActivity(auditEntry({ details: undefined as never }), now).details).toEqual([]);
  });
});
