package com.drones.vision.api.ws;

import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Objects;
import com.drones.vision.api.security.CurrentUser;

/**
 * Resolves the {@code /ws/manual-control} handshake's acting identity before the upgrade completes
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4) — the WebSocket counterpart to every REST controller reading
 * {@link CurrentUser}.
 *
 * <h2>Same seam as every REST call, one layer earlier</h2>
 * The upgrade {@code GET} rides the same-origin session cookie and passes through the same {@code
 * SecurityFilterChain} as {@code /api/**} (the secured chain adds {@code /ws/**} to {@code
 * authenticated()} — see {@code SecurityConfig}), so by the time {@link #beforeHandshake} runs on
 * that request's own thread, {@code CurrentUser} already resolves the same identity a REST
 * controller on this request would get: the fixed dev principal when {@code
 * vision.auth.enabled=false}, or the session's authenticated principal when {@code true}. This
 * class does nothing auth-specific itself — it only threads {@link CurrentUser#userId()}/{@link
 * CurrentUser#scope()}/{@link CurrentUser#authority()} into the WebSocket session's attribute map
 * (the {@code attributes} parameter here <em>is</em> the same map {@link
 * org.springframework.web.socket.WebSocketSession#getAttributes()} returns for the lifetime of the
 * connection), so {@link ManualControlWebSocketHandler} can read them back on every {@code engage}
 * without touching Spring Security itself.
 *
 * <h2>Defensive rejection</h2>
 * With auth enabled, an unauthenticated request never reaches this class at all — the secured
 * chain answers {@code 401} first. The try/catch below only guards the case Spring Security's own
 * {@code SecurityContextPrincipalResolver} calls out as "purely defensive": a resolver call
 * reaching this code with no authenticated principal on the context. Failing the handshake with
 * {@code 401} here rather than letting the exception propagate keeps that guarantee explicit and
 * gives an observable status code instead of a generic handshake failure.
 *
 * <h2>The mid-flight rule, clause 1 (docs/plans/active/AUTH-ROLES-PLAN.md §3.7, wave B4)</h2>
 * This class stashes <em>identity and scope</em> — who is connecting, and what they may see — not
 * a per-asset flight authority answer. It cannot: the asset being flown is not known yet at
 * handshake time, only once an {@code engage} text frame names one. {@link
 * com.drones.vision.api.security.AssetAuthority#mayFly} is therefore asked exactly once, by {@link
 * ManualControlWebSocketHandler#handleEngage}, at the moment {@code engage} is processed — never
 * here, and never again afterward for the life of this connection. This is the frozen contract:
 * <b>the control socket is never closed by session state.</b> A session expiring, a capability
 * being revoked, or a seat being reassigned after {@code engage} has succeeded changes nothing
 * about an already-engaged connection — only the deadman watchdog, an explicit release, or the
 * socket closing ends manual control. A later revocation takes effect at the caller's <em>next</em>
 * {@code engage}, never by reaching into a session already in progress (§3.7 clause 3). See {@link
 * ManualControlWebSocketHandler} for where the one-time gate actually lives, and {@code
 * ManualControlWebSocketHandlerTest} for the test proving a mid-session authority flip is never
 * observed by an already-engaged connection.
 */
@Component
public class ManualControlHandshakeInterceptor implements HandshakeInterceptor {

    /** Session attribute key {@link ManualControlWebSocketHandler} reads the acting {@link UserId} from. */
    static final String ATTR_USER_ID = "manualControlUserId";
    /** Session attribute key {@link ManualControlWebSocketHandler} reads the acting {@link VisibilityScope} from. */
    static final String ATTR_SCOPE = "manualControlScope";
    /**
     * Session attribute key {@link ManualControlWebSocketHandler} reads the acting {@link Authority}
     * from (wave B4) — captured here, on the handshake's HTTP request thread, because {@link
     * CurrentUser#authority()} cannot be read again later on the message-handling thread {@code
     * engage} frames arrive on (see class javadoc).
     */
    static final String ATTR_AUTHORITY = "manualControlAuthority";

    private final CurrentUser currentUser;

    public ManualControlHandshakeInterceptor(CurrentUser currentUser) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UserId userId;
        VisibilityScope scope;
        Authority authority;
        try {
            userId = currentUser.userId();
            scope = currentUser.scope();
            authority = currentUser.authority();
        } catch (RuntimeException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_SCOPE, scope);
        attributes.put(ATTR_AUTHORITY, authority);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                Exception exception) {
        // Nothing to do -- attribute stashing already happened in beforeHandshake.
    }
}
