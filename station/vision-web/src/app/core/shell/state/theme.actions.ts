import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { Theme } from './theme.model';

/** The only source of theme change: a person clicking the switch in the sidebar footer or in
 * Settings (docs/plans/done/VISUAL-REFRESH-PLAN.md wave 1). Nothing server-side has an opinion. */
export const ThemePageActions = createActionGroup({
  source: 'Theme Page',
  events: {
    'Theme Selected': props<{ theme: Theme }>(),
    'Theme Toggled': emptyProps(),
  },
});
