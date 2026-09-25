package com.jannetai.backend.repository;

import com.jannetai.backend.entity.SensitiveZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Audit GAP-033. */
@Repository
public interface SensitiveZoneRepository extends JpaRepository<SensitiveZone, Long> {

    List<SensitiveZone> findByIsActiveTrue();

    List<SensitiveZone> findAllByOrderByNameAsc();
}
