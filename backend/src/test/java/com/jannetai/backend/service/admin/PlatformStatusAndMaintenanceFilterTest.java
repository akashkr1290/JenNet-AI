package com.jannetai.backend.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.dto.admin.PlatformStatusUpdateRequest;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.security.MaintenanceModeFilter;
import com.jannetai.backend.service.AuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit GAP-037 (SRS 15.11 / 15.1): maintenance mode and announcement. NOT EXECUTED here via Maven. */
@ExtendWith(MockitoExtension.class)
class PlatformStatusAndMaintenanceFilterTest {

    @Mock private SettingRepository settingRepository;
    @Mock private AuditService auditService;

    private final List<Setting> rows = new ArrayList<>();

    private PlatformStatusService service() {
        lenient().when(settingRepository.findByScope(SettingScope.PLATFORM)).thenAnswer(inv -> List.copyOf(rows));
        lenient().when(settingRepository.findByScopeAndScopeIdIsNullAndKey(eq(SettingScope.PLATFORM), anyString()))
                .thenAnswer(inv -> rows.stream().filter(r -> r.getKey().equals(inv.getArgument(1))).findFirst());
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting s = inv.getArgument(0);
            if (!rows.contains(s)) {
                s.setSettingId((long) rows.size() + 1);
                rows.add(s);
            }
            return s;
        });
        return new PlatformStatusService(settingRepository, auditService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultsToOffWithNoBanner() {
        var status = service().load();
        assertThat(status.maintenanceMode()).isFalse();
        assertThat(status.maintenanceMessage()).isNull();
        assertThat(status.announcement()).isNull();
        assertThat(status.retryAfterSeconds()).isEqualTo(1800);
    }

    @Test
    void adminUpdateIsStoredAuditedAndVisibleImmediately() {
        PlatformStatusService service = service();
        User admin = User.builder().userId(1L).role(Role.ADMIN).fullName("A").build();

        service.update(admin, new PlatformStatusUpdateRequest(true, "Back at 18:00", 10, "Water supply notice"));

        var now = service.current();
        assertThat(now.maintenanceMode()).isTrue();
        assertThat(now.maintenanceMessage()).isEqualTo("Back at 18:00");
        assertThat(now.retryAfterSeconds()).isEqualTo(600);
        assertThat(now.announcement()).isEqualTo("Water supply notice");
        verify(auditService).record(eq(admin), eq("PLATFORM_STATUS_UPDATED"), eq("SETTING"), anyLong(), anyString());
    }

    private static MockHttpServletResponse run(MaintenanceModeFilter filter, String method, String uri, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void duringMaintenanceWritesGet503WithRetryAfterButReadsAuthAndAdminsPass() throws Exception {
        PlatformStatusService service = service();
        service.update(User.builder().userId(1L).role(Role.ADMIN).build(),
                new PlatformStatusUpdateRequest(true, "Maintenance", 5, null));
        MaintenanceModeFilter filter = new MaintenanceModeFilter(service, new ObjectMapper().findAndRegisterModules());

        FilterChain blocked = mock(FilterChain.class);
        MockHttpServletResponse refused = run(filter, "POST", "/api/v1/complaints", blocked);
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("300");
        assertThat(refused.getContentAsString()).contains("\"error\":\"MAINTENANCE\"");
        verify(blocked, never()).doFilter(any(), any());

        FilterChain read = mock(FilterChain.class);
        run(filter, "GET", "/api/v1/complaints", read);
        verify(read).doFilter(any(), any());

        FilterChain login = mock(FilterChain.class);
        run(filter, "POST", "/api/v1/auth/login", login);
        verify(login).doFilter(any(), any());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        FilterChain adminWrite = mock(FilterChain.class);
        run(filter, "PUT", "/api/v1/admin/platform-status", adminWrite);
        verify(adminWrite).doFilter(any(), any());
    }

    @Test
    void whenMaintenanceIsOffWritesPass() throws Exception {
        MaintenanceModeFilter filter = new MaintenanceModeFilter(service(), new ObjectMapper());
        FilterChain chain = mock(FilterChain.class);
        run(filter, "POST", "/api/v1/complaints", chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void retryMinutesOutsideRangeFallBackToDefault() {
        assertThat(PlatformStatusService.parseMinutes("0")).isEqualTo(30);
        assertThat(PlatformStatusService.parseMinutes("abc")).isEqualTo(30);
        assertThat(PlatformStatusService.parseMinutes(" 15 ")).isEqualTo(15);
    }
}
