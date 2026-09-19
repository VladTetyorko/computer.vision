import { createActionGroup, props } from '@ngrx/store';
import type {
  ControlCatalog,
  ControlProfile,
  CreateControlProfileRequest,
  UpdateControlProfileRequest,
} from '../../api/models';

export const ControlProfilePageActions = createActionGroup({
  source: 'Control Profile Page',
  events: {
    'Load Requested': props<{ force: boolean }>(),
    /** Starts a copy of the built-in for a vehicle kind. */
    'Create Requested': props<{ request: CreateControlProfileRequest }>(),
    /** Replaces one saved layout wholesale — the endpoint takes no partial write. */
    'Update Requested': props<{ id: string; request: UpdateControlProfileRequest }>(),
    /** Makes one layout the one a session on its vehicle kind engages with. */
    'Activate Requested': props<{ id: string }>(),
    'Delete Requested': props<{ id: string }>(),
  },
});

/** Every `*Succeeded` here carries the freshly re-read `profiles` list — `ControlProfileStore`'s own
 *  "writes reload rather than patch" rule (activation in particular is a server-side move that
 *  deactivates a sibling profile, so a locally-patched list would be a guess a real re-read avoids). */
export const ControlProfileApiActions = createActionGroup({
  source: 'Control Profile API',
  events: {
    'Load Succeeded': props<{ profiles: readonly ControlProfile[]; catalog: ControlCatalog }>(),
    'Load Failed': props<{ error: string }>(),
    'Create Succeeded': props<{ profile: ControlProfile; profiles: readonly ControlProfile[] }>(),
    'Create Failed': props<{ error: string }>(),
    'Update Succeeded': props<{ profile: ControlProfile; profiles: readonly ControlProfile[] }>(),
    'Update Failed': props<{ error: string }>(),
    'Activate Succeeded': props<{ profiles: readonly ControlProfile[] }>(),
    'Activate Failed': props<{ error: string }>(),
    'Delete Succeeded': props<{ profiles: readonly ControlProfile[] }>(),
    'Delete Failed': props<{ error: string }>(),
  },
});
