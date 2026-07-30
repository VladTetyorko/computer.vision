import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from './icon';
import type { IconName } from './icon-registry';

/**
 * `vision-empty` — the shared empty/zero-state block (docs/STYLE-TOKENS-PLAN.md §Shared primitives),
 * replacing the ~15 hand-written `<div class="empty"><h3>…</h3><p>…</p></div>` copies across the
 * feature pages.
 *
 * Renders the global `.empty` primitive (src/styles.css) — centred, muted, with a full-contrast
 * heading. `title` is required; `message` and a leading `icon` are optional. Any call-to-action
 * (a "Add device" button, a "Retry" link) is projected via `<ng-content>` so it keeps its own
 * `.btn`/routing markup and sits below the message exactly as the bespoke blocks did.
 */
@Component({
  selector: 'vision-empty',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      @if (icon(); as icon) {
        <vision-icon class="empty-icon" [name]="icon" [size]="32" />
      }
      <h3>{{ title() }}</h3>
      @if (message(); as message) {
        <p>{{ message }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .empty-icon {
      display: block;
      margin: 0 auto var(--space-8);
      color: var(--text-faint);
    }
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input<string>();
  /** Optional decorative icon above the title. Omit for a text-only empty state. */
  readonly icon = input<IconName>();
}
