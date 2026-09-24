package com.jannetai.backend.repository;

import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Phase 4 adds the lookup methods the Authentication Module needs
 * (mobile/email are the two login identifiers per SRS 15.2). Phase 11
 * (Department Assignment Module, SRS 15.7) adds the officer-eligibility
 * lookup its load-balancing step needs - deliberately department-scoped
 * only, not ward-scoped (see PROJECT_INTEGRATION.md Section 6 for why).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByMobileNumber(String mobileNumber);

    Optional<User> findByEmail(String email);

    boolean existsByMobileNumber(String mobileNumber);

    boolean existsByEmail(String email);

    /** Login accepts either identifier (SRS: "mobile/email + password or OTP"). */
    Optional<User> findByMobileNumberOrEmail(String mobileNumber, String email);

    /** SRS 15.7: candidate pool for officer load-balancing within a department. */
    List<User> findByRoleAndDepartment_DepartmentIdAndStatus(Role role, Long departmentId, UserStatus status);

    /**
     * Phase 13 (Department Head Module, SRS 16.2 "Department Performance
     * View" officer workload table + "Reassign Officer" action). Unlike
     * {@link #findByRoleAndDepartment_DepartmentIdAndStatus} (Phase 11's
     * load-balancing candidate pool, ACTIVE-only), this deliberately
     * returns every GOVERNMENT_OFFICER in the department regardless of
     * status - a Department Head's oversight/workload view should not
     * silently hide a SUSPENDED officer who still has open complaints
     * assigned to them.
     */
    List<User> findByRoleAndDepartment_DepartmentIdOrderByFullNameAsc(Role role, Long departmentId);

    /**
     * Phase 14 (Admin & Settings Module, SRS 16.3 "User & Role Management"
     * screen: search/filter by role/status/department, name/mobile
     * search). Every filter is optional (null = "don't filter on this")
     * so a single query serves the full-list case and every combination
     * of filters, same optional-parameter JPQL pattern used elsewhere in
     * this codebase (e.g. AdminAuditLogController's underlying query).
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
              AND (:departmentId IS NULL OR u.department.departmentId = :departmentId)
              AND (:search IS NULL
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR u.mobileNumber LIKE CONCAT('%', :search, '%'))
            ORDER BY u.fullName ASC
            """)
    Page<User> searchAdminUsers(@Param("role") Role role, @Param("status") UserStatus status,
                                 @Param("departmentId") Long departmentId, @Param("search") String search,
                                 Pageable pageable);

    /** Phase 16 (Admin Dashboard, SRS 24.3 "active users" KPI tile). */
    long countByStatus(UserStatus status);

    /** Gap-backlog Patch 18: recipients of the weekly department report. */
    List<User> findByRoleAndStatus(Role role, UserStatus status);
}
