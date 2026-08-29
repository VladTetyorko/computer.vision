import { describe, expect, it } from 'vitest';
import { NAV_MODES, navModeById } from './nav-entries';

/** Every entry a session with (`canManage`) or without (`!canManage`) ADMIN/MANAGER rights would see
 *  in the sidebar — mirrors `shared/ui/app-sidebar/app-sidebar.ts#modes`'s own filter exactly, kept
 *  here as a small local helper rather than imported so this spec stays a pure data-level check with
 *  no Angular/TestBed dependency (matching this file's own pre-existing "no Angular" precedent). */
function visibleEntries(canManage: boolean) {
  return NAV_MODES.flatMap((mode) => mode.entries.filter((entry) => !entry.managerOnly || canManage));
}

describe('NAV_MODES', () => {
  it('has exactly the five frozen groups, in order, each with its own primaryRoute (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1, wave W1)', () => {
    expect(NAV_MODES.map((mode) => mode.id)).toEqual(['operate', 'monitor', 'fleet', 'vision', 'system']);
    expect(NAV_MODES.map((mode) => mode.primaryRoute)).toEqual([
      '/fly',
      '/command',
      '/assets',
      '/manage/training',
      '/manage/system',
    ]);
  });

  it('exactly one group (system) renders in the sidebar footer, next to the identity chip', () => {
    expect(NAV_MODES.filter((mode) => mode.footer).map((mode) => mode.id)).toEqual(['system']);
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

  /**
   * The `badge: 'soon'` scaffold tier is retired wholesale this wave (docs/plans/active/WAREHOUSE-UX-PLAN.md
   * §3.1 rule 1) — every entry that used to carry it (Flight plans/missions, Saved Wall layouts,
   * Firmware, Maintenance/health) left `NAV_MODES` outright rather than staying with the flag unset;
   * `NavEntry` no longer declares the field at all. This checks no entry carries it back in by hand.
   */
  it('no entry carries a `badge` field anywhere in NAV_MODES', () => {
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        expect('badge' in entry, `${mode.id}/${entry.name}`).toBe(false);
      }
    }
  });

  it('has no duplicate entry name within one group', () => {
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

  /**
   * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 5 — a detection profile and a transmitter layout
   * are configuration a user visits rarely, not an everyday Operate/Manage door. Both routes stay
   * live and ungated; they are reached from `/settings`'s own new link list instead
   * (`features/settings/account-settings.html`).
   */
  it('Detection defaults and Controller have both left the rail entirely', () => {
    for (const mode of NAV_MODES) {
      expect(mode.entries.some((entry) => entry.to === '/settings/detection'), mode.id).toBe(false);
      expect(mode.entries.some((entry) => entry.to === '/manage/controller'), mode.id).toBe(false);
    }
  });

  describe('OPERATE — Readiness rename (rule 4)', () => {
    const operate = NAV_MODES.find((mode) => mode.id === 'operate')!;

    it('has exactly Cockpit, Wall, Readiness, in that order, none managerOnly', () => {
      expect(operate.entries.map((entry) => entry.name)).toEqual(['Cockpit', 'Wall', 'Readiness']);
      for (const entry of operate.entries) {
        expect(entry.managerOnly, entry.name).toBeFalsy();
      }
    });

    it('"Pre-flight checklist" is renamed to "Readiness"; the route (/operate/preflight) is unchanged', () => {
      const readiness = operate.entries.find((entry) => entry.to === '/operate/preflight');
      expect(readiness).toBeDefined();
      expect(readiness?.name).toBe('Readiness');
      expect(operate.entries.some((entry) => entry.name === 'Pre-flight checklist')).toBe(false);
    });
  });

  describe('MONITOR — Command still one merged entry; Audit trail moved out to System', () => {
    const monitor = NAV_MODES.find((mode) => mode.id === 'monitor')!;

    it('has exactly Command, Activity, Replay library, Alerts center, in that order, none managerOnly', () => {
      expect(monitor.entries.map((entry) => entry.name)).toEqual(['Command', 'Activity', 'Replay library', 'Alerts center']);
      for (const entry of monitor.entries) {
        expect(entry.managerOnly, entry.name).toBeFalsy();
      }
    });

    it('has exactly one /command entry, named "Command"', () => {
      const commandEntries = monitor.entries.filter((entry) => entry.to === '/command');
      expect(commandEntries.map((entry) => entry.name)).toEqual(['Command']);
    });

    it("has no separate \"Wall\" entry — Wall's canonical home is Operate", () => {
      expect(monitor.entries.some((entry) => entry.name === 'Wall' || entry.to === '/wall')).toBe(false);
    });

    it('has no "Audit trail" entry — it moved to System this wave (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1)', () => {
      expect(monitor.entries.some((entry) => entry.name === 'Audit trail' || entry.to === '/monitor/audit')).toBe(false);
    });
  });

  describe('FLEET — three renames plus three carried-over entries (rule 4; see this file\'s own class doc for the carried-over three)', () => {
    const fleet = NAV_MODES.find((mode) => mode.id === 'fleet')!;

    it('Assets is renamed to "Inventory", stays ungrouped and ungated', () => {
      const inventory = fleet.entries.find((entry) => entry.to === '/assets');
      expect(inventory).toBeDefined();
      expect(inventory?.name).toBe('Inventory');
      expect(inventory?.managerOnly).toBeFalsy();
      expect(fleet.entries.some((entry) => entry.name === 'Assets')).toBe(false);
    });

    it('"Add source" is renamed to "Add vehicle", stays managerOnly', () => {
      const addVehicle = fleet.entries.find((entry) => entry.to === '/add-source');
      expect(addVehicle).toBeDefined();
      expect(addVehicle?.name).toBe('Add vehicle');
      expect(addVehicle?.managerOnly).toBe(true);
      expect(fleet.entries.some((entry) => entry.name === 'Add source')).toBe(false);
    });

    it('"Pilots / roster" is renamed to "Crew", stays managerOnly', () => {
      const crew = fleet.entries.find((entry) => entry.to === '/manage/roster');
      expect(crew).toBeDefined();
      expect(crew?.name).toBe('Crew');
      expect(crew?.managerOnly).toBe(true);
      expect(fleet.entries.some((entry) => entry.name === 'Pilots / roster')).toBe(false);
    });

    it('Asset categories, Inventory reports and Devices carry over unrenamed, all managerOnly', () => {
      const carried: readonly [string, string][] = [
        ['/manage/categories', 'Asset categories'],
        ['/manage/reports', 'Inventory reports'],
        ['/devices', 'Devices'],
      ];
      for (const [to, name] of carried) {
        const entry = fleet.entries.find((candidate) => candidate.to === to);
        expect(entry, to).toBeDefined();
        expect(entry?.name).toBe(name);
        expect(entry?.managerOnly, name).toBe(true);
      }
    });
  });

  describe('VISION — every entry managerOnly', () => {
    const vision = NAV_MODES.find((mode) => mode.id === 'vision')!;

    it('has exactly CV training, CV model registry, Geo regions, in that order, all managerOnly', () => {
      expect(vision.entries.map((entry) => entry.name)).toEqual(['CV training', 'CV model registry', 'Geo regions']);
      for (const entry of vision.entries) {
        expect(entry.managerOnly, entry.name).toBe(true);
      }
    });
  });

  describe('SYSTEM — footer group, one deliberate managerOnly carve-out plus the new Settings entry', () => {
    const system = NAV_MODES.find((mode) => mode.id === 'system')!;

    it('renders in the sidebar footer', () => {
      expect(system.footer).toBe(true);
    });

    it('has exactly System status, Audit trail, Debug, Settings, in that order', () => {
      expect(system.entries.map((entry) => entry.name)).toEqual(['System status', 'Audit trail', 'Debug', 'Settings']);
    });

    /**
     * docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1: "System status" is not managerOnly — an operator whose CV
     * pipeline just died needs to see why. "Settings" is not managerOnly either — every signed-in
     * user, pilot included, owns account/detection/controller preferences. Audit trail and Debug stay
     * managerOnly, mirroring the backend's own gates.
     */
    it('System status and Settings are ungated; Audit trail and Debug are managerOnly', () => {
      const byName = (name: string) => system.entries.find((entry) => entry.name === name)!;
      expect(byName('System status').managerOnly).toBeFalsy();
      expect(byName('Settings').managerOnly).toBeFalsy();
      expect(byName('Audit trail').managerOnly).toBe(true);
      expect(byName('Debug').managerOnly).toBe(true);
    });

    it('Audit trail still targets /monitor/audit — only its group changed, not its route', () => {
      const auditTrail = system.entries.find((entry) => entry.name === 'Audit trail');
      expect(auditTrail?.to).toBe('/monitor/audit');
    });

    it('Settings is new this wave and targets /settings', () => {
      const settings = system.entries.find((entry) => entry.name === 'Settings');
      expect(settings).toBeDefined();
      expect(settings?.to).toBe('/settings');
    });
  });

  /**
   * Entry-count regression guard (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1's own illustrative
   * "25 → 15 for a manager, 11 → 10 for a pilot"). That figure is the *eventual*, post-wave-W7 count
   * — it bakes in `Maintenance` (W7, not built) and the merged Inventory page's tab consolidation
   * (W4, not built); summing the plan's own §3.1 itemization for "15" independently gives 17-18, not
   * 15, even before Asset categories/Inventory reports/Devices are placed anywhere (the plan's own
   * §3.1 mermaid diagram never names a group for any of the three). This wave (W1) is a pure IA
   * regroup, not a feature removal, so those three fully-built pages stay in the rail (`fleet` — see
   * `nav-entries.ts`'s own class doc) rather than vanishing with no replacement. The PILOT figure
   * below does land exactly on the plan's own "10" (every item it names for a pilot is accounted
   * for); the MANAGER figure is the TRUE count this wave produces, not the plan's estimate.
   */
  it('a PILOT sees exactly the plan\'s own 10 entries; a MANAGER/ADMIN sees the true full set (20, not the plan\'s illustrative "15" — see this test\'s own doc comment)', () => {
    const pilotVisible = visibleEntries(false);
    const managerVisible = visibleEntries(true);

    expect(pilotVisible.map((entry) => entry.name)).toEqual([
      'Cockpit',
      'Wall',
      'Readiness',
      'Command',
      'Activity',
      'Replay library',
      'Alerts center',
      'Inventory',
      'System status',
      'Settings',
    ]);
    expect(pilotVisible.length).toBe(10);
    expect(managerVisible.length).toBe(20);
  });
});

describe('navModeById', () => {
  it('returns the matching group', () => {
    expect(navModeById('fleet').label).toBe('Fleet');
  });

  it('throws for an id outside the frozen set', () => {
    // @ts-expect-error deliberately an invalid id, to exercise the guard — 'manage' was a valid id
    // before this wave split it into fleet/vision/system, which is exactly why it's a good probe here.
    expect(() => navModeById('manage')).toThrow('Unknown nav mode: manage');
  });
});
