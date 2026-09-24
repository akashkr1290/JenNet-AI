package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.Severity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI classification/severity output per complaint. No trained model exists
 * yet (ARCHITECTURE.md Section 5) - this entity only provides the storage
 * shape; it is not populated until Phase 7+. Maps onto
 * database/migrations/V8__create_predictions.sql.
 */
@Entity
@Table(name = "predictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prediction_id")
    private Long predictionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @Column(name = "ai_confidence", nullable = false, precision = 5, scale = 2)
    private BigDecimal aiConfidence;

    @Column(name = "model_version", nullable = false, length = 30)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_severity", length = 10)
    private Severity predictedSeverity;

    @Column(name = "priority_score", precision = 6, scale = 2)
    private BigDecimal priorityScore;

    @Column(name = "duplicate_flag", nullable = false)
    private Boolean duplicateFlag;

    @Column(name = "raw_model_output", columnDefinition = "json")
    private String rawModelOutput;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
