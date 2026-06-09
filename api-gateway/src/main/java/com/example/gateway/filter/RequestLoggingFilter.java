package com.example.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Request logging filter - logs all requests passing through the gateway
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        log.info("[{}] {} {} from {}",
            requestId,
            request.getMethod(),
            request.getPath(),
            request.getRemoteAddress());

        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - startTime;
            log.info("[{}] {} {} - {} ({}ms)",
                requestId,
                request.getMethod(),
                request.getPath(),
                exchange.getResponse().getStatusCode(),
                duration);
        }));
    }

    @Override
    public int getOrder() {
        return -2; // Run before auth filter
    }
}
public class RequestLoggingFilter {
    
}
