package com.ecommerce.paymentservice.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Checks the signature and expiry of an access token made by user-service.
 * This service never creates tokens.
 */
@Component
public class JwtTokenParser {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;

    public JwtTokenParser(@Value("${auth.token.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("auth.token.secret must be at least 32 characters (same value as user-service)");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    /**
     * Parses AND verifies the token. Throws ExpiredJwtException when past the exp claim,
     * JwtException when malformed or wrongly signed. The filter treats the two differently.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
