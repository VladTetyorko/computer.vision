import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * `vision-section-header` — the shared card/section head (docs/UI-REDESIGN-PLAN.md Frozen contract
 * F3), replacing the 9 hand-written `<header><h2>` blocks across `asset-detail.html` and
 * `live.html`'s `<section class="card"><header><h2>` idiom — this wave only adds the component; no
 * existing page migrates onto it yet (out of scope: `src/app/features/**`).
 *
 * `title`/`eyebrow`/`subtitle` mirror this app's existing two-register typography
 * (`src/styles.css`'s own comment on display vs. structural/label text): `eyebrow` renders with the
 * global `.label` look (uppercase, loosened tracking — "naming a slot/category"), `title` is a plain
 * `<h2>` (mixed-case, the existing `h1/h2/h3` display rule), `subtitle` is a muted line reusing the
 * global `.muted` color. The projected `[actions]` slot is the one place a section's own
 * buttons/kebab/`.segmented` picker lives — always right-aligned, never competing with the title for
 * the reader's first look.
 *
 * `<h2>` is the section's real accessible label; the host element itself is a plain `<header>`, not
 * a landmark — the enclosing card/section (this component's own host page) is what should carry any
 * `role`/`aria-labelledby` wiring, if it needs one.
 */
@Component({
  selector: 'vision-section-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="section-head">
      <div>
        @if (eyebrow(); as eyebrow) {
          <span class="label">{{ eyebrow }}</span>
        }
        <h2>{{ title() }}</h2>
        @if (subtitle(); as subtitle) {
          <p class="muted">{{ subtitle }}</p>
        }
      </div>
      <div class="section-head-actions">
        <ng-content select="[actions]" />
      </div>
    </header>
  `,
  styles: `
    :host {
      display: block;
    }

    .section-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-16);
      margin-bottom: var(--space-16);
    }

    .section-head .label {
      margin-bottom: var(--space-4);
    }

    .section-head h2 {
      margin: 0;
    }

    .section-head p {
      margin: var(--space-4) 0 0;
      font-size: 0.85rem;
      max-width: 62ch;
    }

    .section-head-actions {
      display: flex;
      align-items: center;
      gap: var(--space-8);
      flex-wrap: wrap;
      flex: none;
    }

    .section-head-actions:empty {
      display: none;
    }
  `,
})
export class SectionHeader {
  readonly title = input.required<string>();
  readonly eyebrow = input<string>();
  readonly subtitle = input<string>();
}
