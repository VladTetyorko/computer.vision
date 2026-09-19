import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canManageOrg } from '../../core/org/org-logic';
import type {
  AssetSummary,
  BindingScope,
  Category,
  CvCoverageRow,
  CvModel,
  CvProfile,
  CvProfileIntent,
  CvTracker,
  GroupSummary,
} from '../../core/api/models';
import {
  applyIntentToDraft,
  bindingSummaryLabel,
  canDeleteProfile,
  canEditProfile,
  canForkProfile,
  coverageRowClearTarget,
  draftFromProfile,
  draftToRequest,
  emptyProfileDraft,
  forkDraftFromProfile,
  isDetectionAlways,
  primaryGroupId,
  saveOutcomeMessage,
  sortProfilesForDisplay,
  summarizeProfileBindings,
  validateDraft,
  withDetectionPolicy,
  type ProfileDraft,
} from './vision-profiles-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[vision-profiles]';

/**
 * `VisionProfilesPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/vision/profiles`
 * (docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6). Page-local state, no shared store — the
 * profile roster/coverage table is single-consumer, the same `ModelsFacade`/`MaintenanceFacade`
 * shape ("a routed page's facade may talk to `VisionApi` directly").
 *
 * **The backend did not exist when this wave landed** (docs/plans/active/CV-SETTINGS-CONTEXT.md's
 * ledger — W2/W3/W4 land concurrently). {@link load} therefore treats every one of its seven reads as
 * required for the page to be useful (the editor needs the model/tracker roster, the binding form
 * needs categories/assets/groups) and degrades the *whole* page to one visible notice + Try again on
 * any failure — never a page built from some real rows and some fabricated ones (§3.5 rule 2).
 *
 * **Role-gating**: {@link canManage} (`canManageOrg`, the same predicate `org-guard.ts`/`ModelsFacade`
 * use) hides every write affordance — New/Edit/Fork/Delete/Set binding/Clear — for a non-manager;
 * the route itself also carries `orgGuard` (`vision-profiles.routes.ts`), so a non-manager never
 * reaches this page at all. **Dev parity**: `vision.auth.enabled=false`'s dev principal resolves to
 * `ADMIN`/unbounded, so `canManageOrg` is `true` and every action behaves exactly as for a real admin.
 *
 * **No `GET /api/cv/bindings`** (§5.2 has only the write verbs) — {@link bindingSummaries} derives
 * "bound to" counts from {@link coverage} instead; see `summarizeProfileBindings`'s own doc comment
 * for the one documented gap (a binding matching zero assets today is invisible).
 */
