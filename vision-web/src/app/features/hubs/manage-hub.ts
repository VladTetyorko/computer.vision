import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { NavTile } from '../../shared/ui/nav-tile';
import { TileGrid } from '../../shared/ui/tile-grid';
import { navModeById } from './nav-entries';
import { tileAccent } from './tile-accent';

/**
 * `/manage` — the Manage hub launcher. See `operate-hub.ts`'s class doc comment for the shared
 * "thin page over `NAV_MODES`" shape every hub in this folder follows.
 */
@Component({
  selector: 'vision-manage-hub',
  imports: [Icon, NavTile, TileGrid],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1><vision-icon name="manage" [size]="24" />Manage</h1>
          <p>Manage inventory — assets, sources, and the people who fly them.</p>
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
export class ManageHub {
  protected readonly mode = navModeById('manage');
  protected readonly tileAccent = tileAccent;
}
