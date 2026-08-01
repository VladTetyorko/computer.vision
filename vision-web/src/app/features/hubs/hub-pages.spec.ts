import type { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AuthStore } from '../../core/auth/auth-store';
import { VisionApi } from '../../core/api/vision-api';
import type { MeResponse, Role } from '../../core/api/models';
import { ManageHub } from './manage-hub';
import { MonitorHub } from './monitor-hub';
import { navModeById } from './nav-entries';
import { OperateHub } from './operate-hub';

/**
 * One parametrized spec for the two **ungrouped, ungated** hub pages (`operate-hub.ts`/
 * `monitor-hub.ts`) — they're intentionally identical in shape (a thin `NAV_MODES` filter + a
 * `vision-tile-grid`), so this exercises the one real behavior each has: it renders exactly its own
 * mode's entries as `vision-nav-tile`s, each resolving to that entry's `to` route — no tile ever
 * renders a `#`/empty `href`, i.e. no hub tile 404s (docs/UI-REDESIGN-PLAN.md Wave 1 Done criteria).
 *
 * `ManageHub` (docs/UX-SIMPLIFY-REVIEW.md F3) now groups and role-scopes its own entries via
 * `AuthStore`/`canManageOrg`, so it no longer fits this "render every entry" shape — it gets its own
 * `describe` block below, mirroring `shared/ui/identity-chip.spec.ts`'s own "provide a real `AuthStore`
 * with a mocked `VisionApi`" pattern for exercising a role gate without a real backend.
 */
const HUBS: readonly { readonly id: 'operate' | 'monitor'; readonly Component: Type<unknown> }[] = [
  { id: 'operate', Component: OperateHub },
  { id: 'monitor', Component: MonitorHub },
];

describe('Hub launcher pages (ungrouped, ungated)', () => {
  for (const { id, Component } of HUBS) {
    it(`${id}: renders one vision-nav-tile per NAV_MODES entry, each with a real routerLink`, () => {
      TestBed.configureTestingModule({ providers: [provideRouter([])] });
      const fixture = TestBed.createComponent(Component);
      fixture.detectChanges();

      const mode = navModeById(id);
      const tiles = fixture.nativeElement.querySelectorAll('vision-nav-tile a.nav-tile') as NodeListOf<HTMLAnchorElement>;

      expect(tiles.length).toBe(mode.entries.length);
      const hrefs = Array.from(tiles).map((a) => a.getAttribute('href'));
      for (const entry of mode.entries) {
        expect(hrefs, `${id} tile for "${entry.name}"`).toContain(entry.to);
      }
      // Every tile is a real, non-empty href — never a dead `#`/blank link.
      for (const href of hrefs) {
        expect(href, 'tile href').toBeTruthy();
      }
    });

    it(`${id}: page heading names the mode`, () => {
      TestBed.configureTestingModule({ providers: [provideRouter([])] });
      const fixture = TestBed.createComponent(Component);
      fixture.detectChanges();

      const mode = navModeById(id);
      expect(fixture.nativeElement.querySelector('h1')?.textContent ?? '').toContain(mode.label);
    });
  }
});

function meResponse(topRole: Role): MeResponse {
  return {
    userId: 'u-1',
    username: 'user',
    displayName: 'Test User',
    email: 'user@example.com',
    memberships: [{ groupId: 'g-1', groupName: 'HQ', role: topRole }],
    topRole,
    authEnabled: true,
  };
}

async function renderManageHub(topRole: Role) {
  // A fresh module every call — needed because one `it` below calls this helper more than once
  // (TestBed refuses to `configureTestingModule` again once a previous call's component has
  // already been instantiated).
  TestBed.resetTestingModule();
  const api = { authMe: vi.fn().mockResolvedValue(meResponse(topRole)) };
  TestBed.configureTestingModule({
    providers: [provideRouter([]), AuthStore, { provide: VisionApi, useValue: api }],
  });
  const store = TestBed.inject(AuthStore);
  await store.ready;
  const fixture = TestBed.createComponent(ManageHub);
  fixture.detectChanges();
  return fixture;
}

function tileHrefs(fixture: { nativeElement: HTMLElement }): (string | null)[] {
  return Array.from(fixture.nativeElement.querySelectorAll('vision-nav-tile a.nav-tile')).map((a) =>
    a.getAttribute('href'),
  );
}

function sectionTitles(fixture: { nativeElement: HTMLElement }): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('vision-section-header h2')).map(
    (el) => el.textContent ?? '',
  );
}

/**
 * `ManageHub`'s own role-scoping/grouping behavior (docs/UX-SIMPLIFY-REVIEW.md F3) — the Manage hub
 * used to render all 10 `NAV_MODES` entries flat, regardless of role; now it groups the
 * configuration/diagnostics/advanced tiles under their own labelled section and hides all three
 * sections entirely for anyone who isn't ADMIN/MANAGER (`canManageOrg`).
 */
describe('ManageHub — grouped, role-scoped', () => {
  it('page heading still names Manage, regardless of role', async () => {
    const fixture = await renderManageHub('PILOT');
    expect(fixture.nativeElement.querySelector('h1')?.textContent ?? '').toContain('Manage');
  });

  it('a PILOT sees only the ungated everyday tiles — Assets and Add source, no group sections', async () => {
    const fixture = await renderManageHub('PILOT');

    expect(tileHrefs(fixture)).toEqual(['/assets', '/add-source']);
    expect(sectionTitles(fixture)).toEqual([]);
  });

  it('an ADMIN sees every NAV_MODES manage entry, grouped into Configuration/Diagnostics/Advanced', async () => {
    const fixture = await renderManageHub('ADMIN');
    const mode = navModeById('manage');

    const hrefs = tileHrefs(fixture);
    expect(hrefs).toHaveLength(mode.entries.length);
    for (const entry of mode.entries) {
      expect(hrefs, `tile for "${entry.name}"`).toContain(entry.to);
    }
    expect(sectionTitles(fixture)).toEqual(['Configuration', 'Diagnostics', 'Advanced']);
  });

  it('a MANAGER (not just ADMIN) also sees the full grouped set — canManageOrg covers both', async () => {
    const fixture = await renderManageHub('MANAGER');
    expect(tileHrefs(fixture)).toHaveLength(navModeById('manage').entries.length);
  });

  it('every rendered tile is a real, non-empty href, for every role', async () => {
    for (const role of ['PILOT', 'MANAGER', 'ADMIN'] as const) {
      const fixture = await renderManageHub(role);
      for (const href of tileHrefs(fixture)) {
        expect(href, `${role} tile href`).toBeTruthy();
      }
    }
  });
});
