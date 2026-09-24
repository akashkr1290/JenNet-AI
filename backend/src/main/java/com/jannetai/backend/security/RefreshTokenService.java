package com.jannetai.backend.security;

import com.jannetai.backend.config.JwtProperties;
import com.jannetai.backend.entity.RefreshToken;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.exception.InvalidRefreshTokenException;
import com.jannetai.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque refresh tokens persisted per V17__create_refresh_tokens.sql - see
 * that migration's header for why these are not JWTs. Implements SRS 27.5:
 * single-use rotation, and reuse of an already-rotated/revoked token
 * invalidates its whole family.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public record IssuedToken(String rawToken, RefreshToken entity) {
    }

    /** Starts a brand-new token family - called only at login (not at rotation). */
    @Transactional
    public IssuedToken issueNewFamily(User user, String deviceLabel, String ipAddress) {
        return issue(user, UUID.randomUUID().toString(), deviceLabel, ipAddress);
    }

    private IssuedToken issue(User user, String familyId, String deviceLabel, String ipAddress) {
        String rawToken = generateRawToken();
        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .familyId(familyId)
                .tokenHash(hash(rawToken))
                .expiresAt(LocalDateTime.now().plusDays(jwtProperties.getRefreshTokenExpiryDays()))
                .deviceLabel(deviceLabel)
                .ipAddress(ipAddress)
                .build();
        RefreshToken saved = refreshTokenRepository.save(entity);
        return new IssuedToken(rawToken, saved);
    }

    /**
     * Validates the presented raw refresh token and, if valid, rotates it:
     * the old row is marked revoked+replaced, a new token in the same
     * family is issued and returned. If the presented token is already
     * revoked/replaced (i.e. reused), the entire family is revoked and an
     * exception is thrown, per SRS 27.5.
     */
    /**
     * Validates the presented raw refresh token and, if valid, rotates it:
     * the old row is marked revoked+replaced, a new token in the same
     * family is issued and returned. If the presented token is already
     * revoked/replaced (i.e. reused), the entire family is revoked and an
     * exception is thrown, per SRS 27.5.
     *
     * noRollbackFor is required: the family-revocation branch performs a
     * write (revokeFamily) and then deliberately throws
     * InvalidRefreshTokenException - without this, the default
     * rollback-on-RuntimeException would undo that revocation, defeating
     * the "reuse kills the whole family" security guarantee.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedToken validateAndRotate(String rawToken, String deviceLabel, String ipAddress) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not recognized"));

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }
        if (existing.getRevokedAt() != null) {
            // Reuse of a rotated/revoked token: treat as compromise, kill the family.
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), LocalDateTime.now());
            throw new InvalidRefreshTokenException(
                    "Refresh token was already used or revoked; all sessions in this family have been logged out");
        }

        IssuedToken next = issue(existing.getUser(), existing.getFamilyId(), deviceLabel, ipAddress);
        existing.setRevokedAt(LocalDateTime.now());
        existing.setReplacedByTokenId(next.entity().getRefreshTokenId());
        refreshTokenRepository.save(existing);
        return next;
    }

    /** Logout: revoke just this token's family (this session/device). */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(t -> refreshTokenRepository.revokeFamily(t.getFamilyId(), LocalDateTime.now()));
    }

    /** "Logout everywhere" / Admin-initiated remote revocation (SRS 27.5). */
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
