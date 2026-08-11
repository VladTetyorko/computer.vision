import { describe, expect, it } from 'vitest';
import { NAV_MODES, navModeById } from './nav-entries';

describe('NAV_MODES', () => {
  it('has exactly the three frozen modes, in order, each with its own primaryRoute (docs/extracts/design/19-hubs.md — where the retired /operate|/monitor|/manage hub paths now redirect)', () => {
    expect(NAV_MODES.map((mode) => mode.id)).toEqual(['operate', 'monitor', 'manage']);
    expect(NAV_MODES.map((mode) => mode.primaryRoute)).toEqual(['/fly', '/command', '/assets']);
  });

  it('every entry has a non-empty name/description and a `to` that starts with a slash', () => {
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        expect(entry.name.length, `${mode.id} entry name`).toBeGreaterThan(0);
        expect(entry.description.length, `${mode.id}/${entry.name} description`).toBeGreaterThan(0);
        expect(entry.to.startsWith('/'), `${mode.id}/${entry.name} to="${entry.to}"`).toBe(true);
      }
    }
  });

  it('every badge (when set) is the frozen "soon" scaffold flag — no other value', () => {
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        if (entry.badge !== undefined) {
          expect(entry.badge, `${mode.id}/${entry.name}`).toBe('soon');
        }
      }
    }
  });

  it('has no duplicate entry name within one mode', () => {
    for (const mode of NAV_MODES) {
      const names = mode.entries.map((entry) => entry.name);
      expect(new Set(names).size, mode.id).toBe(names.length);
    }
  });

  it('the canonical cockpit label is exactly "Cockpit" (/fly) — never "Fly"/"Detection"/"Vision"', () => {
    const operate = NAV_MODES.find((mode) => mode.id === 'operate')!;
    const cockpit = operate.entries.find((entry) => entry.to === '/fly' && entry.name === 'Cockpit');
    expect(cockpit).toBeDefined();
    expect(
      operate.entries.some((entry) => entry.name === 'Fly' || entry.name === 'Detection' || entry.name === 'Vision'),
    ).toBe(false);
  });

  /**
   * docs/conclusions/UX-SIMPLIFY-REVIEW.md F1's own standing regression guard: before this task `/command` was
   * linked 3×, `/wall` 2×, `/fly` 2× across Operate/Monitor — this asserts every destination now has
   * exactly one canonical `NavEntry`, app-wide, forever (not just for the three routes the finding
   * happened to name).
   */
  it('F1 — no destination (`to`) is linked from more than one NavEntry anywhere in NAV_MODES', () => {
    const seenBy = new Map<string, string>();
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        const owner = seenBy.get(entry.to);
        expect(owner, `"${entry.to}" already linked by "${owner}", duplicated by ${mode.id}/"${entry.name}"`).toBeUndefined();
        seenBy.set(entry.to, `${mode.id}/${entry.name}`);
      }
    }
  });

  it('F2 — Warehouse is gone: no entry named "Warehouse" or targeting /warehouse', () => {
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        expect(entry.name, `${mode.id}/${entry.name}`).not.toBe('Warehouse');
        expect(entry.to, `${mode.id}/${entry.name}`).not.toBe('/warehouse');
      }
    }
  });

  describe('F3 — Manage grouping/role-scoping shape', () => {
    const manage = NAV_MODES.find((mode) => mode.id === 'manage')!;

    it('every `group` (when set) is one of the three frozen groups', () => {
      for (const entry of manage.entries) {
        if (entry.group !== undefined) {
          expect(['configuration', 'diagnostics', 'advanced'], entry.name).toContain(entry.group);
        }
      }
    });

    it('every grouped entry is also managerOnly — a group is always role-scoped', () => {
      for (const entry of manage.entries) {
        if (entry.group !== undefined) {
          expect(entry.managerOnly, `${entry.name} has a group but isn't managerOnly`).toBe(true);
        }
      }
    });

    it('Assets and Add source are ungrouped and ungated — every role reaches them', () => {
      for (const name of ['Assets', 'Add source']) {
        const entry = manage.entries.find((e) => e.name === name)!;
        expect(entry, name).toBeDefined();
        expect(entry.group, name).toBeUndefined();
        expect(entry.managerOnly, name).toBeFalsy();
      }
    });

    it('Devices is demoted into the advanced group, managerOnly', () => {
      const devices = manage.entries.find((e) => e.name === 'Devices')!;
      expect(devices).toBeDefined();
      expect(devices.group).toBe('advanced');
      expect(devices.managerOnly).toBe(true);
      expect(devices.to).toBe('/devices');
    });
  });

  describe('Monitor: Command is one merged entry, not two', () => {
    const monitor = NAV_MODES.find((mode) => mode.id === 'monitor')!;

    it('has exactly one /command entry, named "Command"', () => {
      const commandEntries = monitor.entries.filter((entry) => entry.to === '/command');
      expect(commandEntries.map((entry) => entry.name)).toEqual(['Command']);
    });

    it('has no separate "Wall" entry — Wall\'s canonical home is Operate', () => {
      expect(monitor.entries.some((entry) => entry.name === 'Wall' || entry.to === '/wall')).toBe(false);
    });
  });
});

describe('navModeById', () => {
  it('returns the matching mode', () => {
    expect(navModeById('manage').label).toBe('Manage');
  });

  it('throws for an id outside the frozen set', () => {
    // @ts-expect-error deliberately an invalid id, to exercise the guard
    expect(() => navModeById('nope')).toThrow('Unknown nav mode: nope');
  });
});
