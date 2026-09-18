import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryFacade } from '../../core/telemetry/telemetry-facade';
import { LinksStore } from '../../core/pairing/links-store';
import { UiStore } from '../../core/ui/ui-store';
import { relativeTimeLabel } from '../../core/events/events-logic';
import { ageSeconds, isStale } from '../../core/telemetry/telemetry-logic';
import {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  reasonedDeviceActions,
  type ActionAvailability,
  type DeviceLifecycleAction,
} from '../../core/fleet/warehouse-logic';
import { formatDuration } from '../../core/stream-info-logic';
import { usageDurationSeconds, type FlightBar } from '../../core/fleet/asset-stats-logic';
import { TacticalMap } from '../../shared/map/tactical-map/tactical-map';
import { MapTools, type MapToolsCapabilities } from '../../shared/map/map-controls/map-tools/map-tools';
import { SectionHeader } from '../../shared/ui/section-header';
import { SidePanel } from '../../shared/ui/side-panel';
import { Icon } from '../../shared/ui/icon';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { Stat } from '../../shared/ui/stat';
import { PageBar, type PageBarCrumb } from '../../shared/ui/page-bar/page-bar';
import { pluralize } from '../../shared/ui/text-logic';
import { PilotsCard } from './pilots-card';
import { CameraPosePanel } from '../camera-geo/camera-pose-panel';
import { AssetDetailFacade } from './asset-detail-facade';
import {
  attributeRowsToRecord,
  attributesToRows,
  roleStatusDescriptor,
  sampleAgeLabel,
  telemetryFactRows,
  type AttributeRow,
  type RoleStatusDescriptor,
  type TelemetryFactRow,
} from './asset-detail-logic';
import type { AssetUsage, Device, DetectionEvent } from '../../core/api/models';

/** The page's four independent editor overlays — docs/plans/done/UI-ARCHITECTURE-PLAN.md's own migration
 *  target for this page — consolidated into ONE mutually-exclusive `UiStore` group, mirroring
 *  `features/fly/flight-command-panel.ts`'s `dialog` group: opening any one implicitly closes
 *  whichever other was open, so this page can never show two editors at once. Transient (no
 *  `storageKey`) — an editor must never survive a reload. `'registration'` renamed to `'identity'`
 *  this wave (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4) — the single-field editor became a
 *  four-field `AssetIdentity` group. `'linkAddress'` added by docs/plans/active/LINK-PAIRING-PLAN.md
 *  §3.4/§3.7, wave L4 — the Links panel's "Fix address" inline form; it edits a device's `uri`, not
 *  an asset field, but the architecture guard (`core/ui/architecture.spec.ts`) requires every overlay
 *  flag on a routed page to live in a `UiStore` group rather than a bare signal, and this page's other
 *  `panels`/`dialogs` groups are typed for drawer-ids/confirm-ids respectively — `editors` is the
 *  closest-fitting existing group for "one inline form open at a time". */
type AssetEditor = 'asset' | 'identity' | 'attributes' | 'assign' | 'linkAddress';

/** The Identity editor's own draft shape — one combined form for all four `AssetIdentity` fields, replacing the old single `registrationNumberDraft` string. */
type IdentityDraft = { serialNumber: string; make: string; model: string; registration: string };

