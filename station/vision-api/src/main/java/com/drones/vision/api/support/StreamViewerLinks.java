package com.drones.vision.api.support;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;

/**
 * The narrow {@code viewUrl}/{@code whepUrl} read {@link
 * com.drones.vision.api.controller.AssetStreamController} needs off {@link StreamPublisherPort} —
 * split out (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.8) so that controller can add a {@link
 * com.drones.vision.perception.domain.model.PipelineConfig} default without exceeding this
 * codebase's five-constructor-parameter ceiling (.claude/skills/java-clean-code/SKILL.md &sect;3):
 * trading a constructor parameter for this one frees exactly the slot the new default needs. {@code
 * StreamController} keeps its own {@link StreamPublisherPort} collaborator directly — it uses it for
 * far more than these two reads, so wrapping there would be a swap, not a reduction. {@link
 * StreamService} was dropped from this class's own collaborators (docs/plans/done/CV-CLEAN-FEED-PLAN.md
 * D-1) once its only read here, {@code burnedIn}, stopped existing: with server-side overlay burn-in
 * removed entirely, every published frame is the clean one, and there is nothing left to report.
 *
 * <p>A real {@code @Component}, unlike {@link StreamDetectionSupport}: every dependency it needs
 * ({@link StreamPublisherPort}) is already a Spring bean regardless of any feature flag, so there is
 * no wiring decision for {@code vision-app} to make on this class's behalf.
 */
@Component
public final class StreamViewerLinks {

    private final StreamPublisherPort streamPublisherPort;

    public StreamViewerLinks(StreamPublisherPort streamPublisherPort) {
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
    }

    public String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    public String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }
}
