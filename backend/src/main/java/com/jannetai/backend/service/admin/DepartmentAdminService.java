package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.department.DepartmentResponse;
import com.jannetai.backend.dto.department.DepartmentUpsertRequest;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.DepartmentRepository;
import com.jannetai.backend.repository.RoutingRuleRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Audit GAP-020 (SRS 15.11 "department configuration", 13.4 Admin
 * responsibilities): Admin create/update/activate/deactivate of departments,
 * including the department head.
 *
 * Safeguards (each a 409 with an explanation):
 * <ul>
 *   <li>the configured fallback department
 *       ({@code app.department-assignment.fallback-department-name}) can be
 *       neither renamed nor deactivated - DepartmentAssignmentService finds it
 *       by that name;</li>
 *   <li>a department still targeted by an active routing rule cannot be
 *       deactivated (complaints would be routed to it) - retarget or deactivate
 *       the rules first;</li>
 *   <li>names are unique.</li>
 * </ul>
 * Rows are never deleted; every change is audited with the previous values.
 */
@Service
@RequiredArgsConstructor
public class DepartmentAdminService {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final AuditService auditService;

    @Value("${app.department-assignment.fallback-department-name}")
    private String fallbackDepartmentName;

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listAll() {
        return departmentRepository.findAllByOrderByNameAsc().stream().map(DepartmentResponse::from).toList();
    }

    @Transactional
    public DepartmentResponse create(User admin, DepartmentUpsertRequest request) {
        if (request.headUserId() != null) {
            throw new IllegalArgumentException("Create the department first, then assign a head who belongs to it");
        }
        String name = request.name().trim();
        requireUniqueName(name, null);
        Department department = departmentRepository.save(Department.builder()
                .name(name)
                .description(blankToNull(request.description()))
                .isActive(true)
                .build());
        auditService.record(admin, "DEPARTMENT_CREATED", "DEPARTMENT", department.getDepartmentId(),
                AuditJson.of("name", name, "description", department.getDescription()));
        return DepartmentResponse.from(department);
    }

    @Transactional
    public DepartmentResponse update(User admin, Long departmentId, DepartmentUpsertRequest request) {
        Department department = requireDepartment(departmentId);
        String name = request.name().trim();
        if (isFallback(department) && !department.getName().equals(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "'" + department.getName()
                    + "' is the configured fallback department for unmapped categories and cannot be renamed");
        }
        requireUniqueName(name, departmentId);
        User head = request.headUserId() == null ? null : requireEligibleHead(request.headUserId(), departmentId);

        String previousName = department.getName();
        String previousDescription = department.getDescription();
        Long previousHead = department.getHeadUser() != null ? department.getHeadUser().getUserId() : null;
        department.setName(name);
        department.setDescription(blankToNull(request.description()));
        department.setHeadUser(head);
        department = departmentRepository.save(department);
        auditService.record(admin, "DEPARTMENT_UPDATED", "DEPARTMENT", departmentId, AuditJson.of(
                "name", name, "previous_name", previousName,
                "description", department.getDescription(), "previous_description", previousDescription,
                "head_user_id", head != null ? head.getUserId() : null, "previous_head_user_id", previousHead));
        return DepartmentResponse.from(department);
    }

    @Transactional
    public DepartmentResponse setActive(User admin, Long departmentId, boolean active) {
        Department department = requireDepartment(departmentId);
        if (Boolean.valueOf(active).equals(department.getIsActive())) {
            return DepartmentResponse.from(department);
        }
        if (!active) {
            if (isFallback(department)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "'" + department.getName()
                        + "' is the configured fallback department and cannot be deactivated");
            }
            if (routingRuleRepository.existsByDepartment_DepartmentIdAndIsActiveTrue(departmentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Active routing rules still send complaints to '"
                        + department.getName() + "' - retarget or deactivate those rules first");
            }
        }
        department.setIsActive(active);
        department = departmentRepository.save(department);
        auditService.record(admin, active ? "DEPARTMENT_ACTIVATED" : "DEPARTMENT_DEACTIVATED", "DEPARTMENT",
                departmentId, AuditJson.of("name", department.getName()));
        return DepartmentResponse.from(department);
    }

    private User requireEligibleHead(Long userId, Long departmentId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getRole() != Role.DEPARTMENT_HEAD) {
            throw new IllegalArgumentException("The department head must have the DEPARTMENT_HEAD role");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("The department head account must be ACTIVE");
        }
        if (user.getDepartment() == null || !departmentId.equals(user.getDepartment().getDepartmentId())) {
            throw new IllegalArgumentException("The department head must belong to this department");
        }
        return user;
    }

    private void requireUniqueName(String name, Long selfId) {
        departmentRepository.findFirstByName(name).filter(d -> !d.getDepartmentId().equals(selfId)).ifPresent(d -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A department named '" + d.getName() + "' already exists");
        });
    }

    private boolean isFallback(Department department) {
        return fallbackDepartmentName != null && fallbackDepartmentName.equals(department.getName());
    }

    private Department requireDepartment(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
