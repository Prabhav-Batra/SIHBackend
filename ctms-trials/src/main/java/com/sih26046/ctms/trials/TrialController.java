package com.sih26046.ctms.trials;

import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trials (§21.2).
 *
 * <p>Class-level {@code trial:read} means a route added here inherits it by default, so
 * forgetting an annotation fails closed rather than open (§6.5). Mutating routes narrow it.
 *
 * <p>No route filters by scope. RLS does that in the database, and a caller outside scope gets
 * an empty result rather than a denial — which the not-found below preserves.
 */
@RestController
@RequestMapping("/api/v1/trials")
@PreAuthorize("hasAuthority('trial:read')")
public class TrialController {

    private final TrialService trials;

    public TrialController(TrialService trials) {
        this.trials = trials;
    }

    @GetMapping
    public List<TrialDtos.TrialView> list() {
        return trials.list().stream().map(TrialDtos.TrialView::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrialDtos.TrialView> get(@PathVariable UUID id) {
        TrialEntity trial = trials.find(id).orElseThrow(TrialController::notFound);
        return withEtag(trial).body(TrialDtos.TrialView.of(trial));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('trial:create')")
    public ResponseEntity<TrialDtos.TrialView> create(
            @Valid @RequestBody TrialDtos.CreateTrial request,
            @AuthenticationPrincipal CurrentUser caller) {

        TrialEntity created =
                trials.create(
                        request.protocolNumber(),
                        request.title(),
                        request.sponsorInstitutionId(),
                        request.phase(),
                        request.targetEnrollment(),
                        caller);

        return ResponseEntity.created(URI.create("/api/v1/trials/" + created.getId()))
                .eTag(etagOf(created))
                .body(TrialDtos.TrialView.of(created));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('trial:update')")
    public ResponseEntity<TrialDtos.TrialView> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TrialDtos.UpdateTrial request,
            @AuthenticationPrincipal CurrentUser caller) {

        TrialEntity trial = loadForWrite(id, ifMatch);
        TrialEntity saved =
                trials.update(
                        trial,
                        request.title(),
                        request.shortTitle(),
                        request.therapeuticArea(),
                        caller);
        return withEtag(saved).body(TrialDtos.TrialView.of(saved));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('trial:update')")
    public ResponseEntity<TrialDtos.TrialView> changeStatus(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TrialDtos.ChangeStatus request,
            @AuthenticationPrincipal CurrentUser caller) {

        TrialEntity trial = loadForWrite(id, ifMatch);
        TrialEntity saved = trials.transition(trial, request.status(), caller);
        return withEtag(saved).body(TrialDtos.TrialView.of(saved));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TrialEntity loadForWrite(UUID id, String ifMatch) {
        TrialEntity trial = trials.find(id).orElseThrow(TrialController::notFound);

        if (ifMatch == null || ifMatch.isBlank()) {
            // §21.1 requires If-Match on updates. Rejecting the request is the point: an
            // unconditional write is last-write-wins, which silently discards a colleague's
            // edit rather than reporting the collision.
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required; fetch the resource for its current ETag");
        }
        if (!etagOf(trial).equals(ifMatch.trim())) {
            throw new OptimisticLockingFailureException("Trial " + id + " has changed");
        }
        return trial;
    }

    private static ResponseStatusException notFound() {
        // §6.4 — out of scope and non-existent are indistinguishable on purpose. Telling a
        // caller that a trial exists but is not theirs discloses the trial.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Trial not found");
    }

    private static String etagOf(TrialEntity trial) {
        return "\"%d\"".formatted(trial.getVersion());
    }

    private static ResponseEntity.BodyBuilder withEtag(TrialEntity trial) {
        return ResponseEntity.ok().eTag(etagOf(trial));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> onStaleWrite() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(IllegalTrialTransitionException.class)
    public ResponseEntity<String> onIllegalTransition(IllegalTrialTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
