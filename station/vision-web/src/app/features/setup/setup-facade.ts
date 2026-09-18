import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthFacade } from '../../core/auth/auth-facade';

/**
 * `SetupPage`'s facade (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — mirrors `LoginFacade`'s own
 * thinness: owns the form's field signals and forwards straight to `AuthFacade.bootstrap()`, which
 * carries every real decision (busy state, what "success" means, what the inline error says).
 *
 * Unlike `LoginFacade`, `busy`/`error` are **not** re-exposed `AuthFacade` signals — `bootstrap()`
 * is a one-shot call with no store-level in-flight/error state of its own (there is nothing else
 * in the app that would ever need to observe "is a bootstrap attempt in flight" except this one
 * page), so this facade owns that pair locally instead of growing `AuthFacade` two more signals for
 * a single caller.
 */
@Injectable()
export class SetupFacade {
  private readonly auth = inject(AuthFacade);
  private readonly router = inject(Router);

  readonly username = signal('');
  readonly displayName = signal('');
  readonly email = signal('');
  readonly password = signal('');
  readonly confirmPassword = signal('');

  private readonly busySignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);
  readonly busy = this.busySignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  /**
   * Client-side confirm-match only — the real password policy (minimum length, etc.,
   * `PasswordPolicy` server-side) is deliberately never duplicated here as a frontend constant
   * (CLAUDE.md's "no magic numbers/hardcoded config" rule): a weak password simply comes back as
   * `error()` from `bootstrap()`, with the server's own policy sentence, same as everywhere else
   * this app surfaces a `WEAK_PASSWORD` response.
   */
  readonly passwordsMatch = computed(() => this.password() === this.confirmPassword());

  canSubmit(): boolean {
    return (
      !this.busy() &&
      this.username().trim().length > 0 &&
      this.displayName().trim().length > 0 &&
      this.email().trim().length > 0 &&
      this.password().length > 0 &&
      this.passwordsMatch()
    );
  }

  async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return; // no empty/mismatched-field request, and `busy()` itself rules out a double-submit mid-flight.
    }
    this.errorSignal.set(null);
    this.busySignal.set(true);
    try {
      const error = await this.auth.bootstrap({
        username: this.username().trim(),
        displayName: this.displayName().trim(),
        email: this.email().trim(),
        password: this.password(),
      });
      if (error) {
        this.errorSignal.set(error);
        return;
      }
      // The freshly-created admin always lands on `/` (landingGuard resolves it from there) —
      // there is no `returnUrl` equivalent for a fresh station's very first visitor.
      await this.router.navigateByUrl('/');
    } finally {
      this.busySignal.set(false);
    }
  }
}
