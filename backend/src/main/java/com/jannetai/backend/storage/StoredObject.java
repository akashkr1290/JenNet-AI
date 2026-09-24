package com.jannetai.backend.storage;

/**
 * Result of a {@link StorageService#store} call - exactly the fields
 * {@code images} (V7__create_images.sql) needs: storage_key, content_type,
 * file_size_bytes.
 */
public record StoredObject(String storageKey, String contentType, long fileSizeBytes) {
}
