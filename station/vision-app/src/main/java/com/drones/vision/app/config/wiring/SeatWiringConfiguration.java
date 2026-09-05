package com.drones.vision.app.config.wiring;

import com.drones.vision.api.security.SeatAccessSettings;
import com.drones.vision.app.config.properties.VisionCrewProperties;
import com.drones.vision.flight.application.seat.DefaultSeatService;
import com.drones.vision.flight.application.seat.SeatService;
import com.drones.vision.platform.AuditTrailPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the two-seat model (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6, wave W2): the one
 * {@link SeatService} bean, and the framework-free {@link SeatAccessSettings} mirror {@code
 * com.drones.vision.api.security.SeatAccess} actually consumes (vision-api may not depend on Spring's
 * {@code @ConfigurationProperties} machinery) — the same bridge-properties shape {@code
 * OpsWiringConfiguration#opsThresholds} already establishes for a config-backed vision-api bean.
 *
 * <p>{@link #seatService} is wired <b>unconditionally</b>, regardless of {@link
 * VisionCrewProperties#enabled()}: it is a cheap, in-heap registry with no side effects until
 * something actually calls {@code take}/{@code preempt}/{@code forceRelease} on it, and {@code
 * SeatAccess}'s own pass-through (gated on {@link SeatAccessSettings#enabled()}) is what keeps it
 * inert when the feature is off — see that class's own javadoc. The RC-release hook registration in
 * {@link ApplicationServiceWiring#manualControlService} is separately gated on the same flag, so no
 * listener is ever registered while the feature is off either.
 */
@Configuration
@EnableConfigurationProperties(VisionCrewProperties.class)
public class SeatWiringConfiguration {

    @Bean
    public SeatService seatService(AuditTrailPort auditTrailPort, VisionCrewProperties properties) {
        return new DefaultSeatService(Clock.systemUTC(), auditTrailPort, properties.seatTtlMs());
    }

    @Bean
    public SeatAccessSettings seatAccessSettings(VisionCrewProperties properties) {
        return new SeatAccessSettings(properties.enabled(), properties.seatTtlMs());
    }
}
