package com.ecommerce.paymentservice.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a trace id, so one request can be followed through all log lines.
 * Nginx sends the id in X-Request-Id. If it is missing or looks wrong, we make our own.
 * The id goes into the log pattern (MDC key "traceId") and back to the caller in the response.
 */
@NullMarked
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    /** Only plain characters are accepted, so a caller cannot write fake lines into our logs. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String id = request.getHeader(HEADER);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
