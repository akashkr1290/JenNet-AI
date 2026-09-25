package com.jannetai.backend.dto;

import com.jannetai.backend.dto.complaint.StatusHistoryResponse;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit GAP-051: rows written before the fix keep their stored text (status
 * history is immutable) but citizens are shown the current wording.
 * NOT EXECUTED in the workspace that wrote it (no Maven Central access).
 */
class StatusHistoryResponseTest {

    private static StatusHistory row(String reason) {
        return StatusHistory.builder().previousStatus(ComplaintStatus.SUBMITTED).newStatus(ComplaintStatus.AI_PROCESSING)
                .actorType(ActorType.SYSTEM).reason(reason).build();
    }

    @Test
    void legacyDeveloperNoteIsShownAsTheCurrentText() {
        // exact text written by ComplaintService.create before the fix
        String legacy = "Queued for AI processing (AI Analysis Module not yet implemented - Phase 7; "
                + "held pending Verification Team manual review per the approved Phase 6 override)";
        assertThat(StatusHistoryResponse.from(row(legacy)).reason()).isEqualTo("Queued for AI processing");
    }

    @Test
    void otherReasonsAreUnchanged() {
        assertThat(StatusHistoryResponse.from(row("Complaint submitted by citizen")).reason())
                .isEqualTo("Complaint submitted by citizen");
        assertThat(StatusHistoryResponse.from(row(null)).reason()).isNull();
    }
}
