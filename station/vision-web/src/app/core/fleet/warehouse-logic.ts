import type { AssetSummary, Device, DeviceEdit, AssetEdit, LifecycleState, SettableLifecycleState } from '../api/models';

/**
 * Pure, Angular-free device/asset lifecycle logic (docs/main/CYCLES-PLAN.md §8), shared across pages.
 *
 * Started life in `features/devices/devices-page-logic.ts` (CW-b, the warehouse UI). CD-b's asset detail
 * page (`features/asset-detail/asset-detail.ts`) needs the exact same lifecycle-action-menu state
 * machine and edit-request builders for its "Hardware" section and its own asset header — and
 * this codebase has no precedent for one page importing another page's module (every cross-page
 * dependency runs through `core/`, see `core/device-logic.ts`'s doc comment for the original
 * precedent) — so the generic pieces were lifted here. `features/devices/devices-page-logic.ts` keeps
 * the Devices-page-specific view models (`WarehouseRow`/`AssetRow`/`AssetListRow` and their
 * builders/filters, `mapDeviceOwners`) that only that page's table/list rendering needs.
 */

/**
 * `DEACTIVATED` on an already-`DELETED` thing is how the pinned contract spells "restore" — there
 * is no direct `DELETED` → `ACTIVE` transition (`POST .../state` with `state: "ACTIVE"` on a
 * deleted device/asset is a 409). Every "Restore" action in the UI sends this target state.
 */
export const RESTORE_TARGET_STATE: SettableLifecycleState = 'DEACTIVATED';

/** Actions a device's row/panel action menu can offer. */
export type DeviceLifecycleAction =
  | 'rename'
  | 'activate'
  | 'deactivate'
  | 'archive'
  | 'restore'
  | 'assign'
  | 'unassign';

/** Actions an asset's action menu can offer. */
export type AssetLifecycleAction = 'rename' | 'activate' | 'deactivate' | 'archive' | 'restore';

/**
 * Which actions a device offers for a given lifecycle state, further narrowed by whether an asset
 * currently owns it. Mirrors the literal per-state matrix docs/main/CYCLES-PLAN.md §8 pins: `ACTIVE`
 * gets the assign/unassign slot (resolved by ownership — assigning an already-owned device makes
 * no sense, nor does unassigning one nobody owns); `DEACTIVATED` does not carry that slot at all
 * (reactivate first); `DELETED` offers only `restore`.
 */
export function availableDeviceActions(
  state: LifecycleState,
  owned: boolean,
): readonly DeviceLifecycleAction[] {
  switch (state) {
    case 'ACTIVE':
      return ['rename', 'deactivate', 'archive', owned ? 'unassign' : 'assign'];
    case 'DEACTIVATED':
      return ['rename', 'activate', 'archive'];
    case 'DELETED':
      return ['restore'];
  }
}

/**
 * Which actions an asset offers for a given lifecycle state — the same three-state shape as
 * devices, minus the assign/unassign slot (that lives on the device side, not the asset itself).
 */
export function availableAssetActions(state: LifecycleState): readonly AssetLifecycleAction[] {
  switch (state) {
    case 'ACTIVE':
      return ['rename', 'deactivate', 'archive'];
    case 'DEACTIVATED':
      return ['rename', 'activate', 'archive'];
    case 'DELETED':
      return ['restore'];
  }
}

/**
 * Builds `PATCH /api/devices/{id}`'s body for the Rename action — omits `name` entirely (rather
 * than sending a `null`/unchanged value) when the trimmed input is blank or equal to what the
 * device already has, so a no-op rename never hits the network.
 */
export function buildDeviceRenameEdit(name: string, original: Pick<Device, 'name'>): DeviceEdit {
  const trimmed = name.trim();
  return trimmed.length > 0 && trimmed !== original.name ? { name: trimmed } : {};
}

/** Form state for the asset rename/re-category card. */
export interface AssetEditForm {
  readonly displayName: string;
  readonly category: string;
}

/**
 * Builds `PATCH /api/assets/{id}`'s body — each of `displayName`/`category` is included only when
 * trimmed and different from the asset's current value; an untouched or blanked-out field is
 * omitted rather than sent as `null` or an empty string, the same `@JsonInclude(NON_NULL)`
 * convention every request builder in this app follows.
 */
export function buildAssetEdit(
  form: AssetEditForm,
  original: Pick<AssetSummary, 'displayName' | 'category'>,
): AssetEdit {
  const edit: { displayName?: string; category?: string } = {};

  const displayName = form.displayName.trim();
  if (displayName.length > 0 && displayName !== original.displayName) {
    edit.displayName = displayName;
  }

  const category = form.category.trim();
  if (category.length > 0 && category !== original.category) {
    edit.category = category;
  }

  return edit;
}

