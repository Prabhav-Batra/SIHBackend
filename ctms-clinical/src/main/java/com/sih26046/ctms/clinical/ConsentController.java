package com.sih26046.ctms.clinical;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public ConsentController(ConsentRepository consents) {
        this.consents = consents;
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
    public ConsentView withdraw(@PathVariable UUID id, @RequestBody WithdrawRequest request) {
        ConsentEntity consent =
                consents.findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Consent not found"));
        consent.withdraw(request.reason());
        return ConsentView.of(consents.save(consent));
    }

    @SuppressWarnings("unused")
    private static Instant now() {
        return Instant.now();
    }
}
