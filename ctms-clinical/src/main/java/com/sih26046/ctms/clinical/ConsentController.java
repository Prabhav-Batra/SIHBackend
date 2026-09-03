package com.sih26046.ctms.clinical;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Consent (§8.13, §21.2). */
@RestController
@RequestMapping("/api/v1/consents")
@PreAuthorize("hasAuthority('consent:read')")
public class ConsentController {

    private final ConsentRepository consents;
    private final AuditTrail audit;

    public ConsentController(ConsentRepository consents, AuditTrail audit) {
        this.consents = consents;
        this.audit = audit;
    }

    public record WithdrawRequest(String reason) {}

    public record ConsentView(
            UUID id, UUID participantId, String consentVersion, String status) {

        static ConsentView of(ConsentEntity c) {
            return new ConsentView(
                    c.getId(), c.getParticipantId(), c.getConsentVersion(), c.getStatus());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ConsentView> list(@RequestParam UUID participantId) {
        return consents.findAllByParticipantId(participantId).stream()
                .map(ConsentView::of)
                .toList();
    }

    /**
     * Withdraws consent.
     *
     * <p>Distinct from withdrawing from the trial (§20.3): a participant may revoke permission
     * to collect while remaining enrolled for safety follow-up. Either one stops collection,
     * which is why {@link ClinicalWriteGuard} checks both. The consent record itself is kept —
     * that permission was once given, and when it was withdrawn, is part of the trial's
     * evidence.
     */
    @PostMapping("/{id}/withdrawal")
    @PreAuthorize("hasAuthority('consent:withdraw')")
    @Transactional
    public ConsentView withdraw(
            @PathVariable UUID id,
            @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal CurrentUser caller) {
        ConsentEntity consent =
                consents.findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Consent not found"));
        Map<String, Object> before = valuesOf(consent);
        consent.withdraw(request.reason());
        ConsentEntity saved = consents.save(consent);

        // trialId is not on the consent record itself and resolving it here would mean an
        // extra participant lookup on every withdrawal purely for the audit row; §19's own
        // guidance (pass what's already in scope) says null is the right call rather than
        // adding a join whose only purpose is populating one denormalised column.
        audit.recordChange(
                caller.userId(),
                "WITHDRAW_CONSENT",
                "consents",
                saved.getId(),
                null,
                before,
                valuesOf(saved));

        return ConsentView.of(saved);
    }

    private static Map<String, Object> valuesOf(ConsentEntity c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("participantId", c.getParticipantId());
        values.put("consentVersion", c.getConsentVersion());
        values.put("status", c.getStatus());
        return values;
    }

    @SuppressWarnings("unused")
    private static Instant now() {
        return Instant.now();
    }
}
