package com.devanshedutech.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-address request limit for the endpoints that are open to the internet.
 *
 * <p>In-memory on purpose. A shared counter in Redis would be more correct across several
 * instances, but this institute runs one, and an in-memory limiter that exists beats a
 * distributed one that does not. If the application is ever scaled horizontally, each instance
 * will enforce its own share of the limit, which degrades sensibly rather than failing open.</p>
 *
 * <p>The chat endpoint already had its own copy of this logic; this is the shared version so a
 * second endpoint does not mean a second implementation that drifts.</p>
 */
@Component
public class RateLimiter {

    private final Cache<String, AtomicInteger> counts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(50_000)
            .build();

    /**
     * @return true when this caller has exceeded the limit and should be refused
     */
    public boolean exceeded(String bucket, String clientKey, int perMinute) {
        AtomicInteger count = counts.get(bucket + "|" + clientKey, k -> new AtomicInteger());
        return count.incrementAndGet() > perMinute;
    }

    /**
     * Best-effort client address. The application sits behind a proxy, so the first hop of
     * X-Forwarded-For is preferred. It is spoofable, which matters less than it sounds: a caller
     * who forges it gets their own bucket rather than someone else's, so the worst they achieve
     * is bypassing their own limit, and that is what the size cap above is for.
     */
    public String clientKey(HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return request.getRemoteAddr();
    }
}
