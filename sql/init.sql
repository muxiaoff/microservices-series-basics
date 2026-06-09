-- ====================================
-- Microservices Demo - Database Init
-- ====================================

-- User Database
CREATE DATABASE IF NOT EXISTS user_db;
USE user_db;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    role VARCHAR(20) DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Order Database
CREATE DATABASE IF NOT EXISTS order_db;
USE order_db;

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    total_price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Product Database
CREATE DATABASE IF NOT EXISTS product_db;
USE product_db;

CREATE TABLE IF NOT EXISTS products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    price DECIMAL(10, 2) NOT NULL,
    stock INT NOT NULL,
    category VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Sample Data
USE user_db;
INSERT INTO users (username, password, name, phone, email, role) VALUES
('zhangsan', 'password123', 'Zhang San', '13800138001', 'zhangsan@example.com', 'USER'),
('lisi', 'password123', 'Li Si', '13800138002', 'lisi@example.com', 'USER'),
('admin', 'admin123', 'Admin', '13800138000', 'admin@example.com', 'ADMIN');

USE product_db;
INSERT INTO products (name, description, price, stock, category) VALUES
('iPhone 15', 'Apple iPhone 15 Pro Max', 9999.00, 100, 'ELECTRONICS'),
('MacBook Pro', 'Apple MacBook Pro 16 inch', 19999.00, 50, 'ELECTRONICS'),
('AirPods Pro', 'Apple AirPods Pro 2nd Gen', 1899.00, 200, 'ELECTRONICS'),
('Java Book', 'Effective Java 3rd Edition', 89.00, 500, 'BOOKS'),
('Spring Boot Book', 'Spring Boot in Action', 69.00, 300, 'BOOKS');
