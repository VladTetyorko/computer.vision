import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from './icon';
import type { IconName } from './icon-registry';

/**
 * `vision-notice` — the shared attention-banner (docs/STYLE-TOKENS-PLAN.md §Shared primitives),
 * replacing the ~13 hand-written amber/red "light text on a matching dark line" banners that were
 * copy-pasted across `app.css`, `wall.css`, `command.css`, `fly.css`, `live.css`, `onboarding.css`,
 * `toast-host.ts`, `player.ts` and others.
 *
 * Renders the global `.notice` primitive (src/styles.css) — the component owns only the structure
 * (optional leading icon + projected message), the colour comes entirely from the `variant` class,
 * so a consumer never re-specifies a hue. `variant`:
 *   - `neutral` (default) — informational, panel-surface tint
 *   - `warn`    — amber (an advisory / degraded state)
 *   - `danger`  — red (a failure / blocking condition) — announced assertively
 *   - `ok`      — green (a healthy / recovered state)
 *
 * Message is projected (`<ng-content>`) so callers keep full markup control (links, `<strong>`,
 * inline `.mono` values). `role` follows severity: `alert` for danger (interrupts the screen
 * reader), `status` otherwise (announced politely).
 */
@Component({
  selector: 'vision-notice',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="notice"
      [class.warn]="variant() === 'warn'"
      [class.danger]="variant() === 'danger'"
      [class.ok]="variant() === 'ok'"
      [attr.role]="variant() === 'danger' ? 'alert' : 'status'"
    >
      @if (icon(); as icon) {
        <vision-icon [name]="icon" [size]="16" />
      }
      <div class="notice-body"><ng-content /></div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    /* The projected message takes the remaining width so a leading icon never squashes it. */
    .notice-body {
      flex: 1;
      min-width: 0;
    }

    vision-icon {
      flex: none;
    }
  `,
})
export class Notice {
  readonly variant = input<'neutral' | 'warn' | 'danger' | 'ok'>('neutral');
  /** Optional leading status icon (e.g. `warning`, `error`). Omit for a text-only banner. */
  readonly icon = input<IconName>();
}
