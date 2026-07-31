import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { WallTile } from './wall-tile';
import { EventsRail } from '../../shared/ui/events-rail';
import { Notice } from '../../shared/ui/notice';
import { EmptyState } from '../../shared/ui/empty-state';
import { WallFacade } from './wall-facade';

/**
 * The Wall (`/wall`) — a grid of every live tile at once. Dumb by convention
 * (docs/UI-ARCHITECTURE-PLAN.md): all orchestration (`FleetStore`/`SettingsStore`/`EventsStore`/
 * event-click navigation) lives in `WallFacade`, which this component injects exclusively.
 */
@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink, EventsRail, Notice, EmptyState],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [WallFacade],
})
export class WallPage {
  protected readonly facade = inject(WallFacade);
}
