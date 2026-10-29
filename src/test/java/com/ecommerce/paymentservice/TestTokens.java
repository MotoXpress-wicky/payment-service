package com.ecommerce.paymentservice;

import io.jsonwebtoken.Jwts;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.Cookie;
import java.util.Date;

/** Makes access tokens like user-service does, for tests. */
public final class TestTokens {

    // Fake value for tests only.
    public static final String SECRET = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"; // gitleaks:allow

    private TestTokens() {
    }

    public static Cookie userCookie(long userId) {
        return new Cookie("access_token", token(userId, "ROLE_USER", 60_000));
    }

    public static Cookie adminCookie() {
        return new Cookie("access_token", token(1, "ROLE_ADMIN", 60_000));
    }

    public static String token(long userId, String roles, long ttlMillis) {
        return token(SECRET, userId, roles, ttlMillis);
    }

    public static String token(String secret, long userId, String roles, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("roles", roles)
                .claim("name", "Nimal Perera")
                .claim("email", "nimal@example.com")
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();
    }
}
