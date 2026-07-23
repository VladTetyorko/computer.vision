package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.ImageEncoding;
import com.drones.vision.proto.v1.InferenceGrpc;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link DetectionPort} over the generated {@code Inference/DetectStream} gRPC
 * stub — the Java client half of the Java&harr;Python CV service contract
 * (see {@code proto/vision/v1/cv.proto}).
 *
 * <h2>Per-stream bidi correlation</h2>
 * {@code DetectStream} is a bidirectional streaming RPC; this class keeps
 * exactly one call open per {@link StreamId}, lazily started on the first
 * {@link #detect} for that stream and kept in a {@link ConcurrentHashMap}.
 * Each frame is sent as one {@code FrameRequest} on that stream's request
 * observer, keyed by {@code sequence}; the matching {@code DetectionResponse}
 * (correlated back by the same {@code sequence}, not by re-parsing the
 * wire-echoed {@code stream_id}) completes the {@link CompletableFuture}
 * returned to the caller. gRPC stream observers are not thread-safe, so all
 * writes to a stream's request observer (including the lazy open) are
 * serialized through a per-stream monitor.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Transport failure</b> ({@code onError} from the server, e.g. the
 *   CV service restarting): every pending future for that stream fails
 *   immediately with the transport exception, and the stream's entry is
 *   dropped — the next {@link #detect} for that {@link StreamId} transparently
 *   reopens a fresh call. This is what lets a caller's retry/backoff recover
 *   after a service restart without ever seeing a stuck stream.</li>
 *   <li><b>Hung or unreachable service</b> (no failure, just silence — this is
 *   also what a session opened while the CV service is entirely down looks
 *   like: the underlying call can sit half-open with no {@code onError} ever
 *   delivered): a bidi call has no natural per-call deadline, so instead every
 *   pending future gets its own {@value #RESPONSE_TIMEOUT_SECONDS}s timeout
 *   ({@link CompletableFuture#orTimeout}). A timeout is treated exactly like a
 *   transport failure — the call is cancelled, every other pending future for
 *   that stream fails immediately, and the session is dropped — so the next
 *   {@link #detect} always opens a fresh call instead of reusing a session
 *   that may never recover on its own.</li>
 *   <li><b>Unsupported {@link PixelFormat}</b>: fails fast with no gRPC call
 *   at all — the frame's stream is never opened/touched.</li>
 * </ul>
 *
 * <h2>Payload shrinking for large BGR24 frames</h2>
 * A {@link PixelFormat#BGR24} frame wider than {@value #MAX_DETECT_WIDTH}px
 * (the full-resolution raw frames the RTSP/file RX path produces, e.g.
 * 1280&times;720 at ~2.7&nbsp;MB uncompressed) is downscaled to
 * {@value #MAX_DETECT_WIDTH}px wide (aspect preserved, integer height
 * rounding) and JPEG-encoded before being sent, as
 * {@code IMAGE_ENCODING_JPEG} with the scaled width/height. {@code JPEG}
 * frames and {@code BGR24} frames already {@value #MAX_DETECT_WIDTH}px wide
 * or narrower pass through byte-identical, exactly as before this existed.
 * Detections come back with box coordinates normalized to {@code [0,1]} and
 * are correlated purely by {@code sequence} — {@link DetectionResult} never
 * references the source frame's pixel dimensions — so <b>no coordinate
 * mapping back to the original resolution is needed or performed</b>; the
 * correlation/pending-map/session/teardown machinery below is entirely
 * unaffected by this and does not need to know it happens.
 *
 * <h2>Stream lifecycle</h2>
 * There is no idle eviction. Callers that know a stream has ended should call
 * {@link #streamEnded(StreamId)} to fail any still-pending futures for it and
 * half-close its request observer; skipping it just leaves the call open
 * until {@link #close()} (the next {@code detect()} for that id would simply
 * keep reusing it). {@link #close()} shuts down every open stream and then
 * the underlying channel, whether the channel was built by this instance or
 * supplied by the caller.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. Looking up/opening the stream and sending the
 * request are non-blocking; the returned stage completes later, on a gRPC
 * executor thread. <b>One nuance to the "never blocks" story</b>: when the
 * payload-shrinking path above applies, the downscale-and-JPEG-encode step
 * runs synchronously on the calling thread, inside {@link #detect}, before
 * the request is even built — it is CPU-bound work (no I/O), bounded at
 * roughly 15ms for a 720p frame, and it *replaces* serializing/transmitting
 * several megabytes of raw pixel data with encoding and sending a couple
 * hundred KB, so net caller-thread cost versus the pre-existing fast path is
 * roughly flat rather than new added latency. A conversion failure (the
 * encoder throwing) fails only that one frame's returned stage — the
 * session, if one already exists for the stream, is never touched, exactly
 * like the existing malformed-response handling in {@code onResponse}.
 */
public final class GrpcDetectionPort implements DetectionPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(GrpcDetectionPort.class.getName());

    /** Per-pending-future response timeout — see class javadoc's "hung service" case. */
    static final long RESPONSE_TIMEOUT_SECONDS = 2;

    /**
     * Widest a {@link PixelFormat#BGR24} frame may be before it is
     * downscaled and JPEG-encoded instead of sent raw — see class javadoc's
     * "Payload shrinking" section. Package-private, adapter-internal (not a
     * {@code PipelineConfig} knob): it tunes the wire payload, not detection
     * behavior, and {@code cv-service}'s own inference input size
     * (`CV_IMGSZ`, see `cv-service/MODULE.md`) is independent of it.
     */
    static final int MAX_DETECT_WIDTH = 640;

    /** JPEG quality passed to the encoder for the downscale path above; ~0.8 balances size vs. detail. */
    private static final float JPEG_QUALITY = 0.8f;

    private static final long CHANNEL_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ManagedChannel channel;
    private final InferenceGrpc.InferenceStub asyncStub;
    private final ConcurrentHashMap<StreamId, StreamSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Convenience constructor: builds a plaintext {@link ManagedChannel} to
     * {@code host:port}. The CV service is reached over a private/internal
     * network (docker-compose) so plaintext is deliberate, not an oversight.
     */
    public GrpcDetectionPort(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    /**
     * Test/advanced seam: bring your own channel (e.g. an in-process channel
     * in tests). {@link #close()} shuts this channel down regardless of who
     * built it.
     */
    public GrpcDetectionPort(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.asyncStub = InferenceGrpc.newStub(channel);
    }

    @Override
    public CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config) {
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("GrpcDetectionPort is closed"));
        }

        ImageEncoding encoding = toImageEncoding(frame.format());
        if (encoding == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported PixelFormat for gRPC inference: " + frame.format()));
        }

        FrameRequest request;
        try {
            request = buildRequest(frame, config, encoding);
        } catch (IOException | RuntimeException e) {
            // Conversion (downscale/JPEG-encode) failure: fails only this frame's stage, exactly
            // like a malformed response does for one pending future -- no session is created or
            // touched here, so an already-open session for this stream (if any) is unaffected.
            return CompletableFuture.failedFuture(e);
        }

        StreamSession session = sessions.computeIfAbsent(frame.streamId(), StreamSession::new);
        return session.send(frame.sequence(), request);
    }

    /**
     * Signals that {@code streamId} has ended: fails any still-pending
     * futures for it with a {@link CancellationException}, half-closes its
     * request observer (best-effort), and drops the stream entry so a future
     * {@link #detect} for the same id starts a fresh call. Idempotent — a
     * second call (or one for an id with no open stream) is a no-op.
     */
    public void streamEnded(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        StreamSession session = sessions.get(streamId);
        if (session != null) {
            session.endAndClose();
        }
    }

    /**
     * Idempotent. Ends every open stream (see {@link #streamEnded}) and then
     * shuts down the channel, awaiting termination briefly before forcing it.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<StreamSession> openSessions = new ArrayList<>(sessions.values());
        openSessions.forEach(StreamSession::endAndClose);

        channel.shutdown();
        try {
            if (!channel.awaitTermination(CHANNEL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    private static ImageEncoding toImageEncoding(PixelFormat format) {
        return switch (format) {
            case JPEG -> ImageEncoding.IMAGE_ENCODING_JPEG;
            case BGR24 -> ImageEncoding.IMAGE_ENCODING_BGR24;
            default -> null;
        };
    }

    /**
     * Builds the wire request, downscaling+JPEG-re-encoding a {@code BGR24}
     * frame wider than {@value #MAX_DETECT_WIDTH}px first (see class
     * javadoc's "Payload shrinking" section). Everything else passes through
     * unchanged. Runs synchronously on the caller thread.
     *
     * @throws IOException if the JPEG encoder fails (see {@link #encodeJpeg})
     */
    private static FrameRequest buildRequest(VideoFrame frame, PipelineConfig config, ImageEncoding encoding)
            throws IOException {
        FrameRequest.Builder builder = FrameRequest.newBuilder()
                .setStreamId(frame.streamId().value().toString())
                .setSequence(frame.sequence())
                .setTimestampMillis(frame.capturedAt().toEpochMilli())
                .setModelId(config.model().id())
                .setModelVersion(config.model().version())
                .setConfidenceThreshold((float) config.confidenceThreshold());

        if (encoding == ImageEncoding.IMAGE_ENCODING_BGR24 && frame.width() > MAX_DETECT_WIDTH) {
            return withDownscaledJpeg(builder, frame);
        }

        return builder
                .setWidth(frame.width())
                .setHeight(frame.height())
                .setEncoding(encoding)
                .setData(ByteString.copyFrom(frame.data()))
                .build();
    }

    private static FrameRequest withDownscaledJpeg(FrameRequest.Builder builder, VideoFrame frame)
            throws IOException {
        int scaledWidth = MAX_DETECT_WIDTH;
        int scaledHeight = Math.round((float) frame.height() * MAX_DETECT_WIDTH / frame.width());

        BufferedImage source = wrapBgr24(frame.width(), frame.height(), frame.data());
        BufferedImage scaled = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        } finally {
            g.dispose();
        }

        byte[] jpeg = encodeJpeg(scaled);
        return builder
                .setWidth(scaledWidth)
                .setHeight(scaledHeight)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                .setData(ByteString.copyFrom(jpeg))
                .build();
    }

    /**
     * Copies raw {@code BGR24} bytes into a fresh {@link BufferedImage#TYPE_3BYTE_BGR}'s
     * backing array — the same packed-BGR-no-padding layout that format
     * already carries, so this is a straight bulk copy, no per-pixel
     * reordering. Same idiom as {@code adapter-overlay}'s
     * {@code Java2DOverlayRenderer.renderBgr24}.
     */
    private static BufferedImage wrapBgr24(int width, int height, ByteBuffer data) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        data.get(pixels);
        return image;
    }

    /** Encodes {@code image} as a JPEG at {@value #JPEG_QUALITY} quality via an explicit ImageWriter. */
    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available on this JVM");
        }
        ImageWriter writer = writers.next();
        try {
            JPEGImageWriteParam param = new JPEGImageWriteParam(null);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static DetectionResult toDetectionResult(StreamId streamId, DetectionResponse response) {
        ModelRef model = new ModelRef(response.getModelId(), response.getModelVersion());
        List<Detection> detections = response.getDetectionsList().stream()
                .map(wire -> toDetection(wire, model))
                .toList();
        return new DetectionResult(
                streamId,
                response.getSequence(),
                Instant.ofEpochMilli(response.getTimestampMillis()),
                detections,
                Duration.ofMillis(response.getInferenceMillis()));
    }

    private static Detection toDetection(com.drones.vision.proto.v1.Detection wire, ModelRef model) {
        var wireBox = wire.getBox();
        BoundingBox box = new BoundingBox(wireBox.getX(), wireBox.getY(), wireBox.getWidth(), wireBox.getHeight());
        return new Detection(wire.getLabel(), wire.getConfidence(), box, model);
    }

    /**
     * One open {@code DetectStream} call for a single {@link StreamId}:
     * owns the request observer, the write lock guarding it, and the
     * sequence&rarr;future correlation map for responses still in flight.
     */
    private final class StreamSession {

        private final StreamId streamId;
        private final Object writeLock = new Object();
        private final ConcurrentHashMap<Long, CompletableFuture<DetectionResult>> pending = new ConcurrentHashMap<>();

        /**
         * Guards session teardown so it runs exactly once, however it is
         * triggered (transport {@code onError}/{@code onCompleted}, a pending
         * future's own response timeout, or an explicit {@link #streamEnded}/
         * {@link #close()}) — these race genuinely (e.g. a timeout firing on
         * one gRPC executor thread just as {@code onError} lands on another),
         * so whichever gets here first tears the session down and every
         * other trigger becomes a no-op instead of double-failing futures or
         * double-cancelling the call.
         */
        private final AtomicBoolean torndown = new AtomicBoolean(false);

        private volatile StreamObserver<FrameRequest> requestObserver;

        StreamSession(StreamId streamId) {
            this.streamId = streamId;
        }

        CompletionStage<DetectionResult> send(long sequence, FrameRequest request) {
            CompletableFuture<DetectionResult> future = new CompletableFuture<>();
            pending.put(sequence, future);
            future.orTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            future.whenComplete((result, error) -> {
                pending.remove(sequence, future);
                if (error instanceof TimeoutException timeout) {
                    onResponseTimeout(sequence, timeout);
                }
            });

            synchronized (writeLock) {
                try {
                    openIfNeeded().onNext(request);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING,
                            () -> "Failed to send frame " + sequence + " on detection stream " + streamId, e);
                    failAllAndDrop(e);
                }
            }
            return future;
        }

        /** Must be called while holding {@link #writeLock}. */
        private StreamObserver<FrameRequest> openIfNeeded() {
            StreamObserver<FrameRequest> observer = requestObserver;
            if (observer == null) {
                LOG.log(System.Logger.Level.INFO, () -> "Opening detection stream for " + streamId);
                observer = asyncStub.detectStream(new ResponseHandler());
                requestObserver = observer;
            }
            return observer;
        }

        private void onResponse(DetectionResponse response) {
            CompletableFuture<DetectionResult> future = pending.remove(response.getSequence());
            if (future == null) {
                return; // already timed out, or an unrecognized/late sequence -- nothing to complete
            }
            try {
                future.complete(toDetectionResult(streamId, response));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }

        private void onTransportError(Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Detection stream for " + streamId + " failed; will reopen on next detect()", t);
            failAllAndDrop(t);
        }

        private void onServerCompleted() {
            failAllAndDrop(new IllegalStateException("Detection stream for " + streamId + " completed unexpectedly"));
        }

        /**
         * A pending future's own {@value #RESPONSE_TIMEOUT_SECONDS}s response
         * timeout fired. A live, healthy session always gets either a
         * response or a transport {@code onError} well within that window, so
         * this means the session itself is presumed dead — most notably the
         * case a session opened while the CV service was down entirely: the
         * call can sit half-open with no {@code onError} ever delivered (no
         * response, no transport signal at all), so without this the session
         * would never be dropped and every future probe would keep reusing
         * the same dead call. Treated exactly like a transport error: the
         * call is cancelled and every other still-pending future for this
         * stream fails immediately instead of waiting out its own timer.
         */
        private void onResponseTimeout(long sequence, TimeoutException cause) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Detection stream for " + streamId + " timed out waiting for a response to frame "
                            + sequence + "; treating session as dead and reopening on next detect()");
            if (failAllAndDrop(cause)) {
                cancelCall(cause);
            }
        }

        /**
         * Fails every still-pending future and drops this session so the next
         * {@code detect()} reopens, exactly once (see {@link #torndown}).
         *
         * @return {@code true} if this call actually performed the teardown
         * (i.e. it won the race); {@code false} if the session was already
         * torn down by another trigger, in which case there is nothing left
         * to do.
         */
        private boolean failAllAndDrop(Throwable cause) {
            if (!torndown.compareAndSet(false, true)) {
                return false;
            }
            sessions.remove(streamId, this);
            pending.values().forEach(future -> future.completeExceptionally(cause));
            return true;
        }

        /** Best-effort: cancels the underlying call so a dead/hung transport is discarded instead of lingering. */
        private void cancelCall(Throwable cause) {
            StreamObserver<FrameRequest> observer = requestObserver;
            if (observer instanceof ClientCallStreamObserver<FrameRequest> clientCallObserver) {
                try {
                    clientCallObserver.cancel("Detection stream for " + streamId + " timed out", cause);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "Ignoring error cancelling detection stream for " + streamId, e);
                }
            }
        }

        /** Fails pending futures, half-closes the request observer, and drops this session. Idempotent. */
        void endAndClose() {
            if (!torndown.compareAndSet(false, true)) {
                return;
            }
            sessions.remove(streamId, this);
            pending.values().forEach(future -> future.completeExceptionally(
                    new CancellationException("Detection stream for " + streamId + " ended")));
            StreamObserver<FrameRequest> observer = requestObserver;
            if (observer != null) {
                try {
                    observer.onCompleted();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "Ignoring error half-closing detection stream for " + streamId, e);
                }
            }
        }

        private final class ResponseHandler implements StreamObserver<DetectionResponse> {
            @Override
            public void onNext(DetectionResponse response) {
                onResponse(response);
            }

            @Override
            public void onError(Throwable t) {
                onTransportError(t);
            }

            @Override
            public void onCompleted() {
                onServerCompleted();
            }
        }
    }
}