// --- Verb+object action labels (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §1 — one verb dictionary, app-wide) -
// A bare verb ("Archive") never says what it acts on; every kebab-menu entry this app renders
// (Devices' Advanced table, the asset detail page's Hardware section and header) pulls its label
// from here so the two surfaces that share this exact state machine can't drift apart in wording.
// Ellipsis marks an action that opens a form/picker before anything happens (Rename, Assign);
// every other action is a single immediate click, per the plan's own worked examples.

/** Devices' Advanced table + the asset detail page's Hardware section share these verbatim. */
export const DEVICE_ACTION_LABELS: Record<DeviceLifecycleAction, string> = {
  rename: 'Rename device…',
  activate: 'Activate device',
  deactivate: 'Deactivate device',
  archive: 'Archive device',
  restore: 'Restore device',
  assign: 'Assign to asset…',
  unassign: 'Unassign from asset',
};

/** The asset-level lifecycle pair operator-facing surfaces (the Devices asset list, the asset
 *  detail header) actually render — see `operatorAssetActions` below for why only two of these
 *  five ever reach a screen in this cycle. */
export const ASSET_ACTION_LABELS: Record<AssetLifecycleAction, string> = {
  rename: 'Rename asset…',
  activate: 'Activate asset',
  deactivate: 'Deactivate asset',
  archive: 'Archive asset',
  restore: 'Restore asset',
};

// --- Poka-yoke: reasoned action availability (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 3a) -------------
// "Prevention over confirmation": an action that cannot apply right now should render disabled
// *with the reason inline*, never enabled-then-error. `availableDeviceActions`/`availableAssetActions`
// above already encode the one true state matrix (they're what every mutation is actually gated
// against); the functions below don't invent a second set of rules — they enumerate the full
// action universe once and ask that same matrix, per entry, "is this the one you'd have picked?",
// attaching a plain-language reason to every entry it wouldn't have offered.
//
// **One real precondition the plan's own worked example ("Archive disabled with 'Stop the stream
// first' while streaming") does NOT reflect in this domain — verified by reading the backend, not
// assumed**: `DefaultAssetService#setState`/`DefaultDeviceService#setState` (vision-application)
// both stop whatever stream is running themselves, as part of the very same transition, whenever
// the target state isn't `ACTIVE`. Archiving (or deactivating) a live asset/device has never been
// rejected by this backend; it just quietly stops the stream on the way. So Archive is never
// disabled *because of* streaming — only because of the lifecycle state itself (see below).
//
// **One real precondition that *is* worth surfacing ahead of the click**: `DefaultAssetService
// #unassignDevice` refuses (409) to unassign a device that is its owning asset's *only* device
// ("Asset ... must keep at least one device; unassign refused") — today the frontend only learns
// this from the 409 (`FleetStore#unassignDevice`'s own bespoke error text). `ownerDeviceCount`
// below lets a caller that already has the owning asset's device count (every caller here does)
// disable Unassign before the click instead of after.

/** One action's availability for the current context, with the reason attached when it isn't. */
export interface ActionAvailability<TAction extends string> {
  readonly action: TAction;
  readonly available: boolean;
  /** Present only when `available` is `false` — why this action can't be taken right now. */
  readonly reason?: string;
}

const ALREADY_ACTIVE_REASON = 'Already active.';
const ALREADY_DEACTIVATED_REASON = 'Already deactivated.';
const NOT_ARCHIVED_REASON = 'Not archived — nothing to restore.';
const ALREADY_ARCHIVED_REASON = 'Already archived.';
const ARCHIVED_FIRST_REASON = 'Archived — restore it first.';
const REACTIVATE_FIRST_REASON = 'Reactivate the device first.';
const ONLY_DEVICE_REASON =
  "This asset has only one device — assign another before unassigning this one.";

/** Every device lifecycle action's own reason for being unavailable in a given state, independent
 *  of the assign/unassign slot (handled separately below — it's gated by ownership, not state). */
function deviceStateReason(action: DeviceLifecycleAction, state: LifecycleState): string {
  switch (state) {
    case 'ACTIVE':
      // The only two slots `availableDeviceActions('ACTIVE', …)` never includes.
      return action === 'activate' ? ALREADY_ACTIVE_REASON : NOT_ARCHIVED_REASON;
    case 'DEACTIVATED':
      if (action === 'deactivate') {
        return ALREADY_DEACTIVATED_REASON;
      }
      if (action === 'restore') {
        return NOT_ARCHIVED_REASON;
      }
      // assign/unassign: `availableDeviceActions` carries that slot only for ACTIVE devices.
      return REACTIVATE_FIRST_REASON;
    case 'DELETED':
      return action === 'archive' ? ALREADY_ARCHIVED_REASON : ARCHIVED_FIRST_REASON;
  }
}

