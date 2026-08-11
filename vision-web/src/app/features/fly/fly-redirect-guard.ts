import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { SettingsStore } from '../../core/settings/settings-store';
import { rememberedStreamingAssetId } from './fly-logic';

/**
 * Gates the bare `/fly` route (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12, docs/extracts/design/01-fly.md —
 * "skip the picker when it has nothing to ask") — decides, **before `DronePickerPage` ever mounts**,
 * whether this visit should redirect straight into a cockpit instead of showing the picker at all.
 * Two independent reasons to redirect, checked in this order:
 *
 * 1. **`?asset=<id>`** (Command's/Alerts' own drill-down links — `command-facade.ts#watchLive`,
 *    `alerts-facade.ts`) is a stronger signal than "remembered": the caller is asking for that exact
 *    drone, streaming or not (a manager drilling into an idle drone from Command is still a real,
 *    intentional ask), so this redirects unconditionally, no fleet fetch needed at all.
 *    `rememberedStreamingAssetId` is deliberately never consulted for this branch.
 * 2. **The remembered drone (`SettingsStore.flyAssetId`) is still actually streaming** — see
 *    `fly-logic.ts#rememberedStreamingAssetId`'s own doc comment for exactly what "still" means.
 *
 * Neither branch fetches `getAsset`/validates the id beyond `rememberedStreamingAssetId`'s own
 * fleet-presence check — an `/fly/:assetId` that turns out to be stale (deleted between this guard's
 * `listAssets()` and the cockpit's own `getAsset()`) is `CockpitPage`'s own honest-empty-state job
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F12's own "must degrade to an honest empty state" requirement), not
 * this guard's — duplicating that check here would only double the round trip for the same answer.
 *
 * **Redirects by navigating and returning `false`, never by returning a `UrlTree`** (unlike
 * `auth-guard.ts`/`org-guard.ts`, both wiring-only redirects that happen to need no particular
 * history behavior). This route's own redirect needs an unambiguous **replacing** navigation
 * (docs/main/UX-DESIGN.md §2 T1's "Back must not loop" concern, restated for F12): landing here already
 * consumed one history entry — the link that brought the operator to `/fly?asset=…`, or whatever
 * preceded a bare `/fly` visit with a live remembered drone. Pushing `/fly/:assetId` *on top* of
 * that (however a guard-returned `UrlTree`'s own history behavior resolves — not pinned down here)
 * would leave `/fly` itself behind in history, and Back from the cockpit would land right back on
 * this same redirect — reproducing the exact "Back does not exit it" complaint F12 opened with, one
 * route later. `router.navigate(..., { replaceUrl: true })` makes that outcome impossible to get
 * wrong: `/fly?asset=…` (or a bare `/fly`) never actually lands in history at all, only
 * `/fly/:assetId` does. A genuine picker visit (no `?asset=`, no live remembered drone) is
 * unaffected either way — `true` renders it normally, and a real click into a picker card is a
 * normal push navigation Back already unwinds correctly (verified live — see this cycle's own
 * MODULE.md status entry).
 *
 * Never redirects on a `popstate` navigation (Back/Forward) — verified live, this is not
 * theoretical. A bare `/fly` only ever becomes a real, Back-reachable history entry at the moment
 * this guard itself renders the picker (every redirect above is `replaceUrl: true`, so a redirect
 * never leaves one behind) — meaning by the time the operator presses Back to return to it,
 * "should this show the picker" was already decided once, when that entry was first pushed.
 * Re-deciding from scratch on the way back in is actively wrong, not merely redundant: viewing a
 * cockpit is exactly what sets `flyAssetId`, so the overwhelmingly common case is "the drone I
 * just came from is still streaming" — re-running this guard on Back would redirect straight back
 * into the very cockpit Back is trying to leave, silently swallowing the Back press
 * (docs/extracts/design/01-fly.md's own "the cockpit needs a way back to it" promise, broken one navigation
 * later). `Router.getCurrentNavigation()?.trigger` is `'popstate'` for both Back and Forward and
 * only those (never a `routerLink` click, a `router.navigate` call, or a typed URL - all
 * `'imperative'`) - checked first, before either branch, so Back/Forward always renders whatever
 * this route actually resolves to with no second-guessing.
 */
export const flyRedirectGuard: CanActivateFn = async (route) => {
  const router = inject(Router);
  if (router.getCurrentNavigation()?.trigger === 'popstate') {
    return true;
  }

  const requestedAssetId = route.queryParamMap.get('asset');
  const watch = route.queryParamMap.get('watch');

  if (requestedAssetId) {
    await router.navigate(['/fly', requestedAssetId], {
      queryParams: watch ? { watch } : undefined,
      replaceUrl: true,
    });
    return false;
  }

  const api = inject(VisionApi);
  const settings = inject(SettingsStore);
  try {
    const assets = await api.listAssets();
    const remembered = rememberedStreamingAssetId(assets, settings.flyAssetId());
    if (remembered) {
      await router.navigate(['/fly', remembered], { replaceUrl: true });
      return false;
    }
  } catch {
    // Silent-degrade (this app's own background-check convention): `DronePickerFacade`'s own
    // `listAssets()` call renders the honest error state a moment later — this guard's own
    // best-effort check failing just means "render the picker", never a dead end.
  }
  return true;
};
