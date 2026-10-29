package com.ecommerce.paymentservice.config.security;

import com.ecommerce.paymentservice.config.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ADMIN_ROLE = "ROLE_ADMIN";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   ApiAuthenticationEntryPoint entryPoint,
                                                   ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // Stateless REST API with a token cookie: no CSRF token, no session, no login form.
                // SameSite=Lax on the cookie (set by user-service) protects against cross-site posts.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Customers never call this service. Every URL needs ROLE_ADMIN.
                        .requestMatchers("/api/v1/payment/**").hasAuthority(ADMIN_ROLE)
                        .anyRequest().denyAll());

        return http.build();
    }

    /**
     * Nobody logs in with a password here (user-service does that). This bean only stops Spring Boot
     * from creating a default user with a random password.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("This service has no password users");
        };
    }
}
