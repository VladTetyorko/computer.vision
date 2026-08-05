import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { WallTile } from './wall-tile';
import { EventsRail } from '../../shared/ui/events-rail';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar, pluralize } from '../../shared/ui/page-bar/page-bar';
import { WallFacade } from './wall-facade';

/**
 * The Wall (`/wall`) — a grid of every live tile at once. Dumb by convention
 * (docs/UI-ARCHITECTURE-PLAN.md): all orchestration (`FleetStore`/`SettingsStore`/`EventsStore`/
 * event-click navigation) lives in `WallFacade`, which this component injects exclusively.
 *
 * **Full-bleed + page bar** (docs/NAV-IA-REDESIGN-PLAN.md §2.1/§2.2, docs/design/03-wall.md): the
 * route carries `data: { fullBleed: true }` (`wall.routes.ts`, wave 1 — auto-collapses the sidebar),
 * and the old `page-head` is now `<vision-page-bar>` (wave 2, this change) with the "Tiles per row"
 * select riding its `[pageBarFilters]` slot. `pluralize` is re-exposed here for the "N stream(s) are
 * running without a publisher URL" notice below the bar — the same fix `<vision-page-bar>`'s own
 * count chip gets for free, applied by hand since this string isn't the bar's count.
 *
 * **`.surface-dark` enclave root** (docs/VISUAL-REFRESH-PLAN.md F3/W4): `wall.html`'s outer `.page`
 * div carries `.surface-dark` — the Wall is a video surface end to end (a grid of live tiles, with
 * the page-bar/notice/events-rail all part of that same picture), not a themed page with a video
 * region inside it, so the enclave is the whole route. See `wall.html`'s own comment for why.
 */
@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink, EventsRail, Notice, EmptyState, PageBar],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [WallFacade],
})
export class WallPage {
  protected readonly facade = inject(WallFacade);
  protected readonly pluralize = pluralize;
}
