import { describe, expect, it } from 'vitest';
import { SidebarPageActions } from './sidebar.actions';
import { initialSidebarState, sidebarCollapsed } from './sidebar.model';
import { sidebarFeature } from './sidebar.reducer';

const reduce = sidebarFeature.reducer;

/** Pure spec — the precedence rule and the disclosures. Persistence (which keys are written, and
 * which deliberately are not) is covered end to end by `sidebar-facade.spec.ts`. */
describe('sidebar reducer', () => {
  it('starts expanded with both disclosures closed', () => {
    expect(sidebarCollapsed(initialSidebarState)).toBe(false);
    expect(initialSidebarState.advancedOpen).toBe(false);
    expect(initialSidebarState.upcomingOpen).toBe(false);
  });

  it('a full-bleed route collapses without touching the cross-session preference', () => {
    const entered = reduce(initialSidebarState, SidebarPageActions.routeEntered({ fullBleed: true }));
    expect(sidebarCollapsed(entered)).toBe(true);
    expect(entered.preference).toBe(false);

    const left = reduce(entered, SidebarPageActions.routeEntered({ fullBleed: false }));
    expect(sidebarCollapsed(left)).toBe(false); // preference never changed, so leaving restores it
  });

  it('CAN be expanded on a full-bleed route, and that choice does not become the global default', () => {
    const onFly = reduce(initialSidebarState, SidebarPageActions.routeEntered({ fullBleed: true }));
    const expanded = reduce(onFly, SidebarPageActions.toggled());
    expect(sidebarCollapsed(expanded)).toBe(false); // the whole point: the toggle works here
    expect(expanded.preference).toBe(false);

    // Navigating on gives the next page its own honest default rather than inheriting the override.
    const ordinary = reduce(expanded, SidebarPageActions.routeEntered({ fullBleed: false }));
    expect(sidebarCollapsed(ordinary)).toBe(false);
    expect(sidebarCollapsed(reduce(ordinary, SidebarPageActions.routeEntered({ fullBleed: true })))).toBe(true);
  });

  it('a manual collapse on an ordinary page restates the preference and outranks a later route default', () => {
    const ordinary = reduce(initialSidebarState, SidebarPageActions.routeEntered({ fullBleed: false }));
    const collapsed = reduce(ordinary, SidebarPageActions.toggled());
    expect(sidebarCollapsed(collapsed)).toBe(true);
    expect(collapsed.preference).toBe(true);

    const nextPage = reduce(collapsed, SidebarPageActions.routeEntered({ fullBleed: false }));
    expect(sidebarCollapsed(nextPage)).toBe(true);
    expect(sidebarCollapsed(reduce(nextPage, SidebarPageActions.toggled()))).toBe(false);
  });

  it('the two disclosures flip independently of collapsed and of each other', () => {
    const advanced = reduce(initialSidebarState, SidebarPageActions.advancedToggled());
    expect(advanced.advancedOpen).toBe(true);
    expect(advanced.upcomingOpen).toBe(false);
    expect(sidebarCollapsed(advanced)).toBe(false);

    const both = reduce(advanced, SidebarPageActions.upcomingToggled());
    expect(both.upcomingOpen).toBe(true);
    expect(both.advancedOpen).toBe(true);

    const closedAdvanced = reduce(both, SidebarPageActions.advancedToggled());
    expect(closedAdvanced.advancedOpen).toBe(false);
    expect(closedAdvanced.upcomingOpen).toBe(true);
  });

  it('Advanced Set / Upcoming Set are idempotent — a second "open" event must not flip it closed', () => {
    const open = reduce(initialSidebarState, SidebarPageActions.advancedSet({ open: true }));
    expect(reduce(open, SidebarPageActions.advancedSet({ open: true })).advancedOpen).toBe(true);
    expect(reduce(open, SidebarPageActions.upcomingSet({ open: false })).upcomingOpen).toBe(false);
  });

  it('selectCollapsed projects the precedence rule', () => {
    expect(sidebarFeature.selectCollapsed.projector({ ...initialSidebarState, override: false, preference: true })).toBe(false);
    expect(sidebarFeature.selectCollapsed.projector({ ...initialSidebarState, preference: true })).toBe(true);
    expect(sidebarFeature.selectCollapsed.projector({ ...initialSidebarState, fullBleed: true })).toBe(true);
  });
});
