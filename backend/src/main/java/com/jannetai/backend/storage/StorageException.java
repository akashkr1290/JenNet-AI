package com.jannetai.backend.storage;

/** Wraps low-level storage I/O failures (local disk today, S3 later) into an unchecked exception GlobalExceptionHandler's generic 500 handler already covers. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
