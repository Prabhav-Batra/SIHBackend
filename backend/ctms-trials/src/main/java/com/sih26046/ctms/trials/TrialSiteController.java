package com.sih26046.ctms.trials;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Trial sites (§8.9, §21.2). */
@RestController
@RequestMapping("/api/v1/sites")
@PreAuthorize("hasAuthority('site:read')")
public class TrialSiteController {

    private final TrialSiteRepository sites;

    public TrialSiteController(TrialSiteRepository sites) {
        this.sites = sites;
    }

    public record CreateSite(
            @NotNull UUID trialId,
            @NotNull UUID institutionId,
            @NotBlank String siteCode,
            Integer targetEnrollment) {}

    public record SiteView(
            UUID id,
            UUID trialId,
            UUID institutionId,
            String siteCode,
            String status,
            Integer targetEnrollment,
            int currentEnrollment,
            int version) {

        static SiteView of(TrialSiteEntity s) {
            return new SiteView(
                    s.getId(),
                    s.getTrialId(),
                    s.getInstitutionId(),
                    s.getSiteCode(),
                    s.getStatus(),
                    s.getTargetEnrollment(),
                    s.getCurrentEnrollment(),
                    s.getVersion());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<SiteView> list(@RequestParam UUID trialId) {
        return sites.findAllByTrialIdOrderBySiteCode(trialId).stream().map(SiteView::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('site:create')")
    @Transactional
    public ResponseEntity<SiteView> create(@Valid @RequestBody CreateSite request) {
        TrialSiteEntity site =
                new TrialSiteEntity(
                        UUID.randomUUID(),
                        request.trialId(),
                        request.institutionId(),
                        request.siteCode());
        site.setTargetEnrollment(request.targetEnrollment());

        TrialSiteEntity saved = sites.saveAndFlush(site);
        return ResponseEntity.created(URI.create("/api/v1/sites/" + saved.getId()))
                .eTag("\"%d\"".formatted(saved.getVersion()))
                .body(SiteView.of(saved));
    }

    /**
     * §8.9 constrains a site code to be unique within its trial, and one institution to appear
     * once per trial. Both are the caller's error, not the server's.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation() {
        return ResponseEntity.unprocessableEntity()
                .body("The site violates a uniqueness or integrity constraint");
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Void> onDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
