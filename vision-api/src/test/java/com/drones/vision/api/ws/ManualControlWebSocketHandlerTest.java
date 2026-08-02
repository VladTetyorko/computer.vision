package com.drones.vision.api.ws;

import com.drones.vision.api.dto.ManualControlChannelsRequest;
import com.drones.vision.api.dto.ManualControlEngageRequest;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.flight.ManualControlService;
import com.drones.vision.application.flight.ManualControlSession;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.application.flight.WatchdogListener;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.ChannelMap;
import com.drones.vision.domain.model.UserId;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ManualControlWebSocketHandler} through the frozen §4 frame protocol
 * (docs/RC-CONTROL-PHASE1-PLAN.md) against a hand-fake {@link ManualControlService}/{@link
 * ManualControlSession} and a hand-fake {@link WebSocketSession} — no Spring context, mirroring
 * this module's other pure unit-test controller/handler coverage.
 */
class ManualControlWebSocketHandlerTest {

    private static final long WATCHDOG_TIMEOUT_MS = 300L;

    private final JsonMapper jsonMapper = new JsonMapper();
    private FakeManualControlService service;
    private ManualControlWebSocketHandler handler;
    private FakeWebSocketSession session;

    @BeforeEach
    void setUp() {
        service = new FakeManualControlService();
        handler = new ManualControlWebSocketHandler(service, WATCHDOG_TIMEOUT_MS);
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
        assertEquals(8, frame.get("channelMap").size());
        JsonNode rollBinding = frame.get("channelMap").get(0);
        assertEquals("AXIS", rollBinding.get("source").asString());
        assertEquals(1, rollBinding.get("rcChannel").asInt());
        assertEquals("Roll", rollBinding.get("label").asString());
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
        private final ChannelMap channelMap = ChannelMap.defaultMap();
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
        public ChannelMap channelMap() {
            return channelMap;
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
