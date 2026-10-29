package com.ecommerce.paymentservice.service;

import java.util.Optional;

/**
 * The kinds of file accepted as a pickup receipt: a photo or a PDF. The type is found from the first bytes
 * of the file (the "magic bytes"), never from the file name or the Content-Type header, because the caller controls those.
 */
public enum ReceiptFileType {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String contentType;

    ReceiptFileType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<ReceiptFileType> detect(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // WebP: "RIFF", 4 bytes of length, "WEBP"
        if (bytes.length >= 12 && startsWith(bytes, 'R', 'I', 'F', 'F')
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of(WEBP);
        }
        // PDF: "%PDF-"
        if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
