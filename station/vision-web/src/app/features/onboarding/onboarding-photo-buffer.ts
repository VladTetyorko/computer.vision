import { Injectable, OnDestroy, signal } from '@angular/core';
import { downscaleImageToJpeg, isAcceptableImageType } from './image-downscale';

/**
 * Owns the Identify step's photo `File`/`Blob`/object-URL (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * §3 rule 11 — non-serializable state must never enter an NgRx slice). The `GlobalOverlayStore`
 * split precedent (wave N1): the *serializable* half (`photoProcessing`/`photoError`) lives in
 * `OnboardingWizardState`; this service holds the rest and the revoke lifecycle that goes with it.
 *
 * **Provided by `state/onboarding.providers.ts#provideOnboardingState()`, deliberately not by
 * `OnboardingPage`'s component `providers:`** — `onboarding.effects.ts#uploadAssetImage$` injects
 * it, and an `@ngrx/effects` class resolves against the **environment** injector, which cannot see
 * an element-injector provider. Moving this into the component's `providers:` compiles cleanly and
 * fails at runtime on the first photo upload; see that providers file's own doc comment.
 *
 * `previewUrl` is exposed directly off this service, not proxied through the store — ownership
 * (create/revoke) never leaves here, so there is nothing for the store to gain by re-exposing it, and
 * every consumer already reads it as `facade.store.photoPreviewUrl()`-shaped, now
 * `facade.photoBuffer.previewUrl()` (see `OnboardingWizardFacade`'s own doc comment for the one
 * template call site this moves).
 */
@Injectable()
export class OnboardingPhotoBuffer implements OnDestroy {
  private blob: Blob | null = null;
  private readonly url = signal<string | null>(null);

  readonly previewUrl = this.url.asReadonly();

  /** The currently-held downscaled image, if any — read once, at asset-creation time, by
   *  `OnboardingWizardFacade#finishCreate`'s own upload step. */
  get currentBlob(): Blob | null {
    return this.blob;
  }

  /** Downscales in the background (`image-downscale.ts`) and, on success, replaces the current
   *  preview. Returns an outcome rather than throwing — the caller dispatches the matching
   *  `photoAccepted`/`photoRejected` fact action itself. */
  async choose(file: File): Promise<{ readonly ok: true } | { readonly ok: false; readonly error: string }> {
    if (!isAcceptableImageType(file.type)) {
      return { ok: false, error: 'Choose a JPEG, PNG, or WebP image.' };
    }
    try {
      const blob = await downscaleImageToJpeg(file);
      this.setBlob(blob);
      return { ok: true };
    } catch {
      this.blob = null;
      return { ok: false, error: 'Could not process that image — try a different file.' };
    }
  }

  remove(): void {
    this.revoke();
    this.blob = null;
  }

  private setBlob(blob: Blob): void {
    this.revoke();
    this.blob = blob;
    this.url.set(URL.createObjectURL(blob));
  }

  private revoke(): void {
    const current = this.url();
    if (current) {
      URL.revokeObjectURL(current);
    }
    this.url.set(null);
  }

  /** Angular calls this when the `/add-source` route's own environment injector is destroyed (this
   *  service is registered there, not on the component) — the same teardown `OnboardingStore`'s
   *  `DestroyRef.onDestroy` used to perform for the photo half. */
  ngOnDestroy(): void {
    this.revoke();
    this.blob = null;
  }
}
