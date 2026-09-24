package com.jannetai.backend.repository;

import com.jannetai.backend.entity.ComplaintAppeal;
import com.jannetai.backend.entity.enums.AppealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintAppealRepository extends JpaRepository<ComplaintAppeal, Long> {

    boolean existsByComplaint_ComplaintIdAndStatus(Long complaintId, AppealStatus status);

    List<ComplaintAppeal> findByComplaint_ComplaintIdOrderByCreatedAtDesc(Long complaintId);

    /** Staff review queue - PENDING appeals across all complaints, oldest first. */
    List<ComplaintAppeal> findByStatusOrderByCreatedAtAsc(AppealStatus status);

    Optional<ComplaintAppeal> findById(Long appealId);
}
