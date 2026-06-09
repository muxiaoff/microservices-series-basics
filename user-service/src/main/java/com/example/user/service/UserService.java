package com.example.user.service;

import com.example.common.exception.BusinessException;
import com.example.user.dto.UserRequest;
import com.example.user.dto.UserResponse;
import com.example.user.entity.User;
import com.example.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse createUser(UserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(400, "Username already exists: " + request.getUsername());
        }

        User user = User.builder()
            .username(request.getUsername())
            .password(request.getPassword()) // In production, use encrypted password
            .name(request.getName())
            .phone(request.getPhone())
            .email(request.getEmail())
            .build();

        User saved = userRepository.save(user);
        log.info("User created: id={}, username={}", saved.getId(), saved.getUsername());

        return toResponse(saved);
    }

    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "User not found: " + id));
        return toResponse(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public UserResponse updateUser(Long id, UserRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "User not found: " + id));

        user.setName(request.getName());
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());

        User updated = userRepository.save(user);
        log.info("User updated: id={}", updated.getId());

        return toResponse(updated);
    }

    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new BusinessException(404, "User not found: " + id);
        }
        userRepository.deleteById(id);
        log.info("User deleted: id={}", id);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
            .id(user.getId())
            .username(user.getUsername())
            .name(user.getName())
            .phone(user.getPhone())
            .email(user.getEmail())
            .role(user.getRole())
            .createdAt(user.getCreatedAt())
            .build();
    }
}
