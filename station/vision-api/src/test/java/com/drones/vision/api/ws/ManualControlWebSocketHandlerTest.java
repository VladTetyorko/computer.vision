package com.drones.vision.api.ws;

import com.drones.vision.api.dto.ManualControlChannelsRequest;
import com.drones.vision.api.dto.ManualControlEngageRequest;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.flight.application.ManualControlService;
import com.drones.vision.flight.application.ManualControlSession;
import com.drones.vision.flight.application.VehicleUnidentifiedException;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.flight.application.WatchdogListener;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.UnidentifiedReason;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ManualControlWebSocketHandler} through the frozen §4 frame protocol
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md) against a hand-fake {@link ManualControlService}/{@link
 * ManualControlSession} and a hand-fake {@link WebSocketSession} — no Spring context, mirroring
 * this module's other pure unit-test controller/handler coverage.
 */
class ManualControlWebSocketHandlerTest {

    private static final long WATCHDOG_TIMEOUT_MS = 300L;
    private static final long ENGAGE_SLOW_THRESHOLD_MS = 2000L;

    private final JsonMapper jsonMapper = new JsonMapper();
    private FakeManualControlService service;
    private ManualControlWebSocketHandler handler;
    private FakeWebSocketSession session;

    @BeforeEach
    void setUp() {
        service = new FakeManualControlService();
        handler = new ManualControlWebSocketHandler(service, WATCHDOG_TIMEOUT_MS, ENGAGE_SLOW_THRESHOLD_MS);
        session = new FakeWebSocketSession();
        session.getAttributes().put(ManualControlHandshakeInterceptor.ATTR_USER_ID, UserId.random());
        session.getAttributes().put(ManualControlHandshakeInterceptor.ATTR_SCOPE, VisibilityScope.unbounded());
    }

    @Test
    void engageHappyPathSendsEngagedFrameWithChannelMap() throws Exception {
        handler.afterConnectionEstablished(session);
        AssetId assetId = AssetId.random();

        handler.handleMessage(session, engageFrame(assetId));

        JsonNode frame = lastFrame();
        assertEquals("engaged", frame.get("type").asString());
        assertEquals(assetId.value().toString(), frame.get("assetId").asString());
        assertTrue(frame.get("channelMap").isArray());

        // The frame describes the vehicle the session is actually engaged to -- a rover here, so
        // channel 1 is steering, not roll, and the label is no longer invented from the channel
        // number (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P5).
        assertEquals("ROVER", frame.get("vehicleKind").asString());
        assertEquals("S-T-", frame.get("profileCode").asString());
        assertEquals("Ground vehicle", frame.get("profileName").asString());

        // Which layout is in force, and whether it is the operator's own or the platform's fallback
        // (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.4): nothing is saved in this fake, so it
        // is the built-in -- and an operator who configured one would see that it is not being used.
        assertEquals("BUILT_IN", frame.get("profileSource").asString());
        assertEquals(ControlProfileId.builtIn(VehicleKind.ROVER).value().toString(),
                frame.get("profileId").asString());
        assertEquals(2, frame.get("channelMap").size());

        JsonNode steering = frame.get("channelMap").get(0);
        assertEquals("AXIS", steering.get("source").asString());
        assertEquals(1, steering.get("rcChannel").asInt());
        assertEquals("STEERING", steering.get("function").asString());
        assertEquals("Steering", steering.get("label").asString());

        JsonNode throttle = frame.get("channelMap").get(1);
        assertEquals("THROTTLE", throttle.get("function").asString());
        assertEquals("CENTERED", throttle.get("travel").asString(),
                "a rover's throttle rests at stop with reverse below it -- 50-0 back, 50-100 forward");
        assertEquals(1500, throttle.get("centerMicros").asInt());
    }

