package com.example.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserResponse {

    private Long id;
    private String username;
    private String name;
    private String phone;
    private String email;
    private String role;
    private LocalDateTime createdAt;
}