/**
 * The asset **manager** page (`/assets/:id`, docs/main/CYCLES-PLAN.md §11, CD-b item 2 — reworked from a
 * hybrid manager/cockpit page into a pure manager view by docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave B).
 * **No video, ever, on this page** — piloting and live video are the cockpit's job (`/fly`) and the
 * lightweight `/live/:deviceId` watch page, both one click away via the cockpit-link band.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md**: every store/service injection, derived read-model,
 * and HTTP-backed command lives in {@link AssetDetailFacade} (provided below, alongside
 * `TelemetryStore` — unchanged, still one poller-set per route activation). This component is left
 * holding only:
 *   - the route-bound `assetId` input (only a component can receive one) and the single constructor
 *     `effect()` that forwards it to `facade.load()` and resets this page's own local overlay state;
 *   - the overlay/view-state a `UiStore`/`PanelState` group or a drill-in `subView` toggle is
 *     explicitly meant to be **host-owned**, per those classes' own doc comments ("a host owns one
 *     instance directly") — `editors` (the four-editor group), `panels` (the three/four drawers),
 *     `dialogs` (the Archive-asset confirm, added by docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2 wave 2 — see
 *     this class's own `requestArchiveAsset` doc comment), `subView` (the two wide-table sub-views);
 *   - truly-ephemeral local view state no other component/route transition needs to stay consistent
 *     with: form drafts (`nameDraft`/`categoryDraft`/`identityDraft`/`renameDraft`/
 *     `assignDraft`/`attributeRows`) and the "which inline row is open" pointer (`rowAction`);
 *   - a handful of pure, stateless template helpers (label/format mappers, `@for`-bound per-row
 *     readers of a facade signal — the same "plain method reading a signal, called from `@for`"
 *     idiom `features/devices/devices.ts#simulatedInfo` already established).
 *
 * **Header** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/05-asset-detail.md): the two
 * `.page-head` blocks this page used to carry (a skeleton title while loading, the real title +
 * chips + actions once loaded) are now one `<vision-page-bar>`, shared across both states via
 * `headerTitle`. `crumb` is a fixed `{ label: 'Assets', to: '/assets' }` (05-asset-detail.md's own
 * mockup) — back to the list, not to a parent entity. The former inline "swap the `<h1>` for text
 * inputs" rename affordance is gone: `<vision-page-bar>`'s `title` is a plain required `string`
 * input (a frozen contract this task must not modify), so it cannot host projected `<input>`
 * elements — the rename form now renders as its own card directly under the bar instead, still
 * gated by the same `editors` group. The old decorative asset photo (`hasImage`/`imageUrl`,
 * docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) is dropped from this header for the same reason: it has no
 * slot in the frozen bar and 05-asset-detail.md's own mockup shows no avatar — the underlying
 * `AssetDetailFacade`/`VisionApi` capability is untouched, only this page's presentation of it.
 */
