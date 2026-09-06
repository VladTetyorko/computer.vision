package com.drones.vision.api.live;

import com.drones.vision.api.dto.SystemStatusResponse;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SystemStatusSampler}'s change detection (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4) — the frozen mitigation for the
 * self-feedback hazard described in that class's own javadoc. {@link LiveUpdateRegistry} is mocked
 * directly (Mockito's inline mock maker handles the {@code final} class with no extra setup) so
 * each test can assert purely on how many times {@link LiveUpdateRegistry#publishSystemStatus} was
 * called, without also standing up a real registry's five collaborators.
 *
 * <p>{@link ImmediateScheduledExecutorService} makes the constructor's own {@code
 * scheduleAtFixedRate} call a harmless no-op (it registers the periodic task but never actually
 * re-invokes it) — every test calls {@link SystemStatusSampler#sample()} directly, deterministically,
 * instead of waiting on a real timer tick.
 */
class SystemStatusSamplerTest {

    private final LiveUpdateRegistry registry = mock(LiveUpdateRegistry.class);

    private SystemStatusSampler sampler(List<SubsystemStatusPort> providers) {
        return new SystemStatusSampler(providers, registry, VisionApiProperties.Live.defaults(),
                new ImmediateScheduledExecutorService());
    }

    private static SubsystemStatusPort providerReturning(SubsystemStatus... statuses) {
        SubsystemStatusPort provider = mock(SubsystemStatusPort.class);
        if (statuses.length == 1) {
            when(provider.status()).thenReturn(statuses[0]);
        } else {
            when(provider.status()).thenReturn(statuses[0],
                    List.of(statuses).subList(1, statuses.length).toArray(new SubsystemStatus[0]));
        }
        return provider;
    }

    /**
     * L4d (1): an unchanged status sampled repeatedly must broadcast exactly once — the first sample
     * has nothing to compare against yet (see {@link SystemStatusSampler}'s own javadoc on {@code
     * lastBroadcastFingerprint}), so it always broadcasts; every following sample finds no change and
     * must not broadcast again.
     */
    @Test
    void unchangedStatusSampledRepeatedlyBroadcastsExactlyOnce() {
        SubsystemStatusPort provider = providerReturning(
                new SubsystemStatus("cv-service", "CV inference", Health.OK, "connected", null, null));
        SystemStatusSampler sampler = sampler(List.of(provider));

        sampler.sample();
        sampler.sample();
        sampler.sample();

        ArgumentCaptor<SystemStatusResponse> captor = ArgumentCaptor.forClass(SystemStatusResponse.class);
        verify(registry, times(1)).publishSystemStatus(captor.capture());
        assertTrue(captor.getValue().subsystems().stream().anyMatch(s -> "cv-service".equals(s.id())),
                "the broadcast payload must still carry every subsystem, including the ones outside the comparison");
    }

    /**
     * L4d (2): the self-feedback hazard's actual proof. {@code live-updates} moves its connection
     * count and then flips {@link Health#OK}&rarr;{@link Health#DEGRADED} (the {@code everDropped}
     * defect this wave deliberately does not fix) across three samples, with every other subsystem
     * held fixed — after the first (baseline) broadcast, neither further sample must broadcast again:
     * the {@code live-updates}-only changes are excluded from the comparison entirely.
     */
    @Test
    void aLiveUpdatesOnlyChangeNeverTriggersAnAdditionalBroadcast() {
        SubsystemStatusPort realSubsystem = providerReturning(
                new SubsystemStatus("cv-service", "CV inference", Health.OK, "connected", null, null));
        SubsystemStatusPort liveUpdates = providerReturning(
                new SubsystemStatus(LiveUpdateStatusProvider.SUBSYSTEM_ID, "Live updates (SSE)", Health.OK,
                        "0 live connections open", null, null),
                new SubsystemStatus(LiveUpdateStatusProvider.SUBSYSTEM_ID, "Live updates (SSE)", Health.OK,
                        "1 live connection open", null, null),
                new SubsystemStatus(LiveUpdateStatusProvider.SUBSYSTEM_ID, "Live updates (SSE)", Health.DEGRADED,
                        "1 live connection open; a slow consumer has missed at least one update", null,
                        "A client reconnect (or page refresh) resubscribes to a fresh buffer"));
        SystemStatusSampler sampler = sampler(List.of(realSubsystem, liveUpdates));

        sampler.sample();
        verify(registry, times(1)).publishSystemStatus(any()); // the unavoidable first-sample baseline

        sampler.sample(); // live-updates' connection count moved -- must not broadcast
        verify(registry, times(1)).publishSystemStatus(any());

        sampler.sample(); // live-updates flipped OK -> DEGRADED (the known everDropped defect) -- still must not broadcast
        verify(registry, times(1)).publishSystemStatus(any());
    }

    /**
     * L4d (3): contrast with the previous test — a real subsystem's own health change must broadcast,
     * exactly once per change, unlike a {@code live-updates}-only change.
     */
    @Test
    void aRealSubsystemHealthChangeTriggersOneAdditionalBroadcast() {
        SubsystemStatusPort provider = providerReturning(
                new SubsystemStatus("cv-service", "CV inference", Health.OK, "connected", null, null),
                new SubsystemStatus("cv-service", "CV inference", Health.DOWN, "unreachable", null,
                        "Check cv-service is running"));
        SystemStatusSampler sampler = sampler(List.of(provider));

        sampler.sample();
        verify(registry, times(1)).publishSystemStatus(any());

        sampler.sample(); // a genuine health change on a non-excluded subsystem
        verify(registry, times(2)).publishSystemStatus(any());
    }

    /** {@code checkedAt} must never itself trigger a broadcast -- {@link SystemStatusReader#read} stamps a fresh {@code Instant.now()} on every sample even when nothing else changed. */
    @Test
    void checkedAtAloneNeverTriggersABroadcast() {
        SubsystemStatusPort provider = providerReturning(
                new SubsystemStatus("cv-service", "CV inference", Health.OK, "connected", null, null));
        SystemStatusSampler sampler = sampler(List.of(provider));

        sampler.sample();
        try {
            Thread.sleep(5); // ensure Instant.now() genuinely advances between samples
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sampler.sample();

        verify(registry, times(1)).publishSystemStatus(any());
    }

    @Test
    void constructorRejectsNullCollaborators() {
        SubsystemStatusPort provider = providerReturning(
                new SubsystemStatus("cv-service", "CV inference", Health.OK, "connected", null, null));
        List<SubsystemStatusPort> providers = List.of(provider);
        VisionApiProperties.Live live = VisionApiProperties.Live.defaults();
        var scheduler = new ImmediateScheduledExecutorService();

        assertEquals("providers must not be null", assertThrowsNpe(() ->
                new SystemStatusSampler(null, registry, live, scheduler)));
        assertEquals("registry must not be null", assertThrowsNpe(() ->
                new SystemStatusSampler(providers, null, live, scheduler)));
        assertEquals("live must not be null", assertThrowsNpe(() ->
                new SystemStatusSampler(providers, registry, null, scheduler)));
        assertEquals("scheduler must not be null", assertThrowsNpe(() ->
                new SystemStatusSampler(providers, registry, live, null)));
    }

    private static String assertThrowsNpe(Runnable action) {
        try {
            action.run();
        } catch (NullPointerException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected a NullPointerException");
    }
}
