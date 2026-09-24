package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Phase 3 skeleton: plain CRUD access only. Query methods specific to a
 * business module (e.g. officer queue filtering, SLA-ordered lists) are
 * added by the phase that owns that module, not here.
 */
@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
}
