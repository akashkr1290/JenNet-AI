package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Severity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Core civic complaint record and lifecycle status.
 * Maps onto database/migrations/V6__create_complaints.sql.
 */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "complaint_id")
    private Long complaintId;

    @Column(name = "reference_number", nullable = false, length = 20)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "citizen_id", nullable = false)
    private User citizen;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private ComplaintCategory category;

    @Column(name = "description", length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_officer_id")
    private User assignedOfficer;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ComplaintStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10)
    private Severity severity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_complaint_id")
    private Complaint parentComplaint;

    @Column(name = "corroboration_count", nullable = false)
    private Integer corroborationCount;

    @Column(name = "is_escalated", nullable = false)
    private Boolean isEscalated;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "is_reopened", nullable = false)
    private Boolean isReopened;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "rejection_reason_code", length = 50)
    private String rejectionReasonCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
