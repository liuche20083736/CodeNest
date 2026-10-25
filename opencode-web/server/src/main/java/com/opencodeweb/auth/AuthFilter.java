package com.opencodeweb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.lang.Strings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Simple bearer-token auth for /api/** endpoints. /api/auth/** stays public.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserRepository users;

    public AuthFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        boolean publicPath = path.startsWith("/api/auth/register") || path.startsWith("/api/auth/login");
        if (publicPath || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            unauthorized(response, "missing bearer token");
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        String username = jwt.validate(token);
        if (username == null) {
            unauthorized(response, "invalid or expired token");
            return;
        }

        User user = users.findByUsername(username).orElse(null);
        if (user == null) {
            unauthorized(response, "user not found");
            return;
        }

        request.setAttribute("authUser", user);
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(java.util.Map.of("message", message)));
    }
}
