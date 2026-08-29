import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelemetryStore } from '../../core/telemetry/telemetry-store';
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
import { SectionHeader } from '../../shared/ui/section-header';
import { SidePanel } from '../../shared/ui/side-panel';
import { Icon } from '../../shared/ui/icon';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { Stat } from '../../shared/ui/stat';
import { PageBar, type PageBarCrumb } from '../../shared/ui/page-bar/page-bar';
import { pluralize } from '../../shared/ui/text-logic';
import { PilotsCard } from './pilots-card';
import { CameraPosePanel } from '../camera-geo/camera-pose-panel';
import { AssetDetailFacade } from './asset-detail-facade';
import {
  attributeRowsToRecord,
  attributesToRows,
  sampleAgeLabel,
  telemetryFactRows,
  type AttributeRow,
  type TelemetryFactRow,
} from './asset-detail-logic';
import type { AssetUsage, Device, DetectionEvent } from '../../core/api/models';

/** The page's four independent editor overlays — docs/plans/done/UI-ARCHITECTURE-PLAN.md's own migration
 *  target for this page — consolidated into ONE mutually-exclusive `UiStore` group, mirroring
 *  `features/fly/flight-command-panel.ts`'s `dialog` group: opening any one implicitly closes
 *  whichever other was open, so this page can never show two editors at once. Transient (no
 *  `storageKey`) — an editor must never survive a reload. */
type AssetEditor = 'asset' | 'registration' | 'attributes' | 'assign';

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
 *     with: form drafts (`nameDraft`/`categoryDraft`/`registrationNumberDraft`/`renameDraft`/
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
  imports: [RouterLink, TacticalMap, PilotsCard, CameraPosePanel, SectionHeader, SidePanel, Icon, KebabMenu, ConfirmDialog, EmptyState, Stat, PageBar],
  templateUrl: './asset-detail.html',
  styleUrl: './asset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AssetDetailFacade, TelemetryStore],
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

  // --- Overlay/view state — host-owned, see this class's own doc comment above ------------------

  private readonly editors = new UiStore();
  protected isEditorOpen(id: AssetEditor): boolean {
    return this.editors.isOpen(id);
  }

  protected readonly panels = new UiStore();
  protected readonly subView = signal<'overview' | 'usage' | 'hardware'>('overview');

  /**
   * The Archive-asset confirm's own one-member `UiStore` group (docs/extracts/design/05-asset-detail.md's
   * own acceptance criterion — "Archive asset requires a confirm and is not adjacent to Rename").
   * A separate group from `editors`/`panels` so a confirm can never coexist with either — mirrors
   * `features/fly/fly.ts`'s identical single-member `dialog` group for its Stop-stream confirm.
   */
  private readonly dialogs = new UiStore();
  protected isDialogOpen(id: 'archive'): boolean {
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

  // --- Registration/tail number editor --------------------------------------------------------

  protected readonly registrationNumberDraft = signal('');

  protected openEditRegistrationNumber(): void {
    this.registrationNumberDraft.set(this.facade.registrationNumber() ?? '');
    this.editors.open('registration');
  }

  protected cancelEditRegistrationNumber(): void {
    this.editors.close('registration');
  }

  protected async confirmEditRegistrationNumber(): Promise<void> {
    const ok = await this.facade.saveRegistrationNumber(this.registrationNumberDraft());
    if (ok) {
      this.editors.close('registration');
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
}
