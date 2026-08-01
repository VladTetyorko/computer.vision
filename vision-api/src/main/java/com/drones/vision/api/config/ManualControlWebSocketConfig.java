package com.drones.vision.api.config;

import com.drones.vision.api.ManualControlHandshakeInterceptor;
import com.drones.vision.api.ManualControlWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Objects;

/**
 * Registers {@link ManualControlWebSocketHandler} at {@code /ws/manual-control}
 * (docs/RC-CONTROL-PHASE1-PLAN.md §4), guarded by {@link ManualControlHandshakeInterceptor}.
 *
 * <p><strong>Origin policy: same-origin (Spring's own default).</strong> Neither {@link
 * org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration#setAllowedOrigins}
 * nor {@code setAllowedOriginPatterns} is called here, so Spring's built-in {@code
 * OriginHandshakeInterceptor} enforces same-origin by default — matching every other endpoint in
 * this app (there is no CORS configuration anywhere in {@code vision-api}/{@code vision-app}; the
 * SPA and the API/SSE/WebSocket endpoints are always served from one origin, and auth rides a
 * same-origin session cookie, not a bearer token — see {@code SecurityConfig}'s own javadoc).
 */
@Configuration
@EnableWebSocket
public class ManualControlWebSocketConfig implements WebSocketConfigurer {

    private final ManualControlWebSocketHandler handler;
    private final ManualControlHandshakeInterceptor handshakeInterceptor;

    public ManualControlWebSocketConfig(ManualControlWebSocketHandler handler,
                                        ManualControlHandshakeInterceptor handshakeInterceptor) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.handshakeInterceptor =
                Objects.requireNonNull(handshakeInterceptor, "handshakeInterceptor must not be null");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/manual-control").addInterceptors(handshakeInterceptor);
    }
}
