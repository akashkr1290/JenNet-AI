package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.admin.SettingResponse;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 14 (Admin & Settings Module, SRS 15.15 "Admin-level default
 * thresholds"). Owns the PLATFORM-scoped rows of the {@code settings}
 * table (V13) - the closed key registry lives in {@link PlatformSettingKey},
 * not here, so this service never has to guess whether an arbitrary key
 * string is legitimate.
 *
 * BACKWARD-COMPATIBILITY DESIGN: every existing consumer of a threshold
 * this class now fronts (RoutingRuleService, EscalationSchedulerService,
 * ComplaintService) keeps its original {@code @Value}-injected field as
 * the fallback default; {@link #getOverride} returns {@link Optional} so
 * a consumer only changes behavior once an Admin has actually written a
 * row. Until then every existing Phase 1-13 behavior is unchanged -
 * consistent with this project's "never silently change completed
 * phases' behavior" discipline even when a later phase adds a real
 * integration point.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final SettingRepository settingRepository;
    private final AuditService auditService;

    /** Returns the current DB override for a key, if an Admin has ever set one. */
    @Transactional(readOnly = true)
    public Optional<String> getOverride(PlatformSettingKey key) {
        return settingRepository.findByScopeAndScopeIdIsNullAndKey(SettingScope.PLATFORM, key.key())
                .map(Setting::getValue);
    }

    /** Full registry view for the Admin Settings screen (SRS 16.3), one row per known key, override or not. */
    @Transactional(readOnly = true)
    public java.util.List<SettingResponse> listPlatformSettings() {
        Map<String, Setting> overrides = settingRepository.findByScope(SettingScope.PLATFORM).stream()
                .collect(Collectors.toMap(Setting::getKey, s -> s));
        return Arrays.stream(PlatformSettingKey.values())
                .map(def -> {
                    Setting row = overrides.get(def.key());
                    return new SettingResponse(
                            def.key(),
                            row != null ? row.getValue() : null,
                            def.recommendedDefault(),
                            row != null,
                            row != null ? row.getUpdatedAt() : null,
                            row != null && row.getUpdatedBy() != null ? row.getUpdatedBy().getFullName() : null);
                })
                .toList();
    }

    /**
     * Validates and upserts a PLATFORM-scoped override, audit-logging the
     * before/after value (Security 27.4: "all configuration changes are
     * versioned and logged").
     *
     * @throws IllegalArgumentException if key is not a known
     *         {@link PlatformSettingKey}, or rawValue fails that key's
     *         range validation (mapped to 400 by GlobalExceptionHandler).
     */
    @Transactional
    public SettingResponse updateSetting(User actor, String key, String rawValue) {
        PlatformSettingKey def = PlatformSettingKey.fromKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown platform setting key: " + key));
        def.validate(rawValue);

        Setting existing = settingRepository
                .findByScopeAndScopeIdIsNullAndKey(SettingScope.PLATFORM, key).orElse(null);
        String before = existing != null ? existing.getValue() : def.recommendedDefault();

        Setting saved;
        if (existing != null) {
            existing.setValue(rawValue);
            existing.setUpdatedBy(actor);
            saved = settingRepository.save(existing);
        } else {
            saved = settingRepository.save(Setting.builder()
                    .scope(SettingScope.PLATFORM)
                    .scopeId(null)
                    .key(key)
                    .value(rawValue)
                    .updatedBy(actor)
                    .build());
        }

        auditService.record(actor, "PLATFORM_SETTING_UPDATED", "SETTING", saved.getSettingId(),
                com.jannetai.backend.service.AuditJson.of("key", key, "before", before, "after", rawValue)); // audit GAP-021

        return new SettingResponse(key, saved.getValue(), def.recommendedDefault(), true,
                saved.getUpdatedAt(), actor.getFullName());
    }
}
