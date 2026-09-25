package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Phase 3 skeleton: plain CRUD access only. Query methods specific to a
 * business module (e.g. officer queue filtering, SLA-ordered lists) are
 * added by the phase that owns that module, not here.
 *
 * Phase 5 adds findByIsActiveTrue - the Citizen Module's ward picker
 * (profile management, SRS 15.1) must only ever offer active wards.
 */
@Repository
public interface WardRepository extends JpaRepository<Ward, Long> {

    List<Ward> findByIsActiveTrueOrderByNameAsc();

    /** Audit GAP-020: uniqueness checks for Admin ward configuration (uq_wards_name / uq_wards_code). */
    java.util.Optional<Ward> findFirstByName(String name);

    java.util.Optional<Ward> findFirstByCode(String code);

    List<Ward> findAllByOrderByNameAsc();
}
