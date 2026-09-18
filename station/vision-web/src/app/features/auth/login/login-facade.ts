import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthFacade } from '../../../core/auth/auth-facade';

/**
 * `LoginPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — thin, mirroring how thin the page already
 * was: owns the two field signals and forwards straight to `AuthFacade.login()`, exactly as
 * `LoginPage` did inline before this refactor (byte-for-byte the same busy/error/canSubmit rules —
 * `AuthFacade` was already doing every real decision, this only moves *where* the plumbing sits).
 */
@Injectable()
export class LoginFacade {
  private readonly auth = inject(AuthFacade);
  private readonly router = inject(Router);

  readonly username = signal('');
  readonly password = signal('');

  /** `AuthFacade`'s own signals, re-exposed as this page's read-model — single source, not copied. */
  readonly busy = this.auth.loginBusy;
  readonly error = this.auth.loginError;

  canSubmit(): boolean {
    return !this.busy() && this.username().trim().length > 0 && this.password().length > 0;
  }

  async submit(returnUrl: string): Promise<void> {
    if (!this.canSubmit()) {
      return; // no empty-field request, and `busy()` itself rules out a double-submit mid-flight.
    }
    const ok = await this.auth.login(this.username().trim(), this.password());
    if (ok) {
      await this.router.navigateByUrl(returnUrl);
    }
  }
}
