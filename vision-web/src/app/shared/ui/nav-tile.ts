import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from './icon';
import type { IconName } from './icon-registry';

/**
 * `vision-nav-tile` — a hub-launcher tile (docs/UI-REDESIGN-PLAN.md Frozen contract F3, consumed by
 * Wave 1's `features/hubs/**`'s Operate/Monitor/Manage pages — this wave adds only the primitive,
 * not the hubs themselves).
 *
 * A **real `<a routerLink>`**, never a `<div>`/`<button>` styled to look like a link: the whole tile
 * is one focusable, one-`Tab`-stop target (F3's own a11y note) — `description`/`badge` are plain
 * `<span>`s inside it, not separate interactive elements, so a keyboard/screen-reader user never
 * lands on "the tile" and then again on "its description" as if they were two different controls.
 *
 * `disabled` exists for API completeness (F3's own signature) but this app never actually disables a
 * tile: a scaffold area (docs/UI-REDESIGN-PLAN.md's "Additions" section) still routes somewhere real
 * — its own honest "coming soon" empty-state page — rather than presenting a dead, unclickable tile;
 * `badge` (e.g. `"soon"`) is how a scaffold is flagged instead.
 */
@Component({
  selector: 'vision-nav-tile',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a
      class="nav-tile"
      [routerLink]="to()"
      [class.disabled]="disabled()"
      [attr.aria-disabled]="disabled() || null"
      [style.--tile-accent]="accent() || null"
    >
      <vision-icon [name]="icon()" [size]="24" />
      <span class="tile-name">{{ name() }}</span>
      @if (description(); as description) {
        <span class="tile-desc">{{ description }}</span>
      }
      @if (badge(); as badge) {
        <span class="chip">{{ badge }}</span>
      }
    </a>
  `,
  styles: `
    /* Host is the grid item; make it fill its (equal-height) cell so the styled <a> inside can too. */
    :host {
      display: flex;
    }

    .nav-tile {
      /* Internal alias so the colour is applied once; falls back to a neutral border when no
         accent was assigned (nav-tile is a shared primitive — a consumer may omit the accent). */
      --_accent: var(--tile-accent, var(--border-strong));
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--space-8);
      padding: var(--space-16);
      background: var(--panel);
      /* Colour lives in the BORDER, not a fill: a subtly hue-tinted 1px frame with a bold pure-hue
         left bar. Card surface stays neutral so text contrast is untouched. */
      border: 1px solid color-mix(in srgb, var(--_accent) 40%, var(--border));
      border-left: 3px solid var(--_accent);
      border-radius: var(--radius);
      color: var(--text);
      min-height: 8rem;
      box-sizing: border-box;
      transition: background 0.15s ease, border-color 0.15s ease;
    }

    /* The tile's icon carries the same accent — a clear, low-ink colour cue paired with the left bar. */
    .nav-tile vision-icon {
      color: var(--_accent);
    }

    .nav-tile:hover {
      /* Faint hue wash + fuller-strength frame on hover — still a tint, never a solid fill. */
      background: color-mix(in srgb, var(--_accent) 8%, var(--panel-hover));
      border-color: color-mix(in srgb, var(--_accent) 60%, var(--border));
    }

    @media (prefers-reduced-motion: reduce) {
      .nav-tile {
        transition: none;
      }
    }

    .nav-tile.disabled {
      opacity: 0.55;
      pointer-events: none;
    }

    .tile-name {
      font-weight: 600;
      font-size: 0.95rem;
    }

    .tile-desc {
      color: var(--text-muted);
      font-size: 0.8rem;
    }

    .chip {
      margin-top: auto;
    }
  `,
})
export class NavTile {
  readonly icon = input.required<IconName>();
  readonly name = input.required<string>();
  readonly description = input<string>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- frozen F3 signature: `string | any[]` (routerLink's own input shape).
  readonly to = input.required<string | any[]>();
  /** Optional status/count chip (e.g. a scaffold area's "soon" flag). */
  readonly badge = input<string>();
  /**
   * Optional decorative accent colour (any CSS colour) — applied to the left border bar and the
   * tile icon only, never as a fill. Omit for a neutral tile. Hub pages assign this from
   * `features/hubs/tile-accent.ts`'s non-semantic cool-arc palette.
   */
  readonly accent = input<string>();
  /** Never actually used to block navigation today — see this class's own doc comment. */
  readonly disabled = input<boolean>(false);
}
