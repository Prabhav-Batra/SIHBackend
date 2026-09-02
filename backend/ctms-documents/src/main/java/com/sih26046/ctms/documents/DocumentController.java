package com.sih26046.ctms.documents;

import com.sih26046.ctms.security.CurrentUser;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Document upload and metadata (§16). */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentRepository documents;

    public DocumentController(DocumentService service, DocumentRepository documents) {
        this.service = service;
        this.documents = documents;
    }

    /**
     * What the API returns about a document.
     *
     * <p>The storage handle is deliberately absent. A public id plus a resource type is most of
     * what an attacker needs to guess at a delivery URL, and the caller has no use for it —
     * downloads go through the signed-URL endpoint, which re-checks scope on every request.
     */
    public record DocumentView(
            UUID id,
            UUID documentFamilyId,
            UUID trialId,
            UUID institutionId,
            UUID trialSiteId,
            String documentType,
            String title,
            String fileName,
            String mimeType,
            long fileSizeBytes,
            String checksumSha256,
            int version,
            String status,
            String scanStatus,
            Instant scannedAt,
            UUID supersededById,
            UUID uploadedBy,
            Instant uploadedAt,
            LocalDate effectiveDate,
            LocalDate expiryDate) {

        static DocumentView of(DocumentEntity d) {
            return new DocumentView(
                    d.getId(),
                    d.getDocumentFamilyId(),
                    d.getTrialId(),
                    d.getInstitutionId(),
                    d.getTrialSiteId(),
                    d.getDocumentType(),
                    d.getTitle(),
                    d.getFileName(),
                    d.getMimeType(),
                    d.getFileSizeBytes(),
                    d.getChecksumSha256(),
                    d.getVersion(),
                    d.getStatus(),
                    d.getScanStatus(),
                    d.getScannedAt(),
                    d.getSupersededById(),
                    d.getUploadedBy(),
                    d.getUploadedAt(),
                    d.getEffectiveDate(),
                    d.getExpiryDate());
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('document:upload')")
    public ResponseEntity<DocumentView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID trialId,
            @RequestParam(required = false) UUID institutionId,
            @RequestParam(required = false) UUID trialSiteId,
            @RequestParam String documentType,
            @RequestParam String title,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate effectiveDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate expiryDate,
            @AuthenticationPrincipal CurrentUser caller) {

        if (trialId == null && institutionId == null) {
            // An unscoped document is one no policy can place, so it would be invisible to
            // everyone including its uploader.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A document must belong to a trial or an institution");
        }

        DocumentEntity saved =
                service.upload(
                        file,
                        new DocumentService.UploadRequest(
                                trialId,
                                institutionId,
                                trialSiteId,
                                documentType,
                                title,
                                effectiveDate,
                                expiryDate,
                                null),
                        caller.userId());

        return ResponseEntity.created(URI.create("/api/v1/documents/" + saved.getId()))
                .body(DocumentView.of(saved));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public List<DocumentView> list(@RequestParam UUID trialId) {
        return documents.findAllByTrialIdOrderByUploadedAtDesc(trialId).stream()
                .map(DocumentView::of)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('document:read')")
    @Transactional(readOnly = true)
    public DocumentView get(@PathVariable UUID id) {
        return DocumentView.of(documents.findById(id).orElseThrow(DocumentController::notFound));
    }

    private static ResponseStatusException notFound() {
        // §6.4 — out of scope and non-existent are deliberately indistinguishable.
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
    }

    /**
     * A rejected upload is 422, not 400: the request was well-formed and the server understood
     * it perfectly — it is the content that is unacceptable.
     */
    @ExceptionHandler(RejectedUploadException.class)
    public ResponseEntity<String> onRejected(RejectedUploadException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getMessage());
    }
}
