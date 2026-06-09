package com.example.order.service;

import com.example.common.exception.BusinessException;
import com.example.order.client.ProductClient;
import com.example.order.client.ProductDTO;
import com.example.order.client.UserClient;
import com.example.order.client.UserDTO;
import com.example.order.dto.OrderDetailResponse;
import com.example.order.dto.OrderRequest;
import com.example.order.entity.Order;
import com.example.order.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final ProductClient productClient;

    @Transactional
    @RateLimiter(name = "create-order", fallbackMethod = "createOrderRateLimitFallback")
    public Order createOrder(OrderRequest request) {
        // Fetch product info via Feign (with circuit breaker)
        ProductDTO product = getProduct(request.getProductId());

        // Calculate total price
        BigDecimal totalPrice = product.getPrice()
            .multiply(BigDecimal.valueOf(request.getQuantity()));

        // Create order
        Order order = Order.builder()
            .userId(request.getUserId())
            .productId(request.getProductId())
            .quantity(request.getQuantity())
            .unitPrice(product.getPrice())
            .totalPrice(totalPrice)
            .status("CREATED")
            .build();

        Order saved = orderRepository.save(order);
        log.info("Order created: id={}, userId={}, productId={}, totalPrice={}",
            saved.getId(), saved.getUserId(), saved.getProductId(), saved.getTotalPrice());

        return saved;
    }

    /**
     * Rate limiter fallback for createOrder
     */
    public Order createOrderRateLimitFallback(OrderRequest request, Exception e) {
        log.warn("Rate limit triggered for createOrder: {}", e.getMessage());
        throw new BusinessException(429, "System is busy, please try again later");
    }

    /**
     * Fetch product with circuit breaker + retry
     */
    @CircuitBreaker(name = "product-service", fallbackMethod = "getProductFallback")
    @Retry(name = "product-service")
    public ProductDTO getProduct(Long productId) {
        return productClient.getProductById(productId);
    }

    /**
     * Circuit breaker fallback for product service
     */
    public ProductDTO getProductFallback(Long productId, Exception e) {
        log.warn("Product service circuit breaker triggered for productId={}: {}", productId, e.getMessage());
        throw new BusinessException(503, "Product service unavailable, please try again later");
    }

    /**
     * Fetch user with circuit breaker - returns degraded result instead of throwing
     */
    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    @Retry(name = "user-service")
    public UserDTO getUser(Long userId) {
        return userClient.getUserById(userId);
    }

    /**
     * Circuit breaker fallback for user service - graceful degradation
     */
    public UserDTO getUserFallback(Long userId, Exception e) {
        log.warn("User service circuit breaker triggered for userId={}: {}", userId, e.getMessage());
        UserDTO fallback = new UserDTO();
        fallback.setId(userId);
        fallback.setName("Unknown User");
        return fallback;
    }

    /**
     * Get order detail with user and product info (demonstrates Feign client calls)
     */
    public OrderDetailResponse getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(404, "Order not found: " + orderId));

        // Fetch user info via Feign (with circuit breaker fallback)
        String userName = "Unknown";
        try {
            UserDTO user = getUser(order.getUserId());
            userName = user.getName();
        } catch (Exception e) {
            log.warn("Failed to fetch user info for userId={}: {}", order.getUserId(), e.getMessage());
        }

        // Fetch product info via Feign (with circuit breaker fallback)
        String productName = "Unknown";
        try {
            ProductDTO product = getProduct(order.getProductId());
            productName = product.getName();
        } catch (Exception e) {
            log.warn("Failed to fetch product info for productId={}: {}", order.getProductId(), e.getMessage());
        }

        return OrderDetailResponse.builder()
            .id(order.getId())
            .userId(order.getUserId())
            .userName(userName)
            .productId(order.getProductId())
            .productName(productName)
            .quantity(order.getQuantity())
            .unitPrice(order.getUnitPrice())
            .totalPrice(order.getTotalPrice())
            .status(order.getStatus())
            .createdAt(order.getCreatedAt())
            .build();
    }

    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "Order not found: " + id));
    }

    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Order cancelOrder(Long id) {
        Order order = orderRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "Order not found: " + id));

        if ("CANCELLED".equals(order.getStatus())) {
            throw new BusinessException(400, "Order already cancelled");
        }

        order.setStatus("CANCELLED");
        Order updated = orderRepository.save(order);
        log.info("Order cancelled: id={}", id);
        return updated;
    }
}
