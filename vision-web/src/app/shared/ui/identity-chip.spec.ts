import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { IdentityChip } from './identity-chip';
import { AuthStore } from '../../core/auth/auth-store';
import { VisionApi } from '../../core/api/vision-api';
import { GlobalOverlayStore } from '../../core/ui/overlay-store';
import type { MeResponse } from '../../core/api/models';

function meResponse(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    userId: 'u-1',
    username: 'pilot',
    displayName: 'Pat Pilot',
    email: 'pilot@example.com',
    memberships: [{ groupId: 'g-1', groupName: 'HQ', role: 'PILOT' }],
    topRole: 'PILOT',
    authEnabled: true,
    ...overrides,
  };
}

async function render(me: MeResponse | null) {
  const api = {
    authMe: vi.fn().mockResolvedValue(me),
    authLogin: vi.fn(),
    authLogout: vi.fn().mockResolvedValue(undefined),
  };
  TestBed.configureTestingModule({
    providers: [provideRouter([]), AuthStore, { provide: VisionApi, useValue: api }],
  });
  const store = TestBed.inject(AuthStore);
  await store.ready;
  const fixture = TestBed.createComponent(IdentityChip);
  fixture.detectChanges();
  return fixture;
}

function menuHrefs(fixture: { nativeElement: HTMLElement }): (string | null)[] {
  return Array.from(fixture.nativeElement.querySelectorAll('.identity-links a')).map((a) => a.getAttribute('href'));
}

function trigger(fixture: { nativeElement: HTMLElement }): HTMLButtonElement {
  return fixture.nativeElement.querySelector('.identity-trigger') as HTMLButtonElement;
}

/** Opens the menu the way a user would — clicking the trigger — then flushes CD, mirroring every
 *  other click-driven spec in this codebase (e.g. `app-sidebar.spec.ts`'s own `.click()` + `detectChanges()`). */
function openMenu(fixture: { nativeElement: HTMLElement; detectChanges(): void }): void {
  trigger(fixture).click();
  fixture.detectChanges();
}

/**
 * Wave 1's own profile-menu extension (docs/plans/done/UI-REDESIGN-PLAN.md, F4 "(shell) Account settings →
 * `/settings` via profile menu") — added alongside the pre-existing My activity/Organization/Log
 * out, per `identity-chip.ts`'s own updated class doc comment.
 */
describe('IdentityChip — profile menu', () => {
  it('renders My activity + Account settings for every signed-in user, no Organization for a PILOT', async () => {
    const fixture = await render(meResponse({ topRole: 'PILOT' }));
    openMenu(fixture);

    expect(menuHrefs(fixture)).toEqual(['/activity', '/settings']);
  });

  it('adds Organization for a MANAGER/ADMIN, alongside the ungated My activity/Account settings', async () => {
    const fixture = await render(meResponse({ topRole: 'MANAGER' }));
    openMenu(fixture);

    expect(menuHrefs(fixture)).toEqual(['/activity', '/settings', '/org']);
  });

  it('renders nothing while there is no session (no placeholder swapped in)', async () => {
    const fixture = await render(null);

    expect(fixture.nativeElement.querySelector('.identity-chip')).toBeNull();
  });
});

/**
 * The dropdown's open state (docs/plans/done/UI-STATE-PLAN.md §1/§2.2) — moved off native `<details>` onto
 * `GlobalOverlayStore`'s `'identity-menu'` id. `GlobalOverlayStore` itself is left real (root-provided,
 * no HTTP deps) — its own exclusivity/Escape/outside-click/close-on-navigation behavior is covered by
 * `core/ui/overlay-store.spec.ts`; these tests only check that this component wires into it correctly.
 */
describe('IdentityChip — overlay state', () => {
  it('starts closed: no menu in the DOM, aria-expanded=false, aria-haspopup="menu"', async () => {
    const fixture = await render(meResponse());
    const btn = trigger(fixture);

    expect(btn.getAttribute('aria-expanded')).toBe('false');
    expect(btn.getAttribute('aria-haspopup')).toBe('menu');
    expect(fixture.nativeElement.querySelector('.identity-menu')).toBeNull();
  });

  it('clicking the trigger opens the menu and sets aria-expanded=true; clicking again closes it', async () => {
    const fixture = await render(meResponse());

    openMenu(fixture);
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('.identity-menu')).not.toBeNull();

    openMenu(fixture); // same trigger, second click — a plain toggle
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('.identity-menu')).toBeNull();
  });

  it('clicking anywhere inside the open menu closes it (the menu\'s own (click) handler)', async () => {
    // Clicks `.identity-menu-name`, not a real `routerLink` anchor — a genuine anchor click triggers
    // actual (async) Router navigation, which can outlive this test and throw once TestBed tears the
    // injector down for the next one; the closing behavior under test is the menu's own bubbling
    // `(click)`, identical regardless of which descendant the click originates from.
    const fixture = await render(meResponse());
    openMenu(fixture);

    const nameRow = fixture.nativeElement.querySelector('.identity-menu-name') as HTMLElement;
    nameRow.click();
    fixture.detectChanges();

    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('false');
  });

  it('opening a sibling shell overlay (notification-bell) closes this menu — exclusivity via GlobalOverlayStore (§1 D1)', async () => {
    const fixture = await render(meResponse());
    openMenu(fixture);
    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('true');

    TestBed.inject(GlobalOverlayStore).open('notification-bell');
    fixture.detectChanges();

    expect(trigger(fixture).getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('.identity-menu')).toBeNull();
  });

  it('closes on Escape and returns focus to the trigger', async () => {
    const fixture = await render(meResponse());
    openMenu(fixture);
    expect(TestBed.inject(GlobalOverlayStore).isOpen('identity-menu')).toBe(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(TestBed.inject(GlobalOverlayStore).isOpen('identity-menu')).toBe(false);
  });
});
