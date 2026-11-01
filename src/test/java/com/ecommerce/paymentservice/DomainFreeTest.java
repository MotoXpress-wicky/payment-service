package com.ecommerce.paymentservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Project rule (CLAUDE.md, decision M42): payment-service says "reference", never "order". The other shop words
 * (product, vehicle, cart, motoXpress) never appear in its code either. Event names live in application.yaml.
 * This test reads every Java file of the service (comments removed, because only code counts) and fails when a
 * forbidden word appears.
 */
class DomainFreeTest {

    private static final Pattern FORBIDDEN = Pattern.compile("(?i)\\b(orders?|products?|vehicles?|carts?|motoxpress)\\b");
    /** Names of the framework are not shop words: import lines, Spring's @Order annotation, and the SQL "order by". */
    private static final Pattern FRAMEWORK = Pattern.compile("(?im)^import .*;$|@Order\\b|order\\s+by");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    @DisplayName("payment-service code never says order (or another shop word)")
    void noForbiddenWordInTheCode() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file);
                code = BLOCK_COMMENT.matcher(code).replaceAll(" ");
                code = LINE_COMMENT.matcher(code).replaceAll(" ");
                code = FRAMEWORK.matcher(code).replaceAll(" ");
                var matcher = FORBIDDEN.matcher(code);
                while (matcher.find()) {
                    found.add(file + ": " + matcher.group());
                }
            }
        }
        assertThat(found).as("forbidden words in the code").isEmpty();
    }
}
