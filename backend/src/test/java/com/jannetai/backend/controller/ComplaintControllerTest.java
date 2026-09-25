package com.jannetai.backend.controller;

import com.jannetai.backend.config.SecurityConfig;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.dto.complaint.VerificationDecisionRequest;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.VerificationDecision;
import com.jannetai.backend.security.JwtAuthenticationFilter;
import com.jannetai.backend.security.RateLimitingFilter;
import com.jannetai.backend.security.RestAccessDeniedHandler;
import com.jannetai.backend.security.RestAuthenticationEntryPoint;
import com.jannetai.backend.security.RoleConstants;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.complaint.AiClassificationService;
import com.jannetai.backend.service.complaint.AiProcessingDispatcher;
import com.jannetai.backend.service.complaint.ImageQualityGate;
import com.jannetai.backend.exception.ImageQualityRejectedException;
import com.jannetai.backend.service.complaint.ComplaintAppealService;
import com.jannetai.backend.service.complaint.ComplaintRatingService;
import com.jannetai.backend.service.complaint.ComplaintService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} controller-slice tests for {@link ComplaintController}
 * - real Spring MVC dispatch and real {@code @PreAuthorize} method-security
 * enforcement (via {@code @Import(SecurityConfig.class)}, which carries
 * {@code @EnableMethodSecurity}), with {@link ComplaintService}/{@link
 * AiClassificationService} mocked so no business logic runs. The four
 * {@link SecurityConfig} filter/handler dependencies are mocked too -
 * {@code addFilters = false} means the actual servlet filter chain never
 * runs for these MockMvc requests, only the method-security AOP interceptor
 * that wraps the controller bean itself, so those mocks only need to exist
 * to satisfy {@code SecurityConfig}'s constructor/bean wiring.
 *
 * Verifies the role gate on every endpoint returns 403 for a role NOT
 * listed in its {@code @PreAuthorize}, and 200/expected status for a role
 * that IS - i.e. this is a regression guard for the @PreAuthorize
 * annotations themselves, not for ComplaintService's business logic
 * (already covered by ComplaintServiceTest).
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
 * against ComplaintController.java's actual @PreAuthorize expressions and
 * request mappings.
 */
