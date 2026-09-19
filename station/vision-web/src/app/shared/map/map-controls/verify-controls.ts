import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { MarksFacade } from '../../../core/map-data/marks-facade';
import { LayersFacade } from '../../../core/map-data/layers-facade';
import { verificationChipClass, verificationLabel } from '../../../core/map-data/mark-logic';
import type { MapMark } from '../../../core/api/models';

/**
 * `<vision-verify-controls>` — DELTA's verify → confirm → share-wider flow, as one small strip
 * (docs/plans/done/MAP-REWORK-PLAN.md §5.2). Shared by the Fly and Command marks panels.
 *
 * **Always shows the verification chip; only a manager sees the verbs.** The chip is information
 * every viewer needs — an UNVERIFIED pin is a claim, not a fact, and the map draws it dashed for the
 * same reason — so it renders for everyone, alongside the mark's layer name (an em dash while the
 * layer list is still loading or the layer is unknown, never a raw uuid and never a guess).
 * Confirm / Reject / **Promote to common picture** render only when `LayersFacade.canManageLayer`
 * says the server resolved MANAGE on this mark's layer (§3's own rule for `verify` and `promote`).
 * That is a *hiding* rule, not an authorization one: every action still goes to the server, and a
 * 403/404 surfaces as a toast through `MarksFacade`'s shared error seam.
 *
 * **Promote hides once the mark is already on the common picture** — there is nowhere left to
 * promote it to, and a button that would no-op is worse than no button. Confirm/Reject stay
 * available on an already-decided mark (a manager may overturn an earlier call), but the button
 * matching the current state is disabled rather than removed, so the strip's shape never jumps.
 */
@Component({
  selector: 'vision-verify-controls',
  templateUrl: './verify-controls.html',
  styleUrl: './verify-controls.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VerifyControls {
  readonly mark = input.required<MapMark>();

  private readonly marks = inject(MarksFacade);
  private readonly layers = inject(LayersFacade);

  constructor() {
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 — see `MarksPanel`'s identical constructor comment. One
    // instance exists per unverified mark row, so this fires once per row mounted; ref-counting on
    // the store side means that is exactly as correct (and as cheap) as one activation per row.
    this.marks.activate();
    this.layers.activate();
    inject(DestroyRef).onDestroy(() => {
      this.marks.release();
      this.layers.release();
    });
  }

  protected readonly verificationLabel = verificationLabel;
  protected readonly verificationChipClass = verificationChipClass;

  protected readonly busy = signal(false);

  /** The mark's layer name, or `undefined` → the template renders an em dash rather than a uuid. */
  protected readonly layerName = computed(() => this.layers.nameOf(this.mark().layerId));

  /** Server-resolved MANAGE on this mark's layer — gates every verb below. */
  protected readonly canManage = computed(() => this.layers.canManageLayer(this.mark().layerId));

  protected readonly onCop = computed(() => this.layers.isCop(this.mark().layerId));

  protected readonly canPromote = computed(() => this.canManage() && !this.onCop());

  protected async confirm(): Promise<void> {
    await this.run(() => this.marks.verify(this.mark().markId, 'CONFIRMED'));
  }

  protected async reject(): Promise<void> {
    await this.run(() => this.marks.verify(this.mark().markId, 'REJECTED'));
  }

  protected async promote(): Promise<void> {
    await this.run(() => this.marks.promote(this.mark().markId));
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } finally {
      this.busy.set(false);
    }
  }
}
