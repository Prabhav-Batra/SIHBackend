package com.sih26046.ctms.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Server-side refresh state (§8.6). */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason")
    private SessionRevocationReason revokedReason;

    // Postgres `inet`. Hibernate 6 maps it natively via SqlTypes.INET — storing it as text
    // would lose the type's validation and index behaviour.
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private InetAddress ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    protected SessionEntity() {} // JPA

    SessionEntity(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            InetAddress ipAddress,
            String userAgent) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    void revoke(SessionRevocationReason reason, Instant at) {
        // ck_sessions_revocation_paired: revoked_at and revoked_reason are set together.
        this.revokedAt = at;
        this.revokedReason = reason;
    }
}
