package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.ward.AdminWardResponse;
import com.jannetai.backend.dto.ward.WardUpsertRequest;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.WardBoundaryValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Audit GAP-020 (SRS 15.11 "ward/zone configuration", 13.4, 16.3 ward-boundary
 * editor): Admin create/update/activate/deactivate of wards.
 *
 * <ul>
 *   <li>Boundaries are validated by {@link WardBoundaryValidator} and must not
 *       overlap any other ACTIVE ward's boundary (409). Neighbouring wards may
 *       share a border.</li>
 *   <li>The stored boundary is what LocationService's reverse geocoding
 *       (WardLocator, audit GAP-008) reads on every submission - there is no
 *       cache, so a saved change applies to the next complaint.</li>
 *   <li>Rows are never deleted (users, locations and reports reference them);
 *       a deactivated ward is no longer offered or matched.</li>
 *   <li>Every change is written to the audit log with the previous values,
 *       including the full previous boundary, so each version can be
 *       recovered (SRS 15.11 "all configuration changes versioned and logged").</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WardAdminService {

    private final WardRepository wardRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<AdminWardResponse> listAll() {
        return wardRepository.findAllByOrderByNameAsc().stream().map(AdminWardResponse::from).toList();
    }

    @Transactional
    public AdminWardResponse create(User admin, WardUpsertRequest request) {
        String name = request.name().trim();
        String code = blankToNull(request.code());
        String boundary = validatedBoundary(request.boundaryGeojson(), null);
        requireUniqueName(name, null);
        requireUniqueCode(code, null);

        Ward ward = wardRepository.save(Ward.builder()
                .name(name)
                .code(code)
                .boundaryGeojson(boundary)
                .isActive(true)
                .build());
        auditService.record(admin, "WARD_CREATED", "WARD", ward.getWardId(),
                AuditJson.of("name", name, "code", code, "boundary_geojson", boundary));
        return AdminWardResponse.from(ward);
    }

    @Transactional
    public AdminWardResponse update(User admin, Long wardId, WardUpsertRequest request) {
        Ward ward = requireWard(wardId);
        String name = request.name().trim();
        String code = blankToNull(request.code());
        String boundary = validatedBoundary(request.boundaryGeojson(), Boolean.TRUE.equals(ward.getIsActive()) ? wardId : null);
        requireUniqueName(name, wardId);
        requireUniqueCode(code, wardId);

        String previousName = ward.getName();
        String previousCode = ward.getCode();
        String previousBoundary = ward.getBoundaryGeojson();
        ward.setName(name);
        ward.setCode(code);
        ward.setBoundaryGeojson(boundary);
        ward = wardRepository.save(ward);
        auditService.record(admin, "WARD_UPDATED", "WARD", wardId, AuditJson.of(
                "name", name, "previous_name", previousName,
                "code", code, "previous_code", previousCode,
                "boundary_changed", !Objects.equals(previousBoundary, boundary),
                "boundary_geojson", boundary, "previous_boundary_geojson", previousBoundary));
        return AdminWardResponse.from(ward);
    }

    @Transactional
    public AdminWardResponse setActive(User admin, Long wardId, boolean active) {
        Ward ward = requireWard(wardId);
        if (Boolean.valueOf(active).equals(ward.getIsActive())) {
            return AdminWardResponse.from(ward);
        }
        if (active && ward.getBoundaryGeojson() != null) {
            // re-activation must not create an overlap with wards activated meanwhile
            requireNoOverlap(WardBoundaryValidator.parseStoredOrEmpty(ward.getBoundaryGeojson()), wardId);
        }
        ward.setIsActive(active);
        ward = wardRepository.save(ward);
        auditService.record(admin, active ? "WARD_ACTIVATED" : "WARD_DEACTIVATED", "WARD", wardId,
                AuditJson.of("name", ward.getName()));
        return AdminWardResponse.from(ward);
    }

    /** Normalised GeoJSON, or null for "no boundary"; overlap-checked against the other active wards. */
    private String validatedBoundary(String geojson, Long selfId) {
        if (geojson == null || geojson.isBlank()) {
            return null;
        }
        WardBoundaryValidator.Result result = WardBoundaryValidator.validate(geojson); // 400 on invalid geometry
        requireNoOverlap(result.polygons(), selfId);
        return result.normalizedGeojson();
    }

    private void requireNoOverlap(List<List<List<double[]>>> polygons, Long selfId) {
        for (Ward other : wardRepository.findByIsActiveTrueOrderByNameAsc()) {
            if (other.getWardId().equals(selfId) || other.getBoundaryGeojson() == null) {
                continue;
            }
            if (WardBoundaryValidator.overlaps(polygons, WardBoundaryValidator.parseStoredOrEmpty(other.getBoundaryGeojson()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Boundary overlaps the boundary of ward '" + other.getName() + "' - wards may share a border but not area");
            }
        }
    }

    private void requireUniqueName(String name, Long selfId) {
        wardRepository.findFirstByName(name).filter(w -> !w.getWardId().equals(selfId)).ifPresent(w -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A ward named '" + w.getName() + "' already exists");
        });
    }

    private void requireUniqueCode(String code, Long selfId) {
        if (code == null) {
            return;
        }
        wardRepository.findFirstByCode(code).filter(w -> !w.getWardId().equals(selfId)).ifPresent(w -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ward code '" + code + "' is already used by '" + w.getName() + "'");
        });
    }

    private Ward requireWard(Long wardId) {
        return wardRepository.findById(wardId).orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
