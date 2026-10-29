package com.ecommerce.paymentservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * The S3 client. Address, region and keys come from application.yaml (env file), so AWS S3 later
 * means new values and nothing else.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        StorageProperties.S3 s3 = properties.s3();

        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                // http://object-storage:8333/bucket/key instead of http://bucket.object-storage:8333/key
                .forcePathStyle(true)
                // Newer SDKs add extra checksum headers by default. S3-compatible servers may not accept them.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
