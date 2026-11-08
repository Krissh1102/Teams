package com.teamformation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window, in-memory rate limiter (single instance; use Redis/bucket4j-jdbc to scale out).
 * Limits: auth endpoints per IP; invite-code join attempts per user AND per IP.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Pattern JOIN = Pattern.compile("^/api/teams/[^/]+/join$");
    private static final long WINDOW_MS = 60_000;

    private final AppProperties.RateLimit cfg;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties.RateLimit cfg) { this.cfg = cfg; }

    boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        boolean[] ok = {false};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w[0] >= WINDOW_MS) { ok[0] = true; return new long[]{now, 1}; }
            if (w[1] < limit) { w[1]++; ok[0] = true; }
            return w;
        });
        if (windows.size() > 100_000) windows.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MS);
        return ok[0];
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (cfg.enabled() && "POST".equals(req.getMethod())) {
            String path = req.getRequestURI();
            String ip = req.getRemoteAddr();
            boolean ok = true;
            if (path.startsWith("/api/auth/")) {
                ok = allow("auth:" + ip, cfg.authPerMinutePerIp());
            } else if (JOIN.matcher(path).matches()) {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                if (a != null && a.getPrincipal() instanceof UUID uid) {
                    ok = allow("join-user:" + uid, cfg.joinPerMinutePerUser());
                }
                ok = ok && allow("join-ip:" + ip, cfg.joinPerMinutePerIp());
            }
            if (!ok) {
                res.setStatus(429);
                res.setHeader("Retry-After", "60");
                res.setContentType("application/json");
                res.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts, slow down\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
