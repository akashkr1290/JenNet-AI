package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.enums.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Phase 6 (Complaint Module): images are always loaded scoped to their owning complaint. */
@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByComplaint_ComplaintIdOrderByUploadedAtAsc(Long complaintId);

    /**
     * Phase 8: the AI Analysis Module classifies the citizen's originally-
     * submitted photo, never an after-repair photo - so this is always
     * {@link ImageType#BEFORE}, oldest-first (there is only ever one today;
     * ordering is defensive against a future phase allowing multiple).
     */
    Optional<Image> findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(
            Long complaintId, ImageType imageType);
}
