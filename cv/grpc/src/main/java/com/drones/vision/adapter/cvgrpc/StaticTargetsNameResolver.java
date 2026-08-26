package com.drones.vision.adapter.cvgrpc;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link NameResolver} over a fixed, pre-parsed {@link CvTarget} list — no DNS, no I/O, resolves
 * synchronously on {@link #start}/{@link #refresh}. Paired with grpc-java's default {@code pick_first}
 * load-balancing policy (unset here deliberately — see {@link CvChannels}), this is what gives {@link
 * CvChannels#forTargets} its failover behaviour: {@code pick_first} walks the resolved address list in
 * order and connects to the first one it can reach, so a channel built over more than one target tries
 * the next when the previous is unreachable, with no custom retry logic in this module at all — {@link
 * CvChannelSupervisor} keeps watching the one resulting {@link io.grpc.ManagedChannel}'s connectivity
 * state exactly as it does for a single-target channel.
 */
final class StaticTargetsNameResolver extends NameResolver {

    private final List<CvTarget> targets;
    private volatile Listener2 listener;

    StaticTargetsNameResolver(List<CvTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    /**
     * The primary (first) target's {@code host:port} — used only for diagnostics ({@link
     * CvChannelSupervisor#describe()} embeds {@code channel.authority()} verbatim) and for {@link
     * WireFormat#resolve(String)}'s loopback check. Not necessarily the target a failed-over channel
     * is actually connected to — see {@link CvChannels}' javadoc for that honest limitation.
     */
    @Override
    public String getServiceAuthority() {
        CvTarget primary = targets.get(0);
        return primary.host() + ":" + primary.port();
    }

    @Override
    public void start(Listener2 listener) {
        this.listener = listener;
        resolve();
    }

    @Override
    public void refresh() {
        resolve();
    }

    private void resolve() {
        Listener2 currentListener = listener;
        if (currentListener == null) {
            return;
        }
        List<EquivalentAddressGroup> groups = new ArrayList<>(targets.size());
        for (CvTarget target : targets) {
            groups.add(new EquivalentAddressGroup(new InetSocketAddress(target.host(), target.port())));
        }
        currentListener.onResult(ResolutionResult.newBuilder().setAddresses(groups).build());
    }

    /** Nothing to release — this resolver starts no background thread and holds no I/O resource. */
    @Override
    public void shutdown() {
        listener = null;
    }
}
