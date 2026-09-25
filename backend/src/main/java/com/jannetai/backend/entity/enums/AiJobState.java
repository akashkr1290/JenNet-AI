package com.jannetai.backend.entity.enums;

/** Audit GAP-010: matches ai_processing_jobs.state CHECK (V26__create_ai_processing_jobs.sql). */
public enum AiJobState {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED
}
