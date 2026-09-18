import { createActionGroup, emptyProps, props } from '@ngrx/store';

/**
 * `Route Entered` is dispatched by the shell on every `NavigationEnd`; the rest come from the head
 * chevron, the global `[` shortcut, and the two `<details>` disclosures inside the nav.
 */
export const SidebarPageActions = createActionGroup({
  source: 'Sidebar Page',
  events: {
    'Route Entered': props<{ fullBleed: boolean }>(),
    'Toggled': emptyProps(),
    'Advanced Toggled': emptyProps(),
    'Advanced Set': props<{ open: boolean }>(),
    'Upcoming Toggled': emptyProps(),
    'Upcoming Set': props<{ open: boolean }>(),
  },
});
