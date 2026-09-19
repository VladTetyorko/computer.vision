import { describe, expect, it } from 'vitest';
import { OverlayPageActions } from './overlay.actions';
import { initialOverlayState, type OverlayState } from './overlay.model';
import { overlayFeature } from './overlay.reducer';

const { reducer } = overlayFeature;

describe('overlayFeature reducer', () => {
  it('starts with nothing open', () => {
    expect(initialOverlayState).toEqual<OverlayState>({ active: null });
  });

  it('opened sets the active overlay', () => {
    const state = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'notification-bell' }));
    expect(state.active).toBe('notification-bell');
  });

  it('opened is exclusive — opening a second overlay replaces the first (docs/plans/done/UI-STATE-PLAN.md §1 D1)', () => {
    const afterFirst = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'notification-bell' }));
    const afterSecond = reducer(afterFirst, OverlayPageActions.opened({ id: 'identity-menu' }));
    expect(afterSecond.active).toBe('identity-menu');
  });

  it('closed with no id closes whichever is open', () => {
    const open = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'sidebar-mobile' }));
    expect(reducer(open, OverlayPageActions.closed({})).active).toBeNull();
  });

  it('closed(id) is a no-op when id is not the one open — mirrors UiStore.close\'s own semantic', () => {
    const open = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'notification-bell' }));
    const state = reducer(open, OverlayPageActions.closed({ id: 'identity-menu' }));
    expect(state.active).toBe('notification-bell');
  });

  it('closed(id) closes when id is the one open', () => {
    const open = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'notification-bell' }));
    expect(reducer(open, OverlayPageActions.closed({ id: 'notification-bell' })).active).toBeNull();
  });

  it('toggled opens a closed overlay', () => {
    const state = reducer(initialOverlayState, OverlayPageActions.toggled({ id: 'sidebar-mobile' }));
    expect(state.active).toBe('sidebar-mobile');
  });

  it('toggled closes the same, already-open overlay', () => {
    const open = reducer(initialOverlayState, OverlayPageActions.toggled({ id: 'sidebar-mobile' }));
    expect(reducer(open, OverlayPageActions.toggled({ id: 'sidebar-mobile' })).active).toBeNull();
  });

  it.each([
    ['escapePressed', OverlayPageActions.escapePressed()],
    ['outsideClicked', OverlayPageActions.outsideClicked()],
    ['navigated', OverlayPageActions.navigated()],
  ])('%s closes whatever is open', (_name, action) => {
    const open = reducer(initialOverlayState, OverlayPageActions.opened({ id: 'identity-menu' }));
    expect(reducer(open, action).active).toBeNull();
  });

  it.each([
    ['escapePressed', OverlayPageActions.escapePressed()],
    ['outsideClicked', OverlayPageActions.outsideClicked()],
    ['navigated', OverlayPageActions.navigated()],
  ])('%s is a no-op (same reference) when nothing is open', (_name, action) => {
    expect(reducer(initialOverlayState, action)).toBe(initialOverlayState);
  });
});
