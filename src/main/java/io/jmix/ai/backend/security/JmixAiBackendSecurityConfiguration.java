package io.jmix.ai.backend.security;

import io.jmix.core.JmixSecurityFilterChainOrder;
import io.jmix.security.util.JmixHttpSecurityUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This configuration complements standard security configurations that come from Jmix modules (security-flowui, oidc,
 * authserver).
 * <p>
 * You can configure custom API endpoints security by defining {@link SecurityFilterChain} beans in this class.
 * In most cases, custom SecurityFilterChain must be applied first, so the proper
 * {@link org.springframework.core.annotation.Order} should be defined for the bean. The order value from the
 * {@link io.jmix.core.JmixSecurityFilterChainOrder#CUSTOM} is guaranteed to be smaller than any other filter chain
 * order from Jmix.
 * <p>
 * Example:
 *
 * <pre>
 * &#064;Bean
 * &#064;Order(JmixSecurityFilterChainOrder.CUSTOM)
 * SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
 *     http.securityMatcher("/public/**")
 *             .authorizeHttpRequests(authorize ->
 *                     authorize.anyRequest().permitAll()
 *             );
 *     return http.build();
 * }
 * </pre>
 *
 * @see io.jmix.securityflowui.security.FlowuiVaadinWebSecurity
 */
@Configuration
public class JmixAiBackendSecurityConfiguration {

    /**
     * Endpoints reachable without authentication.
     * <p>
     * Only two actuator endpoints are listed here instead of {@code /actuator/**}:
     * {@code /actuator/health} is read by container probes and {@code /actuator/prometheus} is
     * scraped by the Prometheus container over the internal Docker network. Every other path under
     * {@code /actuator} falls through to the Jmix filter chain and requires a login. Which actuator
     * endpoints exist over HTTP at all is limited separately by
     * {@code management.endpoints.web.exposure.include} in {@code application.properties} - keep
     * both restrictions, so that widening one of them alone does not expose anything.
     */
    @Bean
    @Order(JmixSecurityFilterChainOrder.CUSTOM)
    SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/health", "/actuator/prometheus",
                        "/chat", "/chat/stream", "/api/**")
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable());
        JmixHttpSecurityUtils.configureAnonymous(http);
        return http.build();
    }
}