package com.example.order.client;

import com.example.order.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client for Product Service.
 * Uses {@link FeignConfig} to automatically unwrap ApiResponse wrappers.
 */
@FeignClient(name = "product-service", path = "/products", configuration = FeignConfig.class)
public interface ProductClient {

    @GetMapping("/{id}")
    ProductDTO getProductById(@PathVariable("id") Long id);
}
