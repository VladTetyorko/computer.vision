import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthStore } from '../../core/auth/auth-store';
import { initialsFor, topRoleLabel } from '../../core/auth/auth-logic';

/**
 * The header identity chip (docs/U-AUTH-PLAN.md wave 4) — displayName + a role badge + a logout
 * affordance, mounted once in `app.html` next to the notification bell/status chips. The first
 * piece of this app's broader responsive pass (the plan's own framing: "build it responsive from
 * the start so it sets the pattern") — see `identity-chip.css` for the collapse rule.
 *
 * **Renders nothing while `user()` is `null`** (`AuthStore`'s `'loading'` status, or a genuinely
 * anonymous session about to be redirected by the guard) — no placeholder/skeleton swapped in
 * afterward, the same "hide entirely rather than show a stale/fake state" rule
 * `shared/ui/weather-chip.ts` already follows for its own always-null-until-ready reading. Injects
 * `AuthStore` directly (root-provided, one instance app-wide) rather than taking inputs — there is
 * exactly one session in this app, nothing for a host page to parameterize.
 *
 * **Shown even with auth disabled** (dev parity, docs/U-AUTH-PLAN.md's own explicit call: "show the
 * dev admin's name too, so the surface is consistent") — the one thing suppressed in that mode is
 * the **Log out** action itself (`@if (auth.authEnabled())`), since logging out of a session that
 * was never real has nothing to do.
 *
 * **Responsive collapse — a single `<details>`, not two templates.** At normal widths the trigger
 * shows the avatar, name, and role badge inline (glanceable, no click needed); `identity-chip.css`'s
 * one `@media (max-width: 640px)` rule (the same breakpoint `app.css` already uses for the header's
 * own responsive pass) hides just the name/role text in the trigger, leaving the avatar alone as a
 * compact tap target. The dropdown menu itself is unaffected by that breakpoint — it always carries
 * name, role, and (when relevant) Log out, so opening it on a narrow viewport surfaces exactly the
 * information the trigger hid, never a reduced feature set. Mirrors `app.css`'s `.tab-more`/
 * `shared/ui/notification-bell.ts`'s `.bell` — this app's one native-`<details>` popover idiom, not
 * a new dropdown component.
 */
@Component({
  selector: 'vision-identity-chip',
  templateUrl: './identity-chip.html',
  styleUrl: './identity-chip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IdentityChip {
  protected readonly auth = inject(AuthStore);

  protected readonly initials = initialsFor;
  protected readonly roleLabel = topRoleLabel;

  protected async logout(): Promise<void> {
    await this.auth.logout();
  }
}
