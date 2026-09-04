package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for session auth ({@code vision.auth.*}), docs/plans/active/AUTH-ROLES-PLAN.md §3.6, wave B5.
 *
 * <p><b>Scope note.</b> §3.6/D2's full target shape is one record covering {@code enabled},
 * {@code session.*} <em>and</em> {@code password.*} — "three components cannot disagree about a
 * value that exists once." Wave B5's own file list names only "{@code VisionAuthProperties} (the
 * {@code session.*} block)", narrower than D2's full scope; {@code enabled} ({@link
 * com.drones.vision.app.config.SecurityConfig}'s {@code @ConditionalOnProperty}s and {@link
 * com.drones.vision.app.config.wiring.AuthWiringConfiguration}'s own) and {@code password.*}
 * ({@code AuthController}/{@code BootstrapController}/{@code AuthPasswordController}/{@code
 * PasswordPolicy}'s scattered {@code @Value}s) are deliberately left untouched by this wave — a
 * later wave folds them into {@link #session()} siblings here, at which point every one of those
 * classes reads this record instead of its own default.
 *
 * @param session idle-timeout/kiosk-idle-timeout/cookie-secure — see {@link Session}
 */
@ConfigurationProperties(prefix = "vision.auth")
public record VisionAuthProperties(Session session) {

    public VisionAuthProperties {
        if (session == null) {
            session = new Session(Session.DEFAULT_IDLE_TIMEOUT_DURATION, Session.DEFAULT_KIOSK_IDLE_TIMEOUT_DURATION,
                    Session.DEFAULT_COOKIE_SECURE_BOOLEAN);
        }
    }

    /**
     * @param idleTimeout      a normal login's idle window (docs/plans/active/AUTH-ROLES-PLAN.md §3.7, wave
     *                         B3) — the session's {@code maxInactiveInterval}, reset only by a fresh
     *                         request; no absolute expiry. Default {@code 12h}.
     * @param kioskIdleTimeout the idle window a {@code kiosk} login gets instead — refused by {@code
     *                         AuthController} for anything but a {@link
     *                         com.drones.vision.identity.domain.model.Role#VIEWER}, the long-lived
     *                         window an always-on wall display needs. Default {@code 365d}.
     * @param cookieSecure     whether the session cookie carries the {@code Secure} attribute
     *                         ({@code application.yaml}'s {@code server.servlet.session.cookie.secure}
     *                         binds to this key by placeholder interpolation, see that file). Default
     *                         {@code false} — this station is plain HTTP by default (CLAUDE.md
     *                         "Deployment maintenance": operators run it on servers they control);
     *                         a {@code Secure} cookie over plain HTTP is silently dropped by the
     *                         browser, which would look like login "not sticking". Flip to
     *                         {@code true} only once a deployment terminates TLS in front of this
     *                         station.
     */
    public record Session(@DefaultValue("12h") Duration idleTimeout,
                           @DefaultValue("365d") Duration kioskIdleTimeout,
                           @DefaultValue("false") boolean cookieSecure) {

        static final Duration DEFAULT_IDLE_TIMEOUT_DURATION = Duration.ofHours(12);
        static final Duration DEFAULT_KIOSK_IDLE_TIMEOUT_DURATION = Duration.ofDays(365);
        static final boolean DEFAULT_COOKIE_SECURE_BOOLEAN = false;

        public Session {
            if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "vision.auth.session.idle-timeout must be positive, was " + idleTimeout);
            }
            if (kioskIdleTimeout == null || kioskIdleTimeout.isNegative() || kioskIdleTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "vision.auth.session.kiosk-idle-timeout must be positive, was " + kioskIdleTimeout);
            }
        }
    }
}
