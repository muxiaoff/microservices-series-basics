# 微服务系列 13 - 微服务安全

> 系列文章目录：[微服务从小白到精通](./README.md)

## 1. 微服务安全概述

微服务的安全比单体应用更复杂，因为：

- 攻击面更大（更多的服务端点）
- 服务间通信需要保护
- 身份验证和授权需要分布式处理
- 数据在更多地方流转

### 1.1 安全层次

```
┌─────────────────────────────────────────────┐
│             外部安全                          │
│  ├── 边界安全（WAF、DDoS 防护）               │
│  ├── API 网关安全（认证、限流）                │
│  └── 传输安全（TLS/HTTPS）                    │
├─────────────────────────────────────────────┤
│             服务间安全                        │
│  ├── 服务认证（mTLS）                         │
│  ├── 服务授权（RBAC/ABAC）                    │
│  └── 通信加密                                 │
├─────────────────────────────────────────────┤
│             数据安全                          │
│  ├── 数据加密（静态 + 传输）                   │
│  ├── 数据脱敏                                 │
│  └── 审计日志                                 │
└─────────────────────────────────────────────┘
```

---

## 2. 身份认证（Authentication）

### 2.1 JWT 认证流程

```
┌──────┐  1.登录    ┌──────────┐  2.验证   ┌──────────┐
│Client │─────────▶│API Gateway│─────────▶│Auth Svc  │
└──────┘          └──────────┘◀─────────└──────────┘
                       │  3.返回 JWT
                       │
     ┌─────────────────┼──────────────────┐
     ▼                 ▼                  ▼
┌──────────┐    ┌──────────┐      ┌──────────┐
│User Svc  │    │Order Svc │      │Product Svc│
│验证JWT    │    │验证JWT    │      │验证JWT    │
└──────────┘    └──────────┘      └──────────┘
```

### 2.2 JWT 实现

**生成 Token：**

```java
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")  // 默认 24 小时
    private long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList());

        return Jwts.builder()
            .claims(claims)
            .subject(userDetails.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiration))
            .signWith(getSigningKey())
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
```

**Gateway 鉴权过滤器：**

```java
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtService jwtService;

    private static final List<String> WHITE_LIST = List.of(
        "/api/auth/login",
        "/api/auth/register"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange);
        
        if (token == null || !jwtService.isTokenValid(token)) {
            return unauthorized(exchange);
        }

        Claims claims = jwtService.parseToken(token);
        
        // 将用户信息传递给下游服务
        ServerHttpRequest request = exchange.getRequest().mutate()
            .header("X-User-Id", claims.getSubject())
            .header("X-User-Roles", String.join(",", 
                (List<String>) claims.get("roles")))
            .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
```

---

## 3. 授权（Authorization）

### 3.1 RBAC（基于角色的访问控制）

```java
// 自定义注解
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    String[] value();
}

// 切面实现
@Aspect
@Component
public class AuthorizationAspect {

    @Autowired
    private HttpServletRequest request;

    @Before("@annotation(requiresRole)")
    public void checkPermission(JoinPoint joinPoint, RequiresRole requiresRole) {
        String userRoles = request.getHeader("X-User-Roles");
        Set<String> userRoleSet = Set.of(userRoles.split(","));
        Set<String> requiredRoles = Set.of(requiresRole.value());

        if (userRoleSet.stream().noneMatch(requiredRoles::contains)) {
            throw new AccessDeniedException("权限不足");
        }
    }
}

// 使用
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @RequiresRole("ADMIN")
    @DeleteMapping("/users/{id}")
    public void deleteUser(@PathVariable Long id) {
        // 只有 ADMIN 角色可以删除用户
    }
}
```

### 3.2 方法级安全

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), 
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

---

## 4. OAuth2 + Spring Authorization Server

### 4.1 OAuth2 流程

```
┌──────┐                     ┌──────────┐
│      │  1.授权请求          │          │
│Client │────────────────────▶│Auth      │
│      │◀────────────────────│Server    │
│      │  2.授权码            │          │
│      │────────────────────▶│          │
│      │◀────────────────────│          │
│      │  3.Access Token     │          │
└──────┘                     └──────────┘
    │
    │  4.携带 Token 请求
    ▼
┌──────────┐
│Resource  │
│Server    │
└──────────┘
```

### 4.2 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

---

## 5. 服务间安全

### 5.1 mTLS（双向 TLS）

```
单向 TLS：客户端验证服务端证书
mTLS：双方互相验证证书

┌──────────┐        ┌──────────┐
│Service A │◀──────▶│Service B │
│  证书 A   │  验证   │  证书 B   │
│          │◀──────▶│          │
└──────────┘        └──────────┘

实现方式：
1. Istio Service Mesh（推荐）
2. Spring Cloud + Vault PKI
3. 自建证书管理
```

### 5.2 API Key / Service Token

```java
// 服务间调用时携带 Service Token
@Component
public class ServiceAuthInterceptor implements RequestInterceptor {

    @Value("${service.auth.token}")
    private String serviceToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Service-Token", serviceToken);
    }
}

// 下游服务验证
@Component
public class ServiceAuthFilter extends OncePerRequestFilter {

    @Value("${service.auth.token}")
    private String validToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) 
            throws ServletException, IOException {
        
        // 内部调用验证
        String serviceToken = request.getHeader("X-Service-Token");
        if (serviceToken != null) {
            if (!validToken.equals(serviceToken)) {
                response.sendError(403, "Invalid service token");
                return;
            }
        }
        
        chain.doFilter(request, response);
    }
}
```

---

## 6. 数据安全

### 6.1 敏感数据加密

```java
// 使用 JPA AttributeConverter 加密数据库字段
@Converter
public class EncryptionConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    
    @Value("${encryption.key}")
    private String encryptionKey;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return decrypt(dbData);
    }
    
    // encrypt/decrypt 实现...
}

// 使用
@Entity
public class User {
    @Convert(converter = EncryptionConverter.class)
    private String idCard;  // 身份证号加密存储
    
    @Convert(converter = EncryptionConverter.class)
    private String phone;   // 手机号加密存储
}
```

### 6.2 数据脱敏

```java
public class MaskUtils {

    // 手机号脱敏：138****1234
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    // 身份证脱敏：110***********1234
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) return idCard;
        return idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length() - 4);
    }

    // 邮箱脱敏：t***@example.com
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        String[] parts = email.split("@");
        return parts[0].charAt(0) + "***@" + parts[1];
    }
}
```

---

## 7. 安全检查清单

| 检查项 | 说明 |
|--------|------|
| ✅ HTTPS | 所有通信使用 TLS |
| ✅ 认证 | 每个请求都验证身份 |
| ✅ 授权 | 最小权限原则 |
| ✅ 输入校验 | 防止注入攻击 |
| ✅ 敏感数据加密 | 静态 + 传输加密 |
| ✅ 数据脱敏 | 日志和 API 响应中脱敏 |
| ✅ 限流 | 防止暴力破解和 DDoS |
| ✅ CORS | 限制跨域访问 |
| ✅ CSRF | 防止跨站请求伪造 |
| ✅ 依赖安全 | 定期扫描依赖漏洞 |
| ✅ 密钥管理 | 使用 Vault/KMS |
| ✅ 审计日志 | 记录关键操作 |

---

**上一篇：** [12 - 可观测性](./12-observability.md)  
**下一篇：** [14 - 最佳实践与总结](./14-best-practices.md)
