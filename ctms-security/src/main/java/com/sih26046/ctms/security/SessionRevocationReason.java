package com.sih26046.ctms.security;

/** Why a session was revoked (§8.6). Mirrors ck_sessions_revoked_reason. */
public enum SessionRevocationReason {
    LOGOUT,
    ROTATED,
    REUSE_DETECTED,
    ADMIN_REVOKE,
    PASSWORD_CHANGE
}
