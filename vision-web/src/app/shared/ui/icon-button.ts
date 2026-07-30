import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icon } from './icon';
import type { IconName } from './icon-registry';

export type IconButtonVariant = 'ghost' | 'hud' | 'danger';

/**
 * `vision-icon-button` — the tool-rail/close/kebab trigger (docs/UI-REDESIGN-PLAN.md Frozen contract
 * F3), replacing this app's existing per-file `.icon-btn` copies (`fly.css`, `cv-control-panel.css`,
 * …) one call site at a time in later waves — this wave only adds the shared component, it does not
 * migrate any existing consumer.
 *
 * `label` is a **required** input, not optional-with-a-default: the rule this component exists to
 * enforce (docs/UI-REDESIGN-PLAN.md's own "Understandability & standards" section — "every icon-only
 * control is a `vision-icon-button` with a required `label`") is a compile-time error if a call site
 * omits it, not a runtime a11y gap discovered later. It drives both `title` (mouse hover) and
 * `aria-label` (screen reader) from the one string, so the visible tooltip and the accessible name
 * never drift apart.
 *
 * Destructive actions that must show text (Stop stream, Disarm) do **not** use this component — see
 * this class's own `variant` doc below.
 */
@Component({
  selector: 'vision-icon-button',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      [class]="'icon-btn ' + variant()"
      [class.active]="active()"
      [title]="label()"
      [attr.aria-label]="label()"
      [attr.aria-pressed]="active()"
      (click)="activated.emit()"
    >
      <vision-icon [name]="icon()" />
    </button>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .icon-btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.25rem;
      height: 2.25rem;
      padding: 0;
      border-radius: var(--radius-sm);
      border: 1px solid transparent;
      background: none;
      color: var(--text-muted);
      cursor: pointer;
      transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
    }

    .icon-btn:hover:not(:disabled) {
      background: var(--panel-hover);
      color: var(--text);
    }

    .icon-btn.active {
      color: var(--text);
      background: var(--panel-hover);
    }

    /* hud variant: the tool-rail-over-video look — reuses the exact --hud-* tokens .surface-hud
       itself is built from (docs/UI-REDESIGN-PLAN.md F1/F3), so a HUD icon button always matches
       whatever frosted-pill surface it sits inside. Round (--radius-pill), not the square
       --radius-sm the other two variants use — a pill button reads distinctly as "floating over
       content", not "sitting in a toolbar". */
    .icon-btn.hud {
      border-radius: var(--radius-pill);
      background: var(--hud-bg);
      border: var(--hud-border);
      backdrop-filter: blur(var(--hud-blur));
      color: var(--text);
    }

    .icon-btn.hud:hover:not(:disabled),
    .icon-btn.hud.active {
      background: var(--hud-bg-strong);
    }

    /* danger variant: icon-only destructive triggers that are *not* Stop/Disarm/Archive (those stay
       labeled .btn.danger text buttons, poka-yoke rule 4 — see this class's own doc comment) — e.g.
       a small "remove" affordance inside a list row. */
    .icon-btn.danger {
      color: var(--danger);
    }

    .icon-btn.danger:hover:not(:disabled),
    .icon-btn.danger.active {
      background: var(--danger-soft);
    }
  `,
})
export class IconButton {
  readonly icon = input.required<IconName>();
  readonly label = input.required<string>();
  readonly active = input<boolean>(false);
  readonly variant = input<IconButtonVariant>('ghost');

  readonly activated = output<void>();
}
