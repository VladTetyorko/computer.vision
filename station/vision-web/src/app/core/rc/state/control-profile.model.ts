import type { ControlCatalog, ControlProfile } from '../../api/models';

/**
 * The operator's controller layouts and the catalogue they're built from
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C6/C8) — migrated off
 * `ControlProfileStore` per docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7.
 *
 * Two consumers, one load: `/manage/controller`'s setup page edits these layouts, the Fly cockpit's
 * Controller drawer reads the active one so a bound switch can fire without engaging a session
 * (decision C3). Both read the same slice, so a drawer opening after the setup page has already
 * loaded costs nothing (`ControlProfileFacade#load`'s own idempotency guard).
 */
export interface ControlProfileState {
  /** Every layout this operator has, saved and built-in, newest saved first (server order). */
  readonly profiles: readonly ControlProfile[];
  /** `undefined` until the first successful load — every picker that reads it renders empty, not wrong. */
  readonly catalog: ControlCatalog | undefined;
  readonly loading: boolean;
  /** Whether a load has ever completed — distinguishes "no profiles" from "not asked yet". */
  readonly loaded: boolean;
}

export const initialControlProfileState: ControlProfileState = {
  profiles: [],
  catalog: undefined,
  loading: false,
  loaded: false,
};
