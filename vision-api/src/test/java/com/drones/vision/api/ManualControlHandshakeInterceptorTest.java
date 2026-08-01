package com.drones.vision.api;

import com.drones.vision.application.VisibilityScope;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ManualControlHandshakeInterceptor} — the WebSocket counterpart to every
 * REST controller reading {@link CurrentUser} (docs/RC-CONTROL-PHASE1-PLAN.md §4).
 */
class ManualControlHandshakeInterceptorTest {

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());

    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);
        attributes = new HashMap<>();
    }

    @Test
    void beforeHandshakeStashesTheCurrentUsersIdentityIntoTheSessionAttributes() {
        CurrentUser currentUser = new CurrentUser(ownership);
        ManualControlHandshakeInterceptor interceptor = new ManualControlHandshakeInterceptor(currentUser);

        boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertTrue(proceed);
        assertEquals(ownerId, attributes.get(ManualControlHandshakeInterceptor.ATTR_USER_ID));
        assertEquals(VisibilityScope.unbounded(), attributes.get(ManualControlHandshakeInterceptor.ATTR_SCOPE));
    }

    @Test
    void beforeHandshakeRejectsWith401WhenTheCurrentUserCannotResolveAnIdentity() {
        CurrentUser currentUser = new CurrentUser(unauthenticatedResolver());
        ManualControlHandshakeInterceptor interceptor = new ManualControlHandshakeInterceptor(currentUser);

        boolean proceed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertFalse(proceed);
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    /** Mirrors the "purely defensive" guard {@code SecurityContextPrincipalResolver} documents for
     *  an unauthenticated request reaching a supposedly-secured seam. */
    private static PrincipalResolver unauthenticatedResolver() {
        return new PrincipalResolver() {
            @Override
            public UserId userId() {
                throw new IllegalStateException("no authenticated principal on the current request");
            }

            @Override
            public Ownership ownership() {
                throw new IllegalStateException("no authenticated principal on the current request");
            }

            @Override
            public VisibilityScope scope() {
                throw new IllegalStateException("no authenticated principal on the current request");
            }
        };
    }
}
