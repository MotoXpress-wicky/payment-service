package com.ecommerce.paymentservice.service.storage;

import com.ecommerce.paymentservice.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/** ObjectStorage on the S3 API, with the standard AWS SDK client. */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.s3().bucket();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageException("Could not store '" + key + "'", e);
        }
    }

    @Override
    public Optional<StoredFile> get(String key) {
        try {
            var response = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredFile(response.asByteArray(), response.response().contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new StorageException("Could not read '" + key + "'", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new StorageException("Could not delete '" + key + "'", e);
        }
    }
}
