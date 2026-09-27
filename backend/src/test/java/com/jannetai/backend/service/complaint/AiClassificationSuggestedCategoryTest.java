package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.ai.AiClassifyResult;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.config.AiServiceProperties;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Image;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.ImageType;
import com.jannetai.backend.repository.ImageRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.notification.NotificationService;
import com.jannetai.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live pilot finding (2026-09-27): a complaint the AI could not auto-approve
 * stayed GENERAL, so the Verification Team never saw the AI's suggestion. It
 * now carries the suggested category while it waits at AI_PROCESSING; the
 * team's verify decision still sets the final category.
 */
@ExtendWith(MockitoExtension.class)
class AiClassificationSuggestedCategoryTest {

    @Mock private ComplaintService complaintService;
    @Mock private ImageRepository imageRepository;
    @Mock private PredictionRepository predictionRepository;
    @Mock private StorageService storageService;
    @Mock private AuditService auditService;
    @Mock private AiServiceClient aiServiceClient;
    @Mock private AiServiceProperties aiServiceProperties;
    @Mock private DuplicateDetectionService duplicateDetectionService;
    @Mock private PriorityBudgetPredictionService priorityBudgetPredictionService;
    @Mock private DepartmentAssignmentService departmentAssignmentService;
    @Mock private NotificationService notificationService;
    @Mock private AiThresholdResolver thresholdResolver;
    @Mock private ReputationService reputationService;

    private AiClassificationService service;
    private Complaint complaint;

    @BeforeEach
    void setUp() {
        service = new AiClassificationService(complaintService, imageRepository, predictionRepository, storageService,
                auditService, aiServiceClient, aiServiceProperties, duplicateDetectionService,
                priorityBudgetPredictionService, departmentAssignmentService, notificationService,
                thresholdResolver, reputationService);
        complaint = Complaint.builder().complaintId(6L).status(ComplaintStatus.AI_PROCESSING)
                .category(ComplaintCategory.GENERAL).description("big hole").build();
        when(complaintService.requireComplaint(6L)).thenReturn(complaint);
        when(aiServiceProperties.isEnabled()).thenReturn(true);
        when(imageRepository.findFirstByComplaint_ComplaintIdAndImageTypeOrderByUploadedAtAsc(6L, ImageType.BEFORE))
                .thenReturn(Optional.of(Image.builder().storageKey("complaints/6/a.jpg").build()));
        when(storageService.load("complaints/6/a.jpg")).thenReturn(new byte[]{1, 2, 3});
        lenient().when(duplicateDetectionService.check(any(), any())).thenReturn(null);
    }

    private static AiClassifyResult manualReview(ComplaintCategory category, String reason) {
        return new AiClassifyResult(category, 0.0, "A deep pothole filled with water.", null, true, reason,
                "yolov11-civic-v1.0", true, "ACCEPTABLE", true, List.of(), Map.of());
    }

    @Test
    void manualReviewCarriesTheAiSuggestedCategoryButStaysUnverified() {
        when(aiServiceClient.classify(any())).thenReturn(manualReview(ComplaintCategory.POTHOLE,
                "GEMINI_CATEGORY_NO_DETECTION"));

        AiClassificationService.AttemptResult result = service.processOnce(6L);

        assertThat(result.outcome()).isEqualTo(AiClassificationService.Outcome.COMPLETED);
        assertThat(complaint.getCategory()).isEqualTo(ComplaintCategory.POTHOLE);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.AI_PROCESSING);
        verify(priorityBudgetPredictionService, never()).predictAndApply(any(), any());
        verify(departmentAssignmentService, never()).assignAndApply(any());
        verify(auditService).record(eq(null), eq("AI_CLASSIFICATION_REQUIRES_MANUAL_REVIEW"), eq("COMPLAINT"),
                anyLong(), any());
    }

    @Test
    void aGeneralSuggestionLeavesTheCategoryAlone() {
        when(aiServiceClient.classify(any())).thenReturn(manualReview(ComplaintCategory.GENERAL,
                "NO_DETECTION_ABOVE_THRESHOLD"));

        service.processOnce(6L);

        assertThat(complaint.getCategory()).isEqualTo(ComplaintCategory.GENERAL);
        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.AI_PROCESSING);
    }
}
