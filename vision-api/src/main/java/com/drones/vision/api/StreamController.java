package com.drones.vision.api;

import com.drones.vision.api.dto.ActiveStreamResponse;
import com.drones.vision.api.dto.DetectionResultResponse;
import com.drones.vision.api.dto.StartStreamRequest;
import com.drones.vision.api.dto.StartStreamResponse;
import com.drones.vision.application.StreamService;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Driving REST adapter for starting, stopping, and listing stream pipelines.
 *
 * <p>Constructor-injected with {@link StreamService} plus {@link
 * StreamPublisherPort}, used read-only here to resolve {@code viewUrl}/{@code whepUrl} for
 * whichever publisher is currently wired in ({@code vision-app} decides
 * which implementation that is). Per the hexagonal dependency rule
 * (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on
 * {@code vision-domain} and {@code vision-application} — never on an
 * adapter.
 *
 * <p>No acting user is threaded through here: a stream is transient plumbing rather than a
 * change to the fleet, so nothing on this path is audited against a principal. Asset-level
 * streaming, which is, lives on {@link AssetController}.
 *
 * <p>Also exposes recent detections read-only over {@link DetectionRepositoryPort} (docs/MVP1-PLAN.md
 * §C8 bullet 3) — the same precedent {@link AssetController} already sets for {@link
 * com.drones.vision.domain.port.out.TelemetryRepositoryPort}: no driving use-case exists for "read
 * a stream's recent detections", so this controller reads the driven port directly instead.
 *
 * <p>{@link #snapshot} (docs/MVP3-PLAN.md C-a) is the one binary (non-JSON) response in this
 * controller — a JPEG thumbnail of a running stream's latest published frame, cheap enough for a
 * manager dashboard to poll per-visible-tile.
 */
@RestController
public class StreamController {

    private static final System.Logger LOG = System.getLogger(StreamController.class.getName());

    /** Default {@code limit} for {@link #detections} when the query parameter is absent. */
    private static final int DEFAULT_DETECTIONS_LIMIT = 50;

    private final StreamService streamService;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;

    public StreamController(StreamService streamService, StreamPublisherPort streamPublisherPort,
                             DetectionRepositoryPort detectionRepositoryPort) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.streamPublisherPort =
                Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
    }

    /**
     * Starts a stream pipeline for the given device. The optional request
     * body's fields, if present, override the corresponding defaults from
     * {@link PipelineConfig#defaults()}; everything else comes from the
     * defaults.
     *
     * @param deviceId the device to stream from
     * @param request  optional overrides; {@code null}/absent means use every default
     * @return the started stream's id and (if available) its viewer URLs
     */
    @PostMapping("/api/devices/{deviceId}/stream")
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse start(@PathVariable String deviceId,
                                      @RequestBody(required = false) StartStreamRequest request) {
        PipelineConfig config = (request == null ? StartStreamRequest.EMPTY : request).mergeOntoDefaults();
        StreamId streamId = streamService.start(DeviceId.of(deviceId), config);
        StartStreamResponse response = new StartStreamResponse(streamId.value().toString(), viewUrl(streamId), whepUrl(streamId));
        LOG.log(System.Logger.Level.INFO, () -> "Started stream " + response.streamId() + " for device " + deviceId
                + " viewUrl=" + response.viewUrl() + " whepUrl=" + response.whepUrl());
        return response;
    }

    /**
     * Lists streams currently active on this instance.
     *
     * @return the active streams, each with its viewer URLs if available
     */
    @GetMapping("/api/streams")
    public List<ActiveStreamResponse> list() {
        return streamService.streams().stream()
                .map(s -> new ActiveStreamResponse(s.streamId().value().toString(), s.deviceId().value().toString(),
                        s.startedAt(), viewUrl(s.streamId()), whepUrl(s.streamId())))
                .toList();
    }

    /**
     * Stops a running stream. Stopping an unknown or already-stopped stream
     * is a no-op (per {@link StreamService#stop(StreamId)}'s contract) and
     * still returns 204 — there is no "stream not found" error case.
     *
     * @param streamId the stream to stop
     */
    @DeleteMapping("/api/streams/{streamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable String streamId) {
        streamService.stop(StreamId.of(streamId));
        LOG.log(System.Logger.Level.INFO, () -> "Stopped stream " + streamId);
    }

    /**
     * Lists a stream's most recent completed detection results, newest first (docs/MVP1-PLAN.md
     * §C8 bullet 3) — for the Live page's detections strip.
     *
     * <p>An unknown stream id behaves exactly as {@link DetectionRepositoryPort#query} does (an
     * empty list, per its driven-port contract), not a 404 — mirroring {@link
     * AssetController#telemetry}'s precedent for the same reason: this endpoint has no service
     * method of its own to layer "unknown stream" validation onto. Results are sorted here rather
     * than relied upon to already be in order, since {@link DetectionRepositoryPort#query}'s
     * contract does not guarantee one.
     *
     * @param streamId the stream to inspect, as a canonical UUID string
     * @param limit    maximum number of results to return; must be positive (400 otherwise, via
     *                 {@link DetectionQuery}'s own validation); defaults to {@value
     *                 #DEFAULT_DETECTIONS_LIMIT}
     * @return the stream's recent detection results, newest first
     */
    @GetMapping("/api/streams/{streamId}/detections")
    public List<DetectionResultResponse> detections(
            @PathVariable String streamId,
            @RequestParam(defaultValue = "" + DEFAULT_DETECTIONS_LIMIT) int limit) {
        DetectionQuery query = new DetectionQuery(StreamId.of(streamId), null, null, null, limit);
        return detectionRepositoryPort.query(query).stream()
                .sorted(Comparator.comparing(DetectionResult::capturedAt).reversed())
                .map(DetectionResultResponse::from)
                .toList();
    }

    /**
     * A JPEG snapshot of the latest published frame on a running stream (docs/MVP3-PLAN.md C-a) —
     * post-overlay burn-in when it's on, since {@link StreamService#latestFrame} returns exactly
     * the instance the pipeline last handed to {@link StreamPublisherPort#publish}. Downscaled to
     * at most {@value SnapshotJpegEncoder#MAX_SNAPSHOT_WIDTH}px wide (aspect-preserving, see {@link
     * SnapshotJpegEncoder}) so a manager dashboard polling many thumbnails at once (docs/MVP3-PLAN.md
     * Command, ~1/5s per visible tile) stays cheap.
     *
     * <p>Never cached ({@code Cache-Control: no-store}) — every poll wants the actual latest frame,
     * not a browser- or intermediary-cached one.
     *
     * @param streamId the stream to snapshot, as a canonical UUID string
     * @return the JPEG bytes
     * @throws NoSuchElementException if the stream is unknown, or is running but hasn't published a
     *                                 frame yet (both → 404, same mapping as every other unknown-id
     *                                 case in this codebase)
     */
    @GetMapping(value = "/api/streams/{streamId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable String streamId) {
        VideoFrame frame = streamService.latestFrame(StreamId.of(streamId))
                .orElseThrow(() -> new NoSuchElementException("No frame available for stream: " + streamId));
        byte[] jpeg = SnapshotJpegEncoder.encode(frame);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(jpeg);
    }

    private String viewUrl(StreamId streamId) {
        return streamPublisherPort.viewUrl(streamId).map(URI::toString).orElse(null);
    }

    private String whepUrl(StreamId streamId) {
        return streamPublisherPort.whepUrl(streamId).map(URI::toString).orElse(null);
    }
}
