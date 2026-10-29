package com.ecommerce.paymentservice.config.filter;

import com.ecommerce.paymentservice.config.security.JwtTokenParser;
import com.ecommerce.paymentservice.dto.CurrentUser;
import com.ecommerce.paymentservice.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads the access token from the cookie and, if it is good, marks the request as logged in.
 * It never rejects a request itself. When the token is missing or bad, it only leaves the reason
 * on the request; Spring Security then blocks the protected URL and ApiAuthenticationEntryPoint
 * writes the 401 JSON with that reason.
 */
@NullMarked
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenParser tokenParser;
    private final String cookieName;

    public JwtAuthenticationFilter(JwtTokenParser tokenParser,
                                   @Value("${auth.cookie.name}") String cookieName) {
        this.tokenParser = tokenParser;
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Optional<String> token = readCookie(request);

        if (token.isEmpty()) {
            request.setAttribute(ErrorCode.REQUEST_ATTRIBUTE, ErrorCode.ACCESS_TOKEN_MISSING);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = tokenParser.parse(token.get());

            List<String> roles = List.of(claims.get("roles", String.class).split(","));
            CurrentUser user = new CurrentUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("name", String.class),
                    claims.get("email", String.class),
                    roles);

            List<SimpleGrantedAuthority> authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));

        } catch (ExpiredJwtException e) {
            request.setAttribute(ErrorCode.REQUEST_ATTRIBUTE, ErrorCode.ACCESS_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            log.warn("Invalid access token for {}", request.getRequestURI());
            request.setAttribute(ErrorCode.REQUEST_ATTRIBUTE, ErrorCode.ACCESS_TOKEN_INVALID);
        }

        filterChain.doFilter(request, response);
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
