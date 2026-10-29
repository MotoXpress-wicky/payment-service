package com.ecommerce.paymentservice.dto;

import java.util.List;

/** The logged-in user, read from the access token. Controllers get it with @AuthenticationPrincipal. */
public record CurrentUser(Long id, String name, String email, List<String> roles) {
}