@Injectable()
export class VisionProfilesFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthFacade);

  private readonly profilesSignal = signal<readonly CvProfile[]>([]);
  private readonly modelsSignal = signal<readonly CvModel[]>([]);
  private readonly trackersSignal = signal<readonly CvTracker[]>([]);
  private readonly categoriesSignal = signal<readonly Category[]>([]);
  private readonly assetsSignal = signal<readonly AssetSummary[]>([]);
  private readonly groupsSignal = signal<readonly GroupSummary[]>([]);
  private readonly coverageSignal = signal<readonly CvCoverageRow[]>([]);

  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly errorSignal = signal<string | null>(null);

  private readonly draftSignal = signal<ProfileDraft | null>(null);
  private readonly formErrorsSignal = signal<readonly string[]>([]);
  private readonly savingSignal = signal(false);
  private readonly busyProfileIdSignal = signal<string | null>(null);

  private readonly bindingScopeKindSignal = signal<BindingScope>('ASSET');
  private readonly bindingScopeIdSignal = signal('');
  private readonly bindingProfileIdSignal = signal('');
  private readonly bindingSavingSignal = signal(false);
  /** `"SCOPE:id"` of the binding currently being written/cleared — disables that one control
   *  (a row's own Clear button, or the org-default Clear button) without blocking the rest of the page. */
  private readonly bindingBusyKeySignal = signal<string | null>(null);

  /**
   * The `attributes` map of the asset currently selected as the Bindings form's `ASSET`-scope
   * target, keyed by `assetId` so a stale fetch racing a newer selection is never shown (D7, §4.7:
   * "`cv.detection-policy = ALWAYS` gets a control on the asset binding in `/vision/profiles`").
   * `null` while no `ASSET` target is selected, or while the fetch for the current one is still in
   * flight — {@link detectionAlwaysOn} reads `null` the same honest way: never an invented/optimistic
   * checked state before the real value is known.
   */
  private readonly bindingAssetAttributesSignal = signal<{ readonly assetId: string; readonly attributes: Record<string, string> } | null>(null);
  private readonly detectionPolicySavingSignal = signal(false);

  readonly profiles = computed(() => sortProfilesForDisplay(this.profilesSignal()));
  readonly models = this.modelsSignal.asReadonly();
  readonly trackers = this.trackersSignal.asReadonly();
  readonly categories = this.categoriesSignal.asReadonly();
  readonly assets = this.assetsSignal.asReadonly();
  readonly groups = this.groupsSignal.asReadonly();
  readonly coverage = this.coverageSignal.asReadonly();

  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first {@link load} settles — lets the page tell "still loading" from "genuinely empty". */
  readonly loaded = this.loadedSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  readonly canManage = computed(() => canManageOrg(this.auth.capabilities()));
  /** The caller's own org `groupId` (single-org simplification — see `primaryGroupId`'s own doc comment). */
  readonly orgGroupId = computed(() => primaryGroupId(this.auth.user()?.memberships ?? []));
  readonly orgGroupName = computed(() => {
    const id = this.orgGroupId();
    return id ? (this.groups().find((g) => g.id === id)?.name ?? id) : null;
  });

  readonly draft = this.draftSignal.asReadonly();
  readonly editorOpen = computed(() => this.draft() !== null);
  readonly formErrors = this.formErrorsSignal.asReadonly();
  readonly saving = this.savingSignal.asReadonly();
  readonly busyProfileId = this.busyProfileIdSignal.asReadonly();

  readonly bindingScopeKind = this.bindingScopeKindSignal.asReadonly();
  readonly bindingScopeId = this.bindingScopeIdSignal.asReadonly();
  readonly bindingProfileId = this.bindingProfileIdSignal.asReadonly();
  readonly bindingSaving = this.bindingSavingSignal.asReadonly();
  readonly bindingBusyKey = this.bindingBusyKeySignal.asReadonly();

  /** `null` while loading/no `ASSET` target selected — see {@link bindingAssetAttributesSignal}'s own
   * doc comment; otherwise the target asset's current always-on choice (D7). */
  readonly detectionAlwaysOn = computed(() => {
    const loaded = this.bindingAssetAttributesSignal();
    return loaded ? isDetectionAlways(loaded.attributes) : null;
  });
  readonly detectionPolicySaving = this.detectionPolicySavingSignal.asReadonly();

  /** Every profile's own "bound to" summary, derived from {@link coverage} — see class doc. */
  readonly bindingSummaries = computed(() => summarizeProfileBindings(this.coverageSignal()));

  constructor() {
    void this.load();
  }

  /** Reads every list this page needs in one pass; a failure on any of them degrades the whole page
   * to {@link error} + Try again, never a page built from some real rows and some fabricated ones. */
  async load(): Promise<void> {
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    try {
      const [profiles, models, trackers, categories, assets, groups, coverage] = await Promise.all([
        this.api.getCvProfiles(),
        this.api.getCvModels(),
        this.api.getCvTrackers(),
        this.api.listCategories(),
        this.api.listAssets(),
        this.api.listGroups(),
        this.api.getCvCoverage(),
      ]);
      this.profilesSignal.set(profiles.profiles);
      this.modelsSignal.set(models.models);
      this.trackersSignal.set(trackers.trackers);
      this.categoriesSignal.set(categories);
      this.assetsSignal.set(assets);
      this.groupsSignal.set(groups);
      this.coverageSignal.set(coverage.rows);
      this.loadedSignal.set(true);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to load`, { error });
      this.errorSignal.set(describeHttpError(error));
    } finally {
      this.loadingSignal.set(false);
    }
  }

  /** Re-reads only the two mutation-sensitive lists (profiles + coverage) after a write — cheaper
   * than a full {@link load}, and leaves the model/tracker/category/asset/group rosters untouched. */
  private async reloadAfterMutation(): Promise<void> {
    try {
      const [profiles, coverage] = await Promise.all([this.api.getCvProfiles(), this.api.getCvCoverage()]);
      this.profilesSignal.set(profiles.profiles);
      this.coverageSignal.set(coverage.rows);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to refresh after a write`, { error });
      this.toasts.error(describeHttpError(error));
    }
  }

  // --- Row-level gates, thin wrappers so the template never imports the pure functions directly ---

  canEdit(profile: CvProfile): boolean {
    return canEditProfile(profile, this.canManage());
  }

  canFork(profile: CvProfile): boolean {
    return canForkProfile(profile, this.canManage());
  }

  canDelete(profile: CvProfile): boolean {
    return canDeleteProfile(profile, this.canManage());
  }

  bindingSummaryFor(profile: CvProfile): string {
    return bindingSummaryLabel(this.bindingSummaries().get(profile.id));
  }

  // --- Editor ------------------------------------------------------------------------------------

  openCreate(): void {
    if (!this.canManage()) {
      return;
    }
    this.formErrorsSignal.set([]);
    this.draftSignal.set(emptyProfileDraft());
  }

  openEdit(profile: CvProfile): void {
    if (!this.canEdit(profile)) {
      return;
    }
    this.formErrorsSignal.set([]);
    this.draftSignal.set(draftFromProfile(profile));
  }

  openFork(profile: CvProfile): void {
    if (!this.canFork(profile)) {
      return;
    }
    this.formErrorsSignal.set([]);
    this.draftSignal.set(
      forkDraftFromProfile(
        profile,
        this.profiles().map((p) => p.name),
      ),
    );
  }

  closeEditor(): void {
    this.draftSignal.set(null);
    this.formErrorsSignal.set([]);
  }

  patchDraft(patch: Partial<ProfileDraft>): void {
    const current = this.draftSignal();
    if (current) {
      this.draftSignal.set({ ...current, ...patch });
    }
  }

  /** The intent picker's own setter — routes through {@link applyIntentToDraft} rather than plain
   * {@link patchDraft} because picking an intent may also blank `model` (see that function's own doc
   * comment for exactly when). */
  setIntent(intent: CvProfileIntent | ''): void {
    const current = this.draftSignal();
    if (current) {
      this.draftSignal.set(applyIntentToDraft(current, intent));
    }
  }

  /** Validates then POSTs (new/fork) or PUTs (edit) the current draft, per §5.1's frozen `CvProfileRequest`.
   * The success toast names which knob(s) the server resolved from `draft.intent` ({@link
   * saveOutcomeMessage}, §4.7 wave W3.6) — `saved.sources` is only ever meaningful on this create/
   * update response, never on a later read (`CvProfile#sources`'s own doc comment). */
  async saveDraft(): Promise<void> {
    const draft = this.draftSignal();
    if (!draft) {
      return;
    }
    const errors = validateDraft(draft);
    this.formErrorsSignal.set(errors);
    if (errors.length > 0) {
      return;
    }
    this.savingSignal.set(true);
    try {
      const request = draftToRequest(draft);
      const sourceId = draft.sourceId;
      const saved = sourceId ? await this.api.updateCvProfile(sourceId, request) : await this.api.createCvProfile(request);
      this.toasts.ok(saveOutcomeMessage(saved, draft.intent, sourceId !== null));
      await this.reloadAfterMutation();
      this.closeEditor();
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to save a profile`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.savingSignal.set(false);
    }
  }

  /** `404` (already gone) and any other failure both surface via `describeHttpError` — no client-side
   * "is it still bound?" precondition invented ahead of the server's own `409` for that case. */
  async deleteProfile(profile: CvProfile): Promise<void> {
    if (!this.canDelete(profile) || this.busyProfileIdSignal() !== null) {
      return;
    }
    this.busyProfileIdSignal.set(profile.id);
    try {
      await this.api.deleteCvProfile(profile.id);
      await this.reloadAfterMutation();
      this.toasts.ok(`"${profile.name}" deleted.`);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to delete ${profile.id}`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyProfileIdSignal.set(null);
    }
  }

  // --- Bindings ------------------------------------------------------------------------------------

  /** Switching scope kind resets the target — `ORGANIZATION` auto-targets the caller's own org
   * ({@link orgGroupId}, no picker needed; see that computed's own doc comment), `CATEGORY`/`ASSET`
   * clear back to "choose one" so a stale target from a different kind is never submitted by accident. */
  setBindingScopeKind(kind: BindingScope): void {
    this.bindingScopeKindSignal.set(kind);
    this.bindingScopeIdSignal.set(kind === 'ORGANIZATION' ? (this.orgGroupId() ?? '') : '');
    this.bindingAssetAttributesSignal.set(null);
  }

  setBindingScopeId(scopeId: string): void {
    this.bindingScopeIdSignal.set(scopeId);
    if (this.bindingScopeKindSignal() === 'ASSET' && scopeId) {
      void this.loadDetectionPolicyTarget(scopeId);
    } else {
      this.bindingAssetAttributesSignal.set(null);
    }
  }

  /**
   * Fetches the selected `ASSET` binding target's own current `attributes` for {@link
   * detectionAlwaysOn} (D7) — the Bindings form's natural home for this control, since it already
   * carries the chosen asset id and this page has no other per-asset detail read. One extra `GET
   * /api/assets/{id}` per selection (this page has no already-fetched full asset elsewhere: {@link
   * assets} is the summary roster the picker's own `<option>` list reads from, and `AssetSummary`
   * already carries `attributes` in full — but re-fetching here, not reading that roster entry,
   * guarantees the checkbox reflects the asset's live value rather than whatever `load()` cached at
   * page-open, honest even if another tab changed it since).
   */
  private async loadDetectionPolicyTarget(assetId: string): Promise<void> {
    this.bindingAssetAttributesSignal.set(null);
    try {
      const asset = await this.api.getAsset(assetId);
      if (this.bindingScopeKindSignal() === 'ASSET' && this.bindingScopeIdSignal() === assetId) {
        this.bindingAssetAttributesSignal.set({ assetId, attributes: asset.attributes });
      }
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to load asset attributes for the detection-policy control`, { error });
      if (this.bindingScopeKindSignal() === 'ASSET' && this.bindingScopeIdSignal() === assetId) {
        this.toasts.error(describeHttpError(error));
      }
    }
  }

  /** Toggles `cv.detection-policy` for the selected `ASSET` binding target — merges into the asset's
   * full current `attributes` map and `PATCH`es it back (`AssetEdit#attributes` is a wholesale
   * replacement, never a merge server-side; {@link withDetectionPolicy}'s own doc comment). */
  async toggleDetectionAlways(): Promise<void> {
    const loaded = this.bindingAssetAttributesSignal();
    if (!loaded || !this.canManage() || this.detectionPolicySavingSignal()) {
      return;
    }
    const nextAlways = !isDetectionAlways(loaded.attributes);
    this.detectionPolicySavingSignal.set(true);
    try {
      const updated = await this.api.updateAsset(loaded.assetId, { attributes: withDetectionPolicy(loaded.attributes, nextAlways) });
      this.bindingAssetAttributesSignal.set({ assetId: loaded.assetId, attributes: updated.attributes });
      this.toasts.ok(
        nextAlways ? 'Always-on detection enabled for this asset.' : 'Always-on detection turned off for this asset.',
      );
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to update the detection policy for ${loaded.assetId}`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.detectionPolicySavingSignal.set(false);
    }
  }

  setBindingProfileId(profileId: string): void {
    this.bindingProfileIdSignal.set(profileId);
  }

  canSubmitBinding(): boolean {
    return this.bindingScopeIdSignal().trim().length > 0 && this.bindingProfileIdSignal().trim().length > 0;
  }

  async submitBinding(): Promise<void> {
    if (!this.canManage() || !this.canSubmitBinding()) {
      return;
    }
    const scopeKind = this.bindingScopeKindSignal();
    const scopeId = this.bindingScopeIdSignal();
    const key = `${scopeKind}:${scopeId}`;
    this.bindingSavingSignal.set(true);
    this.bindingBusyKeySignal.set(key);
    try {
      await this.api.setCvProfileBinding({ scopeKind, scopeId, profileId: this.bindingProfileIdSignal() });
      await this.reloadAfterMutation();
      this.toasts.ok('Binding saved.');
      this.bindingProfileIdSignal.set('');
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to set a binding`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.bindingSavingSignal.set(false);
      this.bindingBusyKeySignal.set(null);
    }
  }

  /** Clears the binding a coverage row resolved through — see `coverageRowClearTarget`'s own doc
   * comment for why an `ORGANIZATION`/`PLATFORM`-sourced row has nothing to clear here. */
  async clearCoverageRow(row: CvCoverageRow): Promise<void> {
    const target = coverageRowClearTarget(row);
    if (!target || !this.canManage()) {
      return;
    }
    await this.clearBinding(target.scopeKind, target.scopeId);
  }

  async clearOrgDefault(): Promise<void> {
    const groupId = this.orgGroupId();
    if (!groupId || !this.canManage()) {
      this.toasts.error('No organization to clear a default for.');
      return;
    }
    await this.clearBinding('ORGANIZATION', groupId);
  }

  isClearingBinding(scopeKind: BindingScope, scopeId: string): boolean {
    return this.bindingBusyKeySignal() === `${scopeKind}:${scopeId}`;
  }

  private async clearBinding(scopeKind: BindingScope, scopeId: string): Promise<void> {
    const key = `${scopeKind}:${scopeId}`;
    this.bindingBusyKeySignal.set(key);
    try {
      await this.api.deleteCvProfileBinding(scopeKind, scopeId);
      await this.reloadAfterMutation();
      this.toasts.ok('Binding cleared.');
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to clear a binding`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.bindingBusyKeySignal.set(null);
    }
  }
}
