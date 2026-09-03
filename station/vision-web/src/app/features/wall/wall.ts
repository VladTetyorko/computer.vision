import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { WallTile } from './wall-tile';
import { WallFocus } from './wall-focus';
import { WallActivity } from './wall-activity';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { declutterLevelLabel } from '../../shared/player/detection-overlay-logic';
import { DENSITY_STOPS } from './wall-logic';
import { WallFacade } from './wall-facade';

/**
 * The Wall (`/wall`) — a grid of every live tile at once (docs/plans/active/WALL-FLOW-PLAN.md wave
 * W3). Dumb by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md): this component injects only
 * `WallFacade` (`architecture.spec.ts` guards it — `wall/wall` is a routed page) plus `Router`, the
 * one framework utility every routed page is still free to use directly — `vision-wall-focus`'s
 * `(openCockpit)`/`(watchLive)` outputs hand back an id, and navigating away is exactly what the
 * *focus view's own labelled exits* are for (§3 law 2: nothing else on the wall navigates).
 *
 * **`.surface-dark` enclave root** (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/W4, unchanged from
 * before this wave): the Wall is a video surface end to end, so the enclave is the whole route.
 *
 * **The events rail is gone from this file already**, not merely in wave W4 as
 * `WALL-FLOW-PLAN.md`'s own W4 text schedules it: wave W1 exposed the frozen `WallFacade` surface
 * verbatim (§3.4), which has no `openEvent` method at all (its `Router`/`resolveReplayDeepLink`
 * plumbing was the fix for D17, not a later cleanup) — so a rail still wired to
 * `(open)="facade.openEvent($event)"` could never have compiled once W1 landed, regardless of which
 * wave's turn it was. Carrying it one more wave in a silently-degraded shape (no click handler)
 * would have shipped a dead control on a page whose whole thesis is "no dead-looking toggle, no
 * fabricated placeholder." The real replacement — `vision-wall-activity`, scoped to this wall's own
 * streams — is still wave W4's own new file; only the *deletion* of the old rail moved one wave
 * earlier than the plan's prose literally says, and the plan's own §3.1 verdict table already calls
 * for its removal ("REMOVE from the resting layout").
 */
@Component({
  selector: 'vision-wall',
  imports: [WallTile, WallFocus, WallActivity, EmptyState, PageBar, RouterLink],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [WallFacade],
})
export class WallPage {
  protected readonly facade = inject(WallFacade);
  private readonly router = inject(Router);

  protected readonly densityStops = DENSITY_STOPS;
  protected readonly declutterLevelLabel = declutterLevelLabel;

  protected onOpenCockpit(assetId: string): void {
    void this.router.navigate(['/fly', assetId]);
  }

  protected onWatchLive(deviceId: string): void {
    void this.router.navigate(['/live', deviceId]);
  }
}
