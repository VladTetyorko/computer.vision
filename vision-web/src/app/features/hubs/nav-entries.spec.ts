import { describe, expect, it } from 'vitest';
import { NAV_MODES, navModeById } from './nav-entries';

describe('NAV_MODES', () => {
  it('has exactly the three frozen modes, in order, each with its own hub route', () => {
    expect(NAV_MODES.map((mode) => mode.id)).toEqual(['operate', 'monitor', 'manage']);
    expect(NAV_MODES.map((mode) => mode.hubRoute)).toEqual(['/operate', '/monitor', '/manage']);
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

  it('the canonical labels are exactly "Cockpit" (/fly) and "Vision" — never "Fly"/"Detection"', () => {
    const operate = NAV_MODES.find((mode) => mode.id === 'operate')!;
    const cockpit = operate.entries.find((entry) => entry.to === '/fly' && entry.name === 'Cockpit');
    const vision = operate.entries.find((entry) => entry.name === 'Vision');
    expect(cockpit).toBeDefined();
    expect(vision).toBeDefined();
    expect(operate.entries.some((entry) => entry.name === 'Fly' || entry.name === 'Detection')).toBe(false);
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
