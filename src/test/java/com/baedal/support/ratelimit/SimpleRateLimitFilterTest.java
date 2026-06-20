package com.baedal.support.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimpleRateLimitFilterTest {

    @Test
    void allowsThirtyRequestsAndRejectsThirtyFirstInOneWindow() throws Exception {
        SimpleRateLimitFilter filter = new SimpleRateLimitFilter(() -> 1_000L);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 30; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request("203.0.113.7"), response, chain);
            assertThat(response.getStatus()).isNotEqualTo(429);
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilterInternal(request("203.0.113.7"), rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void usesFirstForwardedForAddressAsClientIp() throws Exception {
        SimpleRateLimitFilter filter = new SimpleRateLimitFilter(() -> 1_000L);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 30; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockHttpServletRequest request = request("10.0.0.10");
            request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.10");
            filter.doFilterInternal(request, response, chain);
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockHttpServletRequest request = request("10.0.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.10");
        filter.doFilterInternal(request, rejected, chain);

        assertThat(rejected.getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotFilterActuatorEndpoints() {
        SimpleRateLimitFilter filter = new SimpleRateLimitFilter(() -> 1_000L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