@WebMvcTest(controllers = ComplaintController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class ComplaintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private ComplaintService complaintService;
    @MockBean private AiClassificationService aiClassificationService;
    @MockBean private AiProcessingDispatcher aiProcessingDispatcher; // audit GAP-010
    @MockBean private ImageQualityGate imageQualityGate;             // audit GAP-032
    // Pre-existing test defect found during the audit fix session: the
    // controller also depends on these two services (Gap-backlog Patches
    // 11/12), so without mocks the @WebMvcTest context could not start.
    @MockBean private ComplaintRatingService complaintRatingService;
    @MockBean private ComplaintAppealService complaintAppealService;

    // SecurityConfig's own constructor dependencies - never actually invoked
    // since addFilters=false skips the servlet filter chain, but must exist
    // as beans for the context to start.
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private RateLimitingFilter rateLimitingFilter;
    @MockBean private RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    @MockBean private RestAccessDeniedHandler restAccessDeniedHandler;

    private Authentication authenticationFor(Role role) {
        User user = User.builder().userId(1L).role(role).fullName("Test User")
                .mobileNumber("+911234567890").build();
        UserPrincipal principal = new UserPrincipal(user);
        return new UsernamePasswordAuthenticationToken(
                principal, null, java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                RoleConstants.authority(role))));
    }

    // ---- Audit GAP-010: POST /complaints queues AI processing instead of running it inline ----

    @Test
    void createReturns201WithoutRunningAiInline() throws Exception {
        ComplaintResponse stub = stubResponse();
        when(complaintService.create(any(User.class), any(), any(), any(), any(), any(), any())).thenReturn(stub);
        when(aiProcessingDispatcher.submit(1L)).thenReturn(false);

        mockMvc.perform(multipart("/api/v1/complaints")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", "p.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .param("latitude", "12.9716")
                        .param("longitude", "77.5946")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(Role.CITIZEN))))
                .andExpect(status().isCreated());

        verify(aiProcessingDispatcher).submit(1L);
        verify(aiClassificationService, never()).classifyAndRoute(anyLong());
        verify(complaintService, never()).getDetail(any(User.class), eq(1L));
    }

    @Test
    void unusablePhotoIsRejectedWith422AndNoComplaintIsCreated() throws Exception {
        org.mockito.Mockito.doThrow(new ImageQualityRejectedException("BLURRY",
                        "The photo is too blurry. Please hold the camera steady and retake it."))
                .when(imageQualityGate).check(any());

        mockMvc.perform(multipart("/api/v1/complaints")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", "p.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(Role.CITIZEN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error").value("IMAGE_QUALITY_REJECTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("retake")));

        verify(complaintService, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(aiProcessingDispatcher, never()).submit(anyLong());
    }

    // ---- /reopen: CITIZEN only ----

    @Test
    void citizenCanReopenTheirOwnComplaint() throws Exception {
        ComplaintResponse stub = stubResponse();
        when(complaintService.reopen(any(User.class), anyLong())).thenReturn(stub);

        mockMvc.perform(post("/api/v1/complaints/1/reopen")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(Role.CITIZEN))))
                .andExpect(status().isOk());
    }

    @Test
    void officerCannotCallReopen_citizenOnlyAction() throws Exception {
        mockMvc.perform(post("/api/v1/complaints/1/reopen")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                authenticationFor(Role.GOVERNMENT_OFFICER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotCallReopenEither_reopenIsCitizenOnlyWithNoAdminException() throws Exception {
        // Unlike most other actions, reopen has no ADMIN/SUPER_ADMIN
        // override in its @PreAuthorize - worth its own explicit test since
        // "admin can do everything" is the pattern everywhere else in this
        // controller and would be an easy accidental regression to miss.
        mockMvc.perform(post("/api/v1/complaints/1/reopen")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(Role.ADMIN))))
                .andExpect(status().isForbidden());
    }

    // ---- /verify: VERIFICATION_TEAM, ADMIN, SUPER_ADMIN ----

    @Test
    void verificationTeamCanVerify() throws Exception {
        ComplaintResponse stub = stubResponse();
        when(complaintService.verify(any(User.class), anyLong(), any(VerificationDecisionRequest.class)))
                .thenReturn(stub);
        VerificationDecisionRequest request = new VerificationDecisionRequest(
                VerificationDecision.VERIFIED, ComplaintCategory.POTHOLE, null, null, null, null);

        mockMvc.perform(patch("/api/v1/complaints/1/verify")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                authenticationFor(Role.VERIFICATION_TEAM)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void citizenCannotVerify() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest(
                VerificationDecision.VERIFIED, ComplaintCategory.POTHOLE, null, null, null, null);

        mockMvc.perform(patch("/api/v1/complaints/1/verify")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(Role.CITIZEN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void maintenanceTeamCannotVerify() throws Exception {
        // MAINTENANCE_TEAM is a valid staff role elsewhere in this
        // controller (e.g. /status) but is deliberately excluded from
        // /verify's role list.
        VerificationDecisionRequest request = new VerificationDecisionRequest(
                VerificationDecision.VERIFIED, ComplaintCategory.POTHOLE, null, null, null, null);

        mockMvc.perform(patch("/api/v1/complaints/1/verify")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                authenticationFor(Role.MAINTENANCE_TEAM)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ---- /approve-budget: DEPARTMENT_HEAD, ADMIN, SUPER_ADMIN ----

    @Test
    void departmentHeadCanCallApproveBudgetEndpoint() throws Exception {
        // Controller-level role gate only - ComplaintServiceTest already
        // covers the deeper own-department-only business rule this
        // delegates to.
        ComplaintResponse stub = stubResponse();
        when(complaintService.approveBudget(any(User.class), anyLong())).thenReturn(stub);

        mockMvc.perform(patch("/api/v1/complaints/1/approve-budget")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                authenticationFor(Role.DEPARTMENT_HEAD))))
                .andExpect(status().isOk());
    }

    @Test
    void governmentOfficerCannotCallApproveBudget() throws Exception {
        mockMvc.perform(patch("/api/v1/complaints/1/approve-budget")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                authenticationFor(Role.GOVERNMENT_OFFICER))))
                .andExpect(status().isForbidden());
    }

    private ComplaintResponse stubResponse() {
        return ComplaintResponse.from(
                com.jannetai.backend.entity.Complaint.builder()
                        .complaintId(1L)
                        .referenceNumber("JN-2026-000001")
                        .citizen(User.builder().userId(1L).role(Role.CITIZEN).build())
                        .category(ComplaintCategory.POTHOLE)
                        .status(ComplaintStatus.SUBMITTED)
                        .corroborationCount(0)
                        .isEscalated(false)
                        .isReopened(false)
                        .build(),
                java.util.List.of(),
                java.util.List.of());
    }
}
