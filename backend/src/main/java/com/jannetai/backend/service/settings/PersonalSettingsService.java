package com.jannetai.backend.service.settings;

import com.jannetai.backend.dto.settings.PersonalSettingsResponse;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 15 (Personal Settings, SRS 15.15). Owns the USER-scoped rows the
 * generic {@link SettingRepository} added this phase - see
 * {@link PersonalSettingKey}'s Javadoc for exactly what is/isn't in
 * scope here. Mirrors {@code PlatformSettingsService}'s upsert pattern
 * (Phase 14) at USER scope instead of PLATFORM scope.
 */
@Service
@RequiredArgsConstructor
public class PersonalSettingsService {

    /** Defaults applied when a user has never set a given key. */
    private static final String DEFAULT_LANGUAGE = "EN";
    private static final boolean DEFAULT_HIGH_CONTRAST = false;
    /** Default only applies to GOVERNMENT_OFFICER callers - see {@link #getSettings}. */
    private static final String DEFAULT_OFFICER_AVAILABILITY_STATUS = "AVAILABLE";

    private final SettingRepository settingRepository;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public PersonalSettingsResponse getSettings(User user) {
        Map<String, String> rows = settingRepository.findByScopeAndScopeId(SettingScope.USER, user.getUserId())
                .stream()
                .collect(Collectors.toMap(Setting::getKey, Setting::getValue, (a, b) -> b));

        String language = rows.getOrDefault(PersonalSettingKey.LANGUAGE.key(), DEFAULT_LANGUAGE);
        boolean highContrast = Boolean.parseBoolean(
                rows.getOrDefault(PersonalSettingKey.HIGH_CONTRAST_ENABLED.key(), String.valueOf(DEFAULT_HIGH_CONTRAST)));
        // Defaulted (not left null) for an officer who has never set this,
        // same as language/highContrast above - otherwise the Flutter
        // client would have no way to distinguish "not an officer" (always
        // null) from "an officer who hasn't set a status yet" (also null
        // if this weren't defaulted), and couldn't reliably decide whether
        // to show the officer-availability control at all.
        String officerStatus = user.getRole() == Role.GOVERNMENT_OFFICER
                ? rows.getOrDefault(PersonalSettingKey.OFFICER_AVAILABILITY_STATUS.key(), DEFAULT_OFFICER_AVAILABILITY_STATUS)
                : null;

        return new PersonalSettingsResponse(language, highContrast, officerStatus,
                notificationService.getPreferences(user));
    }

    /**
     * Partial update - a null field leaves that setting unchanged.
     *
     * @throws IllegalArgumentException if a non-null field's value fails
     *         its key's {@link PersonalSettingKey#validate} range check
     *         (mapped to 400 by GlobalExceptionHandler).
     * @throws ResponseStatusException 403 if officerAvailabilityStatus is
     *         non-null and the caller is not a GOVERNMENT_OFFICER.
     */
    @Transactional
    public PersonalSettingsResponse updateSettings(User user, String language, Boolean highContrastEnabled,
                                                     String officerAvailabilityStatus) {
        if (language != null) {
            upsert(user, PersonalSettingKey.LANGUAGE, language);
        }
        if (highContrastEnabled != null) {
            upsert(user, PersonalSettingKey.HIGH_CONTRAST_ENABLED, String.valueOf(highContrastEnabled));
        }
        if (officerAvailabilityStatus != null) {
            if (user.getRole() != Role.GOVERNMENT_OFFICER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only a Government Officer may set an availability status");
            }
            upsert(user, PersonalSettingKey.OFFICER_AVAILABILITY_STATUS, officerAvailabilityStatus);
        }
        return getSettings(user);
    }

    private void upsert(User user, PersonalSettingKey key, String rawValue) {
        key.validate(rawValue);
        Setting existing = settingRepository
                .findByScopeAndScopeIdAndKey(SettingScope.USER, user.getUserId(), key.key())
                .orElse(null);
        if (existing != null) {
            existing.setValue(rawValue);
            existing.setUpdatedBy(user);
            settingRepository.save(existing);
        } else {
            settingRepository.save(Setting.builder()
                    .scope(SettingScope.USER)
                    .scopeId(user.getUserId())
                    .key(key.key())
                    .value(rawValue)
                    .updatedBy(user)
                    .build());
        }
    }
}
