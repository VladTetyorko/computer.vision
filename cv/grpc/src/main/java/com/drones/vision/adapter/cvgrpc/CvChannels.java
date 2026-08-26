package com.drones.vision.adapter.cvgrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Builds a {@link ManagedChannel} to cv-service from one or more {@link CvTarget}s
 * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) — the shared channel-construction logic
 * every {@code Grpc*Port} in this module needs, whether the channel serves the single-process "all"
 * cv-service deployment, a dedicated {@code Training}+{@code Geolocation} host ({@link #forTarget}),
 * or a failover-capable {@code Inference} target list ({@link #forTargets}). {@link
 * GrpcDetectionPort#buildChannel} (its {@code (String, int, GrpcCvSettings)} constructor) delegates
 * to {@link #forTarget} rather than duplicating the keepalive/plaintext setup.
 *
 * <h2>Why a target <em>list</em>, not a pool of channels</h2>
 * A separate {@link ManagedChannel} per target would need its own {@link CvChannelSupervisor}, and
 * whichever port sits on top would need to pick which one to call per request — real load-balancer
 * logic this module has no reason to reinvent. Instead {@link #forTargets} resolves every target into
 * one {@link io.grpc.EquivalentAddressGroup} list on a single channel and leaves the choice to
 * grpc-java's own default load-balancing policy (deliberately never overridden here — {@code
 * pick_first} as of the {@code grpc-bom} version this repo pins): it connects to the first address it
 * can reach and, on that connection failing, walks to the next; {@link CvChannelSupervisor}'s existing
 * reconnect loop then forces the whole address list to be retried on its own bounded cadence exactly
 * as it already forces a single-target channel to retry — no change to that class was needed for
 * failover to work.
 *
 * <h2>Honest limitation</h2>
 * {@link CvChannelSupervisor#describe()} embeds {@code channel.authority()}, which for a multi-target
 * channel is always the <em>first</em> target's {@code host:port} (see {@link
 * StaticTargetsNameResolver#getServiceAuthority()}) — the outage message names the primary target even
 * while a live channel is actually connected through a later one. Good enough for today's "is cv-service
 * reachable at all" question; naming which target is actually live would need reading the channel's
 * picked subchannel, which grpc-java does not expose through {@link ManagedChannel}'s public API.
 */
public final class CvChannels {

    private CvChannels() {
    }

    /** Single-target channel — used for both the training/geolocation channel and (via {@link #forTargets}) a one-element inference target list. */
    public static ManagedChannel forTarget(CvTarget target, GrpcCvSettings settings) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        return applyCommonSettings(ManagedChannelBuilder.forAddress(target.host(), target.port()), settings).build();
    }

    /**
     * Ordered failover target list — see class javadoc for the pick_first mechanism. A single-element
     * list is routed through {@link #forTarget} rather than the custom resolver, so the common
     * "deployment has exactly one cv-service" case is byte-identical to a plain {@code
     * ManagedChannelBuilder.forAddress} channel — no custom resolver in the path at all.
     *
     * @throws IllegalArgumentException if {@code targets} is empty
     */
    public static ManagedChannel forTargets(List<CvTarget> targets, GrpcCvSettings settings) {
        Objects.requireNonNull(targets, "targets must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        if (targets.size() == 1) {
            return forTarget(targets.get(0), settings);
        }
        StaticTargetsNameResolverProvider provider = new StaticTargetsNameResolverProvider(targets);
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forTarget(provider.getDefaultScheme() + "://cv-inference-targets")
                .nameResolverFactory(provider);
        return applyCommonSettings(builder, settings).build();
    }

    private static ManagedChannelBuilder<?> applyCommonSettings(ManagedChannelBuilder<?> builder, GrpcCvSettings settings) {
        builder.keepAliveTime(settings.keepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(settings.keepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(settings.keepAliveWithoutCalls());
        if (settings.plaintext()) {
            builder.usePlaintext();
        }
        return builder;
    }
}
