import { TestBed } from '@angular/core/testing';
import { ROUTER_NAVIGATED } from '@ngrx/router-store';
import { Store } from '@ngrx/store';
import { beforeEach, describe, expect, it } from 'vitest';
import { provideAppState } from '../state/app-state';
import { OverlayFacade } from './overlay-facade';
import { OverlayHostRegistry } from './overlay-host-registry';

/**
 * A fake overlay's DOM — a `root` containing a real `trigger` button, both attached to
 * `document.body` so `Node.contains`/`.focus()` behave exactly as they do for the real
 * `identity-chip`/`notification-bell` hosts, not merely detached nodes a real browser would treat
 * differently.
 */
function mountHost(): { root: HTMLElement; trigger: HTMLButtonElement; cleanup: () => void } {
  const root = document.createElement('div');
  const trigger = document.createElement('button');
  root.appendChild(trigger);
  document.body.appendChild(root);
  return { root, trigger, cleanup: () => root.remove() };
}

/**
 * `OverlayFacade` end to end — facade → action → reducer → effect, replacing `overlay-store.spec.ts`
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §8). Real `provideAppState()`, no `Router`: the
 * navigation-close case dispatches `@ngrx/router-store`'s own `ROUTER_NAVIGATED` action directly
 * (see `overlay.effects.ts#closeOnNavigation$`'s doc comment for why this slice listens for that
 * action instead of injecting `Router`) rather than standing up a real `Router` + routes.
 */
