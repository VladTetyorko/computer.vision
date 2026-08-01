import type { Routes } from '@angular/router';

/**
 * `/warehouse` — **deleted** (docs/UX-SIMPLIFY-REVIEW.md F2). `WarehousePage` used to be a two-tile
 * launcher whose only job was linking to People (`/manage/roster`) and Assets (`/assets`) — both
 * already reachable directly from the Manage hub, so it was a third door to a concept ("where are my
 * cameras / who flies them") that already had two doors. Assets is now the one home for "what I
 * own/fly" (`features/hubs/nav-entries.ts`'s own class doc comment has the full writeup); nothing
 * here replaces Warehouse's old content, because nothing needs to.
 *
 * `WarehousePage`/`warehouse.ts`/`warehouse.spec.ts` are gone outright, not just unrouted. This file
 * now holds only a plain path redirect — mirrors `features/map/map.routes.ts`'s own "`/map` folds
 * into Command" precedent exactly, right down to the reasoning: any existing bookmark/deep link to
 * `/warehouse` still lands somewhere real (`/assets`) instead of the `**` not-found catch-all, and any
 * query string a caller appended survives the hop (a path redirect, not a component route).
 * `app.routes.ts`'s own `import`/spread of `WAREHOUSE_ROUTES` needed no change — the route path and
 * export name are unchanged, only what the path resolves to.
 */
export const WAREHOUSE_ROUTES: Routes = [{ path: 'warehouse', redirectTo: 'assets', pathMatch: 'full' }];
