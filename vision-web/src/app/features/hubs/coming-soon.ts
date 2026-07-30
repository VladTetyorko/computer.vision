import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * `ComingSoon` — the one shared placeholder every F4 "scaffold" route lands on
 * (docs/UI-REDESIGN-PLAN.md Wave 1; §D-G: "a scaffold never renders invented rows — it states
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
 * Reuses the existing `.empty` primitive verbatim — `<h3>` + one short `<p>` + **at most one** real
 * next-step link (the same shape `features/wall/wall.html`'s "Nothing is streaming" empty state
 * already uses) — `nearestLabel`/`nearestTo` are optional together: a couple of these areas
 * (Missions, Firmware) genuinely have no adjacent real feature to point at yet, and inventing one
 * would be exactly the fabricated relation this component exists to avoid.
 */
@Component({
  selector: 'vision-coming-soon',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="card empty">
        <span class="label eyebrow">{{ eyebrow() }} · Coming soon</span>
        <h3>{{ title() }}</h3>
        <p>{{ description() }}</p>
        @if (nearestTo(); as to) {
          <a class="btn" [routerLink]="to">{{ nearestLabel() }}</a>
        }
      </div>
    </div>
  `,
  styles: `
    .eyebrow {
      display: block;
      margin-bottom: var(--space-2);
    }
  `,
})
export class ComingSoon {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
  /** The owning mode's display name (e.g. "Operate") — rendered as a small eyebrow over the title. */
  readonly eyebrow = input.required<string>();
  /** Both set together, or neither — see this class's own doc comment. */
  readonly nearestLabel = input<string>();
  readonly nearestTo = input<string>();
}
