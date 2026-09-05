import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Notice } from '../../shared/ui/notice';
import { SetupFacade } from './setup-facade';

/**
 * The first-boot "create the first administrator" screen (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) —
 * `core/auth/auth-guard.ts#authGuard`/`loginGuard` send an anonymous visitor here instead of
 * `/login` whenever `GET /api/auth/bootstrap` still reports `required` (a fresh station, or a
 * clone with an empty `users` table): there is no account yet for a login form to sign in as.
 * `setupGuard` (same file) closes this page back down to a redirect home the instant the one-way
 * latch flips — this component itself never needs to re-check that, it only ever renders while
 * the guard has already confirmed the latch is still open.
 *
 * Deliberately its own route/page rather than a mode of `LoginPage` — the two forms don't share a
 * field (this one needs display name + email + a confirm field the login form has no use for),
 * and conflating "sign in" with "create the very first account" in one component would make both
 * harder to reason about for a single-use, one-time screen.
 *
 * Same responsive/visual posture as `LoginPage` (`setup.css` mirrors `login.css`'s centered-card
 * layout, 44px touch targets, dark-theme tokens) — the two are the only pages ever shown to a
 * visitor with literally no session, and should look like siblings, not like two different apps.
 *
 * Dumb by this codebase's convention: every real decision (busy state, the inline error's text,
 * what "success" means) lives in `SetupFacade`/`AuthStore.bootstrap()` — this component only wires
 * the form to the facade.
 */
@Component({
  selector: 'vision-setup',
  imports: [FormsModule, Notice],
  templateUrl: './setup.html',
  styleUrl: './setup.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SetupFacade],
})
export class SetupPage {
  protected readonly facade = inject(SetupFacade);

  protected submit(): void {
    void this.facade.submit();
  }
}