@Component({
  selector: 'vision-asset-detail',
  imports: [
    RouterLink,
    TacticalMap,
    PilotsCard,
    CameraPosePanel,
    SectionHeader,
    SidePanel,
    Icon,
    KebabMenu,
    ConfirmDialog,
    EmptyState,
    Notice,
    Stat,
    PageBar,
    MapTools,
  ],
  templateUrl: './asset-detail.html',
  styleUrl: './asset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AssetDetailFacade, TelemetryFacade, LinksStore],
})
export class AssetDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly assetId = input.required<string>();

  protected readonly facade = inject(AssetDetailFacade);

  /** Fixed back-link for `<vision-page-bar>`'s `crumb` — see this class's own "Header" doc note. */
  protected readonly crumb: PageBarCrumb = { label: 'Assets', to: '/assets' };

  /** Regular pluralisation for the "Usage history" drill-in's own count subtitle — see
   *  `shared/ui/page-bar/page-bar.ts`'s own doc comment for why this is one shared helper. */
  protected readonly pluralize = pluralize;

  /** `<vision-page-bar>`'s `title` while the asset itself hasn't loaded yet (no name to show). */
  protected readonly headerTitle = computed(() => this.facade.asset()?.displayName ?? 'Asset');

  /**
   * The asset photo (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3), fed to `<vision-page-bar>`'s `avatarSrc`.
   * Restored after the Wave 2 header migration dropped it: the photo was never part of the bar's
   * plain-string `title`, so that contract never required removing it — it only needed a home, which
   * `avatarSrc` now is. The graceful 404 (an asset with no uploaded photo) lives inside `PageBar`
   * itself, so this page no longer carries its own `imageLoadFailed` flag nor the navigation-time
   * reset that flag required.
   */
  protected readonly assetImageSrc = computed(() => {
    const asset = this.facade.asset();
    return asset?.hasImage ? this.facade.imageUrl(asset.assetId) : null;
  });

  /**
   * Sense/Sight role-status chips (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md P3), shown in the
   * cockpit-band header and again next to the "Hardware & devices" subview's own section header —
   * both read {@link AssetDetailFacade.senseStatus}/`sightStatus` through the same descriptor mapping
   * so the two spots can never disagree. `roleStatusDescriptor` returning `{ kind: 'muted' }` for
   * `not-fitted` is this page's own cue to render no chip at all (see the template).
   */
  protected readonly senseChip = computed<RoleStatusDescriptor>(() => roleStatusDescriptor(this.facade.senseStatus()));
  protected readonly sightChip = computed<RoleStatusDescriptor>(() => roleStatusDescriptor(this.facade.sightStatus()));

  // --- Overlay/view state — host-owned, see this class's own doc comment above ------------------

  private readonly editors = new UiStore();
  protected isEditorOpen(id: AssetEditor): boolean {
    return this.editors.isOpen(id);
  }

  protected readonly panels = new UiStore();
  protected readonly subView = signal<'overview' | 'usage' | 'hardware'>('overview');

  /** The Position card's own map inset instance, for the Map tools drawer's Layers section (docs/
   * conclusions/MAP-UX-RESEARCH.md M1) — mirrors `command.ts#tacticalMap`'s identical doc comment. */
  protected readonly tacticalMap = viewChild(TacticalMap);

  /** This inset's one HUD door (docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.2, D9) — read-write now:
   * `layers: 'view'` (administration stays `/command`-only), no `cockpit` (this page flies nothing). */
  protected readonly mapToolsCapabilities: MapToolsCapabilities = { marks: true, layers: 'view', draw: false, zones: true };

  /**
   * The Archive-asset confirm's own one-member `UiStore` group (docs/extracts/design/05-asset-detail.md's
   * own acceptance criterion — "Archive asset requires a confirm and is not adjacent to Rename").
   * A separate group from `editors`/`panels` so a confirm can never coexist with either — mirrors
   * `features/fly/fly.ts`'s identical single-member `dialog` group for its Stop-stream confirm.
   *
   * Widened to `'forget-pairing'` this wave (docs/plans/active/LINK-PAIRING-PLAN.md §3.4/§3.7, wave L4)
   * — the Links panel's own hard-delete confirm (`DELETE /api/devices/{id}/pairing`), same "confirm,
   * not undo" idiom as Archive since forgetting a pairing is irreversible.
   */
  private readonly dialogs = new UiStore();
  protected isDialogOpen(id: 'archive' | 'forget-pairing'): boolean {
    return this.dialogs.isOpen(id);
  }

  constructor() {
    // The only effect left on this component: forwards the route-bound `assetId` input to the
    // facade and resets this page's own local overlay/view state — a fresh navigation to a
    // *different* asset shouldn't stay parked in the previous one's editor/drill-in (Angular reuses
    // this component instance across same-route navigations rather than recreating it).
    //
    // **`rowAction`/`renameDraft`/`assignDraft` reset too** (docs/plans/done/UI-STATE-PLAN.md §2 — a stale value
    // surviving into a context where it's wrong), added alongside the pre-existing `panels`/`editors`/
    // `dialogs` resets above: without this, switching assets (the header switcher, a picker-card pick,
    // or a bare `?sel=`-adjacent link — all same-route navigations, same component instance) left a
    // *different* asset's device still "mid-rename" (`rowAction`) and the Assign-device picker's own
    // stale selection (`assignDraft`) sitting behind a freshly-closed editor. `assignDraft` in
    // particular isn't cosmetic: `openAssign()` never re-blanks it (`assignableDevices` is the global
    // unowned-device pool, not asset-scoped), so a device picked but never confirmed on asset A could
    // reappear silently pre-selected the next time Assign is opened, on asset A or B alike.
    effect(() => {
      const id = this.assetId();
      this.subView.set('overview');
      this.panels.close();
      this.editors.close();
      this.dialogs.close();
      this.rowAction.set(null);
      this.renameDraft.set('');
      this.assignDraft.set('');
      this.linkAddressDraft.set('');
      this.facade.load(id);
    });
  }

  protected relativeTime(event: DetectionEvent): string {
    return relativeTimeLabel(event.lastSeen, this.facade.now());
  }

  // --- Drill-in navigation (docs/plans/done/UI-REDESIGN-PLAN.md Wave 3) ---------------------------------

  protected openDrillIn(view: 'usage' | 'hardware'): void {
    this.subView.set(view);
  }

  protected closeDrillIn(): void {
    this.subView.set('overview');
  }

  // --- Per-device telemetry panels (called from the template) --------------------------------

  protected deviceFacts(deviceId: string): readonly TelemetryFactRow[] {
    return telemetryFactRows(this.facade.latestSampleFor(deviceId));
  }

  protected deviceSampleAgeSeconds(deviceId: string): number | undefined {
    return ageSeconds(this.facade.latestSampleFor(deviceId)?.at, this.facade.now());
  }

  protected deviceStale(deviceId: string): boolean {
    return isStale(this.deviceSampleAgeSeconds(deviceId));
  }

  /** `<humanAge> ago` for the freshest-sample summary card's own "Sample" fact — docs/plans/active/
   *  OPERATOR-UX-4-PLAN.md finding N4, one age vocabulary. */
  protected freshestAgeLabel(): string {
    return sampleAgeLabel(this.facade.freshestAgeSeconds());
  }

  /** Same as {@link freshestAgeLabel}, per device, for the "Full telemetry" drawer's own rows. */
  protected deviceAgeLabel(deviceId: string): string {
    return sampleAgeLabel(this.deviceSampleAgeSeconds(deviceId));
  }

  protected usageDuration(usage: AssetUsage): string {
    return formatDuration(usageDurationSeconds(usage, this.facade.now()));
  }

  protected barLabel(bar: FlightBar): string {
    const when = new Date(bar.startedAt).toLocaleString();
    const duration = formatDuration(bar.durationSeconds);
    return bar.open ? `${when} · ${duration} so far · in progress` : `${when} · ${duration}`;
  }

  protected detailPairs(attributes: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(attributes).map(([key, value]) => ({ key, value }));
  }

  // --- Asset header: rename/category edit + lifecycle -----------------------------------------

  protected readonly nameDraft = signal('');
  protected readonly categoryDraft = signal('');

  protected openEditAsset(): void {
    const asset = this.facade.asset();
    if (!asset) {
      return;
    }
    this.nameDraft.set(asset.displayName);
    this.categoryDraft.set(asset.category);
    this.editors.open('asset');
  }

  protected cancelEditAsset(): void {
    this.editors.close('asset');
  }

  protected async confirmEditAsset(): Promise<void> {
    const ok = await this.facade.saveAssetEdit(this.nameDraft(), this.categoryDraft());
    if (ok) {
      this.editors.close('asset');
    }
  }

  protected renameAsset(): void {
    this.openEditAsset();
  }

  /**
   * Opens the Archive-asset confirm (docs/extracts/design/05-asset-detail.md's own acceptance criterion —
   * Archive requires a confirm and must not sit adjacent to Rename). The button that calls this
   * lives inside `<vision-kebab-menu>` in the page bar's `pageBarActions` slot (asset-detail.html),
   * not next to "Rename asset…" — the poka-yoke separation this class doc references.
   */
  protected requestArchiveAsset(): void {
    this.dialogs.open('archive');
  }

  protected cancelArchiveAsset(): void {
    this.dialogs.close('archive');
  }

  protected async confirmArchiveAsset(): Promise<void> {
    await this.facade.archiveAssetNow();
    this.dialogs.close('archive');
  }

  /** The header's one non-destructive lifecycle action — "Restore asset" only; Archive is
   *  {@link requestArchiveAsset}'s confirm flow above, not this direct-fire path. */
  protected restoreAsset(): void {
    void this.facade.restoreAssetNow();
  }

  // --- Identity editor (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4, wave W4) — Serial/Make/Model/
  //     Registration, one combined form, PATCH'd via `identity` (replaces the old single-field
  //     Registration-only editor). ------------------------------------------------------------

  protected readonly identityDraft = signal<IdentityDraft>({ serialNumber: '', make: '', model: '', registration: '' });

  protected openEditIdentity(): void {
    const identity = this.facade.identity();
    this.identityDraft.set({
      serialNumber: identity?.serialNumber ?? '',
      make: identity?.make ?? '',
      model: identity?.model ?? '',
      // Seeds from the effective (fallback-aware) value — editing and saving migrates a
      // legacy-attribute-only asset onto `identity.registration` for free, the first write after
      // this wave ships fixes the gap for that asset going forward.
      registration: identity?.registration ?? this.facade.effectiveRegistration() ?? '',
    });
    this.editors.open('identity');
  }

  protected setIdentityDraftField(field: keyof IdentityDraft, value: string): void {
    this.identityDraft.update((draft) => ({ ...draft, [field]: value }));
  }

  protected cancelEditIdentity(): void {
    this.editors.close('identity');
  }

  protected async confirmEditIdentity(): Promise<void> {
    const draft = this.identityDraft();
    const ok = await this.facade.saveIdentity(draft.serialNumber, draft.make, draft.model, draft.registration);
    if (ok) {
      this.editors.close('identity');
    }
  }

  // --- Attributes editor (advanced-mode key/value rows) ----------------------------------------

  protected readonly attributeRows = signal<readonly AttributeRow[]>([]);
  protected readonly attributesSubmitting = signal(false);

  protected openAttributesEditor(): void {
    this.attributeRows.set(attributesToRows(this.facade.asset()?.attributes ?? {}));
    this.editors.open('attributes');
  }

  protected cancelAttributesEditor(): void {
    this.editors.close('attributes');
  }

  protected addAttributeRow(): void {
    this.attributeRows.update((rows) => [...rows, { key: '', value: '' }]);
  }

  protected removeAttributeRow(index: number): void {
    this.attributeRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected updateAttributeKey(index: number, key: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, key } : row)));
  }

  protected updateAttributeValue(index: number, value: string): void {
    this.attributeRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, value } : row)));
  }

  protected async confirmAttributesEditor(): Promise<void> {
    this.attributesSubmitting.set(true);
    try {
      const ok = await this.facade.saveAttributes(attributeRowsToRecord(this.attributeRows()));
      if (ok) {
        this.editors.close('attributes');
      }
    } finally {
      this.attributesSubmitting.set(false);
    }
  }

  // --- Hardware section: per-device inline actions + attach-a-device ------------------------

  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' } | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  protected deviceActionsFor(device: Device): readonly ActionAvailability<DeviceLifecycleAction>[] {
    const ownerDeviceCount = this.facade.asset()?.devices.length;
    return reasonedDeviceActions(device.state, true, ownerDeviceCount).filter((entry) => entry.action !== 'assign');
  }

  protected deviceActionLabel(action: DeviceLifecycleAction): string {
    return DEVICE_ACTION_LABELS[action];
  }

  protected onDeviceAction(device: Device, action: DeviceLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.rowAction.set({ deviceId: device.id, mode: 'rename' });
        this.renameDraft.set(device.name);
        break;
      case 'activate':
        void this.facade.setDeviceLifecycle(device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.facade.deactivateDeviceNow(device);
        break;
      case 'archive':
        void this.facade.archiveDeviceNow(device);
        break;
      case 'restore':
        void this.facade.setDeviceLifecycle(device, RESTORE_TARGET_STATE);
        break;
      case 'unassign':
        void this.facade.unassignDevice(device);
        break;
      case 'assign':
        break; // never offered — see deviceActionsFor
    }
  }

  protected rowActionMode(deviceId: string): 'rename' | null {
    const active = this.rowAction();
    return active && active.deviceId === deviceId ? active.mode : null;
  }

  protected cancelRowAction(): void {
    this.rowAction.set(null);
  }

  protected async confirmRename(device: Device): Promise<void> {
    const ok = await this.facade.renameDevice(device, this.renameDraft());
    if (ok) {
      this.rowAction.set(null);
    }
  }

  /** Attaching an existing, unowned device — resolved on demand, not kept warm on every page load. */
  protected async openAssign(): Promise<void> {
    this.editors.open('assign');
    await this.facade.loadAssignableDevices();
  }

  protected cancelAssign(): void {
    this.editors.close('assign');
    this.assignDraft.set('');
  }

  protected async confirmAssign(): Promise<void> {
    const deviceId = this.assignDraft();
    if (!deviceId) {
      return;
    }
    const ok = await this.facade.assignDevice(deviceId);
    if (ok) {
      this.cancelAssign();
    }
  }

  // --- Links panel (docs/plans/active/LINK-PAIRING-PLAN.md §3.4/§3.7, wave L4) --------------------
  // Every derived read-model and HTTP-backed command lives on `AssetDetailFacade` (`links`/
  // `linksSorted`/`pinLink`/`replaceLinkHardware`/etc. — see that class's own "Links panel" section);
  // this component owns only the panel-local overlay state (the Fix-address inline form's open flag
  // rides the `editors` group as `'linkAddress'` — see that type's own doc comment for why — with a
  // plain draft signal alongside it, same "group flag + plain draft" split every other editor above
  // uses) and the one pure template helper (`failoverLinkLabel`) that isn't testable-in-isolation
  // logic worth its own `*-logic.ts` file — it just resolves an id against the same
  // `facade.linksSorted()` the template already reads.

  protected readonly linkAddressDraft = signal('');

  protected openFixLinkAddress(): void {
    this.linkAddressDraft.set(this.facade.pairingDevice()?.uri ?? '');
    this.editors.open('linkAddress');
  }

  protected cancelFixLinkAddress(): void {
    this.editors.close('linkAddress');
    this.linkAddressDraft.set('');
  }

  protected async confirmFixLinkAddress(): Promise<void> {
    const ok = await this.facade.fixLinkAddress(this.linkAddressDraft());
    if (ok) {
      this.cancelFixLinkAddress();
    }
  }

  /**
   * Opens the Forget-pairing confirm (`dialogs` group — see that field's own doc comment for why
   * this is a genuine confirm dialog, not the app's usual undo-toast idiom).
   */
  protected requestForgetLinkPairing(): void {
    this.dialogs.open('forget-pairing');
  }

  protected cancelForgetLinkPairing(): void {
    this.dialogs.close('forget-pairing');
  }

  protected async confirmForgetLinkPairing(): Promise<void> {
    await this.facade.forgetLinkPairing();
    this.dialogs.close('forget-pairing');
  }

  /** "12s ago" for one failover row's `at` timestamp — shares `relativeTimeLabel` with `relativeTime`
   *  above; that helper takes a `DetectionEvent`, this one a raw ISO string, so it can't be reused
   *  as-is, but both ultimately call the same `humanAge`-backed formatter. */
  protected failoverTime(atIso: string): string {
    return relativeTimeLabel(atIso, this.facade.now());
  }

  /** Resolves a `LinkFailoverRow`'s bare `fromLinkId`/`toLinkId` to that link's current display
   *  label, off `facade.linksSorted()` — falls back to the raw id (still better than nothing) for a
   *  link that has since rotated out of the group (e.g. after a hardware replace), and to "—" when
   *  the event carried no id at all (an older/malformed event — `parseLinkFailover`'s own degrade). */
  protected failoverLinkLabel(linkId: string | undefined): string {
    if (!linkId) {
      return '—';
    }
    const link = this.facade.linksSorted().find((candidate) => candidate.id === linkId);
    return link ? `${this.facade.linkKind(link)} · ${link.label}` : linkId;
  }
}
