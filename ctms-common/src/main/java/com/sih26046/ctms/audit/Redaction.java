package com.sih26046.ctms.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * §19.5 — the audit log records what changed, not the clinical content.
 *
 * <p>Applied to every {@code old_values}/{@code new_values} payload before it reaches {@link
 * AuditTrail}, so there is exactly one place that decides a field is sensitive rather than one
 * decision repeated, and inevitably drifting, at every call site.
 *
 * <p>Status, dates, codes, identifiers and enumerations pass through unchanged — an inspector
 * reading "status changed PENDING to APPROVED" needs that value. A clinical measurement, a
 * free-text narrative, or anything drawn from {@code participant_identities} is replaced with a
 * marker: the audit trail can prove the field was touched without duplicating the PHI it
 * protects into a table many roles read for compliance purposes.
 */
public final class Redaction {

    private Redaction() {}

    private static final String REDACTED = "<redacted>";

    /**
     * Field names treated as clinical content or identity data, matched case-insensitively
     * against a map key however it was produced (camelCase from a Java record, snake_case from a
     * raw SQL projection).
     */
    private static final Set<String> SENSITIVE =
            Set.of(
                    // observations (§8.15) — the measurement itself, not that one was recorded
                    "valuenumeric",
                    "value_numeric",
                    "valuetext",
                    "value_text",
                    "valueboolean",
                    "value_boolean",
                    // free-text clinical or deliberation narrative
                    "description",
                    "comments",
                    "summary",
                    "notes",
                    "conditions",
                    // participant_identities (§8.12) — field name only, never a value
                    "fullname",
                    "full_name",
                    "dateofbirth",
                    "date_of_birth",
                    "phone");

    /** Redacts a flat field map in place of building a new one at every call site. */
    public static Map<String, Object> redact(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            result.put(key, SENSITIVE.contains(key.toLowerCase()) ? REDACTED : entry.getValue());
        }
        return result;
    }
}
