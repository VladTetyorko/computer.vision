import type { Routes } from '@angular/router';

/**
 * `/map` — folded into Command (docs/UX-REWORK-PLAN.md §U-c: "`/map` … folds into Command
 * (redirect)"). `MapPage`/`map.html`/`map.css` are deleted, not just unrouted — every job that page
 * did (the fleet map, the "every asset" rail, the docked live preview, the empty-state "Add a test
 * drone") now lives in `features/command/**`, per the plan's own "components fold in" instruction.
 *
 * A plain path redirect (not a component route) preserves any query string a caller appended —
 * `router.navigate`/an `<a href>` to `/map?asset=<id>` still lands on `/command?asset=<id>`, which
 * `CommandPage.requestedAssetId` (aliased `{alias: 'asset'}`, mirrors `features/fly/fly.ts`'s own
 * `?asset=` input) resolves into a selection on arrival — see that page's own class doc comment.
 * `/map` itself never actually supported `?asset=` before this redirect (grep-verified before
 * writing this), so there is nothing concrete this preserves *today*, but old bookmarks/links to a
 * bare `/map` keep working, which is this redirect's actual job.
 */
export const MAP_ROUTES: Routes = [{ path: 'map', redirectTo: 'command', pathMatch: 'full' }];
