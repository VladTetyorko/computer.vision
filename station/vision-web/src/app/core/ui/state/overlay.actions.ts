import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { GlobalOverlayId } from './overlay.model';

/**
 * One source, `'Overlay Page'` — mirroring `sidebar.actions.ts#SidebarPageActions`'s own precedent
 * of folding a fact the shell notices on the user's behalf (`'Route Entered'`, dispatched from a
 * `Router.events` subscription, not a template `(click)`) into the same `'…  Page'` source as an
 * actual click handler: `'Escape Pressed'`/`'Outside Clicked'`/`'Navigated'` are still expressions of
 * user intent ("dismiss this overlay"), just observed by an effect instead of a trigger's own
 * `(click)`. There is no `'… API'`/`'… Socket'` source here — nothing server-side has an opinion
 * about which shell overlay is open.
 */
export const OverlayPageActions = createActionGroup({
  source: 'Overlay Page',
  events: {
    Opened: props<{ id: GlobalOverlayId }>(),
    /** `id` omitted closes whichever overlay is open; given, closes only if it is the one open —
     *  mirrors `UiStore.close`'s own "a stale close from an already-replaced overlay is a no-op". */
    Closed: props<{ id?: GlobalOverlayId }>(),
    Toggled: props<{ id: GlobalOverlayId }>(),
    'Escape Pressed': emptyProps(),
    'Outside Clicked': emptyProps(),
    Navigated: emptyProps(),
  },
});
