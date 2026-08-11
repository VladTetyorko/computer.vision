import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * `vision-kebab-menu` — the shared per-row overflow (⋯) menu (docs/plans/done/STYLE-TOKENS-PLAN.md §Shared
 * primitives), wrapping the native `<details>/<summary>` disclosure idiom documented in
 * src/styles.css (the `.kebab*` primitives) that was hand-assembled at ~30 call sites.
 *
 * Renders the global `.kebab` structure; callers project only the menu *entries* — plain
 * `<button type="button">` rows for routine actions, a `<div class="kebab-divider">` above a
 * destructive entry, and `<button class="danger-action">` for the destructive one (the poka-yoke
 * separation rule, see styles.css). The menu closes itself when an entry is activated (click on the
 * projected content sets the disclosure back to closed), matching the previous bespoke
 * `(click)="close()"` idiom.
 *
 * `label` is required and becomes the trigger's `aria-label` ("More actions for …") — a screen
 * reader must be told what the ⋯ button acts on.
 */
@Component({
  selector: 'vision-kebab-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <details class="kebab" #disclosure>
      <summary class="kebab-trigger" [attr.aria-label]="label()">⋯</summary>
      <div class="kebab-menu" (click)="disclosure.open = false">
        <ng-content />
      </div>
    </details>
  `,
  styles: `
    :host {
      display: inline-flex;
    }
  `,
})
export class KebabMenu {
  /** Describes what the ⋯ acts on, e.g. "More actions for camera 3". Becomes the trigger aria-label. */
  readonly label = input.required<string>();
}
