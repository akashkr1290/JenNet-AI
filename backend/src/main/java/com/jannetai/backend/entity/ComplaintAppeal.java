package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.AppealStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Gap-backlog Patch 12 (Sep 2026 audit): a citizen's appeal of a REJECTED
 * complaint's decision. Maps onto
 * database/migrations/V20__create_complaint_appeals.sql, including that
 * migration's "one PENDING appeal per complaint at a time" unique
 * constraint on {@code pendingComplaintId}, which the service sets while
 * PENDING and clears on review.
 */
@Entity
@Table(name = "complaint_appeals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintAppeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "appeal_id")
    private Long appealId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "citizen_id", nullable = false)
    private User citizen;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppealStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    /** = complaint_id while PENDING, null once reviewed - see V20's comment (portable one-pending-appeal rule). */
    @Column(name = "pending_complaint_id")
    private Long pendingComplaintId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
