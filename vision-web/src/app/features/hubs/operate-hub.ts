import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { NavTile } from '../../shared/ui/nav-tile';
import { TileGrid } from '../../shared/ui/tile-grid';
import { navModeById } from './nav-entries';
import { tileAccent } from './tile-accent';

/**
 * `/operate` — the Operate hub launcher (docs/UI-REDESIGN-PLAN.md Wave 1, F4/D-A: "the hub launcher
 * is just `vision-tile-grid` over `.card`-shaped tiles"). Deliberately thin: every tile comes
 * straight from `nav-entries.ts#NAV_MODES` — the same array `app.ts`'s Operate dropdown reads — so
 * this page and that dropdown can never list different things.
 */
@Component({
  selector: 'vision-operate-hub',
  imports: [Icon, NavTile, TileGrid],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1><vision-icon name="operate" [size]="24" />Operate</h1>
          <p>Fly one drone — cockpit, live vision, and flight settings.</p>
        </div>
      </div>
      <vision-tile-grid>
        @for (entry of mode.entries; track entry.name; let i = $index) {
          <vision-nav-tile
            [icon]="entry.icon"
            [name]="entry.name"
            [description]="entry.description"
            [to]="entry.to"
            [badge]="entry.badge"
            [accent]="tileAccent(i)"
          />
        }
      </vision-tile-grid>
    </div>
  `,
  styles: `
    h1 {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
  `,
})
export class OperateHub {
  protected readonly mode = navModeById('operate');
  protected readonly tileAccent = tileAccent;
}
