import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../icon';
import type { IconName } from '../icon-registry';
import { pluralize } from '../text-logic';

/** A `‹ Assets`-style back link rendered at the far left of the bar. */
export interface PageBarCrumb {
  readonly label: string;
  readonly to: string;
}


/**
 * The one page header for every routed page (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2,
 * docs/extracts/design/00-shell.md) — replacing the `.page-head` block (an `<h1>` plus a two-to-four-line
 * explanatory `<p>`, plus an action row) that every page carried its own copy of.
 *
 * **Why this is a component and not just tighter CSS.** `.page-head` cost ~130px of vertical space
 * before the first control on every page — on `/monitor/alerts` that was 130px of instructions above
 * a feed, and on `/operate/preflight` the header was larger than the content it introduced. But the
 * height was a symptom: the real problem was that "what page am I on" was being answered by prose,
 * because the old top bar could not answer it (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F2). Now that the
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
 *
 * **The `?` hint popover closes on `Escape` and on an outside click** (docs/plans/done/UI-STATE-PLAN.md §2.4) —
 * before this, it only closed by clicking its own trigger a second time, so an operator who hit
 * `Escape` expecting *whatever is floating* to go away (the same instinct §2.2's `GlobalOverlayStore`
 * serves for the shell's identity menu/notification bell) had no way to know this one specific popover
 * needed a different gesture. This popover is page-scoped — it dies with the page like every other
 * page overlay (§2.1's "page overlays" tier) — so per §2.4 it keeps its own local `hintOpen` signal
 * rather than moving into the shell's `GlobalOverlayStore`; only the *behavior* (Escape + outside-click)
 * is mirrored, via the same "one document-level listener pair, `root.contains(target)` decides
 * inside-vs-outside" idiom `core/ui/overlay-store.ts#GlobalOverlayStore` uses for the shell's own
 * overlays — see this class's own `onDocumentKeydown`/`onDocumentClick` below.
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
  /** Singular noun for the count chip; pluralised by `shared/ui/text-logic.ts#pluralize`. */
  readonly countNoun = input<string>('item');
  /** Irregular plural, when `${countNoun}s` is wrong. */
  readonly countNounPlural = input<string | null>(null);
  /** Instructional text worth keeping, shown behind a `?` disclosure. */
  readonly hint = input<string | null>(null);
  readonly crumb = input<PageBarCrumb | null>(null);
  /**
   * Optional thumbnail rendered before the icon/title — the asset photo on `/assets/:id`
   * (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3). Degrades silently: the endpoint 404s for an asset with no
   * uploaded photo, and a broken `<img>` must never leave a torn icon in the page header, so a
   * failed load simply hides the element (`showAvatar` below).
   */
  readonly avatarSrc = input<string | null>(null);

  protected readonly hintOpen = signal(false);

  /** The hint's own trigger-plus-body wrapper (`page-bar.html`'s `#hintRoot`) — the outside-click
   *  listener below treats any click landing inside this element (the `?` button *or* the popover
   *  body itself) as "not outside", the same containment trick `GlobalOverlayStore.register`'s own
   *  doc comment explains. `undefined` whenever no `hint` input is bound at all (the `@if` in the
   *  template never renders `#hintRoot` in that case) — every use below already treats that as "there
   *  is nothing to close". */
  private readonly hintRoot = viewChild<ElementRef<HTMLElement>>('hintRoot');

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

  /**
   * `Escape` closes the popover from anywhere on the page — a `document`-level listener (not one
   * scoped to the popover's own DOM, which the trigger button may not even have focus within) because
   * the operator's instinct this satisfies ("get whatever is floating off my screen") isn't "get rid
   * of whatever currently has focus". No-ops instantly whenever `hintOpen()` is already `false`, so
   * this costs nothing on every other keystroke typed anywhere else in the app.
   */
  @HostListener('document:keydown.escape')
  protected onDocumentKeydown(): void {
    this.hintOpen.set(false);
  }

  /**
   * A click anywhere outside {@link hintRoot} (trigger *and* popover body alike) closes it. Bound on
   * `document:click`, the same phase `GlobalOverlayStore`'s own outside-click listener uses — the
   * trigger's own `(click)="toggleHint()"` is a *target-phase* listener on the button itself, so by
   * normal DOM dispatch order it always runs before this bubble-phase `document` listener sees the
   * same click (see that store's own doc comment for the full ordering argument); a click that just
   * closed the popover is read as "already closed" here and left alone, never reopened.
   */
  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (!this.hintOpen()) {
      return;
    }
    const root = this.hintRoot()?.nativeElement;
    if (root && event.target instanceof Node && root.contains(event.target)) {
      return;
    }
    this.hintOpen.set(false);
  }
}
