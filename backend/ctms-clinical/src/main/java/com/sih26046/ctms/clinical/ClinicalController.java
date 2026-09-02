package com.sih26046.ctms.clinical;

import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

/**
 * Visits, observations and medications (§8.14–§8.16, §21.2).
 *
 * <p>Every write passes {@link ClinicalWriteGuard} first. Scope is still the database's job —
 * a participant outside the caller's site is simply not found — but lawful basis is a
 * different question from authorisation, and consent is what answers it.
 */
@RestController
@RequestMapping("/api/v1")
public class ClinicalController {

    private final ClinicalWriteGuard guard;
    private final VisitRepository visits;
    private final ObservationRepository observations;
    private final MedicationRepository medications;

    public ClinicalController(
            ClinicalWriteGuard guard,
            VisitRepository visits,
            ObservationRepository observations,
            MedicationRepository medications) {
        this.guard = guard;
        this.visits = visits;
        this.observations = observations;
        this.medications = medications;
    }

    // ── visits ───────────────────────────────────────────────────────────────

    public record CreateVisit(
            @NotNull UUID participantId,
            @NotBlank String visitName,
            int visitNumber,
            @NotNull LocalDate scheduledDate) {}

    public record VisitView(
            UUID id,
            UUID participantId,
            String visitName,
            int visitNumber,
            LocalDate scheduledDate,
            String status,
            int version) {

        static VisitView of(VisitEntity v) {
            return new VisitView(
                    v.getId(),
                    v.getParticipantId(),
                    v.getVisitName(),
                    v.getVisitNumber(),
                    v.getScheduledDate(),
                    v.getStatus(),
                    v.getVersion());
        }
    }

    @GetMapping("/visits")
    @PreAuthorize("hasAuthority('visit:read')")
    @Transactional(readOnly = true)
    public List<VisitView> listVisits(@RequestParam UUID participantId) {
        return visits.findAllByParticipantIdOrderByVisitNumber(participantId).stream()
                .map(VisitView::of)
                .toList();
    }

    @PostMapping("/visits")
    @PreAuthorize("hasAuthority('visit:create')")
    @Transactional
    public ResponseEntity<VisitView> createVisit(@Valid @RequestBody CreateVisit request) {
        guard.requireCollectable(request.participantId());

        VisitEntity saved =
                visits.saveAndFlush(
                        new VisitEntity(
                                UUID.randomUUID(),
                                request.participantId(),
                                request.visitName(),
                                request.visitNumber(),
                                request.scheduledDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(VisitView.of(saved));
    }

    // ── observations ─────────────────────────────────────────────────────────

    public record CreateObservation(
            @NotNull UUID visitId,
            @NotBlank String observationCode,
            @NotBlank String observationName,
            @NotBlank String category,
            BigDecimal valueNumeric,
            String valueText,
            Boolean valueBoolean,
            String unit) {}

    public record ObservationView(
            UUID id,
            UUID visitId,
            String observationCode,
            String observationName,
            String category,
            BigDecimal valueNumeric,
            String valueText,
            Boolean valueBoolean,
            String unit,
            String status,
            int version) {

        static ObservationView of(ObservationEntity o) {
            return new ObservationView(
                    o.getId(),
                    o.getVisitId(),
                    o.getObservationCode(),
                    o.getObservationName(),
                    o.getCategory(),
                    o.getValueNumeric(),
                    o.getValueText(),
                    o.getValueBoolean(),
                    o.getUnit(),
                    o.getStatus(),
                    o.getVersion());
        }
    }

    @GetMapping("/observations")
    @PreAuthorize("hasAuthority('observation:read')")
    @Transactional(readOnly = true)
    public List<ObservationView> listObservations(@RequestParam UUID visitId) {
        return observations.findAllByVisitIdOrderByObservationCode(visitId).stream()
                .map(ObservationView::of)
                .toList();
    }

    @PostMapping("/observations")
    @PreAuthorize("hasAuthority('observation:create')")
    @Transactional
    public ResponseEntity<ObservationView> createObservation(
            @Valid @RequestBody CreateObservation request,
            @AuthenticationPrincipal CurrentUser caller) {

        guard.requireCollectableForVisit(request.visitId(), visits);

        ObservationEntity saved =
                observations.saveAndFlush(
                        new ObservationEntity(
                                UUID.randomUUID(),
                                request.visitId(),
                                request.observationCode(),
                                request.observationName(),
                                request.category(),
                                request.valueNumeric(),
                                request.valueText(),
                                request.valueBoolean(),
                                request.unit(),
                                caller.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ObservationView.of(saved));
    }

    // ── medications ──────────────────────────────────────────────────────────

    public record CreateMedication(
            @NotNull UUID participantId,
            @NotBlank String medicationName,
            @NotBlank String medicationType,
            BigDecimal dose,
            String route,
            @NotNull LocalDate startDate) {}

    public record MedicationView(
            UUID id,
            UUID participantId,
            String medicationName,
            String medicationType,
            BigDecimal dose,
            String route,
            LocalDate startDate,
            boolean ongoing,
            int version) {

        static MedicationView of(MedicationEntity m) {
            return new MedicationView(
                    m.getId(),
                    m.getParticipantId(),
                    m.getMedicationName(),
                    m.getMedicationType(),
                    m.getDose(),
                    m.getRoute(),
                    m.getStartDate(),
                    m.isOngoing(),
                    m.getVersion());
        }
    }

    @GetMapping("/medications")
    @PreAuthorize("hasAuthority('medication:read')")
    @Transactional(readOnly = true)
    public List<MedicationView> listMedications(@RequestParam UUID participantId) {
        return medications.findAllByParticipantIdOrderByStartDate(participantId).stream()
                .map(MedicationView::of)
                .toList();
    }

    @PostMapping("/medications")
    @PreAuthorize("hasAuthority('medication:create')")
    @Transactional
    public ResponseEntity<MedicationView> createMedication(
            @Valid @RequestBody CreateMedication request) {
        guard.requireCollectable(request.participantId());

        MedicationEntity saved =
                medications.saveAndFlush(
                        new MedicationEntity(
                                UUID.randomUUID(),
                                request.participantId(),
                                request.medicationName(),
                                request.medicationType(),
                                request.dose(),
                                request.route(),
                                request.startDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MedicationView.of(saved));
    }

    // ── errors ───────────────────────────────────────────────────────────────

    @ExceptionHandler(ClinicalWriteGuard.ClinicalWriteNotPermittedException.class)
    public ResponseEntity<String> onNotCollectable(
            ClinicalWriteGuard.ClinicalWriteNotPermittedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /**
     * The database's own rules: an observation must carry a value, one value per code per
     * visit, a medication must have a known type and route. All are the caller's error.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        return ResponseEntity.unprocessableEntity().body("The record violates a data constraint");
    }
}
