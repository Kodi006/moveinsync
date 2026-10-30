package com.moveinsync.roster.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final Environment environment;

    public ApiKeyFilter(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        TenantPrincipal principal = resolve(apiKey);
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
            return;
        }

        TenantContext.set(principal);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled. Always clear identity before the thread is reused.
            TenantContext.clear();
        }
    }

    private TenantPrincipal resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        // API keys map to tenant:role. A JWT resolver can replace this lookup in the same filter.
        String mapping = environment.getProperty("roster.security.api-keys." + apiKey);
        if (mapping == null) return null;
        int separator = mapping.lastIndexOf(':');
        if (separator <= 0 || separator == mapping.length() - 1) return null;
        try {
            return new TenantPrincipal(mapping.substring(0, separator),
                    Role.valueOf(mapping.substring(separator + 1)));
        } catch (IllegalArgumentException invalidRole) {
            return null;
        }
    }
}
