package com.sih26046.ctms.ethics;

import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * Ethics submission, review, and decision (§5.5, §5.7, §8.19, §8.20).
 *
 * <p>Three audiences read this resource and they read different amounts of it. The committee
 * sees its own institution's submissions and the deliberation behind them. The submitting
 * investigator sees their own submission and its outcome, but not the deliberation. The
 * regulator sees that a decision exists, and also not the deliberation. Every one of those
 * boundaries is a row-level policy; the permission expressions below only decide whether an
 * empty result or a 403 is the more honest answer.
 */
@RestController
@RequestMapping("/api/v1/ethics")
public class EthicsController {

    private final EthicsSubmissionRepository submissions;
    private final EthicsReviewRepository reviews;

    public EthicsController(
            EthicsSubmissionRepository submissions, EthicsReviewRepository reviews) {
        this.submissions = submissions;
        this.reviews = reviews;
    }

    // ── views ────────────────────────────────────────────────────────────────

    public record Submit(
            @NotNull UUID trialId,
            @NotNull UUID institutionId,
            @NotBlank String submissionNumber,
            @NotBlank String submissionType,
            @NotBlank String summary,
            UUID protocolDocumentId) {}

    public record Decide(@NotBlank String status, String conditions, LocalDate approvalValidUntil) {}

    public record SubmissionView(
            UUID id,
            UUID trialId,
            UUID institutionId,
            String submissionNumber,
            String submissionType,
            String summary,
            String status,
            LocalDate decisionDate,
            LocalDate approvalValidUntil,
            String conditions,
            UUID submittedBy,
            Instant submittedAt,
            int version) {

        static SubmissionView of(EthicsSubmissionEntity s) {
            return new SubmissionView(
                    s.getId(),
                    s.getTrialId(),
                    s.getInstitutionId(),
                    s.getSubmissionNumber(),
                    s.getSubmissionType(),
                    s.getSummary(),
                    s.getStatus(),
                    s.getDecisionDate(),
                    s.getApprovalValidUntil(),
                    s.getConditions(),
                    s.getSubmittedBy(),
                    s.getSubmittedAt(),
                    s.getVersion());
        }
    }

    public record CreateReview(
            @NotNull UUID ethicsSubmissionId,
            @NotBlank String recommendation,
            @NotBlank String comments) {}

    public record ReviewView(
            UUID id,
            UUID ethicsSubmissionId,
            UUID reviewerId,
            Instant reviewDate,
            String recommendation,
            String comments) {

        static ReviewView of(EthicsReviewEntity r) {
            return new ReviewView(
                    r.getId(),
                    r.getEthicsSubmissionId(),
                    r.getReviewerId(),
                    r.getReviewDate(),
                    r.getRecommendation(),
                    r.getComments());
        }
    }

    // ── submissions ──────────────────────────────────────────────────────────

