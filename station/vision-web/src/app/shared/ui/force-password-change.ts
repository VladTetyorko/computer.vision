import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthStore } from '../../core/auth/auth-store';
import { Notice } from './notice';

/**
 * The forced password-change gate (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — mounted once,
 * unconditionally, in `app.html` alongside `<vision-reauth-overlay>` (same "always in the DOM,
 * self-gating" shape as that component and `<vision-toast-host>`), rendering nothing unless
 * `AuthStore.mustChangePassword()` is `true`: a session whose password is a one-time value someone
 * else chose for them (an admin's `POST /api/users/{id}/password` reset, or a group's own default
 * onboarding password) must change it before touching anything else in the app.
 *
 * **There is no separate "forced first change" endpoint** — the frozen wire only has
 * `POST /api/auth/password {currentPassword, newPassword}` (`AuthStore.changePassword`, the exact
 * same self-service call the account-settings Security section makes) — so this dialog re-asks for
 * the current (possibly just-issued temporary) password rather than skipping straight to a new one;
 * there is nothing else for the backend to check the request against.
 *
 * **z-index sits below `reauth-overlay.css`'s 200** — the two are not mutually exclusive
 * (`mustChangePassword` only clears once `userSignal` is null or the change succeeds, and a session
 * can still die mid-dialog on `/fly`), so if both would show at once, "your session is gone" wins:
 * nothing is actionable without a session at all, this dialog included.
 *
 * **Unlike `reauth-overlay.ts`, this one has a real "never mind" escape hatch** — "Log out instead"
 * — because unlike a dead session, a valid one genuinely exists here; an operator who doesn't want
 * to change their password right now can always sign back in as someone else, or come back later.
 * Calls `AuthStore.logout()` verbatim, the same method the identity chip's own sign-out button
 * calls.
 *
 * Injects `AuthStore` directly — a `shared/ui/**` component, not a routed page, so
 * `core/ui/architecture.spec.ts`'s facade rule doesn't apply (same precedent as
 * `reauth-overlay.ts`'s own class doc explains for its identical direct injection).
 */
@Component({
  selector: 'vision-force-password-change',
  imports: [FormsModule, Notice],
  templateUrl: './force-password-change.html',
  styleUrl: './force-password-change.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForcePasswordChange {
  protected readonly auth = inject(AuthStore);

  protected readonly currentPassword = signal('');
  protected readonly newPassword = signal('');
  protected readonly confirmPassword = signal('');
  private readonly busySignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);
  protected readonly busy = this.busySignal.asReadonly();
  protected readonly error = this.errorSignal.asReadonly();

  protected readonly passwordsMatch = computed(() => this.newPassword() === this.confirmPassword());

  protected canSubmit(): boolean {
    return (
      !this.busy() &&
      this.currentPassword().length > 0 &&
      this.newPassword().length > 0 &&
      this.passwordsMatch()
    );
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.errorSignal.set(null);
    this.busySignal.set(true);
    try {
      const error = await this.auth.changePassword(this.currentPassword(), this.newPassword());
      if (error) {
        this.errorSignal.set(error);
        return;
      }
      // Success re-fetches /me (`AuthStore.changePassword`'s own doc comment) — `mustChangePassword`
      // flips to `false` there, which is what closes this dialog; nothing to clear locally beyond
      // the fields themselves, in case the operator somehow reopens it (they can't, but tidy state
      // costs nothing).
      this.currentPassword.set('');
      this.newPassword.set('');
      this.confirmPassword.set('');
    } finally {
      this.busySignal.set(false);
    }
  }

  protected logout(): void {
    void this.auth.logout();
  }
}
