package com.baedal.support.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongSupplier;

@Component
@Order(1)
public class SimpleRateLimitFilter extends OncePerRequestFilter {

    static final long WINDOW_MILLIS = 60_000L;
    static final int MAX_REQUESTS_PER_WINDOW = 30;

    private final ConcurrentHashMap<String, Deque<Long>> history = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SimpleRateLimitFilter() {
        this(System::currentTimeMillis);
    }

    SimpleRateLimitFilter(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIp(request);
        Deque<Long> timestamps = history.computeIfAbsent(ip, ignored -> new ConcurrentLinkedDeque<>());
        long now = clock.getAsLong();

        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MILLIS) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"RATE_LIMITED\"}");
            return;
        }

        timestamps.addLast(now);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }
}
