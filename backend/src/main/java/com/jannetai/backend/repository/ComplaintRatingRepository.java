package com.jannetai.backend.repository;

import com.jannetai.backend.entity.ComplaintRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ComplaintRatingRepository extends JpaRepository<ComplaintRating, Long> {

    boolean existsByComplaint_ComplaintId(Long complaintId);

    Optional<ComplaintRating> findByComplaint_ComplaintId(Long complaintId);
}
