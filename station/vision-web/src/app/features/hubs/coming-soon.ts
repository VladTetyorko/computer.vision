import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';

/**
 * `ComingSoon` — the one shared placeholder every F4 "scaffold" route lands on
 * (docs/plans/done/UI-REDESIGN-PLAN.md Wave 1; §D-G: "a scaffold never renders invented rows — it states
 * what's coming and links to the nearest real capability"). Every scaffold tile still routes
 * somewhere real (this page), so no hub tile ever 404s — Wave 4 replaces each scaffold route with
 * its real page later; nothing here is meant to survive that.
 *
 * **Parametrized entirely by route `data`, not by a per-instance host component** (`hubs.routes.ts`
 * gives each scaffold path its own `data: {title, eyebrow, description, nearestLabel?, nearestTo?}`)
 * — `withComponentInputBinding()` (already configured in `app.config.ts`) merges a route's `data`
 * into the input-binding lookup by name, the identical mechanism this app's `AssetDetailPage.assetId`
 * / `FlyPage.watch` already rely on for params/query-params (see `vision-web/MODULE.md`'s Routes
 * section) — so adding a tenth scaffold area later is a `data: {...}` object in `hubs.routes.ts`,
 * never a new component file.
 *
 * **Renders inside the standard page frame** (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N6 + §2 N6) —
 * `<vision-page-bar [title]="title()">` first, exactly like every other routed page, so this page
 * gets the same sticky header, the same `.page` padding, and the same stacking context as everywhere
 * else in the shell (the earlier single-file version skipped `vision-page-bar` entirely and had no
 * `.page` frame of its own beyond a bare `<div class="page">`, which is what let a Chrome walkthrough
 * find the card rendering with no page padding — see that finding for the reproduction). The card
 * below the bar keeps the `.card.empty` primitive verbatim (the same shape `features/wall/wall.html`'s
 * "Nothing is streaming" empty state already uses) but no longer repeats `title()` as its own `<h3>` —
 * the page bar is now the one place the title renders, so the card carries only the eyebrow, the
 * description, and **at most one** real next-step link. `nearestLabel`/`nearestTo` are optional
 * together: a couple of these areas (Missions, Firmware) genuinely have no adjacent real feature to
 * point at yet, and inventing one would be exactly the fabricated relation this component exists to
 * avoid.
 *
 * Split into `.ts`/`.html`/`.css` per this codebase's three-file rule (the previous version was one
 * file with an inline `template:`/`styles:`, the last surviving instance of that pattern in
 * `features/hubs`).
 */
@Component({
  selector: 'vision-coming-soon',
  imports: [RouterLink, PageBar],
  templateUrl: './coming-soon.html',
  styleUrl: './coming-soon.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComingSoon {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
  /** The owning mode's display name (e.g. "Operate") — rendered as a small eyebrow over the description. */
  readonly eyebrow = input.required<string>();
  /** Both set together, or neither — see this class's own doc comment. */
  readonly nearestLabel = input<string>();
  readonly nearestTo = input<string>();
}
