import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { EMPTY, Observable, catchError, concat, distinctUntilChanged, filter, from, map, mergeMap, of, switchMap, tap } from 'rxjs';
import type { Category, DiscoveryCandidate, VehicleProfile } from '../../../core/api/models';
import { describeHttpError } from '../../../core/api-error';
import { VisionApi } from '../../../core/api/vision-api';
import { defaultPilotSelection, pilotsInGroup } from '../../../core/org/pilot-logic';
import { isProbeDisabledError } from '../../../core/readiness/readiness-logic';
import { PollScheduler } from '../../../core/poll-scheduler';
import { ToastService } from '../../../core/toast.service';
import { buildMavlinkScanRequest } from '../drone-scan-logic';
import { OnboardingPhotoBuffer } from '../onboarding-photo-buffer';
import { prefillFromDiscoveryCandidate } from '../onboarding-logic';
import { detectSysidCollision, sysidParameterName } from '../sysid-collision-logic';
import { DISCOVERY_STATUS_POLL_MS } from './onboarding.model';
import { OnboardingApiActions, OnboardingPageActions } from './onboarding.actions';
import { onboardingWizardFeature } from './onboarding.reducer';
import type { FitOutRole } from '../../../core/onboarding/fit-out-logic';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching `discovery.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** One `GET /api/discovery/status` round trip — mirrors `OnboardingStore#fetchDiscoveryStatus`'s own silent-degrade exactly. */
function fetchDiscoveryStatus$(api: VisionApi): Observable<Action> {
  return concat(
    of(OnboardingPageActions.discoveryStatusRequested({ nowMs: Date.now() })),
    from(api.discoveryStatus()).pipe(
      map((discoveryStatus) => OnboardingApiActions.discoveryStatusSucceeded({ discoveryStatus })),
      catchError(() => of(OnboardingApiActions.discoveryStatusFailed())),
    ),
  );
}

/**
 * The waiting room's own poll — only ever runs while the `source` step itself is on screen, exactly
 * like `OnboardingStore`'s constructor-owned `step()` effect used to gate `startDiscoveryStatusPoll`/
 * `stopDiscoveryStatusPollNow`. The matching `DiscoveryInboxFacade#activate`/`#release` ref-count is
 * **not** driven from here — an `@ngrx/effects` class must never inject another facade (docs/plans/
 * active/NGRX-MIGRATION-PLAN.md §3 rule 4); `OnboardingWizardFacade`'s own constructor `effect()`
 * drives that half, watching the same `step` signal independently.
 */
export const discoveryStatusPoll$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) =>
    store.select(onboardingWizardFeature.selectStep).pipe(
      map((step) => step === 'source'),
      distinctUntilChanged(),
      switchMap((onSource) =>
        onSource ? concat(fetchDiscoveryStatus$(api), ticks$(scheduler, DISCOVERY_STATUS_POLL_MS).pipe(mergeMap(() => fetchDiscoveryStatus$(api)))) : EMPTY,
      ),
    ),
  { functional: true },
);

export const categoryOptionsLoad$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.categoryOptionsRequested),
      switchMap(() =>
        from(Promise.all([api.listAssets(), api.listCategories()])).pipe(
          map(([assets, categories]) => {
            const bySlug = new Map<string, { slug: string; name: string }>();
            for (const c of categories) {
              bySlug.set(c.slug, { slug: c.slug, name: c.name });
            }
            for (const asset of assets) {
              if (!bySlug.has(asset.category)) {
                bySlug.set(asset.category, { slug: asset.category, name: asset.categoryName });
              }
            }
            const categoryOptions = [...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name));
            return OnboardingApiActions.categoryOptionsSucceeded({ categoryOptions, categories: categories as readonly Category[] });
          }),
          catchError(() => of(OnboardingApiActions.categoryOptionsFailed())),
        ),
      ),
    ),
  { functional: true },
);

export const systemNetworkLoad$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.systemNetworkRequested),
      switchMap(() =>
        from(api.systemNetwork()).pipe(
          map((network) =>
            OnboardingApiActions.systemNetworkSucceeded({
              addresses: network.addresses,
              mavlinkPort: network.mavlinkPort,
              videoPushPort: network.videoPushPort,
              videoPushPathPrefix: network.videoPushPathPrefix,
            }),
          ),
          catchError(() => of(OnboardingApiActions.systemNetworkFailed())),
        ),
      ),
    ),
  { functional: true },
);

