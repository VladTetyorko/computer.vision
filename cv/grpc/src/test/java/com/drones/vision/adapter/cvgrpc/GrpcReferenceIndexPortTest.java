package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.RegionBounds;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.Tile;
import com.drones.vision.perception.domain.model.TileCoordinate;
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
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link GrpcReferenceIndexPort} against an in-process {@code Geolocation/BuildReferenceIndex}
 * /{@code ListRegions}/{@code DeleteRegion} fake.
 */
class GrpcReferenceIndexPortTest {

    private static final int AWAIT_SECONDS = 5;

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private GrpcReferenceIndexPort newPort(BindableService service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        GeoUploadSettings settings = new GeoUploadSettings(Duration.ofSeconds(10), 64);
        return new GrpcReferenceIndexPort(channel, settings);
    }

    private static RegionIngestSpec spec() {
        return new RegionIngestSpec("kyiv-pozniaky", "Kyiv Pozniaky", new RegionBounds(50.5, 50.4, 30.7, 30.6), 17);
    }

    private static Tile tile(int z, int x, int y, String content) {
        return new Tile(new TileCoordinate(z, x, y), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // -- build(): non-blocking, returns immediately -----------------------------------------------

    @Test
    void buildReturnsImmediatelyWithoutWaitingForTheServer() throws Exception {
        NeverRespondingServicer servicer = new NeverRespondingServicer();
        GrpcReferenceIndexPort port = newPort(servicer);

        long start = System.nanoTime();
        Flow.Publisher<IngestProgress> publisher = port.build(spec(), List.of(tile(17, 1, 1, "a")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 1000, "build() must return immediately, took " + elapsedMillis + "ms");
        assertTrue(publisher != null);
    }

    // -- build(): archive framing -- region.json + tiles/<z>_<x>_<y>.jpg, in order ----------------

    @Test
    void buildFramesTheArchiveAsRegionJsonThenTilesInIterationOrder() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcReferenceIndexPort port = newPort(servicer);

        RegionIngestSpec spec = spec();
        List<Tile> tiles = List.of(tile(17, 1, 1, "AAAA"), tile(17, 1, 2, "BBBB"));
        CollectingSubscriber subscriber = new CollectingSubscriber();
        port.build(spec, tiles).subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        servicer.awaitCompleted(AWAIT_SECONDS);

        byte[] archive = servicer.assembledArchive();
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertEquals(List.of("region.json", "tiles/17_1_1.jpg", "tiles/17_1_2.jpg"), entryNames);
        assertTrue(servicer.assembledRegionJson().contains("\"region_id\":\"kyiv-pozniaky\""));
        assertTrue(servicer.assembledRegionJson().contains("\"name\":\"Kyiv Pozniaky\""));
    }

    // -- build(): progress delivered as it arrives, publisher completes normally on SUCCEEDED -----

    @Test
    void buildDeliversEveryProgressUpdateAndCompletesNormallyOnSucceeded() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        servicer.progressToSendAfterCompletion(List.of(
                progress("receiving", 0, 2, JobState.RUNNING, ""),
                progress("encoding", 2, 2, JobState.RUNNING, ""),
                terminalSucceeded()));
        GrpcReferenceIndexPort port = newPort(servicer);

        CollectingSubscriber subscriber = new CollectingSubscriber();
        port.build(spec(), List.of(tile(17, 1, 1, "a"))).subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(subscriber.error.get(), "expected a normal completion, not onError");
        assertEquals(3, subscriber.items.size());
        assertEquals("receiving", subscriber.items.get(0).phase());
        assertEquals(IngestState.SUCCEEDED, subscriber.items.get(2).state());
    }

    @Test
    void buildDeliversATerminalFailedUpdateAsDataNotAnException() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        servicer.progressToSendAfterCompletion(List.of(
                progress("receiving", 0, 0, JobState.RUNNING, ""),
                progress("extracting", 0, 0, JobState.FAILED, "zip-slip entry rejected")));
        GrpcReferenceIndexPort port = newPort(servicer);

        CollectingSubscriber subscriber = new CollectingSubscriber();
        port.build(spec(), List.of(tile(17, 1, 1, "a"))).subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(subscriber.error.get(), "a content-level FAILED must complete the publisher normally, not onError");
        IngestProgress last = subscriber.items.get(subscriber.items.size() - 1);
        assertEquals(IngestState.FAILED, last.state());
        assertEquals("zip-slip entry rejected", last.message());
    }

