package com.ecommerce.paymentservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Project rule (CLAUDE.md): every API path is /api/v1/payment/... (or /internal/v1/payment/... for calls between services).
 * The base path of every controller is checked, so a wrong path fails the build and not a user.
 */
class ApiPathTest {

    private static final String SERVICE = "payment";
    private static final Pattern ALLOWED = Pattern.compile("^/(api|internal)/v1/" + SERVICE + "(/.*)?$");
    private static final Pattern ANY_MAPPING = Pattern.compile("@RequestMapping\\b");
    private static final Pattern MAPPING_WITH_PATH = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"\\s*\\)");

    @Test
    @DisplayName("every controller of payment-service lives under /api/v1/payment")
    void everyControllerPathFollowsTheRule() throws IOException {
        List<String> paths = new ArrayList<>();
        int mappings = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file);
                mappings += (int) ANY_MAPPING.matcher(code).results().count();
                Matcher matcher = MAPPING_WITH_PATH.matcher(code);
                while (matcher.find()) {
                    paths.add(matcher.group(1));
                }
            }
        }
        assertThat(paths).as("controllers found").isNotEmpty();
        assertThat(paths.size()).as("every @RequestMapping has one plain path (so this test can read it)").isEqualTo(mappings);
        assertThat(paths).as("base paths that break the /api/v1/" + SERVICE + " rule").allMatch(p -> ALLOWED.matcher(p).matches());
    }
}
