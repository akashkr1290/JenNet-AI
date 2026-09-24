package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.complaint.AiClassificationResponse;
import com.jannetai.backend.dto.dashboard.CitizenDashboardResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Prediction;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.department.SlaPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Gap-backlog Patches 08 and 09 (Sep 2026 strict recheck) - the two P0
 * dashboards that did not exist anywhere in the codebase before this pass.
 * Strictly self-scoped: every query keys on the caller's own userId (never a
 * request parameter), so there is no IDOR surface to get wrong.
 */
@Service
@RequiredArgsConstructor
public class PersonalDashboardService {

    private static final EnumSet<ComplaintStatus> PENDING = EnumSet.of(
            ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING,
            ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED);
    private static final EnumSet<ComplaintStatus> OFFICER_OPEN = EnumSet.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    private static final double AT_RISK_FRACTION = 0.8;
    private static final int PERFORMANCE_WINDOW_DAYS = 30;
    private static final int MAX_TASKS = 10;

    private final ComplaintRepository complaintRepository;
    private final PredictionRepository predictionRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final SlaPolicy slaPolicy;

    // ------------------------------ Patch 08 ------------------------------

    @Transactional(readOnly = true)
    public CitizenDashboardResponse citizenDashboard(User citizen) {
        Map<ComplaintStatus, Long> counts = toCounts(complaintRepository.countByStatusForCitizen(citizen.getUserId()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long pending = PENDING.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum();

        List<CitizenDashboardResponse.RecentComplaint> recent = complaintRepository
                .findTop5ByCitizen_UserIdOrderByCreatedAtDesc(citizen.getUserId())
                .stream().map(this::toRecent).toList();

        return new CitizenDashboardResponse(
                total,
                pending,
                counts.getOrDefault(ComplaintStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(ComplaintStatus.RESOLVED, 0L),
                counts.getOrDefault(ComplaintStatus.CLOSED, 0L),
                counts.getOrDefault(ComplaintStatus.REJECTED, 0L),
                counts.getOrDefault(ComplaintStatus.DUPLICATE, 0L),
                recent);
    }

    private CitizenDashboardResponse.RecentComplaint toRecent(Complaint c) {
        Prediction prediction = predictionRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(c.getComplaintId())
                .orElse(null);
        AiClassificationResponse ai = prediction != null ? AiClassificationResponse.from(prediction) : null;
        return new CitizenDashboardResponse.RecentComplaint(
                c.getComplaintId(),
                c.getReferenceNumber(),
                c.getCategory(),
                c.getStatus(),
                c.getSeverity(),
                prediction != null ? prediction.getPriorityScore() : null,
                ai != null ? ai.aiStatus() : null,
                ai != null ? ai.confidence() : null,
                c.getCreatedAt(),
                slaPolicy.dueAt(c),
                Boolean.TRUE.equals(c.getIsEscalated()));
    }

    // ------------------------------ Patch 09 ------------------------------

    @Transactional(readOnly = true)
    public OfficerDashboardResponse officerDashboard(User officer) {
        Long officerId = officer.getUserId();
        Map<ComplaintStatus, Long> counts = toCounts(complaintRepository.countByStatusForOfficer(officerId));
        LocalDateTime now = LocalDateTime.now();

        long overdue = 0;
        long atRisk = 0;
        long onTrack = 0;
        long noSla = 0;
        List<Complaint> open = complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(officerId, OFFICER_OPEN);
        for (Complaint c : open) {
            LocalDateTime due = slaPolicy.dueAt(c);
            if (due == null) {
                noSla++;
            } else if (now.isAfter(due)) {
                overdue++;
            } else {
                long window = Duration.between(c.getCreatedAt(), due).toMinutes();
                long elapsed = Duration.between(c.getCreatedAt(), now).toMinutes();
                if (window > 0 && elapsed >= window * AT_RISK_FRACTION) {
                    atRisk++;
                } else {
                    onTrack++;
                }
            }
        }

        // "Today's tasks": open work that is overdue or falls due within the
        // next 24 hours, most urgent first.
        LocalDateTime horizon = now.plusHours(24);
        List<OfficerDashboardResponse.TaskItem> tasks = open.stream()
                .filter(c -> {
                    LocalDateTime due = slaPolicy.dueAt(c);
                    return due != null && !due.isAfter(horizon);
                })
                .sorted(Comparator.comparing(slaPolicy::dueAt))
                .limit(MAX_TASKS)
                .map(c -> {
                    LocalDateTime due = slaPolicy.dueAt(c);
                    return new OfficerDashboardResponse.TaskItem(c.getComplaintId(), c.getReferenceNumber(),
                            c.getCategory(), c.getStatus(), c.getSeverity(), due, now.isAfter(due));
                })
                .toList();

        // Personal performance from status_history: who resolved it, and when.
        List<StatusHistory> resolutions = statusHistoryRepository
                .findByActor_UserIdAndNewStatusAndChangedAtGreaterThanEqual(
                        officerId, ComplaintStatus.RESOLVED, now.minusDays(PERFORMANCE_WINDOW_DAYS));
        long totalMinutes = 0;
        int withSla = 0;
        int withinSla = 0;
        for (StatusHistory h : resolutions) {
            Complaint c = h.getComplaint();
            totalMinutes += Duration.between(c.getCreatedAt(), h.getChangedAt()).toMinutes();
            LocalDateTime due = slaPolicy.dueAt(c);
            if (due != null) {
                withSla++;
                if (!h.getChangedAt().isAfter(due)) {
                    withinSla++;
                }
            }
        }
        Double avgHours = resolutions.isEmpty() ? null
                : Math.round(totalMinutes / 60.0 / resolutions.size() * 10.0) / 10.0;
        Double compliance = withSla == 0 ? null : Math.round(1000.0 * withinSla / withSla) / 10.0;

        return new OfficerDashboardResponse(
                counts.getOrDefault(ComplaintStatus.ASSIGNED, 0L),
                counts.getOrDefault(ComplaintStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(ComplaintStatus.RESOLVED, 0L),
                counts.getOrDefault(ComplaintStatus.CLOSED, 0L),
                overdue, atRisk, onTrack, noSla,
                resolutions.size(), avgHours, compliance, tasks);
    }

    private static Map<ComplaintStatus, Long> toCounts(List<Object[]> rows) {
        Map<ComplaintStatus, Long> counts = new EnumMap<>(ComplaintStatus.class);
        for (Object[] row : rows) {
            counts.put((ComplaintStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
