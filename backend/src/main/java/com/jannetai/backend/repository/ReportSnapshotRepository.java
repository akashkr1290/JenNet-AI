package com.jannetai.backend.repository;

import com.jannetai.backend.entity.ReportSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.jannetai.backend.repository.support.AppendOnlyRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/** Audit GAP-039: stored period reports. Insert and read only (no update/delete methods exist). */
@Repository
public interface ReportSnapshotRepository extends AppendOnlyRepository<ReportSnapshot, Long> {

    @Query("""
            SELECT r FROM ReportSnapshot r
            WHERE r.reportType = :type AND r.periodStart = :start AND r.periodEnd = :end AND r.timeZone = :zone
              AND ((:departmentId IS NULL AND r.departmentId IS NULL) OR r.departmentId = :departmentId)
            ORDER BY r.snapshotId ASC
            """)
    java.util.List<ReportSnapshot> findSame(@Param("type") String type, @Param("start") LocalDate start,
                                            @Param("end") LocalDate end, @Param("zone") String zone,
                                            @Param("departmentId") Long departmentId);

    default Optional<ReportSnapshot> findFirstSame(String type, LocalDate start, LocalDate end, String zone,
                                                   Long departmentId) {
        return findSame(type, start, end, zone, departmentId).stream().findFirst();
    }

    /** Department scope: a Department Head only ever lists their own department's snapshots. */
    @Query("""
            SELECT r FROM ReportSnapshot r
            WHERE (:departmentId IS NULL OR r.departmentId = :departmentId)
            ORDER BY r.snapshotId DESC
            """)
    Page<ReportSnapshot> findForScope(@Param("departmentId") Long departmentId, Pageable pageable);
}
