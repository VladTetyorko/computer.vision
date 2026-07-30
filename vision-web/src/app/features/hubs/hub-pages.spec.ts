import type { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ManageHub } from './manage-hub';
import { MonitorHub } from './monitor-hub';
import { navModeById } from './nav-entries';
import { OperateHub } from './operate-hub';

/**
 * One parametrized spec for all three hub pages (`operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts`)
 * — they're intentionally identical in shape (a thin `NAV_MODES` filter + a `vision-tile-grid`), so
 * this exercises the one real behavior each has: it renders exactly its own mode's entries as
 * `vision-nav-tile`s, each resolving to that entry's `to` route — no tile ever renders a `#`/empty
 * `href`, i.e. no hub tile 404s (docs/UI-REDESIGN-PLAN.md Wave 1 Done criteria).
 */
const HUBS: readonly { readonly id: 'operate' | 'monitor' | 'manage'; readonly Component: Type<unknown> }[] = [
  { id: 'operate', Component: OperateHub },
  { id: 'monitor', Component: MonitorHub },
  { id: 'manage', Component: ManageHub },
];

describe('Hub launcher pages', () => {
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
