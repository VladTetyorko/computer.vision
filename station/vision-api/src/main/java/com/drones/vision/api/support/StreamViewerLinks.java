package com.drones.vision.api.support;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;

/**
 * The narrow {@code viewUrl}/{@code whepUrl}/{@code burnedIn} read {@link
 * com.drones.vision.api.controller.AssetStreamController} needs off {@link StreamPublisherPort}/
 * {@link StreamService} — split out (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.8) so that
 * controller can add a {@link com.drones.vision.perception.domain.model.PipelineConfig} default
 * without exceeding this codebase's five-constructor-parameter ceiling
 * (.claude/skills/java-clean-code/SKILL.md &sect;3): trading two constructor parameters for this one
 * frees exactly the slot the new default needs. {@code StreamController} keeps its own {@link
 * StreamService}/{@link StreamPublisherPort} collaborators directly — it uses them for far more than
 * these three reads, so wrapping there would be a swap, not a reduction.
 *
 * <p>A real {@code @Component}, unlike {@link StreamDetectionSupport}: every dependency it needs
 * ({@link StreamPublisherPort}, {@link StreamService}) is already a Spring bean regardless of any
 * feature flag, so there is no wiring decision for {@code vision-app} to make on this class's
 * behalf.
 */
@Component
public final class StreamViewerLinks {

    private final StreamPublisherPort streamPublisherPort;
    private final StreamService streamService;

    public StreamViewerLinks(StreamPublisherPort streamPublisherPort, StreamService streamService) {
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
    }

    public String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    public String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }

    public boolean burnedIn(StreamId streamId) {
        return streamService.burnedIn(streamId);
    }
}
