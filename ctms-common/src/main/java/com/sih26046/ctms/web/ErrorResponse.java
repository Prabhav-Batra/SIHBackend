package com.sih26046.ctms.web;

/**
 * The one shape every error takes on the wire (§18.17). A client never sees a stack trace, a SQL
 * message, or an internal class name — {@code code} and {@code message} are always safe to show a
 * user, and {@code requestId} is the correlation key back into the server log and audit trail
 * (§19.6, §30.3).
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, String requestId) {}

    public static ErrorResponse of(String code, String message) {
        String requestId = RequestIdFilter.current();
        return new ErrorResponse(new Body(code, message, requestId));
    }
}
