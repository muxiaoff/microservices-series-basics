package com.example.order.client;

import lombok.Data;

/**
 * User DTO for Feign client response
 * Note: This is a simplified DTO that matches the User Service response format.
 * The response is wrapped in ApiResponse, so we use the inner data structure.
 */
@Data
public class UserDTO {
    private Long id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private String role;
}
public class UserDTO {
    
}
