package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.SettingScope;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Scoped configuration key/value store. `key` is a reserved word in MySQL
 * and is backtick-quoted here to match V13's column definition. Maps onto
 * database/migrations/V13__create_settings.sql.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Long settingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private SettingScope scope;

    /** PLATFORM-scoped rows have no scopeId; DEPARTMENT/USER-scoped rows must
     *  carry the owning id (enforced at application layer, see V13 comment). */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "`key`", nullable = false, length = 100)
    private String key;

    @Column(name = "value", nullable = false, length = 200)
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
