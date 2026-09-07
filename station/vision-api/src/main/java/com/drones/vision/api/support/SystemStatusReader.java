package com.drones.vision.api.support;

import com.drones.vision.api.dto.SubsystemStatusResponse;
import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code GET /api/system/status} rollup — sampling every {@link SubsystemStatusPort} and
 * ranking the worst {@link Health} across them — extracted verbatim out of {@code
 * SystemStatusController} (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2,
 * wave L4a) so {@code com.drones.vision.api.live.SystemStatusSampler} can reuse the exact same
 * logic to decide when the {@code system} live topic has actually changed, without a second copy
 * of the severity ranking. The controller's wire output is unchanged: {@link #read} is the same
 * body the controller's {@code status()} used to run inline.
 *
 * <p>Static, not a Spring bean, matching {@link DeviceOriginParsing}/{@link CapabilityParsing}'s
 * precedent in this package — it has no collaborator of its own, so a bean would add nothing but
 * ceremony (and would give {@code SystemStatusController}'s constructor a second parameter it
 * does not need).
 */
public final class SystemStatusReader {

    /**
     * Worst-to-best ranking used by {@link #worstHealth(List)}, deliberately excluding {@link
     * Health#DISABLED} (never a candidate — filtered out before this map is consulted). The plan
     * (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3) specifies "worst health across subsystems" but
     * not the exact DOWN-vs-UNKNOWN precedence; the call here is that a <em>confirmed</em> DOWN
     * outranks an <em>inconclusive</em> UNKNOWN, since a provider that cannot say what is going on is
     * less alarming than one that has confirmed a real outage.
     */
    private static final Map<Health, Integer> SEVERITY = new EnumMap<>(Health.class);

    static {
        SEVERITY.put(Health.OK, 0);
        SEVERITY.put(Health.DEGRADED, 1);
        SEVERITY.put(Health.UNKNOWN, 2);
        SEVERITY.put(Health.DOWN, 3);
    }

    private SystemStatusReader() {
    }

    /**
     * Samples every provider and rolls the result up into one response — {@code
     * SystemStatusController#status()}'s entire former body.
     *
     * <p><strong>One sick provider must not blind the page</strong>: an exception from a single
     * {@link SubsystemStatusPort} is caught and reported as that one entry being {@link
     * Health#UNKNOWN}, with the exception's message as {@code detail}, rather than failing this
     * call entirely.
     *
     * @param providers every registered subsystem status provider
     * @return the assembled response, {@code checkedAt} stamped to the moment this method ran
     */
    public static SystemStatusResponse read(List<SubsystemStatusPort> providers) {
        List<SubsystemStatus> statuses = providers.stream().map(SystemStatusReader::safeStatus).toList();
        List<SubsystemStatusResponse> subsystems = statuses.stream().map(SubsystemStatusResponse::from).toList();
        Health overall = worstHealth(statuses.stream().map(SubsystemStatus::health).toList());
        return new SystemStatusResponse(overall, Instant.now(), subsystems);
    }

    private static SubsystemStatus safeStatus(SubsystemStatusPort provider) {
        try {
            return provider.status();
        } catch (RuntimeException e) {
            String id = provider.getClass().getSimpleName();
            // Blank, not merely null: SubsystemStatus rejects a blank `detail`, so a provider that
            // threw with an empty message would make *this* constructor throw from inside the catch
            // block -- failing the whole rollup and defeating the very isolation it implements.
            // Throwable#toString() is blank-proof (it always carries at least the class name).
            String message = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            return new SubsystemStatus(id, id, Health.UNKNOWN, message, null, null);
        }
    }

    /**
     * The worst {@link Health} across {@code healths}, ignoring {@link Health#DISABLED} — a
     * deliberately switched-off subsystem must never read as a fault. {@link Health#UNKNOWN} when
     * nothing remains after that filter, whether because {@code healths} was empty to begin with or
     * because every entry present happens to be disabled.
     *
     * <p>Exposed (not {@code private}) so {@code SystemStatusSampler} can recompute the same
     * ranking over a filtered subsystem list — excluding {@code live-updates} — for its own
     * change-detection comparison, without duplicating {@link #SEVERITY}.
     *
     * @param healths the healths to rank
     * @return the worst, or {@link Health#UNKNOWN} if none remain after excluding {@link Health#DISABLED}
     */
    public static Health worstHealth(List<Health> healths) {
        return healths.stream()
                .filter(health -> health != Health.DISABLED)
                .max(Comparator.comparingInt(SEVERITY::get))
                .orElse(Health.UNKNOWN);
    }
}
