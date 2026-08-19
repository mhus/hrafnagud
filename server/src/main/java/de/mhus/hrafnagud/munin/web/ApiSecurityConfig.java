package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the token check in front of the operator API, and nothing else.
 *
 * <p>Three paths are deliberately outside it:
 * <ul>
 *   <li><b>The console</b> ({@code /}, {@code /app.js}, …). It contains no
 *       data and no credential — it asks for the token and holds it in the
 *       browser. Guarding it would mean asking for the token in order to
 *       reach the page that asks for the token.
 *   <li><b>{@code /actuator/**}</b>. The container health check calls
 *       {@code /actuator/health} with no credential, and a 401 there reads as
 *       an unhealthy container. What the actuator exposes is decided by
 *       {@code management.endpoints.web.exposure}.
 *   <li><b>{@code /ode/**}</b>. Those endpoints have their own keys, issued to
 *       a brain rather than to a person, and their guard is registered by the
 *       Ode auto-configuration.
 * </ul>
 */
@Configuration
@Slf4j
public class ApiSecurityConfig {

    /** Everything the operator API answers on. */
    static final String API_PATH_PATTERN = "/api/v1/**";

    @Bean
    public WebMvcConfigurer apiSecurityConfigurer(MuninProperties properties) {
        ApiTokenInterceptor interceptor =
                new ApiTokenInterceptor(properties.getApi().getToken());

        // Said once at startup rather than left to be discovered: an operator
        // who cannot remember which of the two states this instance is in has
        // to test it against a live endpoint, and the wrong answer is the one
        // that stays quiet.
        if (interceptor.isSecured()) {
            log.info("Operator API at {} requires a bearer token", API_PATH_PATTERN);
        } else {
            log.warn("Operator API at {} is UNAUTHENTICATED — anyone who reaches "
                            + "the port can read and delete. Set munin.api.token "
                            + "(HRAFNAGUD_API_TOKEN) unless something in front of it "
                            + "already authenticates.",
                    API_PATH_PATTERN);
        }

        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns(API_PATH_PATTERN);
            }
        };
    }

    /**
     * Serves the console from {@code classpath:/console/} at {@code /console/},
     * with {@code /} redirecting there.
     *
     * <p>The files live outside {@code static/} on purpose. Spring Boot serves
     * that directory unconditionally, so a console kept there could not be
     * switched off — and "off" has to mean "not served", not "served but
     * please do not use it".
     */
    @Bean
    @ConditionalOnProperty(prefix = "munin.api", name = "console-enabled",
            havingValue = "true", matchIfMissing = true)
    public WebMvcConfigurer consoleConfigurer() {
        log.info("Console enabled at /console/");
        return new WebMvcConfigurer() {

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/console/**")
                        .addResourceLocations("classpath:/console/");
            }

            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                // Both spellings, because a person types one and a link uses
                // the other, and a 404 on the bare host reads as "nothing is
                // running here".
                registry.addRedirectViewController("/", "/console/index.html");
                registry.addRedirectViewController("/console/", "/console/index.html");
            }
        };
    }
}
