package io.jmix.ai.backend.security;

import io.jmix.core.JmixSecurityFilterChainOrder;
import io.jmix.security.util.JmixHttpSecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

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
     * Endpoints called by internal applications - the MCP server and the website chat. No login,
     * but {@link ApiKeyFilter} requires a shared key once {@code api.auth.enabled} is turned on.
     * <p>
     * Separate from {@link #monitoringFilterChain}, so that the key is never demanded on the
     * actuator endpoints that Prometheus and the container probes read without one.
     */
    @Bean
    @Order(JmixSecurityFilterChainOrder.CUSTOM)
    SecurityFilterChain publicApiFilterChain(HttpSecurity http,
                                             @Value("${api.auth.enabled}") boolean apiAuthEnabled,
                                             @Value("${api.auth.key}") String apiAuthKey)
            throws Exception {
        http.securityMatcher("/chat", "/chat/stream", "/api/**")
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new ApiKeyFilter(apiAuthEnabled, apiAuthKey),
                        AnonymousAuthenticationFilter.class);
        JmixHttpSecurityUtils.configureAnonymous(http);
        return http.build();
    }

    /**
     * Actuator endpoints reachable without authentication: {@code /actuator/health} is read by
     * container probes and {@code /actuator/prometheus} is scraped by the Prometheus container over
     * the internal Docker network. Every other path under {@code /actuator} falls through to the
     * Jmix filter chain and requires a login. Which actuator endpoints exist over HTTP at all is
     * limited separately by {@code management.endpoints.web.exposure.include} in
     * {@code application.properties} - keep both restrictions, so that widening one of them alone
     * does not expose anything.
     */
    @Bean
    @Order(JmixSecurityFilterChainOrder.CUSTOM + 1)
    SecurityFilterChain monitoringFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/health", "/actuator/prometheus")
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable());
        JmixHttpSecurityUtils.configureAnonymous(http);
        return http.build();
    }
}