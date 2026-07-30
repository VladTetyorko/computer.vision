import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { WarehousePage } from './warehouse';

/**
 * Mirrors `features/hubs/hub-pages.spec.ts`'s own shape: `/warehouse` renders exactly two tiles
 * (People, Assets), each a real `routerLink` — no `#`/empty href, i.e. no dead link.
 */
describe('WarehousePage', () => {
  it('renders exactly two tiles — People and Assets — each with a real routerLink', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(WarehousePage);
    fixture.detectChanges();

    const tiles = fixture.nativeElement.querySelectorAll('vision-nav-tile a.nav-tile') as NodeListOf<HTMLAnchorElement>;
    expect(tiles.length).toBe(2);

    const hrefs = Array.from(tiles).map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/manage/roster');
    expect(hrefs).toContain('/assets');
    for (const href of hrefs) {
      expect(href, 'tile href').toBeTruthy();
    }
  });

  it('page heading names Warehouse', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(WarehousePage);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1')?.textContent ?? '').toContain('Warehouse');
  });
});
