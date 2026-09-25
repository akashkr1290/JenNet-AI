package com.jannetai.backend.repository;

import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.enums.SettingScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Phase 3 skeleton (plain CRUD access only), extended Phase 14 (Admin &
 * Settings Module, SRS 15.15) with the PLATFORM-only lookups
 * {@link com.jannetai.backend.service.admin.PlatformSettingsService}
 * needs (PLATFORM-scoped rows always have a null scope_id - V13's own
 * comment), and extended again Phase 15 (Notification Module / Personal
 * Settings, SRS 15.13/15.15) with the USER-scoped equivalents
 * {@link com.jannetai.backend.service.notification.NotificationPreferenceService}
 * and {@link com.jannetai.backend.service.settings.PersonalSettingsService}
 * need - both fronted by their own closed key registries, same
 * "application layer enforces which key/scope combination is legitimate"
 * division of responsibility V13's header comment describes.
 */
@Repository
public interface SettingRepository extends JpaRepository<Setting, Long> {

    List<Setting> findByScope(SettingScope scope);

    Optional<Setting> findByScopeAndScopeIdIsNullAndKey(SettingScope scope, String key);

    /** Phase 15: a USER-scoped row for one specific user + key (scope_id = that user's id). */
    Optional<Setting> findByScopeAndScopeIdAndKey(SettingScope scope, Long scopeId, String key);

    /** Phase 15: every USER-scoped row for one user, for a single-fetch "full settings" read. */
    List<Setting> findByScopeAndScopeId(SettingScope scope, Long scopeId);

    /** Audit GAP-038: one key for many users in a single query (officer availability at assignment time). */
    List<Setting> findByScopeAndKeyAndScopeIdIn(SettingScope scope, String key, java.util.Collection<Long> scopeIds);
}
