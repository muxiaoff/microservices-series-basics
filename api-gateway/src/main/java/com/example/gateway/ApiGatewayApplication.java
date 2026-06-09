package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

/**
 * API Gateway - Unified entry point for all microservices
 *
 * Features:
 * - Route forwarding
 * - Authentication filter
 * - Rate limiting
 * - CORS handling
 * - Request logging
 */
@SpringBootApplication
@EnableEurekaClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
public class ApiGatewayApplication {
    
}
