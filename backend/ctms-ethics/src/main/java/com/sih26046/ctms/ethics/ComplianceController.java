package com.sih26046.ctms.ethics;

import com.sih26046.ctms.security.CurrentUser;
import java.sql.SQLException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
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

/**
 * The compliance requirement catalogue and per-trial status (§8.21, §8.22).
 *
 * <p>Two resources with opposite access shapes. The catalogue is reference data — everyone
 * reads it, only those who define compliance write it — so its policy is a bare
 * "authenticated" for SELECT. Per-trial status is scoped to the trial, and §5.8 grants the
 * investigator {@code compliance:read} without {@code compliance:update}: being measured and
 * recording the measurement are different jobs, and the trial being assessed does not get to
 * write its own result.
 *
 * <p>That makes the permission layer here deliberately narrower than the row-level policy,
 * which would permit an assigned investigator to write. Narrower is the safe direction, and
 * §5.8 is the authority on which it should be.
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private static final String ACTIVE = "ACTIVE";
    private static final String UNIQUE_VIOLATION = "23505";

    private final ComplianceRequirementRepository requirements;
    private final TrialComplianceRepository trialCompliance;

    public ComplianceController(
            ComplianceRequirementRepository requirements,
            TrialComplianceRepository trialCompliance) {
        this.requirements = requirements;
        this.trialCompliance = trialCompliance;
    }

    // ── the catalogue ────────────────────────────────────────────────────────

    public record DefineRequirement(
            @NotBlank String code,
            @NotBlank String title,
            @NotBlank String description,
            @NotBlank String category,
            String authority,
            List<String> appliesToPhase,
            Boolean isMandatory,
            Boolean evidenceRequired) {}

    public record RequirementView(
            UUID id,
            String code,
            String title,
            String description,
            String category,
            String authority,
            List<String> appliesToPhase,
            boolean isMandatory,
            boolean evidenceRequired,
            String status) {

        static RequirementView of(ComplianceRequirementEntity r) {
            return new RequirementView(
                    r.getId(),
                    r.getCode(),
                    r.getTitle(),
                    r.getDescription(),
                    r.getCategory(),
                    r.getAuthority(),
                    r.getAppliesToPhase() == null ? null : List.of(r.getAppliesToPhase()),
                    r.isMandatory(),
                    r.isEvidenceRequired(),
                    r.getStatus());
        }
    }

    @GetMapping("/requirements")
    @PreAuthorize("hasAuthority('compliance:read')")
    @Transactional(readOnly = true)
    public List<RequirementView> listRequirements(
            @RequestParam(required = false) String category) {
        List<ComplianceRequirementEntity> found =
                category == null
                        ? requirements.findAllByStatusOrderByCode(ACTIVE)
                        : requirements.findAllByCategoryAndStatusOrderByCode(category, ACTIVE);
        return found.stream().map(RequirementView::of).toList();
    }

    @GetMapping("/requirements/{id}")
    @PreAuthorize("hasAuthority('compliance:read')")
    @Transactional(readOnly = true)
    public RequirementView getRequirement(@PathVariable UUID id) {
        return RequirementView.of(
                requirements
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Requirement not found")));
    }

    @PostMapping("/requirements")
    @PreAuthorize("hasAuthority('compliance:define')")
    @Transactional
    public ResponseEntity<RequirementView> define(@Valid @RequestBody DefineRequirement request) {
        ComplianceRequirementEntity saved =
                requirements.saveAndFlush(
                        new ComplianceRequirementEntity(
                                UUID.randomUUID(),
                                request.code(),
                                request.title(),
                                request.description(),
                                request.category(),
                                request.authority(),
                                request.appliesToPhase() == null
                                        ? null
                                        : request.appliesToPhase().toArray(String[]::new),
                                request.isMandatory() == null || request.isMandatory(),
                                request.evidenceRequired() == null || request.evidenceRequired()));

        return ResponseEntity.created(URI.create("/api/v1/compliance/requirements/" + saved.getId()))
                .body(RequirementView.of(saved));
    }

    // ── per-trial status ─────────────────────────────────────────────────────

    public record AttachRequirement(
            @NotNull UUID complianceRequirementId, UUID trialSiteId, LocalDate dueDate) {}

    public record Assess(
            @NotBlank String status, UUID evidenceDocumentId, String notes) {}

    public record TrialComplianceView(
            UUID id,
            UUID trialId,
            UUID complianceRequirementId,
            UUID trialSiteId,
            String status,
            UUID evidenceDocumentId,
            LocalDate dueDate,
            LocalDate completedDate,
            UUID verifiedBy,
            Instant verifiedAt,
            String notes,
            int version) {

        static TrialComplianceView of(TrialComplianceEntity t) {
            return new TrialComplianceView(
                    t.getId(),
                    t.getTrialId(),
                    t.getComplianceRequirementId(),
                    t.getTrialSiteId(),
                    t.getStatus(),
                    t.getEvidenceDocumentId(),
                    t.getDueDate(),
                    t.getCompletedDate(),
                    t.getVerifiedBy(),
                    t.getVerifiedAt(),
                    t.getNotes(),
                    t.getVersion());
        }
    }

    /**
     * The rollup.
     *
     * <p>{@code compliant} requires at least one requirement: a trial nobody has assigned
     * obligations to is unassessed, not compliant, and reporting otherwise would turn an
     * empty worklist into a clean bill of health.
     */
    public record ComplianceSummary(
            UUID trialId,
            long total,
            Map<String, Long> byStatus,
            long mandatoryOutstanding,
            boolean compliant) {}

    @GetMapping("/trials/{trialId}")
    @PreAuthorize("hasAuthority('compliance:read')")
    @Transactional(readOnly = true)
    public List<TrialComplianceView> listForTrial(@PathVariable UUID trialId) {
        return trialCompliance.findAllByTrialIdOrderByCreatedAt(trialId).stream()
                .map(TrialComplianceView::of)
                .toList();
    }

    @GetMapping("/trials/{trialId}/summary")
    @PreAuthorize("hasAuthority('compliance:read')")
    @Transactional(readOnly = true)
    public ComplianceSummary summary(@PathVariable UUID trialId) {
        Map<String, Long> byStatus = new TreeMap<>();
        long total = 0;
        long mandatoryOutstanding = 0;

        for (TrialComplianceRepository.StatusTally tally : trialCompliance.tallyByTrial(trialId)) {
            byStatus.merge(tally.getStatus(), tally.getTotal(), Long::sum);
            total += tally.getTotal();
            if (tally.getMandatory() && TrialComplianceEntity.isOutstanding(tally.getStatus())) {
                mandatoryOutstanding += tally.getTotal();
            }
        }

        return new ComplianceSummary(
                trialId, total, byStatus, mandatoryOutstanding, total > 0 && mandatoryOutstanding == 0);
    }

    @GetMapping("/trials/{trialId}/{id}")
    @PreAuthorize("hasAuthority('compliance:read')")
    @Transactional(readOnly = true)
    public ResponseEntity<TrialComplianceView> getForTrial(
            @PathVariable UUID trialId, @PathVariable UUID id) {
        TrialComplianceEntity found =
                trialCompliance.findByIdAndTrialId(id, trialId).orElseThrow(this::notFound);
        return withEtag(found).body(TrialComplianceView.of(found));
    }

    @PostMapping("/trials/{trialId}/requirements")
    @PreAuthorize("hasAuthority('compliance:update')")
    @Transactional
    public ResponseEntity<TrialComplianceView> attach(
            @PathVariable UUID trialId, @Valid @RequestBody AttachRequirement request) {

        TrialComplianceEntity saved =
                trialCompliance.saveAndFlush(
                        new TrialComplianceEntity(
                                UUID.randomUUID(),
                                trialId,
                                request.complianceRequirementId(),
                                request.trialSiteId(),
                                request.dueDate()));

        return ResponseEntity.created(
                        URI.create("/api/v1/compliance/trials/" + trialId + "/" + saved.getId()))
                .eTag(etagOf(saved))
                .body(TrialComplianceView.of(saved));
    }

    @PostMapping("/trials/{trialId}/{id}/status")
    @PreAuthorize("hasAuthority('compliance:update')")
    @Transactional
    public ResponseEntity<TrialComplianceView> assess(
            @PathVariable UUID trialId,
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody Assess request,
            @AuthenticationPrincipal CurrentUser caller) {

        TrialComplianceEntity found =
                trialCompliance.findByIdAndTrialId(id, trialId).orElseThrow(this::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required; fetch the record for its current ETag");
        }
        if (!etagOf(found).equals(ifMatch.trim())) {
            throw new OptimisticLockingFailureException("Compliance record " + id + " has changed");
        }

        found.assess(
                request.status(), caller.userId(), request.evidenceDocumentId(), request.notes());
        TrialComplianceEntity saved = trialCompliance.saveAndFlush(found);
        return withEtag(saved).body(TrialComplianceView.of(saved));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseStatusException notFound() {
        // §6.4 — out of scope and non-existent are deliberately indistinguishable.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Compliance record not found");
    }

    private static String etagOf(TrialComplianceEntity t) {
        return "\"%d\"".formatted(t.getVersion());
    }

    private static ResponseEntity.BodyBuilder withEtag(TrialComplianceEntity t) {
        return ResponseEntity.ok().eTag(etagOf(t));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> onStaleWrite() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * A duplicate is a conflict; anything else is a malformed request.
     *
     * <p>{@code uq_trial_compliance_scope} is the one that separates them: two rows for one
     * obligation would let the rollup count it twice and let one copy be COMPLIANT while the
     * other is not. Spring refines a unique violation into {@code DuplicateKeyException} only
     * on the JdbcTemplate path — through Hibernate it arrives as the plain supertype — so the
     * SQLSTATE is read here rather than dispatched on by exception type.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body("That requirement is already recorded for this trial at this scope");
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body("The compliance record violates a data constraint");
    }
}
