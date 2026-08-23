package com.drones.vision.app.config.wiring;

import com.drones.vision.api.ratelimit.RateLimitFilter;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.app.config.properties.VisionApiProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The rate limiter is the one wave of docs/plans/done/SCALE-100-PLAN.md that ships <em>off</em>,
 * so "off" is the behavior worth pinning: a filter that quietly registered anyway would 429
 * legitimate traffic in the default (auth-disabled, one-shared-principal) configuration this app
 * runs in today — see {@code VisionApiProperties.RateLimit}'s javadoc for why that keying makes the
 * default non-negotiable rather than merely cautious.
 *
 * <p>An {@link ApplicationContextRunner} rather than a {@code @SpringBootTest}: this asks a question
 * about one {@code @Configuration}'s condition, and the full context would drag in Postgres, Docker
 * and sixty unrelated beans to answer it.
 */
class RateLimitWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CurrentUserStub.class, RateLimitWiring.class)
            .withPropertyValues("spring.main.web-application-type=none");

    @Test
    void registersNoFilterWhenTheLimitIsNotExplicitlyEnabled() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void registersNoFilterWhenTheLimitIsExplicitlyDisabled() {
        contextRunner.withPropertyValues("vision.api.rate-limit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void registersTheFilterOnApiPathsOnlyWhenEnabled() {
        contextRunner.withPropertyValues("vision.api.rate-limit.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(RateLimitFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
        });
    }

    /**
     * The filter reads the budget at construction time, so a misbound property would be invisible
     * until a real 429 happened — which is exactly the situation nobody is watching for.
     */
    @Test
    void takesItsBudgetFromTheConfiguredPermitsPerMinute() {
        contextRunner
                .withPropertyValues("vision.api.rate-limit.enabled=true", "vision.api.rate-limit.permits-per-minute=7")
                .run(context -> assertThat(context.getBean(VisionApiProperties.class).rateLimit().permitsPerMinute())
                        .isEqualTo(7));
    }

    @Configuration(proxyBeanMethods = false)
    static class CurrentUserStub {

        @Bean
        CurrentUser currentUser() {
            return new CurrentUser(mock(PrincipalResolver.class));
        }
    }
}
