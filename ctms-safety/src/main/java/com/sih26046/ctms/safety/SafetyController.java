package com.sih26046.ctms.safety;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Adverse events and safety review (§8.17, §8.18, §21.2). */
@RestController
@RequestMapping("/api/v1")
public class SafetyController {

    private final AdverseEventRepository events;
    private final SafetyReviewRepository reviews;
    private final AuditTrail audit;

    public SafetyController(
            AdverseEventRepository events, SafetyReviewRepository reviews, AuditTrail audit) {
        this.events = events;
        this.reviews = reviews;
        this.audit = audit;
    }

    public record ReportEvent(
            @NotNull UUID participantId,
            UUID visitId,
            @NotBlank String eventTerm,
            @NotBlank String description,
            @NotNull LocalDate onsetDate,
            @NotBlank String severity,
            String seriousness,
            List<String> seriousCriteria) {}

    /**
     * An adverse event as returned by the API.
     *
     * <p>{@code description} is the reported narrative and is included here, where the caller
     * has already been scoped to the event. It must never reach a GIS layer or an aggregate
     * (§11.2), which is why those read from counts rather than from this endpoint.
     */
    public record EventView(
            UUID id,
            UUID participantId,
            UUID trialId,
            String eventTerm,
            String description,
            LocalDate onsetDate,
            String severity,
            String seriousness,
            List<String> seriousCriteria,
            String causality,
            String status,
            int version) {

        static EventView of(AdverseEventEntity e) {
            return new EventView(
                    e.getId(),
                    e.getParticipantId(),
                    e.getTrialId(),
                    e.getEventTerm(),
                    e.getDescription(),
                    e.getOnsetDate(),
                    e.getSeverity(),
                    e.getSeriousness(),
                    e.getSeriousCriteria() == null ? null : List.of(e.getSeriousCriteria()),
                    e.getCausality(),
                    e.getStatus(),
                    e.getVersion());
        }
    }

    @GetMapping("/adverse-events")
    @PreAuthorize("hasAuthority('adverse_event:read')")
    @Transactional(readOnly = true)
    public List<EventView> list(
            @RequestParam(required = false) UUID participantId,
            @RequestParam(required = false) UUID trialId) {

        if (participantId != null) {
            return events.findAllByParticipantIdOrderByOnsetDateDesc(participantId).stream()
                    .map(EventView::of)
                    .toList();
        }
        if (trialId != null) {
            return events.findAllByTrialIdOrderByOnsetDateDesc(trialId).stream()
                    .map(EventView::of)
                    .toList();
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "One of participantId or trialId is required");
    }

    @PostMapping("/adverse-events")
    @PreAuthorize("hasAuthority('adverse_event:create')")
    @Transactional
    public ResponseEntity<EventView> report(
            @Valid @RequestBody ReportEvent request, @AuthenticationPrincipal CurrentUser caller) {

        AdverseEventEntity saved =
                events.saveAndFlush(
                        new AdverseEventEntity(
                                UUID.randomUUID(),
                                request.participantId(),
                                request.visitId(),
                                request.eventTerm(),
                                request.description(),
                                request.onsetDate(),
                                request.severity(),
                                request.seriousness() == null
                                        ? "NON_SERIOUS"
                                        : request.seriousness(),
                                request.seriousCriteria() == null
                                        ? null
                                        : request.seriousCriteria().toArray(String[]::new),
                                caller.userId()));

        // description is free-text clinical narrative — Redaction masks it by field name
        // (§19.5); the real value is passed through here rather than pre-redacted.
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("participantId", saved.getParticipantId());
        newValues.put("visitId", request.visitId());
        newValues.put("eventTerm", saved.getEventTerm());
        newValues.put("description", saved.getDescription());
        newValues.put("onsetDate", saved.getOnsetDate());
        newValues.put("severity", saved.getSeverity());
        newValues.put("seriousness", saved.getSeriousness());
        newValues.put("status", saved.getStatus());
        audit.recordChange(
                caller.userId(),
                "CREATE_ADVERSE_EVENT",
                "adverse_events",
                saved.getId(),
                saved.getTrialId(),
                null,
                newValues);

        return ResponseEntity.created(URI.create("/api/v1/adverse-events/" + saved.getId()))
                .body(EventView.of(saved));
    }

