package com.example.order.client;

import com.example.order.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client for User Service.
 * Uses {@link FeignConfig} to automatically unwrap ApiResponse wrappers.
 */
@FeignClient(name = "user-service", path = "/users", configuration = FeignConfig.class)
public interface UserClient {

    @GetMapping("/{id}")
    UserDTO getUserById(@PathVariable("id") Long id);
}
