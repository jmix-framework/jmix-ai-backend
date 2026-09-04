package io.jmix.ai.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires a shared key in the {@value #HEADER} header on the endpoints called by internal
 * applications ({@code /chat}, {@code /chat/stream}, {@code /api/**}).
 * <p>
 * The check is off until {@code api.auth.enabled} is set to {@code true}, so clients can be updated
 * to send the header one at a time before the key becomes mandatory.
 * <p>
 * Not a Spring bean on purpose: Spring Boot registers every {@code Filter} bean for all requests,
 * which would also demand the key on the anonymous actuator endpoints and on the admin UI. It is
 * instantiated by {@link JmixAiBackendSecurityConfiguration} inside the filter chain that matches
 * those endpoints and nothing else.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    // fixed body, so it needs no escaping; the shape matches the ProblemDetail responses
    // that spring.mvc.problemdetails.enabled produces for the other rejections of these endpoints
    private static final String UNAUTHORIZED_BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,\
            "detail":"Missing or invalid %s header"}""".formatted(HEADER);

    private final boolean enabled;
    private final byte[] expectedKey;

    public ApiKeyFilter(boolean enabled, String key) {
        if (enabled && key.isBlank()) {
            // failing to start is louder than rejecting every call, which looks like a broken backend
            throw new IllegalStateException("api.auth.enabled is true but api.auth.key is empty: "
                    + "set the JMIX_AI_API_KEY environment variable, or set api.auth.enabled=false");
        }
        this.enabled = enabled;
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || isKeyValid(request.getHeader(HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rejected {} {} from {}: missing or invalid {} header",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), HEADER);

        // written directly rather than through sendError, which would start an ERROR dispatch to
        // /error - a path this filter chain does not match, so the 401 would turn into a redirect
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    private boolean isKeyValid(String presented) {
        // constant-time comparison, so a caller cannot learn the key from how long the check takes
        return presented != null
                && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }
}
