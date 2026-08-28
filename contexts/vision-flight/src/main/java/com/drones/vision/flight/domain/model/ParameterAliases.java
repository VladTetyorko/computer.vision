package com.drones.vision.flight.domain.model;

import java.util.List;
import java.util.Set;

/**
 * The same vehicle parameter under the different names successive firmware generations give it
 * (docs/plans/active/FLEET-RADIO-PLAN.md F0).
 *
 * <p>Exists because a firmware rename silently broke readiness: ArduPilot 4.7 renamed
 * {@code SYSID_THISMAV} to {@code MAV_SYSID}, the probe list was updated to the new spelling and the
 * {@code fleet-identity} requirement row was not. Neither spelling then satisfied the other, so the
 * row evaluated {@code MISSING} on every vehicle of every generation, which is a blocker, which made
 * the readiness verdict a constant {@code NO_GO}. A rename is a recurring fact of this firmware's
 * life, not a one-off, so it is modelled once here rather than patched at each of the several places
 * that compare a parameter name.
 *
 * <p>Deliberately <b>not</b> solved by normalizing at probe time: {@link ParameterReading} carries
 * only what the vehicle actually answered, and rewriting the name the vehicle used would make the
 * profile claim a reading it never gave. The alias belongs at the comparison, not at the record.
 *
 * <p>An alias group is a set of spellings that are the same parameter. A name in no group is its own
 * canonical form — the common case, and never an error.
 */
public final class ParameterAliases {

    /**
     * One entry per parameter that has been renamed. The <b>first</b> element is the canonical
     * spelling; the rest are historical or current synonyms. Canonical choice is arbitrary but must
     * stay stable, because {@link ParameterTier} classifies by canonical name.
     */
    private static final List<List<String>> GROUPS = List.of(
            // ArduPilot 4.7 renamed the vehicle's own MAVLink system id.
            List.of("SYSID_THISMAV", "MAV_SYSID"),
            // Renamed in the same sweep, for the same reason.
            List.of("SYSID_MYGCS", "MAV_GCS_SYSID"));

    private ParameterAliases() {
    }

    /**
     * The canonical spelling of {@code name}, or {@code name} itself when it belongs to no alias
     * group. Never {@code null} for a non-null argument.
     */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        for (List<String> group : GROUPS) {
            if (group.contains(name)) {
                return group.get(0);
            }
        }
        return name;
    }

    /** Whether two parameter names refer to the same vehicle parameter, alias groups considered. */
    public static boolean sameParameter(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return canonical(a).equals(canonical(b));
    }

    /**
     * Every spelling of {@code name}, including {@code name} itself — what a probe must ask for to
     * get an answer from a vehicle of any firmware generation. A name in no group yields just
     * itself.
     *
     * <p>Asking for all of them costs one unanswered request per obsolete spelling, because MAVLink
     * has no "no such parameter" reply and an absent name can only be detected by timeout. That cost
     * is accepted: the alternative is knowing the vehicle's firmware version before probing it,
     * which is one of the things a probe is for.
     */
    public static Set<String> spellingsOf(String name) {
        if (name == null) {
            return Set.of();
        }
        for (List<String> group : GROUPS) {
            if (group.contains(name)) {
                return Set.copyOf(group);
            }
        }
        return Set.of(name);
    }
}
