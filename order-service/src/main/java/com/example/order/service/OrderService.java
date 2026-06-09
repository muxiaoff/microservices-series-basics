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
    public Order createOrder(OrderRequest request) {
        // Fetch product info via Feign
        ProductDTO product;
        try {
            product = productClient.getProductById(request.getProductId());
        } catch (Exception e) {
            log.error("Failed to fetch product: {}", e.getMessage());
            throw new BusinessException(503, "Product service unavailable");
        }

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
     * Get order detail with user and product info (demonstrates Feign client calls)
     */
    public OrderDetailResponse getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(404, "Order not found: " + orderId));

        // Fetch user info via Feign
        String userName = "Unknown";
        try {
            UserDTO user = userClient.getUserById(order.getUserId());
            userName = user.getName();
        } catch (Exception e) {
            log.warn("Failed to fetch user info for userId={}: {}", order.getUserId(), e.getMessage());
        }

        // Fetch product info via Feign
        String productName = "Unknown";
        try {
            ProductDTO product = productClient.getProductById(order.getProductId());
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
public class OrderService {
    
}
