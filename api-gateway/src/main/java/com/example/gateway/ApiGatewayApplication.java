package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway - Unified entry point for all microservices
 *
 * Features:
 * - Route forwarding
 * - JWT authentication filter
 * - Rate limiting
 * - CORS handling
 * - Request logging
 * - Distributed tracing
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