    @Test
    void engagedFrameReportsTheSessionsOwnRateNotAHardcodedDefault() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        assertEquals(FakeManualControlSession.RATE_HZ, lastFrame().get("rateHz").asInt(),
                "rateHz must come from the engaged session (and so from the adapter), not a mirrored constant");
    }

    @Test
    void channelsFrameForwardsToSessionAndAcksWithSeqTSentAndTServer() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        session.sentPayloads.clear();

        handler.handleMessage(session, channelsFrame(42L));

        JsonNode ack = lastFrame();
        assertEquals("ack", ack.get("type").asString());
        assertEquals(42L, ack.get("seq").asLong());
        assertEquals(1_042L, ack.get("tSent").asLong());
        assertTrue(ack.has("tServer"));
        assertEquals(1, service.lastSession.channelCalls.size());
    }

    @Test
    void engageDeniedOutOfScope() throws Exception {
        service.nextEngageFailure = new AccessDeniedException("outside your scope");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("OUT_OF_SCOPE", denied.get("code").asString());
    }

    @Test
    void engageDeniedNotCommandableWhenNoDeviceSupportsManualControl() throws Exception {
        service.nextEngageFailure =
                new IllegalStateException("Asset xyz has no active manual-control-capable device");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("NOT_COMMANDABLE", denied.get("code").asString());
    }

    @Test
    void engageDeniedUnsupportedWhenThePortRefusesTheDevice() throws Exception {
        service.nextEngageFailure =
                new IllegalStateException("MavlinkManualControlSender does not support device: some-device");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("UNSUPPORTED", denied.get("code").asString());
    }

    /**
     * FLEET-RADIO R2: {@link VehicleUnidentifiedException} is caught before the plain {@link
     * IllegalStateException} case above, so it must map to its own dedicated {@code
     * VEHICLE_UNIDENTIFIED} code -- never message-sniffed into {@code NOT_COMMANDABLE}/{@code
     * UNSUPPORTED} the way the other three causes are.
     */
    @Test
    void engageDeniedVehicleUnidentifiedGetsItsOwnCodeNotMessageSniffedIntoOneOfTheOtherThree() throws Exception {
        service.nextEngageFailure = new VehicleUnidentifiedException(UnidentifiedReason.NEVER_IDENTIFIED,
                "Asset xyz could not be identified: this platform has never seen the vehicle type it is "
                        + "reporting. If you can see the vehicle, choose its kind explicitly to proceed. "
                        + "Manual control refused.");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("VEHICLE_UNIDENTIFIED", denied.get("code").asString());
        assertTrue(denied.get("reason").asString().contains("could not be identified"),
                "expected the server-supplied reason rendered verbatim, got: " + denied.get("reason").asString());
    }

    /**
     * Expected result: the operator sees a refusal, not a connection error that looks identical
     * whatever the true cause -- and the three causes must not collapse into one wire message.
     */
    @Test
    void engageDeniedVehicleUnidentifiedCarriesADistinctReasonPerCause() throws Exception {
        handler.afterConnectionEstablished(session);

        service.nextEngageFailure =
                new VehicleUnidentifiedException(UnidentifiedReason.NOT_A_VEHICLE, "is not a vehicle at all");
        handler.handleMessage(session, engageFrame(AssetId.random()));
        String notAVehicleReason = lastFrame().get("reason").asString();
        assertEquals("VEHICLE_UNIDENTIFIED", lastFrame().get("code").asString());

        session.sentPayloads.clear();
        service.nextEngageFailure = new VehicleUnidentifiedException(UnidentifiedReason.UNSUPPORTED_VEHICLE,
                "reports a recognized airframe that this platform does not support");
        handler.handleMessage(session, engageFrame(AssetId.random()));
        String unsupportedReason = lastFrame().get("reason").asString();
        assertEquals("VEHICLE_UNIDENTIFIED", lastFrame().get("code").asString());

        session.sentPayloads.clear();
        service.nextEngageFailure =
                new VehicleUnidentifiedException(UnidentifiedReason.NEVER_IDENTIFIED, "could not be identified");
        handler.handleMessage(session, engageFrame(AssetId.random()));
        String neverIdentifiedReason = lastFrame().get("reason").asString();

        assertNotEquals(notAVehicleReason, unsupportedReason,
                "a gimbal on the link and an unsupported airframe must not read as the same refusal");
        assertNotEquals(notAVehicleReason, neverIdentifiedReason);
        assertNotEquals(unsupportedReason, neverIdentifiedReason);
    }

    /**
     * FLY-CONTROL-UX H1: the catch-all that closes the one gap
     * docs/plans/active/fly-control-ux/R3-handshake-denial.md could not rule out -- an exception type
     * {@link ManualControlService#engage} does not document must still get an honest {@code denied}
     * reply (never silence, which is what let the web client's own 4s {@code ENGAGE_TIMEOUT_MS} fire
     * and misreport a station fault as "the station never confirmed control"), and must be logged
     * with its stack trace for diagnosis, without leaking the exception's own message to the client.
     */
    @Test
    void engageUnexpectedExceptionSendsInternalErrorDeniedFrameAndLogsWarningWithStackTrace() throws Exception {
        service.nextEngageFailure = new IllegalArgumentException("boom -- not one of the three documented types");
        handler.afterConnectionEstablished(session);

        List<LogRecord> records = captureLogRecords(() -> handler.handleMessage(session, engageFrame(AssetId.random())));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("INTERNAL_ERROR", denied.get("code").asString());
        assertTrue(denied.get("reason").asString().contains("IllegalArgumentException"),
                "the client-facing reason should name the exception class: " + denied.get("reason").asString());
        assertFalse(denied.get("reason").asString().contains("boom"),
                "the exception's own message must never reach the client: " + denied.get("reason").asString());
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getThrown() instanceof IllegalArgumentException),
                "expected a WARNING log record carrying the exception (with stack trace)");
    }

    /**
     * A {@link java.util.NoSuchElementException} (e.g. an unknown {@code assetId}, per
     * {@code DefaultManualControlService#engage}'s own javadoc) is one concrete instance of the same
     * gap the test above covers with a synthetic exception -- neither {@link AccessDeniedException},
     * {@link VehicleUnidentifiedException} nor {@link IllegalStateException} names it.
     */
    @Test
    void engageUnknownAssetSurfacesAsInternalErrorRatherThanSilence() throws Exception {
        service.nextEngageFailure = new java.util.NoSuchElementException("no asset with that id");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("INTERNAL_ERROR", denied.get("code").asString());
    }

    /**
     * Engage-duration instrumentation smoke test (FLY-CONTROL-UX H1): a handler configured with a
     * 0ms slow-engage threshold must escalate even a fast, successful engage to WARNING, naming the
     * outcome -- proving the duration measurement actually wraps the {@code engage()} call rather
     * than being dead code.
     */
    @Test
    void engageDurationEscalatesToWarnOnceItReachesTheConfiguredSlowThreshold() throws Exception {
        ManualControlWebSocketHandler zeroThresholdHandler =
                new ManualControlWebSocketHandler(service, WATCHDOG_TIMEOUT_MS, 0L);
        zeroThresholdHandler.afterConnectionEstablished(session);

        List<LogRecord> records =
                captureLogRecords(() -> zeroThresholdHandler.handleMessage(session, engageFrame(AssetId.random())));

        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage() != null && r.getMessage().contains("outcome=engaged")),
                "a 0ms slow-engage threshold must escalate even a successful engage's duration log to WARNING");
    }

    /** Captures every {@code java.util.logging} record the handler's own logger publishes during {@code action}. */
    private static List<LogRecord> captureLogRecords(ThrowingRunnable action) throws Exception {
        java.util.logging.Logger julLogger =
                java.util.logging.Logger.getLogger(ManualControlWebSocketHandler.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // no-op fake
            }

            @Override
            public void close() {
                // no-op fake
            }
        };
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            action.run();
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }
        return records;
    }

    /** Lets {@link #captureLogRecords} wrap {@code handleMessage}, which declares {@code throws Exception}. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void engageDeniedAlreadyEngagedOnTheSameConnectionWithoutCallingTheServiceAgain() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        session.sentPayloads.clear();
        int engageCallsBefore = service.engageCallCount;

        handler.handleMessage(session, engageFrame(AssetId.random()));

        JsonNode denied = lastFrame();
        assertEquals("denied", denied.get("type").asString());
        assertEquals("ALREADY_ENGAGED", denied.get("code").asString());
        assertEquals(engageCallsBefore, service.engageCallCount, "a same-connection re-engage must not call the service again");
    }

    @Test
    void watchdogFramePushedWhenListenerFiresThenLaterChannelsFramesAreSilentlyDropped() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        WatchdogListener watchdogListener = service.lastWatchdogListener;
        session.sentPayloads.clear();

        watchdogListener.watchdogTripped();

        JsonNode watchdogFrame = lastFrame();
        assertEquals("watchdog", watchdogFrame.get("type").asString());
        assertEquals(WATCHDOG_TIMEOUT_MS, watchdogFrame.get("timeoutMs").asLong());

        session.sentPayloads.clear();
        handler.handleMessage(session, channelsFrame(1L));
        assertTrue(session.sentPayloads.isEmpty(), "a channels frame after the watchdog trip must be a silent no-op");
    }

    @Test
    void explicitReleaseSendsReleasedExplicitAndReleasesTheSession() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        session.sentPayloads.clear();

        handler.handleMessage(session, releaseFrame());

        JsonNode released = lastFrame();
        assertEquals("released", released.get("type").asString());
        assertEquals("EXPLICIT", released.get("reason").asString());
        assertTrue(service.lastSession.released);
    }

    @Test
    void connectionClosedReleasesTheSessionWithoutSendingAFrame() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        session.sentPayloads.clear();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertTrue(service.lastSession.released);
        assertTrue(session.sentPayloads.isEmpty(), "afterConnectionClosed must never attempt to send a frame");
    }

    @Test
    void connectionClosedIsIdempotentEvenWithNoSessionEverEngaged() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertTrue(session.sentPayloads.isEmpty());
    }

    @Test
    void concurrentAckAndWatchdogSendsNeverInterleaveOnTheSameConnection() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, engageFrame(AssetId.random()));
        WatchdogListener watchdogListener = service.lastWatchdogListener;

        int iterations = 300;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Thread channelsThread = new Thread(() -> {
            ready.countDown();
            awaitQuietly(go);
            for (long seq = 0; seq < iterations; seq++) {
                try {
                    handler.handleMessage(session, channelsFrame(seq));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread watchdogThread = new Thread(() -> {
            ready.countDown();
            awaitQuietly(go);
            for (int i = 0; i < iterations; i++) {
                watchdogListener.watchdogTripped();
            }
        });

        channelsThread.start();
        watchdogThread.start();
        ready.await();
        go.countDown();
        channelsThread.join(10_000);
        watchdogThread.join(10_000);

        assertEquals(0, session.concurrencyViolations.get(),
                "WebSocketSession#sendMessage must never be called concurrently for the same connection");
    }

    private TextMessage engageFrame(AssetId assetId) {
        return new TextMessage(jsonMapper.writeValueAsString(
                new ManualControlEngageRequest("engage", assetId.value().toString())));
    }

    private TextMessage channelsFrame(long seq) {
        return new TextMessage(jsonMapper.writeValueAsString(
                new ManualControlChannelsRequest("channels", List.of(0.1, -0.2), List.of(1.0), seq, 1_000L + seq)));
    }

    private TextMessage releaseFrame() {
        return new TextMessage("{\"type\":\"release\"}");
    }

    private JsonNode lastFrame() {
        List<String> payloads = session.sentPayloads;
        assertFalse(payloads.isEmpty(), "expected at least one frame to have been sent");
        return jsonMapper.readTree(payloads.get(payloads.size() - 1));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Hand-fake {@link ManualControlService}: one queued failure, otherwise always succeeds. */
    private static final class FakeManualControlService implements ManualControlService {
        private RuntimeException nextEngageFailure;
        private WatchdogListener lastWatchdogListener;
        private FakeManualControlSession lastSession;
        private int engageCallCount;

        @Override
        public ManualControlSession engage(AssetId assetId, UserId actor, VisibilityScope scope,
                                            WatchdogListener onWatchdog) {
            engageCallCount++;
            if (nextEngageFailure != null) {
                RuntimeException failure = nextEngageFailure;
                nextEngageFailure = null;
                throw failure;
            }
            lastWatchdogListener = onWatchdog;
            lastSession = new FakeManualControlSession();
            return lastSession;
        }
    }

    /** Hand-fake {@link ManualControlSession}: records every {@code onChannels} call, tracks release/active. */
    private static final class FakeManualControlSession implements ManualControlSession {

        /** Deliberately unlike any hardcoded default, so a regression to a mirrored constant fails
         * loudly instead of coincidentally matching. */
        static final int RATE_HZ = 41;

        @Override
        public int rateHz() {
            return RATE_HZ;
        }
        /** A rover, deliberately: its map is the one that differs most from the old frozen default. */
        static final VehicleKind KIND = VehicleKind.ROVER;

        private final ControlProfile controlProfile = ControlProfile.forKind(KIND);
        private final List<Object[]> channelCalls = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean active = true;
        private volatile boolean released;

        @Override
        public void onChannels(List<Double> axes, List<Double> buttons, long seq, long tSent) {
            channelCalls.add(new Object[] {axes, buttons, seq, tSent});
        }

        @Override
        public void release() {
            released = true;
            active = false;
        }

        @Override
        public ControlProfile controlProfile() {
            return controlProfile;
        }

        @Override
        public boolean active() {
            return active;
        }
    }

    /**
     * Hand-fake {@link WebSocketSession}: captures every sent {@link TextMessage} payload and
     * detects any two {@link #sendMessage} calls overlapping in time (a concurrency violation the
     * handler's per-connection send lock must prevent — see {@code
     * concurrentAckAndWatchdogSendsNeverInterleaveOnTheSameConnection}).
     */
    private static final class FakeWebSocketSession implements WebSocketSession {
        private final String id = "fake-session-" + System.nanoTime();
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final List<String> sentPayloads = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean sendInProgress = new AtomicBoolean(false);
        private final AtomicInteger concurrencyViolations = new AtomicInteger(0);
        private volatile boolean open = true;

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/manual-control");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            // no-op fake
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            // no-op fake
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            boolean acquired = sendInProgress.compareAndSet(false, true);
            if (!acquired) {
                concurrencyViolations.incrementAndGet();
            }
            try {
                Thread.sleep(1); // widen the race window so a missing lock reliably shows up
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (message instanceof TextMessage textMessage) {
                sentPayloads.add(textMessage.getPayload());
            }
            if (acquired) {
                sendInProgress.set(false);
            }
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
