package com.drones.vision.api.live;

import com.drones.vision.api.dto.SubsystemStatusResponse;
import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.api.support.SystemStatusReader;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Samples every {@link SubsystemStatusPort} on a fixed schedule and broadcasts on the {@code
 * system} live topic — but only when the sample actually changed (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4) — replacing {@code
 * SystemStatusController}'s pure on-demand read (still there, unchanged, for a client that hasn't
 * moved off polling yet) with a server-side push for one that has.
 *
 * <h2>Structural constraint: this class owns the schedule, not {@link LiveUpdateRegistry}</h2>
 * Every other {@code publish*} method on {@link LiveUpdateRegistry} is called reactively, the
 * instant a context has something to announce. {@code system} has no such moment — nothing
 * "changes" a subsystem's health from inside this codebase, so something has to go looking on a
 * timer. That something is this class, not the registry: {@link LiveUpdateRegistry#publishSystemStatus}
 * is the one new public method the registry gained for this wave, with one new buffer and
 * <b>no new constructor collaborator</b> (see that method's own javadoc) — the registry stays a
 * passive fan-out hub, and the decision of <em>when</em> to call it lives entirely here.
 *
 * <h2>The self-feedback hazard this class exists to avoid</h2>
 * {@link LiveUpdateStatusProvider} — one of the very providers this class samples — reports on the
 * registry this class broadcasts through: its {@code detail} embeds {@link
 * LiveUpdateRegistry#connectionCount()} and its {@link Health} flips on {@link
 * LiveUpdateRegistry#anyBufferEverDropped()}. Naively broadcasting on every difference would loop:
 * a broadcast can itself change the connection count (a slow tab reconnecting) or trip {@code
 * everDropped} (a burst collapsing a latest-only buffer), which would change the very status this
 * class just sampled, triggering another broadcast — every tab opening or closing would eventually
 * fan a broadcast to every other tab.
 *
 * <p><b>Frozen mitigation</b> (&sect;4.2): {@link #sample()}'s change detection compares {@code
 * overall} plus each subsystem's {@code (id, health, detail, hint)} — {@code checkedAt} is ignored
 * entirely (it changes on every sample by construction), and the {@link
 * LiveUpdateStatusProvider#SUBSYSTEM_ID} subsystem is ignored entirely, on both counts: its own
 * tuple never enters the comparison, <em>and</em> {@code overall} is recomputed over the remaining
 * subsystems only ({@link SystemStatusReader#worstHealth}), so a {@code live-updates}-only health
 * flip cannot even indirectly move the comparison's {@code overall}. Its value still rides in the
 * <em>broadcast</em> payload unchanged (verbatim {@link SystemStatusResponse}, the diagnostics page
 * still shows it) — it simply never <em>triggers</em> one.
 *
 * <h2>First tick, no seed case needed</h2>
 * The first tick runs at delay {@code 0} (the constructor arms {@link #scheduler} with an initial
 * delay of zero), not after one interval — so {@link LiveUpdateRegistry}'s {@code systemBuffer} is
 * populated before any connection can possibly arrive. This is exactly why {@code
 * LiveUpdateRegistry#seedIfEmpty} needs no {@code SYSTEM} case (unlike {@code FLEET}/{@code
 * DEVICES}/{@code DETECTION_EVENTS}, which do have a live-query fallback for a buffer that might
 * still be empty).
 *
 * <h2>Gating</h2>
 * Same repeated-condition idiom as {@link LiveUpdateRegistry}/{@link LiveUpdateStatusProvider}: on
 * whenever {@code vision.live.enabled} is (default true), never via {@code @ConditionalOnBean}
 * (bean-order sensitive — see {@link LiveUpdateStatusProvider}'s own javadoc for why that pattern is
 * avoided repo-wide).
 */
@Component
@ConditionalOnProperty(prefix = "vision.live", name = "enabled", matchIfMissing = true)
public final class SystemStatusSampler {

    private final List<SubsystemStatusPort> providers;
    private final LiveUpdateRegistry registry;
    private final ScheduledExecutorService scheduler;

    /**
     * The last <em>comparison</em> fingerprint broadcast, or {@code null} before the first sample
     * has ever run — so the very first {@link #sample()} call always broadcasts (nothing to compare
     * against yet), exactly once, regardless of how many subsequent ticks find no real change.
     */
    private final AtomicReference<Fingerprint> lastBroadcastFingerprint = new AtomicReference<>(null);

    /**
     * {@code @Autowired} disambiguates this from the package-private test-seam constructor below —
     * Spring cannot pick one of two candidate constructors on its own.
     *
     * <p>{@code registry} is the concrete {@link LiveUpdateRegistry} class, not one of its seven
     * port-typed aliases — needed for {@link LiveUpdateRegistry#publishSystemStatus}, which is not on
     * any of those port interfaces — so {@code @Qualifier("liveUpdateRegistry")} pins it to the one
     * bean actually named that, exactly like {@link LiveUpdateStatusProvider}'s own constructor (see
     * that class's javadoc for why a plain by-type autowire would otherwise find several candidates).
     */
    @Autowired
    public SystemStatusSampler(List<SubsystemStatusPort> providers,
                                @Qualifier("liveUpdateRegistry") LiveUpdateRegistry registry,
                                VisionApiProperties.Live live) {
        this(providers, registry, live, defaultScheduler());
    }

    /**
     * Test seam: an injectable scheduler so a test can call {@link #sample()} directly instead of
     * waiting on real timer ticks — same shape as {@link LiveUpdateRegistry}'s own test-seam
     * constructor.
     */
    SystemStatusSampler(List<SubsystemStatusPort> providers, LiveUpdateRegistry registry,
                         VisionApiProperties.Live live, ScheduledExecutorService scheduler) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(live, "live must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        long periodMillis = live.systemSample().toMillis();
        this.scheduler.scheduleAtFixedRate(this::sample, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "system-status-sampler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Samples every provider via {@link SystemStatusReader#read}, then broadcasts on {@link
     * LiveUpdateRegistry#publishSystemStatus} only if the result's comparison {@link Fingerprint}
     * differs from the last one broadcast — see this class's javadoc for exactly what that fingerprint
     * excludes and why. Package-private so a test can call it directly/deterministically instead of
     * waiting on the real {@link #scheduler} tick, matching {@code LiveUpdateRegistry#flushPending}'s
     * own precedent.
     */
    void sample() {
        SystemStatusResponse status = SystemStatusReader.read(providers);
        Fingerprint fingerprint = Fingerprint.of(status);
        Fingerprint previous = lastBroadcastFingerprint.getAndSet(fingerprint);
        if (!fingerprint.equals(previous)) {
            registry.publishSystemStatus(status);
        }
    }

    /**
     * The subset of a {@link SystemStatusResponse} that actually matters for deciding whether to
     * broadcast — {@code checkedAt} and the {@link LiveUpdateStatusProvider#SUBSYSTEM_ID} subsystem
     * both excluded (see this class's own javadoc). {@code overall} here is recomputed over the
     * filtered subsystem list, not copied from {@link SystemStatusResponse#overall()} — copying it
     * would let a {@code live-updates}-only flip move the literal field even though its own tuple is
     * filtered out below, reopening exactly the loop this fingerprint exists to close.
     */
    private record Fingerprint(Health overall, List<SubsystemFingerprint> subsystems) {

        static Fingerprint of(SystemStatusResponse status) {
            List<SubsystemFingerprint> subsystems = status.subsystems().stream()
                    .filter(subsystem -> !LiveUpdateStatusProvider.SUBSYSTEM_ID.equals(subsystem.id()))
                    .map(SubsystemFingerprint::of)
                    .toList();
            Health overall = SystemStatusReader.worstHealth(subsystems.stream().map(SubsystemFingerprint::health).toList());
            return new Fingerprint(overall, subsystems);
        }
    }

    /** One subsystem's comparison tuple — {@code since} is deliberately not part of it: it is derived from {@code health}, never an independent change. */
    private record SubsystemFingerprint(String id, Health health, String detail, String hint) {

        static SubsystemFingerprint of(SubsystemStatusResponse subsystem) {
            return new SubsystemFingerprint(subsystem.id(), subsystem.health(), subsystem.detail(), subsystem.hint());
        }
    }
}
