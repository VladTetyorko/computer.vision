package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.GrpcCvSettings;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wires the CV inference gRPC connection to cv-service — the CV slice of what used to be one
 * 825-line {@code WiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D). Config extraction
 * (wave F4): {@link #detectionPort} now builds a {@code GrpcCvSettings} from {@link
 * VisionCvProperties} instead of passing just {@code detectWidth}/{@code jpegQuality} — every
 * mapped default is byte-identical to the literal it replaced, and this deletes the {@code
 * CV_KEEPALIVE_TIME_SECONDS}/{@code CV_KEEPALIVE_TIMEOUT_SECONDS} constants this class's
 * predecessor used to duplicate from {@code GrpcCvSettings}'s own (formerly package-private,
 * unreachable-from-here) constants.
 *
 * <p>{@code TrainingWiringConfiguration}'s {@code datasetUploadPort}/{@code modelRegistryPort}
 * beans reuse {@link #toGrpcCvSettings} (datasetUploadPort) and {@link
 * VisionCvProperties.Registry#callTimeout()} (modelRegistryPort) — the same shared channel and
 * settings mapping this class builds.
 */
@Configuration
@EnableConfigurationProperties(VisionCvProperties.class)
public class CvWiring {

    /**
     * The shared gRPC connection to cv-service (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): one
     * {@link ManagedChannel} for both {@link #detectionPort}'s {@code GrpcDetectionPort} ({@code
     * Inference/DetectStream}, the per-frame hot path) and {@code TrainingWiringConfiguration}'s
     * {@code modelRegistryPort}/{@code trainingPort} beans ({@code Training/*}, control-plane) — so
     * the platform holds one connection to cv-service, not two independently configured ones.
     *
     * <p>Present whenever either property enables a consumer: {@link VisionCvProperties#enabled()}
     * (live detection) <strong>or</strong> {@code VisionTrainingProperties#enabled()} (the model
     * registry <em>and</em> training port). With both off (the default), no channel is built at all.
     *
     * <h2>Shutdown ownership</h2>
     * This bean — not either port — owns the channel's lifecycle ({@code destroyMethod =
     * "shutdown"}). {@link #detectionPort} disables {@code GrpcDetectionPort}'s own inferred {@code
     * close()} destroy call via an explicit empty {@code destroyMethod}.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false}")
    public ManagedChannel cvGrpcChannel(VisionCvProperties cvProperties) {
        GrpcCvSettings settings = toGrpcCvSettings(cvProperties);
        return ManagedChannelBuilder.forAddress(cvProperties.host(), cvProperties.port())
                .usePlaintext()
                .keepAliveTime(settings.keepAliveTime().toSeconds(), TimeUnit.SECONDS)
                .keepAliveTimeout(settings.keepAliveTimeout().toSeconds(), TimeUnit.SECONDS)
                .keepAliveWithoutCalls(settings.keepAliveWithoutCalls())
                .build();
    }

    /**
     * Maps every {@code GrpcCvSettings} field off {@link VisionCvProperties} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F4) — shared by {@link #cvGrpcChannel}/{@link #detectionPort} here and {@code
     * TrainingWiringConfiguration#datasetUploadPort}.
     */
    static GrpcCvSettings toGrpcCvSettings(VisionCvProperties properties) {
        VisionCvProperties.Upload upload = properties.upload();
        return new GrpcCvSettings(properties.responseTimeout(), properties.keepAliveTime(),
                properties.keepAliveTimeout(), properties.keepAliveWithoutCalls(), properties.channelShutdownTimeout(),
                properties.plaintext(), upload.timeout(), upload.chunkBytes(), properties.detectWidth(),
                properties.jpegQuality());
    }

    /**
     * Selects the {@link DetectionPort} implementation per {@link VisionCvProperties#enabled()}
     * (docs/plans/done/MVP1-PLAN.md §C7 bullet 4): {@code true} wires {@code GrpcDetectionPort}
     * (adapter-cv-grpc) against the shared {@link #cvGrpcChannel}; {@code false} (the default)
     * keeps today's {@link NoopDetectionPort}.
     *
     * <p><strong>{@code destroyMethod = ""}, deliberately</strong> — disables {@code @Bean}'s
     * default destroy-method inference (which would otherwise call {@code GrpcDetectionPort#close()}
     * at context shutdown, unconditionally shutting the <em>shared</em> channel down).
     */
    @Bean(destroyMethod = "")
    public DetectionPort detectionPort(VisionCvProperties cvProperties, ObjectProvider<ManagedChannel> cvGrpcChannel) {
        if (cvProperties.enabled()) {
            return new GrpcDetectionPort(cvGrpcChannel.getObject(), toGrpcCvSettings(cvProperties));
        }
        return new NoopDetectionPort();
    }

    /**
     * The detection-model roster {@code CvModelsController} (component-scanned from {@code
     * vision-api}) serves at {@code GET /api/cv/models} (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire
     * contract) — the Fly cockpit's model picker builds its dropdown from exactly this list.
     *
     * <p>Deliberately a static, in-source constant, not the dormant {@code ModelRegistryPort}
     * (docs/plans/done/CV-CONTROL-PLAN.md §D): that port models versioned promote/rollback (a Phase-3
     * training-studio concern) and has no implementation — wiring it now for a picker that only
     * needs a display list would be over-building.
     */
    @Bean
    public List<CvModelResponse> cvModelRoster() {
        return List.of(
                new CvModelResponse("yolo26n.pt", "General (people & vehicles, fast)", "general", false, List.of()),
                new CvModelResponse("orion12l.pt", "Military vehicles", "specialized", false, List.of()),
                new CvModelResponse("yoloe-26s-seg-pf.pt", "Everything (incl. buildings, slower)", "open-vocab",
                        true, List.of()));
    }
}
