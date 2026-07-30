import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * `vision-tile-grid` — pure layout, no inputs/outputs (docs/UI-REDESIGN-PLAN.md Frozen contract F3):
 * content-projects `vision-nav-tile`s (or anything else) into a responsive grid. Consumed by Wave 1's
 * hub launcher pages (`features/hubs/**`, not built by this wave).
 *
 * `repeat(auto-fill, minmax(13rem, 1fr))` is already responsive on its own (a narrower container
 * naturally fits fewer columns) — the two breakpoints below are a **deliberate** density drop on top
 * of that, matching `--bp-lg`'s own doc comment in `src/styles.css` ("below: hub tile grid drops a
 * column"): below `--bp-lg` the minimum tile width grows, below `--bp-sm` it collapses to one column
 * outright (mirroring every other `--bp-sm` collapse in this app).
 */
@Component({
  selector: 'vision-tile-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tile-grid">
      <ng-content />
    </div>
  `,
  styles: `
    .tile-grid {
      display: grid;
      /* Fixed 3-up rhythm on desktop (intentional, scannable rows) → 2-up → 1-up. */
      grid-template-columns: repeat(3, minmax(0, 1fr));
      /* Equal-height tiles: every row track is sized to the tallest tile in the grid, so cards
         share one bottom baseline (and their "soon" badges line up) instead of a ragged edge. */
      grid-auto-rows: 1fr;
      align-items: stretch;
      gap: var(--space-16);
    }

    @media (max-width: 900px) {
      /* --bp-md */
      .tile-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 640px) {
      /* --bp-sm */
      .tile-grid {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class TileGrid {}
