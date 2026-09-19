import { createFeature, createReducer, on } from '@ngrx/store';
import { ThemePageActions } from './theme.actions';
import { initialThemeState, type Theme } from './theme.model';

function flip(theme: Theme): Theme {
  return theme === 'light' ? 'dark' : 'light';
}

export const themeFeature = createFeature({
  name: 'theme',
  reducer: createReducer(
    initialThemeState,
    on(ThemePageActions.themeSelected, (state, { theme }) => ({ ...state, theme })),
    on(ThemePageActions.themeToggled, (state) => ({ ...state, theme: flip(state.theme) })),
  ),
});
