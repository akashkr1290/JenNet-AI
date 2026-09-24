package com.jannetai.backend.dto.auth;

/** Generic {message: "..."} body for endpoints with no richer payload. */
public record SimpleMessageResponse(String message) {
}
