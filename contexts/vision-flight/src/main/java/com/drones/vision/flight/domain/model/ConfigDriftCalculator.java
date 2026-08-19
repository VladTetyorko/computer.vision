package com.drones.vision.flight.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure function computing which parameters changed between two {@link VehicleProfile} snapshots of
 * the same aircraft, taken on two different flights (docs/plans/active/DRONE-ONBOARDING-PLAN.md O11,
 * "a config-drift diff between consecutive flights"). Framework-free and stateless -- the same "pure
 * rule" idiom as {@link FlightPhaseRule}, just with no configuration to carry, so this stays a plain
 * final class with a private constructor instead of one holding fields.
 *
 * <h2>What counts as drift</h2>
 * Only a parameter present in <b>both</b> snapshots' {@link VehicleProfile#parameters()} with a
 * different value. A parameter present in one snapshot and absent from the other is deliberately
 * <b>not</b> reported: {@link ParameterReading}'s own contract is "only what was actually read"
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 5.3), so an absence means this probe's window
 * did not read that parameter this time, not that its value changed. Reporting it as drift would
 * manufacture a false positive out of an incomplete probe -- exactly what C7's honesty rule exists to
 * prevent.
 */
public final class ConfigDriftCalculator {

    private ConfigDriftCalculator() {
    }

    /**
     * @param earlier the snapshot taken first; must not be observed after {@code later}
     * @param later   the snapshot taken second
     * @return one {@link ParameterDrift} per parameter present in both snapshots whose value
     *         differs, sorted by parameter name; empty if nothing changed
     * @throws IllegalArgumentException if {@code later} was observed before {@code earlier}
     */
    public static List<ParameterDrift> diff(VehicleProfile earlier, VehicleProfile later) {
        Objects.requireNonNull(earlier, "earlier must not be null");
        Objects.requireNonNull(later, "later must not be null");
        if (later.observedAt().isBefore(earlier.observedAt())) {
            throw new IllegalArgumentException(
                    "ConfigDriftCalculator.diff: later (" + later.observedAt()
                            + ") must not be observed before earlier (" + earlier.observedAt() + ")");
        }

        Map<String, ParameterReading> earlierByName = byName(earlier);
        Map<String, ParameterReading> laterByName = byName(later);

        List<ParameterDrift> drift = new ArrayList<>();
        for (Map.Entry<String, ParameterReading> entry : earlierByName.entrySet()) {
            ParameterReading laterReading = laterByName.get(entry.getKey());
            if (laterReading == null) {
                continue;
            }
            double previousValue = entry.getValue().value();
            double currentValue = laterReading.value();
            if (Double.compare(previousValue, currentValue) != 0) {
                drift.add(new ParameterDrift(entry.getKey(), previousValue, currentValue,
                        earlier.observedAt(), later.observedAt()));
            }
        }
        drift.sort(Comparator.comparing(ParameterDrift::parameterName));
        return List.copyOf(drift);
    }

    /** Latest reading wins if a snapshot ever reports the same parameter name twice. */
    private static Map<String, ParameterReading> byName(VehicleProfile profile) {
        Map<String, ParameterReading> byName = new LinkedHashMap<>();
        for (ParameterReading reading : profile.parameters()) {
            byName.put(reading.name(), reading);
        }
        return byName;
    }
}
