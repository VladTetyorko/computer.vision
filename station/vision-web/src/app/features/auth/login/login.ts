import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Notice } from '../../../shared/ui/notice';
import { LoginFacade } from './login-facade';

/**
 * The login screen (docs/plans/done/U-AUTH-PLAN.md wave 4) — the one destination `core/auth/auth-guard.ts`
 * sends an anonymous user to whenever auth is enabled. Never reachable at all in dev-parity mode
 * (`authEnabled === false`); see `core/auth/auth-logic.ts#needsLogin`.
 *
 * **Responsive from the start** (the plan's own explicit ask, and the first piece of this app's
 * broader responsive pass): a single centered card at every width (`login.css`), inputs and the
 * submit button both `min-height: 44px` (a comfortable touch target, not just a mouse-sized one),
 * no layout branch between phone and desktop — only the card's own `min()`-clamped width changes.
 * Reuses this app's existing dark-theme tokens (`.card`, `input`, `.btn` from `styles.css`) rather
 * than inventing a parallel visual language for the one unauthenticated page.
 *
 * Dumb by this codebase's own convention: every real decision — busy state, what the inline error
 * says, what "success" means — lives in `AuthFacade.login()`, orchestrated by `LoginFacade`
 * (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — this component owns only the route-bound `returnUrl` input and
 * renders the facade's signals/commands.
 */
@Component({
  selector: 'vision-login',
  imports: [FormsModule, Notice],
  templateUrl: './login.html',
  styleUrl: './login.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [LoginFacade],
})
export class LoginPage {
  /** Bound from `?returnUrl=` (set by `auth-guard.ts` when it redirects here) — where to land after a successful sign-in. Falls back to the operator's own default landing page. */
  readonly returnUrl = input<string>('/fly');

  protected readonly facade = inject(LoginFacade);

  protected submit(): void {
    void this.facade.submit(this.returnUrl());
  }
}
