import type { FlightCapability, FlightCommandResult } from '../../core/api/models';
import { canCommandReturnHome } from '../../core/telemetry/flight-state-logic';

/**
 * Pure, Angular-free logic behind `flight-command-panel.ts`/`arm-confirm-dialog.ts`
 * (docs/DRONE-INFRA-PLAN.md I-e Stage 2 — the Fly cockpit's Arm/Disarm/Mode panel, extending Stage
 * 1's "Bring home") — visibility gating, confirm copy, and outcome-toast mapping, split out so
 * every rule is unit-testable without Angular/HTTP, mirroring `shared/ui/return-home-button-logic.ts`'s
 * own split (this cycle's direct precedent) and `core/telemetry/flight-state-logic.ts`'s wider
 * convention.
 *
 * **Single consumer today** (only `FlyPage`, per the plan's own frozen "UI — Wave C" scope — Command's
 * `AssetPanel` is not part of this wave) — lives beside its one component under `features/fly/`
 * rather than `core/telemetry/` or `shared/ui/`, per this codebase's own "second consumer moves it to
 * a shared home" precedent (`core/fleet/device-logic.ts`'s doc comment names the original case this
 * follows). Promote it if/when a second consumer needs it, not before.
 */

// --- Panel visibility (docs/DRONE-INFRA-PLAN.md I-e Stage 2's frozen contract) ------------------

/**
 * Whether the flight-command panel may render *anything* for the currently-flown asset:
 * `capabilities.commandable` **and** Stage 1's own firmware+freshness gate
 * (`core/telemetry/flight-state-logic.ts#canCommandReturnHome`) — the identical bar "Bring home"
 * already clears, extended with the capability read Stage 2 adds. `capabilities` is `undefined`
 * whenever the `GET .../flight-capabilities` call hasn't resolved yet or failed outright (`FlyPage`
 * degrades to `undefined` on either, per the plan's own "degrade to hidden if the capabilities call
 * fails" instruction) — that alone hides the panel, with no separate "loading" state to model.
 *
 * Individual controls inside the panel gate further, per-capability (`armSupported`/
 * `modeSelectSupported`/a non-empty `selectableModes`) — this function only answers "is there
 * anything at all worth showing", not "which controls".
 */
export function canShowCommandPanel(
  capabilities: FlightCapability | undefined,
  firmware: string | undefined,
  ageSeconds: number | undefined,
): boolean {
  return capabilities !== undefined && capabilities.commandable && canCommandReturnHome(firmware, ageSeconds);
}

// --- Confirm copy (docs/DRONE-INFRA-PLAN.md I-e Stage 2's own safety-framing wording) -----------

/** Mode picker's standard confirm (the plan's own wording, verbatim: `"Set <asset> to <mode>?"`). */
export function modeConfirmMessage(assetDisplayName: string, mode: string): string {
  return `Set ${assetDisplayName} to ${mode}?`;
}

/**
 * The Arm confirm's first-stage warning (`arm-confirm-dialog.ts`) — the plan's own required wording,
 * verbatim: "This will ARM &lt;asset&gt; — the propellers will spin." Deliberately states the
 * physical consequence, not an instruction — same poka-yoke rule `flight-state-logic.ts#flightBanner`
 * already follows for what the aircraft *does*, applied here to what the *command* does.
 */
export function armWarningMessage(assetDisplayName: string): string {
  return `This will ARM ${assetDisplayName} — the propellers will spin.`;
}

/**
 * The Arm confirm's second, final stage — a distinct sentence from {@link armWarningMessage}, not a
 * repeat, so the two stages read as genuinely separate steps rather than the same dialog blinking
 * twice (see `arm-confirm-dialog.ts`'s own doc comment for the full two-stage design).
 */
export function armFinalConfirmLabel(assetDisplayName: string): string {
  return `Yes, arm ${assetDisplayName}`;
}

/**
 * Disarm's confirm copy — a plain question when the aircraft isn't known to be armed, a crash
 * warning when it is. `armed` is `flightState.armed` straight off the latest telemetry sample,
 * `undefined`/`false` both render the plain form (never assume armed from silence).
 *
 * **Airborne, not just armed, is what actually makes disarming dangerous** — but `FlightState`
 * carries no `inAir`/`landed` flag anywhere in the wire contract (`core/api/models.ts`), and
 * `TelemetrySample.altitudeMeters` alone is not a safe stand-in: it's an absolute/relative reading
 * that varies by firmware and home-point setup, so a grounded vehicle can still report a nonzero
 * value — inventing an altitude threshold here would be exactly the kind of fabricated-confidence
 * read this codebase's own poka-yoke rule refuses elsewhere (`flight-state-logic.ts`'s own top doc
 * comment). Per the plan's own documented fallback ("if not [cleanly derivable]... warn whenever
 * armed"), this warns on `armed === true` alone, worded to say "if it's flying" rather than assert
 * it — honest about what is and isn't actually known.
 */
export function disarmConfirmMessage(assetDisplayName: string, armed: boolean | undefined): string {
  return armed === true
    ? `${assetDisplayName} is armed — if it's currently flying, disarming will make it fall.`
    : `Disarm ${assetDisplayName}?`;
}

// --- Outcome toasts (docs/DRONE-INFRA-PLAN.md I-e Stage 2's frozen contract) ---------------------

export type FlightCommandAction = 'mode' | 'arm' | 'disarm';

export type FlightCommandToastKind = 'ok' | 'warning';

export interface FlightCommandToast {
  readonly kind: FlightCommandToastKind;
  readonly text: string;
}

const ACCEPTED_TEXT: Record<FlightCommandAction, string> = {
  mode: 'Mode set',
  arm: 'Armed',
  disarm: 'Disarmed',
};

const NO_ACK_TEXT: Record<FlightCommandAction, string> = {
  mode: 'Mode command sent — no acknowledgement from aircraft',
  arm: 'Arm command sent — no acknowledgement from aircraft',
  disarm: 'Disarm command sent — no acknowledgement from aircraft',
};

/**
 * Pure "which toast for which outcome" decision, one per command action — mirrors
 * `shared/ui/return-home-button-logic.ts#returnHomeToastFor`'s identical shape/reasoning, extended
 * to the 3-action trio this panel adds. Covers only the two success-shaped `202` outcomes; a
 * `404`/`409`/`400`/`403`/network failure never reaches this function — those go through the
 * existing `describeHttpError` (`core/api-error.ts`) at the call site instead, same as Stage 1.
 */
export function commandOutcomeToast(result: FlightCommandResult, action: FlightCommandAction): FlightCommandToast {
  return result === 'ACCEPTED'
    ? { kind: 'ok', text: ACCEPTED_TEXT[action] }
    : { kind: 'warning', text: NO_ACK_TEXT[action] };
}
