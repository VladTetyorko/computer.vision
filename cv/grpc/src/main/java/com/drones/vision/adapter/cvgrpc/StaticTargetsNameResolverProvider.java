package com.drones.vision.adapter.cvgrpc;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;

import java.net.URI;
import java.util.List;

/**
 * Builds one {@link StaticTargetsNameResolver} per instance — handed directly to {@link
 * io.grpc.ManagedChannelBuilder#nameResolverFactory} by {@link CvChannels#forTargets}, never
 * registered with grpc's global {@link io.grpc.NameResolverRegistry}, so it cannot leak into or
 * collide with any other channel's scheme resolution anywhere else in the process.
 */
final class StaticTargetsNameResolverProvider extends NameResolverProvider {

    /** Never resolved by DNS or any real scheme handler — this provider is always passed explicitly. */
    static final String SCHEME = "cv-static-targets";

    private final List<CvTarget> targets;

    StaticTargetsNameResolverProvider(List<CvTarget> targets) {
        this.targets = List.copyOf(targets);
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new StaticTargetsNameResolver(targets);
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    /** Never globally registered (see class javadoc), so priority relative to other providers never matters. */
    @Override
    protected int priority() {
        return 0;
    }
}
