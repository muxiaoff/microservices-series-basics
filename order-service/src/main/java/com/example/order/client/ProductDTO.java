package com.example.order.client;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Product DTO for Feign client response
 */
@Data
public class ProductDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
}