/** Every possible device action; `assign`/`unassign` are filtered to the one ownership picks. */
const ALL_DEVICE_LIFECYCLE_ACTIONS: readonly DeviceLifecycleAction[] = [
  'rename',
  'activate',
  'deactivate',
  'assign',
  'unassign',
  'archive',
  'restore',
];

/**
 * The full device action menu, reasoned (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 3a) — every action a
 * device could ever offer, each marked available or not, with a reason attached whenever not.
 * Backs the Devices page's Advanced table and the asset detail page's Hardware section, the two
 * surfaces that keep the full, granular lifecycle menu (item 2's "operator-facing" simplification
 * only narrows the *asset*-level menu — see `operatorAssetActions` below).
 *
 * `ownerDeviceCount`, when given and the device is currently owned, disables `unassign` with
 * {@link ONLY_DEVICE_REASON} once it would leave the owning asset with zero devices — the one real
 * backend precondition described in this module's own doc comment, surfaced ahead of the click
 * instead of only after a 409.
 */
export function reasonedDeviceActions(
  state: LifecycleState,
  owned: boolean,
  ownerDeviceCount?: number,
): readonly ActionAvailability<DeviceLifecycleAction>[] {
  const allowed = new Set(availableDeviceActions(state, owned));
  const slots = ALL_DEVICE_LIFECYCLE_ACTIONS.filter((action) => {
    if (action === 'assign') {
      return !owned;
    }
    if (action === 'unassign') {
      return owned;
    }
    return true;
  });

  return slots.map((action) => {
    if (action === 'unassign' && allowed.has('unassign') && ownerDeviceCount === 1) {
      return { action, available: false, reason: ONLY_DEVICE_REASON };
    }
    if (allowed.has(action)) {
      return { action, available: true };
    }
    return { action, available: false, reason: deviceStateReason(action, state) };
  });
}

const ALL_ASSET_LIFECYCLE_ACTIONS: readonly AssetLifecycleAction[] = [
  'rename',
  'activate',
  'deactivate',
  'archive',
  'restore',
];

function assetStateReason(action: AssetLifecycleAction, state: LifecycleState): string {
  switch (state) {
    case 'ACTIVE':
      return action === 'activate' ? ALREADY_ACTIVE_REASON : NOT_ARCHIVED_REASON;
    case 'DEACTIVATED':
      return action === 'deactivate' ? ALREADY_DEACTIVATED_REASON : NOT_ARCHIVED_REASON;
    case 'DELETED':
      return action === 'archive' ? ALREADY_ARCHIVED_REASON : ARCHIVED_FIRST_REASON;
  }
}

/** The full, five-action asset menu, reasoned — same shape as {@link reasonedDeviceActions}. */
export function reasonedAssetActions(
  state: LifecycleState,
): readonly ActionAvailability<AssetLifecycleAction>[] {
  const allowed = new Set(availableAssetActions(state));
  return ALL_ASSET_LIFECYCLE_ACTIONS.map((action) =>
    allowed.has(action)
      ? { action, available: true }
      : { action, available: false, reason: assetStateReason(action, state) },
  );
}

/**
 * The asset-level lifecycle slice operator-facing surfaces actually render (docs/plans/done/UX-REWORK-PLAN.md
 * §U-a2 item 2 — "pick ONE lifecycle verb pair… surface it as Archive + Restore only, keep the
 * finer states in Advanced"). Narrows {@link reasonedAssetActions} to just `archive`/`restore`;
 * `activate`/`deactivate` don't retreat into an "Advanced" asset table the way device actions do
 * (this codebase has no such table — only the raw *devices* table exists), so this cycle simply
 * stops rendering them anywhere in scope, a deliberate, documented loss of UI surface for those two
 * states, not a backend removal (still reachable via the API/debug console).
 */
export function operatorAssetActions(
  state: LifecycleState,
): readonly ActionAvailability<Extract<AssetLifecycleAction, 'archive' | 'restore'>>[] {
  return reasonedAssetActions(state).filter(
    (entry): entry is ActionAvailability<Extract<AssetLifecycleAction, 'archive' | 'restore'>> =>
      entry.action === 'archive' || entry.action === 'restore',
  );
}
