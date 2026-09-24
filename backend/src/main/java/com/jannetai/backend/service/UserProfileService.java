package com.jannetai.backend.service;

import com.jannetai.backend.dto.auth.UserProfileResponse;
import com.jannetai.backend.dto.user.UpdateProfileRequest;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the Citizen Module's "profile management" feature (SRS 15.1),
 * separate from AuthService (Authentication Module, SRS 15.2) - the two
 * are distinct SRS sections and this keeps that module boundary in the
 * code, not just the docs.
 *
 * Reputation score display, complaint history/status tracking, community
 * heatmap view, and feedback/rating on resolved complaints (also SRS 15.1
 * features) are NOT implemented here: all four either already exist
 * elsewhere (reputation score is already on UserProfileResponse, Phase 4)
 * or require the Complaint entity, which does not exist until Phase 6
 * (Complaint Module, per ARCHITECTURE.md Section 8's phase map). Building
 * any of those now would mean silently starting Phase 6 work inside a
 * Phase 5 session - see PROJECT_PROGRESS.md "KNOWN LIMITATIONS" for the
 * explicit list carried forward.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final WardService wardService;
    private final AuditService auditService;

    @Transactional
    public UserProfileResponse updateProfile(User currentUser, UpdateProfileRequest request) {
        // Re-fetch inside the transaction rather than mutating the
        // request-scoped principal's detached entity directly, so the
        // save() below is guaranteed to persist against a managed instance.
        User user = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + currentUser.getUserId()));

        user.setFullName(request.fullName());

        if (request.wardId() == null) {
            user.setWard(null);
        } else {
            Ward ward = wardService.requireActiveWardEntity(request.wardId());
            user.setWard(ward);
        }

        User saved = userRepository.save(user);
        auditService.record(saved, "PROFILE_UPDATED", saved.getUserId(), null);

        return UserProfileResponse.from(saved);
    }
}
