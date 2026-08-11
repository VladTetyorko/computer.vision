import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, type SafeHtml } from '@angular/platform-browser';
import { ICONS, type IconName } from './icon-registry';

/**
 * `<vision-icon>` — the inline-SVG icon primitive (docs/plans/done/UI-REDESIGN-PLAN.md Frozen contract F2).
 * Purely decorative: `host: aria-hidden="true"` on every instance, always — the labeled control
 * around it (a `vision-icon-button`'s required `label`, a `vision-nav-tile`'s own visible name text,
 * a page heading) owns the accessible name, per F2's own render note. Never use this component where
 * no such labeled context exists.
 *
 * `[innerHTML]` bypasses Angular's sanitizer deliberately: every value it can ever render comes from
 * `ICONS`, a closed, compile-time `Record<IconName, string>` this module owns — never user input,
 * never a network response — so there is nothing here for the sanitizer to protect against, and
 * bypassing it sidesteps Angular's default SVG-attribute allowlist silently dropping legitimate
 * attributes (`stroke-linecap`, `transform`) that a couple of these hand-authored icons use.
 */
@Component({
  selector: 'vision-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      [innerHTML]="markup()"
    ></svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
    }

    svg {
      display: block;
    }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  /** Px — sets both `width`/`height` on the rendered `<svg>`; the `viewBox` stays `0 0 24 24`. */
  readonly size = input<number>(16);

  private readonly sanitizer = inject(DomSanitizer);

  protected readonly markup = computed<SafeHtml>(() => this.sanitizer.bypassSecurityTrustHtml(ICONS[this.name()]));
}
