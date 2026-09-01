package com.sih26046.ctms.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Authentication identity (§8.2). */
@Entity
@Table(name = "users")
public class UserAccountEntity {

    /** §18.5 — ten consecutive failures locks the account. */
    private static final int LOCKOUT_THRESHOLD = 10;

    @Id private UUID id;

    // users.email is citext (§8.2), which reports as JDBC OTHER. Without this the entity
    // would map to varchar and Hibernate's schema validation would reject the column. The
    // citext type is worth keeping: it makes case-insensitive uniqueness a property of the
    // column rather than something every query has to remember to apply.
    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "institution_id")
    private UUID institutionId;

    @Column(nullable = false)
    private String status;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserAccountEntity() {} // JPA

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getInstitutionId() {
        return institutionId;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    /** Records a failed attempt, locking the account once the threshold is reached. */
    void recordFailedLogin() {
        this.failedLoginCount++;
        if (this.failedLoginCount >= LOCKOUT_THRESHOLD) {
            this.status = "LOCKED";
        }
    }

    void recordSuccessfulLogin(Instant at) {
        this.failedLoginCount = 0;
        this.lastLoginAt = at;
    }
}
