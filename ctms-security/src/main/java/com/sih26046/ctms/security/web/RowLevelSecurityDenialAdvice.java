package com.sih26046.ctms.security.web;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a row-level security refusal into 403 rather than 500.
 *
 * <p>RLS is the last line, not the first: a write that reaches the database out of scope is
 * normally a bug in a controller's own checks. But "normally" is not "always", and the failure
 * mode without this advice is a 500 that reads like an outage — which is both wrong for the
 * caller and noisy enough to bury the real signal.
 *
 * <p>The check is on SQLSTATE rather than on the Spring exception type. PgJDBC raises a plain
 * {@code PSQLException} for 42501 rather than the JDBC subclass Spring's translator keys on, so
 * which {@link DataAccessException} arrives here depends on translator internals; the SQLSTATE
 * does not. {@code 42501} is {@code insufficient_privilege}, which a policy violation and a
 * missing GRANT share — both are correctly a 403 to the caller.
 *
 * <p>The body says nothing about which policy refused. That is deliberate: a message naming the
 * institution or trial that owns the row would disclose the row (§6.4).
 */
@RestControllerAdvice
public class RowLevelSecurityDenialAdvice {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final AuditTrail audit;

    public RowLevelSecurityDenialAdvice(AuditTrail audit) {
        this.audit = audit;
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> onDataAccessException(
            DataAccessException e, HttpServletRequest request) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && INSUFFICIENT_PRIVILEGE.equals(sql.getSQLState())) {
                // Coarse-grained on purpose: the advice sees only the exception, not which
                // business action was attempted. "Who was refused, on what path, and when" is
                // still exactly what §19.6's failed-access query needs.
                audit.recordDenied(callerId(), request.getMethod() + " " + request.getRequestURI(), "rls_policy");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("The record is outside your scope");
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        throw e;
    }

    private static java.util.UUID callerId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof CurrentUser user ? user.userId() : null;
    }
}
