import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../icon';
import type { IconName } from '../icon-registry';

/** A `‹ Assets`-style back link rendered at the far left of the bar. */
export interface PageBarCrumb {
  readonly label: string;
  readonly to: string;
}

/**
 * Regular English pluralisation for the bar's count chip — exported (and unit-tested) rather than
 * inlined so the `asset(s)`/`device(s)` placeholder-looking parenthetical this app rendered on
 * `/assets`, `/devices` and `/manage/reports` has exactly one replacement, not one per page
 * (docs/NAV-IA-REDESIGN-PLAN.md §2.2). Callers that need an irregular plural pass it explicitly.
 */
export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

/**
 * The one page header for every routed page (docs/NAV-IA-REDESIGN-PLAN.md §2.2,
 * docs/design/00-shell.md) — replacing the `.page-head` block (an `<h1>` plus a two-to-four-line
 * explanatory `<p>`, plus an action row) that every page carried its own copy of.
 *
 * **Why this is a component and not just tighter CSS.** `.page-head` cost ~130px of vertical space
 * before the first control on every page — on `/monitor/alerts` that was 130px of instructions above
 * a feed, and on `/operate/preflight` the header was larger than the content it introduced. But the
 * height was a symptom: the real problem was that "what page am I on" was being answered by prose,
 * because the old top bar could not answer it (docs/NAV-IA-REDESIGN-PLAN.md F2). Now that the
 * sidebar marks the active page permanently, the prose is redundant and the title can collapse to
 * one 48px row. Making that row a component — rather than a CSS class each page composes by hand —
 * is what stops the count chip, the filter slot and the action row drifting apart across 18 pages,
 * the same single-source-of-truth reasoning behind `features/hubs/nav-entries.ts`.
 *
 * **Sticky.** The bar sticks to the top of `<main>`'s scroll box, so the page's identity, its count
 * and its primary action stay reachable while a long list scrolls. `<main>` is the scroll container
 * (`app.css`), so a plain `position: sticky; top: 0` is enough — no scroll listener, no z-index war
 * beyond sitting under the side panel (120) and dialogs (150).
 *
 * **`hint` replaces the description.** Where a page's old `<p>` carried genuine instruction rather
 * than restating its title, it moves behind a `?` disclosure instead of being deleted — one click,
 * no permanent vertical cost. Where the title already said it ("Assets", "Devices", "My activity"),
 * the prose is dropped outright at the call site and no `hint` is passed.
 *
 * Content projection, both optional:
 * - `[pageBarFilters]` — search inputs, filter chips/selects. Sits left of the actions, wraps first.
 * - `[pageBarActions]` — the primary button and any icon buttons. Always rightmost.
 */
@Component({
  selector: 'vision-page-bar',
  imports: [Icon, RouterLink],
  templateUrl: './page-bar.html',
  styleUrl: './page-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageBar {
  readonly title = input.required<string>();
  readonly icon = input<IconName | null>(null);
  /** Omit (or pass `null`) to render no count chip — `0` is a real count and still renders. */
  readonly count = input<number | null>(null);
  /** Singular noun for the count chip; pluralised by `pluralize` above. */
  readonly countNoun = input<string>('item');
  /** Irregular plural, when `${countNoun}s` is wrong. */
  readonly countNounPlural = input<string | null>(null);
  /** Instructional text worth keeping, shown behind a `?` disclosure. */
  readonly hint = input<string | null>(null);
  readonly crumb = input<PageBarCrumb | null>(null);
  /**
   * Optional thumbnail rendered before the icon/title — the asset photo on `/assets/:id`
   * (docs/UX-REWORK-PLAN.md §U-d item 3). Degrades silently: the endpoint 404s for an asset with no
   * uploaded photo, and a broken `<img>` must never leave a torn icon in the page header, so a
   * failed load simply hides the element (`showAvatar` below).
   */
  readonly avatarSrc = input<string | null>(null);

  protected readonly hintOpen = signal(false);

  /**
   * The `avatarSrc` value whose `<img>` fired `error`, or `null`. Storing the failed **src** rather
   * than a bare boolean is what makes the retry reset itself: navigating to a different asset
   * changes `avatarSrc()`, which no longer equals `failedSrc()`, so the new photo gets a fresh
   * attempt with no `effect` or manual reset call at the call site (the previous implementation
   * needed exactly such a reset inside the page's own navigation handler).
   */
  private readonly failedSrc = signal<string | null>(null);

  protected readonly showAvatar = computed(() => {
    const src = this.avatarSrc();
    return src !== null && src !== this.failedSrc();
  });

  protected onAvatarError(): void {
    this.failedSrc.set(this.avatarSrc());
  }

  protected readonly countText = computed(() => {
    const value = this.count();
    if (value === null) {
      return null;
    }
    return pluralize(value, this.countNoun(), this.countNounPlural() ?? undefined);
  });

  protected toggleHint(): void {
    this.hintOpen.update((open) => !open);
  }
}
