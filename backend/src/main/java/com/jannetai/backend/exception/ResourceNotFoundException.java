package com.jannetai.backend.exception;

/**
 * Generic "entity not found" exception for use by later phases' service
 * layers. Not thrown anywhere in Phase 3 itself (no business endpoints yet)
 * - added now so GlobalExceptionHandler has a stable contract to build
 * against from Phase 4 onward.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
