package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} repository-layer tests for {@link ComplaintRepository}
 * - real Hibernate + real Flyway-migrated H2 schema (see
 * application-test.yml), not mocks. Covers the derived-query methods
 * ComplaintService/DepartmentPerformanceService/GovernmentDashboardService
 * rely on: the anti-spam rolling-24h counter, and the department/officer/
 * status-scoped lookups that back the dashboard KPI tiles.
 *
 * NOT EXECUTED in this workspace (no Maven Central reach to resolve
 * spring-boot-starter-test/H2/Flyway - see PROJECT_PROGRESS.md's Phase 20
 * TESTS section). Manually validated against ComplaintRepository.java's
 * actual method names/signatures and the entity mappings in Complaint.java/
 * User.java/Department.java.
 */
@DataJpaTest
// Audit GAP-013: without replace=NONE, @DataJpaTest silently swaps in a plain
// embedded H2 database (ignoring application-test.yml), on which the
// MySQL-specific Flyway migrations cannot run. The test profile now points at
// a real MySQL 8 (the service container in backend-ci.yml, or a local one).
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ComplaintRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ComplaintRepository complaintRepository;

    private User persistCitizen(String mobile) {
        User citizen = User.builder()
                .fullName("Citizen " + mobile)
                .mobileNumber(mobile)
                .passwordHash("hash")
                .role(Role.CITIZEN)
                .reputationScore(100)
                .status(UserStatus.ACTIVE)
                .failedLoginCount(0)
                .build();
        return entityManager.persistAndFlush(citizen);
    }

    private Department persistDepartment(String name) {
        Department department = Department.builder().name(name).isActive(true).build();
        return entityManager.persistAndFlush(department);
    }

    private Complaint persistComplaint(User citizen, Department department, ComplaintStatus status,
                                        String refNumber, Boolean escalated) {
        Complaint complaint = Complaint.builder()
                .referenceNumber(refNumber)
                .citizen(citizen)
                .category(ComplaintCategory.POTHOLE)
                .status(status)
                .department(department)
                .corroborationCount(1)
                .isEscalated(escalated)
                .isReopened(false)
                .build();
        return entityManager.persistAndFlush(complaint);
    }

    @Test
    void existsByReferenceNumberIsTrueOnlyForAPersistedComplaint() {
        User citizen = persistCitizen("+911111111111");
        persistComplaint(citizen, null, ComplaintStatus.SUBMITTED, "JN-2026-000001", false);

        assertThat(complaintRepository.existsByReferenceNumber("JN-2026-000001")).isTrue();
        assertThat(complaintRepository.existsByReferenceNumber("JN-2026-999999")).isFalse();
    }

    @Test
    void countByCitizenAndCreatedAtAfterOnlyCountsRecentSubmissionsForThatCitizen() {
        User citizen = persistCitizen("+912222222222");
        User otherCitizen = persistCitizen("+913333333333");
        persistComplaint(citizen, null, ComplaintStatus.SUBMITTED, "JN-2026-000002", false);
        persistComplaint(citizen, null, ComplaintStatus.SUBMITTED, "JN-2026-000003", false);
        persistComplaint(otherCitizen, null, ComplaintStatus.SUBMITTED, "JN-2026-000004", false);

        long count = complaintRepository.countByCitizen_UserIdAndCreatedAtAfter(
                citizen.getUserId(), LocalDateTime.now().minusHours(24));

        // Anti-spam counter (SRS 15.1) must be scoped to this citizen only.
        assertThat(count).isEqualTo(2);
    }

    @Test
    void countByCitizenAndCreatedAtAfterExcludesSubmissionsOutsideTheWindow() {
        User citizen = persistCitizen("+914444444444");
        Complaint old = persistComplaint(citizen, null, ComplaintStatus.SUBMITTED, "JN-2026-000005", false);
        // createdAt is DB-generated (insertable=false) at persist time - move
        // the cutoff window to exclude it instead of trying to backdate it.
        entityManager.flush();
        entityManager.refresh(old);

        long countWithFutureWindow = complaintRepository.countByCitizen_UserIdAndCreatedAtAfter(
                citizen.getUserId(), old.getCreatedAt().plusSeconds(1));

        assertThat(countWithFutureWindow).isZero();
    }

    @Test
    void findByStatusInReturnsOnlyComplaintsMatchingOneOfTheGivenStatuses() {
        User citizen = persistCitizen("+915555555555");
        persistComplaint(citizen, null, ComplaintStatus.VERIFIED, "JN-2026-000006", false);
        persistComplaint(citizen, null, ComplaintStatus.RESOLVED, "JN-2026-000007", false);
        persistComplaint(citizen, null, ComplaintStatus.REJECTED, "JN-2026-000008", false);

        List<Complaint> result = complaintRepository.findByStatusIn(
                List.of(ComplaintStatus.VERIFIED, ComplaintStatus.RESOLVED));

        assertThat(result).extracting(Complaint::getReferenceNumber)
                .containsExactlyInAnyOrder("JN-2026-000006", "JN-2026-000007");
    }

    @Test
    void findByDepartmentAndStatusInScopesResultsToOneDepartment() {
        User citizen = persistCitizen("+916666666666");
        Department roads = persistDepartment("Roads");
        Department water = persistDepartment("Water");
        persistComplaint(citizen, roads, ComplaintStatus.IN_PROGRESS, "JN-2026-000009", false);
        persistComplaint(citizen, water, ComplaintStatus.IN_PROGRESS, "JN-2026-000010", false);

        List<Complaint> result = complaintRepository.findByDepartment_DepartmentIdAndStatusIn(
                roads.getDepartmentId(), List.of(ComplaintStatus.IN_PROGRESS));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReferenceNumber()).isEqualTo("JN-2026-000009");
    }

    @Test
    void countByIsEscalatedCountsOnlyEscalatedComplaints() {
        User citizen = persistCitizen("+917777777777");
        persistComplaint(citizen, null, ComplaintStatus.IN_PROGRESS, "JN-2026-000011", true);
        persistComplaint(citizen, null, ComplaintStatus.IN_PROGRESS, "JN-2026-000012", false);

        assertThat(complaintRepository.countByIsEscalated(true)).isEqualTo(1);
        assertThat(complaintRepository.countByIsEscalated(false)).isEqualTo(1);
    }

    // ---- Audit GAP-050: DB-generated timestamps are populated right after insert ----

    @Autowired
    private StatusHistoryRepository statusHistoryRepository;

    @Test
    void newStatusHistoryRowCarriesItsTimestampWithoutARefresh() {
        User citizen = persistCitizen("9000005001");
        Complaint complaint = persistComplaint(citizen, null, ComplaintStatus.VERIFIED, "JN-2026-905001", false);

        StatusHistory saved = statusHistoryRepository.save(StatusHistory.builder()
                .complaint(complaint).previousStatus(ComplaintStatus.AI_PROCESSING).newStatus(ComplaintStatus.VERIFIED)
                .actorType(ActorType.SYSTEM).reason("test").build());
        entityManager.flush();

        assertThat(saved.getChangedAt()).isNotNull();       // was null before @Generated
        assertThat(complaint.getCreatedAt()).isNotNull();
        assertThat(complaint.getUpdatedAt()).isNotNull();
    }

    // ---- Audit GAP-027 / GAP-028: new queries run on MySQL ----

    @Test
    void slaAndAutoCloseQueriesUseThePersistedFields() {
        User citizen = persistCitizen("9000005002");
        Complaint due = persistComplaint(citizen, null, ComplaintStatus.IN_PROGRESS, "JN-2026-905002", false);
        due.setSlaDueAt(LocalDateTime.now().minusMinutes(1));
        due.setSlaWarningAt(LocalDateTime.now().minusHours(2));
        due.setSlaHours(24);
        entityManager.persistAndFlush(due);

        assertThat(complaintRepository.findSlaDueBreaches(
                java.util.Set.of(ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS), LocalDateTime.now()))
                .extracting(Complaint::getReferenceNumber).contains("JN-2026-905002");
        assertThat(complaintRepository.findResolvedPastGracePeriod(LocalDateTime.now().plusDays(1),
                org.springframework.data.domain.PageRequest.of(0, 10))).isNotNull();
    }
}
