package com.example.gateway.filter;

import com.example.gateway.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT authentication filter for API Gateway.
 *
 * Validates the Bearer token in the Authorization header before forwarding requests.
 * Passes user identity (username, role) downstream via request headers.
 *
 * Whitelist paths (no auth required):
 *  - POST /api/users  (registration)
 *  - Actuator endpoints
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** Paths that skip JWT validation entirely. */
    private static final List<String> WHITELIST = List.of(
        "/api/users",          // user registration (POST)
        "/actuator/**",
        "/api/*/actuator/**"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        // Allow OPTIONS (CORS pre-flight)
        if (HttpMethod.OPTIONS.equals(method)) {
            return chain.filter(exchange);
        }

        // Whitelist: user registration endpoint
        if (HttpMethod.POST.equals(method) && "/api/users".equals(path)) {
            return chain.filter(exchange);
        }

        // Whitelist: all other configured paths
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // Extract token from Authorization header
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return respondUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("Invalid or expired JWT token for path: {}", path);
            return respondUnauthorized(exchange, "Invalid or expired token");
        }

        // Extract user info and add to downstream request headers
        String username = jwtUtil.getSubject(token);
        String role = jwtUtil.getClaim(token, "role");

        ServerHttpRequest mutatedRequest = request.mutate()
            .header("X-User-Name", username)
            .header("X-User-Role", role != null ? role : "")
            .build();

        log.debug("JWT validated for user={}, role={}, path={}", username, role, path);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> respondUnauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory()
            .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // Run after logging filter (order -2) but before routing
    }
}