describe('OverlayFacade', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    TestBed.configureTestingModule({ providers: [provideAppState()] });
  });

  it('starts with nothing open', () => {
    const facade = TestBed.inject(OverlayFacade);
    expect(facade.active()).toBeNull();
    expect(facade.isOpen('identity-menu')).toBe(false);
    expect(facade.isOpen('notification-bell')).toBe(false);
    expect(facade.isOpen('sidebar-mobile')).toBe(false);
  });

  it('is exclusive — opening a second overlay closes the first (docs/plans/done/UI-STATE-PLAN.md §1 D1)', () => {
    const facade = TestBed.inject(OverlayFacade);
    facade.open('notification-bell');
    expect(facade.isOpen('notification-bell')).toBe(true);

    facade.open('identity-menu');
    expect(facade.isOpen('identity-menu')).toBe(true);
    expect(facade.isOpen('notification-bell')).toBe(false);
  });

  it('toggle() opens then closes the same id', () => {
    const facade = TestBed.inject(OverlayFacade);
    facade.toggle('sidebar-mobile');
    expect(facade.isOpen('sidebar-mobile')).toBe(true);
    facade.toggle('sidebar-mobile');
    expect(facade.active()).toBeNull();
  });

  it("a stale close(id) is a no-op — mirrors UiStore.close's own semantic, preserved not reimplemented", () => {
    const facade = TestBed.inject(OverlayFacade);
    facade.open('notification-bell');
    facade.close('identity-menu'); // not the one open — must not clobber notification-bell
    expect(facade.isOpen('notification-bell')).toBe(true);

    facade.close('notification-bell');
    expect(facade.active()).toBeNull();
  });

  it('closes on ROUTER_NAVIGATED (§1 D2 — this survived a route change before this slice existed)', () => {
    const facade = TestBed.inject(OverlayFacade);
    const store = TestBed.inject(Store);
    facade.open('identity-menu');
    expect(facade.isOpen('identity-menu')).toBe(true);

    store.dispatch({ type: ROUTER_NAVIGATED });
    expect(facade.active()).toBeNull();

    // Not a one-shot: a second, unrelated navigation still closes whatever got reopened meanwhile.
    facade.open('notification-bell');
    store.dispatch({ type: ROUTER_NAVIGATED });
    expect(facade.active()).toBeNull();
  });

  describe('Escape', () => {
    it('closes whatever is open and returns focus to its registered trigger (§4 a11y bullet)', () => {
      const facade = TestBed.inject(OverlayFacade);
      const host = mountHost();
      facade.register('identity-menu', host.root, host.trigger);
      facade.open('identity-menu');

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

      expect(facade.active()).toBeNull();
      expect(document.activeElement).toBe(host.trigger);
      host.cleanup();
    });

    it('is a no-op when nothing is open', () => {
      const facade = TestBed.inject(OverlayFacade);
      expect(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))).not.toThrow();
      expect(facade.active()).toBeNull();
    });

    it('ignores every other key', () => {
      const facade = TestBed.inject(OverlayFacade);
      facade.open('notification-bell');
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      expect(facade.isOpen('notification-bell')).toBe(true);
    });
  });

  describe('outside click (§2.2 rule 3, §1 D3)', () => {
    it("closes the open overlay on a click outside its registered root", () => {
      const facade = TestBed.inject(OverlayFacade);
      const host = mountHost();
      facade.register('notification-bell', host.root, host.trigger);
      facade.open('notification-bell');

      const outside = document.createElement('div');
      document.body.appendChild(outside);
      outside.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(facade.active()).toBeNull();
      host.cleanup();
      outside.remove();
    });

    it('does not close on a click inside the registered root — e.g. the dropdown content itself', () => {
      const facade = TestBed.inject(OverlayFacade);
      const host = mountHost();
      const dropdownRow = document.createElement('span');
      host.root.appendChild(dropdownRow);
      facade.register('notification-bell', host.root, host.trigger);
      facade.open('notification-bell');

      dropdownRow.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(facade.isOpen('notification-bell')).toBe(true);
      host.cleanup();
    });

    it('does not close while a *different* overlay has no registered host at all (no host ⇒ every click is "outside")', () => {
      const facade = TestBed.inject(OverlayFacade);
      facade.open('sidebar-mobile');
      document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(facade.active()).toBeNull();
    });

    it("clicking the trigger while open closes it once — not close-then-reopen (the plan's own explicit callout)", () => {
      const facade = TestBed.inject(OverlayFacade);
      const host = mountHost();
      facade.register('notification-bell', host.root, host.trigger);
      // Mirrors the real trigger's own `(click)="toggle(...)"` binding — registered directly on the
      // element, exactly like Angular's own event binding, so DOM bubble order is genuinely
      // exercised rather than assumed.
      host.trigger.addEventListener('click', () => facade.toggle('notification-bell'));
      facade.open('notification-bell');

      host.trigger.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(facade.active()).toBeNull();
      host.cleanup();
    });

    it('clicking the trigger while closed opens it and the document listener leaves it open', () => {
      const facade = TestBed.inject(OverlayFacade);
      const host = mountHost();
      facade.register('notification-bell', host.root, host.trigger);
      host.trigger.addEventListener('click', () => facade.toggle('notification-bell'));

      host.trigger.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(facade.isOpen('notification-bell')).toBe(true);
      host.cleanup();
    });
  });

  it('register() overwrites a stale entry for the same id (the sidebar hamburger/scrim swap case)', () => {
    const facade = TestBed.inject(OverlayFacade);
    const first = mountHost();
    const second = mountHost();
    facade.register('sidebar-mobile', first.root, first.trigger);
    facade.register('sidebar-mobile', second.root, second.trigger);
    facade.open('sidebar-mobile');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(document.activeElement).toBe(second.trigger);
    first.cleanup();
    second.cleanup();
  });

  it('register() delegates straight to OverlayHostRegistry (no store round-trip for DOM refs)', () => {
    const facade = TestBed.inject(OverlayFacade);
    const registry = TestBed.inject(OverlayHostRegistry);
    const host = mountHost();
    facade.register('identity-menu', host.root, host.trigger);
    expect(registry.get('identity-menu')).toEqual({ root: host.root, trigger: host.trigger });
    host.cleanup();
  });
});
