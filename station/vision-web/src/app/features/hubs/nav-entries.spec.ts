import { describe, expect, it } from 'vitest';
import { NAV_MODES, navModeById } from './nav-entries';

/** Every entry a session with (`canManage`) or without (`!canManage`) the `MANAGE_ORG` capability
 *  would see in the sidebar — mirrors `shared/ui/app-sidebar/app-sidebar.ts#modes`'s own filter
 *  exactly (every entry in `NAV_MODES` today names that one capability, so this stays a plain
 *  boolean rather than importing `AuthCapability`/`hasCapability` for a real per-capability check),
 *  kept here as a small local helper rather than imported so this spec stays a pure data-level check
 *  with no Angular/TestBed dependency (matching this file's own pre-existing "no Angular" precedent). */
function visibleEntries(canManage: boolean) {
  return NAV_MODES.flatMap((mode) => mode.entries.filter((entry) => !entry.requires || canManage));
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
   * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 5 — a transmitter layout is configuration a user
   * visits rarely, not an everyday Operate/Manage door. `/manage/controller` stays live and ungated;
   * it is reached from `/settings`'s own link list instead (`features/settings/account-settings.html`).
   * `/settings/detection` never comes back as a `NavEntry.to` either — it now redirects to
   * `/vision/profiles` (`settings.routes.ts`), which *is* on the rail (see the VISION describe block
   * below) — wave W6's supersession of the WAREHOUSE-UX-era move, not a second instance of it.
   */
  it('Controller has left the rail entirely; /settings/detection is never a live NavEntry.to', () => {
    for (const mode of NAV_MODES) {
      expect(mode.entries.some((entry) => entry.to === '/settings/detection'), mode.id).toBe(false);
      expect(mode.entries.some((entry) => entry.to === '/manage/controller'), mode.id).toBe(false);
    }
  });

  describe('OPERATE — Readiness rename (rule 4)', () => {
    const operate = NAV_MODES.find((mode) => mode.id === 'operate')!;

    it('has exactly Cockpit, Wall, Readiness, Crew seat, in that order, none gated', () => {
      // "Crew seat" joined this wave (docs/plans/active/CREW-CONTROL-PLAN.md §4, wave W3) — the
      // sensor-operator seat, ungated like every other entry in this group.
      expect(operate.entries.map((entry) => entry.name)).toEqual(['Cockpit', 'Wall', 'Readiness', 'Crew seat']);
      for (const entry of operate.entries) {
        expect(entry.requires, entry.name).toBeFalsy();
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

    it('has exactly Command, Activity, Replay library, Alerts center, in that order, none gated', () => {
      expect(monitor.entries.map((entry) => entry.name)).toEqual(['Command', 'Activity', 'Replay library', 'Alerts center']);
      for (const entry of monitor.entries) {
        expect(entry.requires, entry.name).toBeFalsy();
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

  describe('FLEET — three W1 renames, plus W4 folds three entries into Inventory and adds Maintenance', () => {
    const fleet = NAV_MODES.find((mode) => mode.id === 'fleet')!;

    it('has exactly Inventory, Add vehicle, Crew, Maintenance, in that order', () => {
      expect(fleet.entries.map((entry) => entry.name)).toEqual(['Inventory', 'Add vehicle', 'Crew', 'Maintenance']);
    });

    it('Assets is renamed to "Inventory", stays ungrouped and ungated', () => {
      const inventory = fleet.entries.find((entry) => entry.to === '/assets');
      expect(inventory).toBeDefined();
      expect(inventory?.name).toBe('Inventory');
      expect(inventory?.requires).toBeFalsy();
      expect(fleet.entries.some((entry) => entry.name === 'Assets')).toBe(false);
    });

    it('"Add source" is renamed to "Add vehicle", stays MANAGE_ORG-gated', () => {
      const addVehicle = fleet.entries.find((entry) => entry.to === '/add-source');
      expect(addVehicle).toBeDefined();
      expect(addVehicle?.name).toBe('Add vehicle');
      expect(addVehicle?.requires).toBe('MANAGE_ORG');
      expect(fleet.entries.some((entry) => entry.name === 'Add source')).toBe(false);
    });

    it('"Pilots / roster" is renamed to "Crew", stays MANAGE_ORG-gated', () => {
      const crew = fleet.entries.find((entry) => entry.to === '/manage/roster');
      expect(crew).toBeDefined();
      expect(crew?.name).toBe('Crew');
      expect(crew?.requires).toBe('MANAGE_ORG');
      expect(fleet.entries.some((entry) => entry.name === 'Pilots / roster')).toBe(false);
    });

    /**
     * Wave W4 (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3) folds all three of the old carried-over
     * entries into `InventoryPage`'s own tabs — Asset categories → `?tab=categories`, Devices →
     * `?tab=links`, Inventory reports → the KPI strip above Vehicles (no tab of its own). None of the
     * three keeps a standalone nav entry; their routes still resolve (as redirects into `/assets`),
     * just not from the rail.
     */
    it('Asset categories, Inventory reports and Devices no longer have their own nav entry', () => {
      for (const [to, name] of [
        ['/manage/categories', 'Asset categories'],
        ['/manage/reports', 'Inventory reports'],
        ['/devices', 'Devices'],
      ] as const) {
        expect(fleet.entries.some((entry) => entry.to === to || entry.name === name), name).toBe(false);
      }
    });

    it('Maintenance is new this wave (W4 — the page itself shipped in W7, this nav entry did not), targets /fleet/maintenance, MANAGE_ORG-gated', () => {
      const maintenance = fleet.entries.find((entry) => entry.to === '/fleet/maintenance');
      expect(maintenance).toBeDefined();
      expect(maintenance?.name).toBe('Maintenance');
      expect(maintenance?.requires).toBe('MANAGE_ORG');
    });
  });

  describe('VISION — every entry MANAGE_ORG-gated', () => {
    const vision = NAV_MODES.find((mode) => mode.id === 'vision')!;

    it('has exactly Profiles, CV training, CV model registry, Geo regions, in that order, all MANAGE_ORG-gated', () => {
      expect(vision.entries.map((entry) => entry.name)).toEqual([
        'Profiles',
        'CV training',
        'CV model registry',
        'Geo regions',
      ]);
      for (const entry of vision.entries) {
        expect(entry.requires, entry.name).toBe('MANAGE_ORG');
      }
    });

    /** docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6 — replaces the old Settings-page "Detection
     *  defaults" link outright; see this file's own "Controller has left the rail" test above. */
    it('Profiles targets /vision/profiles', () => {
      const profiles = vision.entries.find((entry) => entry.name === 'Profiles');
      expect(profiles?.to).toBe('/vision/profiles');
    });
  });

  describe('SYSTEM — footer group, one deliberate ungated carve-out plus the new Settings entry', () => {
    const system = NAV_MODES.find((mode) => mode.id === 'system')!;

    it('renders in the sidebar footer', () => {
      expect(system.footer).toBe(true);
    });

    it('has exactly System status, Audit trail, Debug, Settings, in that order', () => {
      expect(system.entries.map((entry) => entry.name)).toEqual(['System status', 'Audit trail', 'Debug', 'Settings']);
    });

    /**
     * docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1: "System status" is ungated — an operator whose CV
     * pipeline just died needs to see why. "Settings" is ungated too — every signed-in user, pilot
     * included, owns account/detection/controller preferences. Audit trail and Debug stay
     * `MANAGE_ORG`-gated, mirroring the backend's own gates.
     */
    it('System status and Settings are ungated; Audit trail and Debug are MANAGE_ORG-gated', () => {
      const byName = (name: string) => system.entries.find((entry) => entry.name === name)!;
      expect(byName('System status').requires).toBeFalsy();
      expect(byName('Settings').requires).toBeFalsy();
      expect(byName('Audit trail').requires).toBe('MANAGE_ORG');
      expect(byName('Debug').requires).toBe('MANAGE_ORG');
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
   * "25 → 15 for a manager, 11 → 10 for a pilot"). Wave W1 landed the manager at the TRUE count of 20
   * (not the plan's own "15" estimate — see the W1-era version of this comment for the accounting).
   * **Wave W4 moved the manager count to 18** — three carried-over `MANAGE_ORG`-gated entries fold
   * into Inventory's own tabs/KPI strip (Asset categories, Inventory reports, Devices: −3), and one
   * new gated entry (Maintenance, W7) joins Fleet (+1): 20 − 3 + 1 = 18. **Wave W6
   * (docs/plans/active/CV-SETTINGS-PLAN.md) moves it to 19** — one new gated entry (Profiles)
   * joins Vision (+1): 18 + 1 = 19. The PILOT count is unaffected across both waves — none of the
   * changed entries was ever pilot-visible (every VISION entry, Profiles included, is `MANAGE_ORG`-gated)
   * — it still lands on the plan's own "10". **Wave W3 (docs/plans/active/CREW-CONTROL-PLAN.md §4)
   * moves both counts to +1** — "Crew seat" is ungated, so it joins both the pilot and manager
   * lists alike: pilot 10 + 1 = 11, manager 19 + 1 = 20.
   */
  it('a PILOT sees exactly the plan\'s own 11 entries; a MANAGER/ADMIN sees 20 (19 + 1 new Crew seat — see this test\'s own doc comment)', () => {
    const pilotVisible = visibleEntries(false);
    const managerVisible = visibleEntries(true);

    expect(pilotVisible.map((entry) => entry.name)).toEqual([
      'Cockpit',
      'Wall',
      'Readiness',
      'Crew seat',
      'Command',
      'Activity',
      'Replay library',
      'Alerts center',
      'Inventory',
      'System status',
      'Settings',
    ]);
    expect(pilotVisible.length).toBe(11);
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
