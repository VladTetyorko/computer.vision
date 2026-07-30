import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthStore } from '../../../core/auth/auth-store';
import { Notice } from '../../../shared/ui/notice';

/**
 * The login screen (docs/U-AUTH-PLAN.md wave 4) — the one destination `core/auth/auth-guard.ts`
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
 * says, what "success" means — lives in `AuthStore.login()`; this component owns only the two
 * field signals and where to navigate once that call resolves `true`.
 */
@Component({
  selector: 'vision-login',
  imports: [FormsModule, Notice],
  templateUrl: './login.html',
  styleUrl: './login.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  /** Bound from `?returnUrl=` (set by `auth-guard.ts` when it redirects here) — where to land after a successful sign-in. Falls back to the operator's own default landing page. */
  readonly returnUrl = input<string>('/fly');

  protected readonly username = signal('');
  protected readonly password = signal('');

  protected readonly busy = this.auth.loginBusy;
  protected readonly error = this.auth.loginError;

  protected canSubmit(): boolean {
    return !this.busy() && this.username().trim().length > 0 && this.password().length > 0;
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return; // no empty-field request, and `busy()` itself rules out a double-submit mid-flight.
    }
    const ok = await this.auth.login(this.username().trim(), this.password());
    if (ok) {
      await this.router.navigateByUrl(this.returnUrl());
    }
  }
}
