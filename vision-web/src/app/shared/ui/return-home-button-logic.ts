import type { ReturnHomeResult } from '../../core/api/models';

export type ReturnHomeToastKind = 'ok' | 'warning';

export interface ReturnHomeToast {
  readonly kind: ReturnHomeToastKind;
  readonly text: string;
}

/**
 * Pure "which toast for which outcome" decision behind `shared/ui/return-home-button.ts`
 * (docs/DRONE-INFRA-PLAN.md I-e Stage 1's frozen contract: "result toast — ACCEPTED green / NO_ACK
 * amber 'sent, no acknowledgement'") — split out so it's unit-testable without Angular/HTTP,
 * mirroring this app's own `*-logic.ts` convention (e.g. `core/geofence/geofence-logic.ts#geofenceBreachToastMessage`).
 * Covers only the two success-shaped outcomes the backend's `202` can return — a `404`/`409`/network
 * failure never reaches this function at all, those go through the existing `describeHttpError`
 * (`core/api-error.ts`) at the call site instead.
 */
export function returnHomeToastFor(result: ReturnHomeResult): ReturnHomeToast {
  return result === 'ACCEPTED'
    ? { kind: 'ok', text: 'Return home commanded' }
    : { kind: 'warning', text: 'Command sent — no acknowledgement from aircraft' };
}