    // ── review ───────────────────────────────────────────────────────────────

    public record CreateReview(
            @NotNull UUID adverseEventId,
            @NotBlank String assessedSeverity,
            @NotBlank String assessedCausality,
            @NotNull Boolean isExpected,
            Boolean requiresExpeditedReporting,
            String comments,
            @NotBlank String decision) {}

    public record ReviewView(
            UUID id,
            UUID adverseEventId,
            String assessedSeverity,
            String assessedCausality,
            boolean isExpected,
            boolean requiresExpeditedReporting,
            String decision) {

        static ReviewView of(SafetyReviewEntity r) {
            return new ReviewView(
                    r.getId(),
                    r.getAdverseEventId(),
                    r.getAssessedSeverity(),
                    r.getAssessedCausality(),
                    r.isExpected(),
                    r.isRequiresExpeditedReporting(),
                    r.getDecision());
        }
    }

    @GetMapping("/safety/reviews")
    @PreAuthorize("hasAuthority('safety_report:read')")
    @Transactional(readOnly = true)
    public List<ReviewView> listReviews(@RequestParam UUID adverseEventId) {
        return reviews.findAllByAdverseEventIdOrderByReviewDateDesc(adverseEventId).stream()
                .map(ReviewView::of)
                .toList();
    }

    /**
     * Records a review.
     *
     * <p>Gated on {@code adverse_event:review}, which §5.8 gives to the Safety Officer alone:
     * causality is the reviewer's judgement, and letting the reporting investigator set it
     * would make the assessment of their own trial's events their own to write.
     *
     * <p>Recording a review also moves the event out of REPORTED, so an assessed event cannot
     * sit indefinitely in the queue of things nobody has looked at.
     */
    @PostMapping("/safety/reviews")
    @PreAuthorize("hasAuthority('adverse_event:review')")
    @Transactional
    public ResponseEntity<ReviewView> review(
            @Valid @RequestBody CreateReview request,
            @AuthenticationPrincipal CurrentUser caller) {

        AdverseEventEntity event =
                events.findById(request.adverseEventId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Adverse event not found"));

        SafetyReviewEntity saved =
                reviews.saveAndFlush(
                        new SafetyReviewEntity(
                                UUID.randomUUID(),
                                request.adverseEventId(),
                                caller.userId(),
                                request.assessedSeverity(),
                                request.assessedCausality(),
                                request.isExpected(),
                                Boolean.TRUE.equals(request.requiresExpeditedReporting()),
                                request.comments(),
                                request.decision()));

        event.recordReview(request.assessedCausality());
        events.save(event);

        // comments is free-text deliberation narrative — Redaction masks it by field name
        // (§19.5); the real value is passed through here rather than pre-redacted.
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("adverseEventId", saved.getAdverseEventId());
        newValues.put("assessedSeverity", saved.getAssessedSeverity());
        newValues.put("assessedCausality", saved.getAssessedCausality());
        newValues.put("isExpected", saved.isExpected());
        newValues.put("requiresExpeditedReporting", saved.isRequiresExpeditedReporting());
        newValues.put("comments", request.comments());
        newValues.put("decision", saved.getDecision());
        audit.recordChange(
                caller.userId(),
                "REVIEW_ADVERSE_EVENT",
                "safety_reviews",
                saved.getId(),
                event.getTrialId(),
                null,
                newValues);

        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewView.of(saved));
    }

    /**
     * ck_adverse_events_serious_criteria is the one that matters here: a serious event with no
     * criteria recorded cannot be reported to an authority, which is the one thing a serious
     * event exists to trigger.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        return ResponseEntity.unprocessableEntity()
                .body("The event violates a data constraint; a serious event requires criteria");
    }
}
