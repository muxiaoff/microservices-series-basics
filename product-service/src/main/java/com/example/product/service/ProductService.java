package com.example.product.service;

import com.example.common.exception.BusinessException;
import com.example.product.dto.ProductRequest;
import com.example.product.entity.Product;
import com.example.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public Product createProduct(ProductRequest request) {
        Product product = Product.builder()
            .name(request.getName())
            .description(request.getDescription())
            .price(request.getPrice())
            .stock(request.getStock())
            .category(request.getCategory())
            .build();

        Product saved = productRepository.save(product);
        log.info("Product created: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "Product not found: " + id));
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public List<Product> getProductsByCategory(String category) {
        return productRepository.findByCategory(category);
    }

    public List<Product> searchProducts(String name) {
        return productRepository.findByNameContaining(name);
    }

    @Transactional
    public Product updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "Product not found: " + id));

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());
        product.setCategory(request.getCategory());

        Product updated = productRepository.save(product);
        log.info("Product updated: id={}", updated.getId());
        return updated;
    }

    @Transactional
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new BusinessException(404, "Product not found: " + id);
        }
        productRepository.deleteById(id);
        log.info("Product deleted: id={}", id);
    }

    @Transactional
    public Product deductStock(Long id, Integer quantity) {
        Product product = getProductById(id);
        if (product.getStock() < quantity) {
            throw new BusinessException(400, "Insufficient stock: available=" + product.getStock() + ", requested=" + quantity);
        }
        product.setStock(product.getStock() - quantity);
        Product updated = productRepository.save(product);
        log.info("Stock deducted: productId={}, quantity={}, remaining={}", id, quantity, updated.getStock());
        return updated;
    }
}
public class ProductService {
    
}
