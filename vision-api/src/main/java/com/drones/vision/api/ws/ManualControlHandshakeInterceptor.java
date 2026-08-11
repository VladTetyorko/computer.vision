package com.drones.vision.api.ws;

import com.drones.vision.identity.application.scope.VisibilityScope;
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
 * CurrentUser#scope()} into the WebSocket session's attribute map (the {@code attributes} parameter
 * here <em>is</em> the same map {@link org.springframework.web.socket.WebSocketSession#getAttributes()}
 * returns for the lifetime of the connection), so {@link ManualControlWebSocketHandler} can read
 * them back on every {@code engage} without touching Spring Security itself.
 *
 * <h2>Defensive rejection</h2>
 * With auth enabled, an unauthenticated request never reaches this class at all — the secured
 * chain answers {@code 401} first. The try/catch below only guards the case Spring Security's own
 * {@code SecurityContextPrincipalResolver} calls out as "purely defensive": a resolver call
 * reaching this code with no authenticated principal on the context. Failing the handshake with
 * {@code 401} here rather than letting the exception propagate keeps that guarantee explicit and
 * gives an observable status code instead of a generic handshake failure.
 */
@Component
public class ManualControlHandshakeInterceptor implements HandshakeInterceptor {

    /** Session attribute key {@link ManualControlWebSocketHandler} reads the acting {@link UserId} from. */
    static final String ATTR_USER_ID = "manualControlUserId";
    /** Session attribute key {@link ManualControlWebSocketHandler} reads the acting {@link VisibilityScope} from. */
    static final String ATTR_SCOPE = "manualControlScope";

    private final CurrentUser currentUser;

    public ManualControlHandshakeInterceptor(CurrentUser currentUser) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UserId userId;
        VisibilityScope scope;
        try {
            userId = currentUser.userId();
            scope = currentUser.scope();
        } catch (RuntimeException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_SCOPE, scope);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                Exception exception) {
        // Nothing to do -- attribute stashing already happened in beforeHandshake.
    }
}
