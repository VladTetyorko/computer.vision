import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AuthStore } from '../../core/auth/auth-store';
import { Notice } from './notice';

/**
 * The mid-flight reauth overlay (docs/plans/active/AUTH-ROLES-PLAN.md §3.7 clause 2, wave W1) — mounted
 * once, unconditionally, in `app.html` (same "always in the DOM, self-gating" shape as
 * `<vision-toast-host>`/`<vision-undo-toast>`), rendering nothing unless `AuthStore.reauthRequired()`
 * is `true`.
 *
 * **The frozen rule this exists for**: a session that dies while the operator is on `/fly` must be
 * recoverable *without leaving that page* — never a redirect to `/login`, and never touching the
 * manual-control RC websocket. `core/auth/session-interceptor.ts` sets `reauthRequired` (and nothing
 * else) for exactly that case; this component is the other half — an in-place sign-in form over a
 * backdrop, so whatever's on screen behind it (the cockpit, a live manual-control session) is
 * completely undisturbed and still there the instant this closes.
 *
 * **Reuses `AuthStore.login()` verbatim** — the same call the `/login` page itself makes — rather
 * than a separate reauth-specific method: a successful call already clears `reauthRequired`
 * (`AuthStore.applySession` always resets it) and refreshes the session in place, with no navigation
 * anywhere, which is exactly this overlay's contract. `loginBusy`/`loginError` are the same signals
 * the login page reads; the two are never visible at the same time in practice (one requires a
 * session already present, the other requires none), so sharing them causes no cross-talk.
 *
 * Injects `AuthStore` directly — a `shared/ui/**` component, not a routed page, so
 * `core/ui/architecture.spec.ts`'s facade rule doesn't apply (same precedent as
 * `shared/ui/identity-chip.ts`'s own class doc explains for its identical direct injection).
 *
 * Deliberately no dismiss/cancel affordance: unlike `shared/ui/confirm-dialog.ts`, there is no safe
 * "never mind" here — the session is genuinely gone, and the only way off `/fly` without signing back
 * in would be a manual URL change, which is the operator's own choice, not a button this overlay
 * offers.
 *
 * **No `FormsModule`** — bound with native `[value]`/`(input)` instead. This component is
 * reachable from the eagerly-loaded app shell (`app.ts` renders it unconditionally), so importing
 * `FormsModule` here pulls all ~36 kB of `@angular/forms` into the *initial* bundle to serve a
 * dialog almost nobody sees, which on its own broke `angular.json`'s 440 kB initial-bundle error
 * budget. The bindings were already one-way into signals, so forms bought nothing. Keep it that
 * way: `required` stays for a11y only — `canSubmit()` is the real gate, so native validation
 * never fires.
 */
@Component({
  selector: 'vision-reauth-overlay',
  imports: [Notice],
  templateUrl: './reauth-overlay.html',
  styleUrl: './reauth-overlay.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReauthOverlay {
  protected readonly auth = inject(AuthStore);

  protected readonly username = signal('');
  protected readonly password = signal('');

  protected canSubmit(): boolean {
    return !this.auth.loginBusy() && this.username().trim().length > 0 && this.password().length > 0;
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    const ok = await this.auth.login(this.username().trim(), this.password());
    if (ok) {
      // Cleared only on success — a failed attempt keeps the username/password so the operator
      // doesn't have to retype both after a mistyped password, mirroring the login page's own
      // (LoginFacade's) behavior of leaving the fields alone on a rejected attempt.
      this.username.set('');
      this.password.set('');
    }
  }
}