/** Mirrors `OnboardingStore#applyCandidateQueryPrefill` — one inbox fetch + a client-side find, since
 *  no single-candidate-by-id `GET` exists. */
export const candidateQueryPrefillLoad$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.candidateQueryPrefillRequested),
      switchMap(({ candidateId }) =>
        from(api.listDiscoveryInboxCandidates()).pipe(
          map((response) => {
            const candidate = response.candidates.find((c) => c.id === candidateId);
            return candidate
              ? OnboardingApiActions.candidateQueryPrefillSucceeded({ candidate })
              : OnboardingApiActions.candidateQueryPrefillFailed();
          }),
          catchError(() => of(OnboardingApiActions.candidateQueryPrefillFailed())),
        ),
      ),
    ),
  { functional: true },
);

export const scan$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.scanRequested),
      switchMap(({ timeoutMs }) =>
        from(api.scan({ timeoutMs })).pipe(
          map((result) => OnboardingApiActions.scanSucceeded({ result })),
          catchError((error: unknown) => of(OnboardingApiActions.scanFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const droneScan$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.droneScanRequested),
      switchMap(() =>
        from(api.scan(buildMavlinkScanRequest())).pipe(
          map((result) => OnboardingApiActions.droneScanSucceeded({ result })),
          catchError((error: unknown) => of(OnboardingApiActions.droneScanFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const testRow$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.testRowRequested),
      mergeMap(({ role, request }) =>
        from(api.probeDevice(request)).pipe(
          map((result) => OnboardingApiActions.testRowSucceeded({ role, request, result })),
          catchError((error: unknown) => of(OnboardingApiActions.testRowFailed({ role, request, error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** The device-list read feeding a row's `sysidCollision` — mirrors `OnboardingStore#detectSysidCollisionQuietly`'s own silent-degrade. */
function sysidCollisionCheck$(api: VisionApi, role: FitOutRole, profile: VehicleProfile): Observable<Action> {
  return from(api.listDevices()).pipe(
    map((devices) => {
      const collision = detectSysidCollision(profile, devices);
      return OnboardingApiActions.sysidCollisionChecked({
        role,
        collision,
        parameterToWrite: collision !== null ? sysidParameterName(profile) : null,
      });
    }),
    catchError(() => of(OnboardingApiActions.sysidCollisionCheckFailed())),
  );
}

export const verifyRow$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.verifyRowRequested),
      mergeMap(({ role, request }) =>
        from(api.probeVehicleCandidate(request)).pipe(
          switchMap((result) => concat(of(OnboardingApiActions.verifyRowSucceeded({ role, request, result })), sysidCollisionCheck$(api, role, result))),
          catchError((error: unknown) =>
            of(
              isProbeDisabledError(error)
                ? OnboardingApiActions.verifyRowFailed({ role, request, error: null, disabled: true })
                : OnboardingApiActions.verifyRowFailed({ role, request, error: describeHttpError(error), disabled: false }),
            ),
          ),
        ),
      ),
    ),
  { functional: true },
);

/** Mirrors `OnboardingStore#chooseAttachTarget`'s own "load once, on demand" guard. */
export const loadExistingAssetsOnTarget$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.attachTargetChosen, OnboardingPageActions.attachExistingFromConfirm),
      concatLatestFrom(() => [
        store.select(onboardingWizardFeature.selectAttachTarget),
        store.select(onboardingWizardFeature.selectExistingAssets),
        store.select(onboardingWizardFeature.selectExistingAssetsLoading),
      ]),
      filter(([, target, assets, loading]) => target === 'existing' && assets.length === 0 && !loading),
      map(() => OnboardingPageActions.existingAssetsRequested()),
    ),
  { functional: true },
);

export const existingAssetsLoad$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.existingAssetsRequested),
      switchMap(() =>
        from(api.listAssets()).pipe(
          map((assets) => OnboardingApiActions.existingAssetsSucceeded({ assets })),
          catchError((error: unknown) => of(OnboardingApiActions.existingAssetsFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const createAsset$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.createAssetRequested),
      mergeMap(({ request }) =>
        from(api.createAsset(request)).pipe(
          map((created) => OnboardingApiActions.createAssetSucceeded({ assetId: created.assetId, displayName: created.displayName })),
          catchError((error: unknown) => of(OnboardingApiActions.createAssetFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** The "create minimal, then PATCH" follow-up edit — shared by `createViaCandidateRegister` and `createViaSimulation`'s own post-creation identity patch. */
export const updateAssetEdit$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.updateAssetEditRequested),
      mergeMap(({ assetId, edit }) =>
        from(api.updateAsset(assetId, edit)).pipe(
          map((asset) => OnboardingApiActions.updateAssetEditSucceeded({ displayName: asset.displayName })),
          catchError((error: unknown) => of(OnboardingApiActions.updateAssetEditFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** The plain register-device-then-assign loop — mirrors `OnboardingStore#registerAndAssignRows`. */
export const registerAndAssign$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.registerAndAssignRequested),
      mergeMap(({ assetId, specs }) =>
        from(
          (async () => {
            for (const spec of specs) {
              const device = await api.registerDevice(spec);
              await api.assignDevice(assetId, device.id);
            }
          })(),
        ).pipe(
          map(() => OnboardingApiActions.registerAndAssignSucceeded()),
          catchError((error: unknown) => of(OnboardingApiActions.registerAndAssignFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/**
 * The photo upload — reads the still-live `Blob` straight off `OnboardingPhotoBuffer` rather than
 * carrying it on the action (a `Blob` would trip `strictActionSerializability`). Legal for an effect
 * to inject: `OnboardingPhotoBuffer` is a plain page-provided service, not a facade — the ban in §3
 * rule 4 is on injecting *another facade*. A blob no longer present (removed between dispatch and
 * here) degrades to a no-op success — `OnboardingWizardFacade#finishCreate` only ever dispatches this
 * action after confirming `currentBlob` is set, so this is defensive, not a case a correct caller hits.
 */
export const uploadAssetImage$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi), photoBuffer = inject(OnboardingPhotoBuffer)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.uploadAssetImageRequested),
      mergeMap(({ assetId, displayName }) => {
        const blob = photoBuffer.currentBlob;
        if (!blob) {
          return of(OnboardingApiActions.uploadAssetImageSucceeded({ displayName }));
        }
        return from(api.uploadAssetImage(assetId, blob)).pipe(
          map(() => OnboardingApiActions.uploadAssetImageSucceeded({ displayName })),
          catchError((error: unknown) => of(OnboardingApiActions.uploadAssetImageFailed({ displayName, error: describeHttpError(error) }))),
        );
      }),
    ),
  { functional: true },
);

export const pilotCandidatesLoad$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.pilotCandidatesRequested),
      switchMap(({ group, creatorUserId }) =>
        from(api.listUsers()).pipe(
          map((users) =>
            OnboardingApiActions.pilotCandidatesSucceeded({
              candidates: pilotsInGroup(users, group?.groupId),
              defaultCustodianId: creatorUserId ? (defaultPilotSelection(creatorUserId, group)[0] ?? null) : null,
            }),
          ),
          catchError(() => of(OnboardingApiActions.pilotCandidatesFailed())),
        ),
      ),
    ),
  { functional: true },
);

export const writeSysid$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.writeSysidRequested),
      switchMap(({ assetId, request }) =>
        from(api.writeAssetParameter(assetId, request)).pipe(
          map((result) => OnboardingApiActions.writeSysidSucceeded({ result })),
          catchError((error: unknown) => of(OnboardingApiActions.writeSysidFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const setAssetCustody$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.setAssetCustodyRequested),
      switchMap(({ assetId, custodianId, location }) =>
        from(api.setAssetCustody(assetId, { action: 'ISSUE', custodianId, ...(location.length > 0 ? { location } : {}) })).pipe(
          map(() => OnboardingApiActions.setAssetCustodySucceeded()),
          catchError((error: unknown) => of(OnboardingApiActions.setAssetCustodyFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

// --- Toasting — every side effect the old store's own scattered `this.toasts.*` call sites performed,
// moved here per §3 rule 6. -----------------------------------------------------------------------

/** Every operator-initiated mutation whose catch block was a plain `toasts.error(describeHttpError(error))` — see each action's own doc comment in `onboarding.actions.ts`. */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        OnboardingApiActions.scanFailed,
        OnboardingApiActions.droneScanFailed,
        OnboardingApiActions.existingAssetsFailed,
        OnboardingApiActions.createAssetFailed,
        OnboardingApiActions.updateAssetEditFailed,
        OnboardingApiActions.registerAndAssignFailed,
      ),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

export const notifyScanResult$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingApiActions.scanSucceeded),
      tap(({ result }) => {
        if (result.failedMethods.length > 0) {
          toasts.error(`These scanners failed and found nothing: ${result.failedMethods.join(', ')}.`);
        }
        if (result.devices.length === 0 && result.failedMethods.length === 0) {
          toasts.info('Scan finished — nothing responded on this network.');
        }
      }),
    ),
  { functional: true, dispatch: false },
);

export const notifyDroneScanResult$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingApiActions.droneScanSucceeded),
      tap(({ result }) => {
        if (result.failedMethods.length > 0) {
          toasts.error('The MAVLink scanner failed — check that nothing else is bound to its port.');
        }
      }),
    ),
  { functional: true, dispatch: false },
);

/** `OnboardingStore#useCandidate`'s own "could not supply a stream URI" info toast. */
export const notifySightCandidateMissingUri$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.sightCandidateChosen),
      tap(({ candidate }) => {
        if (!candidate.uri) {
          toasts.info(`${candidate.method} could not supply a stream URI — check the address before registering.`);
        }
      }),
    ),
  { functional: true, dispatch: false },
);

/** `OnboardingStore#prefillRowFromCandidate`/`chooseFoundCandidate`'s shared "no suggested stream yet" info toast — recomputes the same pure predicate the reducer already applied, per this wave's own documented "cheap redundant-but-pure" tradeoff (see `onboarding.reducer.ts#applyCandidatePrefill`'s own doc comment). */
export const notifyPrefillMiss$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.rowPrefilledFromCandidate, OnboardingPageActions.foundCandidateChosen),
      tap(({ candidate }: { candidate: DiscoveryCandidate }) => {
        if (!prefillFromDiscoveryCandidate(candidate)) {
          toasts.info(`${candidate.method} could not supply a stream address for "${candidate.name}" yet.`);
        }
      }),
    ),
  { functional: true, dispatch: false },
);

/** `OnboardingStore#finishCreate`'s own bespoke "photo could not be uploaded" toast. */
export const notifyUploadImageFailed$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingApiActions.uploadAssetImageFailed),
      tap(({ displayName, error }) => toasts.error(`"${displayName}" was created, but the photo could not be uploaded: ${error}`)),
    ),
  { functional: true, dispatch: false },
);

/** `OnboardingStore#finishCreate`'s own "is ready" toast — fires once the wizard reaches either of
 *  the two post-creation steps, independent of which creation branch produced the asset. */
export const notifyAssetReady$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(OnboardingPageActions.sysidStepEntered, OnboardingPageActions.handoverStepEntered),
      tap(({ displayName }) => toasts.ok(`"${displayName}" is ready.`)),
    ),
  { functional: true, dispatch: false },
);

export const onboardingEffects = {
  discoveryStatusPoll$,
  categoryOptionsLoad$,
  systemNetworkLoad$,
  candidateQueryPrefillLoad$,
  scan$,
  droneScan$,
  testRow$,
  verifyRow$,
  loadExistingAssetsOnTarget$,
  existingAssetsLoad$,
  createAsset$,
  updateAssetEdit$,
  registerAndAssign$,
  uploadAssetImage$,
  pilotCandidatesLoad$,
  writeSysid$,
  setAssetCustody$,
  notifyFailure$,
  notifyScanResult$,
  notifyDroneScanResult$,
  notifySightCandidateMissingUri$,
  notifyPrefillMiss$,
  notifyUploadImageFailed$,
  notifyAssetReady$,
};
