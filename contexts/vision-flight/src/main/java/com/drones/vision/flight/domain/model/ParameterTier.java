package com.drones.vision.flight.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The risk tiering ANY-DRONE-PLAN.md §1.3 defines and docs/plans/active/DRONE-ONBOARDING-PLAN.md §4a
 * makes enforceable: <b>the allowlist is domain code in {@code contexts/vision-flight}, not a UI
 * convention</b> (§4a, D9) — a screen that forgets to hide a button must still be refused by the
 * service that would otherwise send the write.
 *
 * <p>{@link #classify(String)} returns {@link Optional#empty()} for any name that matches none of
 * the patterns below. That is deliberately <b>not</b> the same as {@link #C}: a name this platform
 * has no opinion on is simply never writable, exactly like {@link #C}, but for a different reason
 * (no policy exists, rather than an explicit "never" policy) — callers that need to distinguish
 * "known-forbidden" from "unrecognized" can still do so via the returned {@code Optional}, while
 * every write path in this module treats both identically: refuse.
 *
 * <p><b>Honesty about the seed list</b>: the exact ArduPilot parameter names below are a
 * representative seed illustrating each tier from docs/plans/active/DRONE-ONBOARDING-PLAN.md §4a's
 * own examples, not a verified-complete enumeration of every parameter a real firmware exposes —
 * the plan's own Mechanism C section makes the identical caveat about BF/INAV CLI keys ("must be
 * verified against firmware sources before implementation"). Tightening or extending this list is
 * expected and safe: every change only ever <i>removes</i> writability for names that were not
 * explicitly allowed before, never grants it.
 */
public enum ParameterTier {

    /** Reporting: stream rates, system id, serial protocol selection. One confirm, audited, reversible. */
    A,
    /** Link and failsafe behaviour. Explicit per-item consent, disarmed-only, audited. */
    B,
    /** Flight-critical. Never written, at any authority level, behind no flag (D9). Reported only. */
    C;

    private static final List<TierPattern> PATTERNS = List.of(
            // Tier A -- reporting (docs/plans/active/DRONE-ONBOARDING-PLAN.md §4a).
            new TierPattern(A, Pattern.compile("SR\\d_.*")),
            new TierPattern(A, Pattern.compile("SYSID_THISMAV")),
            new TierPattern(A, Pattern.compile("SYSID_MYGCS")),
            new TierPattern(A, Pattern.compile("SERIAL\\d_PROTOCOL")),
            // Tier B -- link & failsafe behaviour.
            new TierPattern(B, Pattern.compile("FS_GCS_ENABLE")),
            new TierPattern(B, Pattern.compile("FS_LONG_TIMEOUT")),
            new TierPattern(B, Pattern.compile("FS_SHORT_TIMEOUT")),
            new TierPattern(B, Pattern.compile("RC_FS_TIMEOUT")),
            new TierPattern(B, Pattern.compile("RC\\d*_OVERRIDE_.*")),
            // Tier C -- flight-critical, never written (docs/plans/active/DRONE-ONBOARDING-PLAN.md §4a).
            new TierPattern(C, Pattern.compile("ARMING_CHECK")),
            new TierPattern(C, Pattern.compile("FRAME_CLASS")),
            new TierPattern(C, Pattern.compile("FRAME_TYPE")),
            new TierPattern(C, Pattern.compile("BATT_CAPACITY")),
            new TierPattern(C, Pattern.compile("COMPASS_OFS_[XYZ]")),
            new TierPattern(C, Pattern.compile("INS_ACCOFFS_[XYZ]")),
            new TierPattern(C, Pattern.compile("ATC_.*")) // attitude-controller PID family
    );

    /**
     * Classifies a vehicle parameter name against the seeded allowlist.
     *
     * @param parameterName the vehicle-reported parameter name (case-sensitive — vehicle parameter
     *                      names are already upper-case by MAVLink convention)
     * @return the matching tier, or {@link Optional#empty()} if the name matches no known pattern
     * @throws IllegalArgumentException if {@code parameterName} is blank
     */
    public static Optional<ParameterTier> classify(String parameterName) {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("parameterName must not be blank");
        }
        // Classify by canonical spelling, so a firmware rename cannot make a known parameter
        // unclassified -- an unclassified name is refused outright, which would silently take the
        // remedy away from exactly the parameters that were renamed (ParameterAliases).
        String canonical = ParameterAliases.canonical(parameterName);
        return PATTERNS.stream()
                .filter(p -> p.pattern.matcher(canonical).matches())
                .map(p -> p.tier)
                .findFirst();
    }

    /**
     * Whether this tier may ever be written at all, independent of who is asking. {@link #C} is
     * never writable "at any authority level" (§6.1) — authority/consent/arming gates only ever
     * apply to {@link #A}/{@link #B}, and only after this check already passed.
     */
    public boolean everWritable() {
        return this != C;
    }

    private record TierPattern(ParameterTier tier, Pattern pattern) {
    }
}
