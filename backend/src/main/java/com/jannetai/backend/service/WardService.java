package com.jannetai.backend.service;

import com.jannetai.backend.dto.user.WardResponse;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.WardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only ward lookups for the Citizen Module (SRS 15.1: "address/ward"
 * as a registration/profile input). Ward CRUD itself (create/deactivate a
 * ward) is not a citizen-facing operation and is not built here - it
 * belongs with whichever phase owns Admin/reference-data management
 * (Phase 14).
 */
@Service
@RequiredArgsConstructor
public class WardService {

    private final WardRepository wardRepository;

    @Transactional(readOnly = true)
    public List<WardResponse> listActiveWards() {
        return wardRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(WardResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WardResponse getActiveWard(Long wardId) {
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardId));
        if (!Boolean.TRUE.equals(ward.getIsActive())) {
            throw new ResourceNotFoundException("Ward not found: " + wardId);
        }
        return WardResponse.from(ward);
    }

    /** Used by UserProfileService to validate+attach a ward on profile update. */
    @Transactional(readOnly = true)
    public Ward requireActiveWardEntity(Long wardId) {
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardId));
        if (!Boolean.TRUE.equals(ward.getIsActive())) {
            throw new ResourceNotFoundException("Ward not found: " + wardId);
        }
        return ward;
    }
}
