package com.drones.vision.api;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular bundle shipped by {@code vision-web} and makes client-side routes
 * survive a page refresh.
 *
 * <p>The SPA owns paths like {@code /devices} and {@code /live/cam-1} that have no server-side
 * handler; without a fallback, refreshing one returns 404. Any request that is not an API call
 * and does not look like a file (no extension) therefore resolves to {@code index.html}, letting
 * the router take over. Requests that <em>do</em> look like files still 404 — serving HTML in
 * place of a missing script would surface as a confusing MIME-type error instead of the real
 * "asset not found".
 *
 * <p>{@code classpath:/static/} stays registered so the legacy Phase-1 console remains reachable
 * at {@code /legacy/} until the Debug tab supersedes it (docs/WEB-PLAN.md, W5).
 */
@Configuration
public class SpaResourceConfiguration implements WebMvcConfigurer {

    private static final String INDEX_PATH = "META-INF/resources/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/META-INF/resources/", "classpath:/static/",
                        "classpath:/public/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /** Resolves a real asset when one exists, and the SPA shell for client-side routes. */
    private static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (!isClientRoute(resourcePath)) {
                return null;
            }
            Resource index = new ClassPathResource(INDEX_PATH);
            return index.exists() ? index : null;
        }

        private static boolean isClientRoute(String resourcePath) {
            return !resourcePath.startsWith("api/")
                    && !resourcePath.startsWith("actuator/")
                    && !resourcePath.contains(".");
        }
    }
}