    // -- build(): transport failure -> closeExceptionally -------------------------------------------

    @Test
    void buildTransportFailureSurfacesAsOnError() throws Exception {
        UnavailableServicer servicer = new UnavailableServicer();
        GrpcReferenceIndexPort port = newPort(servicer);

        CollectingSubscriber subscriber = new CollectingSubscriber();
        port.build(spec(), List.of(tile(17, 1, 1, "a"))).subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, subscriber.error.get());
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    // -- list() -----------------------------------------------------------------------------------

    @Test
    void listMapsEveryFieldOfRegionInfoAndItsNestedStats() throws Exception {
        RegionInfo info = RegionInfo.newBuilder()
                .setRegionId("kyiv-pozniaky")
                .setName("Kyiv Pozniaky")
                .setZoom(17)
                .setNorth(50.5).setSouth(50.4).setEast(30.7).setWest(30.6)
                .setBuiltAtMillis(1000)
                .setStats(ReferenceIndexStats.newBuilder()
                        .setTileCount(120)
                        .setDescriptorCount(4800)
                        .setDescriptorDim(256)
                        .setEncoderId("dinov2-small")
                        .setAcceptSimilarity(0.82f)
                        .setAcceptMargin(0.1f)
                        .setHoldoutRecallAt1(0.91f)
                        .setHoldoutMedianErrorMeters(6.5f)
                        .setIndexBytes(1_048_576)
                        .setNeverAcceptCells(3)
                        .build())
                .build();
        ListRegionsServicer servicer = new ListRegionsServicer(RegionList.newBuilder().addRegions(info).build());
        GrpcReferenceIndexPort port = newPort(servicer);

        List<ReferenceIndexSummary> summaries = port.list();

        assertEquals(1, summaries.size());
        ReferenceIndexSummary summary = summaries.get(0);
        assertEquals("kyiv-pozniaky", summary.regionId());
        assertEquals("Kyiv Pozniaky", summary.name());
        assertEquals(17, summary.zoom());
        assertEquals(50.5, summary.bounds().north(), 1e-9);
        assertEquals(120, summary.tileCount());
        assertEquals(4800, summary.descriptorCount());
        assertEquals(256, summary.descriptorDim());
        assertEquals("dinov2-small", summary.encoderId());
        assertEquals(3, summary.neverAcceptCells());
    }

    @Test
    void listPropagatesATransportFailure() throws Exception {
        GrpcReferenceIndexPort port = newPort(new FailingControlPlaneServicer());

        assertThrows(StatusRuntimeException.class, port::list);
    }

    // -- delete() ---------------------------------------------------------------------------------

    @Test
    void deleteSucceedsWhenTheServerAcksOk() throws Exception {
        DeleteRegionServicer servicer = new DeleteRegionServicer(true, "");
        GrpcReferenceIndexPort port = newPort(servicer);

        port.delete("kyiv-pozniaky");

        assertEquals("kyiv-pozniaky", servicer.lastRequestedRegionId());
    }

