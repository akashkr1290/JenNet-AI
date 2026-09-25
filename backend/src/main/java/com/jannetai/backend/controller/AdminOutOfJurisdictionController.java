package com.jannetai.backend.controller;

import com.jannetai.backend.dto.admin.JurisdictionAcceptRequest;
import com.jannetai.backend.dto.admin.OutOfJurisdictionComplaintResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.admin.OutOfJurisdictionReviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit GAP-031 (SRS 15.5): Admin review queue for out-of-jurisdiction complaint locations. */
@RestController
@RequestMapping("/api/v1/admin/out-of-jurisdiction")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin - Out of jurisdiction", description = "Review complaints located outside the configured boundary (SRS 15.5)")
public class AdminOutOfJurisdictionController {

    private final OutOfJurisdictionReviewService reviewService;

    @GetMapping
    public Page<OutOfJurisdictionComplaintResponse> list(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        return reviewService.list(page, pageSize);
    }

    @PostMapping("/{complaintId}/accept")
    public OutOfJurisdictionComplaintResponse accept(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long complaintId,
                                                     @Valid @RequestBody JurisdictionAcceptRequest request) {
        return reviewService.accept(principal.getUser(), complaintId, request.wardId());
    }
}
