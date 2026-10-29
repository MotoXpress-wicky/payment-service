package com.ecommerce.paymentservice.config;

import com.ecommerce.paymentservice.TestTokens;
import com.ecommerce.paymentservice.config.security.JwtTokenParser;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenParserTest {

    private final JwtTokenParser parser = new JwtTokenParser(TestTokens.SECRET);

    @Test
    void readsTheClaimsOfAValidToken() {
        var claims = parser.parse(TestTokens.token(42, "ROLE_USER", 60_000));

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("roles", String.class)).isEqualTo("ROLE_USER");
    }

    @Test
    void rejectsAnExpiredToken() {
        String expired = TestTokens.token(42, "ROLE_USER", -1_000);

        assertThatThrownBy(() -> parser.parse(expired)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String forged = TestTokens.token("another-secret-another-secret-another-secret-1", 42, "ROLE_ADMIN", 60_000);

        assertThatThrownBy(() -> parser.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithAShortSecret() {
        assertThatThrownBy(() -> new JwtTokenParser("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
