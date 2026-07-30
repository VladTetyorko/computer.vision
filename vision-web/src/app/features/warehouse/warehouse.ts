import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { NavTile } from '../../shared/ui/nav-tile';
import { TileGrid } from '../../shared/ui/tile-grid';
import { tileAccent } from '../hubs/tile-accent';
import type { IconName } from '../../shared/ui/icon-registry';

/** One tile on the Warehouse launcher — same shape as `features/hubs/nav-entries.ts#NavEntry`, kept
 *  local since this page is not one of the three frozen `NAV_MODES` hubs (it's a leaf page reached
 *  *from* the Manage hub's own "Warehouse" tile, not a mode of its own). */
interface WarehouseTile {
  readonly icon: IconName;
  readonly name: string;
  readonly description: string;
  readonly to: string;
}

/**
 * `/warehouse` — the inventory landing page: a two-way launcher between the people who fly the
 * fleet and the assets they fly. Used to be a plain alias onto the combined Devices/Warehouse page
 * (`features/devices/**` before the Assets/Devices split); now that Assets and Devices are separate
 * pages, "Warehouse" earns its own identity as the door between them, mirroring
 * `features/hubs/operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts`'s own "thin page over a small tile
 * list" shape without actually being a fourth `NAV_MODES` hub.
 *
 * **People → `/manage/roster`** (docs/UI-REDESIGN-PLAN.md Wave 1's own `ComingSoon` scaffold, same
 * target as the Manage hub's "Pilots / roster" tile — deliberately shared, mirroring `Map`/`Command
 * dashboard` both resolving to `/command` in `features/hubs/nav-entries.ts`): there is no dedicated
 * roster page yet, so this is the honest "nearest real destination", never a dead link — per-asset
 * pilot assignment already works from each asset's own Pilots card (`features/asset-detail/pilots-card.ts`),
 * linked from `/assets` below.
 *
 * **Assets → `/assets`**: the asset-first grid (search/filter/cards) split out of the old combined
 * page — see `features/assets/assets.ts`'s own class doc comment.
 */
@Component({
  selector: 'vision-warehouse',
  imports: [Icon, NavTile, TileGrid],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1><vision-icon name="warehouse" [size]="24" />Warehouse</h1>
          <p>The inventory landing spot — jump to the people who fly the fleet, or the assets they fly.</p>
        </div>
      </div>
      <vision-tile-grid>
        @for (tile of tiles; track tile.name; let i = $index) {
          <vision-nav-tile
            [icon]="tile.icon"
            [name]="tile.name"
            [description]="tile.description"
            [to]="tile.to"
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
export class WarehousePage {
  protected readonly tileAccent = tileAccent;

  protected readonly tiles: readonly WarehouseTile[] = [
    {
      icon: 'pilot',
      name: 'People',
      description: "The roster of pilots who fly the fleet, and each asset's own assignments.",
      to: '/manage/roster',
    },
    {
      icon: 'drone',
      name: 'Assets',
      description: 'Every asset, asset-first — search, filter, and watch, open, or archive.',
      to: '/assets',
    },
  ];
}
