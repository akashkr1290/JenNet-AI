package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.enums.ActorType;
import com.jannetai.backend.entity.enums.ComplaintStatus;

import java.time.LocalDateTime;

public record StatusHistoryResponse(
        ComplaintStatus previousStatus,
        ComplaintStatus newStatus,
        ActorType actorType,
        Long actorId,
        String actorName,
        String reason,
        LocalDateTime changedAt
) {
    public static StatusHistoryResponse from(StatusHistory history) {
        return new StatusHistoryResponse(
                history.getPreviousStatus(),
                history.getNewStatus(),
                history.getActorType(),
                history.getActor() != null ? history.getActor().getUserId() : null,
                history.getActor() != null ? history.getActor().getFullName() : null,
                history.getReason(),
                history.getChangedAt()
        );
    }
}
