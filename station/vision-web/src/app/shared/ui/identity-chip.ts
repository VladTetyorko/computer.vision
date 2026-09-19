import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthFacade } from '../../core/auth/auth-facade';
import { initialsFor, topRoleLabel } from '../../core/auth/auth-logic';
import { canManageOrg } from '../../core/org/org-logic';
import { OverlayFacade } from '../../core/ui/overlay-facade';

/**
 * The header identity chip (docs/plans/done/U-AUTH-PLAN.md wave 4) — displayName + a role badge + a logout
 * affordance, mounted once in `app.html` next to the notification bell/status chips. The first
 * piece of this app's broader responsive pass (the plan's own framing: "build it responsive from
 * the start so it sets the pattern") — see `identity-chip.css` for the collapse rule.
 *
 * **Renders nothing while `user()` is `null`** (`AuthFacade`'s `'loading'` status, or a genuinely
 * anonymous session about to be redirected by the guard) — no placeholder/skeleton swapped in
 * afterward, the same "hide entirely rather than show a stale/fake state" rule
 * `shared/ui/weather-chip.ts` already follows for its own always-null-until-ready reading. Injects
 * `AuthFacade` directly (root-provided, one instance app-wide) rather than taking inputs — there is
 * exactly one session in this app, nothing for a host page to parameterize.
 *
 * **Account settings** (docs/plans/done/UI-REDESIGN-PLAN.md Wave 1, F4's "(shell) → `/settings` via profile
 * menu") sits in the menu right alongside **My activity** — no role gate, same as My activity,
 * since every signed-in user (pilot included) owns their own detection/notification defaults. This
 * is the same `/settings` route the Operate hub's own "Flight & detection settings" tile links to
 * (`features/hubs/nav-entries.ts`) — one destination, reachable from two places, not a duplicate
 * page.
 *
 * **Shown even with auth disabled** (dev parity, docs/plans/done/U-AUTH-PLAN.md's own explicit call: "show the
 * dev admin's name too, so the surface is consistent") — the one thing suppressed in that mode is
 * the **Log out** action itself (`@if (auth.authEnabled())`), since logging out of a session that
 * was never real has nothing to do.
 *
 * **Responsive collapse — a single template, not two.** At normal widths the trigger shows the
 * avatar, name, and role badge inline (glanceable, no click needed); `identity-chip.css`'s one
 * `@media (max-width: 640px)` rule (the same breakpoint `app.css` already uses for the header's own
 * responsive pass) hides just the name/role text in the trigger, leaving the avatar alone as a
 * compact tap target. The dropdown menu itself is unaffected by that breakpoint — it always carries
 * name, role, and (when relevant) Log out, so opening it on a narrow viewport surfaces exactly the
 * information the trigger hid, never a reduced feature set.
 *
 * **Signal-backed open state, not `<details>`** (docs/plans/done/UI-STATE-PLAN.md §1 D4/D5, §2.3, §2.2): this
 * used to be a native `<details>`, whose `open` state lived in the DOM where nothing could see or
 * reset it — and since this component is mounted once in the always-on shell (`app-sidebar.html`'s
 * foot) and never destroyed on navigation, "the page component is destroyed on route change" (this
 * app's only other cleanup mechanism) never applied to it either. Reproduced live: open this menu,
 * then the notification bell — both stayed open at once (D1); navigate to another page — both stayed
 * open there too (D2). The trigger now toggles `OverlayFacade` (`'identity-menu'`, an NgRx slice,
 * docs/plans/done/NGRX-MIGRATION-PLAN.md §8 — the old `GlobalOverlayStore` composed
 * `core/ui/ui-store.ts#UiStore` for this; the reducer now expresses one-open-at-a-time directly) for
 * exclusivity with the bell and adds the three lifecycle rules the shell needs and no page does:
 * closes on any navigation, on `Escape` (returning focus to the trigger), and on a click outside —
 * see `core/ui/state/overlay.effects.ts`'s own doc comment for the full mechanism. The
 * trigger registers itself (`OverlayFacade.register`) via an `effect()` over its own `viewChild`,
 * not `afterNextRender()`, because the trigger doesn't exist on the very first render — it sits behind
 * `@if (auth.user())`, and `user()` can still be `'loading'` at that point.
 */
@Component({
  selector: 'vision-identity-chip',
  imports: [RouterLink],
  templateUrl: './identity-chip.html',
  styleUrl: './identity-chip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IdentityChip {
  protected readonly auth = inject(AuthFacade);
  protected readonly overlays = inject(OverlayFacade);
  private readonly host = inject(ElementRef<HTMLElement>);
  /** Optional, not `.required()` — see the class doc's "signal-backed open state" paragraph for why
   *  the trigger genuinely may not exist yet the first time this runs. */
  private readonly triggerEl = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly initials = initialsFor;
  protected readonly roleLabel = topRoleLabel;

  /**
   * Whether to show the **Organization** link (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — ADMIN/MANAGER
   * only, the same gate `core/org/org-guard.ts` enforces on the route itself, so a pilot never sees
   * the door, not just a bounced click. **My activity** below it has no such gate (every user reads
   * their own).
   */
  protected readonly canManageOrg = computed(() => canManageOrg(this.auth.capabilities()));

  constructor() {
    // Registers this component's own host (trigger + dropdown together) with the shell's overlay
    // registry the moment the trigger exists — see `OverlayHostRegistry.register`'s own doc comment
    // for why `root` containing `trigger` is what lets a click on the trigger itself never fight the
    // outside-click listener.
    effect(() => {
      const trigger = this.triggerEl();
      if (trigger) {
        this.overlays.register('identity-menu', this.host.nativeElement, trigger.nativeElement);
      }
    });
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
  }
}
