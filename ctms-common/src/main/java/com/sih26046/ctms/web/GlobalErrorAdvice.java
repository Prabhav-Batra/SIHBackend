package com.sih26046.ctms.web;

import com.sih26046.ctms.audit.AuditTrail;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fallback for every exception a controller does not already translate itself (§18.17).
 *
 * <p>Spring dispatches an exception to the most specific {@code @ExceptionHandler} it can find,
 * always preferring one declared on the controller that threw over one declared here — so the
 * existing per-controller handlers (a domain-specific 422 for a constraint violation, a 409 for
 * a stale write) are unaffected and keep firing exactly as before. This class only catches what
 * nothing else claimed: security exceptions that never reached a controller's own handler,
 * malformed request bodies, validation failures, and — last resort — anything unanticipated,
 * which becomes a bare 500 with full detail in the log and nothing but a correlation id on the
 * wire.
 */
@RestControllerAdvice
public class GlobalErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorAdvice.class);

    private final AuditTrail audit;

    public GlobalErrorAdvice(AuditTrail audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> onResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String message = e.getReason() != null ? e.getReason() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(ErrorResponse.of(codeOf(status), message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> onAccessDenied(HttpServletRequest request) {
        // Generic on the wire on purpose (§6.5, §18.17); the specifics — who, and which
        // permission a @PreAuthorize check found missing — go to the audit trail instead,
        // keyed by request id.
        //
        // No CurrentUser reference here: ctms-common cannot depend on ctms-security (that
        // dependency runs the other way), so the actor id comes from the principal's name
        // rather than the typed object, and is a plain string rather than a parsed UUID.
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication == null ? null : authentication.getName();
        audit.recordDenied(
                parseUuid(actor), request.getMethod() + " " + request.getRequestURI(), "authorization");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", "You do not have permission to do that"));
    }

    private static java.util.UUID parseUuid(String value) {
        try {
            return value == null ? null : java.util.UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> onAuthentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", "Authentication is required"));
    }

    /**
     * The one place a response names a field: the caller supplied it and needs to know what to
     * fix, and the field names are already known to them (§18.17's stated exception to full
     * genericness).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        String message =
                fields.isEmpty()
                        ? "Validation failed"
                        : "Validation failed: " + fields;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onMalformedBody() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST", "The request body could not be read"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnhandled(Exception e) {
        // The only place a stack trace goes is here — into the log, alongside the same request
        // id the client receives, and nowhere else (§18.17, §30.1).
        log.error("Unhandled exception [requestId={}]", RequestIdFilter.current(), e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("INTERNAL_ERROR", "Something went wrong"));
    }

    private static String codeOf(HttpStatus status) {
        return status.name();
    }
}
