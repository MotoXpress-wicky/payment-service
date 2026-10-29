package com.ecommerce.paymentservice.service.storage;

import java.util.Optional;

/**
 * Puts files into the object storage, reads them back and removes them. The rest of the service only
 * knows this interface, so moving from the SeaweedFS container to AWS S3 does not touch any other class (M18).
 */
public interface ObjectStorage {

    /** Stores the file under this key in the (private) bucket of this service. Throws StorageException if it fails. */
    void put(String key, byte[] content, String contentType);

    /** Reads the file. Empty when the key does not exist. */
    Optional<StoredFile> get(String key);

    /** Removes the file. Removing a file that does not exist is not an error. */
    void delete(String key);

    record StoredFile(byte[] content, String contentType) {
    }
}
