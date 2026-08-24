package com.drones.vision.app.config.wiring;

import com.drones.vision.api.support.AuxFunctionCatalog;
import com.drones.vision.app.config.properties.VisionControlProperties;
import com.drones.vision.flight.application.ControlProfileService;
import com.drones.vision.flight.application.DefaultControlProfileService;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for controller layouts — the operator's own per-control bindings (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md wave C6).
 *
 * <p>Its own {@code @Configuration} rather than three more beans on {@link ApplicationServiceWiring}
 * because the feature spans two seams that read oddly next to each other there: an application
 * service over a JPA port, and a framework-free settings record handed to a {@code vision-api}
 * controller.
 *
 * <p>Unconditional, like every other persistence-backed service in this app: there is no flag to
 * turn controller setup off, because the fallback is not "no layout" but the platform's built-in one
 * ({@code ControlProfile.forKind}), which is what an operator flies with until they save something
 * of their own (decision C7).
 */
@Configuration
@EnableConfigurationProperties(VisionControlProperties.class)
public class ControlProfileWiring {

    /**
     * The operator's saved layouts, over the Postgres-backed repository wired in {@link
     * PersistenceWiringConfiguration}.
     *
     * @param repository the stored profiles
     * @return the service
     */
    @Bean
    public ControlProfileService controlProfileService(ControlProfileRepositoryPort repository) {
        return new DefaultControlProfileService(repository);
    }

    /**
     * The aux-function menu the setup page offers, from {@code vision.control.aux-functions} where a
     * deployment configured one and from the built-in catalogue otherwise.
     *
     * <p>Never a whitelist: {@code POST /api/assets/{id}/aux-function} accepts any number in range,
     * so a short menu narrows what is easy to pick rather than what is possible to send.
     *
     * @param properties this deployment's configured menu, empty by default
     * @return the catalogue handed to {@code ControlProfileController}
     */
    @Bean
    public AuxFunctionCatalog auxFunctionCatalog(VisionControlProperties properties) {
        if (properties.auxFunctions().isEmpty()) {
            return AuxFunctionCatalog.defaults();
        }
        return new AuxFunctionCatalog(properties.auxFunctions().stream()
                .map(function -> new AuxFunctionCatalog.Function(function.number(), function.label()))
                .toList());
    }
}
