package com.drones.vision.api.ws;

import com.drones.vision.api.dto.ManualControlAckFrame;
import com.drones.vision.api.dto.ManualControlChannelBindingResponse;
import com.drones.vision.api.dto.ManualControlDeniedFrame;
import com.drones.vision.api.dto.ManualControlEngagedFrame;
import com.drones.vision.api.dto.ManualControlReleasedFrame;
import com.drones.vision.api.dto.ManualControlWatchdogFrame;
import com.drones.vision.identity.application.scope.AccessDeniedException;
import com.drones.vision.flight.application.ManualControlService;
import com.drones.vision.flight.application.ManualControlSession;
import com.drones.vision.identity.application.scope.VisibilityScope;
import com.drones.vision.flight.application.WatchdogListener;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlBinding;
import com.drones.vision.kernel.UserId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw (non-STOMP) {@code WebSocketHandler} for {@code /ws/manual-control}
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4) — parses/emits the frozen JSON text-frame protocol on top of
 * {@link ManualControlService}, the stateful RC-relay session service R2 built.
 *
 * <h2>One session per connection, service is a shared singleton</h2>
 * Each {@link WebSocketSession} gets its own {@link ConnectionState} (tracked in {@link
 * #connections}, keyed by {@link WebSocketSession#getId()}) holding at most one {@link
 * ManualControlSession} at a time — a second {@code engage} frame on the <em>same</em> connection
 * while one is already active is denied {@code ALREADY_ENGAGED} without calling {@link
 * #manualControlService} again. {@link #manualControlService} itself is wired as one app-wide
 * singleton (see {@code WiringConfiguration}), so a <em>different</em> connection trying to engage
 * while another connection's session is still active hits {@code
 * DefaultManualControlService}'s own one-session-per-handle guard — surfacing as an {@link
 * IllegalStateException} this handler also maps to {@code ALREADY_ENGAGED} (see {@link
 * #mapIllegalState}).
 *
 * <h2>Exception&rarr;{@code denied} mapping (best-effort, documented rough edge)</h2>
 * {@link ManualControlService#engage} throws exactly two types: {@link AccessDeniedException}
 * (unambiguous &rarr; {@code OUT_OF_SCOPE}) and {@link IllegalStateException} for three distinct
 * causes ("already active on this handle", "no active manual-control-capable device", or the
 * port's own "not currently reachable"/"does not support device" messages) that the application
 * layer does not distinguish by exception type — only by message text. {@link #mapIllegalState}
 * matches on message substrings in priority order; the "not currently reachable" case (a
 * commandable device that isn't currently heard) falls through to {@code NOT_COMMANDABLE} for lack
 * of a more specific frozen code — see that method's own javadoc.
 *
 * <h2>Per-connection send lock</h2>
 * {@link WebSocketSession#sendMessage} is not safe to call concurrently from two threads for the
 * same session. An {@code ack} (this handler's own read/dispatch thread) and a {@code watchdog}
 * frame (pushed from whatever thread {@code vision.rc}'s shared {@code "rc-watchdog"} scheduler
 * runs on, via {@link WatchdogListener#watchdogTripped()}) can race, so every send goes through
 * {@link #sendFrame}, which synchronizes on {@link ConnectionState#sendLock} — exactly the guard
 * {@code LiveConnection} uses around {@code SseEmitter#send}.
 *
 * <h2>{@code rateHz} is informational only</h2>
 * See {@link ManualControlEngagedFrame}'s own javadoc: this module may not depend on
 * adapter-mavlink, so the {@code engaged} frame's {@code rateHz} is a fixed constant mirroring
 * {@code MavlinkManualControlSender}'s own default, not read live from its {@code
 * VISION_RC_OVERRIDE_HZ} env knob. A documented rough edge, not a bug.
 */
@Component
public class ManualControlWebSocketHandler extends TextWebSocketHandler {

    private static final System.Logger LOG = System.getLogger(ManualControlWebSocketHandler.class.getName());

    private static final String TYPE_ENGAGE = "engage";
    private static final String TYPE_CHANNELS = "channels";
    private static final String TYPE_RELEASE = "release";

    private static final String CODE_OUT_OF_SCOPE = "OUT_OF_SCOPE";
    private static final String CODE_NOT_COMMANDABLE = "NOT_COMMANDABLE";
    private static final String CODE_UNSUPPORTED = "UNSUPPORTED";
    private static final String CODE_ALREADY_ENGAGED = "ALREADY_ENGAGED";
    private static final String CODE_BAD_REQUEST = "BAD_REQUEST";
    private static final String CODE_MALFORMED = "MALFORMED";
    private static final String CODE_UNKNOWN_TYPE = "UNKNOWN_TYPE";

    private final ManualControlService manualControlService;
    private final long watchdogTimeoutMillis;
    private final JsonMapper jsonMapper = new JsonMapper();
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

    /**
     * @param manualControlService  the shared relay-session service (see class javadoc for the
     *                               "one connection, one app-wide singleton" split)
     * @param watchdogTimeoutMillis {@code vision.rc.watchdog-timeout-ms} — read independently here
     *                              (rather than asked of {@code manualControlService}, which has no
     *                              getter for it) purely to echo it on the {@code watchdog} frame;
     *                              {@code WiringConfiguration} reads the same property key to build
     *                              {@code manualControlService}'s actual watchdog, so the two stay
     *                              in sync by construction as long as both read that one key
     */
    public ManualControlWebSocketHandler(ManualControlService manualControlService,
                                          @Value("${vision.rc.watchdog-timeout-ms:300}") long watchdogTimeoutMillis) {
        this.manualControlService =
                Objects.requireNonNull(manualControlService, "manualControlService must not be null");
        this.watchdogTimeoutMillis = watchdogTimeoutMillis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.put(session.getId(), new ConnectionState());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ConnectionState state = connections.get(session.getId());
        if (state == null) {
            return; // afterConnectionEstablished always runs first; defensive only
        }
        JsonNode node;
        try {
            node = jsonMapper.readTree(message.getPayload());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "malformed /ws/manual-control frame, ignoring", e);
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_MALFORMED, "could not parse frame as JSON"));
            return;
        }
        String type = node.path("type").asString(null);
        if (type == null) {
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_MALFORMED, "frame is missing its \"type\" field"));
            return;
        }
        switch (type) {
            case TYPE_ENGAGE -> handleEngage(session, state, node);
            case TYPE_CHANNELS -> handleChannels(session, state, node);
            case TYPE_RELEASE -> handleRelease(session, state);
            default -> {
                String unknownType = type;
                LOG.log(System.Logger.Level.WARNING, () -> "unknown /ws/manual-control frame type: " + unknownType);
                sendFrame(session, state,
                        new ManualControlDeniedFrame(CODE_UNKNOWN_TYPE, "unknown frame type: " + unknownType));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        ConnectionState state = connections.remove(session.getId());
        if (state != null && state.session != null) {
            state.session.release(); // idempotent; the socket is gone, so no released frame is sent
        }
    }

    private void handleEngage(WebSocketSession session, ConnectionState state, JsonNode node) {
        if (state.session != null && state.session.active()) {
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_ALREADY_ENGAGED,
                    "a manual-control session is already active on this connection"));
            return;
        }

        AssetId assetId;
        try {
            assetId = AssetId.of(node.path("assetId").asString(null));
        } catch (IllegalArgumentException e) {
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_BAD_REQUEST, "missing or malformed assetId"));
            return;
        }

        UserId actor = (UserId) session.getAttributes().get(ManualControlHandshakeInterceptor.ATTR_USER_ID);
        VisibilityScope scope =
                (VisibilityScope) session.getAttributes().get(ManualControlHandshakeInterceptor.ATTR_SCOPE);
        if (actor == null || scope == null) {
            // The handshake interceptor stashes both, and an unauthenticated handshake is already
            // rejected before upgrade -- defensive only, never expected in practice.
            sendFrame(session, state,
                    new ManualControlDeniedFrame(CODE_OUT_OF_SCOPE, "no acting identity on this connection"));
            return;
        }

        WatchdogListener onWatchdog = () -> {
            state.session = null;
            sendFrame(session, state, new ManualControlWatchdogFrame(watchdogTimeoutMillis));
        };

        try {
            ManualControlSession mcSession = manualControlService.engage(assetId, actor, scope, onWatchdog);
            state.session = mcSession;
            sendFrame(session, state, new ManualControlEngagedFrame(assetId.value().toString(),
                    ManualControlEngagedFrame.DEFAULT_RATE_HZ, toChannelMapResponse(mcSession.channelMap())));
        } catch (AccessDeniedException e) {
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_OUT_OF_SCOPE, e.getMessage()));
        } catch (IllegalStateException e) {
            sendFrame(session, state, new ManualControlDeniedFrame(mapIllegalState(e), e.getMessage()));
        }
    }

    private void handleChannels(WebSocketSession session, ConnectionState state, JsonNode node) {
        ManualControlSession mcSession = state.session;
        if (mcSession == null) {
            return; // nothing engaged on this connection yet -- a stray/late frame, silently dropped
        }
        List<Double> axes = readDoubleList(node.path("axes"));
        List<Double> buttons = readDoubleList(node.path("buttons"));
        long seq = node.path("seq").asLong(0L);
        long tSent = node.path("tSent").asLong(0L);
        mcSession.onChannels(axes, buttons, seq, tSent);
        sendFrame(session, state, new ManualControlAckFrame(seq, tSent, System.currentTimeMillis()));
    }

    private void handleRelease(WebSocketSession session, ConnectionState state) {
        if (state.session != null) {
            state.session.release();
            state.session = null;
        }
        sendFrame(session, state, new ManualControlReleasedFrame(ManualControlReleasedFrame.REASON_EXPLICIT));
    }

    /**
     * Best-effort message-substring mapping from {@link ManualControlService#engage}'s
     * undifferentiated {@link IllegalStateException} to one of the three remaining frozen {@code
     * denied} codes (see class javadoc). {@code DefaultManualControlService}'s own "already active
     * on this handle" message is checked first (unambiguous); {@code
     * MavlinkManualControlSender.engage}'s own "does not support device" message (a defensive check
     * that should never actually fire, since {@code supports()} is checked first) maps to {@code
     * UNSUPPORTED}; everything else — notably "no active manual-control-capable device" (no device
     * even claims to support manual control) and the port's "you cannot command what you cannot
     * hear" not-currently-reachable message — falls through to {@code NOT_COMMANDABLE} as the
     * closest of the four frozen codes, since there is no dedicated "unreachable" code in the
     * frozen §4 contract.
     */
    private static String mapIllegalState(IllegalStateException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("already active")) {
            return CODE_ALREADY_ENGAGED;
        }
        if (message.contains("does not support device")) {
            return CODE_UNSUPPORTED;
        }
        return CODE_NOT_COMMANDABLE;
    }

    private static List<ManualControlChannelBindingResponse> toChannelMapResponse(ChannelMap channelMap) {
        return channelMap.bindings().stream()
                .map(binding -> new ManualControlChannelBindingResponse(binding.source().name(), binding.sourceIndex(),
                        binding.rcChannel(), labelFor(binding.source(), binding.rcChannel())))
                .toList();
    }

    /**
     * This handler's own {@code source+rcChannel -> label} mapping (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md
     * §5) — {@link ControlBinding} carries no label field, so the frozen default map's function
     * names are hardcoded here rather than derived.
     */
    private static String labelFor(ControlBinding.Source source, int rcChannel) {
        if (source == ControlBinding.Source.AXIS) {
            return switch (rcChannel) {
                case 1 -> "Roll";
                case 2 -> "Pitch";
                case 3 -> "Throttle";
                case 4 -> "Yaw";
                default -> "Axis RC" + rcChannel;
            };
        }
        return switch (rcChannel) {
            case 5 -> "Aux 1";
            case 6 -> "Aux 2";
            case 7 -> "Aux 3";
            case 8 -> "Aux 4";
            default -> "Button RC" + rcChannel;
        };
    }

    private static List<Double> readDoubleList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        return arrayNode.valueStream().map(JsonNode::asDouble).toList();
    }

    private void sendFrame(WebSocketSession session, ConnectionState state, Object frame) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(frame);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to serialize /ws/manual-control frame", e);
            return;
        }
        synchronized (state.sendLock) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to send /ws/manual-control frame", e);
            }
        }
    }

    /**
     * This handler is a Spring singleton bean; every field here is per-{@link WebSocketSession}
     * state, tracked in {@link #connections} rather than as instance fields on the handler itself.
     * {@link #sendLock} is the guard {@link #sendFrame} synchronizes on (see class javadoc);
     * {@link #session} is written from this connection's own read/dispatch thread (engage/release)
     * and from the watchdog scheduler thread (on trip), so it is {@code volatile} for visibility —
     * no finer-grained synchronization is needed since both writers already only ever replace it
     * wholesale, never read-modify-write it.
     */
    private static final class ConnectionState {
        private final Object sendLock = new Object();
        private volatile ManualControlSession session;
    }
}
