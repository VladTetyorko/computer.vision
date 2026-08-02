package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.DatasetUpload;
import com.drones.vision.domain.port.out.DatasetUploadPort.ExportEntry;
import com.drones.vision.proto.v1.DatasetChunk;
import com.drones.vision.proto.v1.TrainingGrpc;
import com.drones.vision.proto.v1.UploadAck;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcDatasetUploadPortTest {

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

    private GrpcDatasetUploadPort newPort(TrainingGrpc.TrainingImplBase service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcDatasetUploadPort(channel, GrpcCvSettings.defaults());
    }

    @Test
    void uploadFramesTheArchiveWithTheExactExpectedZipLayout() throws Exception {
        RecordingUploadServicer servicer = new RecordingUploadServicer(Outcome.SUCCESS, "");
        GrpcDatasetUploadPort port = newPort(servicer);
        DatasetId datasetId = DatasetId.random();

        // One small entry, and one large enough to span several 256 KiB chunks, so both the
        // per-entry naming and the chunk-boundary framing are exercised in a single archive.
        // Random (not patterned) bytes so the zip's DEFLATE compression can't shrink it back
        // under one chunk.
        byte[] smallImage = "small-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] bigImage = new byte[GrpcCvSettings.CHUNK_BYTES * 3 + 12_345];
        new java.util.Random(42).nextBytes(bigImage);
        List<ExportEntry> entries = List.of(
                new ExportEntry("frame-001.jpg", smallImage, "0 0.5 0.5 0.2 0.2\n"),
                new ExportEntry("frame-002.png", bigImage, ""));
        String dataYaml = "names: [building]\nnc: 1\ntrain: images\nval: images\n";

        DatasetUpload result = port.upload(datasetId, dataYaml, entries);

        assertEquals(1, servicer.callCount.get(), "one RPC per upload() call");
        assertEquals(1, servicer.receivedArchives.size());
        assertTrue(servicer.chunkCount.get() > 1,
                "the big entry alone should span more than one " + GrpcCvSettings.CHUNK_BYTES
                        + "-byte chunk; sent " + servicer.chunkCount.get());
        for (String seenDatasetId : servicer.seenDatasetIds) {
            assertEquals(datasetId.value().toString(), seenDatasetId, "dataset_id must be identical on every chunk");
        }

        Map<String, byte[]> zipEntries = unzip(servicer.receivedArchives.get(0));
        assertEquals(List.of("data.yaml", "images/frame-001.jpg", "labels/frame-001.txt",
                "images/frame-002.png", "labels/frame-002.txt"), new ArrayList<>(zipEntries.keySet()),
                "entries must appear in this exact order: data.yaml, then images/+labels/ per input entry");
        assertArrayEquals(dataYaml.getBytes(StandardCharsets.UTF_8), zipEntries.get("data.yaml"));
        assertArrayEquals(smallImage, zipEntries.get("images/frame-001.jpg"));
        assertArrayEquals("0 0.5 0.5 0.2 0.2\n".getBytes(StandardCharsets.UTF_8), zipEntries.get("labels/frame-001.txt"));
        assertArrayEquals(bigImage, zipEntries.get("images/frame-002.png"));
        assertArrayEquals(new byte[0], zipEntries.get("labels/frame-002.txt"));

        assertEquals(datasetId, result.datasetId());
        assertEquals(2, result.sampleCount());
        assertEquals(servicer.receivedArchives.get(0).length, result.sizeBytes(),
                "sizeBytes should come from UploadAck.bytesReceived");
    }

    @Test
    void uploadThrowsIllegalStateExceptionWhenCvServiceRejectsTheDataset() throws Exception {
        GrpcDatasetUploadPort port = newPort(new RecordingUploadServicer(Outcome.REJECTED, "corrupt archive"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> port.upload(DatasetId.random(), "names: []\nnc: 0\n", List.of(oneEntry())));
        assertTrue(ex.getMessage().contains("corrupt archive"),
                "message should surface cv-service's refusal reason: " + ex.getMessage());
    }

    @Test
    void uploadPropagatesTransportFailureUnwrapped() throws Exception {
        GrpcDatasetUploadPort port = newPort(new RecordingUploadServicer(Outcome.ERROR, "simulated cv-service crash"));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> port.upload(DatasetId.random(), "names: []\nnc: 0\n", List.of(oneEntry())));
        assertEquals(Status.Code.INTERNAL, ex.getStatus().getCode());
    }

    @Test
    void uploadIsNotCachedOrDeduplicatedOnTheClient() throws Exception {
        RecordingUploadServicer servicer = new RecordingUploadServicer(Outcome.SUCCESS, "");
        GrpcDatasetUploadPort port = newPort(servicer);
        DatasetId datasetId = DatasetId.random();
        String dataYaml = "names: []\nnc: 0\n";
        List<ExportEntry> entries = List.of(oneEntry());

        port.upload(datasetId, dataYaml, entries);
        port.upload(datasetId, dataYaml, entries);

        assertEquals(2, servicer.callCount.get(),
                "re-uploading the same dataset id must issue a fresh RPC every time -- idempotency is the server's job");
    }

    @Test
    void constructorRejectsNullChannel() {
        assertThrows(NullPointerException.class, () -> new GrpcDatasetUploadPort(null, GrpcCvSettings.defaults()));
    }

    @Test
    void constructorRejectsNullSettings() {
        ManagedChannel channel = InProcessChannelBuilder.forName(InProcessServerBuilder.generateName()).build();
        try {
            assertThrows(NullPointerException.class, () -> new GrpcDatasetUploadPort(channel, null));
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void uploadRejectsNullArguments() throws Exception {
        GrpcDatasetUploadPort port = newPort(new RecordingUploadServicer(Outcome.SUCCESS, ""));
        List<ExportEntry> entries = List.of(oneEntry());

        assertThrows(NullPointerException.class, () -> port.upload(null, "names: []\nnc: 0\n", entries));
        assertThrows(NullPointerException.class, () -> port.upload(DatasetId.random(), null, entries));
        assertThrows(NullPointerException.class, () -> port.upload(DatasetId.random(), "names: []\nnc: 0\n", null));
    }

    private static ExportEntry oneEntry() {
        return new ExportEntry("frame-001.jpg", "bytes".getBytes(StandardCharsets.UTF_8), "");
    }

    /** Reassembles a zip archive's entries into name -> content, preserving encounter order. */
    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private enum Outcome { SUCCESS, REJECTED, ERROR }

    /**
     * Captures every chunk of every {@code UploadDataset} call this test's port issues, and
     * answers with a configurable {@link UploadAck} (or a transport error) once the client
     * half-closes.
     */
    private static final class RecordingUploadServicer extends TrainingGrpc.TrainingImplBase {

        final AtomicInteger callCount = new AtomicInteger();
        final AtomicInteger chunkCount = new AtomicInteger();
        final List<byte[]> receivedArchives = Collections.synchronizedList(new ArrayList<>());
        final List<String> seenDatasetIds = Collections.synchronizedList(new ArrayList<>());

        private final Outcome outcome;
        private final String message;

        RecordingUploadServicer(Outcome outcome, String message) {
            this.outcome = outcome;
            this.message = message;
        }

        @Override
        public StreamObserver<DatasetChunk> uploadDataset(StreamObserver<UploadAck> responseObserver) {
            callCount.incrementAndGet();
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            AtomicReference<String> datasetId = new AtomicReference<>();

            return new StreamObserver<>() {
                @Override
                public void onNext(DatasetChunk chunk) {
                    chunkCount.incrementAndGet();
                    datasetId.set(chunk.getDatasetId());
                    seenDatasetIds.add(chunk.getDatasetId());
                    try {
                        archive.write(chunk.getContent().toByteArray());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // client-initiated abort; none of these tests exercise it.
                }

                @Override
                public void onCompleted() {
                    receivedArchives.add(archive.toByteArray());
                    switch (outcome) {
                        case SUCCESS -> {
                            responseObserver.onNext(UploadAck.newBuilder()
                                    .setOk(true)
                                    .setMessage("dataset ready")
                                    .setDatasetId(datasetId.get() == null ? "" : datasetId.get())
                                    .setBytesReceived(archive.size())
                                    .setFileCount(0)
                                    .build());
                            responseObserver.onCompleted();
                        }
                        case REJECTED -> {
                            responseObserver.onNext(UploadAck.newBuilder()
                                    .setOk(false)
                                    .setMessage(message)
                                    .build());
                            responseObserver.onCompleted();
                        }
                        case ERROR -> responseObserver.onError(
                                Status.INTERNAL.withDescription(message).asRuntimeException());
                    }
                }
            };
        }
    }
}
