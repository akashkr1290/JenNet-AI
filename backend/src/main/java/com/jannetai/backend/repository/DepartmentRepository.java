package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 11.
 */
@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    /**
     * Phase 11 (SRS 15.7 Validation Rules: "unmapped categories default
     * to a configurable 'General Civic Issues' department"). V15 seeded
     * this fallback department under the name "General Triage" (see that
     * migration's own header comment) - the app.department-assignment.
     * fallback-department-name property (default "General Triage") is
     * this lookup's key, kept name-based (not a hardcoded department_id)
     * so the mapping stays correct even if a real deployment's seed data
     * uses a different literal name.
     */
    Optional<Department> findByNameAndIsActiveTrue(String name);

    List<Department> findByIsActiveTrueOrderByNameAsc();
}
