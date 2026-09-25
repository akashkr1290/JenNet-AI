package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.PlatformStatusResponse;
import com.jannetai.backend.dto.admin.PlatformStatusUpdateRequest;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Audit GAP-037 (SRS 15.11 "platform-wide announcement and maintenance-mode
 * control"; SRS 15.1 Exceptions "submission during platform maintenance window
 * is queued and auto-retried").
 *
 * <p>State lives in PLATFORM-scope rows of the existing {@code settings} table
 * (keys below), so it survives restarts and is shared by every backend
 * instance. These keys are deliberately not in {@link PlatformSettingKey}: that
 * registry holds numeric thresholds edited on the generic Settings screen,
 * while these are texts and a switch with their own screen and endpoint.
 *
 * <p>{@link com.jannetai.backend.security.MaintenanceModeFilter} reads {@link #current()} on write requests;
 * the value is cached for {@link #CACHE_MILLIS} so the filter does not query
 * the database on every request. A change made on one instance is therefore
 * seen by other instances within that time.
 */
@Service
@RequiredArgsConstructor
public class PlatformStatusService {

    static final String KEY_MAINTENANCE_MODE = "maintenance_mode";
    static final String KEY_MAINTENANCE_MESSAGE = "maintenance_message";
    static final String KEY_RETRY_AFTER_MINUTES = "maintenance_retry_after_minutes";
    static final String KEY_ANNOUNCEMENT = "announcement_text";
    static final int DEFAULT_RETRY_AFTER_MINUTES = 30;
    static final String DEFAULT_MAINTENANCE_MESSAGE =
            "JanNet AI is undergoing scheduled maintenance. Please try again shortly.";
    static final long CACHE_MILLIS = 5_000;

    private final SettingRepository settingRepository;
    private final AuditService auditService;
    private final Clock clock = Clock.systemUTC();

    private volatile PlatformStatusResponse cached;
    private volatile long cachedAtMillis;

    /** Cached current state (see class Javadoc). */
    public PlatformStatusResponse current() {
        PlatformStatusResponse snapshot = cached;
        long now = clock.millis();
        if (snapshot == null || now - cachedAtMillis > CACHE_MILLIS) {
            snapshot = load();
            cached = snapshot;
            cachedAtMillis = now;
        }
        return snapshot;
    }

    /** Public view: the maintenance message is only included while maintenance mode is on. */
    @Transactional(readOnly = true)
    public PlatformStatusResponse load() {
        return load(false);
    }

    /** Admin editor view: includes the stored maintenance message even while maintenance mode is off. */
    @Transactional(readOnly = true)
    public PlatformStatusResponse adminView() {
        return load(true);
    }

    private PlatformStatusResponse load(boolean alwaysIncludeMessage) {
        Map<String, Setting> rows = settingRepository.findByScope(SettingScope.PLATFORM).stream()
                .filter(s -> s.getScopeId() == null)
                .collect(Collectors.toMap(Setting::getKey, s -> s, (a, b) -> a));
        boolean maintenance = "true".equalsIgnoreCase(value(rows, KEY_MAINTENANCE_MODE).orElse("false"));
        String message = value(rows, KEY_MAINTENANCE_MESSAGE).filter(v -> !v.isBlank()).orElse(DEFAULT_MAINTENANCE_MESSAGE);
        int retryMinutes = value(rows, KEY_RETRY_AFTER_MINUTES).map(PlatformStatusService::parseMinutes)
                .orElse(DEFAULT_RETRY_AFTER_MINUTES);
        String announcement = value(rows, KEY_ANNOUNCEMENT).filter(v -> !v.isBlank()).orElse(null);
        LocalDateTime updatedAt = Stream.of(KEY_MAINTENANCE_MODE, KEY_MAINTENANCE_MESSAGE, KEY_RETRY_AFTER_MINUTES, KEY_ANNOUNCEMENT)
                .map(rows::get).filter(Objects::nonNull).map(Setting::getUpdatedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new PlatformStatusResponse(maintenance, maintenance || alwaysIncludeMessage ? message : null,
                retryMinutes * 60, announcement, updatedAt);
    }

    @Transactional
    public PlatformStatusResponse update(User admin, PlatformStatusUpdateRequest request) {
        PlatformStatusResponse before = load();
        Setting modeRow = upsert(admin, KEY_MAINTENANCE_MODE, Boolean.TRUE.equals(request.maintenanceMode()) ? "true" : "false");
        upsert(admin, KEY_MAINTENANCE_MESSAGE, trimToEmpty(request.maintenanceMessage()));
        upsert(admin, KEY_RETRY_AFTER_MINUTES, String.valueOf(
                request.retryAfterMinutes() != null ? request.retryAfterMinutes() : DEFAULT_RETRY_AFTER_MINUTES));
        upsert(admin, KEY_ANNOUNCEMENT, trimToEmpty(request.announcement()));
        PlatformStatusResponse after = load();
        cached = after;
        cachedAtMillis = clock.millis();
        // audit_logs.entity_id is NOT NULL: the maintenance_mode settings row identifies the change
        auditService.record(admin, "PLATFORM_STATUS_UPDATED", "SETTING", modeRow.getSettingId(), AuditJson.of(
                "maintenance_mode", after.maintenanceMode(), "previous_maintenance_mode", before.maintenanceMode(),
                "maintenance_message", request.maintenanceMessage(),
                "retry_after_seconds", after.retryAfterSeconds(),
                "announcement", after.announcement(), "previous_announcement", before.announcement()));
        return after;
    }

    private Setting upsert(User admin, String key, String value) {
        Setting row = settingRepository.findByScopeAndScopeIdIsNullAndKey(SettingScope.PLATFORM, key)
                .orElseGet(() -> Setting.builder().scope(SettingScope.PLATFORM).scopeId(null).key(key).build());
        row.setValue(value);
        row.setUpdatedBy(admin);
        return settingRepository.save(row);
    }

    private static Optional<String> value(Map<String, Setting> rows, String key) {
        return Optional.ofNullable(rows.get(key)).map(Setting::getValue);
    }

    static int parseMinutes(String raw) {
        try {
            int minutes = Integer.parseInt(raw.trim());
            return minutes >= 1 && minutes <= 1440 ? minutes : DEFAULT_RETRY_AFTER_MINUTES;
        } catch (RuntimeException e) {
            return DEFAULT_RETRY_AFTER_MINUTES;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
