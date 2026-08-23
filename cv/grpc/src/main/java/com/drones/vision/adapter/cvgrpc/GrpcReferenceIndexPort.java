package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.RegionBounds;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.Tile;
import com.drones.vision.perception.domain.model.TileCoordinate;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;
import com.drones.vision.proto.v1.Ack;
import com.drones.vision.proto.v1.GeolocationGrpc;
import com.drones.vision.proto.v1.JobState;
import com.drones.vision.proto.v1.ReferenceIndexProgress;
import com.drones.vision.proto.v1.ReferenceIndexStats;
import com.drones.vision.proto.v1.ReferencePackChunk;
import com.drones.vision.proto.v1.RegionInfo;
import com.drones.vision.proto.v1.RegionList;
import com.drones.vision.proto.v1.RegionRef;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link ReferenceIndexPort} over the generated {@code Geolocation/BuildReferenceIndex} (bidi
 * streaming), {@code Geolocation/ListRegions} and {@code Geolocation/DeleteRegion} gRPC RPCs
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md &sect;3.1, D10, H3) — the region-ingest half of this
 * module's geolocation client. Sibling of {@link GrpcPulledGeolocationPort} (the {@code
 * LocalizeStream} half); the two never share state.
 *
 * <h2>Channel sharing (D3)</h2>
 * Takes a pre-built {@link ManagedChannel} — the same one {@link GrpcDetectionPort}/{@link
 * GrpcPulledGeolocationPort} already use — and never closes it, exactly like every other port in
 * this module.
 *
 * <h2>{@link #build}: non-blocking, per {@link ReferenceIndexPort}'s own contract</h2>
 * Unlike the harvested {@code feat/visual-geo} branch's {@code build(...)} (which blocked the
 * calling thread for the whole build), the current {@link ReferenceIndexPort#build} contract
 * requires an <em>immediate</em> return of a live {@link Flow.Publisher}. This class satisfies that
 * by doing every bit of archive-framing/upload/response-handling work on one dedicated {@link
 * Thread#ofVirtual() virtual thread} — the same one-off-background-work idiom {@code
 * DefaultStreamService}/{@code UsageTracker}/{@code DefaultDiscoveryService} already use elsewhere
 * in this codebase — while {@link #build} itself only starts that thread and hands back the {@link
 * SubmissionPublisher} it will report progress on.
 *
 * <h2>{@link #build}: framing — never fully materialized</h2>
 * A {@link ZipOutputStream} is built directly over a private {@link ChunkingOutputStream} that
 * buffers writes into a {@link GeoUploadSettings#uploadChunkBytes()}-sized (256 KiB default) array
 * and emits one {@link ReferencePackChunk} onto the request observer every time it fills, plus a
 * final (possibly smaller) chunk on close — so a large region's tile set is never held whole in
 * memory or on disk, only this one rolling buffer. {@code tiles} is consumed lazily, one {@link
 * Tile} at a time (the port's own contract: "may be a lazily fetched sequence... must not assume it
 * can be iterated twice"), so a caller that fetches each tile on demand (in practice, {@code
 * DefaultReferenceRegionService} composing {@code ReferenceTileSourcePort#fetch}) never needs to
 * hold more than one tile's bytes at once either. Zip entries, in this exact order: {@link
 * #MANIFEST_ENTRY} ({@code region.json}, built by {@link #manifestJson}), then {@code
 * tiles/<z>_<x>_<y>.jpg} per {@link Tile} in iteration order (the wire contract's own comment on
 * {@link ReferencePackChunk#getContent()}: "region.json + tiles/&lt;z&gt;_&lt;x&gt;_&lt;y&gt;.jpg").
 *
 * <h2>{@code region.json}'s content — a documented decision, not a frozen schema</h2>
 * Neither the plan nor the proto names a field-level schema for {@code region.json}; cv-service's
 * own landing step ({@code cv_service/geo/pack.py#land_pack}) validates it only as syntactically
 * valid JSON, and its only confirmed reader ({@code servicers.py#_region_info}, backing {@code
 * ListRegions}) reads just {@code name} (falling back to the region id when absent) — {@code zoom}/
 * bounds for {@code ListRegions}/{@code localize.py} come from a separately cv-service-built {@code
 * tiles.json}, not from this file. {@link #manifestJson} therefore writes {@code region_id}, {@code
 * name}, {@code zoom} and {@code bounds} from {@link RegionIngestSpec} — a superset of the
 * confirmed-required {@code name} — purely for forward-compatibility and on-disk diagnostics (an
 * operator inspecting a landed pack's {@code region.json} directly); no other Java or Python code
 * path in this codebase depends on the extra fields being present.
 *
 * <h2>{@link #build}: progress delivery and failure semantics</h2>
 * <ul>
 *   <li>Every {@link ReferenceIndexProgress} the server sends is mapped and {@link
 *   SubmissionPublisher#submit submitted} to the returned publisher as it arrives — blocking
 *   {@code submit}, not a latest-wins {@code offer}, because losing an intermediate phase update is
 *   tolerable but losing the terminal {@code SUCCEEDED}/{@code FAILED} update is not (it is the only
 *   way {@code DefaultReferenceRegionService}'s in-flight job ever learns the build finished).</li>
 *   <li><b>Content-level failure</b> (corrupt zip, zip-slip, no tiles, encoder failure): cv-service
 *   reports this as a normal terminal {@link ReferenceIndexProgress}{@code {state: FAILED, ...}} —
 *   delivered to the publisher like any other progress item, then the publisher completes normally
 *   ({@link SubmissionPublisher#close()}), exactly matching {@link
 *   com.drones.vision.perception.domain.model.IngestState}'s own "terminal FAILED is data, not an
 *   exception" contract.</li>
 *   <li><b>Transport failure</b> (server dies, connection breaks, deadline exceeded) or a local
 *   archive-framing failure (an {@link IOException} while zipping, or the caller's {@code tiles}
 *   iterator itself throwing — e.g. a tile source's {@code fetch} throwing on a missing tile):
 *   {@link SubmissionPublisher#closeExceptionally}, and the request stream is told {@code onError}
 *   too so cv-service does not keep waiting on a call the client has already abandoned.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. {@link #build} itself never blocks; the background virtual thread it
 * starts runs for the build's lifetime (potentially minutes). {@link #list}/{@link #delete} block
 * only for one control-plane RPC each, bounded by {@link #CALL_TIMEOUT_SECONDS}. Safe for
 * concurrent use from multiple threads building/listing/deleting different regions; a single {@link
 * #build} call is not itself meant to be invoked concurrently for the same region id.
 */
public final class GrpcReferenceIndexPort implements ReferenceIndexPort {

    private static final System.Logger LOG = System.getLogger(GrpcReferenceIndexPort.class.getName());

    private static final String MANIFEST_ENTRY = "region.json";
    private static final String TILES_PREFIX = "tiles/";
    private static final String TILE_EXTENSION = ".jpg";

    /**
     * Per-call deadline for the control-plane RPCs ({@link #list()}/{@link #delete(String)}) —
     * mirrors {@code GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS}: infrequent control-plane
     * operations, not the streaming build itself (that uses {@link GeoUploadSettings#uploadTimeout()}
     * instead), deliberately not a {@link GeoUploadSettings} field for the same reason that
     * constant's own javadoc gives — the point is only to guarantee the calling thread is never
     * blocked indefinitely by an unreachable or hung cv-service.
     */
    static final long CALL_TIMEOUT_SECONDS = 10;

    private final GeolocationGrpc.GeolocationStub asyncStub;
    private final GeolocationGrpc.GeolocationBlockingStub blockingStub;
    private final GeoUploadSettings settings;

    /**
     * @param channel  a channel already open to cv-service — typically the same one {@link
     *                 GrpcDetectionPort} built; never closed by this class
     * @param settings supplies {@link GeoUploadSettings#uploadTimeout()} (this class's {@link
     *                 #build} deadline) and {@link GeoUploadSettings#uploadChunkBytes()} (the
     *                 zip-chunk framing size)
     * @throws NullPointerException if either argument is {@code null}
     */
    public GrpcReferenceIndexPort(ManagedChannel channel, GeoUploadSettings settings) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.asyncStub = GeolocationGrpc.newStub(channel);
        this.blockingStub = GeolocationGrpc.newBlockingStub(channel);
    }

    @Override
    public Flow.Publisher<IngestProgress> build(RegionIngestSpec spec, Iterable<Tile> tiles) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(tiles, "tiles must not be null");

        SubmissionPublisher<IngestProgress> publisher = new SubmissionPublisher<>();
        Thread.ofVirtual().name("geo-index-build-" + spec.regionId()).start(() -> runBuild(spec, tiles, publisher));
        return publisher;
    }

    private void runBuild(RegionIngestSpec spec, Iterable<Tile> tiles, SubmissionPublisher<IngestProgress> publisher) {
        String id = spec.regionId();
        LOG.log(System.Logger.Level.INFO, () -> "Building reference index for region " + id);

        ProgressObserver responseObserver = new ProgressObserver(id, publisher);
        StreamObserver<ReferencePackChunk> requestObserver = asyncStub
                .withDeadlineAfter(settings.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .buildReferenceIndex(responseObserver);

        try (ChunkingOutputStream chunker = new ChunkingOutputStream(id, requestObserver, settings.uploadChunkBytes());
             ZipOutputStream zip = new ZipOutputStream(chunker)) {
            writeEntry(zip, MANIFEST_ENTRY, manifestJson(spec).getBytes(StandardCharsets.UTF_8));
            for (Tile tile : tiles) {
                TileCoordinate c = tile.coordinate();
                writeEntry(zip, TILES_PREFIX + c.z() + "_" + c.x() + "_" + c.y() + TILE_EXTENSION, tile.content());
            }
        } catch (IOException e) {
            failArchive(id, requestObserver, publisher, new UncheckedIOException(
                    "Failed to build the reference pack archive for region " + id, e));
            return;
        } catch (RuntimeException e) {
            // Most likely the caller's lazy tiles iterator throwing (e.g. a tile source's fetch()
            // failing) -- see class javadoc "failure semantics".
            failArchive(id, requestObserver, publisher, e);
            return;
        }

        try {
            requestObserver.onCompleted();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Failed to half-close the reference index build stream for region " + id, e);
            publisher.closeExceptionally(e);
        }
        // The response side (ProgressObserver) owns closing the publisher from here -- either
        // normally, once the server's own terminal progress+onCompleted arrive, or exceptionally on
        // a transport onError. This thread's job (framing and sending the archive) is done.
    }

    /** Tells the server we're abandoning the call, then fails the publisher. Called from {@link #runBuild}'s catch. */
    private void failArchive(String regionId, StreamObserver<ReferencePackChunk> requestObserver,
            SubmissionPublisher<IngestProgress> publisher, RuntimeException cause) {
        LOG.log(System.Logger.Level.WARNING,
                () -> "Failed to build the reference pack archive for region " + regionId, cause);
        try {
            requestObserver.onError(cause);
        } catch (RuntimeException ignored) {
            // The call may already be dead (e.g. the same failure already tore down the transport);
            // closeExceptionally below is what actually matters to the caller.
        }
        publisher.closeExceptionally(cause);
    }

    @Override
    public List<ReferenceIndexSummary> list() {
        RegionList response;
        try {
            response = blockingStub().listRegions(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list geo regions from cv-service", e);
            throw e;
        }
        return response.getRegionsList().stream().map(GrpcReferenceIndexPort::toDomainSummary).toList();
    }

    @Override
    public void delete(String regionId) {
        Objects.requireNonNull(regionId, "regionId must not be null");
        RegionRef request = RegionRef.newBuilder().setRegionId(regionId).build();

        Ack ack;
        try {
            ack = blockingStub().deleteRegion(request);
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to delete region " + regionId + " on cv-service", e);
            throw e;
        }
        if (!ack.getOk()) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "cv-service refused to delete region " + regionId + ": " + ack.getMessage());
            throw new IllegalStateException(
                    "cv-service refused to delete region " + regionId + ": " + ack.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, () -> "Deleted region " + regionId + " on cv-service");
    }

    private GeolocationGrpc.GeolocationBlockingStub blockingStub() {
        return blockingStub.withDeadlineAfter(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** See class javadoc "region.json's content" for why these four fields and no others. */
    private static String manifestJson(RegionIngestSpec spec) {
        RegionBounds bounds = spec.bounds();
        return "{"
                + "\"region_id\":\"" + escapeJson(spec.regionId()) + "\","
                + "\"name\":\"" + escapeJson(spec.name()) + "\","
                + "\"zoom\":" + spec.zoom() + ","
                + "\"bounds\":{"
                + "\"north\":" + bounds.north() + ","
                + "\"south\":" + bounds.south() + ","
                + "\"east\":" + bounds.east() + ","
                + "\"west\":" + bounds.west()
                + "}"
                + "}";
    }

    /** Minimal hand-rolled JSON string escaping -- {@code region_id} is kebab-case-validated and never needs
     * this, but {@code name} is free operator-facing text and might contain a quote or backslash. */
    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ReferenceIndexSummary toDomainSummary(RegionInfo info) {
        ReferenceIndexStats stats = info.getStats();
        RegionBounds bounds = new RegionBounds(info.getNorth(), info.getSouth(), info.getEast(), info.getWest());
        return new ReferenceIndexSummary(
                info.getRegionId(), info.getName(), bounds, info.getZoom(),
                Instant.ofEpochMilli(info.getBuiltAtMillis()),
                stats.getTileCount(), stats.getDescriptorCount(), stats.getDescriptorDim(), stats.getEncoderId(),
                stats.getAcceptSimilarity(), stats.getAcceptMargin(),
                stats.getHoldoutRecallAt1(), stats.getHoldoutMedianErrorMeters(),
                stats.getIndexBytes(), stats.getNeverAcceptCells());
    }

    /**
     * {@link IngestProgress#summary()} is deliberately left {@code null} even on the terminal {@code
     * SUCCEEDED} update, despite the wire's own {@link ReferenceIndexProgress#hasStats()} sometimes
     * carrying a populated {@link ReferenceIndexStats}. {@link ReferenceIndexStats} alone has no
     * region identity, bounds, zoom or {@code built_at} of its own (unlike {@link RegionInfo}, which
     * carries all of those) — synthesizing those missing fields here (a fabricated bounding box, a
     * zoom of {@code 0}, "now" as a stand-in {@code built_at}) would be exactly the kind of invented
     * data this codebase's own "no magic numbers/no invented conversion" convention refuses
     * elsewhere (H2a's {@code gps_radius_meters} precedent). {@link ReferenceIndexSummary}'s own
     * contract permits a {@code null} summary even on {@code SUCCEEDED} (only a <em>non</em>-null
     * summary on a non-{@code SUCCEEDED} state is rejected), and the one real consumer today ({@code
     * DefaultReferenceRegionService.ProgressSubscriber}) never reads this field anyway — it drops its
     * in-flight job the instant {@code SUCCEEDED} arrives and defers to {@link #list()} (backed by
     * {@code ListRegions}'s full, authoritative {@link RegionInfo}) for the region's real summary
     * from then on. A future consumer that does want the summary inline on the terminal event should
     * gain that by richening the wire contract (cv-service echoing the full identity on {@code
     * BuildReferenceIndex}'s own terminal message), not by this codec guessing it.
     */
    private static IngestProgress toDomainProgress(String regionId, ReferenceIndexProgress wire) {
        return new IngestProgress(regionId, wire.getPhase(), wire.getDone(), wire.getTotal(),
                toDomainState(wire.getState()), wire.getMessage(), null);
    }

    private static IngestState toDomainState(JobState wire) {
        return switch (wire) {
            case RUNNING -> IngestState.RUNNING;
            case SUCCEEDED -> IngestState.SUCCEEDED;
            case FAILED -> IngestState.FAILED;
            case JOB_STATE_UNSPECIFIED, UNRECOGNIZED -> {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "cv-service sent a JobState this client doesn't recognize (" + wire
                                + "); treating as RUNNING defensively");
                yield IngestState.RUNNING;
            }
        };
    }

    /**
     * The response side of {@code Geolocation/BuildReferenceIndex}'s bidi pair — forwards every
     * {@link ReferenceIndexProgress} to the publisher as it arrives (genuinely bidi: cv-service may
     * still be receiving chunks while it streams progress back), and closes the publisher on the
     * stream's terminal signal. Runs on a gRPC callback thread, never {@link #runBuild}'s own
     * virtual thread.
     */
    private static final class ProgressObserver implements StreamObserver<ReferenceIndexProgress> {

        private final String regionId;
        private final SubmissionPublisher<IngestProgress> publisher;

        ProgressObserver(String regionId, SubmissionPublisher<IngestProgress> publisher) {
            this.regionId = regionId;
            this.publisher = publisher;
        }

        @Override
        public void onNext(ReferenceIndexProgress value) {
            publisher.submit(toDomainProgress(regionId, value));
        }

        @Override
        public void onError(Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Reference-index build stream failed for region " + regionId, t);
            publisher.closeExceptionally(t);
        }

        @Override
        public void onCompleted() {
            LOG.log(System.Logger.Level.INFO, () -> "Reference-index build stream ended for region " + regionId);
            publisher.close();
        }
    }

    /**
     * Batches {@link OutputStream#write} calls into a {@link GeoUploadSettings#uploadChunkBytes()}
     * -byte buffer and emits one {@link ReferencePackChunk} onto the request observer every time it
     * fills, plus a final (possibly smaller) chunk on {@link #close()} — duplicated from {@code
     * adapter-cv-grpc}'s {@code GrpcDatasetUploadPort.ChunkingOutputStream} verbatim (D3: adapters
     * never depend on each other, and both classes live in this same module anyway so nothing is
     * actually shared across a module boundary), generic over {@link ReferencePackChunk} instead of
     * {@code DatasetChunk}. Not thread-safe; used only by the single virtual thread running {@link
     * #runBuild} while it drives the {@link ZipOutputStream} wrapping this stream.
     */
    private static final class ChunkingOutputStream extends OutputStream {

        private final String regionId;
        private final StreamObserver<ReferencePackChunk> requestObserver;
        private final byte[] buffer;
        private int length;

        ChunkingOutputStream(String regionId, StreamObserver<ReferencePackChunk> requestObserver, int chunkBytes) {
            this.regionId = regionId;
            this.requestObserver = requestObserver;
            this.buffer = new byte[chunkBytes];
        }

        @Override
        public void write(int b) {
            buffer[length++] = (byte) b;
            if (length == buffer.length) {
                flush();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int toCopy = Math.min(buffer.length - length, remaining);
                System.arraycopy(b, offset, buffer, length, toCopy);
                length += toCopy;
                offset += toCopy;
                remaining -= toCopy;
                if (length == buffer.length) {
                    flush();
                }
            }
        }

        @Override
        public void flush() {
            if (length == 0) {
                return;
            }
            requestObserver.onNext(ReferencePackChunk.newBuilder()
                    .setRegionId(regionId)
                    .setContent(ByteString.copyFrom(buffer, 0, length))
                    .build());
            length = 0;
        }

        @Override
        public void close() {
            flush();
        }
    }
}
