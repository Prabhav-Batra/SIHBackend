package com.sih26046.ctms.trials;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Staff assignments (§8.10, §21.2).
 *
 * <p>These endpoints are the access-control surface of the platform. A row here is not a
 * record that someone has access — it is what grants it, because every scoped RLS policy
 * resolves through this table (§7.5).
 */
@RestController
@RequestMapping("/api/v1/trial-staff")
@PreAuthorize("hasAuthority('trial_staff:read')")
public class TrialStaffController {

    private final TrialStaffRepository assignments;

    public TrialStaffController(TrialStaffRepository assignments) {
        this.assignments = assignments;
    }

    public record CreateAssignment(
            @NotNull UUID trialId,
            UUID trialSiteId,
            @NotNull UUID userId,
            @NotBlank String staffRole) {}

    public record AssignmentView(
            UUID id,
            UUID trialId,
            UUID trialSiteId,
            UUID userId,
            String staffRole,
            LocalDate startDate,
            LocalDate endDate) {

        static AssignmentView of(TrialStaffEntity a) {
            return new AssignmentView(
                    a.getId(),
                    a.getTrialId(),
                    a.getTrialSiteId(),
                    a.getUserId(),
                    a.getStaffRole(),
                    a.getStartDate(),
                    a.getEndDate());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AssignmentView> list(@RequestParam UUID trialId) {
        return assignments.findAllByTrialIdAndEndDateIsNull(trialId).stream()
                .map(AssignmentView::of)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('trial_staff:create')")
    @Transactional
    public ResponseEntity<AssignmentView> assign(@Valid @RequestBody CreateAssignment request) {
        TrialStaffEntity saved =
                assignments.saveAndFlush(
                        new TrialStaffEntity(
                                UUID.randomUUID(),
                                request.trialId(),
                                request.trialSiteId(),
                                request.userId(),
                                request.staffRole()));

        return ResponseEntity.created(URI.create("/api/v1/trial-staff/" + saved.getId()))
                .body(AssignmentView.of(saved));
    }

    /**
     * Ends an assignment.
     *
     * <p>DELETE by verb, an UPDATE in fact. §20.1 forbids hard deletes and no DELETE privilege
     * is granted on this table, so ending is the only expressible removal — which is correct:
     * revoking someone's access must not also erase that they once had it.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('trial_staff:delete')")
    @Transactional
    public ResponseEntity<Void> end(@PathVariable UUID id) {
        TrialStaffEntity assignment =
                assignments
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Assignment not found"));
        assignment.end();
        assignments.save(assignment);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        // uq_trial_staff_active: one live assignment per user per site.
        return ResponseEntity.unprocessableEntity().body("The assignment already exists");
    }
}