    @GetMapping("/submissions")
    @PreAuthorize("hasAuthority('ethics:read')")
    @Transactional(readOnly = true)
    public List<SubmissionView> list(
            @RequestParam(required = false) UUID trialId,
            @RequestParam(required = false) UUID institutionId,
            @RequestParam(required = false) String status) {

        List<EthicsSubmissionEntity> found;
        if (trialId != null) {
            found = submissions.findAllByTrialIdOrderBySubmittedAtDesc(trialId);
        } else if (institutionId != null && status != null) {
            found =
                    submissions.findAllByInstitutionIdAndStatusOrderBySubmittedAtDesc(
                            institutionId, status);
        } else if (institutionId != null) {
            found = submissions.findAllByInstitutionIdOrderBySubmittedAtDesc(institutionId);
        } else {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "One of trialId or institutionId is required");
        }
        return found.stream().map(SubmissionView::of).toList();
    }

    @GetMapping("/submissions/{id}")
    @PreAuthorize("hasAuthority('ethics:read')")
    @Transactional(readOnly = true)
    public ResponseEntity<SubmissionView> get(@PathVariable UUID id) {
        EthicsSubmissionEntity submission = submissions.findById(id).orElseThrow(this::notFound);
        return withEtag(submission).body(SubmissionView.of(submission));
    }

    /**
     * The investigator lodges the application.
     *
     * <p>Nothing here checks that the caller is assigned to the trial. That check exists — it is
     * the WITH CHECK on {@code ethics_submissions_submit} — and letting it be the one that fires
     * keeps a single statement of the rule rather than two that can drift apart. An unassigned
     * caller gets 403 from {@code RowLevelSecurityDenialAdvice}.
     */
    @PostMapping("/submissions")
    @PreAuthorize("hasAuthority('ethics:submit')")
    @Transactional
    public ResponseEntity<SubmissionView> submit(
            @Valid @RequestBody Submit request, @AuthenticationPrincipal CurrentUser caller) {

        EthicsSubmissionEntity saved =
                submissions.saveAndFlush(
                        new EthicsSubmissionEntity(
                                UUID.randomUUID(),
                                request.trialId(),
                                request.institutionId(),
                                request.submissionNumber(),
                                request.submissionType(),
                                request.summary(),
                                request.protocolDocumentId(),
                                caller.userId()));

        return ResponseEntity.created(URI.create("/api/v1/ethics/submissions/" + saved.getId()))
                .eTag(etagOf(saved))
                .body(SubmissionView.of(saved));
    }

    /**
     * Records the committee's decision.
     *
     * <p>Restricted to statuses that are decisions: {@code /decision} must not double as a
     * general status setter, or a committee could un-submit an application or retract it on the
     * applicant's behalf.
     */
    @PostMapping("/submissions/{id}/decision")
    @PreAuthorize("hasAuthority('ethics:decide')")
    @Transactional
    public ResponseEntity<SubmissionView> decide(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody Decide request) {

        if (!EthicsDecision.isDecision(request.status())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Not an ethics decision: " + request.status());
        }

        EthicsSubmissionEntity submission = loadForWrite(id, ifMatch);
        submission.decide(request.status(), request.conditions(), request.approvalValidUntil());
        EthicsSubmissionEntity saved = submissions.saveAndFlush(submission);
        return withEtag(saved).body(SubmissionView.of(saved));
    }

    /**
     * The investigator retracts their own application.
     *
     * <p>This is the only status an investigator may write, and the rule lives in the WITH CHECK
     * of {@code ethics_submissions_withdraw} rather than here — it pins the destination status,
     * so the same policy that permits withdrawal is what refuses self-approval.
     */
    @PostMapping("/submissions/{id}/withdraw")
    @PreAuthorize("hasAuthority('ethics:submit')")
    @Transactional
    public ResponseEntity<SubmissionView> withdraw(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        EthicsSubmissionEntity submission = loadForWrite(id, ifMatch);
        submission.withdraw();
        EthicsSubmissionEntity saved = submissions.saveAndFlush(submission);
        return withEtag(saved).body(SubmissionView.of(saved));
    }

    // ── reviews ──────────────────────────────────────────────────────────────

    /**
     * Deliberation content, §5.7.
     *
     * <p>Gated on {@code ethics:review}, which V3 grants to the committee alone. The row-level
     * policy would already return nothing to anyone else; requiring the permission turns that
     * empty list into a 403, which does not disclose any row and is the more truthful answer to
     * a caller who is not entitled to the resource at all.
     */
    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('ethics:review')")
    @Transactional(readOnly = true)
    public List<ReviewView> listReviews(@RequestParam UUID submissionId) {
        return reviews.findAllByEthicsSubmissionIdOrderByReviewDateDesc(submissionId).stream()
                .map(ReviewView::of)
                .toList();
    }

    @PostMapping("/reviews")
    @PreAuthorize("hasAuthority('ethics:review')")
    @Transactional
    public ResponseEntity<ReviewView> review(
            @Valid @RequestBody CreateReview request, @AuthenticationPrincipal CurrentUser caller) {

        // Reading the submission first is the scope check: a member of another institution's
        // committee cannot see it, so they get 404 rather than a policy error mentioning a
        // submission they were never entitled to know about (§6.4).
        EthicsSubmissionEntity submission =
                submissions.findById(request.ethicsSubmissionId()).orElseThrow(this::notFound);

        EthicsReviewEntity saved =
                reviews.saveAndFlush(
                        new EthicsReviewEntity(
                                UUID.randomUUID(),
                                request.ethicsSubmissionId(),
                                caller.userId(),
                                request.recommendation(),
                                request.comments()));

        // A submission somebody has assessed is no longer one nobody has looked at.
        if (EthicsDecision.SUBMITTED.equals(submission.getStatus())) {
            submission.markUnderReview();
            submissions.save(submission);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewView.of(saved));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private EthicsSubmissionEntity loadForWrite(UUID id, String ifMatch) {
        EthicsSubmissionEntity submission = submissions.findById(id).orElseThrow(this::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required; fetch the submission for its current ETag");
        }
        if (!etagOf(submission).equals(ifMatch.trim())) {
            throw new OptimisticLockingFailureException("Submission " + id + " has changed");
        }
        return submission;
    }

    private ResponseStatusException notFound() {
        // §6.4 — out of scope and non-existent are deliberately indistinguishable.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ethics submission not found");
    }

    private static String etagOf(EthicsSubmissionEntity s) {
        return "\"%d\"".formatted(s.getVersion());
    }

    private static ResponseEntity.BodyBuilder withEtag(EthicsSubmissionEntity s) {
        return ResponseEntity.ok().eTag(etagOf(s));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> onStaleWrite() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * {@code ck_ethics_submissions_conditions} is the one that matters here: an approval
     * qualified by conditions that records none is not an auditable decision.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(
                        "The submission violates a data constraint; an approval with conditions"
                                + " must record them");
    }
}
