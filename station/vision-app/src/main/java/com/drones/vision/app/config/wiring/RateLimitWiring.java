package com.drones.vision.app.config.wiring;

import com.drones.vision.api.ratelimit.RateLimitFilter;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.app.config.properties.VisionApiProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@code vision-api}'s per-principal request limiter
 * (docs/plans/done/SCALE-100-PLAN.md S6 item 3), and only when an operator asks for it.
 *
 * <p>Its own {@code @Configuration} class rather than a method on {@code ApplicationServiceWiring}
 * for the same reason {@code DiscoveryWiringConfiguration} and {@code PublishWiring} are: this is a
 * whole edge concern that is either present or absent, and a class-level condition says that more
 * plainly than a condition buried on one bean method among sixty.
 *
 * <p>See {@code VisionApiProperties.RateLimit} for why the default is off — with auth disabled,
 * every caller in the deployment shares one bucket, so a limit that is correct under auth is an
 * outage without it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vision.api.rate-limit", name = "enabled", havingValue = "true")
// Declared here as well as in PublishWiring: this class must not silently depend on another
// @Configuration having enabled the properties it reads.
@EnableConfigurationProperties(VisionApiProperties.class)
public class RateLimitWiring {

    /**
     * Registered through {@link FilterRegistrationBean} rather than as a bare {@code Filter} bean so
     * the URL pattern is declared here, at the wiring layer, instead of being implied by the
     * filter's own {@code shouldNotFilter}. Order is left at the default lowest precedence
     * deliberately: the filter reads {@link CurrentUser}, which only has an answer after Spring
     * Security has run.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(CurrentUser currentUser,
                                                                               VisionApiProperties apiProperties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(currentUser, apiProperties.rateLimit().permitsPerMinute()));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
