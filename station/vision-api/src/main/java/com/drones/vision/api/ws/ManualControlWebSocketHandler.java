package com.drones.vision.api.ws;

import com.drones.vision.api.dto.ManualControlAckFrame;
import com.drones.vision.api.dto.ControlProfileResponse;
import com.drones.vision.api.dto.ManualControlChannelBindingResponse;
import com.drones.vision.api.dto.ManualControlDeniedFrame;
import com.drones.vision.api.dto.ManualControlEngagedFrame;
import com.drones.vision.api.dto.ManualControlReleasedFrame;
import com.drones.vision.api.dto.ManualControlWatchdogFrame;
import com.drones.vision.api.security.CapabilityAssetAuthority;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.flight.application.ManualControlService;
import com.drones.vision.flight.application.ManualControlSession;
import com.drones.vision.flight.application.VehicleUnidentifiedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.flight.application.WatchdogListener;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.flight.domain.model.ControlProfile;
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
 * <h2>The {@code mayFly} gate is asked once, at {@code engage} (wave B4)</h2>
 * {@link #handleEngage} checks {@link CapabilityAssetAuthority#mayFly(Authority, UserId, AssetId)}
 * before calling {@link #manualControlService}, denying {@code OUT_OF_SCOPE} on {@code false} —
 * the same code {@link AccessDeniedException} from the service itself already maps to, so a denial
 * looks identical to the client whichever gate produced it. This is strictly additive to {@link
 * ManualControlService#engage}'s own {@code scope().includes(...)} check (unreachable from here —
 * {@code contexts/vision-flight}): the edge gate is narrower (capability + seat), so it denies
 * first whenever it would deny. Per docs/plans/active/AUTH-ROLES-PLAN.md §3.7 clause 1, this is the
 * <em>only</em> place authority is checked for a manual-control session — never again afterward,
 * regardless of what changes about the caller's authority while the connection is open. See {@link
 * ManualControlHandshakeInterceptor}'s class javadoc for the full mid-flight rule and why this
 * gate reads its actor/authority from session attributes rather than the ambient {@link
 * com.drones.vision.api.security.CurrentUser}.
 *
 * <h2>Exception&rarr;{@code denied} mapping (best-effort, documented rough edge)</h2>
 * {@link ManualControlService#engage} throws three <em>documented</em> types: {@link
 * AccessDeniedException} (unambiguous &rarr; {@code OUT_OF_SCOPE}); {@link
 * VehicleUnidentifiedException} (FLEET-RADIO R2, caught <em>before</em> the plain form below since
 * it is a subtype) &rarr; the additive {@code VEHICLE_UNIDENTIFIED} code, with the operator-facing
 * distinction between "never identified", "a real airframe we don't support" and "not a vehicle at
 * all" carried entirely in its own message, not in the code — see that exception's javadoc for why
 * a dedicated exception type exists here instead of a fourth message-sniffed case; and the plain
 * {@link IllegalStateException} for the three remaining causes ("already active on this handle", "no
 * active manual-control-capable device", or the port's own "not currently reachable"/"does not
 * support device" messages) that the application layer does not distinguish by exception type —
 * only by message text. {@link #mapIllegalState} matches on message substrings in priority order;
 * the "not currently reachable" case (a commandable device that isn't currently heard) falls through
 * to {@code NOT_COMMANDABLE} for lack of a more specific frozen code — see that method's own
 * javadoc.
 *
 * <h2>Everything else is {@code INTERNAL_ERROR}, never silence (FLY-CONTROL-UX H1)</h2>
 * docs/plans/active/fly-control-ux/R3-handshake-denial.md traced a real, reported "Control denied —
 * The station never confirmed control" to the web client's own {@code ENGAGE_TIMEOUT_MS} (4s)
 * firing with <em>no</em> reply at all — a timeout that can only fire from a station fault, since
 * {@code engage()}'s whole path is local and ack-less (no vehicle round-trip exists to be slow). A
 * fourth, final {@code catch (RuntimeException e)} closes that gap: any exception the three clauses
 * above do not name (including a plain {@link java.util.NoSuchElementException} from an unknown
 * {@code assetId}, or anything thrown while composing the {@code engaged} frame's response fields)
 * is logged WARNING with its stack trace and still answers with a {@code denied} frame — {@code
 * INTERNAL_ERROR}, a generic operator-facing sentence, and the exception's simple class name (never
 * its message — that could leak internals a caller has no business seeing). {@link #handleEngage}
 * must never return without a reply on a socket that is still open.
 *
 * <h2>Per-connection send lock</h2>
 * {@link WebSocketSession#sendMessage} is not safe to call concurrently from two threads for the
 * same session. An {@code ack} (this handler's own read/dispatch thread) and a {@code watchdog}
 * frame (pushed from whatever thread {@code vision.rc}'s shared {@code "rc-watchdog"} scheduler
 * runs on, via {@link WatchdogListener#watchdogTripped()}) can race, so every send goes through
 * {@link #sendFrame}, which synchronizes on {@link ConnectionState#sendLock} — exactly the guard
 * {@code LiveConnection} uses around {@code SseEmitter#send}.
 *
 * <h2>Engage duration is measured, not assumed (FLY-CONTROL-UX H1)</h2>
 * Every {@code engage} call's wall time — success or denial alike — is logged: WARNING, naming the
 * outcome and the elapsed milliseconds, once it reaches {@link #engageSlowThresholdMillis} ({@code
 * vision.rc.engage-slow-threshold-ms}, default 2000); DEBUG otherwise, so a real "never confirmed"
 * report is diagnosable from this station's own log in seconds — R3 found the server side had no
 * equivalent of the client's own console-warn on abandon. This is observability only: nothing in
 * {@link ManualControlService#engage}'s own contract (see that interface's javadoc, and {@code
 * mavlink-core}'s {@code ManualControlService.engage}) performs a vehicle round-trip, so the
 * threshold exists to catch a station-side regression, not a genuinely slow vehicle.
 *
 * <h2>The stick layout comes from the vehicle, and its labels come with it</h2>
 * The {@code engaged} frame carries the session's {@link ControlProfile} — the vehicle kind the
 * adapter is currently hearing, plus the bindings chosen for it, each with its own function and
 * travel. This handler no longer invents labels from channel numbers: the {@code source+rcChannel ->
 * label} switch it used to own hardcoded {@code case 1 -> "Roll"}, which is simply false on a ground
 * vehicle whose channel 1 is steering (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P5).
 *
 * <h2>{@code rateHz} is read live, through the port</h2>
 * This module still may not depend on adapter-mavlink, so the number does not come from there
 * directly — it rides the seam the port already owns: the adapter stamps its clamped keepalive rate
 * on the {@code ManualControlLink} it returns from {@code engage}, {@link ManualControlSession}
 * exposes it, and this handler echoes it (docs/plans/done/RC-LATENCY-PLAN.md §2 C). The
 * hand-mirrored constant this class used to send is gone.
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
    private static final String CODE_VEHICLE_UNIDENTIFIED = "VEHICLE_UNIDENTIFIED";
    private static final String CODE_BAD_REQUEST = "BAD_REQUEST";
    private static final String CODE_MALFORMED = "MALFORMED";
    private static final String CODE_UNKNOWN_TYPE = "UNKNOWN_TYPE";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    private final ManualControlService manualControlService;
    private final CapabilityAssetAuthority assetAuthority;
    private final long watchdogTimeoutMillis;
    private final long engageSlowThresholdMillis;
    private final JsonMapper jsonMapper = new JsonMapper();
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

    /**
     * @param manualControlService      the shared relay-session service (see class javadoc for the
     *                                   "one connection, one app-wide singleton" split)
     * @param assetAuthority            the {@code mayFly} gate (docs/plans/active/AUTH-ROLES-PLAN.md
     *                                  §3.7/§3.8, wave B4) — the concrete {@link
     *                                  CapabilityAssetAuthority} type, not the {@code AssetAuthority}
     *                                  interface, because {@link #handleEngage} calls its
     *                                  explicit-actor overload; see that class's own javadoc for why
     *                                  the ambient-{@code CurrentUser} interface method cannot be
     *                                  used here
     * @param watchdogTimeoutMillis     {@code vision.rc.watchdog-timeout-ms} — read independently
     *                                  here (rather than asked of {@code manualControlService},
     *                                  which has no getter for it) purely to echo it on the {@code
     *                                  watchdog} frame; {@code WiringConfiguration} reads the same
     *                                  property key to build {@code manualControlService}'s actual
     *                                  watchdog, so the two stay in sync by construction as long as
     *                                  both read that one key
     * @param engageSlowThresholdMillis {@code vision.rc.engage-slow-threshold-ms} (FLY-CONTROL-UX
     *                                  H1) — the elapsed-time bound past which an {@code engage}
     *                                  call's own duration log escalates from DEBUG to WARNING; see
     *                                  the class javadoc's "Engage duration is measured" section
     */
    public ManualControlWebSocketHandler(ManualControlService manualControlService,
                                          CapabilityAssetAuthority assetAuthority,
                                          @Value("${vision.rc.watchdog-timeout-ms:300}") long watchdogTimeoutMillis,
                                          @Value("${vision.rc.engage-slow-threshold-ms:2000}") long engageSlowThresholdMillis) {
        this.manualControlService =
                Objects.requireNonNull(manualControlService, "manualControlService must not be null");
        this.assetAuthority = Objects.requireNonNull(assetAuthority, "assetAuthority must not be null");
        this.watchdogTimeoutMillis = watchdogTimeoutMillis;
        this.engageSlowThresholdMillis = engageSlowThresholdMillis;
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
        Authority authority =
                (Authority) session.getAttributes().get(ManualControlHandshakeInterceptor.ATTR_AUTHORITY);
        if (actor == null || scope == null || authority == null) {
            // The handshake interceptor stashes all three, and an unauthenticated handshake is
            // already rejected before upgrade -- defensive only, never expected in practice.
            sendFrame(session, state,
                    new ManualControlDeniedFrame(CODE_OUT_OF_SCOPE, "no acting identity on this connection"));
            return;
        }

        long engageStartNanos = System.nanoTime();

        // The mayFly gate (docs/plans/active/AUTH-ROLES-PLAN.md §3.7/§3.8, wave B4) -- asked exactly
        // once, here, at engage. Never re-asked for the life of this connection: see the mid-flight
        // rule in ManualControlHandshakeInterceptor's class javadoc. Uses the explicit-actor overload
        // against what the handshake interceptor captured, not AssetAuthority#mayFly(AssetId) --
        // CapabilityAssetAuthority's own javadoc explains why the ambient-CurrentUser path cannot
        // resolve on this (message-handling) thread.
        if (!assetAuthority.mayFly(authority, actor, assetId)) {
            logEngageDuration(engageStartNanos, assetId, "denied:" + CODE_OUT_OF_SCOPE);
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_OUT_OF_SCOPE,
                    "Asset " + assetId.value() + " may not be flown by you"));
            return;
        }

        WatchdogListener onWatchdog = () -> {
            state.session = null;
            sendFrame(session, state, new ManualControlWatchdogFrame(watchdogTimeoutMillis));
        };

        try {
            ManualControlSession mcSession = manualControlService.engage(assetId, actor, scope, onWatchdog);
            // Built before state.session is set: if composing the response somehow throws, the
            // catch-all below still sees an unengaged connection rather than one whose local state
            // disagrees with what the client was told.
            ControlProfile profile = mcSession.controlProfile();
            ManualControlEngagedFrame engagedFrame = new ManualControlEngagedFrame(assetId.value().toString(),
                    mcSession.rateHz(), profile.kind().name(), profile.id().value().toString(),
                    profile.isBuiltIn() ? ControlProfileResponse.SOURCE_BUILT_IN : ControlProfileResponse.SOURCE_SAVED,
                    profile.code(), profile.displayName(), toChannelMapResponse(profile));
            state.session = mcSession;
            logEngageDuration(engageStartNanos, assetId, "engaged");
            sendFrame(session, state, engagedFrame);
        } catch (AccessDeniedException e) {
            logEngageDuration(engageStartNanos, assetId, "denied:" + CODE_OUT_OF_SCOPE);
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_OUT_OF_SCOPE, e.getMessage()));
        } catch (VehicleUnidentifiedException e) {
            // A dedicated code, not message-sniffed like the generic IllegalStateException causes
            // below -- the exception itself already carries which of the three UnidentifiedReason
            // causes applied (FLEET-RADIO R2), so the operator-facing distinction rides entirely in
            // this one exception's own message, composed by DefaultManualControlService per reason.
            logEngageDuration(engageStartNanos, assetId, "denied:" + CODE_VEHICLE_UNIDENTIFIED);
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_VEHICLE_UNIDENTIFIED, e.getMessage()));
        } catch (IllegalStateException e) {
            String code = mapIllegalState(e);
            logEngageDuration(engageStartNanos, assetId, "denied:" + code);
            sendFrame(session, state, new ManualControlDeniedFrame(code, e.getMessage()));
        } catch (RuntimeException e) {
            // FLY-CONTROL-UX H1: the one gap R3 could not rule out -- anything not one of the three
            // documented types above must still answer, or the client's own ENGAGE_TIMEOUT_MS (4s)
            // fires with no reply at all and reports a station fault as "the station never confirmed
            // control" (see class javadoc). The full exception (with stack trace) goes to the log;
            // only its simple class name -- never its message, which may carry internals a caller
            // has no business seeing -- goes to the client.
            logEngageDuration(engageStartNanos, assetId, "denied:" + CODE_INTERNAL_ERROR);
            String assetIdValue = assetId.value().toString();
            LOG.log(System.Logger.Level.WARNING,
                    () -> "unexpected exception from ManualControlService.engage for asset " + assetIdValue, e);
            sendFrame(session, state, new ManualControlDeniedFrame(CODE_INTERNAL_ERROR,
                    "The station hit an internal error taking control -- check station logs ("
                            + e.getClass().getSimpleName() + ")"));
        }
    }

    /**
     * Logs one {@code engage} attempt's wall time and outcome — WARNING once it reaches {@link
     * #engageSlowThresholdMillis}, DEBUG otherwise (see the class javadoc's "Engage duration is
     * measured" section). {@code outcome} is either {@code "engaged"} or {@code "denied:<code>"}.
     */
    private void logEngageDuration(long startNanos, AssetId assetId, String outcome) {
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        String assetIdValue = assetId.value().toString();
        if (elapsedMillis >= engageSlowThresholdMillis) {
            LOG.log(System.Logger.Level.WARNING, () -> "engage() for asset " + assetIdValue + " took "
                    + elapsedMillis + "ms (outcome=" + outcome + "), at/past the " + engageSlowThresholdMillis
                    + "ms slow-engage threshold -- engage() is documented local/ack-less, so this points at a "
                    + "station-side fault, not vehicle slowness");
        } else {
            LOG.log(System.Logger.Level.DEBUG, () -> "engage() for asset " + assetIdValue + " took "
                    + elapsedMillis + "ms (outcome=" + outcome + ")");
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

    private static List<ManualControlChannelBindingResponse> toChannelMapResponse(ControlProfile profile) {
        return profile.channelMap().bindings().stream().map(ManualControlChannelBindingResponse::from).toList();
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
