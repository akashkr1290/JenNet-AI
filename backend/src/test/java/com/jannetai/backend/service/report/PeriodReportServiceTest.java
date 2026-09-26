package com.jannetai.backend.service.report;

import com.jannetai.backend.dto.report.PeriodReport;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.ReportSnapshot;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.BudgetRepository;
import com.jannetai.backend.repository.ComplaintRatingRepository;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.ReportSnapshotRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-039: scoping, snapshot reuse and storage. NOT EXECUTED here via Maven. */
@ExtendWith(MockitoExtension.class)
class PeriodReportServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private StatusHistoryRepository statusHistoryRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private ComplaintRatingRepository complaintRatingRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private ReportSnapshotRepository reportSnapshotRepository;

    private PeriodReportService service;
    private final Department roads = Department.builder().departmentId(1L).name("Roads").build();
    private final User head = User.builder().userId(4L).role(Role.DEPARTMENT_HEAD).department(roads).build();

    @BeforeEach
    void setUp() {
        service = new PeriodReportService(complaintRepository, statusHistoryRepository, budgetRepository,
                complaintRatingRepository, departmentRepository, reportSnapshotRepository);
        lenient().when(departmentRepository.findById(1L)).thenReturn(Optional.of(roads));
        lenient().when(reportSnapshotRepository.save(any(ReportSnapshot.class))).thenAnswer(inv -> {
            ReportSnapshot s = inv.getArgument(0);
            return ReportSnapshot.builder().snapshotId(77L).reportType(s.getReportType()).periodStart(s.getPeriodStart())
                    .periodEnd(s.getPeriodEnd()).timeZone(s.getTimeZone()).departmentId(s.getDepartmentId())
                    .insufficientData(s.getInsufficientData()).payloadJson(s.getPayloadJson()).build();
        });
    }

    @Test
    void aDepartmentHeadIsScopedToTheirDepartmentAndCannotAskForAnother() {
        assertThatThrownBy(() -> service.generate(head, ReportPeriods.Type.WEEKLY, null, null, 2L))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.resolveScope(head, null)).isEqualTo(1L);
    }

    @Test
    void aNewReportIsComputedStoredAndCarriesTheSnapshotId() {
        PeriodReport r = service.generate(head, ReportPeriods.Type.WEEKLY, null, null, null);

        assertThat(r.snapshotId()).isEqualTo(77L);
        assertThat(r.departmentName()).isEqualTo("Roads");
        assertThat(r.insufficientData()).isTrue(); // no complaints in the mocked period
        verify(complaintRepository).findReceivedInPeriod(eq(1L), any(), any());
        verify(reportSnapshotRepository).save(any(ReportSnapshot.class));
    }

    @Test
    void anEndedPeriodIsServedFromItsStoredSnapshotWithoutRecomputing() {
        LocalDate day = LocalDate.of(2026, 1, 5);
        PeriodReport stored = PeriodReportCalculator.calculate("DAILY", day, day, "Asia/Kolkata", 1L, "Roads",
                LocalDateTime.of(2026, 1, 6, 3, 0), 1, List.of(), List.of(), List.of(), List.of());
        // The service calls the repository's findFirstSame(...) default method, which
        // internally delegates to findSame(...) in real code - but a Mockito mock never
        // executes an interface default method's body, it just returns Optional.empty(),
        // so stubbing findSame() here was never actually exercised. Stub findFirstSame()
        // itself instead.
        when(reportSnapshotRepository.findFirstSame(eq("DAILY"), eq(day), eq(day), anyString(), eq(1L))).thenReturn(Optional.of(
                ReportSnapshot.builder().snapshotId(5L).reportType("DAILY").periodStart(day).periodEnd(day)
                        .timeZone("Asia/Kolkata").departmentId(1L).insufficientData(true)
                        .payloadJson(PeriodReportCodec.toJson(stored)).build()));

        PeriodReport r = service.generate(head, ReportPeriods.Type.DAILY, null, day, null);

        assertThat(r.snapshotId()).isEqualTo(5L);
        assertThat(r.generatedAt()).isEqualTo(stored.generatedAt());
        verify(complaintRepository, never()).findReceivedInPeriod(any(), any(), any());
        verify(reportSnapshotRepository, never()).save(any());
    }
}
