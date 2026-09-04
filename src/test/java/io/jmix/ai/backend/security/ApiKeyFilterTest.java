package io.jmix.ai.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyFilterTest {

    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static MockHttpServletRequest request(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat");
        if (key != null) {
            request.addHeader(ApiKeyFilter.HEADER, key);
        }
        return request;
    }

    private boolean reachedTheEndpoint() {
        return chain.getRequest() != null;
    }

    @Test
    void passesEveryRequestThroughWhileTheCheckIsOff() throws Exception {
        new ApiKeyFilter(false, "").doFilter(request(null), response, chain);

        assertThat(reachedTheEndpoint()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void passesTheRequestThroughWhenTheKeyMatches() throws Exception {
        new ApiKeyFilter(true, "secret").doFilter(request("secret"), response, chain);

        assertThat(reachedTheEndpoint()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsAMissingHeaderWithoutCallingTheEndpoint() throws Exception {
        new ApiKeyFilter(true, "secret").doFilter(request(null), response, chain);

        assertThat(reachedTheEndpoint()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":401", ApiKeyFilter.HEADER);
    }

    @Test
    void rejectsAWrongKeyWithoutCallingTheEndpoint() throws Exception {
        new ApiKeyFilter(true, "secret").doFilter(request("guess"), response, chain);

        assertThat(reachedTheEndpoint()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Rejecting every call would look like a broken backend; refusing to start names the cause.
     */
    @Test
    void refusesToBeCreatedWhenTheCheckIsOnWithNoKeyConfigured() {
        assertThatThrownBy(() -> new ApiKeyFilter(true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JMIX_AI_API_KEY");
    }
}
