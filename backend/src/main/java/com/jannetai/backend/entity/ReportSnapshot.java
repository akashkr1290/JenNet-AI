package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Audit GAP-039: an immutable stored period report (V28__create_report_snapshots.sql). */
@Entity
@Immutable
@Table(name = "report_snapshots")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Column(name = "report_type", nullable = false, length = 20)
    private String reportType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "generated_by")
    private Long generatedBy;

    @Column(name = "generated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime generatedAt;

    @Column(name = "insufficient_data", nullable = false)
    private Boolean insufficientData;

    @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;
}
