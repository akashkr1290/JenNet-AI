package com.jannetai.backend.repository;

import com.jannetai.backend.entity.ComplaintRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ComplaintRatingRepository extends JpaRepository<ComplaintRating, Long> {

    boolean existsByComplaint_ComplaintId(Long complaintId);

    Optional<ComplaintRating> findByComplaint_ComplaintId(Long complaintId);

    /** Audit GAP-039: ratings given in [start, endExclusive), optionally for one department's complaints. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT r.rating FROM ComplaintRating r
            WHERE r.createdAt >= :start AND r.createdAt < :endExclusive
              AND (:departmentId IS NULL OR r.complaint.department.departmentId = :departmentId)
            """)
    java.util.List<Integer> findRatingsInPeriod(
            @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
            @org.springframework.data.repository.query.Param("endExclusive") java.time.LocalDateTime endExclusive,
            @org.springframework.data.repository.query.Param("departmentId") Long departmentId);
}
