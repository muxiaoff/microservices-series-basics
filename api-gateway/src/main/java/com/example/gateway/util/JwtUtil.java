package com.example.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility for token validation in the API Gateway.
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:microservices-demo-secret-key-must-be-at-least-32-chars}")
    private String secret;

    @Value("${jwt.expiration:86400000}")  // 24 hours in ms
    private long expiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Validate the JWT token and return its claims.
     *
     * @param token JWT token string (without "Bearer " prefix)
     * @return parsed Claims
     * @throws JwtException if the token is invalid or expired
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * Check if a token is valid (not expired, correct signature).
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = validateToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extract the subject (usually username) from a token.
     */
    public String getSubject(String token) {
        return validateToken(token).getSubject();
    }

    /**
     * Extract a custom claim from the token.
     */
    public String getClaim(String token, String claimName) {
        return validateToken(token).get(claimName, String.class);
    }
}
