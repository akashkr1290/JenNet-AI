package com.jannetai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Persisted refresh tokens enabling single-use rotation and whole-family
 * revocation on reuse (SRS 27.5). Maps onto
 * database/migrations/V17__create_refresh_tokens.sql.
 *
 * token_hash is a SHA-256 hash of the raw refresh token - the raw value is
 * never persisted, only returned once in the auth response body.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id")
    private Long refreshTokenId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Groups every token descended from one login via rotation. */
    @Column(name = "family_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String familyId;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "replaced_by_token_id")
    private Long replacedByTokenId;

    @Column(name = "device_label", length = 255)
    private String deviceLabel;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
