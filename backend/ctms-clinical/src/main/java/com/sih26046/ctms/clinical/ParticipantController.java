package com.sih26046.ctms.clinical;

import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Participants (§8.11, §14.6, §21.2). */
@RestController
@RequestMapping("/api/v1/participants")
@PreAuthorize("hasAuthority('participant:read')")
public class ParticipantController {

    private final EnrollmentService enrollment;
    private final ParticipantRepository participants;
    private final IdempotencyStore idempotency;

    public ParticipantController(
            EnrollmentService enrollment,
            ParticipantRepository participants,
            IdempotencyStore idempotency) {
        this.enrollment = enrollment;
        this.participants = participants;
        this.idempotency = idempotency;
    }

    /**
     * A participant as the API describes them.
     *
     * <p>Note what cannot appear here: name, contact details, full date of birth. The view has
     * no field for them, so a change to the entity cannot leak one into a response by accident
     * (ADR-011).
     */
    public record ParticipantView(
            UUID id,
            UUID trialId,
            UUID trialSiteId,
            String subjectCode,
            LocalDate enrollmentDate,
            String status,
            Integer dateOfBirthYear,
            String sex,
            int version) {

        static ParticipantView of(ParticipantEntity p) {
            return new ParticipantView(
                    p.getId(),
                    p.getTrialId(),
                    p.getTrialSiteId(),
                    p.getSubjectCode(),
                    p.getEnrollmentDate(),
                    p.getStatus(),
                    p.getDateOfBirthYear(),
                    p.getSex(),
                    p.getVersion());
        }
    }

    public record WithdrawRequest(String reason) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<ParticipantView> list(@RequestParam UUID trialId) {
        return participants.findAllByTrialIdOrderBySubjectCode(trialId).stream()
                .map(ParticipantView::of)
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ParticipantView get(@PathVariable UUID id) {
        return participants
                .findById(id)
                .map(ParticipantView::of)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('participant:create')")
    public ResponseEntity<ParticipantView> enrol(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EnrollmentRequest request,
            @AuthenticationPrincipal CurrentUser caller) {

        // §14.5 — a retried enrolment must return the original participant, not create a
        // second person. Enrolment is the one operation where a duplicate is not merely
        // untidy: it produces a real participant record nobody intended.
        Optional<UUID> alreadyEnrolled = idempotency.lookup(idempotencyKey, caller.userId());
        if (alreadyEnrolled.isPresent()) {
            return participants
                    .findById(alreadyEnrolled.get())
                    .map(p -> ResponseEntity.ok(ParticipantView.of(p)))
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        }

        ParticipantEntity enrolled = enrollment.enrol(request, caller);
        idempotency.remember(idempotencyKey, caller.userId(), enrolled.getId());

        return ResponseEntity.created(URI.create("/api/v1/participants/" + enrolled.getId()))
                .eTag("\"%d\"".formatted(enrolled.getVersion()))
                .body(ParticipantView.of(enrolled));
    }

    @PostMapping("/{id}/withdrawal")
    @PreAuthorize("hasAuthority('participant:withdraw')")
    @Transactional
    public ParticipantView withdraw(
            @PathVariable UUID id, @RequestBody WithdrawRequest request) {
        ParticipantEntity participant =
                participants
                        .findById(id)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        // §20.3 — withdrawal stops new data. It does not delete what was already collected:
        // the data gathered while the participant was consented remains part of the trial.
        participant.withdraw(request.reason());
        return ParticipantView.of(participants.save(participant));
    }

    @ExceptionHandler(EnrollmentService.TrialNotEnrollingException.class)
    public ResponseEntity<String> onNotEnrolling(EnrollmentService.TrialNotEnrollingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /**
     * A duplicate subject code, or enrolment past the trial's target, both arrive here.
     *
     * <p>Both are constraint violations the database refused, and both are the caller's error.
     * Critically the transaction is already rolled back by this point, so the enrolment
     * counters are untouched.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        return ResponseEntity.unprocessableEntity()
                .body("The enrolment violates a data constraint");
    }
}
