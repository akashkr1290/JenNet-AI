package com.jannetai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Gap-backlog Patch 11 (Sep 2026 audit): citizen's 1-5 star rating of a
 * resolved/closed complaint, plus an optional comment. Maps onto
 * database/migrations/V19__create_complaint_ratings.sql - that
 * migration's own unique constraint on complaint_id is what actually
 * enforces "one rating per complaint", not application code alone.
 */
@Entity
@Table(name = "complaint_ratings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rating_id")
    private Long ratingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "citizen_id", nullable = false)
    private User citizen;

    @Column(name = "rating", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer rating;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