    @Test
    void deleteThrowsIllegalStateExceptionWhenTheServerRefuses() throws Exception {
        DeleteRegionServicer servicer = new DeleteRegionServicer(false, "region does not exist");
        GrpcReferenceIndexPort port = newPort(servicer);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> port.delete("ghost-region"));
        assertTrue(ex.getMessage().contains("region does not exist"));
    }

    @Test
    void deletePropagatesATransportFailure() throws Exception {
        GrpcReferenceIndexPort port = newPort(new FailingControlPlaneServicer());

        assertThrows(StatusRuntimeException.class, () -> port.delete("kyiv-pozniaky"));
    }

    // -- helpers ------------------------------------------------------------------------------------

    private static ReferenceIndexProgress progress(String phase, int done, int total, JobState state,
            String message) {
        return ReferenceIndexProgress.newBuilder()
                .setRegionId("kyiv-pozniaky")
                .setPhase(phase)
                .setDone(done)
                .setTotal(total)
                .setState(state)
                .setMessage(message)
                .build();
    }

    private static ReferenceIndexProgress terminalSucceeded() {
        return ReferenceIndexProgress.newBuilder()
                .setRegionId("kyiv-pozniaky")
                .setPhase("done")
                .setDone(1)
                .setTotal(1)
                .setState(JobState.SUCCEEDED)
                .setMessage("")
                .setStats(ReferenceIndexStats.newBuilder().setTileCount(1).build())
                .build();
    }

    // -- test servicers -------------------------------------------------------------------------

    /** Records the assembled archive bytes and answers with a fixed progress sequence after the client half-closes. */
    private static class RecordingServicer extends GeolocationGrpc.GeolocationImplBase {
        private final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        private volatile String regionId;
        private volatile List<ReferenceIndexProgress> progressToSend = List.of(terminalSucceeded());
        private final CountDownLatch completed = new CountDownLatch(1);

        void progressToSendAfterCompletion(List<ReferenceIndexProgress> progress) {
            this.progressToSend = progress;
        }

        byte[] assembledArchive() {
            return archive.toByteArray();
        }

        String assembledRegionJson() throws Exception {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(assembledArchive()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals("region.json")) {
                        return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            throw new AssertionError("no region.json entry found in the assembled archive");
        }

        void awaitCompleted(int seconds) throws InterruptedException {
            assertTrue(completed.await(seconds, TimeUnit.SECONDS), "expected the client to half-close");
        }

        @Override
        public StreamObserver<ReferencePackChunk> buildReferenceIndex(
                StreamObserver<ReferenceIndexProgress> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(ReferencePackChunk value) {
                    regionId = value.getRegionId();
                    try {
                        value.getContent().writeTo(archive);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // unused
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                    for (ReferenceIndexProgress progress : progressToSend) {
                        responseObserver.onNext(progress);
                    }
                    responseObserver.onCompleted();
                }
            };
        }
    }

    /** Never sends a response -- used only to prove {@code build()} returns before any server interaction. */
    private static final class NeverRespondingServicer extends GeolocationGrpc.GeolocationImplBase {
        @Override
        public StreamObserver<ReferencePackChunk> buildReferenceIndex(
                StreamObserver<ReferenceIndexProgress> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(ReferencePackChunk value) {
                    // never respond
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }
    }

    /** Fails the call with UNAVAILABLE as soon as the first chunk arrives. */
    private static final class UnavailableServicer extends GeolocationGrpc.GeolocationImplBase {
        @Override
        public StreamObserver<ReferencePackChunk> buildReferenceIndex(
                StreamObserver<ReferenceIndexProgress> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(ReferencePackChunk value) {
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("simulated: cv-service unreachable").asRuntimeException());
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }
    }

    private static final class ListRegionsServicer extends GeolocationGrpc.GeolocationImplBase {
        private final RegionList response;

        ListRegionsServicer(RegionList response) {
            this.response = response;
        }

        @Override
        public void listRegions(com.google.protobuf.Empty request, StreamObserver<RegionList> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private static final class DeleteRegionServicer extends GeolocationGrpc.GeolocationImplBase {
        private final boolean ok;
        private final String message;
        private volatile String lastRequestedRegionId;

        DeleteRegionServicer(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        String lastRequestedRegionId() {
            return lastRequestedRegionId;
        }

        @Override
        public void deleteRegion(RegionRef request, StreamObserver<Ack> responseObserver) {
            lastRequestedRegionId = request.getRegionId();
            responseObserver.onNext(Ack.newBuilder().setOk(ok).setMessage(message).build());
            responseObserver.onCompleted();
        }
    }

    /** Fails every control-plane RPC with UNAVAILABLE. */
    private static final class FailingControlPlaneServicer extends GeolocationGrpc.GeolocationImplBase {
        @Override
        public void listRegions(com.google.protobuf.Empty request, StreamObserver<RegionList> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
        }

        @Override
        public void deleteRegion(RegionRef request, StreamObserver<Ack> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
        }
    }

    /** Collects every delivered {@code IngestProgress} and the terminal signal, requesting unbounded demand. */
    private static final class CollectingSubscriber implements Flow.Subscriber<IngestProgress> {
        final List<IngestProgress> items = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(IngestProgress item) {
            items.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }
    }
}
