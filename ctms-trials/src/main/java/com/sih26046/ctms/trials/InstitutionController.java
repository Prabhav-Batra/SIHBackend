package com.sih26046.ctms.trials;

import com.sih26046.ctms.audit.AuditTrail;
import com.sih26046.ctms.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Institutions (§8.7, §21.2). */
@RestController
@RequestMapping("/api/v1/institutions")
@PreAuthorize("hasAuthority('institution:read')")
public class InstitutionController {

    private final InstitutionRepository institutions;
    private final AuditTrail audit;

    public InstitutionController(InstitutionRepository institutions, AuditTrail audit) {
        this.institutions = institutions;
        this.audit = audit;
    }

    public record CreateInstitution(
            @NotBlank String name,
            @NotBlank String institutionType,
            @NotBlank String city,
            @NotBlank String state,
            BigDecimal latitude,
            BigDecimal longitude) {}

    public record UpdateInstitution(
            String name,
            String city,
            String state,
            String addressLine,
            String postalCode,
            BigDecimal latitude,
            BigDecimal longitude) {}

    public record InstitutionView(
            UUID id,
            String name,
            String registrationNumber,
            String institutionType,
            String city,
            String state,
            String country,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean hasEthicsCommittee,
            String status) {

        static InstitutionView of(InstitutionEntity i) {
            return new InstitutionView(
                    i.getId(),
                    i.getName(),
                    i.getRegistrationNumber(),
                    i.getInstitutionType(),
                    i.getCity(),
                    i.getState(),
                    i.getCountry(),
                    i.getLatitude(),
                    i.getLongitude(),
                    i.isHasEthicsCommittee(),
                    i.getStatus());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<InstitutionView> list() {
        return institutions.findAllByOrderByName().stream().map(InstitutionView::of).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<InstitutionView> get(@PathVariable UUID id) {
        InstitutionEntity found = institutions.findById(id).orElseThrow(this::notFound);
        return ResponseEntity.ok().eTag(etagOf(found)).body(InstitutionView.of(found));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('institution:create')")
    @Transactional
    public ResponseEntity<InstitutionView> create(
            @Valid @RequestBody CreateInstitution request,
            @AuthenticationPrincipal CurrentUser caller) {
        InstitutionEntity saved =
                institutions.saveAndFlush(
                        new InstitutionEntity(
                                UUID.randomUUID(),
                                request.name(),
                                request.institutionType(),
                                request.city(),
                                request.state(),
                                request.latitude(),
                                request.longitude()));

        audit.recordChange(
                caller.userId(),
                "CREATE_INSTITUTION",
                "institutions",
                saved.getId(),
                null,
                null,
                valuesOf(saved));

        return ResponseEntity.created(URI.create("/api/v1/institutions/" + saved.getId()))
                .eTag(etagOf(saved))
                .body(InstitutionView.of(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('institution:update')")
    @Transactional
    public ResponseEntity<InstitutionView> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateInstitution request,
            @AuthenticationPrincipal CurrentUser caller) {

        InstitutionEntity found = institutions.findById(id).orElseThrow(this::notFound);
        requireCurrentVersion(found, ifMatch);
        Map<String, Object> before = valuesOf(found);

        found.amend(
                request.name(),
                request.city(),
                request.state(),
                request.addressLine(),
                request.postalCode());
        if (request.latitude() != null || request.longitude() != null) {
            found.moveTo(request.latitude(), request.longitude());
        }

        InstitutionEntity saved = institutions.saveAndFlush(found);
        audit.recordChange(
                caller.userId(), "UPDATE_INSTITUTION", "institutions", saved.getId(), null, before,
                valuesOf(saved));
        return ResponseEntity.ok().eTag(etagOf(saved)).body(InstitutionView.of(saved));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> valuesOf(InstitutionEntity i) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", i.getName());
        values.put("institutionType", i.getInstitutionType());
        values.put("city", i.getCity());
        values.put("state", i.getState());
        values.put("latitude", i.getLatitude());
        values.put("longitude", i.getLongitude());
        values.put("status", i.getStatus());
        return values;
    }

    private void requireCurrentVersion(InstitutionEntity found, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required; fetch the resource for its current ETag");
        }
        if (!etagOf(found).equals(ifMatch.trim())) {
            throw new OptimisticLockingFailureException("Institution " + found.getId()
                    + " has changed");
        }
    }

    private static String etagOf(InstitutionEntity i) {
        // Derived from updated_at rather than a version counter: §8.7 defines no version column
        // for this table. The trigger advances updated_at on every write, which is exactly the
        // property a validator needs.
        return "\"%d\"".formatted(
                Optional.ofNullable(i.getUpdatedAt()).map(Instant::toEpochMilli).orElse(0L));
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Institution not found");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> onStaleWrite() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * A CHECK constraint rejecting the request is the client's error, not the server's.
     *
     * <p>§8.7 refuses half a coordinate and an out-of-range one; both arrive here as an
     * integrity violation and must surface as 422 rather than a 500 that reads like a bug.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onConstraintViolation(DataIntegrityViolationException e) {
        return ResponseEntity.unprocessableEntity()
                .body("The institution violates a data constraint");
    }
}
