package com.jannetai.backend.service.report;

import com.jannetai.backend.dto.report.PeriodReport;
import com.jannetai.backend.entity.Budget;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.ReportSnapshot;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.BudgetRepository;
import com.jannetai.backend.repository.ComplaintRatingRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.ReportSnapshotRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Audit GAP-039 (SRS 15.12, 21, US-10): period reports with an explicit date
 * range, stored as immutable snapshots.
 *
 * <ul>
 *   <li>Types: DAILY (one day, default yesterday), WEEKLY (the 7 days ending on
 *       a day, default yesterday - US-10 "prior 7 days"), CUSTOM (from/to).
 *       Ranges are validated by {@link ReportPeriods}: max
 *       {@code app.reports.max-range-days} (default 366, "1 year").</li>
 *   <li>Scope: a DEPARTMENT_HEAD always gets their own department (a different
 *       departmentId is 403), ADMIN/SUPER_ADMIN any department or all -
 *       the same rule as the Government Dashboard.</li>
 *   <li>Point-in-time: figures come from creation times and status_history
 *       transition times inside the period. For a period that has ended, the
 *       first stored snapshot with the same type/period/zone/scope is served
 *       again instead of recomputing, so the same request always returns the
 *       same figures. Budget approval status and the department of a later
 *       reassigned complaint are as at generation time - which is exactly why
 *       the snapshot, not a recomputation, is authoritative.</li>
 *   <li>Insufficient data: fewer than {@code app.reports.min-complaints}
 *       (default 1) complaints received -> the report is still produced and is
 *       labelled INSUFFICIENT DATA (SRS 15.12 Exceptions).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PeriodReportService {

    static final Set<ComplaintStatus> REPORTED_TRANSITIONS = Set.of(
            ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintStatus.RESOLVED,
            ComplaintStatus.CLOSED, ComplaintStatus.REJECTED, ComplaintStatus.ESCALATED);

    private final ComplaintRepository complaintRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final BudgetRepository budgetRepository;
    private final ComplaintRatingRepository complaintRatingRepository;
    private final DepartmentRepository departmentRepository;
    private final ReportSnapshotRepository reportSnapshotRepository;

    @Value("${app.reports.zone:Asia/Kolkata}")
    private String zoneName = "Asia/Kolkata";

    @Value("${app.reports.max-range-days:366}")
    private int maxRangeDays = 366;

    @Value("${app.reports.min-complaints:1}")
    private int minComplaints = 1;

    /** Report for a user request (scope-checked) - see class Javadoc. */
    @Transactional
    public PeriodReport generate(User requester, ReportPeriods.Type type, LocalDate from, LocalDate to,
                                 Long requestedDepartmentId) {
        Long departmentId = resolveScope(requester, requestedDepartmentId);
        return generateFor(requester.getUserId(), type, from, to, departmentId);
    }

    /** Scheduler entry point (no user scope check; generated_by is NULL). */
    @Transactional
    public PeriodReport generateScheduled(ReportPeriods.Type type, LocalDate from, LocalDate to, Long departmentId) {
        return generateFor(null, type, from, to, departmentId);
    }

    @Transactional(readOnly = true)
    public Page<PeriodReport> listSnapshots(User requester, Long requestedDepartmentId, int page, int pageSize) {
        Long departmentId = resolveScope(requester, requestedDepartmentId);
        return reportSnapshotRepository.findForScope(departmentId,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100)))
                .map(this::fromSnapshot);
    }

    @Transactional(readOnly = true)
    public PeriodReport getSnapshot(User requester, Long snapshotId) {
        ReportSnapshot snapshot = reportSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Report snapshot not found: " + snapshotId));
        if (requester.getRole() == Role.DEPARTMENT_HEAD) {
            Long own = requester.getDepartment() != null ? requester.getDepartment().getDepartmentId() : null;
            if (own == null || !own.equals(snapshot.getDepartmentId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only view your own department's reports");
            }
        }
        return fromSnapshot(snapshot);
    }

    private PeriodReport generateFor(Long generatedBy, ReportPeriods.Type type, LocalDate from, LocalDate to,
                                     Long departmentId) {
        ZoneId zone = ZoneId.of(zoneName);
        LocalDate today = LocalDate.now(zone);
        ReportPeriods.Period period = ReportPeriods.resolve(type, from, to, today, maxRangeDays);
        String departmentName = null;
        if (departmentId != null) {
            Department department = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
            departmentName = department.getName();
        }

        if (ReportPeriods.isClosed(period, today)) {
            Optional<ReportSnapshot> existing = reportSnapshotRepository.findFirstSame(
                    period.type().name(), period.start(), period.end(), zone.getId(), departmentId);
            if (existing.isPresent()) {
                return fromSnapshot(existing.get());
            }
        }

        LocalDateTime start = ReportPeriods.utcStart(period, zone);
        LocalDateTime end = ReportPeriods.utcEndExclusive(period, zone);
        List<Complaint> received = complaintRepository.findReceivedInPeriod(departmentId, start, end);
        List<StatusHistory> transitions = statusHistoryRepository.findTransitionsInPeriod(
                REPORTED_TRANSITIONS, start, end, departmentId);

        PeriodReport computed = PeriodReportCalculator.calculate(
                period.type().name(), period.start(), period.end(), zone.getId(), departmentId, departmentName,
                LocalDateTime.now(ZoneOffset.UTC), minComplaints,
                received.stream().map(PeriodReportService::complaintFact).toList(),
                transitions.stream().map(PeriodReportService::transitionFact).toList(),
                latestBudgets(received),
                complaintRatingRepository.findRatingsInPeriod(start, end, departmentId));

        ReportSnapshot saved = reportSnapshotRepository.save(ReportSnapshot.builder()
                .reportType(period.type().name())
                .periodStart(period.start())
                .periodEnd(period.end())
                .timeZone(zone.getId())
                .departmentId(departmentId)
                .generatedBy(generatedBy)
                .insufficientData(computed.insufficientData())
                .payloadJson(PeriodReportCodec.toJson(computed))
                .build());
        return withSnapshotId(computed, saved.getSnapshotId());
    }

    private PeriodReport fromSnapshot(ReportSnapshot snapshot) {
        return withSnapshotId(PeriodReportCodec.fromJson(snapshot.getPayloadJson()), snapshot.getSnapshotId());
    }

    private static PeriodReport withSnapshotId(PeriodReport r, Long snapshotId) {
        return new PeriodReport(r.reportType(), r.periodStart(), r.periodEnd(), r.timeZone(), r.departmentId(),
                r.departmentName(), r.generatedAt(), snapshotId, r.insufficientData(), r.minimumComplaints(),
                r.counts(), r.sla(), r.averageResolutionHours(), r.byCategory(), r.byWard(), r.budget(), r.engagement());
    }

    private List<PeriodReportCalculator.BudgetFact> latestBudgets(List<Complaint> received) {
        Map<Long, Complaint> byId = new HashMap<>();
        received.forEach(c -> byId.put(c.getComplaintId(), c));
        List<Long> ids = new ArrayList<>(byId.keySet());
        Map<Long, Budget> latest = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 1000) { // bounded IN lists
            for (Budget b : budgetRepository.findByComplaint_ComplaintIdIn(ids.subList(i, Math.min(ids.size(), i + 1000)))) {
                latest.merge(b.getComplaint().getComplaintId(), b, (a, c) -> newer(a, c));
            }
        }
        List<PeriodReportCalculator.BudgetFact> facts = new ArrayList<>();
        latest.values().stream().sorted(Comparator.comparing(b -> b.getComplaint().getComplaintId())).forEach(b -> {
            Complaint c = byId.get(b.getComplaint().getComplaintId());
            facts.add(new PeriodReportCalculator.BudgetFact(c.getComplaintId(),
                    c.getCategory() != null ? c.getCategory().name() : null,
                    b.getEstimatedCostMin(), b.getEstimatedCostMax(),
                    b.getApprovalStatus() != null ? b.getApprovalStatus().name() : null));
        });
        return facts;
    }

    private static Budget newer(Budget a, Budget b) {
        Comparator<Budget> order = Comparator.comparing(Budget::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Budget::getBudgetId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return order.compare(a, b) >= 0 ? a : b;
    }

    private static PeriodReportCalculator.ComplaintFact complaintFact(Complaint c) {
        var ward = c.getLocation() != null ? c.getLocation().getWard() : null;
        return new PeriodReportCalculator.ComplaintFact(c.getComplaintId(),
                c.getCategory() != null ? c.getCategory().name() : null,
                ward != null ? ward.getWardId() : null, ward != null ? ward.getName() : null,
                c.getCitizen() != null ? c.getCitizen().getUserId() : null);
    }

    private static PeriodReportCalculator.TransitionFact transitionFact(StatusHistory h) {
        Complaint c = h.getComplaint();
        return new PeriodReportCalculator.TransitionFact(c.getComplaintId(), h.getNewStatus().name(),
                c.getCategory() != null ? c.getCategory().name() : null,
                h.getChangedAt(), c.getCreatedAt(), c.getSlaDueAt());
    }

    /** Same rule as GovernmentDashboardService: DH -> own department only; Admins -> requested or all. */
    Long resolveScope(User requester, Long requestedDepartmentId) {
        if (requester.getRole() == Role.DEPARTMENT_HEAD) {
            Long own = requester.getDepartment() != null ? requester.getDepartment().getDepartmentId() : null;
            if (own == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No department is assigned to this account");
            }
            if (requestedDepartmentId != null && !requestedDepartmentId.equals(own)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may only view your own department's reports");
            }
            return own;
        }
        return requestedDepartmentId;
    }
}
